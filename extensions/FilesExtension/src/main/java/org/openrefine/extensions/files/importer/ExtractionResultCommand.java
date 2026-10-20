package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 抽取结果分页读取（只读）。
 *
 * <p>平台侧对外提供抽取结果接口时使用：按数据页（卷内 / 卷级）返回列定义与分页行，
 * 不修改项目、不切换活动数据页，因此可在抽取运行期间安全调用。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code project}：OR 数字项目 id（必填）</li>
 *   <li>{@code sheet}：{@code inner}（默认）/ {@code volume} / 具体 sheetId</li>
 *   <li>{@code start}：起始行下标（默认 0）</li>
 *   <li>{@code limit}：每页行数（默认 200，上限 2000）</li>
 * </ul>
 */
public class ExtractionResultCommand extends Command {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2000;

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }
        String rawProject = request.getParameter("project");
        long projectId;
        try {
            projectId = Long.parseLong(rawProject == null ? "" : rawProject.trim());
        } catch (NumberFormatException e) {
            respondError(response, "project 参数非法: " + rawProject);
            return;
        }
        Project project = ProjectManager.singleton.getProject(projectId);
        if (project == null) {
            respondError(response, "未找到项目: " + projectId);
            return;
        }

        String sheetParam = request.getParameter("sheet");
        SheetData sheet = resolveSheet(project, sheetParam);
        if (sheet == null) {
            respondError(response, "未找到数据页: " + (sheetParam == null ? "inner" : sheetParam));
            return;
        }

        List<Column> columns = new ArrayList<>(sheet.columnModel.columns);
        columns.sort(Comparator.comparingInt(Column::getCellIndex));

        int total = sheet.rows.size();
        int start = Math.max(0, intParam(request, "start", 0));
        int limit = intParam(request, "limit", DEFAULT_LIMIT);
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);
        int end = Math.min(total, start + limit);

        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        result.put("sheetId", sheet.sheetId);
        result.put("sheetName", sheet.sheetName);
        result.put("total", total);
        result.put("start", start);
        result.put("limit", limit);

        ArrayNode columnArr = result.putArray("columns");
        for (Column c : columns) {
            ObjectNode n = columnArr.addObject();
            n.put("name", c.getName());
            n.put("index", c.getCellIndex());
        }

        ArrayNode sheetArr = result.putArray("sheets");
        if (project.sheetDataMap != null) {
            for (SheetData s : project.sheetDataMap.values()) {
                ObjectNode n = sheetArr.addObject();
                n.put("id", s.sheetId);
                n.put("name", s.sheetName);
                n.put("rows", s.rows.size());
            }
        }

        ArrayNode rowArr = result.putArray("rows");
        for (int i = start; i < end; i++) {
            Row row = sheet.rows.get(i);
            ObjectNode obj = rowArr.addObject();
            obj.put("_row", i);
            for (Column c : columns) {
                Cell cell = row.getCell(c.getCellIndex());
                Object value = cell == null ? null : cell.value;
                if (value == null) {
                    obj.putNull(c.getName());
                } else {
                    obj.set(c.getName(), mapper.valueToTree(value));
                }
            }
        }

        respondJSON(response, result);
    }

    /** {@code inner}/{@code volume} 别名 → 实际 SheetData；单表项目回退到主表。 */
    private static SheetData resolveSheet(Project project, String param) {
        String sheetId = param == null || param.trim().isEmpty() ? "inner" : param.trim();
        if ("inner".equalsIgnoreCase(sheetId)) {
            sheetId = BatchExtractionCommand.INNER_SHEET_ID;
        } else if ("volume".equalsIgnoreCase(sheetId)) {
            sheetId = BatchExtractionCommand.SUMMARY_SHEET_ID;
        }
        if (project.sheetDataMap != null) {
            SheetData s = project.sheetDataMap.get(sheetId);
            if (s != null) {
                return s;
            }
        }
        // 按件模板（单表）没有 sheetDataMap，主表即卷内
        if ("inner".equalsIgnoreCase(param) || param == null || param.trim().isEmpty()
                || BatchExtractionCommand.INNER_SHEET_ID.equals(sheetId)) {
            SheetData main = new SheetData(BatchExtractionCommand.INNER_SHEET_ID,
                    BatchExtractionCommand.INNER_SHEET_NAME, "");
            main.rows = project.rows;
            main.columnModel = project.columnModel;
            return main;
        }
        return null;
    }

    private static int intParam(HttpServletRequest request, String name, int def) {
        String v = request.getParameter(name);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void respondJSON(HttpServletResponse response, ObjectNode node) throws IOException {
        response.setHeader("Content-Type", "application/json; charset=UTF-8");
        response.getWriter().write(node.toString());
    }

    private static void respondError(HttpServletResponse response, String message) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", "error");
        node.put("message", message);
        respondJSON(response, node);
    }
}
