/*
 * Data Quality Extension - Apply Extraction Labels Command
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;

/**
 * 抽取反馈 · 训练数据导入：把真值写回项目单元格。
 *
 * <p>闭环口径见 {@code docs/extraction-feedback-loop-design.md} §3.2：标签取「最终留下的值
 * vs pageMap 胜出候选」的快照比对。合成数据（mock-data-gen）没有人工改格子这一步，
 * 故先由本命令按外部真值批量改写单元格（模拟人工定稿），再由
 * {@link SubmitExtractionFeedbackCommand} 走 {@code ui_export} 提交，
 * 使样本 {@code verify_state=ok} 且带 {@code origin} 合成标记。</p>
 *
 * <p>只改单元格值，不动 pageMap 与列结构；改完立即落盘。可休眠：本命令只被自动化脚本调用，
 * 不被调用时对既有链路零影响。</p>
 *
 * <p>请求参数：{@code labels} = JSON 数组，元素形如
 * {@code {"row": 3, "fieldKey": "题名", "labelValue": "民事判决书"}}；
 * {@code labelValue} 缺省或为 null 表示清空该格（对应 wrong 的 false-positive 场景）。</p>
 */
public class ApplyExtractionLabelsCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("ApplyExtractionLabelsCommand");
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 卷内表 id（与 {@link SubmitExtractionFeedbackCommand#SUMMARY_SHEET_ID} 同源约定）。 */
    public static final String INNER_SHEET_ID = "batch#卷内";

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }
        Project project = getProject(request);
        if (project == null) {
            respondError(response, "未找到项目");
            return;
        }

        String raw = request.getParameter("labels");
        if (raw == null || raw.trim().isEmpty()) {
            respondError(response, "缺少 labels（JSON 数组）");
            return;
        }

        JsonNode labels;
        try {
            labels = mapper.readTree(raw);
        } catch (Exception e) {
            respondError(response, "labels JSON 解析失败: " + e.getMessage());
            return;
        }
        if (!labels.isArray()) {
            respondError(response, "labels 必须是 JSON 数组");
            return;
        }

        SheetData sheet = project.getSheetData(INNER_SHEET_ID);
        boolean mainTable = false;
        if (sheet == null || sheet.rows == null || sheet.columnModel == null) {
            // 按件模板（单表）：主表即卷内
            sheet = new SheetData(INNER_SHEET_ID, "卷内", "");
            sheet.rows = project.rows;
            sheet.columnModel = project.columnModel;
            mainTable = true;
        }

        int applied = 0;
        int skipped = 0;
        for (JsonNode n : labels) {
            if (n == null || !n.isObject()) {
                skipped++;
                continue;
            }
            int rowIndex = n.path("row").asInt(-1);
            String fieldKey = n.path("fieldKey").asText("");
            if (rowIndex < 0 || rowIndex >= sheet.rows.size() || fieldKey.isEmpty()) {
                skipped++;
                continue;
            }
            Column column = sheet.columnModel.getColumnByName(fieldKey);
            if (column == null) {
                skipped++;
                continue;
            }
            JsonNode labelNode = n.get("labelValue");
            String label = labelNode == null || labelNode.isNull() ? null : labelNode.asText();
            Row row = sheet.rows.get(rowIndex);
            if (row == null) {
                skipped++;
                continue;
            }
            Cell old = row.getCell(column.getCellIndex());
            Cell next = label == null || label.trim().isEmpty()
                    ? null : new Cell(label, old == null ? null : old.recon);
            if (sameValue(old, next)) {
                skipped++;
                continue;
            }
            row.setCell(column.getCellIndex(), next);
            applied++;
        }

        boolean saved = true;
        try {
            // 直接改内存不改历史：先显式刷新修改时间，否则 ensureProjectSaved 会因
            // 「modified 不晚于上次落盘」跳过保存（同 SaveQualityResultCommand 的做法）
            if (project.getMetadata() != null) {
                project.getMetadata().updateModified();
            }
            ProjectManager.singleton.ensureProjectSaved(project.id);
        } catch (Exception e) {
            saved = false;
            logger.warn("标签写回后落盘失败 project={}", project.id, e);
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        result.put("projectId", String.valueOf(project.id));
        result.put("applied", applied);
        result.put("skipped", skipped);
        result.put("mainTable", mainTable);
        result.put("saved", saved);
        respondJSON(response, result);
    }

    private static boolean sameValue(Cell a, Cell b) {
        if (a == null || b == null) {
            return a == b;
        }
        Object av = a.value;
        Object bv = b.value;
        return av == null ? bv == null : av.equals(bv);
    }

    private static void respondError(HttpServletResponse response, String message) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", "error");
        node.put("message", message);
        respondJSON(response, node);
    }
}
