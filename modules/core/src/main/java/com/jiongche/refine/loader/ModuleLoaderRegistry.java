/*
 * JinRefine 容器改造 · 装载器开关解析
 */

package com.jiongche.refine.loader;

/**
 * 按开关解析装载器实现。取值优先级：
 * <ol>
 * <li>servlet init-param {@code refine.module.loader}（为容器侧留口）；</li>
 * <li>JVM 系统属性 {@code -Drefine.module.loader}；</li>
 * <li>缺省 {@code "butterfly"}（现状）。</li>
 * </ol>
 * 取值 {@code "solon"} → {@link StandaloneModuleLoader}；其余 → {@link ButterflyModuleLoader}（秒级回退）。
 */
public final class ModuleLoaderRegistry {

    public static final String INIT_PARAM = "refine.module.loader";
    public static final String SYS_PROPERTY = "refine.module.loader";
    public static final String LOADER_BUTTERFLY = "butterfly";
    public static final String LOADER_SOLON = "solon";

    private ModuleLoaderRegistry() {
    }

    public static RefineModuleLoader resolve(String initParamValue) {
        String value = initParamValue;
        if (value == null || value.isBlank()) {
            value = System.getProperty(SYS_PROPERTY, LOADER_BUTTERFLY);
        }
        if (LOADER_SOLON.equalsIgnoreCase(value.trim())) {
            return StandaloneModuleLoader.INSTANCE;
        }
        return ButterflyModuleLoader.INSTANCE;
    }
}
