/*
 * Data Quality Extension - Submit Extraction Feedback Command
 */
package com.google.refine.extension.quality.commands;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.auth.RefineAuthGate;
import com.google.refine.commands.Command;
import com.google.refine.io.FileProjectManager;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;

/**
 * 抽取反馈 · 最终态快照比对与提交。
 *
 * <p>口径见 {@code docs/extraction-feedback-loop-design.md} §3.2：不取事件流，只比
 * 「当前单元格值」与「pageMap 里胜出候选（{@code w=1}）的值」，故撤销后值回原值，标签自动消失。</p>
 *
 * <p>比对结果 POST 到 RCP {@code /api/extraction-feedback/samples}；平台侧再做一次同口径复核，
 * 并把 {@code normalized}（口径差异）与强标签分开存。请求带当前会话令牌，未登录场景（平台
 * 内部拉取）改为 {@code push=0}，只返回计算出的载荷，由 RCP 直接入库。</p>
 *
 * <p>可休眠：本类只被命令入口调用；失效时前端埋点与导出均不受影响。</p>
 */
public class SubmitExtractionFeedbackCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("SubmitExtractionFeedbackCommand");
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 与 {@code BatchExtractionManager.PAGE_MAP_KEY} 保持一致（本模块不依赖 FilesExtension）。 */
    public static final String PAGE_MAP_KEY = "aimpElementPageMap";
    /** 卷级汇总表 id（与 BatchExtractionCommand.SUMMARY_SHEET_ID 一致）。 */
    public static final String SUMMARY_SHEET_ID = "batch#卷级";
    public static final String SUMMARY_PAGE_MAP_PREFIX = SUMMARY_SHEET_ID + ":";
    /** 件首页定位节点，不是要素列。 */
    public static final String PIECE_START_KEY = "__piece_start__";

    private static final String PROP_PLATFORM_URL = "refine.feedback.platform-url";
    private static final String PROP_TOKEN = "refine.feedback.token";
    /**
     * 未修改单元格的采样率（校准器负类）。{@code 0} = 关闭，回到 §3.2「unchanged 不上传」。
     *
     * <p>只上传强标签（{@code wrong}/{@code cleared}）会让校准器只见过「被改过的值」，
     * 学到的是 P(被改) 而非 P(正确)；按比例补少量未修改单元格作真负类后，
     * 分数方向与量纲才有实际含义。</p>
     */
    private static final String PROP_UNCHANGED_RATE = "refine.feedback.unchanged-rate";
    private static final double DEFAULT_UNCHANGED_RATE = 0.10;
    private static final String DIR_NAME = "feedback-pending";
    private static final int TIMEOUT_MS = 10_000;

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

        String source = trim(request.getParameter("source"));
        if (source == null) {
            source = "ui_export";
        }
        // 来源标记（可选）：自动化/模拟数据生成器据此区分合成批次（RCP 冻结侧按它做留出集过滤）
        String origin = trim(request.getParameter("origin"));
        boolean push = !"0".equals(trim(request.getParameter("push")));

        ObjectNode payload;
        try {
            payload = buildPayload(project, source, origin);
        } catch (Exception e) {
            logger.warn("构建反馈载荷失败 project={}", project.id, e);
            respondError(response, "构建反馈载荷失败: " + e.getMessage());
            return;
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        result.put("projectId", String.valueOf(project.id));
        result.put("sampleCount", payload.path("samples").size());

        if (!push) {
            // 平台内部拉取模式：不落盘、不回传令牌，载荷随响应返回
            result.set("payload", payload);
            respondJSON(response, result);
            return;
        }

        String token = trim(RefineAuthGate.tokenOf(request));
        if (token == null) {
            token = trim(System.getProperty(PROP_TOKEN));
        }
        if (token == null) {
            String saved = savePending(project.id, payload);
            result.put("pushed", false);
            result.put("reason", "无会话令牌，已暂存待平台拉取");
            result.put("pendingFile", saved == null ? "" : saved);
            respondJSON(response, result);
            return;
        }

        try {
            String body = push(payload, token);
            result.put("pushed", true);
            result.set("remote", body == null || body.isEmpty()
                    ? mapper.createObjectNode() : mapper.readTree(body));
        } catch (Exception e) {
            String saved = savePending(project.id, payload);
            logger.warn("反馈提交失败，已暂存 project={} file={} err={}", project.id, saved, e.toString());
            result.put("pushed", false);
            result.put("reason", "平台不可达或拒绝，已暂存: " + e.getMessage());
            result.put("pendingFile", saved == null ? "" : saved);
        }
        respondJSON(response, result);
    }

    // ================= 快照比对 =================

    private ObjectNode buildPayload(Project project, String source, String origin) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("source", source);
        payload.put("projectId", String.valueOf(project.id));
        if (origin != null) {
            payload.put("origin", origin);
        }

        JsonNode pageMap = pageMapOf(project);
        ObjectNode opportunities = mapper.createObjectNode();
        ArrayNode samples = mapper.createArrayNode();
        Set<String> volumes = new HashSet<>();
        double unchangedRate = unchangedRate();

        if (pageMap != null) {
            // 卷内表：pageMap 键为行号；单表项目（按件模板）直接用主表
            SheetData inner = project.getSheetData("batch#卷内");
            if (inner == null) {
                inner = new SheetData("batch#卷内", "卷内", "");
                inner.rows = project.rows;
                inner.columnModel = project.columnModel;
            }
            collect(inner, pageMap, false, project.id, unchangedRate,
                    opportunities, samples, volumes);

            SheetData summary = project.getSheetData(SUMMARY_SHEET_ID);
            if (summary != null) {
                collect(summary, pageMap, true, project.id, unchangedRate,
                        opportunities, samples, volumes);
            }
        }

        payload.set("opportunities", opportunities);
        payload.set("samples", samples);
        payload.put("volumeCount", volumes.isEmpty() ? 1 : volumes.size());
        return payload;
    }

    private void collect(SheetData sheet, JsonNode pageMap, boolean summarySheet, long projectId,
            double unchangedRate, ObjectNode opportunities, ArrayNode samples,
            Set<String> volumes) {
        if (sheet == null || sheet.rows == null || sheet.columnModel == null) {
            return;
        }
        Column volumeColumn = sheet.columnModel.getColumnByName("案卷号");
        Map<String, Integer> opportunityByField = new LinkedHashMap<>();

        for (int i = 0; i < sheet.rows.size(); i++) {
            String key = summarySheet ? SUMMARY_PAGE_MAP_PREFIX + i : String.valueOf(i);
            JsonNode rowNode = pageMap.get(key);
            if (rowNode == null || !rowNode.isObject()) {
                continue;
            }
            Row row = sheet.rows.get(i);
            String volumeId = cellText(row, volumeColumn);
            boolean counted = false;

            Iterator<Map.Entry<String, JsonNode>> fields = rowNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String fieldKey = e.getKey();
                if (fieldKey.startsWith("__") || PIECE_START_KEY.equals(fieldKey)) {
                    continue;
                }
                JsonNode candidates = e.getValue();
                if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
                    continue;
                }
                JsonNode winner = winnerOf(candidates);
                if (winner == null) {
                    continue;
                }
                Column column = sheet.columnModel.getColumnByName(fieldKey);
                if (column == null) {
                    continue;
                }
                String pred = text(winner.get("v"));
                String cur = cellText(row, column);
                int pageNo = winner.path("p").asInt(0);

                // 机会数：该卷该字段「有胜出候选可评」的单元格数（评估期分母）
                opportunityByField.merge(fieldKey, 1, Integer::sum);
                counted = true;

                if (eq(cur, pred)) {
                    // §3.2：值未变无强标签，默认不上传；但校准器只见过「被改过的值」时
                    // 学到的是 P(被改) 而非 P(正确)，故按 unchangedRate 确定性抽样补负类。
                    if (cur != null && pickUnchanged(projectId, volumeId, pageNo,
                            fieldKey, pred, unchangedRate)) {
                        samples.add(sampleOf(volumeId, pageNo, fieldKey, pred, cur,
                                candidates, winner, "unchanged"));
                    }
                    continue;
                }
                samples.add(sampleOf(volumeId, pageNo, fieldKey, pred, cur,
                        candidates, winner, null));
            }
            if (counted && volumeId != null) {
                volumes.add(volumeId);
            }
        }
        for (Map.Entry<String, Integer> e : opportunityByField.entrySet()) {
            opportunities.put(e.getKey(), e.getValue());
        }
    }

    /** 组装一条反馈样本；{@code kind} 为 null 时由平台按 pred/label 兜底判定。 */
    private ObjectNode sampleOf(String volumeId, int pageNo, String fieldKey, String pred,
                                String cur, JsonNode candidates, JsonNode winner, String kind) {
        ObjectNode sample = mapper.createObjectNode();
        sample.put("volumeId", volumeId);
        sample.put("pageNo", pageNo);
        sample.put("fieldKey", fieldKey);
        if (pred != null) {
            sample.put("predValue", pred);
        }
        if (cur != null) {
            sample.put("labelValue", cur);
        }
        if (kind != null) {
            sample.put("kind", kind);
        }
        if (winner.has("q")) {
            sample.put("qualityScore", winner.get("q").asDouble());
        }
        if (winner.has("qf")) {
            sample.set("qualityFlags", winner.get("qf"));
        }
        sample.set("features", features(fieldKey, candidates, winner, cur, pred));
        return sample;
    }

    /** 未修改单元格采样率：{@code refine.feedback.unchanged-rate}（0~1），非法或缺省取 0.10。 */
    private static double unchangedRate() {
        String raw = trim(System.getProperty(PROP_UNCHANGED_RATE));
        if (raw == null) {
            return DEFAULT_UNCHANGED_RATE;
        }
        try {
            double v = Double.parseDouble(raw);
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        } catch (NumberFormatException e) {
            return DEFAULT_UNCHANGED_RATE;
        }
    }

    /**
     * 确定性抽样：同一（项目/卷/页/字段/值）每次导出结果一致，便于重放与排查；
     * 不用随机数，避免同一快照重复导出时负类集合抖动。
     */
    private static boolean pickUnchanged(long projectId, String volumeId, int pageNo,
                                         String fieldKey, String pred, double rate) {
        if (rate <= 0) {
            return false;
        }
        if (rate >= 1) {
            return true;
        }
        String key = projectId + "|" + volumeId + "|" + pageNo + "|" + fieldKey + "|" + pred;
        long bucket = Math.floorMod(key.hashCode(), 1_000_000L);
        return bucket < Math.round(rate * 1_000_000L);
    }

    /** 胜出候选：{@code w=1}，每列最多一条。 */
    private static JsonNode winnerOf(JsonNode candidates) {
        for (JsonNode c : candidates) {
            if (c.path("w").asInt(0) == 1) {
                return c;
            }
        }
        return null;
    }

    /**
     * 结构化特征（§7.1，{@code fv1}）：只带值形态与候选一致性，不含原文。
     */
    private ObjectNode features(String fieldKey, JsonNode candidates, JsonNode winner,
                                String label, String pred) {
        ObjectNode f = mapper.createObjectNode();
        f.put("fv", "fv1");
        f.put("candidateCount", candidates.size());
        int winnerCount = 0;
        int sameValue = 0;
        for (JsonNode c : candidates) {
            if (c.path("w").asInt(0) == 1) {
                winnerCount++;
            }
            if (eq(text(c.get("v")), pred)) {
                sameValue++;
            }
        }
        f.put("winnerCount", winnerCount);
        f.put("sameValueCandidates", sameValue);
        f.put("fieldType", fieldType(fieldKey));
        f.put("labelLength", label == null ? 0 : label.length());
        f.put("predLength", pred == null ? 0 : pred.length());
        f.put("hasBookTitle", label != null && (label.contains("《") || label.contains("》")));
        f.put("dateFormatOk", label != null && isDateLike(label));
        f.put("docNoLike", label != null && isDocNoLike(label));
        if (winner.has("p")) {
            f.put("pageNo", winner.get("p").asInt(0));
        }
        // 页内结构特征（AIMP 侧 quality_features，fv1）：行号/总行数/页首页尾/整行/命中数。
        // OR 没有 OCR 全文，反算必然丢位置信息，故随 pageMap 的 feat 原样平铺进样本特征。
        JsonNode feat = winner.get("feat");
        if (feat != null && feat.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = feat.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> fe = it.next();
                JsonNode fv = fe.getValue();
                if (fv != null && (fv.isNumber() || fv.isBoolean())) {
                    f.set(fe.getKey(), fv);
                }
            }
        }
        return f;
    }

    private static String fieldType(String fieldKey) {
        if ("成文日期".equals(fieldKey)) {
            return "date";
        }
        if ("文号".equals(fieldKey)) {
            return "doc_no";
        }
        if ("题名".equals(fieldKey)) {
            return "title";
        }
        if ("责任者".equals(fieldKey)) {
            return "responsible";
        }
        return "other";
    }

    private static boolean isDateLike(String v) {
        String s = v.trim();
        return s.matches("\\d{4}[年./\\-]\\d{1,2}[月./\\-]\\d{1,2}日?") || s.matches("\\d{8}");
    }

    private static boolean isDocNoLike(String v) {
        return v.trim().matches(".*[〔\\[（(]\\s?\\d{4}\\s?[〕\\]）)]\\s?\\d+\\s?号.*");
    }

    private static JsonNode pageMapOf(Project project) {
        if (project.getMetadata() == null) {
            return null;
        }
        Object stored = project.getMetadata().getCustomMetadata(PAGE_MAP_KEY);
        if (!(stored instanceof String) || ((String) stored).isEmpty()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree((String) stored);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 提交 / 暂存 =================

    private String push(ObjectNode payload, String token) throws IOException {
        String base = trim(System.getProperty(PROP_PLATFORM_URL));
        if (base == null) {
            base = trim(System.getProperty(RefineAuthGate.PROP_PLATFORM_URL));
        }
        if (base == null) {
            base = "http://127.0.0.1:8090";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpURLConnection conn = (HttpURLConnection)
                new URL(base + "/api/extraction-feedback/samples").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("X-Auth-Token", token);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int code = conn.getResponseCode();
        java.io.InputStream in = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            throw new IOException("平台返回 " + code + ": " + text);
        }
        return text;
    }

    /** 提交失败时把载荷暂存到工作区，平台每日空闲兜底扫描会再收集。 */
    private String savePending(long projectId, ObjectNode payload) {
        try {
            File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
            Path dir = new File(workspaceDir, DIR_NAME).toPath();
            Files.createDirectories(dir);
            String name = projectId + "-"
                    + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + ".json";
            Path file = dir.resolve(name);
            Files.write(file, payload.toString().getBytes(StandardCharsets.UTF_8));
            return file.toString();
        } catch (Exception e) {
            logger.warn("暂存反馈载荷失败 project={}", projectId, e);
            return null;
        }
    }

    // ================= 工具 =================

    private static String cellText(Row row, Column column) {
        if (row == null || column == null) {
            return null;
        }
        Cell cell = row.getCell(column.getCellIndex());
        Object v = cell == null ? null : cell.value;
        return v == null ? null : trim(String.valueOf(v));
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        return trim(n.asText());
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void respondError(HttpServletResponse response, String message) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", "error");
        node.put("message", message);
        respondJSON(response, node);
    }
}
