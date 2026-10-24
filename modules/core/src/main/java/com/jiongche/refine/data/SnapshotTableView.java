/*
 * JinRefine · OR 数据源适配器（P2）
 *
 * {@link TableView} 的**只读快照**实现：构造时把活的 {@code Project} 的列名 / 单元格值 /
 * overlay JSON 一次性拷出，之后与宿主的后续变更解耦。
 *
 * <p>存在的意义：data-quality 的检查可能跑在后台线程，而 OR 的活 {@code Project} 随时可能被
 * UI 改动（并发读写不安全）；快照提供一个「冻结视图」，语义与 {@link OrTableDriver} 的读侧
 * **逐值等价**（复用 {@link OrTableDriver#projectCellValue(Object)}）。由
 * {@link OrViews#viewFor(Project)} 经开关选用。</p>
 *
 * <p>元数据（{@code metadataJson}）不做快照，按需从宿主元数据读透——宿主元数据只在导入时写入，
 * 变更面远小于行/overlay，且无公开的全量访问器可枚举。</p>
 */
package com.jiongche.refine.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jiongche.sdk.data.TableSchema;
import com.jiongche.sdk.data.TableView;

import com.google.refine.model.Column;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

public class SnapshotTableView implements TableView {

    private final String id;
    private final List<String> columnNames;
    /** [列位置] → 单元格索引；冻结列的「位置 → cellIndex」映射。 */
    private final int[] cellIndexOfColumn;
    /** [行][单元格索引] → SDK 投影值；单元格索引与 {@code Row#getCell(cellIndex)} 语义一致。 */
    private final Object[][] values;
    private final Map<String, String> overlayJson;

    /** 宿主元数据引用（只读读透）。 */
    private final Project project;

    public SnapshotTableView(Project project) {
        this.project = project;
        this.id = String.valueOf(project.id);

        List<Column> columns = project.columnModel.columns;
        this.columnNames = new ArrayList<String>(project.columnModel.getColumnNames());
        this.cellIndexOfColumn = new int[columns.size()];
        int maxCellIndex = -1;
        for (int c = 0; c < columns.size(); c++) {
            int cellIndex = columns.get(c).getCellIndex();
            cellIndexOfColumn[c] = cellIndex;
            if (cellIndex > maxCellIndex) {
                maxCellIndex = cellIndex;
            }
        }

        int rowCount = project.rows.size();
        int width = maxCellIndex + 1;
        this.values = new Object[rowCount][width];
        for (int r = 0; r < rowCount; r++) {
            for (int c = 0; c < columns.size(); c++) {
                values[r][cellIndexOfColumn[c]] =
                        OrTableDriver.projectCellValue(project.rows.get(r).getCellValue(cellIndexOfColumn[c]));
            }
        }

        this.overlayJson = new HashMap<String, String>();
        Map<String, OverlayModel> overlays = project.overlayModels;
        if (overlays != null) {
            for (Map.Entry<String, OverlayModel> e : overlays.entrySet()) {
                overlayJson.put(e.getKey(), OrTableDriver.toJson(e.getValue()));
            }
        }
    }

    @Override
    public TableSchema schema() {
        return new TableSchema() {
            @Override
            public List<String> columnNames() {
                return new ArrayList<String>(columnNames);
            }

            @Override
            public int columnIndex(String name) {
                return name == null ? -1 : columnNames.indexOf(name);
            }
        };
    }

    @Override
    public int rowCount() {
        return values.length;
    }

    @Override
    public Object value(int row, int col) {
        if (row < 0 || row >= values.length || col < 0 || col >= cellIndexOfColumn.length) {
            return null;
        }
        return values[row][cellIndexOfColumn[col]];
    }

    @Override
    public String overlayJson(String name) {
        return overlayJson.get(name);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String metadataJson(String key) {
        Serializable value = project.getMetadata().getCustomMetadata(key);
        return value == null ? null : OrTableDriver.toJson(value);
    }

    @Override
    public Object valueByCellIndex(int row, int cellIndex) {
        if (row < 0 || row >= values.length || cellIndex < 0 || cellIndex >= values[row].length) {
            return null;
        }
        return values[row][cellIndex];
    }
}
