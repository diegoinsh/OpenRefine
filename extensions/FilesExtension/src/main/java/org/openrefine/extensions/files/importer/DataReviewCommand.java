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

    /**
     * 件级校审（内容组合类）在既有列之后追加的 5 列；仅在带 {@code pieceMap}
     * 启动时加入，旧链路结果列与旧口径逐字一致。
     */
    private static final List<String> PIECE_COLUMNS = Arrays.asList(
            "件号", "件题名", "要求项", "命中项", "缺失项");

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
            String projectName = trim(UnicodeText.decode(request.getParameter("projectName")));
            // 可选：沿用已存在的引擎项目（平台侧绑定表里的项目号）。
            // 重新校审必须写回同一个项目号，下游嵌入的界面地址（project=<引擎号>）才不会失效。
            String reuseProject = trim(request.getParameter("project"));
            String rulesJson = request.getParameter("rules");
            String aimpUrl = aimpUrl(request.getParameter("aimpUrl"));
            double confidence = parseDouble(request.getParameter("confidence"), 0.5);
            // 件级校审（内部参数，平台按已抽取项目装配）：缺省为空 → 旧链路逐字不变
            String pieceMapJson = trim(UnicodeText.decode(request.getParameter("pieceMap")));

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
                totalFiles = 0;
                for (PieceSpec piece : pieces) totalFiles += piece.pages.size();
            }

            Project project = prepareProject(projectName, reuseProject, pieces != null);
            Task task = new Task(project.id, rootPath, aimpUrl, confidence, rules, units, totalFiles);
            task.pieces = pieces;
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

    private Project createEmptyProject(String projectName, boolean pieceMode) throws Exception {
        Project project = new Project();
        List<String> cols = pieceMode ? pieceColumns() : COLUMNS;
        addColumns(project.columnModel, cols);
        project.update();

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(!projectName.isEmpty() ? projectName : "数据校审");
        metadata.setEncoding("UTF-8");
        ProjectManager.singleton.registerProject(project, metadata);
        return project;
    }

    private static List<String> pieceColumns() {
        List<String> cols = new ArrayList<>(COLUMNS);
        cols.addAll(PIECE_COLUMNS);
        return cols;
    }

    /**
     * 取本次校审要写入的项目：带 {@code project} 参数且项目存在时**沿用它**（清空旧行与
     * 旧 pageMap 后重跑），否则新建。
     *
     * <p>复用时项目号保持不变——平台侧据此把「外部项目号 → 引擎项目号」的绑定保持稳定。</p>
     */
    private Project prepareProject(String projectName, String reuseProject, boolean pieceMode) throws Exception {
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
                    if (pieceMode) {
                        ensurePieceColumns(existing);
                    }
                    return existing;
                }
                logger.warn("校审复用项目不存在，改为新建: {}", pid);
            } catch (NumberFormatException e) {
                logger.warn("校审 project 参数非法，改为新建: {}", reuseProject);
            }
        }
        return createEmptyProject(projectName, pieceMode);
    }

    /** 复用时补齐件级列（从旧链路切到件级校审时列集合会变大）。 */
    private static void ensurePieceColumns(Project project) throws Exception {
        ColumnModel cm = project.columnModel;
        for (String name : PIECE_COLUMNS) {
            if (cm.getColumnByName(name) == null) {
                int idx = cm.getMaxCellIndex() + 1;
                cm.addColumn(idx, new Column(idx, name), false);
            }
        }
        project.update();
    }

    /** 复用项目前清掉上一轮结果（行数据与 pageMap），保留项目号与其它元信息。 */
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
        try {
            Project project = ProjectManager.singleton.getProject(task.projectId);
            if (project == null) {
                finish(task, STATUS_FAILED, "项目不存在或已被删除");
                return;
            }
            if (task.pieces != null) {
                runPieces(task, project);
            } else {
                runFiles(task, project);
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

    /** 旧链路：按文件逐页校审（行为与引入件级校审前逐字一致）。 */
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
    }

    /**
     * 件级链路（内容组合类）：一件一行，按件内页清单做存在类检测。
     *
     * <p>{@code piece} 覆盖粒度判定「件内至少一处」，{@code everyPage} 判定「每页都有」；
     * 页清单由平台按已抽取项目下发，引擎只按文件名与 {@code rootPath} 扫描结果匹配。</p>
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
            String keys = pieceRuleKeys(piece);

            PieceOutcome outcome = evaluatePiece(task, piece, refs, cache, keys);
            Row row = buildPieceRow(project.columnModel, rowIndex + 1, piece, refs, outcome);
            ObjectNode pageMapRow = refs.isEmpty() ? null
                    : buildPiecePageMapRow(refs.get(0), piece, outcome, keys, cache);

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

    private Row buildPieceRow(ColumnModel columnModel, int seq, PieceSpec piece,
                              List<PageRef> refs, PieceOutcome outcome) {
        Row row = new Row(columnModel.getMaxCellIndex() + 1);
        PageRef first = refs.isEmpty() ? null : refs.get(0);
        put(columnModel, row, "序号", seq);
        put(columnModel, row, "案卷号", piece.volumeNo);
        put(columnModel, row, "文件名", first == null ? "" : first.fileName);
        put(columnModel, row, "页数", String.valueOf(piece.pages.size()));
        put(columnModel, row, "规则分类", "存在类");
        put(columnModel, row, "校审规则", String.join("、", outcome.ruleNames(piece)));
        put(columnModel, row, "校审结果", outcome.resultLabel);
        put(columnModel, row, "印章数量", String.valueOf(outcome.sealSum));
        put(columnModel, row, "指纹数量", String.valueOf(outcome.fpSum));
        put(columnModel, row, "说明", outcome.detail);
        put(columnModel, row, FOLDER_PATH_COLUMN, first == null ? "" : first.unit.path);
        put(columnModel, row, "件号", piece.pieceNo);
        put(columnModel, row, "件题名", piece.title);
        put(columnModel, row, "要求项", String.join("、", outcome.requiredNames));
        put(columnModel, row, "命中项", String.join("、", outcome.hitNames));
        put(columnModel, row, "缺失项", String.join("、", outcome.missingNames));
        return row;
    }

    private ObjectNode buildPiecePageMapRow(PageRef ref, PieceSpec piece, PieceOutcome outcome,
                                            String keys, Map<String, Detection> cache) {
        Detection d = cache.get(ref.path);
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
        if (d != null && d.ok) {
            ObjectNode ann = buildAnnotation(ref.fileName, ref.pageNo, keys, d);
            if (ann != null) {
                rowNode.set(ANNOTATION_KEY, ann);
            }
        }
        return rowNode;
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
        if (hasKey(keys, "seal") && detection.sealCount == 0) {
            ObjectNode missing = items.addObject();
            missing.put("kind", "missing");
            missing.put("label", "印章");
            missing.put("color", "seal");
            missing.put("message", "未检出印章（可能存在漏章）");
        }
        if (hasKey(keys, "fingerprint") && detection.fingerprintCount == 0) {
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
            if (!"seal".equals(rule.key) && !"fingerprint".equals(rule.key)) {
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
        final long startedAt = System.currentTimeMillis();
        /** 件级校审参数；null = 旧链路（按文件逐页校审） */
        List<PieceSpec> pieces;

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
