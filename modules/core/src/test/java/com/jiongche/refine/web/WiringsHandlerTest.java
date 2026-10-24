/*
 * JinRefine · wirings.js 输出契约
 */

package com.jiongche.refine.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.kernel.ModuleDescriptor;
import com.jiongche.refine.kernel.ModuleRegistry;
import com.jiongche.refine.kernel.RefineModule;
import org.testng.annotations.Test;

public class WiringsHandlerTest {

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
                return null;
            }

            @Override
            public void init(ServletConfig config) {
            }

            @Override
            public boolean process(String relativePath, HttpServletRequest request,
                    HttpServletResponse response) {
                return false;
            }

            @Override
            public void destroy() {
            }
        };
    }

    private static StringWriter write(ModuleRegistry registry) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter buffer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(buffer));
        WiringsHandler.write(registry, response);
        verify(response).setContentType("text/javascript");
        verify(response).setCharacterEncoding("UTF-8");
        return buffer;
    }

    @Test
    public void writesMountMapInRegistrationOrder() throws Exception {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("core", "/"));
        registry.register(module("files", "/extension/files/"));

        assertEquals(write(registry).toString(),
                "var ModuleWirings = {\"core\":\"/\",\"files\":\"/extension/files/\"};");
    }

    @Test
    public void writesEmptyObjectWithoutModules() throws Exception {
        assertEquals(write(new ModuleRegistry()).toString(), "var ModuleWirings = {};");
    }

    @Test
    public void omitsModulesWithoutMountPoint() throws Exception {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("core", "/"));
        registry.register(module("detached", null));

        assertEquals(write(registry).toString(), "var ModuleWirings = {\"core\":\"/\"};");
    }
}
