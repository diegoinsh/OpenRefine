/*
 * JinRefine 容器改造 · 装载器接缝 · 缺省实现
 *
 * 把「现状（Butterfly 自己装载）」显式化为一个全 no-op 的装载器：
 * owns() 恒 false → 恒走 super.createModule；handle() 恒 false → 恒走 super.service。
 * 因此缺省路径与改造前等价。
 */

package com.jiongche.refine.loader;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import edu.mit.simile.butterfly.ButterflyModule;

import com.google.refine.RefineServlet;

public final class ButterflyModuleLoader implements RefineModuleLoader {

    public static final ButterflyModuleLoader INSTANCE = new ButterflyModuleLoader();

    private ButterflyModuleLoader() {
    }

    @Override
    public String id() {
        return "butterfly";
    }

    @Override
    public void preConfigure(RefineServlet servlet) {
        // no-op：交由 Butterfly 自身发现与装载
    }

    @Override
    public void afterConfigure(RefineServlet servlet) {
        // no-op
    }

    @Override
    public boolean owns(String moduleName) {
        return false;
    }

    @Override
    public ClassLoader classLoaderFor(String moduleName) {
        return null;
    }

    @Override
    public ButterflyModule createModule(RefineServlet servlet, String moduleName) {
        throw new IllegalStateException("ButterflyModuleLoader never creates modules");
    }

    @Override
    public boolean handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        return false;
    }

    @Override
    public void unload(RefineServlet servlet) {
        // no-op
    }
}
