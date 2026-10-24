/*
 * JinRefine · 数据源 SPI 注册表（进程内全局目录）
 *
 * 与 OR 既有注册表（OperationRegistry / ExporterRegistry / ImportingManager）同形：
 * 静态、全局、进程内。业务扩展经 com.jiongche.refine.spi.RefineModuleContext#registerDataSource
 * 登记；外部（能力平台 3A）在进程内按名取用 / 枚举。
 */

package com.jiongche.refine.data;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.jiongche.sdk.data.DataSource;

/**
 * 数据源注册表（SPI registry 的数据源面）。
 *
 * <p>key 规则：业务扩展经 {@link com.jiongche.refine.spi.RefineModuleContext#registerDataSource}
 * 登记时以模块命名空间拼接，key = {@code module + "/" + name}（与 commands/operations 等注册面一致）；
 * core 内建数据源以无命名空间的稳定名 {@link #OR} 登记，供外部直接取用。</p>
 *
 * <p>同名以最后一次为准（overwrite）。{@code OperationRegistry.registerOperation} 是追加语义、
 * 重复登记会产生重复条目，故注册方（如 {@link OrDataSource#register()}）须自行做幂等守卫。</p>
 */
public final class DataSourceRegistry {

    /** OR core 内建数据源（包住 {@code ProjectManager}）的稳定名。 */
    public static final String OR = "or";

    private static final Map<String, DataSource> REGISTRY = new ConcurrentHashMap<String, DataSource>();

    private DataSourceRegistry() {
    }

    /**
     * 以命名空间登记（key = {@code namespace + "/" + name}）。
     *
     * @param namespace 模块命名空间；空/空白则退化为无命名空间（同 {@link #register(String, DataSource)}）
     * @param name      数据源名，非空
     * @param ds        数据源实现，非空
     */
    public static void register(String namespace, String name, DataSource ds) {
        require(ds, name);
        String key = (namespace == null || namespace.isBlank()) ? name : namespace + "/" + name;
        REGISTRY.put(key, ds);
    }

    /** 以无命名空间的稳定名登记（用于 core 内建数据源）。 */
    public static void register(String name, DataSource ds) {
        register(null, name, ds);
    }

    /** 按 key 取数据源；未登记返回 {@code null}。 */
    public static DataSource get(String key) {
        return key == null ? null : REGISTRY.get(key);
    }

    /** 是否已登记该 key。 */
    public static boolean contains(String key) {
        return key != null && REGISTRY.containsKey(key);
    }

    /** 已登记 key 的快照（不可变，按插入序不可保证——底层为 ConcurrentHashMap）。 */
    public static Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(REGISTRY.keySet()));
    }

    /** OR 内建数据源；未登记返回 {@code null}。 */
    public static DataSource orDefault() {
        return get(OR);
    }

    private static void require(DataSource ds, String name) {
        if (ds == null) {
            throw new IllegalArgumentException("dataSource 不得为 null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据源名不得为空");
        }
    }
}
