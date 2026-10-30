/*
 * Data Quality Extension - SSO Project Ingest Command
 *
 * 供外部系统（SSO）接入用：按外部项目号幂等地建立/重建一个 OpenRefine 项目，
 * 使 SSO 侧上传的条目 Excel（或仅图像目录）成为可被 run-quality-check 检查的 OR 项目。
 */
package com.google.refine.extension.quality.commands;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.commands.Command;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.ModelException;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;

/**
 * 建立/重建 SSO 项目对应的 OR 项目。
 *
 * <p>请求（POST，multipart 或普通表单/查询串皆可）：
 * <ul>
 *   <li>{@code projectId}（必填）：外部项目号，OR 项目名固定为 {@code SSO-{projectId}}</li>
 *   <li>{@code projectName}（可选）：展示名，写入项目元数据</li>
 *   <li>{@code excelPath}（可选）：本机 Excel（xls/xlsx）绝对路径</li>
 *   <li>{@code file}（可选）：multipart 上传的 Excel；与 excelPath 二选一</li>
 *   <li>{@code imagePath}（可选）：本机图像根目录，按目录树生成「目录层级」表</li>
 *   <li>{@code mode}（可选）：{@code rebuild}（默认，已存在则删除重建）/
 *       {@code reuse}（已存在则复用）/ {@code appendSheet}（已存在则在原项目上追加/覆盖对应表）</li>
 * </ul>
 *
 * <p>{@code excelPath}/{@code file} 与 {@code imagePath} 至少提供一个：
 * <ul>
 *   <li>仅 Excel：单表「条目」（与既有行为一致）</li>
 *   <li>仅 imagePath：单表「目录层级」，用于「不传条目、只审查图像」的场景</li>
 *   <li>两者都有：两张表（条目 + 目录层级），活动表为「条目」</li>
 * </ul>
 * 目录层级表：除根目录外每个目录一行（空目录也出行，文件数量为 0）；
 * 根目录仅在其下有直属文件时出行。列为「一级目录…N级目录、文件数量、图像数量」，
 * N 按本次扫描的最深层级确定；后续扫到更深层级时在末尾追加列，既有行的单元格索引不变。
 * 「图像数量」= 图像文件个数 + 各 PDF 中可提取的图像数（按页统计 XObject 图像）。
 *
 * <p>响应：{@code {"code":"ok","ssoProjectId":..,"projectId":..,"projectName":..,
 * "rowCount":..,"created":..,"sheetKind":..,"sheetCount":..}}
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

    /** 多表项目中的「条目」表。 */
    public static final String ENTRY_SHEET_ID = "sso#条目";
    public static final String ENTRY_SHEET_NAME = "条目";
    /** 多表项目中的「目录层级」表。 */
    public static final String DIR_SHEET_ID = "sso#目录";
    public static final String DIR_SHEET_NAME = "目录层级";

    /** 目录层级表的统计列名。 */
    public static final String COL_FILE_COUNT = "文件数量";
    public static final String COL_IMAGE_COUNT = "图像数量";

    /** 项目元数据键：记录当前项目里有哪些表（entry / dir / both）；检查入口据此判定是否跳过条目类检查。 */
    public static final String META_SHEET_KIND = "ssoSheetKind";

    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif", ".webp"};

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
            File imageRoot = directoryOf(params.get("imagePath"));
            if (excel == null && imageRoot == null) {
                respondJsonError(response, "未提供可读的 Excel 文件（file 或 excelPath），也未提供图像根目录（imagePath）");
                return;
            }

            String orName = PROJECT_PREFIX + externalId;
            String modeText = trim(params.get("mode"));
            boolean reuse = "reuse".equalsIgnoreCase(modeText);
            boolean append = "appendsheet".equalsIgnoreCase(modeText);
            boolean rebuild = !reuse && !append;
            long existingId = findProjectId(orName);
            boolean created = false;

            if (existingId > 0 && rebuild) {
                ProjectManager.singleton.deleteProject(existingId);
                logger.info("已删除同名 OR 项目以便重建: {} (id={})", orName, existingId);
                existingId = -1;
            }

            long orProjectId;
            int rowCount;
            int sheetCount;
            String sheetKind;
            if (existingId > 0) {
                orProjectId = existingId;
                Project project = ProjectManager.singleton.getProject(existingId);
                if (project == null) {
                    throw new IOException("OR 项目已存在但无法加载: " + existingId);
                }
                if (append) {
                    appendSheets(project, excel, imageRoot);
                    ProjectManager.singleton.ensureProjectSaved(existingId);
                }
                rowCount = project.rows == null ? 0 : project.rows.size();
                sheetCount = Math.max(1, project.sheetDataMap.size());
                sheetKind = sheetKindOf(project);
            } else {
                Project project = buildProject(orName, externalId, excel, imageRoot);
                orProjectId = project.id;
                rowCount = project.rows == null ? 0 : project.rows.size();
                sheetCount = Math.max(1, project.sheetDataMap.size());
                sheetKind = sheetKindOf(project);
                created = true;
            }

            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");
            response.getWriter().write("{\"code\":\"ok\""
                    + ",\"ssoProjectId\":\"" + esc(externalId) + "\""
                    + ",\"projectId\":" + orProjectId
                    + ",\"projectName\":\"" + esc(orName) + "\""
                    + ",\"rowCount\":" + rowCount
                    + ",\"created\":" + created
                    + ",\"sheetKind\":\"" + esc(sheetKind) + "\""
                    + ",\"sheetCount\":" + sheetCount + "}");
        } catch (Exception e) {
            logger.error("建立 SSO 项目失败", e);
            respondException(response, e);
        } finally {
            if (uploaded != null && !uploaded.delete()) {
                uploaded.deleteOnExit();
            }
        }
    }

    /** 新建项目：按有无 Excel / 图像根目录决定单表或双表。 */
    private Project buildProject(String orName, String externalId, File excel, File imageRoot) throws Exception {
        Project project = new Project();
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(orName);
        metadata.setCustomMetadata(SSO_PROJECT_ID_KEY, externalId);

        DirScan scan = imageRoot != null ? scanDirectories(imageRoot) : null;
        if (excel != null && scan != null) {
            SheetData entry = new SheetData(ENTRY_SHEET_ID, ENTRY_SHEET_NAME, "");
            applyExcel(entry.columnModel, entry.rows, excel);
            SheetData dir = new SheetData(DIR_SHEET_ID, DIR_SHEET_NAME, "");
            applyDirectory(dir.columnModel, dir.rows, scan);
            project.addSheetData(entry);
            project.addSheetData(dir);
            project.setActiveSheet(ENTRY_SHEET_ID);
            entry.columnModel.update();
            dir.columnModel.update();
            metadata.setCustomMetadata(META_SHEET_KIND, "both");
        } else if (excel != null) {
            applyExcel(project.columnModel, project.rows, excel);
            metadata.setCustomMetadata(META_SHEET_KIND, "entry");
        } else {
            applyDirectory(project.columnModel, project.rows, scan);
            metadata.setCustomMetadata(META_SHEET_KIND, "dir");
        }

        project.update();
        ProjectManager.singleton.registerProject(project, metadata);
        ProjectManager.singleton.ensureProjectSaved(project.id);
        logger.info("已建立 OR 项目: {} (id={}, rows={}, sheets={})",
                orName, project.id, project.rows == null ? 0 : project.rows.size(), project.sheetDataMap.size());
        return project;
    }

    /**
     * 在原项目上追加/覆盖表：已有单表先提升为 SheetData，再按本次提供的 Excel / 图像目录追加。
     * 上传条目后活动表切到「条目」，目录表保留但不再参与检查（仅作参考）。
     */
    private void appendSheets(Project project, File excel, File imageRoot) throws Exception {
        ProjectMetadata metadata = project.getMetadata();
        String kind = metadata == null ? null : String.valueOf(metadata.getCustomMetadata(META_SHEET_KIND));
        if (!project.isMultiSheetProject) {
            boolean existingIsDir = "dir".equals(kind);
            SheetData existing = new SheetData(
                    existingIsDir ? DIR_SHEET_ID : ENTRY_SHEET_ID,
                    existingIsDir ? DIR_SHEET_NAME : ENTRY_SHEET_NAME, "");
            existing.rows = project.rows;
            existing.columnModel = project.columnModel;
            project.addSheetData(existing);
        }

        if (excel != null) {
            SheetData entry = project.getSheetData(ENTRY_SHEET_ID);
            if (entry == null) {
                entry = new SheetData(ENTRY_SHEET_ID, ENTRY_SHEET_NAME, "");
                project.addSheetData(entry);
            } else {
                entry.rows.clear();
                entry.columnModel = new ColumnModel();
            }
            applyExcel(entry.columnModel, entry.rows, excel);
            entry.columnModel.update();
            project.setActiveSheet(ENTRY_SHEET_ID);
        }

        if (imageRoot != null) {
            DirScan scan = scanDirectories(imageRoot);
            SheetData dir = project.getSheetData(DIR_SHEET_ID);
            if (dir == null) {
                dir = new SheetData(DIR_SHEET_ID, DIR_SHEET_NAME, "");
                project.addSheetData(dir);
            }
            applyDirectory(dir.columnModel, dir.rows, scan);
            dir.columnModel.update();
            // 仅在项目里还没有条目表时才让目录表成为活动表
            if (project.getSheetData(ENTRY_SHEET_ID) == null) {
                project.setActiveSheet(DIR_SHEET_ID);
            }
        }

        String newKind;
        boolean hasEntry = project.getSheetData(ENTRY_SHEET_ID) != null || excel != null;
        boolean hasDir = project.getSheetData(DIR_SHEET_ID) != null || imageRoot != null;
        if (hasEntry && hasDir) {
            newKind = "both";
        } else if (hasEntry) {
            newKind = "entry";
        } else {
            newKind = "dir";
        }
        if (metadata != null) {
            metadata.setCustomMetadata(META_SHEET_KIND, newKind);
        }
        project.update();
    }

    /** Excel → 条目表（POI 读首表；首行为表头，空行跳过）。 */
    private static void applyExcel(ColumnModel columnModel, List<Row> rows, File excel) throws Exception {
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
                cellIndex[i] = columnModel.allocateNewCellIndex();
                columnModel.addColumn(-1, new Column(cellIndex[i], name), false);
            }
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Row orRow = new Row(width);
                boolean any = false;
                for (int i = 0; i < width; i++) {
                    String v = fmt.formatCellValue(row.getCell(i)).trim();
                    if (!v.isEmpty()) {
                        any = true;
                    }
                    orRow.setCell(cellIndex[i], new Cell(v, null));
                }
                if (any) {
                    rows.add(orRow);
                }
            }
        }
    }

    /**
     * 目录树 → 目录层级表。
     *
     * <p>行规则：除根目录外每个目录一行（空目录也出行，数量为 0）；根目录仅在有直属文件时出行。
     * 数量列只统计直属文件（不含子目录）。
     */
    private static void applyDirectory(ColumnModel columnModel, List<Row> rows, DirScan scan) throws ModelException {
        if (scan == null) {
            return;
        }
        // 1) 补层级列：按本次最深层级在末尾追加，既有行的单元格索引不变
        int existingLevels = 0;
        while (columnModel.getColumnByName(levelColumn(existingLevels + 1)) != null) {
            existingLevels++;
        }
        for (int i = existingLevels + 1; i <= scan.maxDepth; i++) {
            int cellIndex = columnModel.allocateNewCellIndex();
            columnModel.addColumn(-1, new Column(cellIndex, levelColumn(i)), false);
        }
        // 2) 统计列
        int fileCountIndex = ensureColumn(columnModel, COL_FILE_COUNT);
        int imageCountIndex = ensureColumn(columnModel, COL_IMAGE_COUNT);
        // 3) 已有行按「目录键」建索引（层级列值用 / 连接）
        Map<String, Row> byKey = new HashMap<>();
        for (Row row : rows) {
            byKey.put(dirKeyOf(row, columnModel), row);
        }
        int width = Math.max(columnModel.getMaxCellIndex() + 1, scan.maxDepth + 2);
        for (DirEntry entry : scan.entries) {
            String key = String.join("/", entry.levels);
            Row row = byKey.get(key);
            if (row == null) {
                row = new Row(width);
                rows.add(row);
                byKey.put(key, row);
            }
            for (int i = 0; i < entry.levels.length; i++) {
                Column column = columnModel.getColumnByName(levelColumn(i + 1));
                if (column != null) {
                    row.setCell(column.getCellIndex(), new Cell(entry.levels[i], null));
                }
            }
            row.setCell(fileCountIndex, new Cell(entry.fileCount, null));
            row.setCell(imageCountIndex, new Cell(entry.imageCount, null));
        }
        columnModel.update();
    }

    /** 目录树扫描结果。 */
    private static final class DirScan {
        int maxDepth;
        final List<DirEntry> entries = new ArrayList<>();
    }

    /** 单个目录：层级各段、直属文件数、直属可审查图像数。 */
    private static final class DirEntry {
        String[] levels = new String[0];
        int fileCount;
        int imageCount;
    }

    private static DirScan scanDirectories(File root) throws IOException {
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("图像根目录不存在或不是目录: " + root);
        }
        Path rootPath = root.toPath();
        DirScan scan = new DirScan();
        try (Stream<Path> walk = Files.walk(rootPath)) {
            List<Path> dirs = walk.filter(Files::isDirectory).collect(Collectors.toList());
            for (Path dir : dirs) {
                Path rel = rootPath.relativize(dir);
                int depth = rel.getNameCount();
                List<Path> files;
                try (Stream<Path> list = Files.list(dir)) {
                    files = list.filter(Files::isRegularFile).collect(Collectors.toList());
                }
                int imageCount = 0;
                for (Path file : files) {
                    imageCount += imageCountOf(file);
                }
                if (depth == 0 && files.isEmpty()) {
                    continue;
                }
                DirEntry entry = new DirEntry();
                if (depth > 0) {
                    String[] levels = new String[depth];
                    for (int i = 0; i < depth; i++) {
                        levels[i] = rel.getName(i).toString();
                    }
                    entry.levels = levels;
                }
                entry.fileCount = files.size();
                entry.imageCount = imageCount;
                scan.entries.add(entry);
                scan.maxDepth = Math.max(scan.maxDepth, depth);
            }
        }
        scan.entries.sort(Comparator.comparing(e -> String.join("/", e.levels)));
        return scan;
    }

    /** 该文件的「可审查图像数」：图像文件计 1，PDF 按页统计可提取图像数。 */
    private static int imageCountOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return 1;
            }
        }
        if (name.endsWith(".pdf")) {
            return countPdfImages(file);
        }
        return 0;
    }

    private static int countPdfImages(Path file) {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int count = 0;
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            logger.warn("统计 PDF 图像数失败: {}", file, e);
            return 0;
        }
    }

    /** 第 n 级目录的列名（一级目录、二级目录…）。 */
    private static String levelColumn(int n) {
        return chineseNumber(n) + "级目录";
    }

    private static String chineseNumber(int n) {
        if (n <= 0) {
            return String.valueOf(n);
        }
        if (n < 10) {
            return DIGITS[n];
        }
        if (n < 20) {
            return "十" + (n % 10 == 0 ? "" : DIGITS[n % 10]);
        }
        if (n < 100) {
            return DIGITS[n / 10] + "十" + (n % 10 == 0 ? "" : DIGITS[n % 10]);
        }
        return String.valueOf(n);
    }

    private static int ensureColumn(ColumnModel columnModel, String name) throws ModelException {
        Column column = columnModel.getColumnByName(name);
        if (column != null) {
            return column.getCellIndex();
        }
        int cellIndex = columnModel.allocateNewCellIndex();
        columnModel.addColumn(-1, new Column(cellIndex, name), false);
        return cellIndex;
    }

    /** 行的目录键：各级目录列的值用 / 连接（根目录行为空串）。 */
    private static String dirKeyOf(Row row, ColumnModel columnModel) {
        List<String> values = new ArrayList<>();
        int level = 1;
        Column column;
        while ((column = columnModel.getColumnByName(levelColumn(level))) != null) {
            Cell cell = row.getCell(column.getCellIndex());
            String value = cell == null || cell.value == null ? "" : String.valueOf(cell.value).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
            level++;
        }
        return String.join("/", values);
    }

    /** 当前项目的表构成（entry / dir / both）；无元数据的老项目按条目表处理。 */
    private static String sheetKindOf(Project project) {
        ProjectMetadata metadata = project.getMetadata();
        Object kind = metadata == null ? null : metadata.getCustomMetadata(META_SHEET_KIND);
        return kind == null ? "entry" : String.valueOf(kind);
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

    /** 图像根目录：要求存在且为目录，否则视为未提供。 */
    private static File directoryOf(String path) {
        File file = fileOf(path);
        return file != null && file.isDirectory() ? file : null;
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
