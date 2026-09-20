package org.openrefine.extensions.files.importer;

import com.google.refine.ProjectManager;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final int SAVE_EVERY_ROWS = 10;

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
        public volatile int totalPages;
        public volatile boolean cancelRequested;
        public volatile String status = STATUS_RUNNING;
        public volatile String message = "";
        public volatile int processedPages;
        public volatile int failedPages;
        public volatile String currentUnit = "";
        public volatile int rowsAppended;

        public Task(long projectId, String rootPath, ExtractionTemplate template,
                    List<CustomElementType> customElements, List<UnitScanner.Volume> units) {
            this.projectId = projectId;
            this.rootPath = rootPath;
            this.template = template;
            this.customElements = customElements;
            this.units = units;
            int total = 0;
            for (UnitScanner.Volume u : units) total += u.pages.size();
            this.totalPages = total;
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
                      List<CustomElementType> customElements, String aimpUrl) {
        List<UnitScanner.Volume> units = template == ExtractionTemplate.BATCH_TITLE_CASE
                ? UnitScanner.scanCases(rootPath)
                : UnitScanner.scanVolumes(rootPath);
        Task task = new Task(projectId, rootPath, template, customElements, units);
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
        AimpLlmClient client = new AimpLlmClient(aimpUrl);
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
                if (unit.pdfMode) {
                    for (String pdf : unit.pages) {
                        if (task.cancelRequested) break;
                        AimpLlmClient.ExtractPageResult r = client.extractPage(pdf, keyList, customJson);
                        task.processedPages += Math.max(1, r.pageCount);
                        if (r.success) {
                            task.totalPages += Math.max(0, r.pageCount - 1);
                            consecutiveFailures = 0;
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            p.startPage = 1;
                            p.endPage = Math.max(1, r.pageCount);
                            p.title = r.values.getOrDefault("title", "");
                            appendUnitRow(task, project, unit, unitSeq, p, r.values, null);
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
                    int unitFailedPages = 0;
                    for (int i = 0; i < unit.pages.size(); i++) {
                        String page = unit.pages.get(i);
                        if (task.cancelRequested) break;
                        AimpLlmClient.ExtractPageResult r = client.extractPage(page, keyList, customJson,
                                i + 1, unit.pages.size());
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
                        task.processedPages++;
                    }
                    if (task.cancelRequested) break;
                    String remark = unitFailedPages > 0 ? unitFailedPages + " 页提取失败" : null;
                    if (task.template == ExtractionTemplate.BATCH_TITLE_CASE) {
                        TitleSplitter.Piece p = new TitleSplitter.Piece();
                        p.startPage = 1;
                        p.endPage = unit.pages.size();
                        p.title = firstNonEmpty(titles);
                        Map<String, String> merged = accumulate(pageValues);
                        appendUnitRow(task, project, unit, unitSeq, p, merged.isEmpty() ? null : merged, remark);
                    } else {
                        List<TitleSplitter.Piece> pieces = TitleSplitter.split(titles);
                        int pieceNo = 1;
                        for (TitleSplitter.Piece p : pieces) {
                            List<Map<String, String>> pieceValues =
                                    pageValues.subList(p.startPage - 1, p.endPage);
                            Map<String, String> merged = accumulate(pieceValues);
                            appendUnitRow(task, project, unit, pieceNo, p, merged.isEmpty() ? null : merged, remark);
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
        Map<String, String> merged = new HashMap<>();
        for (Map<String, String> pv : pageValues) {
            if (pv == null) continue;
            for (Map.Entry<String, String> e : pv.entrySet()) {
                String v = e.getValue();
                if (v != null && !v.trim().isEmpty() && !merged.containsKey(e.getKey())) {
                    merged.put(e.getKey(), v.trim());
                }
            }
        }
        return merged;
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

    private void fail(Task task, String message) {
        task.status = STATUS_FAILED;
        task.message = message;
        saveProject(task.projectId);
        logger.warn("Batch extraction task failed: projectId={}, message={}", task.projectId, message);
    }
}
