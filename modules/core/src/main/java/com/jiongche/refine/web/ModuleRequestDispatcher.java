/*
 * JinRefine 容器改造 · 模块请求分发
 *
 * 非 /command 请求统一在此分发：
 *   wirings.js → WiringsHandler
 *   *-bundle.js → BundleHandler
 *   core 的 templatedFiles → TemplateService
 *   其余 → module.process()，未处理再走静态资源
 *
 * 顺序上 module.process 必须先于静态资源，保证扩展自定义端点不被静态命中。
 */

package com.jiongche.refine.web;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.kernel.ModuleRegistry;
import com.jiongche.refine.kernel.RefineModule;

public final class ModuleRequestDispatcher {

    private final ModuleRegistry registry;

    public ModuleRequestDispatcher(ModuleRegistry registry) {
        this.registry = registry;
    }

    public boolean handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        if ("/wirings.js".equals(path)) {
            WiringsHandler.write(registry, response);
            return true;
        }

        if (path.endsWith("-bundle.js")) {
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            String bundle = lastSegment.substring(0, lastSegment.length() - "-bundle.js".length());
            response.setContentType("text/javascript");
            response.setCharacterEncoding("UTF-8");
            BundleHandler.write(bundle + "/scripts", response.getWriter());
            return true;
        }

        RefineModule module = registry.findByPath(path);
        if (module == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return true;
        }

        String relative = registry.relativePath(path, module);
        String normalized = relative;
        if (normalized.isEmpty() || "/".equals(normalized)) {
            normalized = "index";
        } else if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String lastSegment = normalized.substring(normalized.lastIndexOf('/') + 1);

        if (module.descriptor().isCore() && TemplateService.isTemplated(lastSegment)) {
            if (TemplateService.render(module, lastSegment, normalized, request, response)) {
                return true;
            }
        }

        try {
            if (module.process(relative, request, response)) {
                return true;
            }
        } catch (Exception e) {
            throw new IOException("Module request processing failed: " + module.getName() + " " + relative, e);
        }

        if (StaticResourceService.serve(module, relative, response)) {
            return true;
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return true;
    }
}
