/*
 * JinRefine · OR 数据源适配器（P0）
 */

package com.jiongche.refine.data;

import java.time.Instant;

import com.jiongche.sdk.data.DataSource;
import com.jiongche.sdk.data.TableDriver;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.model.Project;
import com.google.refine.operations.OperationRegistry;

/**
 * {@link DataSource} 的 OR 实现：把 OR 的 {@link ProjectManager}（库/编目）以窄接口暴露。
 *
 * <p>构造时静态注册本适配器引入的两个变更操作（命名空间 {@code data}），使 history JSON 里的
 * {@code op=data/set-cell-value} 等能经 {@link OperationRegistry} 反解。
 */
public class OrDataSource implements DataSource {

    static {
        OperationRegistry.registerOperation("data", SetCellValueOperation.OPERATION_NAME, SetCellValueOperation.class);
        OperationRegistry.registerOperation("data", PutOverlayJsonOperation.OPERATION_NAME, PutOverlayJsonOperation.class);
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
