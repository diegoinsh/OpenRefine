/*
 * JinRefine SPI · 桥入口
 *
 * 供 controller.js（Rhino）一行调用：把模块的注册逻辑委托给其 SPI 实现类。
 *
 * SPI 类名来源（单一事实来源）：模块 MOD-INF/module.properties 的 refine.spi；
 * 显式传入的非空类名优先；均无时回退 ServiceLoader（META-INF/services）。
 */

package com.jiongche.refine.spi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.ServiceLoader;

import com.jiongche.refine.kernel.ModuleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI 桥入口。装载器（Butterfly 或 Standalone）在模块初始化时调用：
 *
 * <pre>
 * Packages.com.jiongche.refine.spi.SpiBootstrap.register(module, null);
 * </pre>
 *
 * <p>注意：{@link RefineModuleSpi} 实现类位于扩展自身的 MOD-INF/classes（或 lib），
 * 由装载器的类加载器加载；本类在 core，故必须显式用「能看见扩展类」的类加载器
 * 反射实例化，否则在父类加载器（parent-first）下会 ClassNotFoundException。</p>
 */
public final class SpiBootstrap {

    /** module.properties 中声明 SPI 实现类的键（与装载器归属判定共用，保证单一事实来源）。 */
    public static final String SPI_PROPERTY = "refine.spi";

    private static final Logger logger = LoggerFactory.getLogger("refine-spi");

    private SpiBootstrap() {
    }

    /**
     * 以 {@link ModuleView} 为门面注册 SPI。
     */
    public static RefineModuleContext register(ModuleView module, String spiClassName) {
        RefineModuleContext ctx = new DefaultModuleContext(module);
        try {
            // 插件形态：该模块的注册已交由 Solon 插件的 Plugin.start 驱动，
            // 此处忽略 controller.js 那次调用（保证单一注册路径，避免命令重复注册）。
            if (SolonPluginBridge.isDelegated(module)) {
                logger.info("SPI registration delegated to Solon plugin (skip JS-side call): module={}",
                        module.getName());
                return ctx;
            }
            String fqcn = (spiClassName != null && !spiClassName.isBlank())
                    ? spiClassName
                    : readModuleProperty(module.getPath(), SPI_PROPERTY);

            RefineModuleSpi spi;
            if (fqcn != null && !fqcn.isBlank()) {
                Object instance = resolveSpiClass(fqcn, module).getDeclaredConstructor().newInstance();
                if (!(instance instanceof RefineModuleSpi)) {
                    throw new IllegalStateException(fqcn + " 未实现 " + RefineModuleSpi.class.getName());
                }
                spi = (RefineModuleSpi) instance;
            } else {
                spi = firstServiceProvider();
                if (spi == null) {
                    logger.warn("No SPI declared for module {} (no refine.spi descriptor, no META-INF/services)",
                            module.getName());
                    return ctx;
                }
            }

            spi.register(ctx);
            logger.info("SPI registered: module={} spi={}", module.getName(), spi.getClass().getName());
            return ctx;
        } catch (Throwable t) {
            logger.error("SPI bootstrap failed: module={} spi={}", module.getName(), spiClassName, t);
            throw new RuntimeException("SPI bootstrap failed for module " + module.getName(), t);
        }
    }

    /**
     * 从模块的 {@code MOD-INF/module.properties} 读取某键值。
     *
     * <p>不能用 {@code module.getProperties()}：Butterfly 的 {@code configureModules()}
     * 会把模块属性覆盖为 Butterfly 的全局属性（{@code _properties}），
     * 导致读不到模块自身声明的键。</p>
     */
    public static String readModuleProperty(File modulePath, String key) {
        if (modulePath == null) {
            return null;
        }
        File propsFile = new File(modulePath, "MOD-INF/module.properties");
        if (!propsFile.isFile()) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("Cannot read {}", propsFile, e);
            return null;
        }
        String value = props.getProperty(key);
        return value == null ? null : value.trim();
    }

    /** ServiceLoader 回退：取首个 {@link RefineModuleSpi} 提供者；空则返回 null（仅告警）。 */
    private static RefineModuleSpi firstServiceProvider() {
        try {
            ServiceLoader<RefineModuleSpi> loader = ServiceLoader.load(RefineModuleSpi.class,
                    Thread.currentThread().getContextClassLoader());
            for (RefineModuleSpi provider : loader) {
                return provider;
            }
        } catch (Throwable t) {
            logger.warn("ServiceLoader lookup for {} failed", RefineModuleSpi.class.getName(), t);
        }
        return null;
    }

    /**
     * 按优先级尝试若干类加载器定位 SPI 实现类：
     * <ol>
     * <li>线程上下文类加载器 —— 装载器会在模块初始化前置换成该模块的类加载器；</li>
     * <li>装载 module 对象的类加载器；</li>
     * <li>本类（core）自己的类加载器。</li>
     * </ol>
     */
    private static Class<?> resolveSpiClass(String className, ModuleView module) throws ClassNotFoundException {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleCl = module.getClassLoader();
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
