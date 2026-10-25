/*
 * JinRefine · OR 数据源适配器（P0）
 */

package com.jiongche.refine.data;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.changes.CellChange;

/**
 * 设置单个单元格的 OR 变更操作（供 SDK 数据源驱动的 {@code setValue} 使用）。
 *
 * <p>复用既有 {@link CellChange} 作为变更载体：{@code apply} 内 {@code row.setCell} +
 * 清列预计算 + 刷新 lookup 缓存，与 OR 既有单元格编辑同一条路径。
 */
public class SetCellValueOperation extends AbstractOperation {

    /** 操作注册名（命名空间 {@link OrDataSource#OPERATION_NAMESPACE}，见 {@link OrDataSource#register()}）。 */
    @JsonIgnore
    public static final String OPERATION_NAME = "set-cell-value";

    @JsonProperty("row")
    protected final int row;

    @JsonProperty("cellIndex")
    protected final int cellIndex;

    @JsonProperty("newValue")
    protected final Serializable newValue;

    @JsonCreator
    public SetCellValueOperation(
            @JsonProperty("row") int row,
            @JsonProperty("cellIndex") int cellIndex,
            @JsonProperty("newValue") Serializable newValue) {
        this.row = row;
        this.cellIndex = cellIndex;
        this.newValue = newValue;
    }

    @Override
    protected String getBriefDescription(Project project) {
        return "Set cell value (row " + row + ", cell " + cellIndex + ")";
    }

    @Override
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID) throws Exception {
        Cell oldCell = project.rows.get(row).getCell(cellIndex);
        Cell newCell = newValue == null ? null
                : new Cell(newValue, oldCell == null ? null : oldCell.recon);
        Change change = new CellChange(row, cellIndex, oldCell, newCell);
        return new HistoryEntry(historyEntryID, project, getBriefDescription(null), this, change);
    }
}
