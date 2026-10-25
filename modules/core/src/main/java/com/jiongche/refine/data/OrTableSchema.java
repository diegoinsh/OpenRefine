/*
 * JinRefine · OR 数据源适配器（P0）
 *
 * SDK 数据源契约（com.jiongche.sdk.data）的 OR 实现：把**活的** Project 以窄接口暴露给下沉模块。
 * 单一真相源 = OR 的 Project（内存）+ overlay + ProjectManager（落盘）；本适配器只经句柄访问。
 */

package com.jiongche.refine.data;

import java.util.ArrayList;
import java.util.List;

import com.jiongche.sdk.data.TableSchema;

import com.google.refine.model.ColumnModel;

/**
 * {@link TableSchema} 的 OR 实现：包住活的 {@link ColumnModel}。
 *
 * <p>列序号 = 列在 {@code ColumnModel} 里的**位置**（与 OR UI 列序一致）；仅在读写单元格时
 * 再经 {@code Column#getCellIndex()} 换算为真正的 cell index。
 */
public class OrTableSchema implements TableSchema {

    private final ColumnModel columnModel;

    public OrTableSchema(ColumnModel columnModel) {
        this.columnModel = columnModel;
    }

    @Override
    public List<String> columnNames() {
        return new ArrayList<String>(columnModel.getColumnNames());
    }

    @Override
    public int columnIndex(String name) {
        return name == null ? -1 : columnModel.getColumnIndexByName(name);
    }
}
