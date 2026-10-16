/*
 * JinRefine SPI · 桥入口
 *
 * 供 controller.js（Rhino）一行调用：把模块的注册逻辑委托给其 SPI 实现类。
 */

package com.jiongche.refine.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.mit.simile.butterfly.ButterflyModule;

/**
 * SPI 桥入口。装载器（Butterfly）在模块初始化时调用：
 *
 * <pre>
 * Packages.com.jiongche.refine.spi.SpiBootstrap.register(module, "com.jiongche.xxx.XxxSpi");
 * </pre>
 *
 * <p>注意：{@link RefineModuleSpi} 实现类位于扩展自身的 MOD-INF/classes（或 lib），
 * 由装载器（Butterfly）的类加载器加载；本类在 core，故必须显式用「能看见扩展类」的类加载器
 * 反射实例化，否则在父类加载器（parent-first）下会 ClassNotFoundException。</p>
 */
public final class SpiBootstrap {

    private static final Logger logger = LoggerFactory.getLogger("refine-spi");

    private SpiBootstrap() {
    }

    /**
     * 以 {@link ButterflyModule} 为门面，实例化并注册其 SPI 实现。
     *
     * @param module      被装载的 Butterfly 模块
     * @param spiClassName 业务扩展的 {@link RefineModuleSpi} 实现类全限定名
     * @return 传给 SPI 的注册门面（供装载器后续复用）
     */
    public static RefineModuleContext register(ButterflyModule module, String spiClassName) {
        RefineModuleContext ctx = new ButterflyModuleContext(module);
        try {
            Class<?> klass = resolveSpiClass(spiClassName, module);
            Object instance = klass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof RefineModuleSpi)) {
                throw new IllegalStateException(spiClassName + " 未实现 " + RefineModuleSpi.class.getName());
            }
            RefineModuleSpi spi = (RefineModuleSpi) instance;
            spi.register(ctx);
            logger.info("SPI registered: module={} spi={}", module.getName(), spiClassName);
            return ctx;
        } catch (Throwable t) {
            logger.error("SPI bootstrap failed: module={} spi={}", module.getName(), spiClassName, t);
            throw new RuntimeException("SPI bootstrap failed for " + spiClassName + " (module " + module.getName() + ")",
                    t);
        }
    }

    /**
     * 按优先级尝试若干类加载器定位 SPI 实现类：
     * <ol>
     * <li>线程上下文类加载器 —— Butterfly 会把它设为共享的 ButterflyClassLoader，
     * 该加载器 watch 了所有模块的 MOD-INF/classes 与 lib；</li>
     * <li>装载 module 对象的类加载器；</li>
     * <li>本类（core）自己的类加载器。</li>
     * </ol>
     */
    private static Class<?> resolveSpiClass(String className, ButterflyModule module) throws ClassNotFoundException {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleCl = module.getClass().getClassLoader();
        ClassLoader coreCl = SpiBootstrap.class.getClassLoader();

        ClassNotFoundException last = null;
        for (ClassLoader cl : new ClassLoader[] { ctx, moduleCl, coreCl }) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(className, true, cl);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ClassNotFoundException(className);
    }
}
