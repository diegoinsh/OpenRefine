/*
 * Data Quality Extension - SSO Project Ingest Command
 *
 * 供外部系统（SSO）接入用：按外部项目号幂等地建立/重建一个 OpenRefine 项目，
 * 使 SSO 侧上传的条目 Excel 成为可被 run-quality-check 检查的 OR 项目。
 */
package com.google.refine.extension.quality.commands;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.commands.Command;
import com.google.refine.model.Column;
import com.google.refine.model.Project;

/**
 * 建立/重建 SSO 项目对应的 OR 项目。
 *
 * <p>请求（POST，multipart 或普通表单/查询串皆可）：
 * <ul>
 *   <li>{@code projectId}（必填）：外部项目号，OR 项目名固定为 {@code SSO-{projectId}}</li>
 *   <li>{@code projectName}（可选）：展示名，写入项目元数据</li>
 *   <li>{@code excelPath}（可选）：本机 Excel（xls/xlsx）绝对路径</li>
 *   <li>{@code file}（可选）：multipart 上传的 Excel；与 excelPath 二选一</li>
 *   <li>{@code mode}（可选）：{@code rebuild}（默认，已存在则删除重建）/ {@code reuse}（已存在则复用）</li>
 * </ul>
 *
 * <p>响应：{@code {"code":"ok","ssoProjectId":..,"projectId":..,"projectName":..,"rowCount":..,"created":..}}
 *
 * <p>说明：OR 无 {@code ProjectManager.createProject}，此处按 core 的范式手工构造
 * {@link Project}（POI 读表 → 建列 → 建行），再 {@code registerProject} + {@code ensureProjectSaved}
 * 落盘。跨扩展复用不可行（各扩展在 butterfly 独立类加载器中），故不依赖 FilesExtension。
 */
public class IngestProjectCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("IngestProjectCommand");

    /** OR 项目名约定：一个外部项目对应一个 OR 项目。 */
    public static final String PROJECT_PREFIX = "SSO-";
    /** 项目元数据键：记录外部项目号，便于反查。 */
    public static final String SSO_PROJECT_ID_KEY = "ssoProjectId";

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        File uploaded = null;
        try {
            Map<String, String> params = new HashMap<>();
            if (ServletFileUpload.isMultipartContent(request)) {
                DiskFileItemFactory factory = new DiskFileItemFactory();
                ServletFileUpload upload = new ServletFileUpload(factory);
                upload.setHeaderEncoding("UTF-8");
                List<FileItem> items = upload.parseRequest(request);
                for (FileItem item : items) {
                    if (item.isFormField()) {
                        params.put(item.getFieldName(), item.getString("UTF-8"));
                    } else if (item.getSize() > 0) {
                        uploaded = File.createTempFile("sso-ingest-", ".bin");
                        try (InputStream in = item.getInputStream()) {
                            Files.copy(in, uploaded.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } else {
                Enumeration<String> names = request.getParameterNames();
                while (names.hasMoreElements()) {
                    String n = names.nextElement();
                    params.put(n, request.getParameter(n));
                }
            }

            String externalId = trim(params.get("projectId"));
            if (externalId == null) {
                respondJsonError(response, "projectId 不能为空");
                return;
            }
            File excel = uploaded != null ? uploaded : fileOf(params.get("excelPath"));
            if (excel == null || !excel.isFile()) {
                respondJsonError(response, "未提供可读的 Excel 文件（file 或 excelPath）");
                return;
            }

            String orName = PROJECT_PREFIX + externalId;
            boolean rebuild = !"reuse".equalsIgnoreCase(trim(params.get("mode")));
            long existingId = findProjectId(orName);
            boolean created = false;

            if (existingId > 0 && rebuild) {
                ProjectManager.singleton.deleteProject(existingId);
                logger.info("已删除同名 OR 项目以便重建: {} (id={})", orName, existingId);
                existingId = -1;
            }

            long orProjectId;
            int rowCount;
            if (existingId > 0) {
                orProjectId = existingId;
                ProjectMetadata md = ProjectManager.singleton.getProjectMetadata(existingId);
                rowCount = md == null ? 0 : md.getRowCount();
            } else {
                Project project = buildProject(orName, externalId, excel);
                orProjectId = project.id;
                rowCount = project.rows.size();
                created = true;
            }

            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");
            response.getWriter().write("{\"code\":\"ok\""
                    + ",\"ssoProjectId\":\"" + esc(externalId) + "\""
                    + ",\"projectId\":" + orProjectId
                    + ",\"projectName\":\"" + esc(orName) + "\""
                    + ",\"rowCount\":" + rowCount
                    + ",\"created\":" + created + "}");
        } catch (Exception e) {
            logger.error("建立 SSO 项目失败", e);
            respondException(response, e);
        } finally {
            if (uploaded != null && !uploaded.delete()) {
                uploaded.deleteOnExit();
            }
        }
    }

    /** POI 读表 → 建 OR 项目并落盘。 */
    private Project buildProject(String orName, String externalId, File excel) throws Exception {
        Project project = new Project();
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(orName);
        metadata.setCustomMetadata(SSO_PROJECT_ID_KEY, externalId);

        org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
        try (InputStream in = Files.newInputStream(excel.toPath());
                org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            org.apache.poi.ss.usermodel.Row header = sheet.getRow(sheet.getFirstRowNum());
            int width = header == null ? 0 : header.getLastCellNum();
            if (width <= 0) {
                throw new IllegalArgumentException("Excel 首行为空，无法确定列");
            }
            int[] cellIndex = new int[width];
            for (int i = 0; i < width; i++) {
                String name = fmt.formatCellValue(header.getCell(i)).trim();
                if (name.isEmpty()) {
                    name = "列" + (i + 1);
                }
                cellIndex[i] = project.columnModel.allocateNewCellIndex();
                project.columnModel.addColumn(-1, new Column(cellIndex[i], name), false);
            }
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                com.google.refine.model.Row orRow = new com.google.refine.model.Row(width);
                boolean any = false;
                for (int i = 0; i < width; i++) {
                    String v = fmt.formatCellValue(row.getCell(i)).trim();
                    if (!v.isEmpty()) {
                        any = true;
                    }
                    orRow.setCell(cellIndex[i], new com.google.refine.model.Cell(v, null));
                }
                if (any) {
                    project.rows.add(orRow);
                }
            }
        }

        project.update();
        ProjectManager.singleton.registerProject(project, metadata);
        ProjectManager.singleton.ensureProjectSaved(project.id);
        logger.info("已建立 OR 项目: {} (id={}, rows={})", orName, project.id, project.rows.size());
        return project;
    }

    /** 按项目名反查项目 id；不存在返回 -1（不加唯一性约束，取首个命中）。 */
    private static long findProjectId(String name) {
        for (Map.Entry<Long, ProjectMetadata> e : ProjectManager.singleton.getAllProjectMetadata().entrySet()) {
            if (name.equals(e.getValue().getName())) {
                return e.getKey();
            }
        }
        return -1L;
    }

    /** JSON 形式的错误响应（OR 的 Command 只有 respondException）。 */
    private static void respondJsonError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Type", "application/json");
        response.getWriter().write("{\"code\":\"error\",\"message\":\"" + esc(message) + "\"}");
    }

    private static File fileOf(String path) {
        String p = trim(path);
        return p == null ? null : new File(p);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
