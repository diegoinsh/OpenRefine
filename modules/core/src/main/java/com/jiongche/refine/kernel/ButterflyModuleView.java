/*
 * JinRefine 容器改造 · Butterfly 模块适配器
 *
 * 过渡期把现有 ButterflyModule 包装为 ModuleView；A7 删除 Butterfly 后本类一并移除。
 */

package com.jiongche.refine.kernel;

import java.io.File;
import java.net.URL;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import edu.mit.simile.butterfly.ButterflyModule;
import edu.mit.simile.butterfly.MountPoint;

public final class ButterflyModuleView implements RefineModule {

    private final ButterflyModule module;
    private volatile ModuleDescriptor descriptor;

    public ButterflyModuleView(ButterflyModule module) {
        if (module == null) {
            throw new IllegalArgumentException("module must not be null");
        }
        this.module = module;
    }

    public ButterflyModule unwrap() {
        return module;
    }

    @Override
    public ModuleDescriptor descriptor() {
        ModuleDescriptor result = descriptor;
        if (result == null) {
            synchronized (this) {
                result = descriptor;
                if (result == null) {
                    result = ModuleDescriptorReader.read(module.getPath());
                    descriptor = result;
                }
            }
        }
        return result;
    }

    @Override
    public String getName() {
        return module.getName();
    }

    @Override
    public File getPath() {
        return module.getPath();
    }

    @Override
    public ClassLoader getClassLoader() {
        return module.getClass().getClassLoader();
    }

    @Override
    public URL getResource(String path) {
        return module.getResource(path);
    }

    @Override
    public String getMountPoint() {
        MountPoint mountPoint = module.getMountPoint();
        return mountPoint == null ? null : mountPoint.getMountPoint();
    }

    @Override
    public void init(ServletConfig config) throws Exception {
        module.init(config);
    }

    @Override
    public boolean process(String relativePath, HttpServletRequest request, HttpServletResponse response) throws Exception {
        return module.process(relativePath, request, response);
    }

    @Override
    public void destroy() throws Exception {
        module.destroy();
    }
}
