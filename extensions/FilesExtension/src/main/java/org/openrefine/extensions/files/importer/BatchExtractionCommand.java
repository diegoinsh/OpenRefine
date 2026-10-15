package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.ModelException;
import com.google.refine.model.Project;
import com.google.refine.model.SheetData;
import com.google.refine.ProjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BatchExtractionCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(BatchExtractionCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_AIMP_URL = "http://127.0.0.1:7998";

    public static final String INNER_SHEET_ID = "batch#卷内";
    public static final String INNER_SHEET_NAME = "卷内";
    public static final String SUMMARY_SHEET_ID = "batch#卷级";
    public static final String SUMMARY_SHEET_NAME = "卷级";

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
        } else if ("pageMap".equals(subCommand)) {
            doPageMap(request, response);
        } else if ("list".equals(subCommand)) {
            doList(request, response);
        } else if ("save-task".equals(subCommand)) {
            doSaveTask(request, response);
        } else if ("pause".equals(subCommand)) {
            doControl(request, response, "pause");
        } else if ("resume".equals(subCommand)) {
            doControl(request, response, "resume");
        } else {
            respondError(response, "Unknown subCommand: " + subCommand);
        }
    }

    private void doStart(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        try {
            String rootPath = request.getParameter("rootPath");
            String projectName = request.getParameter("projectName");
            String templateParam = request.getParameter("template");
            String customElementsJson = request.getParameter("customElements");
            String archiveCategory = request.getParameter("archiveCategory");
            String archiveSubCategory = request.getParameter("archiveSubCategory");
            String fixedElementsJson = request.getParameter("fixedElements");
            String aimpUrl = request.getParameter("aimpUrl");
            // 请求没带的项回退到「高级设置」里的系统级配置：控制台发起时通常只给目录与模式
            Map<String, Object> config = ExtractionConfigStore.load();
            if (archiveCategory == null || archiveCategory.trim().isEmpty()) {
                Object v = config.get("extraction.defaultCategory");
                archiveCategory = v == null ? null : String.valueOf(v);
            }
            if (archiveSubCategory == null || archiveSubCategory.trim().isEmpty()) {
                Object v = config.get("extraction.defaultSubCategory");
                archiveSubCategory = v == null ? null : String.valueOf(v);
            }
            boolean disableCache = request.getParameter("disableCache") != null
                    ? "true".equalsIgnoreCase(request.getParameter("disableCache"))
                    : Boolean.parseBoolean(String.valueOf(config.get("extraction.disableCache")));

            if (rootPath == null || rootPath.trim().isEmpty()) {
                respondError(response, "缺少 rootPath 参数");
                return;
            }
            ExtractionTemplate template = parseTemplate(templateParam);
            List<CustomElementType> customElements = CustomElementsCodec.fromJson(customElementsJson);
            List<String> errors = CustomElementType.validate(customElements, template);
            if (!errors.isEmpty()) {
                respondError(response, "自定义提取类型校验失败: " + String.join("; ", errors));
                return;
            }

            // 续跑判定：本次「启动」的参数与登记表里上次完全一致时，才敢沿用项目里已落盘的行
            // （参数一变，列构成随之改变，旧行与新列对不上）。用户勾「禁用缓存」即视为从零重跑。
            Map<String, Object> prevRecord = null;
            String targetParam = request.getParameter("project");
            if (targetParam != null && !targetParam.trim().isEmpty()) {
                try {
                    prevRecord = ExtractionTaskRegistry.get(Long.parseLong(targetParam.trim()));
                } catch (NumberFormatException ignored) {
                    // 非法 id 交给 createOrReuseProject 统一报错
                }
            }
            boolean resume = !disableCache && matchesResumeSignature(prevRecord, rootPath, templateParam,
                    archiveCategory, archiveSubCategory, fixedElementsJson, customElementsJson);
            List<String> completedUnits = resume ? stringList(prevRecord.get("completedUnits"))
                    : new ArrayList<>();

            // 勾选的固定要素键（title/responsible_party/document_number/date）；
            // 为空表示沿用模板的全部要素，未勾选的要素不向 AIMP 请求
            List<String> selectedKeys = new ArrayList<>();
            if (fixedElementsJson != null && !fixedElementsJson.trim().isEmpty()) {
                try {
                    JsonNode node = mapper.readTree(fixedElementsJson);
                    if (node.isArray()) {
                        for (JsonNode n : node) {
                            String k = n.asText("").trim();
                            if (!k.isEmpty()) {
                                selectedKeys.add(k);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("fixedElements 不是合法 JSON 数组，已按模板默认要素处理: " + e.getMessage());
                }
            }

            AimpLlmClient client = new AimpLlmClient(aimpUrl(aimpUrl));
            AimpLlmClient.CompatibilityResult compat = client.checkCompatibility();
            if (!compat.reachable) {
                ObjectNode err = mapper.createObjectNode();
                err.put("code", "error");
                err.put("messageKey", "files-importing/aimp-unavailable");
                err.put("message", "AI服务模块不可用，请检查或重启模块");
                respondJSON(response, err);
                return;
            }
            if (!compat.compatible) {
                // 两边版本不配套时，后续调用会以各种莫名其妙的运行时错误呈现，
                // 故在开跑前就拦下，并说明双方各自的版本，用户照着更新模块即可
                ObjectNode err = mapper.createObjectNode();
                err.put("code", "error");
                err.put("messageKey", "files-importing/aimp-version-mismatch");
                err.put("message", "AI服务模块版本不匹配：本扩展要求接口版本 "
                        + compat.expectedApiVersions() + "，当前模块为 " + compat.actualVersion()
                        + "，请更新模块后重试");
                err.put("expectedApiVersion", compat.expectedApiVersions());
                err.put("actualVersion", compat.actualVersion());
                respondJSON(response, err);
                return;
            }

            List<UnitScanner.Volume> preview = template == ExtractionTemplate.BATCH_TITLE_CASE
                    ? UnitScanner.scanCases(rootPath)
                    : UnitScanner.scanVolumes(rootPath);
            if (preview.isEmpty()) {
                respondError(response, template == ExtractionTemplate.BATCH_TITLE_CASE
                        ? "未发现可提取的件：根目录下需为包含图像/PDF的文件夹或PDF文件"
                        : "未发现可提取的案卷：根目录下需为包含图像/PDF的子文件夹");
                return;
            }

            // 只有存在 PDF 等多页文件时才需要「文件名」列：单页图片按整目录成件，该列整列为空
            boolean includeFileNameColumn = false;
            for (UnitScanner.Volume v : preview) {
                if (v.pdfMode) {
                    includeFileNameColumn = true;
                    break;
                }
            }

            Project project = createOrReuseProject(request, response, template, projectName,
                    customElements, includeFileNameColumn, resume);
            if (project == null) {
                return;   // 复用校验失败（如既有任务在跑），错误响应已写出
            }
            int totalPages = 0;
            for (UnitScanner.Volume v : preview) totalPages += v.pages.size();

            BatchExtractionManager.get().start(project.id, rootPath, template, customElements,
                    archiveCategory, archiveSubCategory, selectedKeys, aimpUrl(aimpUrl), disableCache,
                    resume, completedUnits);

            // 登记到控制台可见的任务表（内存任务表重启即丢，控制台要能列历史任务）
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("projectId", project.id);
            record.put("projectName", project.getMetadata() == null ? projectName : project.getMetadata().getName());
            record.put("rootPath", rootPath);
            record.put("template", template == ExtractionTemplate.BATCH_TITLE_CASE
                    ? "batch-title-case" : "batch-title-volume");
            record.put("archiveCategory", archiveCategory == null ? "" : archiveCategory);
            record.put("archiveSubCategory", archiveSubCategory == null ? "" : archiveSubCategory);
            record.put("unitCount", preview.size());
            record.put("totalPages", totalPages);
            record.put("status", BatchExtractionManager.STATUS_RUNNING);
            record.put("startedAt", System.currentTimeMillis());
            // 抽取参数随任务一并落盘：重启后控制台按这份记录回填表单，勾选的要素（含案由、
            // 当事人、审级这类非必选项）才不会丢；completedUnits 是续跑的断点依据
            record.put("disableCache", disableCache);
            record.put("fixedElements", trimToEmpty(fixedElementsJson));
            record.put("customElements", trimToEmpty(customElementsJson));
            record.put("completedUnits", completedUnits);
            ExtractionTaskRegistry.upsert(project.id, record);

            result.put("code", "ok");
            result.put("projectId", project.id);
            result.put("projectName", projectName);
            result.put("unitCount", preview.size());
            result.put("totalPages", totalPages);
            respondJSON(response, result);
        } catch (Exception e) {
            logger.error("batch-extraction start failed", e);
            respondError(response, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 新建项目，或对既有项目「就地重跑」。
     *
     * <p>就地重跑（控制台「启动」）：沿用同一 projectId 与项目目录。{@code resume} 为 true 时
     * **不清空项目**——上次中断前已跑完的卷，其卷内/卷级行原样留在表里，本次只补跑剩下的卷；
     * 否则（参数变了、或用户勾了「禁用缓存」）重建列结构并清空旧行，从头跑一遍。
     */
    private Project createOrReuseProject(HttpServletRequest request, HttpServletResponse response,
                                         ExtractionTemplate template, String projectName,
                                         List<CustomElementType> customElements,
                                         boolean includeFileNameColumn, boolean resume)
            throws Exception {
        ColumnPlan plan = planColumns(template, customElements, includeFileNameColumn);
        String target = request.getParameter("project");
        if (target == null || target.trim().isEmpty()) {
            return createEmptyProject(projectName, plan);
        }

        long pid;
        try {
            pid = Long.parseLong(target.trim());
        } catch (NumberFormatException e) {
            respondError(response, "project 参数非法: " + target);
            return null;
        }
        BatchExtractionManager.Task live = BatchExtractionManager.get().getTask(pid);
        if (live != null && (BatchExtractionManager.STATUS_RUNNING.equals(live.status)
                || BatchExtractionManager.STATUS_PAUSED.equals(live.status))) {
            respondError(response, "该项目已有抽取任务在进行中，请先停止后再重跑");
            return null;
        }
        Project project = ProjectManager.singleton.getProject(pid);
        if (project == null) {
            respondError(response, "未找到项目: " + pid);
            return null;
        }
        if (resume) {
            // 续跑：参数没变，列结构本就与 plan 一致，已有行（含卷内/卷级）原样保留
            return project;
        }
        resetProject(project, plan);
        return project;
    }

    /** 就地重跑：重建列结构、清空旧行，保留同一项目与项目名 */
    private static void resetProject(Project project, ColumnPlan plan) throws ModelException {
        project.sheetDataMap.clear();
        project.rows = new ArrayList<>();
        project.columnModel = new ColumnModel();
        attachColumns(project, plan);
        project.update();
        ProjectManager.singleton.ensureProjectSaved(project.id);
    }

    private Project createEmptyProject(String projectName, ColumnPlan plan) throws Exception {
        Project project = new Project();
        attachColumns(project, plan);
        project.update();

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(projectName != null && !projectName.trim().isEmpty() ? projectName.trim() : "批量题名提取");
        metadata.setEncoding("UTF-8");
        ProjectManager.singleton.registerProject(project, metadata);
        return project;
    }

    /** 把列结构挂到项目上：案卷模板两张表（卷内/卷级），按件模板单表 */
    private static void attachColumns(Project project, ColumnPlan plan) throws ModelException {
        if (plan.volumeSummary) {
            SheetData innerSheet = new SheetData(INNER_SHEET_ID, INNER_SHEET_NAME, "");
            addColumns(innerSheet.columnModel, plan.innerColumns);
            SheetData summarySheet = new SheetData(SUMMARY_SHEET_ID, SUMMARY_SHEET_NAME, "");
            List<String> summaryColumns = ExtractionTemplate.volumeSummaryColumns();
            // 卷级要素排在「文件夹路径」之前，使文件夹路径固定为最后一列
            summaryColumns.addAll(summaryColumns.size() - 1, plan.volumeElementColumns);
            addColumns(summarySheet.columnModel, summaryColumns);
            project.addSheetData(innerSheet);
            project.addSheetData(summarySheet);
            project.setActiveSheet(INNER_SHEET_ID);
            summarySheet.columnModel.update();
        } else {
            addColumns(project.columnModel, plan.innerColumns);
        }
    }

    /** 列结构计划：卷内列 + （案卷模板下的）卷级要素列 */
    private static ColumnPlan planColumns(ExtractionTemplate template, List<CustomElementType> customElements,
                                          boolean includeFileNameColumn) {
        ColumnPlan plan = new ColumnPlan();
        plan.volumeSummary = template.isGenerateVolumeSummary();
        for (String column : template.getColumns(includeFileNameColumn)) {
            plan.innerColumns.add(column);
        }
        // 卷级要素（案由/当事人/审级/结案方式/密级/保管期限/开放状态）整卷同值，案卷模板下只出在
        // 「卷级」表，卷内表只保留件级要素——口径见 docs/design/archive-category-and-fields-standard.md 3.4
        int insertAt = plan.innerColumns.indexOf("成文日期");
        insertAt = insertAt >= 0 ? insertAt + 1 : plan.innerColumns.size();
        for (CustomElementType ce : customElements) {
            if (!ce.isInclude()) {
                continue;
            }
            if (plan.volumeSummary && ExtractionTemplate.isVolumeLevelElement(ce.getKey())) {
                plan.volumeElementColumns.add(ce.getName());
            } else {
                plan.innerColumns.add(insertAt++, ce.getName());
            }
        }
        return plan;
    }

    private static final class ColumnPlan {
        boolean volumeSummary;
        List<String> innerColumns = new ArrayList<>();
        List<String> volumeElementColumns = new ArrayList<>();
    }

    private static void addColumns(ColumnModel columnModel, List<String> columnNames) throws ModelException {
        for (int i = 0; i < columnNames.size(); i++) {
            columnModel.addColumn(i, new Column(i, columnNames.get(i)), false);
        }
    }

    private void doProgress(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        String projectId = request.getParameter("project");
        if (projectId == null || projectId.isEmpty()) {
            respondError(response, "缺少 project 参数");
            return;
        }
        BatchExtractionManager.Task task = BatchExtractionManager.get().getTask(Long.parseLong(projectId));
        if (task == null) {
            respondError(response, "未找到提取任务: " + projectId);
            return;
        }
        result.put("code", "ok");
        result.put("projectId", task.projectId);
        result.put("status", task.status);
        result.put("processedPages", task.processedPages);
        result.put("totalPages", task.totalPages);
        result.put("processedFiles", task.processedFiles);
        result.put("totalFiles", task.totalFiles);
        result.put("unitFraction", task.unitFraction);
        result.put("failedPages", task.failedPages);
        result.put("currentUnit", task.currentUnit);
        result.put("unitKind", task.template == ExtractionTemplate.BATCH_TITLE_VOLUME ? "volume" : "case");
        // 揭示节奏由后端给依据：串行逐页调用时前端「到达即显示」，并发批调用时按实测的平均
        // 每页耗时逐条揭示——不同门类的识别速度差很多，写死固定间隔必然对不上
        result.put("pageConcurrency", task.pageConcurrency);
        result.put("avgPageMillis", task.avgPageMillis());
        result.put("rowsAppended", task.rowsAppended);
        result.put("message", task.message);
        result.put("volumeIndex", task.volumeIndex);
        // 批次开始时间（epoch 毫秒）：前端据此显示「已耗时」并按完成度推算总耗时
        result.put("startedAt", task.startedAt);
        // 页级预览：件级行要等整卷抽完并分件后才写入，长卷（2-300 页）期间靠这里
        // 让用户看到「AI 认出了什么」。取值为页级原始识别结果，未做件级合并与分件矫正。
        ArrayNode previews = result.putArray("recentPages");
        synchronized (task.recentPages) {
            for (BatchExtractionManager.Task.PagePreview preview : task.recentPages.values()) {
                ObjectNode node = previews.addObject();
                node.put("page", preview.page);
                node.put("fileName", preview.fileName);
                // 本页是否复用自页级缓存：续跑时前面若干页是「历史」，前端一次性铺开、
                // 不按新页的平均识别耗时逐条揭示（否则重启后历史页会一条条回放）
                node.put("cached", preview.cached);
                // 要素按列顺序输出（每项带列名），前端拼成「列名：取值」
                appendPreviewFields(node.putArray("fields"), task, preview.values);
            }
        }
        respondJSON(response, result);
    }

    /**
     * 组装页级预览的要素取值：按列顺序（勾选的固定要素 + 自定义著录项）输出，每项带列名，
     * 前端拼成「列名：取值」。
     *
     * 预览只用于观察进展，原则是「AIMP 认出多少就展示多少」——题名、责任者、文号、
     * 成文日期属于固定要素、并不在 customElements 里，若只遍历自定义著录项就会漏掉。
     * 但**不追加列名未知的键**：AIMP 还会随页级结果返回 party_roles 这类内部辅助数据
     * （供卷级分件使用，不是著录项），展示出来只会是难读的原始 JSON。
     */
    private void appendPreviewFields(ArrayNode fields, BatchExtractionManager.Task task,
                                     Map<String, String> values) {
        java.util.Set<String> emitted = new java.util.HashSet<>();
        Map<String, String> fixedNames = task.template.getExtractionKeyMapping();
        Map<String, String> customNames = new java.util.HashMap<>();
        for (CustomElementType ce : task.customElements) {
            if (ce.isInclude() && ce.getKey() != null) {
                customNames.put(ce.getKey(),
                        ce.getName() != null && !ce.getName().isEmpty() ? ce.getName() : ce.getKey());
            }
        }
        List<String> ordered = new ArrayList<>();
        if (task.selectedKeys != null && !task.selectedKeys.isEmpty()) {
            ordered.addAll(task.selectedKeys);
        } else {
            ordered.addAll(Arrays.asList(task.template.getExtractionKeys()));
        }
        for (CustomElementType ce : task.customElements) {
            if (ce.isInclude()) {
                ordered.add(ce.getKey());
            }
        }
        for (String key : ordered) {
            if (!emitted.add(key)) continue;
            String value = values.get(key);
            if (value == null || value.isEmpty()) continue;
            addPreviewField(fields, displayName(key, fixedNames, customNames), value);
        }
    }

    /** 列名：固定要素查模板映射，自定义著录项用其名称 */
    private static String displayName(String key, Map<String, String> fixedNames, Map<String, String> customNames) {
        String name = fixedNames.get(key);
        if (name == null) {
            name = customNames.get(key);
        }
        return name != null ? name : key;
    }

    private static void addPreviewField(ArrayNode fields, String name, String value) {
        ObjectNode field = fields.addObject();
        field.put("name", name);
        field.put("value", value);
    }

    /**
     * 暂停 / 恢复（对齐数据质量 task-control）。暂停只是置标志位：当前正在识别的页会跑完，
     * 之后在批/页边界原地等待；恢复后从断点继续，已识别的页不重做。
     */
    private void doControl(HttpServletRequest request, HttpServletResponse response, String action) throws IOException {
        String projectId = request.getParameter("project");
        if (projectId == null || projectId.isEmpty()) {
            respondError(response, "缺少 project 参数");
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(projectId);
        } catch (NumberFormatException e) {
            respondError(response, "project 参数非法: " + projectId);
            return;
        }
        boolean pause = "pause".equals(action);
        boolean ok = pause ? BatchExtractionManager.get().pause(pid) : BatchExtractionManager.get().resume(pid);
        if (!ok) {
            respondError(response, (pause ? "暂停失败：任务不在运行中" : "恢复失败：任务不在暂停中"));
            return;
        }
        ExtractionTaskRegistry.updateRunState(pid,
                pause ? BatchExtractionManager.STATUS_PAUSED : BatchExtractionManager.STATUS_RUNNING,
                pause ? "已暂停" : null);

        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        result.put("status", pause ? BatchExtractionManager.STATUS_PAUSED : BatchExtractionManager.STATUS_RUNNING);
        respondJSON(response, result);
    }

    private void doCancel(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        String projectId = request.getParameter("project");
        if (projectId == null || projectId.isEmpty()) {
            respondError(response, "缺少 project 参数");
            return;
        }
        boolean ok = BatchExtractionManager.get().cancel(Long.parseLong(projectId));
        if (ok) {
            result.put("code", "ok");
            result.put("message", "取消请求已提交");
        } else {
            respondError(response, "未找到提取任务: " + projectId);
            return;
        }
        respondJSON(response, result);
    }

    /**
     * 返回「行 → 列 → 要素取值候选页」映射，供前端点击单元格时定位到该取值所在页。
     * 映射存于项目 metadata，未做过批量提取的项目返回 pageMap=null。
     */
    private void doPageMap(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        String projectId = request.getParameter("project");
        if (projectId == null || projectId.isEmpty()) {
            respondError(response, "缺少 project 参数");
            return;
        }
        Project project = ProjectManager.singleton.getProject(Long.parseLong(projectId));
        if (project == null) {
            respondError(response, "未找到项目: " + projectId);
            return;
        }
        Object stored = project.getMetadata() == null ? null
                : project.getMetadata().getCustomMetadata(BatchExtractionManager.PAGE_MAP_KEY);
        result.put("code", "ok");
        if (!(stored instanceof String) || ((String) stored).isEmpty()) {
            result.putNull("pageMap");
        } else {
            try {
                result.set("pageMap", mapper.readTree((String) stored));
            } catch (Exception e) {
                logger.warn("页码映射不是合法JSON，已忽略", e);
                result.putNull("pageMap");
            }
        }
        respondJSON(response, result);
    }

    /**
     * 控制台「抽取任务」列表：以登记表（持久化）+ 内存任务表（实时状态/进度）合并输出。
     * 登记表提供重启后仍在的历史任务；内存表提供正在跑的任务的实时页数/当前件。
     */
    private void doList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = result.putArray("tasks");
        for (Map<String, Object> record : ExtractionTaskRegistry.list()) {
            ObjectNode node = mapper.valueToTree(record);
            Object rawId = record.get("projectId");
            boolean live = false;
            if (rawId != null) {
                try {
                    BatchExtractionManager.Task liveTask = BatchExtractionManager.get().getTask(Long.parseLong(String.valueOf(rawId)));
                    if (liveTask != null) {
                        node.put("status", liveTask.status);
                        node.put("processedPages", liveTask.processedPages);
                        node.put("totalPages", liveTask.totalPages);
                        node.put("currentUnit", liveTask.currentUnit);
                        node.put("message", liveTask.message);
                        node.put("live", true);
                        live = true;
                    }
                } catch (NumberFormatException ignored) {
                    // 登记表里 projectId 非法时按静态记录输出
                }
            }
            // 登记表说「进行中/已暂停」但内存里没有对应任务 —— 说明上次是进程被直接关闭
            // （断电/杀进程）而不是点了暂停/停止。标为「已中断」，前端据此重新放出「启动」，
            // 再次启动即就地重跑并命中页级缓存，从断点继续。
            if (!live) {
                String status = String.valueOf(record.get("status"));
                if (BatchExtractionManager.STATUS_RUNNING.equals(status)
                        || BatchExtractionManager.STATUS_PAUSED.equals(status)) {
                    node.put("status", "interrupted");
                    node.put("message", "上次运行被中断（再次启动可续跑）");
                }
                node.put("live", false);
            }
            arr.add(node);
        }
        result.put("code", "ok");
        respondJSON(response, result);
    }

    /**
     * 控制台「编辑」：只保存该项目的抽取参数（目录/模式/门类/子类/缓存），不启动。
     * 状态置为「待开始」，随后由「启动」按这份参数开跑。
     */
    private void doSaveTask(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String projectId = request.getParameter("project");
        if (projectId == null || projectId.isEmpty()) {
            respondError(response, "缺少 project 参数");
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(projectId);
        } catch (NumberFormatException e) {
            respondError(response, "project 参数非法: " + projectId);
            return;
        }
        Project project = ProjectManager.singleton.getProject(pid);
        if (project == null) {
            respondError(response, "未找到项目: " + projectId);
            return;
        }

        ExtractionTemplate template = parseTemplate(request.getParameter("template"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("projectId", pid);
        record.put("projectName", project.getMetadata() == null ? ("#" + pid) : project.getMetadata().getName());
        record.put("rootPath", trimToEmpty(request.getParameter("rootPath")));
        record.put("template", template == ExtractionTemplate.BATCH_TITLE_CASE ? "batch-title-case" : "batch-title-volume");
        record.put("archiveCategory", trimToEmpty(request.getParameter("archiveCategory")));
        record.put("archiveSubCategory", trimToEmpty(request.getParameter("archiveSubCategory")));
        record.put("disableCache", "true".equalsIgnoreCase(request.getParameter("disableCache")));
        // 抽取要素随参数一并留存，控制台「编辑」时才能回填上次的选择
        record.put("fixedElements", trimToEmpty(request.getParameter("fixedElements")));
        record.put("customElements", trimToEmpty(request.getParameter("customElements")));
        record.put("status", "pending");
        record.put("configuredAt", System.currentTimeMillis());
        ExtractionTaskRegistry.merge(pid, record);

        ObjectNode result = mapper.createObjectNode();
        result.put("code", "ok");
        respondJSON(response, result);
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 续跑判定：本次「启动」的参数与登记表里上次是否完全一致。
     *
     * <p>只有一致才敢沿用项目里已落盘的行——目录、模板、门类子类、勾选要素（固定要素 + 自定义
     * 著录项）任一变，列的构成就会变，旧行与新列对不上；此时按全新任务处理（清空重跑）。
     * 上次没留下这些参数（改造前的旧登记项）时同样判为不一致，宁可从零重跑。
     */
    private boolean matchesResumeSignature(Map<String, Object> record, String rootPath, String template,
                                           String archiveCategory, String archiveSubCategory,
                                           String fixedElements, String customElements) {
        if (record == null) return false;
        String prev = resumeSignature(str(record.get("rootPath")), str(record.get("template")),
                str(record.get("archiveCategory")), str(record.get("archiveSubCategory")),
                str(record.get("fixedElements")), str(record.get("customElements")));
        String current = resumeSignature(rootPath, normalizeTemplate(template), archiveCategory,
                archiveSubCategory, fixedElements, customElements);
        return !prev.isEmpty() && prev.equals(current);
    }

    private static String resumeSignature(String rootPath, String template, String archiveCategory,
                                          String archiveSubCategory, String fixedElements,
                                          String customElements) {
        return trimToEmpty(rootPath) + "\n" + trimToEmpty(template) + "\n" + trimToEmpty(archiveCategory)
                + "\n" + trimToEmpty(archiveSubCategory) + "\n" + normalizeJson(fixedElements)
                + "\n" + normalizeJson(customElements);
    }

    /** JSON 规范化（容忍空白与键序差异）；非法 JSON 原样比较 */
    private static String normalizeJson(String json) {
        if (json == null || json.trim().isEmpty()) return "";
        try {
            return mapper.readTree(json).toString();
        } catch (Exception e) {
            return json.trim();
        }
    }

    private static String normalizeTemplate(String template) {
        return "batch-title-case".equals(template) ? "batch-title-case" : "batch-title-volume";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                if (item != null) out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private ExtractionTemplate parseTemplate(String name) {
        if ("batch-title-case".equals(name)) {
            return ExtractionTemplate.BATCH_TITLE_CASE;
        }
        return ExtractionTemplate.BATCH_TITLE_VOLUME;
    }

    /** 请求参数优先，其次「高级设置」里的 AIMP 服务地址，最后内置默认值 */
    private String aimpUrl(String param) {
        if (param != null && !param.trim().isEmpty()) {
            return param.trim();
        }
        String configured = ExtractionConfigStore.getString("aimp.server");
        return configured != null ? configured : DEFAULT_AIMP_URL;
    }

    private void respondError(HttpServletResponse response, String message) throws IOException {
        ObjectNode err = mapper.createObjectNode();
        err.put("code", "error");
        err.put("message", message);
        respondJSON(response, err);
    }
}
