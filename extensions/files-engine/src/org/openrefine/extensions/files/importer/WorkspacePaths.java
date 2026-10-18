package org.openrefine.extensions.files.importer;

import java.io.File;
import java.util.function.Supplier;

/**
 * 文件工具的运行期宿主目录（workspace）注入点。
 *
 * <p>下沉模块不依赖 OpenRefine core，无法直接读 {@code FileProjectManager}。OR 侧在模块注册时
 * 注入一个获取 workspace 的函数（延迟求值，避免启动顺序问题）；未注入时返回 {@code null}，
 * 与改造前「定位不到 workspace 就静默不缓存 / 不落盘」的行为一致。
 */
public final class WorkspacePaths {

    private static volatile Supplier<File> workspaceSupplier;

    private WorkspacePaths() {
    }

    /** 注入 workspace 获取函数；传 {@code null} 可撤销注入（测试用）。 */
    public static void configure(Supplier<File> supplier) {
        workspaceSupplier = supplier;
    }

    /** 当前 workspace 目录；未注入或宿主不可用时返回 {@code null}。 */
    public static File workspaceDir() {
        Supplier<File> supplier = workspaceSupplier;
        return supplier == null ? null : supplier.get();
    }
}
