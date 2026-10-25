/*
 * JinRefine 容器改造 · 模块基类
 *
 * ButterflyModuleImpl 的框架无关替代：持有描述符、目录、类加载器、挂载前缀、
 * 继承链与 Rhino 控制器宿主；子类可覆盖 init/destroy/process。
 */

package com.jiongche.refine.kernel;

import java.io.File;
import java.net.URL;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbstractRefineModule implements RefineModule {

    private static final Logger logger = LoggerFactory.getLogger("refine-kernel");

    private String name;
    private File path;
    private String mountPoint;
    private ModuleDescriptor descriptor;
    private ClassLoader classLoader;
    private ModuleRegistry registry;
    private AbstractRefineModule extended;
    private RhinoControllerHost controllerHost;

    public void setName(String name) {
        this.name = name;
    }

    public void setPath(File path) {
        this.path = path;
    }

    public void setMountPoint(String mountPoint) {
        this.mountPoint = mountPoint;
    }

    public void setDescriptor(ModuleDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setRegistry(ModuleRegistry registry) {
        this.registry = registry;
    }

    public void setExtended(AbstractRefineModule extended) {
        this.extended = extended;
    }

    public AbstractRefineModule getExtended() {
        return extended;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public File getPath() {
        return path;
    }

    @Override
    public String getMountPoint() {
        return mountPoint;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public URL getResource(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String relative = resourcePath.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }

        if (relative.startsWith("@@")) {
            int end = relative.indexOf("@@", 2);
            if (end > 0) {
                String target = relative.substring(2, end);
                String rest = relative.substring(end + 2);
                RefineModule targetModule = registry == null ? null : registry.get(target);
                return targetModule == null ? null : targetModule.getResource(rest);
            }
        }

        if (relative.isEmpty()) {
            return toUrl(path);
        }

        try {
            File base = path.getCanonicalFile();
            File candidate = new File(base, relative).getCanonicalFile();
            if (!candidate.toPath().startsWith(base.toPath())) {
                logger.warn("Rejected resource path outside module {}: {}", name, resourcePath);
                return null;
            }
            if (candidate.exists()) {
                return candidate.toURI().toURL();
            }
        } catch (Exception e) {
            logger.warn("Cannot resolve resource {} in module {}", resourcePath, name, e);
        }

        if (extended != null) {
            URL fromExtended = extended.getResource(relative);
            if (fromExtended != null) {
                return fromExtended;
            }
        }
        if (classLoader != null) {
            return classLoader.getResource(relative);
        }
        return null;
    }

    private static URL toUrl(File file) {
        try {
            return file == null ? null : file.toURI().toURL();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void init(ServletConfig config) throws Exception {
        controllerHost = new RhinoControllerHost(this, registry);
        controllerHost.init();
    }

    @Override
    public boolean process(String relativePath, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        return controllerHost != null && controllerHost.process(relativePath, request, response);
    }

    @Override
    public void destroy() throws Exception {
        if (controllerHost != null) {
            controllerHost.destroy();
            controllerHost = null;
        }
    }
}
