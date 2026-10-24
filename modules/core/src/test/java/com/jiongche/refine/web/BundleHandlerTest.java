/*
 * JinRefine · -bundle.js 拼接字节契约
 */

package com.jiongche.refine.web;

import static org.testng.Assert.assertEquals;

import java.io.File;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import com.jiongche.refine.kernel.ModuleView;
import org.testng.annotations.Test;

import com.google.refine.ClientSideResourceManager;

public class BundleHandlerTest {

    private static ModuleView module(String name, String mount, Map<String, URL> resources) {
        return new ModuleView() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public File getPath() {
                return new File(name);
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }

            @Override
            public URL getResource(String path) {
                return resources.get(path);
            }

            @Override
            public String getMountPoint() {
                return mount;
            }
        };
    }

    private static URL resource(String content) throws Exception {
        File file = File.createTempFile("bundle-", ".js");
        file.deleteOnExit();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file.toURI().toURL();
    }

    private static String bundleName() {
        return "test-bundle-" + System.nanoTime();
    }

    @Test
    public void concatenatesRegisteredResourcesByteForByte() throws Exception {
        String bundle = bundleName();
        Map<String, URL> resources = new HashMap<>();
        resources.put("/scripts/a.js", resource("var a = 1;\n"));
        resources.put("scripts/b.js", resource("var b = '中文';\n"));
        ClientSideResourceManager.addPaths(bundle, module("core", "/", resources),
                new String[] { "/scripts/a.js", "scripts/b.js" });

        StringWriter out = new StringWriter();
        BundleHandler.write(bundle, out);

        String expected = "/* ===== /scripts/a.js ===== */\n\nvar a = 1;\n\n\n"
                + "/* ===== /scripts/b.js ===== */\n\nvar b = '中文';\n\n\n";
        assertEquals(out.toString(), expected);
        assertEquals(out.toString().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void skipsResourcesThatCannotBeResolved() throws Exception {
        String bundle = bundleName();
        Map<String, URL> resources = new HashMap<>();
        resources.put("scripts/present.js", resource("var present = true;\n"));
        ClientSideResourceManager.addPaths(bundle, module("files", "/extension/files/", resources),
                new String[] { "scripts/missing.js", "scripts/present.js" });

        StringWriter out = new StringWriter();
        BundleHandler.write(bundle, out);

        assertEquals(out.toString(),
                "/* ===== /extension/files/scripts/present.js ===== */\n\nvar present = true;\n\n\n");
    }

    @Test
    public void writesNothingForUnknownBundle() throws Exception {
        StringWriter out = new StringWriter();
        BundleHandler.write(bundleName(), out);
        assertEquals(out.toString(), "");
    }
}
