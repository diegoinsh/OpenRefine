package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

/**
 * 校审结果的 OR overlay 槽值（槽名 {@code dataReviewResult}，与合规 {@code qualityCheckResult} 对称）。
 *
 * <p>结果只存 overlay、不写数据行：源表格保持输入清单（序号/案卷号/文件名/页数/文件夹路径），
 * 结构化结果（summary / pieces / files）随项目一起落盘，不进 undo/redo 历史。</p>
 */
public class ReviewResultOverlay implements OverlayModel {

    public static final String OVERLAY_MODEL_KEY = "dataReviewResult";

    @JsonProperty("result")
    private ObjectNode result;

    public ReviewResultOverlay() {
    }

    @JsonCreator
    public ReviewResultOverlay(@JsonProperty("result") ObjectNode result) {
        this.result = result;
    }

    public ObjectNode getResult() {
        return result;
    }

    public void setResult(ObjectNode result) {
        this.result = result;
    }

    @Override
    public void onBeforeSave(Project project) {
    }

    @Override
    public void onAfterSave(Project project) {
    }

    @Override
    public void dispose(Project project) {
    }
}
