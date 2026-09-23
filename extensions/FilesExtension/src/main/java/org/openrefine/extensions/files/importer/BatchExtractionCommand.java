package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.ProjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BatchExtractionCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(BatchExtractionCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_AIMP_URL = "http://127.0.0.1:7998";

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
            String aimpUrl = request.getParameter("aimpUrl");
            boolean disableCache = "true".equalsIgnoreCase(request.getParameter("disableCache"));

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

            AimpLlmClient client = new AimpLlmClient(aimpUrl(aimpUrl));
            if (!client.testConnection()) {
                ObjectNode err = mapper.createObjectNode();
                err.put("code", "error");
                err.put("messageKey", "files-importing/aimp-unavailable");
                err.put("message", "AI服务模块不可用，请检查或重启模块");
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

            Project project = createEmptyProject(template, projectName, customElements, includeFileNameColumn);
            int totalPages = 0;
            for (UnitScanner.Volume v : preview) totalPages += v.pages.size();

            BatchExtractionManager.get().start(project.id, rootPath, template, customElements, aimpUrl(aimpUrl), disableCache);

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

    private Project createEmptyProject(ExtractionTemplate template, String projectName,
                                       List<CustomElementType> customElements, boolean includeFileNameColumn) throws Exception {
        Project project = new Project();
        List<String> columnNames = new ArrayList<>();
        for (String column : template.getColumns(includeFileNameColumn)) {
            columnNames.add(column);
        }
        int insertAt = columnNames.indexOf("成文日期");
        insertAt = insertAt >= 0 ? insertAt + 1 : columnNames.size();
        for (CustomElementType ce : customElements) {
            if (ce.isInclude()) {
                columnNames.add(insertAt++, ce.getName());
            }
        }
        for (int i = 0; i < columnNames.size(); i++) {
            project.columnModel.addColumn(i, new Column(i, columnNames.get(i)), false);
        }
        project.update();

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(projectName != null && !projectName.trim().isEmpty() ? projectName.trim() : "批量题名提取");
        metadata.setEncoding("UTF-8");
        ProjectManager.singleton.registerProject(project, metadata);
        return project;
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
        result.put("rowsAppended", task.rowsAppended);
        result.put("message", task.message);
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

    private ExtractionTemplate parseTemplate(String name) {
        if ("batch-title-case".equals(name)) {
            return ExtractionTemplate.BATCH_TITLE_CASE;
        }
        return ExtractionTemplate.BATCH_TITLE_VOLUME;
    }

    private String aimpUrl(String param) {
        return param != null && !param.trim().isEmpty() ? param.trim() : DEFAULT_AIMP_URL;
    }

    private void respondError(HttpServletResponse response, String message) throws IOException {
        ObjectNode err = mapper.createObjectNode();
        err.put("code", "error");
        err.put("message", message);
        respondJSON(response, err);
    }
}
