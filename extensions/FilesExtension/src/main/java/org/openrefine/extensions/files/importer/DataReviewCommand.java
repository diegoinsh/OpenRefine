package org.openrefine.extensions.files.importer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
import org.openrefine.extensions.files.review.CatalogChecklist;
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

    /**
     * 件级预览的保留键（仅件级链路写入，旧链路不出现）：
     * <ul>
     *   <li>{@code __piece_pages__}：件内页组（有序），{@code [{f,p}]}，前端据此把缩略图限定为本件；</li>
     *   <li>{@code __review_ann_pages__}：页 → 标注映射，{@code {"页号|文件名": ann}}，翻到件内任意页都能画框；</li>
     *   <li>{@code __piece_legend__}：件级结论（要求项/命中项/缺失项 + 逐页状态），渲染在预览左上角。</li>
     * </ul>
     */
    public static final String PIECE_PAGES_KEY = "__piece_pages__";
    public static final String ANNOTATION_PAGES_KEY = "__review_ann_pages__";
    public static final String PIECE_LEGEND_KEY = "__piece_legend__";

    /**
     * 内容组合类（件级）的占位规则键：本身不产生文件级检测结果，
     * 具体件清单与每件要求由平台按抽取项目号（pieceMode）或内联模板下发。
     * 与前端 {@code REVIEW_RULE_CATEGORIES} 的 content / piece_combo 保持一致。
     */
    public static final String CONTENT_COMBO_KEY = "piece_combo";

    /**
     * 校审结果 overlay 槽名（与合规 {@code qualityCheckResult} 对称）：
     * 结构化结果只存 overlay，**不再写入数据行**。
     */
    public static final String REVIEW_RESULT_KEY = ReviewResultOverlay.OVERLAY_MODEL_KEY;

    /**
     * 数据行只保留输入清单列：结果侧（校审结果/印章数量/…）一律进 overlay，
     * 平台从 overlay 组装旧口径的 {@code rows} 供老下游使用。
     */
    private static final List<String> INPUT_COLUMNS = Arrays.asList(
            "序号", "案卷号", "文件名", "页数", FOLDER_PATH_COLUMN);

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
        } else if ("delete".equals(subCommand)) {
            doDeleteTask(request, response);
        } else if ("save-rules".equals(subCommand)) {
            doSaveRules(request, response);
        } else if ("get-result".equals(subCommand)) {
            doGetResult(request, response);
        } else if ("save-result".equals(subCommand)) {
            doSaveResult(request, response);
        } else if ("list".equals(subCommand)) {
            doList(request, response);
        } else {
            respondError(response, "Unknown subCommand: " + subCommand);
        }
    }

    /**
     * 读校审结果（与合规 {@code get-quality-result} 同构）：默认按 {@code project} 参数返回
     * {@code {code, hasResult, result}}。平台侧优先走此命令，未命中再回退数据行/快照。
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGetResult(request, response);
    }

    /* ---------------- start ---------------- */

    private void doStart(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String rootPath = trim(request.getParameter("rootPath"));
            String projectName = trim(UnicodeText.decode(request.getParameter("projectName")));
            // 可选：沿用已存在的引擎项目（平台侧绑定表里的项目号）。
            // 重新校审必须写回同一个项目号，下游嵌入的界面地址（project=<引擎号>）才不会失效。
            String reuseProject = trim(request.getParameter("project"));
            String rulesJson = request.getParameter("rules");
            String aimpUrl = aimpUrl(request.getParameter("aimpUrl"));
            double confidence = parseDouble(request.getParameter("confidence"), 0.5);
            // 件级校审（内部参数，平台按已抽取项目装配）：缺省为空 → 旧链路逐字不变
            String pieceMapJson = trim(UnicodeText.decode(request.getParameter("pieceMap")));
            // 下游本次提交目录原文（平台 startReview 随任务下发）：项目页按它展示
            // 「数据来源 / 二级目录 / 四级题名 / 件页」，不再反查抽取任务（FR-P-06）
            String catalogJson = trim(UnicodeText.decode(request.getParameter("catalog")));
            // 纯数据模式（平台只选缺件/缺页等数据规则）：只建项目 + 登记提交目录，
            // 结构判定在引擎侧按提交目录完成；有 rootPath 时顺带扫描件目录，供项目页预览原件。
            boolean catalogOnly = "true".equalsIgnoreCase(request.getParameter("catalogOnly"));

            if (rootPath.isEmpty() && !catalogOnly) {
                respondError(response, "缺少 rootPath 参数");
                return;
            }

            List<ReviewRule> rules = parseRules(rulesJson);
            if (rules.isEmpty()) {
                respondError(response, "请至少选择一项校审规则");
                return;
            }
            for (ReviewRule rule : rules) {
                if (!isReviewRuleKey(rule.key) && !(catalogOnly && isCatalogRuleKey(rule.key))) {
                    respondError(response, "暂不支持的校审规则: " + rule.name);
                    return;
                }
            }
            // 内容组合类（piece_combo）本身不产生文件级检测结果：件清单由平台按抽取项目号下发。
            // 只勾它启动文件级校审会得到「全部通过」的假结果，故要求至少一项检测规则；
            // 件级链路（带 pieceMap）由平台逐件下发，不受此限。
            List<PieceSpec> pieces = null;
            if (!pieceMapJson.isEmpty()) {
                pieces = parsePieceMap(pieceMapJson);
                if (pieces.isEmpty()) {
                    respondError(response, "件级校审参数为空：未取得任何件");
                    return;
                }
                for (PieceSpec piece : pieces) {
                    if (piece.rules.isEmpty()) {
                        respondError(response, "件级校审未配置能力: " + piece.title);
                        return;
                    }
                    for (PieceRule rule : piece.rules) {
                        if (!"seal".equals(rule.key) && !"fingerprint".equals(rule.key)) {
                            respondError(response, "件级校审暂不支持的规则: " + rule.name);
                            return;
                        }
                    }
                }
            }
            if (pieces == null && !hasDetectionRule(rules) && catalogJson.isEmpty()) {
                respondError(response, "内容组合类需按件清单（由平台按抽取项目号下发），"
                        + "不能单独启动文件级校审：请同时选择存在类规则，或由平台 startReview 按件启动");
                return;
            }
            // 下游提交目录：只做解析校验与登记，不参与文件级检测（判定在平台侧完成）
            JsonNode catalogNode = null;
            if (!catalogJson.isEmpty()) {
                try {
                    catalogNode = mapper.readTree(catalogJson);
                } catch (Exception e) {
                    respondError(response, "本次提交目录不是合法 JSON：" + e.getMessage());
                    return;
                }
            }
            if (catalogOnly) {
                if (catalogNode == null) {
                    respondError(response, "纯数据校审需要同时提供 catalog");
                    return;
                }
                // 判定与落盘都在引擎侧完成（与合规同构）：平台只下发目录与规则，
                // 结果写项目 overlay，平台用 get-result 读回。
                CatalogChecklist.Catalog parsed = CatalogChecklist.parse(catalogNode);
                Map<String, Object> checklist = CatalogChecklist.evaluate(parsed);
                Project project = prepareProject(projectName, reuseProject);
                List<Map<String, Object>> catalogItems = summarizeParsedCatalog(parsed);
                int catalogPages = 0;
                for (Map<String, Object> item : catalogItems) {
                    catalogPages += intOf(item.get("pages"));
                }
                // 数据行的「文件夹路径」要能直接预览原件：按下发件题名匹配真实件目录
                // （正卷/二级目录/序号 件名/页图），匹配不到才退回卷内相对路径。
                List<UnitScanner.Volume> units = new ArrayList<>();
                if (!rootPath.isEmpty()) {
                    try {
                        units = scanUnits(rootPath);
                    } catch (Exception e) {
                        logger.warn("纯数据校审扫描目录失败（预览路径退回相对路径）: {}", e.getMessage());
                    }
                }
                writeCatalogRows(project, parsed, units);

                Map<String, Object> summary = asMap(checklist.get("summary"));
                int errorCount = intOf(summary.get("errorCount"));
                int warningCount = intOf(summary.get("warningCount"));
                int missing = intOf(summary.get("missing"));
                int unknown = intOf(summary.get("unknown"));
                long now = System.currentTimeMillis();
                int version = writeCatalogReviewResult(project, rules, checklist, catalogItems,
                        catalogPages, now);

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("projectId", project.id);
                record.put("projectName", project.getMetadata() == null
                        ? projectName : project.getMetadata().getName());
                record.put("rootPath", rootPath);
                record.put("mode", "review-data");
                record.put("rules", rulesJson == null ? "" : rulesJson);
                record.put("totalFiles", catalogPages);
                record.put("processedFiles", catalogPages);
                record.put("passed", missing == 0 ? 1 : 0);
                record.put("failed", errorCount);
                record.put("unknown", unknown);
                record.put("status", STATUS_COMPLETED);
                record.put("catalogOnly", true);
                record.put("catalogSource", "downstream");
                record.put("catalog", mapper.convertValue(catalogNode, Map.class));
                record.put("pieceList", catalogItems);
                record.put("templateId", trim(request.getParameter("templateId")));
                record.put("templateVersion", trim(request.getParameter("templateVersion")));
                record.put("resultVersion", version);
                record.put("hasResult", true);
                record.put("issueCount", errorCount + warningCount);
                record.put("startedAt", now);
                record.put("finishedAt", now);
                DataReviewTaskRegistry.upsert(project.id, record);
                ProjectManager.singleton.ensureProjectSaved(project.id);

                ObjectNode result = mapper.createObjectNode();
                result.put("code", "ok");
                result.put("projectId", project.id);
                result.put("projectName", projectName);
                result.put("totalFiles", catalogPages);
                result.put("resultVersion", version);
                result.put("issueCount", errorCount + warningCount);
                respondJSON(response, result);
                return;
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
            if (pieces != null) {
                // 件级路径的总页数以平台下发的逐页清单为准；目录拆分形态下平台拿不到逐页
                // 文件名、pages 为空，此时退回扫描到的总页数，避免进度分母恒为 0。
                int piecePages = 0;
                for (PieceSpec piece : pieces) piecePages += piece.pages.size();
                if (piecePages > 0) {
                    totalFiles = piecePages;
                }
            }

            Project project = prepareProject(projectName, reuseProject);
            Task task = new Task(project.id, rootPath, aimpUrl, confidence, rules, units, totalFiles);
            task.pieces = pieces;
            task.catalog = catalogNode;
            TASKS.put(project.id, task);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("projectId", project.id);
            record.put("projectName", project.getMetadata() == null ? projectName : project.getMetadata().getName());
            record.put("rootPath", rootPath);
            record.put("mode", pieces == null ? "review-file" : "review-piece");
            record.put("rules", rulesJson == null ? "" : rulesJson);
            record.put("totalFiles", totalFiles);
            record.put("processedFiles", 0);
            record.put("passed", 0);
            record.put("failed", 0);
            record.put("unknown", 0);
            record.put("status", STATUS_RUNNING);
            record.put("startedAt", task.startedAt);
            // 模板标识：件级/文件级链路也要登记，项目页据此还原模板栏与各页签参数
            record.put("templateId", trim(request.getParameter("templateId")));
            record.put("templateVersion", trim(request.getParameter("templateVersion")));
            // 件级链路把件清单摘要一并登记：参数页（view=review-params）据此只读展示
            // 「本件有哪些件、每件要求什么」，不必重跑校审；旧链路不写，记录保持原样。
            if (pieces != null) {
                record.put("pieceMode", true);
                record.put("pieceList", summarizePieces(pieces));
            }
            if (catalogNode != null) {
                // 判定输入：下游本次提交目录（只读）。有件级链路时两者并存，
                // 项目页优先读 catalog；无件级链路时用提交目录充当件清单，供页签展示。
                record.put("catalogSource", "downstream");
                record.put("catalog", mapper.convertValue(catalogNode, Map.class));
                if (pieces == null) {
                    record.put("pieceList", summarizeCatalog(catalogNode));
                }
            }
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
     * 解析平台下发的件级校审参数（内部结构，不由下游直传）：
     * <pre>{ "pieces": [ { "volumeNo","pieceNo","title","pages":["0003.jpg"], 
     *   "rules":[ {"key":"seal","name":"印章检测","required":true,"coverage":"piece"} ] } ] }</pre>
     */
    private static List<PieceSpec> parsePieceMap(String json) {
        List<PieceSpec> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode pieces = root.isArray() ? root : root.path("pieces");
            if (pieces == null || !pieces.isArray()) {
                return out;
            }
            for (JsonNode p : pieces) {
                PieceSpec piece = new PieceSpec();
                piece.volumeNo = p.path("volumeNo").asText("");
                piece.pieceNo = p.path("pieceNo").asText("");
                piece.title = p.path("title").asText("");
                JsonNode pages = p.path("pages");
                if (pages.isArray()) {
                    for (JsonNode n : pages) {
                        String s = n.isTextual() ? n.asText("") : n.path("fileName").asText("");
                        if (!s.trim().isEmpty()) {
                            piece.pages.add(s.trim());
                        }
                    }
                }
                JsonNode rules = p.path("rules");
                if (rules.isArray()) {
                    for (JsonNode n : rules) {
                        String key = n.path("key").asText("").trim();
                        if (key.isEmpty()) continue;
                        PieceRule rule = new PieceRule();
                        rule.key = key;
                        rule.name = n.path("name").asText(key);
                        rule.category = n.path("category").asText("存在类");
                        rule.required = n.path("required").asBoolean(true);
                        rule.coverage = "everyPage".equalsIgnoreCase(
                                n.path("coverage").asText("")) ? "everyPage" : "piece";
                        piece.rules.add(rule);
                    }
                }
                out.add(piece);
            }
        } catch (Exception e) {
            logger.warn("件级校审参数不是合法 JSON，已忽略: " + e.getMessage());
        }
        return out;
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

    private Project createEmptyProject(String projectName) throws Exception {
        Project project = new Project();
        addColumns(project.columnModel, INPUT_COLUMNS);
        project.update();

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(!projectName.isEmpty() ? projectName : "数据校审");
        metadata.setEncoding("UTF-8");
        ProjectManager.singleton.registerProject(project, metadata);
        return project;
    }

    /**
     * 取本次校审要写入的项目：带 {@code project} 参数且项目存在时**沿用它**（清空旧行与
     * 旧 pageMap 后重跑），否则新建。
     *
     * <p>复用时项目号保持不变——平台侧据此把「外部项目号 → 引擎项目号」的绑定保持稳定。</p>
     */
    private Project prepareProject(String projectName, String reuseProject) throws Exception {
        if (!reuseProject.isEmpty()) {
            try {
                long pid = Long.parseLong(reuseProject);
                Project existing = ProjectManager.singleton.getProject(pid);
                if (existing != null) {
                    Task running = TASKS.get(pid);
                    if (running != null && STATUS_RUNNING.equals(running.status) && !running.cancelled) {
                        running.cancelled = true;
                    }
                    resetForRerun(existing, projectName);
                    return existing;
                }
                logger.warn("校审复用项目不存在，改为新建: {}", pid);
            } catch (NumberFormatException e) {
                logger.warn("校审 project 参数非法，改为新建: {}", reuseProject);
            }
        }
        return createEmptyProject(projectName);
    }

    /**
     * 复用项目前清掉上一轮的行数据与 pageMap，保留项目号与其它元信息。
     *
     * <p>overlay 结果**不清**：新一轮结果落盘时覆盖写并据此把 {@code resultVersion} 递增；
     * 重跑期间读到的仍是上一轮结果（只留最新一轮，不留历史）。</p>
     */
    private static void resetForRerun(Project project, String projectName) {
        synchronized (project) {
            project.rows.clear();
            project.getMetadata().setCustomMetadata(BatchExtractionManager.PAGE_MAP_KEY, null);
            if (!projectName.isEmpty()) {
                project.getMetadata().setName(projectName);
            }
            project.update();
            try {
                ProjectManager.singleton.ensureProjectSaved(project.id);
            } catch (Exception e) {
                logger.warn("清空校审项目旧结果后落盘失败: {}", project.id, e);
            }
        }
    }

    private static void addColumns(ColumnModel columnModel, List<String> names) throws Exception {
        for (int i = 0; i < names.size(); i++) {
            columnModel.addColumn(i, new Column(i, names.get(i)), false);
        }
    }

    /* ---------------- 后台执行 ---------------- */

    private void run(Task task) {
        String status;
        String message;
        try {
            Project project = ProjectManager.singleton.getProject(task.projectId);
            if (project == null) {
                finish(task, STATUS_FAILED, "项目不存在或已被删除");
                return;
            }
            if (task.pieces != null) {
                runPieces(task, project);
                // 结构类判定（缺件/缺页/空件/卷内目录/件序/文书要素/内容组合）统一在引擎侧算，
                // 与件级签章结论同一次结果落 overlay，平台只读回、不另算一套。
                if (task.catalog != null) {
                    try {
                        task.checklist = CatalogChecklist.evaluate(CatalogChecklist.parse(task.catalog));
                    } catch (Exception e) {
                        logger.warn("件级结构判定跳过: {}", e.getMessage());
                    }
                }
            } else {
                runFiles(task, project);
            }
            flushPageMap(task);
            saveProject(task);
            status = task.cancelled ? STATUS_CANCELLED : STATUS_COMPLETED;
            message = task.cancelled ? "已取消" : "校审完成";
        } catch (Exception e) {
            logger.error("data-review run failed", e);
            status = STATUS_FAILED;
            message = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        // 终态落盘：先定终态（写 finishedAt），再把结构化结果写进 overlay（已跑出的部分也保留）
        finish(task, status, message);
        saveReviewResult(task, status);
        saveProject(task);
    }

    /** 旧链路：按文件逐页校审（检测口径与件级上线前一致，结果只进 overlay）。 */
    private void runFiles(Task task, Project project) {
        int rowIndex = 0;
        int rowsSinceSave = 0;
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
                Row row = buildInputRow(project.columnModel, rowIndex + 1, unit.name,
                        fileName, detection.pageCount, unit.path);
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
                    task.fileResults.add(fileResult(rowIndex, unit.name, fileName,
                            detection.pageCount, ruleNameList(task),
                            detection.resultLabel(task),
                            detection.ok ? String.valueOf(detection.sealCount) : "",
                            detection.ok ? String.valueOf(detection.fingerprintCount) : "",
                            detection.describe(task), unit.path, null));
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
    }

    /**
     * 件级链路（内容组合类）：一件一行，按件内页清单做存在类检测。
     *
     * <p>{@code piece} 覆盖粒度判定「件内至少一处」，{@code everyPage} 判定「每页都有」；
     * 页清单由平台按已抽取项目下发，引擎只按文件名与 {@code rootPath} 扫描结果匹配。
     * 件的结构化结论（要求/命中/缺失 + 逐页状态）写进 overlay {@code pieces[]}。</p>
     */
    private void runPieces(Task task, Project project) {
        Map<String, PageRef> index = buildPageIndex(task);
        Map<String, Detection> cache = new HashMap<>();
        int rowIndex = 0;
        int rowsSinceSave = 0;
        for (PieceSpec piece : task.pieces) {
            if (task.cancelled) break;
            task.currentUnit = piece.volumeNo;
            task.currentFile = piece.title;

            List<PageRef> refs = new ArrayList<>();
            for (String name : piece.pages) {
                PageRef ref = index.get(piece.volumeNo + "|" + name);
                if (ref == null) {
                    ref = index.get("|" + name);
                }
                if (ref == null) {
                    ref = index.get("|" + name.toLowerCase(Locale.ROOT));
                }
                if (ref != null && !refs.contains(ref)) {
                    refs.add(ref);
                }
            }
            // 目录拆分形态：下游按「正卷/二级目录/序号 件名/页图」提交时，catalog 通常不带
            // 逐页文件名，平台也就无法下发 pages[]。按件目录名（去前导序号）与件题名匹配兜底，
            // 取该目录全部页，避免整卷被判成「件内页未匹配到」。
            if (refs.isEmpty()) {
                refs = refsByDirectory(task, piece);
            }
            String keys = pieceRuleKeys(piece);

            PieceOutcome outcome = evaluatePiece(task, piece, refs, cache, keys);
            PageRef first = refs.isEmpty() ? null : refs.get(0);
            Row row = buildInputRow(project.columnModel, rowIndex + 1, piece.volumeNo,
                    first == null ? "" : first.fileName, String.valueOf(refs.size()),
                    first == null ? "" : first.unit.path);
            ObjectNode pageMapRow = refs.isEmpty() ? null
                    : buildPiecePageMapRow(refs, piece, outcome, keys, cache);

            synchronized (task) {
                project.rows.add(row);
                if (pageMapRow != null) {
                    task.pageMap.set(String.valueOf(rowIndex), pageMapRow);
                }
                if (!outcome.unknown && "通过".equals(outcome.resultLabel)) {
                    task.passed++;
                } else if (outcome.unknown) {
                    task.unknown++;
                } else {
                    task.failed++;
                }
                ObjectNode extra = mapper.createObjectNode();
                extra.put("pieceNo", piece.pieceNo);
                extra.put("pieceTitle", piece.title);
                extra.put("required", String.join("、", outcome.requiredNames));
                extra.put("present", String.join("、", outcome.presentNames));
                extra.put("missing", String.join("、", outcome.missingNames));
                task.fileResults.add(fileResult(rowIndex, piece.volumeNo,
                        first == null ? "" : first.fileName, String.valueOf(refs.size()),
                        String.join("、", outcome.ruleNames(piece)), outcome.resultLabel,
                        String.valueOf(outcome.sealSum), String.valueOf(outcome.fpSum),
                        outcome.detail, first == null ? "" : first.unit.path, extra));
                task.pieceResults.add(buildPieceResult(piece, refs, outcome, cache));
                task.rowsAppended++;
                task.processedFiles += Math.max(1, refs.size());
            }
            rowIndex++;
            flushPageMap(task);
            if (++rowsSinceSave >= SAVE_EVERY_ROWS) {
                rowsSinceSave = 0;
                saveProject(task);
            }
        }
    }

    /** 文件级结果节点（{@code files[]} 的一项，字段与旧数据行列逐项对应）。 */
    private static ObjectNode fileResult(int rowIndex, String archiveNo, String fileName,
                                         String pages, String rules, String result,
                                         String sealCount, String fingerprintCount,
                                         String note, String folderPath, ObjectNode extra) {
        ObjectNode f = mapper.createObjectNode();
        f.put("rowIndex", rowIndex);
        f.put("archiveNo", archiveNo);
        f.put("fileName", fileName);
        f.put("pages", pages);
        f.put("rules", rules);
        f.put("result", result);
        f.put("sealCount", sealCount);
        f.put("fingerprintCount", fingerprintCount);
        f.put("note", note);
        f.put("folderPath", folderPath);
        if (extra != null) {
            extra.fields().forEachRemaining(e -> f.set(e.getKey(), e.getValue()));
        }
        return f;
    }

    /** 件级结果节点（{@code pieces[]} 的一项：要求/命中/缺失 + 逐页状态）。 */
    private static ObjectNode buildPieceResult(PieceSpec piece, List<PageRef> refs,
                                               PieceOutcome outcome,
                                               Map<String, Detection> cache) {
        ObjectNode p = mapper.createObjectNode();
        p.put("pieceNo", piece.pieceNo);
        p.put("title", piece.title);
        p.put("volumeNo", piece.volumeNo);
        p.put("status", outcome.unknown ? "unknown"
                : ("通过".equals(outcome.resultLabel) ? "passed" : "failed"));
        p.put("detail", outcome.detail);
        ArrayNode pages = p.putArray("pages");
        for (PageRef ref : refs) {
            ObjectNode page = pages.addObject();
            page.put("f", ref.fileName);
            page.put("p", ref.pageNo);
        }
        ArrayNode requirements = p.putArray("requirements");
        for (PieceRule rule : piece.rules) {
            ObjectNode req = requirements.addObject();
            req.put("key", rule.key);
            req.put("name", rule.name);
            req.put("category", rule.category);
            req.put("required", rule.required);
            req.put("coverage", rule.coverage);
        }
        addNames(p.putArray("present"), outcome.presentNames);
        addNames(p.putArray("missing"), outcome.missingNames);
        ArrayNode pageStatus = p.putArray("pageStatus");
        for (PageRef ref : refs) {
            Detection d = cache.get(ref.path);
            boolean ok = d != null && d.ok;
            ObjectNode st = pageStatus.addObject();
            st.put("f", ref.fileName);
            st.put("p", ref.pageNo);
            st.put("status", ok ? "checked" : "unknown");
            ArrayNode present = st.putArray("present");
            ArrayNode missing = st.putArray("missing");
            for (PieceRule rule : piece.rules) {
                boolean hit = ok && countOf(d, rule.key) > 0;
                if (hit) {
                    if (!containsText(present, rule.name)) present.add(rule.name);
                } else if (rule.required && "everyPage".equals(rule.coverage) && ok
                        && !containsText(missing, rule.name)) {
                    missing.add(rule.name);
                }
            }
        }
        return p;
    }

    /**
     * 按件目录兜底匹配页：目录拆分形态（{@code 正卷/二级目录/序号 件名/页图}）下，
     * 下游提交目录一般不逐页给文件名，按件目录名去掉前导序号后与件题名比对。
     * 命中多个时取页数最多的目录（同名件极少数见于分卷）。
     */
    private static List<PageRef> refsByDirectory(Task task, PieceSpec piece) {
        return refsByDirectory(task.units, piece.title);
    }

    /** 同上的纯目录版本（纯数据校审没有 Task，只按件题名在扫描结果里找件目录）。 */
    private static List<PageRef> refsByDirectory(List<UnitScanner.Volume> units, String title) {
        String want = stripPieceNo(title);
        List<PageRef> best = new ArrayList<>();
        if (want.isEmpty() || units == null) {
            return best;
        }
        for (UnitScanner.Volume unit : units) {
            String name = unit.name == null ? "" : unit.name;
            String segment = name.substring(name.lastIndexOf('/') + 1);
            String got = stripPieceNo(segment);
            if (got.isEmpty() || !(got.equals(want) || got.contains(want) || want.contains(got))) {
                continue;
            }
            List<PageRef> refs = new ArrayList<>();
            for (int i = 0; i < unit.pages.size(); i++) {
                String path = unit.pages.get(i);
                refs.add(new PageRef(unit, path, new File(path).getName(), pageNoOf(unit, i)));
            }
            if (refs.size() > best.size()) {
                best = refs;
            }
        }
        return best;
    }

    /** 去掉件目录名的前导序号（如 {@code 01 询问、调查取证材料} → {@code 询问、调查取证材料}）。 */
    private static String stripPieceNo(String s) {
        return s == null ? "" : s.replaceFirst("^\\s*\\d+[\\s.、_\\-]*", "").trim();
    }

    /** 文件名 → 扫描页索引（卷名 + 文件名精确匹配优先，退回全局文件名）。 */
    private static Map<String, PageRef> buildPageIndex(Task task) {
        Map<String, PageRef> index = new HashMap<>();
        for (UnitScanner.Volume unit : task.units) {
            for (int i = 0; i < unit.pages.size(); i++) {
                String path = unit.pages.get(i);
                String name = new File(path).getName();
                PageRef ref = new PageRef(unit, path, name, pageNoOf(unit, i));
                index.putIfAbsent(unit.name + "|" + name, ref);
                index.putIfAbsent("|" + name, ref);
                index.putIfAbsent("|" + name.toLowerCase(Locale.ROOT), ref);
            }
        }
        return index;
    }

    /** 单件评测：件内任意一页 vs 逐页（everyPage）。 */
    private PieceOutcome evaluatePiece(Task task, PieceSpec piece, List<PageRef> refs,
                                       Map<String, Detection> cache, String keys) {
        PieceOutcome out = new PieceOutcome();
        for (PieceRule rule : piece.rules) {
            boolean hit = false;
            boolean unknown = false;
            if ("everyPage".equals(rule.coverage)) {
                if (refs.isEmpty()) {
                    unknown = true;
                } else {
                    hit = true;
                    for (PageRef ref : refs) {
                        Detection d = detectCached(task, ref.path, keys, cache);
                        if (!d.ok) {
                            unknown = true;
                        } else if (countOf(d, rule.key) <= 0) {
                            hit = false;
                        }
                    }
                }
            } else {
                for (PageRef ref : refs) {
                    Detection d = detectCached(task, ref.path, keys, cache);
                    if (!d.ok) {
                        unknown = true;
                    } else if (countOf(d, rule.key) > 0) {
                        hit = true;
                    }
                }
                if (refs.isEmpty()) {
                    unknown = true;
                }
            }
            if (hit) {
                out.hitNames.add(rule.name);
            }
            // 「有什么」：件内任一处检出即算存在，与结论口径（everyPage 需逐页满足）分开
            boolean present = false;
            for (PageRef ref : refs) {
                Detection d = detectCached(task, ref.path, keys, cache);
                if (d.ok && countOf(d, rule.key) > 0) {
                    present = true;
                    break;
                }
            }
            if (present) {
                out.presentNames.add(rule.name);
            }
            if (rule.required && !hit) {
                out.missingNames.add(rule.name);
                if (unknown) {
                    out.unknown = true;
                }
            }
            if (rule.required) {
                out.requiredNames.add(rule.name);
            }
            out.details.add(describePieceRule(rule, refs, cache, hit, unknown));
        }
        for (PageRef ref : refs) {
            Detection d = detectCached(task, ref.path, keys, cache);
            if (d.ok) {
                out.sealSum += d.sealCount;
                out.fpSum += d.fingerprintCount;
            }
        }
        if (out.missingNames.isEmpty()) {
            out.resultLabel = "通过";
        } else if (out.unknown) {
            out.resultLabel = "无法判定";
        } else {
            out.resultLabel = "不通过";
        }
        if (refs.isEmpty()) {
            out.detail = "件内页在源目录中未匹配到，无法判定";
        } else {
            out.detail = String.join("；", out.details);
        }
        return out;
    }

    private Detection detectCached(Task task, String path, String keys,
                                   Map<String, Detection> cache) {
        Detection d = cache.get(path);
        if (d == null) {
            d = detect(task, path, keys);
            cache.put(path, d);
        }
        return d;
    }

    private static int countOf(Detection d, String key) {
        return "fingerprint".equals(key) ? d.fingerprintCount : d.sealCount;
    }

    private static String describePieceRule(PieceRule rule, List<PageRef> refs,
                                            Map<String, Detection> cache, boolean hit,
                                            boolean unknown) {
        int pages = refs.size();
        if ("everyPage".equals(rule.coverage)) {
            int hitPages = 0;
            for (PageRef ref : refs) {
                Detection d = cache.get(ref.path);
                if (d != null && d.ok && countOf(d, rule.key) > 0) {
                    hitPages++;
                }
            }
            String s = rule.name + "：逐页检出 " + hitPages + "/" + pages + " 页";
            return unknown && !hit ? s + "（部分页无法判定）" : s;
        }
        String s = rule.name + "：件内检出";
        if (!hit) {
            s = rule.name + "：件内未检出";
        }
        return unknown && !hit ? s + "（部分页无法判定）" : s;
    }

    /**
     * 数据行只写输入清单（序号/案卷号/文件名/页数/文件夹路径）：结果侧一律进 overlay，
     * 不再双写；预览所需的标注/件页组仍走 pageMap 保留键。
     */
    private static Row buildInputRow(ColumnModel columnModel, int seq, String archiveNo,
                                     String fileName, String pages, String folderPath) {
        Row row = new Row(columnModel.getMaxCellIndex() + 1);
        put(columnModel, row, "序号", seq);
        put(columnModel, row, "案卷号", archiveNo);
        put(columnModel, row, "文件名", fileName);
        put(columnModel, row, "页数", pages);
        put(columnModel, row, FOLDER_PATH_COLUMN, folderPath);
        return row;
    }

    /**
     * 件级预览行：额外写入件页组、逐页标注与件级图例，供前端把「一张」扩成「一组」。
     * 旧键（{@code __piece_start__} / {@code __review_ann__}）照旧写入，前端未升级时行为不变。
     */
    private ObjectNode buildPiecePageMapRow(List<PageRef> refs, PieceSpec piece, PieceOutcome outcome,
                                            String keys, Map<String, Detection> cache) {
        if (refs == null || refs.isEmpty()) return null;
        PageRef ref = refs.get(0);
        ObjectNode rowNode = mapper.createObjectNode();
        if (outcome != null) {
            ObjectNode cand = mapper.createObjectNode();
            cand.put("v", outcome.resultLabel);
            cand.put("p", ref.pageNo);
            cand.put("f", ref.fileName);
            cand.put("w", 1);
            rowNode.putArray("校审结果").add(cand);
            ObjectNode sealCand = rowNode.putArray("印章数量").addObject();
            sealCand.put("v", String.valueOf(outcome.sealSum));
            sealCand.put("p", ref.pageNo);
            sealCand.put("f", ref.fileName);
            ObjectNode fpCand = rowNode.putArray("指纹数量").addObject();
            fpCand.put("v", String.valueOf(outcome.fpSum));
            fpCand.put("p", ref.pageNo);
            fpCand.put("f", ref.fileName);
            ObjectNode pieceCand = rowNode.putArray("件号").addObject();
            pieceCand.put("v", piece.pieceNo);
            pieceCand.put("p", ref.pageNo);
            pieceCand.put("f", ref.fileName);
        }
        ObjectNode start = mapper.createObjectNode();
        start.put("v", "校审原件");
        start.put("p", ref.pageNo);
        start.put("f", ref.fileName);
        rowNode.set(BatchExtractionManager.PIECE_START_KEY, start);

        ArrayNode pages = mapper.createArrayNode();
        ObjectNode annPages = mapper.createObjectNode();
        ArrayNode legendPages = mapper.createArrayNode();
        for (PageRef r : refs) {
            ObjectNode page = pages.addObject();
            page.put("f", r.fileName);
            page.put("p", r.pageNo);

            Detection d = cache.get(r.path);
            boolean ok = d != null && d.ok;
            if (ok) {
                // 件级页只画检出框；缺项由件级图例按 coverage 判定，避免 piece 覆盖的件误标某页缺章
                ObjectNode ann = buildAnnotation(r.fileName, r.pageNo, keys, d, false);
                if (ann != null) {
                    annPages.set(r.pageNo + "|" + r.fileName, ann);
                    if (r == ref) rowNode.set(ANNOTATION_KEY, ann);
                }
            }
            ObjectNode lp = legendPages.addObject();
            lp.put("f", r.fileName);
            lp.put("p", r.pageNo);
            lp.put("ok", ok);
            ArrayNode hits = lp.putArray("hits");
            ArrayNode miss = lp.putArray("missing");
            for (PieceRule rule : piece.rules) {
                boolean hit = ok && countOf(d, rule.key) > 0;
                if (hit) {
                    if (!containsText(hits, rule.name)) hits.add(rule.name);
                } else if (rule.required && "everyPage".equals(rule.coverage) && ok
                        && !containsText(miss, rule.name)) {
                    miss.add(rule.name);
                }
            }
        }
        rowNode.set(PIECE_PAGES_KEY, pages);
        rowNode.set(ANNOTATION_PAGES_KEY, annPages);

        ObjectNode legend = mapper.createObjectNode();
        legend.put("pieceNo", piece.pieceNo);
        legend.put("title", piece.title);
        legend.put("result", outcome == null ? "无法判定" : outcome.resultLabel);
        addNames(legend.putArray("required"), outcome == null ? null : outcome.requiredNames);
        addNames(legend.putArray("hit"), outcome == null ? null : outcome.presentNames);
        addNames(legend.putArray("missing"), outcome == null ? null : outcome.missingNames);
        legend.set("pages", legendPages);
        rowNode.set(PIECE_LEGEND_KEY, legend);
        return rowNode;
    }

    private static void addNames(ArrayNode array, List<String> names) {
        if (names == null) return;
        for (String n : names) {
            if (n != null && !n.isEmpty() && !containsText(array, n)) array.add(n);
        }
    }

    private static boolean containsText(ArrayNode array, String text) {
        for (int i = 0; i < array.size(); i++) {
            if (text.equals(array.get(i).asText())) return true;
        }
        return false;
    }

    private static String pieceRuleKeys(PieceSpec piece) {
        List<String> keys = new ArrayList<>();
        for (PieceRule rule : piece.rules) {
            if (!keys.contains(rule.key)) keys.add(rule.key);
        }
        return String.join(",", keys);
    }

    /** 卷内页号：投放命名规范里解析出的页号优先，解析不出时用列表下标（从 1 起） */
    private static int pageNoOf(UnitScanner.Volume unit, int i) {
        if (unit.pageNos != null && i < unit.pageNos.size()) {
            Integer no = unit.pageNos.get(i);
            if (no != null) return no;
        }
        return i + 1;
    }

    /** 本次校审所选规则名（用于 overlay {@code files[].rules}）；内容组合类不参与文件级检测 */
    private static String ruleNameList(Task task) {
        List<String> names = new ArrayList<>();
        for (ReviewRule rule : task.rules) {
            if (!isDetectionRuleKey(rule.key)) continue;
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

    /**
     * 纯数据校审写数据行：一件一行，只承载本次提交目录（结果一律进 overlay）。
     *
     * <p>{@code units} 为本卷根目录的扫描结果：件题名能匹配到真实件目录时，
     * 「文件夹路径」写该目录的绝对路径，项目页点行即可预览原件；匹配不到时退回
     * 「卷N/二级目录」的展示路径。</p>
     */
    private static void writeCatalogRows(Project project, CatalogChecklist.Catalog catalog,
                                         List<UnitScanner.Volume> units) {
        String archiveNo = catalog.archiveNo != null && !catalog.archiveNo.isBlank()
                ? catalog.archiveNo
                : (catalog.caseNo == null ? "" : catalog.caseNo);
        synchronized (project) {
            project.rows.clear();
            int seq = 0;
            for (CatalogChecklist.Volume volume : catalog.volumes) {
                for (CatalogChecklist.Item item : volume.items) {
                    seq++;
                    String level2 = item.level2 == null ? "" : item.level2;
                    String display = "卷" + volume.volumeNo + (level2.isEmpty() ? "" : "/" + level2);
                    List<PageRef> refs = refsByDirectory(units, item.title);
                    String folder = refs.isEmpty() ? display : refs.get(0).unit.path;
                    String range = item.startPage > 0 && item.endPage >= item.startPage
                            ? "（第" + item.startPage + "-" + item.endPage + "页）" : "";
                    project.rows.add(buildInputRow(project.columnModel, seq, archiveNo,
                            (item.title == null ? "" : item.title) + range,
                            String.valueOf(item.pageCount), folder));
                }
            }
            project.update();
        }
    }

    /** 解析后的目录 → 件清单摘要（任务记录 pieceList 与 overlay.pieces 共用）。 */
    private static List<Map<String, Object>> summarizeParsedCatalog(CatalogChecklist.Catalog catalog) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CatalogChecklist.Volume volume : catalog.volumes) {
            for (CatalogChecklist.Item item : volume.items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("volumeNo", volume.volumeNo);
                m.put("pieceNo", item.pieceNo);
                m.put("title", item.title);
                m.put("level1", item.level1);
                m.put("level2", item.level2);
                m.put("level3Title", item.level3Title);
                m.put("startPage", item.startPage);
                m.put("endPage", item.endPage);
                m.put("pages", item.pageCount);
                out.add(m);
            }
        }
        return out;
    }

    /** 纯数据校审结果落盘：overlay 里写完整 checklist（平台 get-result 读的同一份）。 */
    private int writeCatalogReviewResult(Project project, List<ReviewRule> rules,
                                         Map<String, Object> checklist,
                                         List<Map<String, Object>> catalogItems,
                                         int catalogPages, long now) {
        int version = 1;
        Object prev = project.overlayModels.get(REVIEW_RESULT_KEY);
        if (prev instanceof ReviewResultOverlay
                && ((ReviewResultOverlay) prev).getResult() != null) {
            version = ((ReviewResultOverlay) prev).getResult().path("resultVersion").asInt(0) + 1;
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("mode", "review-data");
        r.put("pieceMode", true);
        r.put("projectId", project.id);
        r.put("status", STATUS_COMPLETED);
        r.put("resultVersion", version);
        ArrayNode rulesArr = r.putArray("rules");
        for (ReviewRule rule : rules) {
            ObjectNode n = rulesArr.addObject();
            n.put("key", rule.key);
            n.put("name", rule.name);
            n.put("category", rule.category);
        }
        ObjectNode summary = mapper.valueToTree(asMap(checklist.get("summary")));
        summary.put("totalFiles", catalogPages);
        summary.put("processedFiles", catalogPages);
        summary.put("totalPieces", catalogItems.size());
        summary.put("processedPieces", catalogItems.size());
        r.set("checklist", mapper.valueToTree(checklist));
        // 件级结论：checklist.issues 里带件号的按件聚合，项目页结果表直接展示
        Map<String, List<String>> notesByPiece = new LinkedHashMap<>();
        for (Map<String, Object> issue : listOfMaps(checklist.get("issues"))) {
            String key = asText(issue.get("volumeNo")) + "#" + asText(issue.get("pieceNo"));
            notesByPiece.computeIfAbsent(key, k -> new ArrayList<>()).add(asText(issue.get("message")));
        }
        int passedPieces = 0;
        int failedPieces = 0;
        ArrayNode pieces = r.putArray("pieces");
        for (Map<String, Object> item : catalogItems) {
            String volumeNo = asText(item.get("volumeNo"));
            String pieceNo = asText(item.get("pieceNo"));
            List<String> notes = notesByPiece.get(volumeNo + "#" + pieceNo);
            if (notes == null) {
                notes = new ArrayList<>();
            }
            ObjectNode p = pieces.addObject();
            p.put("volumeNo", volumeNo);
            p.put("pieceNo", pieceNo);
            p.put("title", asText(item.get("title")));
            p.put("level2", asText(item.get("level2")));
            p.put("pageCount", intOf(item.get("pages")));
            p.put("status", notes.isEmpty() ? "passed" : "failed");
            p.putArray("matched");
            p.putArray("missing");
            ArrayNode noteArr = p.putArray("notes");
            for (String note : notes) {
                noteArr.add(note);
            }
            if (notes.isEmpty()) {
                passedPieces++;
            } else {
                failedPieces++;
            }
        }
        summary.put("passedPieces", passedPieces);
        summary.put("failedPieces", failedPieces);
        summary.put("unknownPieces", 0);
        r.set("summary", summary);
        r.putArray("files");
        r.put("startedAt", now);
        r.put("finishedAt", now);
        synchronized (project) {
            project.overlayModels.put(REVIEW_RESULT_KEY, new ReviewResultOverlay(r));
            project.getMetadata().updateModified();
        }
        return version;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 下游提交目录 → 项目页件清单（只读展示用，不含检测结论）。 */
    private static List<Map<String, Object>> summarizeCatalog(JsonNode catalog) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode items = catalog == null ? null : catalog.path("items");
        if (items == null || !items.isArray()) {
            return out;
        }
        int index = 0;
        for (JsonNode it : items) {
            index++;
            int start = it.path("startPage").asInt(0);
            int end = it.path("endPage").asInt(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pieceNo", it.path("pieceNo").asText(String.valueOf(index)));
            item.put("title", it.hasNonNull("name") ? it.get("name").asText()
                    : it.hasNonNull("title") ? it.get("title").asText()
                    : it.path("level3Title").asText(""));
            item.put("level1", it.path("level1").asText(""));
            item.put("level2", it.path("level2").asText(""));
            item.put("level3Title", it.path("level3Title").asText(""));
            item.put("startPage", start);
            item.put("endPage", end);
            item.put("pages", Math.max(0, end - start + 1));
            out.add(item);
        }
        return out;
    }

    /** 件级校审清单摘要（写入任务登记表，供参数页只读展示）。 */
    private static List<Map<String, Object>> summarizePieces(List<PieceSpec> pieces) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PieceSpec piece : pieces) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pieceNo", piece.pieceNo);
            item.put("title", piece.title);
            item.put("volumeNo", piece.volumeNo);
            item.put("pages", piece.pages.size());
            item.put("pageFiles", new ArrayList<>(piece.pages));
            List<Map<String, Object>> rules = new ArrayList<>();
            for (PieceRule rule : piece.rules) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", rule.key);
                r.put("name", rule.name);
                r.put("category", rule.category);
                r.put("required", rule.required);
                r.put("coverage", rule.coverage);
                rules.add(r);
            }
            item.put("rules", rules);
            out.add(item);
        }
        return out;
    }

    private static boolean hasRule(Task task, String key) {
        for (ReviewRule rule : task.rules) {
            if (key.equals(rule.key)) return true;
        }
        return false;
    }

    /** 纯数据（catalogOnly）可判定的规则键：不调检测模型，只按提交目录比对。 */
    private static final List<String> CATALOG_RULE_KEYS = List.of(
            "missing_piece", "missing_page", "empty_piece", "missing_attachment",
            "piece_order", "volume_toc", "document_element", CONTENT_COMBO_KEY);

    private static boolean isCatalogRuleKey(String key) {
        return CATALOG_RULE_KEYS.contains(key);
    }

    private static boolean isReviewRuleKey(String key) {
        return isDetectionRuleKey(key) || CONTENT_COMBO_KEY.equals(key);
    }

    /** 会真正调用 AIMP 的检测规则键（件级 connectivity 同口径）。 */
    private static boolean isDetectionRuleKey(String key) {
        return "seal".equals(key) || "fingerprint".equals(key);
    }

    /** 是否有至少一项会真正调用 AIMP 的检测规则（piece_combo 不算）。 */
    private static boolean hasDetectionRule(List<ReviewRule> rules) {
        for (ReviewRule rule : rules) {
            if (isDetectionRuleKey(rule.key)) {
                return true;
            }
        }
        return false;
    }

    /** 逗号分隔的能力键是否包含某项（用于件级结果标注/判定）。 */
    private static boolean hasKey(String keys, String key) {
        if (keys == null || keys.isEmpty()) return false;
        for (String s : keys.split(",")) {
            if (key.equals(s.trim())) return true;
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
        return buildAnnotation(fileName, pageNo, ruleKeyList(task), detection);
    }

    private static ObjectNode buildAnnotation(String fileName, int pageNo, String keys,
                                              Detection detection) {
        return buildAnnotation(fileName, pageNo, keys, detection, true);
    }

    /**
     * @param withMissing 是否把「所选规则未检出」写成缺失标签。件级预览传 false：
     *                    缺项已由件级图例按 coverage 表达，避免 piece 覆盖的件被逐页误标。
     */
    private static ObjectNode buildAnnotation(String fileName, int pageNo, String keys,
                                              Detection detection, boolean withMissing) {
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
        if (withMissing && hasKey(keys, "seal") && detection.sealCount == 0) {
            ObjectNode missing = items.addObject();
            missing.put("kind", "missing");
            missing.put("label", "印章");
            missing.put("color", "seal");
            missing.put("message", "未检出印章（可能存在漏章）");
        }
        if (withMissing && hasKey(keys, "fingerprint") && detection.fingerprintCount == 0) {
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

    /**
     * 把本轮结构化结果写进 overlay（{@code dataReviewResult}）：覆盖写、只留最新一轮、
     * 不进 undo/redo；数据行不再承载结果。
     */
    private void saveReviewResult(Task task, String status) {
        Project project = ProjectManager.singleton.getProject(task.projectId);
        if (project == null) return;
        try {
            synchronized (project) {
                int version = 1;
                Object prev = project.overlayModels.get(REVIEW_RESULT_KEY);
                if (prev instanceof ReviewResultOverlay
                        && ((ReviewResultOverlay) prev).getResult() != null) {
                    version = ((ReviewResultOverlay) prev).getResult().path("resultVersion").asInt(0) + 1;
                }
                task.resultVersion = version;
                project.overlayModels.put(REVIEW_RESULT_KEY,
                        new ReviewResultOverlay(buildReviewResult(task, status)));
                project.getMetadata().updateModified();
            }
            ProjectManager.singleton.ensureProjectSaved(task.projectId);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("resultVersion", task.resultVersion);
            fields.put("hasResult", true);
            fields.put("finishedAt", task.finishedAt);
            DataReviewTaskRegistry.merge(task.projectId, fields);
        } catch (Exception e) {
            logger.warn("校审结果落盘失败 {}", task.projectId, e);
        }
    }

    /** 组装 ReviewResult：汇总（页/件分口径）+ files[]（对应输入清单）+ pieces[]（件级）。 */
    private ObjectNode buildReviewResult(Task task, String status) {
        ObjectNode r = mapper.createObjectNode();
        r.put("mode", task.pieces == null ? "review-file" : "review-piece");
        r.put("pieceMode", task.pieces != null);
        r.put("projectId", task.projectId);
        r.put("rootPath", task.rootPath);
        r.put("status", status);
        r.put("resultVersion", task.resultVersion > 0 ? task.resultVersion : 1);
        ArrayNode rules = r.putArray("rules");
        for (ReviewRule rule : task.rules) {
            ObjectNode n = rules.addObject();
            n.put("key", rule.key);
            n.put("name", rule.name);
            n.put("category", rule.category);
        }
        ObjectNode summary = r.putObject("summary");
        summary.put("totalFiles", task.totalFiles);
        summary.put("processedFiles", task.processedFiles);
        summary.put("totalPieces", task.pieces == null ? task.rowsAppended : task.pieces.size());
        summary.put("processedPieces", task.rowsAppended);
        summary.put("passedPieces", task.passed);
        summary.put("failedPieces", task.failed);
        summary.put("unknownPieces", task.unknown);
        ArrayNode files = r.putArray("files");
        synchronized (task) {
            for (ObjectNode f : task.fileResults) files.add(f);
        }
        if (task.pieces != null) {
            ArrayNode pieces = r.putArray("pieces");
            synchronized (task) {
                for (ObjectNode p : task.pieceResults) pieces.add(p);
            }
            // 结构类判定随件级结果一起落盘（平台 getReviewResult 优先读它，不再自行计算）
            if (task.checklist != null) {
                r.set("checklist", mapper.valueToTree(task.checklist));
            }
        }
        r.put("startedAt", task.startedAt);
        r.put("finishedAt", task.finishedAt > 0 ? task.finishedAt : System.currentTimeMillis());
        return r;
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
        return detect(task, filePath, ruleKeyList(task));
    }

    /** 指定能力键的检测（件级校审按件下发，避免把别的件的能力带进来） */
    private Detection detect(Task task, String filePath, String elementKeys) {
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
                    .field("element_type", elementKeys)
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
            result.put("resultVersion", intOf(record.get("resultVersion")));
            result.put("hasResult", Boolean.TRUE.equals(record.get("hasResult")));
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
            result.put("resultVersion", task.resultVersion);
        }
        respondJSON(response, result);
    }

    /**
     * 保存校审参数（项目页「编辑校审参数」入口调用），不启动校审任务。
     *
     * <p>与抽取的 save-task 同口径：只更新任务登记表里的规则清单，
     * 保存后的参数对之后启动的校审任务生效；已完成的校审结果不回写。</p>
     */
    private void doSaveRules(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(trim(request.getParameter("project")));
        } catch (NumberFormatException e) {
            respondError(response, "缺少或非法的 project 参数");
            return;
        }
        String rulesJson = request.getParameter("rules");
        List<ReviewRule> rules = parseRules(rulesJson);
        if (rules.isEmpty()) {
            respondError(response, "请至少选择一项校审规则");
            return;
        }
        for (ReviewRule rule : rules) {
            if (!isReviewRuleKey(rule.key)) {
                respondError(response, "暂不支持的校审规则: " + rule.name);
                return;
            }
        }
        Task live = TASKS.get(pid);
        if (live != null && STATUS_RUNNING.equals(live.status) && !live.cancelled) {
            respondError(response, "校审任务进行中，暂时不能修改参数");
            return;
        }
        if (live != null) {
            synchronized (live) {
                live.rules.clear();
                live.rules.addAll(rules);
            }
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rules", rulesJson == null ? "" : rulesJson);
        fields.put("rulesUpdatedAt", System.currentTimeMillis());
        DataReviewTaskRegistry.merge(pid, fields);

        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        respondJSON(response, result);
    }

    /**
     * 读校审结果：{@code {code:"ok", hasResult, result, resultVersion}}。
     * 与合规 {@code get-quality-result} 同构；未产生结果时 {@code hasResult:false}。
     */
    private void doGetResult(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(trim(request.getParameter("project")));
        } catch (NumberFormatException e) {
            respondError(response, "缺少或非法的 project 参数");
            return;
        }
        Project project = ProjectManager.singleton.getProject(pid);
        if (project == null) {
            respondError(response, "未找到校审项目: " + pid);
            return;
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("code", "ok");
        Object raw = project.overlayModels.get(REVIEW_RESULT_KEY);
        if (raw instanceof ReviewResultOverlay
                && ((ReviewResultOverlay) raw).getResult() != null) {
            ReviewResultOverlay overlay = (ReviewResultOverlay) raw;
            out.put("hasResult", true);
            out.put("resultVersion", overlay.getResult().path("resultVersion").asInt(0));
            out.set("result", overlay.getResult());
        } else {
            out.put("hasResult", false);
        }
        respondJSON(response, out);
    }

    /** 写校审结果（平台侧补偿/迁移用）：覆盖 overlay，与引擎内部落盘同一槽位。 */
    private void doSaveResult(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(trim(request.getParameter("project")));
        } catch (NumberFormatException e) {
            respondError(response, "缺少或非法的 project 参数");
            return;
        }
        String resultJson = request.getParameter("result");
        if (resultJson == null || resultJson.trim().isEmpty() || "null".equals(resultJson.trim())) {
            respondError(response, "缺少 result 参数");
            return;
        }
        Project project = ProjectManager.singleton.getProject(pid);
        if (project == null) {
            respondError(response, "未找到校审项目: " + pid);
            return;
        }
        try {
            JsonNode node = mapper.readTree(resultJson);
            if (!node.isObject()) {
                respondError(response, "result 必须是 JSON 对象");
                return;
            }
            ObjectNode result = (ObjectNode) node;
            synchronized (project) {
                project.overlayModels.put(REVIEW_RESULT_KEY, new ReviewResultOverlay(result));
                project.getMetadata().updateModified();
            }
            ProjectManager.singleton.ensureProjectSaved(pid);
            ObjectNode out = mapper.createObjectNode();
            out.put("code", "ok");
            out.put("resultVersion", result.path("resultVersion").asInt(0));
            respondJSON(response, out);
        } catch (IOException e) {
            respondError(response, "result 不是合法 JSON: " + e.getMessage());
        }
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

    /** 删除校审任务：先移除内存任务与登记表，再删除对应的校审项目（含校审结果） */
    private void doDeleteTask(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(trim(request.getParameter("project")));
        } catch (NumberFormatException e) {
            respondError(response, "缺少或非法的 project 参数");
            return;
        }
        Task task = TASKS.get(pid);
        if (task != null && STATUS_RUNNING.equals(task.status) && !task.cancelled) {
            respondError(response, "校审任务进行中，请先停止再删除");
            return;
        }
        TASKS.remove(pid);
        DataReviewTaskRegistry.remove(pid);
        try {
            if (ProjectManager.singleton.getProject(pid) != null) {
                ProjectManager.singleton.deleteProject(pid);
            }
        } catch (Exception e) {
            logger.warn("删除校审项目失败: {}", pid, e);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
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

    /** 件级校审：一件 + 其页清单 + 该件要求的规则。 */
    private static final class PieceSpec {
        String volumeNo = "";
        String pieceNo = "";
        String title = "";
        final List<String> pages = new ArrayList<>();
        final List<PieceRule> rules = new ArrayList<>();
    }

    /** 件级规则：能力键 + 是否必需 + 覆盖粒度（piece / everyPage）。 */
    private static final class PieceRule {
        String key;
        String name;
        String category = "存在类";
        boolean required = true;
        String coverage = "piece";
    }

    /** 件级判定结果（供行写入与计数使用）。 */
    private static final class PieceOutcome {
        String resultLabel = "无法判定";
        String detail = "";
        boolean unknown;
        int sealSum;
        int fpSum;
        final List<String> requiredNames = new ArrayList<>();
        final List<String> hitNames = new ArrayList<>();
        /** 件内任一处检出（「有什么」），与 hitNames（结论口径）区分 */
        final List<String> presentNames = new ArrayList<>();
        final List<String> missingNames = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        List<String> ruleNames(PieceSpec piece) {
            List<String> names = new ArrayList<>();
            for (PieceRule rule : piece.rules) {
                if (!names.contains(rule.name)) names.add(rule.name);
            }
            return names;
        }
    }

    /** 扫描到的单页引用（卷、路径、文件名、卷内页号）。 */
    private static final class PageRef {
        final UnitScanner.Volume unit;
        final String path;
        final String fileName;
        final int pageNo;

        PageRef(UnitScanner.Volume unit, String path, String fileName, int pageNo) {
            this.unit = unit;
            this.path = path;
            this.fileName = fileName;
            this.pageNo = pageNo;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PageRef && path.equals(((PageRef) o).path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
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
        /** 结构化结果（终态写 overlay）：文件级清单与件级清单 */
        final List<ObjectNode> fileResults = new ArrayList<>();
        final List<ObjectNode> pieceResults = new ArrayList<>();
        final long startedAt = System.currentTimeMillis();
        /** 件级校审参数；null = 旧链路（按文件逐页校审） */
        List<PieceSpec> pieces;
        /** 下游提交目录原文：件级路径同样据它做结构类判定（单一权威在引擎侧） */
        JsonNode catalog;
        /** 结构类判定结果（CatalogChecklist.evaluate），终态随结果写 overlay */
        Map<String, Object> checklist;

        String status = STATUS_RUNNING;
        String message = "";
        long finishedAt;
        int totalFiles;
        int processedFiles;
        int rowsAppended;
        int passed;
        int failed;
        int unknown;
        /** 本轮结果版本（overlay 覆盖写时递增，供下游判断是否新一轮） */
        int resultVersion;
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
