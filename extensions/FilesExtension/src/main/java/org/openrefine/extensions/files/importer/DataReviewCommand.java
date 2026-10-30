package org.openrefine.extensions.files.importer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.commands.Command;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.jiongche.sdk.aimp.AimpTransport;
import com.jiongche.sdk.aimp.HttpAimpTransport;
import com.jiongche.sdk.aimp.Multipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 数据校审命令（极简版）。
 *
 * <p>端到端链路：新建项目页「校审模式」→ 本命令创建单表项目 → 逐文件调用 AIMP
 * {@code /review/element}（存在类：印章检测 / 指纹检测）→ 校审结果逐行写入项目数据区 →
 * 数据行的单元格按 pageMap 定位到被校审的原件页。</p>
 *
 * <p>印章与指纹同源出框，AIMP 返回 {@code sealCount} / {@code fingerprintCount} 与每个框的
 * {@code regionType}；本命令只按所选规则判存在性，不改变 AIMP 的框取舍。</p>
 *
 * <p>子命令：start / progress / cancel / list。校审结果直接写进 OR 项目数据区
 * （不另建结果库），卷级/件级拆分留待后续版本。</p>
 */
public class DataReviewCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(DataReviewCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_AIMP_URL = "http://127.0.0.1:7998";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_FAILED = "failed";

    /** 与前端 FileViewPanel 约定的「文件夹路径」列，缺失则该行无法预览原件 */
    private static final String FOLDER_PATH_COLUMN = "文件夹路径";

    /**
     * pageMap 行节点内的保留键「校审标注」：{@code {f, p, w, h, items:[{x,y,w,h,label,message,color}]}}。
     * 坐标为原图像素；前端据此在预览区原图上框出问题位置并显示错误类型标签。
     * 与 {@code __piece_start__} 同属 {@code __} 前缀保留键，不会与要素候选混淆。
     */
    public static final String ANNOTATION_KEY = "__review_ann__";

    private static final List<String> COLUMNS = Arrays.asList(
            "序号", "案卷号", "文件名", "页数", "规则分类", "校审规则",
            "校审结果", "印章数量", "指纹数量", "说明", FOLDER_PATH_COLUMN);

    /** 内存任务表：进程重启即丢，历史任务由登记表兜底 */
    private static final Map<Long, Task> TASKS = new ConcurrentHashMap<>();

    /** 每写多少行落盘一次项目 */
    private static final int SAVE_EVERY_ROWS = 20;

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }
        String subCommand = request.getParameter("subCommand");
        if ("start".equals(subCommand)) {
            doStart(request, response);
        } else if ("progress".equals(subCommand)) {
            doProgress(request, response);
        } else if ("cancel".equals(subCommand)) {
            doCancel(request, response);
        } else if ("list".equals(subCommand)) {
            doList(request, response);
        } else {
            respondError(response, "Unknown subCommand: " + subCommand);
        }
    }

    /* ---------------- start ---------------- */

    private void doStart(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String rootPath = trim(request.getParameter("rootPath"));
            String projectName = trim(request.getParameter("projectName"));
            String rulesJson = request.getParameter("rules");
            String aimpUrl = aimpUrl(request.getParameter("aimpUrl"));
            double confidence = parseDouble(request.getParameter("confidence"), 0.5);

            if (rootPath.isEmpty()) {
                respondError(response, "缺少 rootPath 参数");
                return;
            }

            List<ReviewRule> rules = parseRules(rulesJson);
            if (rules.isEmpty()) {
                respondError(response, "请至少选择一项校审规则");
                return;
            }
            for (ReviewRule rule : rules) {
                if (!"seal".equals(rule.key) && !"fingerprint".equals(rule.key)) {
                    respondError(response, "暂不支持的校审规则: " + rule.name);
                    return;
                }
            }

            // AIMP 可达性：存在类检测走 /review/element，模块未启动时直接给出明确提示
            AimpLlmClient probe = new AimpLlmClient(aimpUrl);
            AimpLlmClient.CompatibilityResult compat = probe.checkCompatibility();
            if (!compat.reachable) {
                ObjectNode err = mapper.createObjectNode();
                err.put("code", "error");
                err.put("message", "AI服务模块不可用，请检查或重启模块");
                respondJSON(response, err);
                return;
            }

            List<UnitScanner.Volume> units = scanUnits(rootPath);
            if (units.isEmpty()) {
                respondError(response, "未发现可校审的文件：目录下需为包含图像/PDF的子文件夹或图像文件");
                return;
            }
            int totalFiles = 0;
            for (UnitScanner.Volume unit : units) totalFiles += unit.pages.size();

            Project project = createEmptyProject(projectName, units);
            Task task = new Task(project.id, rootPath, aimpUrl, confidence, rules, units, totalFiles);
            TASKS.put(project.id, task);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("projectId", project.id);
            record.put("projectName", project.getMetadata() == null ? projectName : project.getMetadata().getName());
            record.put("rootPath", rootPath);
            record.put("mode", "review-file");
            record.put("rules", rulesJson == null ? "" : rulesJson);
            record.put("totalFiles", totalFiles);
            record.put("processedFiles", 0);
            record.put("passed", 0);
            record.put("failed", 0);
            record.put("unknown", 0);
            record.put("status", STATUS_RUNNING);
            record.put("startedAt", task.startedAt);
            DataReviewTaskRegistry.upsert(project.id, record);

            Thread worker = new Thread(() -> run(task), "data-review-" + project.id);
            worker.setDaemon(true);
            task.worker = worker;
            worker.start();

            ObjectNode result = mapper.createObjectNode();
            result.put("code", "ok");
            result.put("projectId", project.id);
            result.put("projectName", projectName);
            result.put("totalFiles", totalFiles);
            respondJSON(response, result);
        } catch (Exception e) {
            logger.error("data-review start failed", e);
            respondError(response, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** 解析前端提交的规则清单：[{category, key, name}] */
    private static List<ReviewRule> parseRules(String json) {
        List<ReviewRule> rules = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return rules;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) return rules;
            for (JsonNode n : node) {
                String key = n.path("key").asText("").trim();
                if (key.isEmpty()) continue;
                ReviewRule rule = new ReviewRule();
                rule.key = key;
                rule.category = n.path("category").asText("");
                rule.name = n.path("name").asText(key);
                rules.add(rule);
            }
        } catch (Exception e) {
            logger.warn("校审规则不是合法 JSON 数组，已忽略: " + e.getMessage());
        }
        return rules;
    }

    /**
     * 扫描待校审单元：优先按「根目录下每个子文件夹为一卷」，其次按文件级目录，
     * 最后把根目录自身当作一卷（根目录下直接放图像的情况）。
     */
    private static List<UnitScanner.Volume> scanUnits(String rootPath) {
        List<UnitScanner.Volume> units = UnitScanner.scanVolumes(rootPath);
        if (!units.isEmpty()) return units;
        units = UnitScanner.scanCases(rootPath);
        if (!units.isEmpty()) return units;

        File root = new File(rootPath);
        File[] files = root.listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        if (files == null) return units;
        List<String> images = new ArrayList<>();
        for (File f : files) {
            if (UnitScanner.IMAGE_EXTENSIONS.contains(ext(f.getName()))) {
                images.add(f.getAbsolutePath());
            }
        }
        if (images.isEmpty()) return units;
        images.sort((a, b) -> UnitScanner.NATURAL_ORDER.compare(new File(a).getName(), new File(b).getName()));
        UnitScanner.Volume v = new UnitScanner.Volume();
        v.name = root.getName();
        v.path = root.getAbsolutePath();
        v.pages.addAll(images);
        for (String p : images) v.pageNos.add(UnitScanner.parseInternalPageNo(p));
        v.numberedPages = v.pageNos.stream().anyMatch(java.util.Objects::nonNull);
        units.add(v);
        return units;
    }

    private Project createEmptyProject(String projectName, List<UnitScanner.Volume> units) throws Exception {
        Project project = new Project();
        addColumns(project.columnModel, COLUMNS);
        project.update();

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(!projectName.isEmpty() ? projectName : "数据校审");
        metadata.setEncoding("UTF-8");
        ProjectManager.singleton.registerProject(project, metadata);
        return project;
    }

    private static void addColumns(ColumnModel columnModel, List<String> names) throws Exception {
        for (int i = 0; i < names.size(); i++) {
            columnModel.addColumn(i, new Column(i, names.get(i)), false);
        }
    }

    /* ---------------- 后台执行 ---------------- */

    private void run(Task task) {
        int rowIndex = 0;
        int rowsSinceSave = 0;
        try {
            Project project = ProjectManager.singleton.getProject(task.projectId);
            if (project == null) {
                finish(task, STATUS_FAILED, "项目不存在或已被删除");
                return;
            }
            for (UnitScanner.Volume unit : task.units) {
                if (task.cancelled) break;
                task.currentUnit = unit.name;
                for (int i = 0; i < unit.pages.size(); i++) {
                    if (task.cancelled) break;
                    String pagePath = unit.pages.get(i);
                    String fileName = new File(pagePath).getName();
                    int pageNo = pageNoOf(unit, i);
                    task.currentFile = fileName;

                    Detection detection = detect(task, pagePath);
                    Row row = buildRow(project.columnModel, rowIndex + 1, unit, fileName, task, detection);
                    ObjectNode pageMapRow = buildPageMapRow(unit, fileName, pageNo, task, detection, row);

                    synchronized (task) {
                        project.rows.add(row);
                        if (pageMapRow != null) {
                            task.pageMap.set(String.valueOf(rowIndex), pageMapRow);
                        }
                        if (detection.ok) {
                            if ("通过".equals(detection.resultLabel(task))) task.passed++;
                            else task.failed++;
                        } else {
                            task.unknown++;
                        }
                        task.rowsAppended++;
                        task.processedFiles++;
                    }
                    rowIndex++;
                    flushPageMap(task);
                    if (++rowsSinceSave >= SAVE_EVERY_ROWS) {
                        rowsSinceSave = 0;
                        saveProject(task);
                    }
                }
            }
            flushPageMap(task);
            saveProject(task);
            finish(task, task.cancelled ? STATUS_CANCELLED : STATUS_COMPLETED, task.cancelled
                    ? "已取消" : "校审完成");
        } catch (Exception e) {
            logger.error("data-review run failed", e);
            finish(task, STATUS_FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** 卷内页号：投放命名规范里解析出的页号优先，解析不出时用列表下标（从 1 起） */
    private static int pageNoOf(UnitScanner.Volume unit, int i) {
        if (unit.pageNos != null && i < unit.pageNos.size()) {
            Integer no = unit.pageNos.get(i);
            if (no != null) return no;
        }
        return i + 1;
    }

    private Row buildRow(ColumnModel columnModel, int seq, UnitScanner.Volume unit,
                         String fileName, Task task, Detection detection) {
        Row row = new Row(columnModel.getMaxCellIndex() + 1);
        put(columnModel, row, "序号", seq);
        put(columnModel, row, "案卷号", unit.name);
        put(columnModel, row, "文件名", fileName);
        put(columnModel, row, "页数", detection.pageCount);
        put(columnModel, row, "规则分类", "存在类");
        put(columnModel, row, "校审规则", ruleNameList(task));
        put(columnModel, row, "校审结果", detection.resultLabel(task));
        put(columnModel, row, "印章数量", detection.ok ? String.valueOf(detection.sealCount) : "");
        put(columnModel, row, "指纹数量", detection.ok ? String.valueOf(detection.fingerprintCount) : "");
        put(columnModel, row, "说明", detection.describe(task));
        put(columnModel, row, FOLDER_PATH_COLUMN, unit.path);
        return row;
    }

    /** 本次校审所选规则名（用于「校审规则」列） */
    private static String ruleNameList(Task task) {
        List<String> names = new ArrayList<>();
        for (ReviewRule rule : task.rules) {
            if (!names.contains(rule.name)) names.add(rule.name);
        }
        return String.join("、", names);
    }

    /** 本次校审所选规则键（提交给 AIMP 的 element_type，逗号分隔） */
    private static String ruleKeyList(Task task) {
        List<String> keys = new ArrayList<>();
        if (hasRule(task, "seal")) keys.add("seal");
        if (hasRule(task, "fingerprint")) keys.add("fingerprint");
        return String.join(",", keys);
    }

    private static boolean hasRule(Task task, String key) {
        for (ReviewRule rule : task.rules) {
            if (key.equals(rule.key)) return true;
        }
        return false;
    }

    /**
     * 行 → 列 → 证据页：单元格点击据此定位到被校审的原件页。
     * 结构与批量抽取一致 {@code {v,c,p,f,w}}，另加行级兜底 {@code __piece_start__}。
     */
    private static ObjectNode buildPageMapRow(UnitScanner.Volume unit, String fileName, int pageNo,
                                              Task task, Detection detection, Row row) {
        ObjectNode rowNode = mapper.createObjectNode();
        if (detection.ok) {
            ObjectNode cand = mapper.createObjectNode();
            cand.put("v", detection.resultLabel(task));
            cand.put("p", pageNo);
            cand.put("f", fileName);
            cand.put("w", 1);
            if (detection.maxConfidence != null) cand.put("c", detection.maxConfidence);
            ArrayNode arr = rowNode.putArray("校审结果");
            arr.add(cand);
            ArrayNode sealArr = rowNode.putArray("印章数量");
            ObjectNode sealCand = sealArr.addObject();
            sealCand.put("v", String.valueOf(detection.sealCount));
            sealCand.put("p", pageNo);
            sealCand.put("f", fileName);
            ArrayNode fpArr = rowNode.putArray("指纹数量");
            ObjectNode fpCand = fpArr.addObject();
            fpCand.put("v", String.valueOf(detection.fingerprintCount));
            fpCand.put("p", pageNo);
            fpCand.put("f", fileName);
        }
        ObjectNode start = mapper.createObjectNode();
        start.put("v", "校审原件");
        start.put("p", pageNo);
        start.put("f", fileName);
        rowNode.set(BatchExtractionManager.PIECE_START_KEY, start);
        ObjectNode ann = buildAnnotation(fileName, pageNo, task, detection);
        if (ann != null) {
            rowNode.set(ANNOTATION_KEY, ann);
        }
        return rowNode;
    }

    /**
     * 校审标注：原图像素坐标 + 标签，供预览区在图片上框出检出位置。
     *
     * <p>item 分两类：{@code kind="detected"} 为视觉级检出的对象（带 bbox，预览区画框），
     * {@code kind="missing"} 为视觉级检查缺项（不带 bbox，只在预览区「缺失标签」中列出）。</p>
     *
     * @return 无任何标签时返回 null，避免为空行写入标注
     */
    private static ObjectNode buildAnnotation(String fileName, int pageNo, Task task, Detection detection) {
        if (!detection.ok) {
            return null;
        }
        ArrayNode items = mapper.createArrayNode();
        for (double[] box : detection.sealBoxes) {
            items.add(annotationItem("印章", "seal", box,
                    String.format("检出印章，置信度 %.2f", box[4])));
        }
        for (double[] box : detection.fingerprintBoxes) {
            items.add(annotationItem("疑似指纹", "fingerprint", box,
                    String.format("检出疑似指纹，置信度 %.2f", box[4])));
        }
        // 视觉级缺项：所选规则预期「有」但未检出，单独归入「缺失标签」并列出
        if (hasRule(task, "seal") && detection.sealCount == 0) {
            ObjectNode missing = items.addObject();
            missing.put("kind", "missing");
            missing.put("label", "印章");
            missing.put("color", "seal");
            missing.put("message", "未检出印章（可能存在漏章）");
        }
        if (hasRule(task, "fingerprint") && detection.fingerprintCount == 0) {
            ObjectNode missing = items.addObject();
            missing.put("kind", "missing");
            missing.put("label", "指纹");
            missing.put("color", "fingerprint");
            missing.put("message", "未检出指纹（可能存在漏印）");
        }
        if (items.size() == 0) {
            return null;
        }
        ObjectNode ann = mapper.createObjectNode();
        ann.put("f", fileName);
        ann.put("p", pageNo);
        if (detection.imageWidth > 0) ann.put("w", detection.imageWidth);
        if (detection.imageHeight > 0) ann.put("h", detection.imageHeight);
        ann.set("items", items);
        return ann;
    }

    private static ObjectNode annotationItem(String label, String color, double[] box, String message) {
        ObjectNode item = mapper.createObjectNode();
        item.put("kind", "detected");
        item.put("x", box[0]);
        item.put("y", box[1]);
        item.put("w", Math.max(1.0, box[2] - box[0]));
        item.put("h", Math.max(1.0, box[3] - box[1]));
        item.put("label", label);
        item.put("color", color);
        item.put("message", message);
        return item;
    }

    private void flushPageMap(Task task) {
        synchronized (task) {
            if (task.pageMap.size() == 0) return;
            Project project = ProjectManager.singleton.getProject(task.projectId);
            if (project == null) return;
            project.getMetadata().setCustomMetadata(BatchExtractionManager.PAGE_MAP_KEY, task.pageMap.toString());
        }
    }

    private void saveProject(Task task) {
        try {
            Project project = ProjectManager.singleton.getProject(task.projectId);
            if (project == null) return;
            project.update();
            ProjectManager.singleton.ensureProjectSaved(task.projectId);
        } catch (Exception e) {
            logger.warn("校审项目落盘失败 {}", task.projectId, e);
        }
    }

    private void finish(Task task, String status, String message) {
        task.status = status;
        task.message = message;
        task.finishedAt = System.currentTimeMillis();
        DataReviewTaskRegistry.updateStatus(task.projectId, status, message,
                task.processedFiles, task.passed, task.failed, task.unknown);
    }

    /** 调用 AIMP /review/element 做存在类检测（印章 / 指纹，同源出框后分流） */
    private Detection detect(Task task, String filePath) {
        Detection d = new Detection();
        d.pageCount = ext(filePath).equals("pdf") ? "" : "1";
        if (!UnitScanner.IMAGE_EXTENSIONS.contains(ext(filePath))) {
            d.description = "暂不支持该文件类型（PDF 等），请按页图像目录组织";
            return d;
        }
        try {
            File file = new File(filePath);
            byte[] bytes = Files.readAllBytes(file.toPath());
            Multipart multipart = Multipart.webKitBoundary()
                    .file("img", file.getName(), bytes)
                    .field("element_type", ruleKeyList(task))
                    .field("confidence", String.valueOf(task.confidence));
            AimpTransport.Response resp = new HttpAimpTransport(task.aimpUrl).execute(
                    AimpTransport.Request.post("/review/element", multipart.contentType(),
                            multipart.bytes(), 15000, 60000));
            if (resp.status() != 200) {
                d.description = "AI服务返回 HTTP " + resp.status();
                return d;
            }
            JsonNode json = mapper.readTree(resp.text());
            if (!"ok".equals(json.path("code").asText())) {
                d.description = json.path("message").asText("校审检测失败");
                return d;
            }
            d.ok = true;
            d.sealCount = json.path("sealCount").asInt(json.path("count").asInt(0));
            d.fingerprintCount = json.path("fingerprintCount").asInt(0);
            d.imageWidth = json.path("imageWidth").asInt(0);
            d.imageHeight = json.path("imageHeight").asInt(0);
            JsonNode regions = json.path("regions");
            if (regions.isArray()) {
                for (JsonNode region : regions) {
                    JsonNode bbox = region.path("bbox");
                    if (!bbox.isArray() || bbox.size() < 4) continue;
                    double[] box = new double[5];
                    for (int i = 0; i < 4; i++) box[i] = bbox.get(i).asDouble();
                    box[4] = region.path("confidence").asDouble(0.0);
                    // AIMP 已按 gstd 标好框类型；缺字段时按印章兜底（兼容旧版接口）
                    if ("fingerprint".equalsIgnoreCase(region.path("regionType").asText())) {
                        d.fingerprintBoxes.add(box);
                    } else {
                        d.sealBoxes.add(box);
                    }
                }
            }
            // 置信度取全部印章框的最高值，供预览区提示
            for (double[] box : d.sealBoxes) {
                if (d.maxConfidence == null || box[4] > d.maxConfidence) d.maxConfidence = box[4];
            }
            return d;
        } catch (Exception e) {
            d.description = "校审检测失败: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            return d;
        }
    }

    /* ---------------- progress / cancel / list ---------------- */

    private void doProgress(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(trim(request.getParameter("project")));
        } catch (NumberFormatException e) {
            respondError(response, "缺少或非法的 project 参数");
            return;
        }
        ObjectNode result = mapper.createObjectNode();
        Task task = TASKS.get(pid);
        if (task == null) {
            // 进程重启后内存任务丢失，按登记表输出终态
            Map<String, Object> record = DataReviewTaskRegistry.get(pid);
            if (record == null) {
                respondError(response, "未找到校审任务: " + pid);
                return;
            }
            result.put("code", "ok");
            result.put("projectId", pid);
            result.put("status", String.valueOf(record.get("status")));
            result.put("processedFiles", intOf(record.get("processedFiles")));
            result.put("totalFiles", intOf(record.get("totalFiles")));
            result.put("passed", intOf(record.get("passed")));
            result.put("failed", intOf(record.get("failed")));
            result.put("unknown", intOf(record.get("unknown")));
            result.put("message", String.valueOf(record.getOrDefault("message", "")));
            respondJSON(response, result);
            return;
        }
        synchronized (task) {
            result.put("code", "ok");
            result.put("projectId", task.projectId);
            result.put("status", task.status);
            result.put("processedFiles", task.processedFiles);
            result.put("totalFiles", task.totalFiles);
            result.put("passed", task.passed);
            result.put("failed", task.failed);
            result.put("unknown", task.unknown);
            result.put("rowsAppended", task.rowsAppended);
            result.put("currentFile", task.currentFile);
            result.put("currentUnit", task.currentUnit);
            result.put("message", task.message);
            result.put("startedAt", task.startedAt);
        }
        respondJSON(response, result);
    }

    private void doCancel(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(trim(request.getParameter("project")));
        } catch (NumberFormatException e) {
            respondError(response, "缺少或非法的 project 参数");
            return;
        }
        Task task = TASKS.get(pid);
        if (task == null) {
            respondError(response, "未找到校审任务: " + pid);
            return;
        }
        task.cancelled = true;
        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        result.put("message", "取消请求已提交");
        respondJSON(response, result);
    }

    private void doList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = result.putArray("tasks");
        for (Map<String, Object> record : DataReviewTaskRegistry.list()) {
            ObjectNode node = mapper.valueToTree(record);
            long pid = longOf(record.get("projectId"));
            Task live = TASKS.get(pid);
            if (live != null) {
                synchronized (live) {
                    node.put("status", live.status);
                    node.put("processedFiles", live.processedFiles);
                    node.put("totalFiles", live.totalFiles);
                    node.put("passed", live.passed);
                    node.put("failed", live.failed);
                    node.put("unknown", live.unknown);
                    node.put("currentFile", live.currentFile);
                    node.put("message", live.message);
                }
                node.put("live", true);
            } else {
                String status = String.valueOf(record.get("status"));
                if (STATUS_RUNNING.equals(status)) {
                    node.put("status", "interrupted");
                    node.put("message", "上次运行被中断");
                }
                node.put("live", false);
            }
            arr.add(node);
        }
        result.put("code", "ok");
        respondJSON(response, result);
    }

    /* ---------------- 工具 ---------------- */

    private static String aimpUrl(String param) {
        if (param != null && !param.trim().isEmpty()) return param.trim();
        String configured = ExtractionConfigStore.getString("aimp.server");
        return configured != null ? configured : DEFAULT_AIMP_URL;
    }

    private static void put(ColumnModel columnModel, Row row, String columnName, java.io.Serializable value) {
        Column column = columnModel.getColumnByName(columnName);
        if (column != null) {
            row.setCell(column.getCellIndex(), new Cell(value, null));
        }
    }

    private static String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(String.valueOf(s).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int intOf(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long longOf(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    private void respondError(HttpServletResponse response, String message) throws IOException {
        ObjectNode err = mapper.createObjectNode();
        err.put("code", "error");
        err.put("message", message);
        respondJSON(response, err);
    }

    /* ---------------- 内部类型 ---------------- */

    private static final class ReviewRule {
        String key;
        String category;
        String name;
    }

    private static final class Detection {
        boolean ok;
        /** 印章流框数（AIMP 按 gstd 分流后的印章框） */
        int sealCount;
        /** 指纹流框数（同上，仅作标记，不参与印章判定） */
        int fingerprintCount;
        Double maxConfidence;
        String pageCount = "1";
        String description = "";
        /** 印章框 bbox：[x1, y1, x2, y2, confidence]，原图像素坐标 */
        final List<double[]> sealBoxes = new ArrayList<>();
        /** 疑似指纹框 bbox，字段含义同上 */
        final List<double[]> fingerprintBoxes = new ArrayList<>();
        /** AIMP 实际检测所用图像的像素尺寸，前端据此把 bbox 换算成相对比例 */
        int imageWidth;
        int imageHeight;

        /**
         * 存在类规则：只按**所选规则**判存在性——选中的规则全部检出才算通过；
         * 未选中的规则不参与判定（如只选指纹检测时，无印章页面不再判不通过）。
         */
        String resultLabel(Task task) {
            if (!ok) return "无法判定";
            boolean pass = true;
            if (hasRule(task, "seal")) pass = pass && sealCount > 0;
            if (hasRule(task, "fingerprint")) pass = pass && fingerprintCount > 0;
            return pass ? "通过" : "不通过";
        }

        /** 「说明」列：逐条列出所选规则的检出情况 */
        String describe(Task task) {
            if (!ok) return description;
            List<String> parts = new ArrayList<>();
            if (hasRule(task, "seal")) {
                parts.add(sealCount > 0
                        ? "印章 " + sealCount + " 枚"
                        + (maxConfidence == null ? "" : String.format("（最高置信度 %.2f）", maxConfidence))
                        : "未检出印章（可能存在漏章）");
            }
            if (hasRule(task, "fingerprint")) {
                parts.add(fingerprintCount > 0
                        ? "疑似指纹 " + fingerprintCount + " 处"
                        : "未检出指纹（可能存在漏印）");
            }
            return String.join("；", parts);
        }
    }

    private static final class Task {
        final long projectId;
        final String rootPath;
        final String aimpUrl;
        final double confidence;
        final List<ReviewRule> rules;
        final List<UnitScanner.Volume> units;
        final ObjectNode pageMap = mapper.createObjectNode();
        final long startedAt = System.currentTimeMillis();

        String status = STATUS_RUNNING;
        String message = "";
        long finishedAt;
        int totalFiles;
        int processedFiles;
        int rowsAppended;
        int passed;
        int failed;
        int unknown;
        String currentUnit = "";
        String currentFile = "";
        volatile boolean cancelled;
        Thread worker;

        Task(long projectId, String rootPath, String aimpUrl, double confidence,
             List<ReviewRule> rules, List<UnitScanner.Volume> units, int totalFiles) {
            this.projectId = projectId;
            this.rootPath = rootPath;
            this.aimpUrl = aimpUrl;
            this.confidence = confidence;
            this.rules = rules;
            this.units = units;
            this.totalFiles = totalFiles;
        }
    }
}
