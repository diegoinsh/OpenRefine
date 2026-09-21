package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatchExtractionManager {

    private static final Logger logger = LoggerFactory.getLogger(BatchExtractionManager.class);
    private static final BatchExtractionManager INSTANCE = new BatchExtractionManager();
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 要素置信度下限：低于该值的取值视为幻觉（如顶部归档章乱码），不写入结果 */
    private static final double MIN_ELEMENT_CONFIDENCE = 0.3;
    private static final int SAVE_EVERY_ROWS = 10;
    /** 异步任务轮询间隔 */
    private static final int ASYNC_POLL_INTERVAL_MS = 1000;
    /** 单份文档异步提取的等待上限；页数极多时远大于同步读取超时，且可随时取消 */
    private static final long ASYNC_TASK_DEADLINE_MS = 60L * 60 * 1000;
    private static final ObjectMapper mapper = new ObjectMapper();

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_FAILED = "failed";

    public static class Task {
        public final long projectId;
        public final String rootPath;
        public final ExtractionTemplate template;
        public final List<CustomElementType> customElements;
        public final List<UnitScanner.Volume> units;
        public final boolean disableCache;
        public volatile int totalPages;
        /** 待处理件数（每个子文件夹或单个 PDF 文件各算一件，与页数无关） */
        public volatile int totalFiles;
        public volatile int processedFiles;
        public volatile boolean cancelRequested;
        public volatile String status = STATUS_RUNNING;
        public volatile String message = "";
        public volatile int processedPages;
        public volatile int failedPages;
        public volatile String currentUnit = "";
        public volatile int rowsAppended;

        public Task(long projectId, String rootPath, ExtractionTemplate template,
                    List<CustomElementType> customElements, List<UnitScanner.Volume> units,
                    boolean disableCache) {
            this.projectId = projectId;
            this.rootPath = rootPath;
            this.template = template;
            this.customElements = customElements;
            this.units = units;
            this.disableCache = disableCache;
            int total = 0;
            for (UnitScanner.Volume u : units) total += u.pages.size();
            this.totalPages = total;
            this.totalFiles = units.size();
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "batch-title-extraction");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final Object rowLock = new Object();
    private int rowsSinceSave;

    private BatchExtractionManager() {
    }

    public static BatchExtractionManager get() {
        return INSTANCE;
    }

    public Task start(long projectId, String rootPath, ExtractionTemplate template,
                      List<CustomElementType> customElements, String aimpUrl, boolean disableCache) {
        List<UnitScanner.Volume> units = template == ExtractionTemplate.BATCH_TITLE_CASE
                ? UnitScanner.scanCases(rootPath)
                : UnitScanner.scanVolumes(rootPath);
        Task task = new Task(projectId, rootPath, template, customElements, units, disableCache);
        tasks.put(projectId, task);
        executor.submit(() -> run(task, aimpUrl));
        return task;
    }

    public Task getTask(long projectId) {
        return tasks.get(projectId);
    }

    public boolean cancel(long projectId) {
        Task task = tasks.get(projectId);
        if (task == null) return false;
        task.cancelRequested = true;
        return true;
    }

    private void run(Task task, String aimpUrl) {
        try {
            executeExtraction(task, aimpUrl);
        } catch (Throwable t) {
            logger.error("Batch extraction task aborted", t);
            fail(task, t.toString());
        }
    }

    private void executeExtraction(Task task, String aimpUrl) {
        AimpLlmClient client = new AimpLlmClient(aimpUrl);
        client.setDisableCache(task.disableCache);
        if (!client.testConnection()) {
            fail(task, "AIMP_SERVICE_UNAVAILABLE");
            return;
        }
        String keyList = String.join(",", task.template.getExtractionKeys());
        String customJson = CustomElementsCodec.toJson(task.customElements);
        Project project = ProjectManager.singleton.getProject(task.projectId);
        if (project == null) {
            fail(task, "Project not found: " + task.projectId);
            return;
        }
        try {
            int consecutiveFailures = 0;
            int unitSeq = 1;
            for (UnitScanner.Volume unit : task.units) {
                if (task.cancelRequested) break;
                task.currentUnit = unit.name;
                // 件级计数：进入某件即计一件，与件内页数无关
                task.processedFiles++;
                if (unit.pdfMode) {
                    for (int k = 0; k < unit.pages.size(); k++) {
                        if (task.cancelRequested) break;
                        String pdf = unit.pages.get(k);
                        String label = fileLabel(new File(pdf).getName(), k + 1, unit.pages.size());
                        int pagesBefore = task.processedPages;
                        int totalBefore = task.totalPages;
                        task.message = label + " 提交中…";

                        AimpLlmClient.ExtractPageResult r = client.submitAsync(pdf, keyList, customJson);
                        if (!r.success || r.taskId == null || r.taskId.isEmpty()) {
                            consecutiveFailures++;
                            task.failedPages++;
                            task.processedPages = pagesBefore + 1;
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            appendUnitRow(task, project, unit, unitSeq, p, null,
                                    r.error != null ? r.error : "提交提取任务失败");
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                fail(task, "AIMP 连续失败 " + consecutiveFailures + " 次，任务中止");
                                return;
                            }
                            unitSeq++;
                            continue;
                        }

                        // 异步轮询：同步等待会在大页数文档上触发读取超时，这里按页观察进度
                        int realPages = 0;
                        boolean corrected = false;
                        boolean timedOut = false;
                        long deadline = System.currentTimeMillis() + ASYNC_TASK_DEADLINE_MS;
                        while (true) {
                            if (task.cancelRequested) break;
                            if (System.currentTimeMillis() > deadline) {
                                timedOut = true;
                                break;
                            }
                            AimpLlmClient.TaskStatusResult st = client.getTaskStatus(r.taskId);
                            if (!st.success) {
                                if (st.error != null && st.error.startsWith("HTTP 404")) {
                                    // 任务在 AIMP 侧已不存在（如服务重启），继续轮询无意义
                                    AimpLlmClient.ExtractPageResult lost = new AimpLlmClient.ExtractPageResult();
                                    lost.error = "AIMP 任务已丢失（服务可能已重启）";
                                    r = lost;
                                    break;
                                }
                                // 网络抖动等瞬时错误：保持轮询，不中断整批任务
                                sleepQuietly(ASYNC_POLL_INTERVAL_MS);
                                continue;
                            }
                            if (st.totalPages > 0) realPages = st.totalPages;
                            if (!corrected && realPages > 1) {
                                task.totalPages = totalBefore + realPages - 1;
                                corrected = true;
                            }
                            task.processedPages = pagesBefore + st.processedPages;
                            task.message = realPages > 0
                                    ? label + " 第 " + Math.min(st.processedPages + 1, realPages) + "/" + realPages + " 页"
                                    : label + " 解析中";
                            if ("completed".equals(st.status)) {
                                AimpLlmClient.ExtractPageResult parsed = client.parseTaskResult(st.result);
                                parsed.pageCount = realPages > 0 ? realPages : 1;
                                r = parsed;
                                break;
                            }
                            if ("failed".equals(st.status) || "cancelled".equals(st.status)) {
                                AimpLlmClient.ExtractPageResult failed = new AimpLlmClient.ExtractPageResult();
                                failed.error = st.error != null && !st.error.isEmpty()
                                        ? st.error : "AIMP 任务" + st.status;
                                r = failed;
                                break;
                            }
                            sleepQuietly(ASYNC_POLL_INTERVAL_MS);
                        }
                        if (task.cancelRequested) break;

                        int donePages = realPages > 1 ? realPages : Math.max(1, r.pageCount);
                        if (!corrected && donePages > 1) task.totalPages = totalBefore + donePages - 1;
                        task.processedPages = pagesBefore + donePages;
                        if (timedOut) {
                            r.success = false;
                            r.error = "等待提取结果超时（" + (ASYNC_TASK_DEADLINE_MS / 60000) + " 分钟）";
                        }

                        if (r.success) {
                            consecutiveFailures = 0;
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            p.startPage = 1;
                            p.endPage = Math.max(1, r.pageCount);
                            p.title = r.values.getOrDefault("title", "");
                            Map<String, String> pdfValues = filterLowConfidence(r.values, r.confidences);
                            appendUnitRow(task, project, unit, unitSeq, p, pdfValues, null);
                        } else {
                            consecutiveFailures++;
                            task.failedPages++;
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            appendUnitRow(task, project, unit, unitSeq, p, null, r.error);
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                fail(task, "AIMP 连续失败 " + consecutiveFailures + " 次，任务中止");
                                return;
                            }
                        }
                        unitSeq++;
                    }
                } else {
                    List<String> titles = new ArrayList<>();
                    List<Map<String, String>> pageValues = new ArrayList<>();
                    List<Map<String, Double>> pageConfidences = new ArrayList<>();
                    int unitFailedPages = 0;
                    for (int i = 0; i < unit.pages.size(); i++) {
                        String page = unit.pages.get(i);
                        if (task.cancelRequested) break;
                        task.message = new File(page).getName() + " 第 " + (i + 1) + "/" + unit.pages.size() + " 页";
                        AimpLlmClient.ExtractPageResult r = client.extractPage(page, keyList, customJson,
                                i + 1, unit.pages.size(), buildPreviousExtractions(pageValues, pageConfidences));
                        if (r.success) {
                            consecutiveFailures = 0;
                        } else {
                            consecutiveFailures++;
                            task.failedPages++;
                            unitFailedPages++;
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                fail(task, "AIMP 连续失败 " + consecutiveFailures + " 次，任务中止");
                                return;
                            }
                        }
                        titles.add(r.success ? r.values.getOrDefault("title", "") : "");
                        pageValues.add(r.success ? r.values : null);
                        pageConfidences.add(r.success ? r.confidences : null);
                        task.processedPages++;
                    }
                    if (task.cancelRequested) break;
                    String remark = unitFailedPages > 0 ? unitFailedPages + " 页提取失败" : null;
                    if (task.template == ExtractionTemplate.BATCH_TITLE_CASE) {
                        TitleSplitter.Piece p = new TitleSplitter.Piece();
                        p.startPage = 1;
                        p.endPage = unit.pages.size();
                        p.title = firstNonEmpty(titles);
                        MergedElements merged = accumulateByConfidence(pageValues, pageConfidences);
                        appendUnitRow(task, project, unit, unitSeq, p,
                                merged.isEmpty() ? null : merged.values, remark);
                    } else {
                        List<TitleSplitter.Piece> pieces = TitleSplitter.split(titles);
                        int pieceNo = 1;
                        for (TitleSplitter.Piece p : pieces) {
                            List<Map<String, String>> pieceValues =
                                    pageValues.subList(p.startPage - 1, p.endPage);
                            MergedElements merged = accumulateByConfidence(pieceValues,
                                    pageConfidences.subList(p.startPage - 1, p.endPage));
                            appendUnitRow(task, project, unit, pieceNo, p,
                                    merged.isEmpty() ? null : merged.values, remark);
                            pieceNo++;
                        }
                    }
                }
                unitSeq++;
            }
            if (task.cancelRequested && !STATUS_FAILED.equals(task.status)) {
                task.status = STATUS_CANCELLED;
                task.message = "已取消";
            } else if (!STATUS_FAILED.equals(task.status)) {
                task.status = STATUS_COMPLETED;
                task.message = "提取完成";
            }
            saveProject(task.projectId);
        } catch (Exception e) {
            logger.error("Batch extraction failed", e);
            fail(task, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String buildPreviousExtractions(List<Map<String, String>> pageValues,
                                           List<Map<String, Double>> pageConfidences) {
        if (pageValues == null || pageValues.isEmpty()) return null;
        ArrayNode arr = mapper.createArrayNode();
        for (int i = 0; i < pageValues.size(); i++) {
            Map<String, String> values = pageValues.get(i);
            if (values == null || values.isEmpty()) continue;
            Map<String, Double> confs = pageConfidences != null && i < pageConfidences.size()
                    ? pageConfidences.get(i) : null;
            ObjectNode elements = mapper.createObjectNode();
            for (Map.Entry<String, String> e : values.entrySet()) {
                String v = e.getValue();
                if (v == null || v.trim().isEmpty()) continue;
                ObjectNode node = elements.putObject(e.getKey()).put("value", v);
                Double conf = confs == null ? null : confs.get(e.getKey());
                if (conf != null) node.put("confidence", conf);
            }
            if (elements.size() == 0) continue;
            ObjectNode pageNode = mapper.createObjectNode();
            pageNode.put("page_index", i + 1);
            pageNode.set("elements", elements);
            arr.add(pageNode);
        }
        return arr.size() == 0 ? null : arr.toString();
    }

    private void appendUnitRow(Task task, Project project, UnitScanner.Volume unit, int seq,
                               TitleSplitter.Piece piece, Map<String, String> values, String remark) {
        String status;
        if (values == null) {
            status = "失败";
            if (remark == null || remark.isEmpty()) remark = "提取失败";
        } else if (remark != null && !remark.isEmpty()) {
            status = "部分成功";
        } else {
            status = "成功";
        }
        Row row = new Row(project.columnModel.getMaxCellIndex() + 1);
        if (task.template == ExtractionTemplate.BATCH_TITLE_VOLUME) {
            put(project, row, "案卷号", unit.name);
            put(project, row, "件号", String.format("%03d", seq));
            put(project, row, "起止页号", String.format("%04d-%04d", piece.startPage, piece.endPage));
        } else {
            put(project, row, "文件夹名", unit.name);
            put(project, row, "件号", parsePieceNumber(unit.name, seq));
        }
        put(project, row, "页数", piece.pageCount());
        put(project, row, "题名", piece.title);
        put(project, row, "责任者", get(values, "responsible_party"));
        put(project, row, "文号", get(values, "document_number"));
        put(project, row, "成文日期", normalizeDate(get(values, "date")));
        put(project, row, "文件夹路径", unit.path);
        put(project, row, "提取状态", status);
        put(project, row, "备注", remark == null ? "" : remark);
        for (CustomElementType ce : task.customElements) {
            if (ce.isInclude()) {
                put(project, row, ce.getName(), get(values, ce.getKey()));
            }
        }
        appendRow(task, project, row);
    }

    private void appendRow(Task task, Project project, Row row) {
        synchronized (rowLock) {
            project.rows.add(row);
            task.rowsAppended++;
            if (++rowsSinceSave >= SAVE_EVERY_ROWS) {
                rowsSinceSave = 0;
                saveProject(task.projectId);
            }
        }
    }

    private void saveProject(long projectId) {
        try {
            ProjectManager.singleton.ensureProjectSaved(projectId);
        } catch (Exception e) {
            logger.warn("Failed to save project {}", projectId, e);
        }
    }

    private void put(Project project, Row row, String columnName, java.io.Serializable value) {
        Column column = project.columnModel.getColumnByName(columnName);
        if (column != null) {
            row.setCell(column.getCellIndex(), new Cell(value, null));
        }
    }

    private String get(Map<String, String> values, String key) {
        if (values == null) return "";
        String v = values.get(key);
        return v == null ? "" : v;
    }

    private String firstNonEmpty(List<String> titles) {
        for (String t : titles) {
            if (t != null && !t.trim().isEmpty()) return t.trim();
        }
        return "";
    }

    static Map<String, String> accumulate(List<Map<String, String>> pageValues) {
        return accumulateByConfidence(pageValues, null).values;
    }

    /** 跨页累积结果：values 与 confidences 一一对应，且只保留达到置信度下限的要素 */
    static class MergedElements {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Double> confidences = new HashMap<>();

        boolean isEmpty() {
            return values.isEmpty();
        }
    }

    /**
     * 跨页累积：同一要素取置信度最高的页面取值，低于 {@link #MIN_ELEMENT_CONFIDENCE}
     * 的取值视为幻觉（如顶部归档章乱码）直接丢弃，其余页面若置信度更高则覆盖。
     * 页面未返回置信度时按首个非空值保底，避免整列丢空。
     */
    static MergedElements accumulateByConfidence(List<Map<String, String>> pageValues,
                                                 List<Map<String, Double>> pageConfidences) {
        MergedElements merged = new MergedElements();
        for (int i = 0; i < pageValues.size(); i++) {
            Map<String, String> pv = pageValues.get(i);
            if (pv == null) continue;
            Map<String, Double> pc = pageConfidences != null && i < pageConfidences.size()
                    ? pageConfidences.get(i) : null;
            for (Map.Entry<String, String> e : pv.entrySet()) {
                String key = e.getKey();
                String v = e.getValue();
                if (v == null || v.trim().isEmpty()) continue;
                Double conf = pc == null ? null : pc.get(key);
                if (conf == null) {
                    if (!merged.values.containsKey(key)) merged.values.put(key, v.trim());
                    continue;
                }
                if (conf < MIN_ELEMENT_CONFIDENCE) continue;
                Double best = merged.confidences.get(key);
                if (best == null || conf > best) {
                    merged.values.put(key, v.trim());
                    merged.confidences.put(key, conf);
                }
            }
        }
        return merged;
    }

    /** 单次多页结果（AIMP 内部已按置信度选优）同样丢弃低置信度要素 */
    static Map<String, String> filterLowConfidence(Map<String, String> values, Map<String, Double> confidences) {
        if (values == null || values.isEmpty() || confidences == null || confidences.isEmpty()) return values;
        Map<String, String> kept = new HashMap<>(values);
        kept.keySet().removeIf(k -> {
            Double c = confidences.get(k);
            return c != null && c < MIN_ELEMENT_CONFIDENCE;
        });
        return kept;
    }

    /** 件内含多个文件时补件内序号，便于区分同一件中的第几个 PDF */
    private String fileLabel(String fileName, int index, int total) {
        return total > 1 ? fileName + "（第 " + index + "/" + total + " 个）" : fileName;
    }

    private String parsePieceNumber(String name, int seq) {
        if (name != null) {
            Matcher m = Pattern.compile("\\d+").matcher(name);
            if (m.find()) {
                return m.group();
            }
        }
        return String.format("%03d", seq);
    }

    private String normalizeDate(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        try {
            Matcher m = Pattern.compile("(\\d{4})[年./-](\\d{1,2})[月./-](\\d{1,2})日?").matcher(s);
            if (m.find()) {
                return String.format("%04d%02d%02d", Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            m = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$").matcher(s);
            if (m.matches()) {
                return s;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(Task task, String message) {
        task.status = STATUS_FAILED;
        task.message = message;
        saveProject(task.projectId);
        logger.warn("Batch extraction task failed: projectId={}, message={}", task.projectId, message);
    }
}
