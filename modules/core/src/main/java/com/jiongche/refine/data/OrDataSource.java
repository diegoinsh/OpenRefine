/*
 * JinRefine · OR 数据源适配器（P0）
 */

package com.jiongche.refine.data;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jiongche.sdk.data.DataSource;
import com.jiongche.sdk.data.TableDriver;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.model.Project;
import com.google.refine.operations.OperationRegistry;

/**
 * {@link DataSource} 的 OR 实现：把 OR 的 {@link ProjectManager}（库/编目）以窄接口暴露。
 *
 * <p>由 {@link #register()} 登记：把本适配器引入的两个变更操作注册进 {@link OperationRegistry}
 * （命名空间 {@code data}，使 history JSON 里的 {@code op=data/set-cell-value} 等能反解），
 * 并把本适配器自身登记进 {@link DataSourceRegistry}（稳定名 {@link DataSourceRegistry#OR}，
 * 供 SPI 能力面 / 外部进程内按名取用）。注册由 {@code RefineServlet.init()} 驱动，幂等。</p>
 */
public class OrDataSource implements DataSource {

    /** 变更操作在 {@link OperationRegistry} 里的命名空间（history JSON 里的 {@code op=data/...}）。 */
    public static final String OPERATION_NAMESPACE = "data";

    /** 本适配器在 {@link DataSourceRegistry} 里的稳定名。 */
    public static final String NAME = DataSourceRegistry.OR;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    /**
     * 幂等注册：登记两个变更操作 + 把本适配器登记进数据源目录。
     *
     * <p>由 {@code RefineServlet.init()} 驱动（与 {@code LicenseGuard.register} 同序，须在
     * {@code FileProjectManager.initialize} 之后调用，以保证 {@link ProjectManager#singleton} 已就绪）。
     * 用 {@link AtomicBoolean} 守卫：{@code OperationRegistry.registerOperation} 是**追加**语义，
     * 重复登记会产生重复条目，故须恰好一次。</p>
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        OperationRegistry.registerOperation(OPERATION_NAMESPACE, SetCellValueOperation.OPERATION_NAME,
                SetCellValueOperation.class);
        OperationRegistry.registerOperation(OPERATION_NAMESPACE, PutOverlayJsonOperation.OPERATION_NAME,
                PutOverlayJsonOperation.class);
        DataSourceRegistry.register(NAME, new OrDataSource());
    }

    private final ProjectManager projectManager;

    /** 以全局单例 {@link ProjectManager#singleton} 装配。 */
    public OrDataSource() {
        this(ProjectManager.singleton);
    }

    /** 显式注入 ProjectManager（便于测试）。 */
    public OrDataSource(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    @Override
    public boolean exists(String projectId) {
        return projectManager != null && projectManager.getProjectMetadata(parseId(projectId)) != null;
    }

    @Override
    public TableDriver open(String projectId) {
        if (projectManager == null) {
            return null;
        }
        Project project = projectManager.getProject(parseId(projectId));
        return project == null ? null : new OrTableDriver(project);
    }

    @Override
    public TableDriver create(String projectId, String name) {
        if (projectManager == null) {
            throw new IllegalStateException("ProjectManager 未初始化");
        }
        Project project = Project.withId(parseId(projectId));
        Instant now = Instant.now();
        projectManager.registerProject(project, new ProjectMetadata(now, now, name == null ? "" : name));
        return new OrTableDriver(project);
    }

    @Override
    public void persist(String projectId) {
        if (projectManager != null) {
            projectManager.ensureProjectSaved(parseId(projectId));
        }
    }

    private static long parseId(String projectId) {
        try {
            return Long.parseLong(projectId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("projectId 须为数字字符串: " + projectId);
        }
    }
}
