/*
 * JinRefine SPI · Solon 插件桥（Phase 3 插件装载形态）
 *
 * Solon 的插件契约 {@code org.noear.solon.core.Plugin#start(AppContext)} 不接收 OR 模块对象，
 * 故由装载器在模块初始化窗口内用本桥绑定「当前正在初始化的 Butterfly 模块」；
 * 插件侧在 {@code start()} 内调用 {@link #registerCurrentModule()} 即完成 SPI 注册
 * ——语义等价于原 controller.js 的那行 SpiBootstrap.register(module, null)。
 */

package com.jiongche.refine.spi;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.mit.simile.butterfly.ButterflyModule;

public final class SolonPluginBridge {

    private static final ThreadLocal<ButterflyModule> CURRENT = new ThreadLocal<>();

    /**
     * 由「插件形态」装载器接管的模块名集合。这些模块的 SPI 注册**由插件驱动**，
     * 故 {@code controller.js} 里的 {@code SpiBootstrap.register(...)} 行在插件模式下让位
     * （见 {@link #isDelegated}）—— 既保证单一注册路径，又不改动 JS 与 butterfly/solon 模式。
     */
    private static final Set<String> OWNED = ConcurrentHashMap.newKeySet();

    private SolonPluginBridge() {
    }

    /** 装载器在 {@code createModule} 时登记：该模块的运行期注册交由插件驱动。 */
    public static void markOwned(String moduleName) {
        if (moduleName != null) {
            OWNED.add(moduleName);
        }
    }

    /** 装载器在 {@code unload} 时清理登记。 */
    public static void clearOwned() {
        OWNED.clear();
    }

    /**
     * 该模块的 SPI 注册是否应由插件（而非 {@code controller.js}）驱动。
     * 仅在「模块已被插件接管」且「当前不在插件生命周期窗口内」时为 true ——
     * 即忽略 JS 侧那次调用，留给 {@link #registerCurrentModule()}。
     */
    public static boolean isDelegated(ButterflyModule module) {
        return module != null && OWNED.contains(module.getName()) && CURRENT.get() != module;
    }

    /** 装载器在驱动插件生命周期前置入当前模块。 */
    public static void bindCurrent(ButterflyModule module) {
        CURRENT.set(module);
    }

    /** 装载器在插件生命周期结束后清除绑定。 */
    public static void clearCurrent() {
        CURRENT.remove();
    }

    /** 当前绑定的 OR 模块；未绑定（非插件装载窗口）时为 {@code null}。 */
    public static ButterflyModule currentModule() {
        return CURRENT.get();
    }

    /**
     * 插件 {@code start()} 内调用：注册当前模块的 SPI。SPI 实现类名取自
     * {@code MOD-INF/module.properties} 的 {@code refine.spi}；实例化在调用线程的
     * 上下文类加载器（= 该插件自己的 CL）内完成。
     */
    public static void registerCurrentModule() {
        ButterflyModule module = CURRENT.get();
        if (module == null) {
            throw new IllegalStateException(
                    "SolonPluginBridge.registerCurrentModule() 必须在装载器绑定的模块初始化窗口内调用");
        }
        SpiBootstrap.register(module, null);
    }
}
