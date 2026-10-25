/*
 * JinRefine 容器改造 · -bundle.js 拼接
 *
 * 与 core controller.js 的拼接逻辑逐字节等价：
 *   分隔注释（fullPath） + 两行换行 + 资源原文 + 两行换行
 * 资源顺序仍由 ClientSideResourceManager 的注册顺序决定。
 */

package com.jiongche.refine.web;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.refine.ClientSideResourceManager;
import com.google.refine.ClientSideResourceManager.QualifiedPath;

public final class BundleHandler {

    private BundleHandler() {
    }

    public static void write(String bundleName, Writer out) throws IOException {
        QualifiedPath[] paths = ClientSideResourceManager.getPaths(bundleName);
        for (QualifiedPath qualifiedPath : paths) {
            URL url = qualifiedPath.module.getResource(qualifiedPath.path);
            if (url == null) {
                continue;
            }
            out.write("/* ===== ");
            out.write(qualifiedPath.fullPath);
            out.write(" ===== */\n\n");
            copy(url, out);
            out.write("\n\n");
        }
    }

    private static void copy(URL url, Writer out) throws IOException {
        char[] buffer = new char[8192];
        try (Reader reader = new java.io.InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
