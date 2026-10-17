/*
 * JinRefine · OR 数据源适配器（P2）
 *
 * 把活的 OR {@code Project} 以 SDK {@link TableView} 暴露给下沉模块的统一入口。
 *
 * <p>缺省（{@code live}）返回**活视图** {@link OrTableDriver}：直接投影内存对象，与 OR 的
 * UI / 落盘天然同源、逐值等价（缺省零差异）。开关
 * {@code -Druyi.data-quality.view=snapshot} 时改返回 {@link SnapshotTableView} 只读快照，
 * 供后台线程与活 Project 并发变更时回退使用。</p>
 */
package com.jiongche.refine.data;

import com.jiongche.sdk.data.TableView;

import com.google.refine.model.Project;

public final class OrViews {

    /** 视图开关的系统属性名：{@code live}（缺省）| {@code snapshot}。 */
    public static final String VIEW_PROPERTY = "ruyi.data-quality.view";

    /** 缺省视图模式（活视图，零拷贝、行为等价）。 */
    public static final String MODE_LIVE = "live";

    /** 快照视图模式（冻结拷贝，隔离宿主后续变更）。 */
    public static final String MODE_SNAPSHOT = "snapshot";

    private OrViews() {
    }

    /** 按开关 {@link #VIEW_PROPERTY} 为指定 Project 取 {@link TableView}。 */
    public static TableView viewFor(Project project) {
        return MODE_SNAPSHOT.equalsIgnoreCase(System.getProperty(VIEW_PROPERTY, MODE_LIVE))
                ? new SnapshotTableView(project)
                : new OrTableDriver(project);
    }
}
