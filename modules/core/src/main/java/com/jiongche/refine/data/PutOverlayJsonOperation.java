/*
 * JinRefine · OR 数据源适配器（P0）
 */

package com.jiongche.refine.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * 按名写入 overlay 模型的 OR 变更操作（供 SDK 数据源驱动的 {@code putOverlayJson} 使用）。
 *
 * <p>overlay 名称 → 实现类的解析经 OR 的 overlay 注册表（{@link Project#overlayModelClass(String)}），
 * 故下沉模块只传 JSON，不必依赖 OR 的 OverlayModel 类型。
 */
public class PutOverlayJsonOperation extends AbstractOperation {

    /** 操作注册名（命名空间 {@link OrDataSource#OPERATION_NAMESPACE}，见 {@link OrDataSource#register()}）。 */
    @JsonIgnore
    public static final String OPERATION_NAME = "put-overlay-json";

    @JsonProperty("name")
    protected final String name;

    @JsonProperty("json")
    protected final String json;

    @JsonCreator
    public PutOverlayJsonOperation(
            @JsonProperty("name") String name,
            @JsonProperty("json") String json) {
        this.name = name;
        this.json = json;
    }

    @Override
    protected String getBriefDescription(Project project) {
        return "Put overlay model " + name;
    }

    @Override
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID) throws Exception {
        Class<? extends OverlayModel> klass = Project.overlayModelClass(name);
        if (klass == null) {
            throw new IllegalArgumentException("未注册的 overlay 模型: " + name);
        }
        OverlayModel newModel = json == null ? null : ParsingUtilities.mapper.readValue(json, klass);
        Change change = new OverlayChange(name, newModel);
        return new HistoryEntry(historyEntryID, project, getBriefDescription(null), this, change);
    }
}
