/*
 * JinRefine 容器改造 · 装载器接缝（Phase 3 POC）
 *
 * 把「模块发现 + ClassLoader 提供 + 模块对象创建 + 请求路径」收敛为一个可替换接缝，
 * 与 Butterfly 的装配流程（wireModules / configureModules / initializeModule）并存。
 * 缺省实现 ButterflyModuleLoader 全 no-op，故缺省行为与改造前逐字节一致。
 */

package com.jiongche.refine.loader;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import edu.mit.simile.butterfly.ButterflyModule;

import com.google.refine.RefineServlet;

/**
 * 装载器接缝。由 {@link com.google.refine.RefineServlet} 在 {@code configure()} 时按开关解析。
 */
public interface RefineModuleLoader {

    /** 装载器标识，用于日志与开关值（"butterfly" | "solon"）。 */
    String id();

    /** {@code super.configure()} 之前调用：计算/记录归属集合。 */
    void preConfigure(RefineServlet servlet);

    /** {@code super.configure()} 之后调用：断言/日志。 */
    void afterConfigure(RefineServlet servlet);

    /** 该模块是否由本装载器接管（为 true 时 {@link #createModule} 会被调用）。 */
    boolean owns(String moduleName);

    /**
     * 该模块的类加载器（供 {@code RefineServlet} 在模块初始化时置换线程上下文 CL）；
     * 未接管则返回 {@code null}。
     */
    ClassLoader classLoaderFor(String moduleName);

    /** 创建模块对象（仅当 {@link #owns} 为 true 时调用）。 */
    ButterflyModule createModule(RefineServlet servlet, String moduleName);

    /** 请求路径接缝：返回 true 表示已处理（本 POC 恒 false，保留落点）。 */
    boolean handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    /** 卸载：反注册 + 关闭类加载器。 */
    void unload(RefineServlet servlet);
}
