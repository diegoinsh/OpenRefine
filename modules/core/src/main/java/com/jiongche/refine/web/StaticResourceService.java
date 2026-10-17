/*
 * JinRefine 容器改造 · 静态资源服务
 *
 * 复用 ModuleView.getResource（Butterfly 的实现已做目录归一化与越界校验），
 * 只补 MIME、目录 index 与 MOD-INF 拒绝；响应字节与 Butterfly sendText 等价。
 */

package com.jiongche.refine.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Locale;

import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.kernel.RefineModule;

public final class StaticResourceService {

    private StaticResourceService() {
    }

    public static boolean serve(RefineModule module, String relativePath, HttpServletResponse response)
            throws IOException {
        String path = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (containsModInf(path)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        if (path.isEmpty() || path.endsWith("/")) {
            path = path + "index.html";
        }

        URL url = module.getResource(path);
        if (url == null) {
            return false;
        }

        String mime = mimeOf(path);
        response.setContentType(mime);
        if (isText(mime)) {
            response.setCharacterEncoding("UTF-8");
        }
        try (InputStream in = url.openStream(); OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return true;
    }

    private static boolean containsModInf(String path) {
        for (String segment : path.split("/")) {
            if ("MOD-INF".equalsIgnoreCase(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("javascript") || mime.contains("xml");
    }

    static String mimeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "js":
                return "text/javascript";
            case "css":
            case "less":
                return "text/css";
            case "html":
            case "htm":
                return "text/html";
            case "xml":
                return "application/xml";
            case "svg":
                return "image/svg+xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "json":
                return "application/json";
            case "txt":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }
}
