package com.jiongche.refine.kernel;

import java.io.File;
import java.net.URL;
import java.util.Collections;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ModuleRegistryTest {

    private static RefineModule module(String name, String mount) {
        return new RefineModule() {
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
                return null;
            }

            @Override
            public String getMountPoint() {
                return mount;
            }

            @Override
            public ModuleDescriptor descriptor() {
                return new ModuleDescriptor(name, getPath(), null,
                        "core".equals(name) ? Collections.singletonList("core") : Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                        null, null, null);
            }

            @Override
            public void init(javax.servlet.ServletConfig config) {
            }

            @Override
            public boolean process(String relativePath, javax.servlet.http.HttpServletRequest request,
                    javax.servlet.http.HttpServletResponse response) {
                return false;
            }

            @Override
            public void destroy() {
            }
        };
    }

    @Test
    public void findsLongestMountPrefix() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("core", "/"));
        registry.register(module("files", "/extension/files/"));
        registry.computeOrder();

        Assert.assertEquals(registry.findByPath("/extension/files/scripts/a.js").getName(), "files");
        Assert.assertEquals(registry.findByPath("/about.html").getName(), "core");
        Assert.assertEquals(registry.relativePath("/extension/files/scripts/a.js",
                registry.findByPath("/extension/files/scripts/a.js")), "scripts/a.js");
        Assert.assertEquals(registry.relativePath("/", registry.findByPath("/")), "");
    }
}
