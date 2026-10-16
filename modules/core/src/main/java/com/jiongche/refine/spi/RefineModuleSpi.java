/*
 * JinRefine SPI · 业务扩展实现契约
 */

package com.jiongche.refine.spi;

/**
 * 业务扩展实现本接口，把原本写在 {@code controller.js} 里的注册逻辑收敛到 Java 侧。
 *
 * <p>装载器通过 {@link SpiBootstrap#register} 实例化并调用 {@link #register(RefineModuleContext)}。
 * 实现类需编译进该扩展的 MOD-INF/classes（或 lib），由扩展的类加载器加载。</p>
 */
public interface RefineModuleSpi {

    /** 模块命名空间，须与装载器中的 module 名一致（用于 key 拼接）。 */
    String getName();

    /** 执行注册。 */
    void register(RefineModuleContext ctx);

    /** 卸载回调，默认空实现。 */
    default void destroy() {
    }
}
