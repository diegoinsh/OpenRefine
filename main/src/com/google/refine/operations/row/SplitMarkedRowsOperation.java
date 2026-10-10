/*******************************************************************************
 * Copyright (C) 2025, OpenRefine contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package com.google.refine.operations.row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.browsing.Engine;
import com.google.refine.browsing.EngineConfig;
import com.google.refine.browsing.RowVisitor;
import com.google.refine.history.HistoryEntry;
import com.google.refine.messages.OpenRefineMessage;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnsDiff;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.changes.SplitMarkedRowsChange;
import com.google.refine.operations.EngineDependentOperation;
import com.google.refine.operations.OperationDescription;

/**
 * 拆分行操作：对当前视图（经 facet 筛选）中带星标或旗标的行，各自复制出一行新行并插入到原行之后，
 * 新行继承原行的标记。若存在“件号”类列（列名忽略大小写匹配 {@link #JIANHAO_COLUMN_ALIASES}）且其
 * 单元格值为 0 开头的多位数字，则新行的编号取原值 + 1，并从被拆分行之后沿严格递增链逐行 +1 顺延，
 * 一旦不再递增（后值 &lt;= 前值）立即停止；编号按原值书写宽度补零。
 *
 * <p>
 * 整次改动作为单个 {@link SplitMarkedRowsChange} 提交，因此会写入一条历史记录，可整体撤销与重做。
 */
public class SplitMarkedRowsOperation extends EngineDependentOperation {

    /** 列名忽略大小写匹配的“件号”列别名。 */
    static final Set<String> JIANHAO_COLUMN_ALIASES = Set.of("件号", "jh", "jianhao");

    /** 视作可自增编号的单元格值形态：0 开头的多位数字。 */
    static final Pattern ZERO_LEADING_NUMBER = Pattern.compile("^0\\d+$");

    @JsonCreator
    public SplitMarkedRowsOperation(
            @JsonProperty("engineConfig") EngineConfig engineConfig) {
        super(engineConfig);
    }

    @Override
    protected String getBriefDescription(Project project) {
        return OperationDescription.row_split_marked_brief();
    }

    @Override
    protected Optional<Set<String>> getColumnDependenciesWithoutEngine() {
        return Optional.of(Set.of());
    }

    @JsonIgnore
    public Optional<ColumnsDiff> getColumnsDiff() {
        return Optional.of(ColumnsDiff.empty());
    }

    @Override
    public SplitMarkedRowsOperation renameColumns(Map<String, String> newColumnNames) {
        return new SplitMarkedRowsOperation(_engineConfig.renameColumnDependencies(newColumnNames));
    }

    /**
     * 收集当前视图（engine 过滤后）中带星标或旗标的行索引，升序。
     */
    private static List<Integer> collectMarkedRowIndices(Project project, Engine engine) {
        List<Integer> indices = new ArrayList<>();
        engine.getAllFilteredRows().accept(project, new RowVisitor() {

            @Override
            public void start(Project p) {
            }

            @Override
            public void end(Project p) {
            }

            @Override
            public boolean visit(Project p, int rowIndex, Row row) {
                if (row.starred || row.flagged) {
                    indices.add(rowIndex);
                }
                return false;
            }
        });
        return indices;
    }

    /**
     * 当前视图内带星标或旗标的行数（供命令的 dryRun 计数使用）。
     */
    public static int countMarkedRows(Project project, Engine engine) {
        return collectMarkedRowIndices(project, engine).size();
    }

    @Override
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID) throws Exception {
        Engine engine = createEngine(project);
        List<Integer> markedIndices = collectMarkedRowIndices(project, engine);
        if (markedIndices.isEmpty()) {
            throw new Exception("No marked rows in the current view");
        }

        List<Row> rows = project.rows;
        int rowCount = rows.size();

        boolean[] marked = new boolean[rowCount];
        for (int index : markedIndices) {
            if (index >= 0 && index < rowCount) {
                marked[index] = true;
            }
        }

        // 定位“件号”列（可能不止一列）
        List<Integer> jianhaoCellIndexes = new ArrayList<>();
        for (Column column : project.columnModel.columns) {
            if (column != null && column.getName() != null
                    && JIANHAO_COLUMN_ALIASES.contains(column.getName().trim().toLowerCase())) {
                jianhaoCellIndexes.add(column.getCellIndex());
            }
        }

        // 逐列解析原始件号数值与书写宽度；不可解析处保持 null
        List<Long[]> columnValues = new ArrayList<>(jianhaoCellIndexes.size());
        List<int[]> columnWidths = new ArrayList<>(jianhaoCellIndexes.size());
        for (int cellIndex : jianhaoCellIndexes) {
            Long[] values = new Long[rowCount];
            int[] widths = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                Object value = rows.get(i).getCellValue(cellIndex);
                if (value instanceof String && ZERO_LEADING_NUMBER.matcher((String) value).matches()) {
                    try {
                        values[i] = Long.parseLong((String) value);
                        widths[i] = ((String) value).length();
                    } catch (NumberFormatException e) {
                        values[i] = null;
                    }
                }
            }
            columnValues.add(values);
            columnWidths.add(widths);
        }

        // 逐列计算顺延增量：每个可编号插入点向后沿严格递增链各贡献 +1
        List<int[]> columnShifts = new ArrayList<>(jianhaoCellIndexes.size());
        for (int c = 0; c < jianhaoCellIndexes.size(); c++) {
            Long[] values = columnValues.get(c);
            int[] shifts = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                if (!marked[i] || values[i] == null) {
                    continue;
                }
                for (int j = i + 1; j < rowCount; j++) {
                    if (values[j] != null && values[j - 1] != null && values[j] > values[j - 1]) {
                        shifts[j] += 1;
                    } else {
                        break;
                    }
                }
            }
            columnShifts.add(shifts);
        }

        // 构造新行集合：统一 deepCopy，避免污染 SplitMarkedRowsChange 的旧行快照
        List<Row> newRows = new ArrayList<Row>(rowCount + markedIndices.size());
        for (int i = 0; i < rowCount; i++) {
            Row row = rows.get(i).deepCopy();
            applyShifts(row, jianhaoCellIndexes, columnValues, columnWidths, columnShifts, i);
            newRows.add(row);

            if (marked[i]) {
                Row copy = rows.get(i).deepCopy();
                applyShifts(copy, jianhaoCellIndexes, columnValues, columnWidths, columnShifts, i);
                for (int c = 0; c < jianhaoCellIndexes.size(); c++) {
                    Long value = columnValues.get(c)[i];
                    if (value != null) {
                        setFormattedValue(copy, jianhaoCellIndexes.get(c),
                                value + columnShifts.get(c)[i] + 1, columnWidths.get(c)[i]);
                    }
                }
                newRows.add(copy);
            }
        }

        return new HistoryEntry(
                historyEntryID,
                project,
                OpenRefineMessage.row_split_marked_description(markedIndices.size()),
                this,
                new SplitMarkedRowsChange(newRows));
    }

    private static void applyShifts(Row row, List<Integer> cellIndexes, List<Long[]> values,
            List<int[]> widths, List<int[]> shifts, int rowIndex) {
        for (int c = 0; c < cellIndexes.size(); c++) {
            Long value = values.get(c)[rowIndex];
            int shift = shifts.get(c)[rowIndex];
            if (value != null && shift > 0) {
                setFormattedValue(row, cellIndexes.get(c), value + shift, widths.get(c)[rowIndex]);
            }
        }
    }

    private static void setFormattedValue(Row row, int cellIndex, long value, int width) {
        StringBuilder sb = new StringBuilder(Long.toString(value));
        while (sb.length() < width) {
            sb.insert(0, '0');
        }
        Cell old = row.getCell(cellIndex);
        row.setCell(cellIndex, new Cell(sb.toString(), old != null ? old.recon : null));
    }
}