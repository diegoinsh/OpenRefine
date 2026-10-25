/*
 * JinRefine · OR 数据源适配器（P0）
 */

package com.jiongche.refine.data;

import java.io.Serializable;
import java.util.Properties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jiongche.sdk.data.TableDriver;
import com.jiongche.sdk.data.TableSchema;

import com.google.refine.ProjectManager;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * {@link TableDriver} 的 OR 实现：**包住活的 {@link Project}**（路线①「共享活对象句柄」）。
 *
 * <p>读侧直接投影内存对象，故与 OR 的 UI / 落盘天然同源；写侧**不直接改内存**，而是构造
 * OR 变更操作（{@link SetCellValueOperation} / {@link PutOverlayJsonOperation}）经
 * {@code History.addEntry} → {@code Change.apply} → {@code setModified} 施加，
 * 以保证 history / 落盘 / UI 一条路。
 */
public class OrTableDriver implements TableDriver {

    private final Project project;

    public OrTableDriver(Project project) {
        this.project = project;
    }

    @Override
    public TableSchema schema() {
        return new OrTableSchema(project.columnModel);
    }

    @Override
    public int rowCount() {
        return project.rows.size();
    }

    @Override
    public Object value(int row, int col) {
        if (row < 0 || row >= project.rows.size() || col < 0 || col >= project.columnModel.columns.size()) {
            return null;
        }
        int cellIndex = project.columnModel.columns.get(col).getCellIndex();
        return projectCellValue(project.rows.get(row).getCellValue(cellIndex));
    }

    @Override
    public String overlayJson(String name) {
        OverlayModel model = project.overlayModels.get(name);
        return model == null ? null : toJson(model);
    }

    @Override
    public String id() {
        return String.valueOf(project.id);
    }

    @Override
    public String metadataJson(String key) {
        Serializable value = project.getMetadata().getCustomMetadata(key);
        return value == null ? null : toJson(value);
    }

    @Override
    public Object valueByCellIndex(int row, int cellIndex) {
        if (row < 0 || row >= project.rows.size() || cellIndex < 0) {
            return null;
        }
        return projectCellValue(project.rows.get(row).getCellValue(cellIndex));
    }

    @Override
    public void setValue(int row, int col, Object value) {
        if (row < 0 || row >= project.rows.size()) {
            throw new IllegalArgumentException("row 越界: " + row);
        }
        if (col < 0 || col >= project.columnModel.columns.size()) {
            throw new IllegalArgumentException("col 越界: " + col);
        }
        if (value != null && !(value instanceof Serializable)) {
            throw new IllegalArgumentException("写入值须为 Serializable（primitive/String)："
                    + value.getClass().getName());
        }
        int cellIndex = project.columnModel.columns.get(col).getCellIndex();
        apply(new SetCellValueOperation(row, cellIndex, (Serializable) value));
    }

    @Override
    public void putOverlayJson(String name, String json) {
        apply(new PutOverlayJsonOperation(name, json));
    }

    @Override
    public void markDirtyAndPersist() {
        ProjectManager.singleton.ensureProjectSaved(project.id);
    }

    /** 走 OR 的变更施加路径（operation → HistoryEntry → History.addEntry → Change.apply）。 */
    private void apply(com.google.refine.model.AbstractOperation op) {
        try {
            op.createProcess(project, new Properties()).performImmediate();
        } catch (Exception e) {
            throw new RuntimeException("施加 OR 变更失败: " + e.getMessage(), e);
        }
    }

    /**
     * 单元格值的 SDK 投影：primitive / {@link String} 原样返回；复合值（日期/错误等）统一以
     * JSON 串返回，使下沉模块无需依赖 OR 类型。{@link SnapshotTableView} 复用本投影以保证与其一致。
     */
    public static Object projectCellValue(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        return toJson(v);
    }

    /** 任意 OR 复合值 → JSON 串；序列化失败返回 {@code null}。 */
    public static String toJson(Object value) {
        try {
            return ParsingUtilities.mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
