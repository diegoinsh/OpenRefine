/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine.commands.cell;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import com.google.refine.commands.Command;
import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.messages.OpenRefineMessage;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.changes.CellChange;
import com.google.refine.model.changes.MassCellChange;
import com.google.refine.process.QuickHistoryEntryProcess;
import com.google.refine.util.ParsingUtilities;

/**
 * 「填充柄」批量填充命令：把编辑态源单元格的内容，按「复制」（copy）或「序列」（series）方式，
 * 一次性写入沿拖动方向选中的一组相邻单元格（覆盖原值）。
 *
 * <p>
 * 源单元格若在就地编辑中被改动，其新值会与目标单元格的填充值一起，作为**同一条** {@link MassCellChange}
 * 历史记录提交，因此整次操作可被一次性撤销 / 重做。
 *
 * <p>
 * 请求参数（POST body）：
 * <ul>
 * <li>{@code mode}：{@code "copy"} 或 {@code "series"}</li>
 * <li>{@code source}：JSON 对象 {@code {row, cell, value, type}}（{@code value} 为编辑框当前内容，
 * {@code type} 为 text/number/boolean）</li>
 * <li>{@code targets}：JSON 数组 {@code [{row, cell}, ...]}，沿拖动方向排列，不含源单元格</li>
 * </ul>
 */
public class FillCellsCommand extends Command {

    /** 纯整数（可带符号与前导零）：保号 + 保宽度。 */
    private static final Pattern LEADING_ZERO_INT = Pattern.compile("^(-?)(0*)(\\d+)$");

    /** 纯小数（可带符号）：用 BigDecimal 相加，保留原小数位数。 */
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+\\.\\d+$");

    /** 文本尾号为数字：前缀原样 + 数字段整体 +k 并保持原宽度。 */
    private static final Pattern TRAILING_DIGITS = Pattern.compile("^(.*?)(\\d+)$");

    protected static class FillResult {

        @JsonProperty("code")
        protected String code;
        @JsonProperty("historyEntry")
        @JsonInclude(Include.NON_NULL)
        protected HistoryEntry historyEntry;

        protected FillResult(String code, HistoryEntry historyEntry) {
            this.code = code;
            this.historyEntry = historyEntry;
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            request.setCharacterEncoding("UTF-8");
            response.setCharacterEncoding("UTF-8");

            Project project = getProject(request);

            String mode = request.getParameter("mode");
            JsonNode source = ParsingUtilities.mapper.readTree(request.getParameter("source"));
            JsonNode targets = ParsingUtilities.mapper.readTree(request.getParameter("targets"));
            if (source == null || source.isNull() || targets == null) {
                respond(response, "{ \"code\" : \"error\", \"message\" : \"Missing FillCellsCommand parameters\" }");
                return;
            }

            FillCellsProcess process = new FillCellsProcess(project, mode, source, targets);
            if (process.isEmpty()) {
                // 没有任何实际改动（例如目标值恰好与原值相同）时不写入历史
                respondJSON(response, new FillResult("ok", null));
                return;
            }

            HistoryEntry historyEntry = project.processManager.queueProcess(process);
            if (historyEntry != null) {
                respondJSON(response, new FillResult("ok", historyEntry));
            } else {
                respond(response, "{ \"code\" : \"pending\" }");
            }
        } catch (Exception e) {
            respondException(response, e);
        }
    }

    /**
     * 一次性把源值（复制或序列）写入目标单元格，并连同源单元格的改动一起组成单条历史。
     */
    protected static class FillCellsProcess extends QuickHistoryEntryProcess {

        final List<CellChange> cellChanges = new ArrayList<>();
        final String commonColumnName;

        FillCellsProcess(Project project, String mode, JsonNode source, JsonNode targets) {
            super(project, OpenRefineMessage.fill_cells_brief(0));

            int sourceRow = source.get("row").asInt();
            int sourceCell = source.get("cell").asInt();
            String type = source.hasNonNull("type") ? source.get("type").asText() : "text";
            Serializable sourceValue = parseValue(type, source.hasNonNull("value") ? source.get("value").asText() : "");

            Set<Integer> touchedColumns = new LinkedHashSet<>();

            // 源单元格：编辑框里的内容若与原值不同，也一并在本次变更中提交（不另起一条历史）
            addChange(sourceRow, sourceCell, sourceValue);
            touchedColumns.add(sourceCell);

            if ("series".equals(mode)) {
                int step = 1;
                for (JsonNode target : targets) {
                    int row = target.get("row").asInt();
                    int cell = target.get("cell").asInt();
                    touchedColumns.add(cell);
                    addChange(row, cell, seriesValue(sourceValue, step));
                    step++;
                }
            } else {
                for (JsonNode target : targets) {
                    int row = target.get("row").asInt();
                    int cell = target.get("cell").asInt();
                    touchedColumns.add(cell);
                    addChange(row, cell, sourceValue);
                }
            }

            // 全部落在同一列时，把列名交给 MassCellChange，使其清理该列的预计算 / 匹配缓存
            String columnName = null;
            if (touchedColumns.size() == 1) {
                Column column = project.columnModel.getColumnByCellIndex(touchedColumns.iterator().next());
                if (column != null) {
                    columnName = column.getName();
                }
            }
            this.commonColumnName = columnName;
        }

        boolean isEmpty() {
            return cellChanges.isEmpty();
        }

        @Override
        protected HistoryEntry createHistoryEntry(long historyEntryID) throws Exception {
            String description = OpenRefineMessage.fill_cells_brief(cellChanges.size());
            Change change = new MassCellChange(cellChanges, commonColumnName, true);

            return new HistoryEntry(historyEntryID, _project, description, null, change);
        }

        /**
         * 取旧值并做越界保护；新值与旧值相同则不产生变更（避免留下无实际改动的历史）。
         */
        private void addChange(int row, int cell, Serializable newValue) {
            if (row < 0 || row >= _project.rows.size()) {
                return;
            }
            Column column = _project.columnModel.getColumnByCellIndex(cell);
            if (column == null) {
                return;
            }
            Cell oldCell = _project.rows.get(row).getCell(cell);
            if (oldCell != null && Objects.equals(oldCell.value, newValue)) {
                return;
            }
            cellChanges.add(new CellChange(row, cell, oldCell, new Cell(newValue, null)));
        }
    }

    /**
     * 按编辑框给出的类型解析源值：number 走 Long→Double 回退，boolean 走 true/false，其余按字符串。
     */
    static Serializable parseValue(String type, String valueString) {
        if ("number".equals(type)) {
            try {
                return Long.parseLong(valueString);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(valueString);
                } catch (NumberFormatException e2) {
                    return valueString;
                }
            }
        } else if ("boolean".equals(type)) {
            return "true".equalsIgnoreCase(valueString);
        }
        return valueString;
    }

    /**
     * 智能序列推算：数字 +k；文本尾号为数字则整体 +k 并保留前导零与前后缀；识别不出则原样返回。
     */
    static Serializable seriesValue(Serializable base, int k) {
        if (base instanceof Long) {
            return ((Long) base) + k;
        }
        if (base instanceof Double) {
            return ((Double) base) + k;
        }
        if (base instanceof Boolean) {
            return base;
        }

        String s = String.valueOf(base);

        if (DECIMAL.matcher(s).matches()) {
            return new BigDecimal(s).add(BigDecimal.valueOf(k)).toPlainString();
        }

        Matcher integer = LEADING_ZERO_INT.matcher(s);
        if (integer.matches()) {
            String sign = integer.group(1);
            String zeros = integer.group(2);
            String digits = integer.group(3);
            String incremented = incrementDigits(digits, k, zeros.length() + digits.length());
            return incremented == null ? base : sign + incremented;
        }

        Matcher trailing = TRAILING_DIGITS.matcher(s);
        if (trailing.matches()) {
            String prefix = trailing.group(1);
            String digits = trailing.group(2);
            String incremented = incrementDigits(digits, k, digits.length());
            return incremented == null ? base : prefix + incremented;
        }

        return base;
    }

    /**
     * 数字段整体 +k，并按 {@code width} 左侧补零；溢出（超出 long）时返回 null，交由调用方回退原值。
     */
    private static String incrementDigits(String digits, int k, int width) {
        long n;
        try {
            n = Long.parseLong(digits) + k;
        } catch (NumberFormatException e) {
            return null;
        }
        String out = Long.toString(Math.abs(n));
        StringBuilder sb = new StringBuilder(out);
        while (sb.length() < width) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }
}
