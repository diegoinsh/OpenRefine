/*
 * Data Quality Extension - Export Quality Report Command
 */
package com.google.refine.extension.quality.commands;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PiePlot;
import org.jfree.data.general.DefaultPieDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectMetadata;
import com.google.refine.commands.Command;
import com.google.refine.extension.quality.model.ContentComparisonRule;
import com.google.refine.extension.quality.model.FormatRule;
import com.google.refine.extension.quality.model.ImageCheckCategory;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Column;
import com.google.refine.model.Project;

/**
 * Command to export quality check results to Excel or PDF.
 */
public class ExportQualityReportCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(ExportQualityReportCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            String format = request.getParameter("format");
            String resultsJson = request.getParameter("results");

            if (resultsJson == null || resultsJson.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No results data provided");
                return;
            }

            JsonNode results = mapper.readTree(resultsJson);

            if ("excel".equalsIgnoreCase(format)) {
                exportExcel(response, results, project);
            } else if ("pdf".equalsIgnoreCase(format)) {
                exportPdf(response, results, project);
            } else {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid format: " + format);
            }

        } catch (Exception e) {
            logger.error("Error exporting quality report", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void exportExcel(HttpServletResponse response, JsonNode results, Project project) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=quality_report.xlsx");

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream out = response.getOutputStream()) {

            // Create summary sheet
            Sheet summarySheet = workbook.createSheet("检查摘要");
            createSummarySheet(workbook, summarySheet, results);

            // Create errors sheet
            Sheet errorsSheet = workbook.createSheet("错误详情");
            createErrorsSheet(workbook, errorsSheet, results);

            // Create data sheet with error comments
            Sheet dataSheet = workbook.createSheet("数据表格");
            createDataSheet(workbook, dataSheet, results, project);

            workbook.write(out);
        }
    }

    private void createSummarySheet(Workbook workbook, Sheet sheet, JsonNode results) {
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle percentStyle = workbook.createCellStyle();
        percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("数据质量检查报告");
        titleCell.setCellStyle(headerStyle);

        rowNum++; // Empty row

        // Summary stats section header
        Row summaryHeader = sheet.createRow(rowNum++);
        Cell summaryHeaderCell = summaryHeader.createCell(0);
        summaryHeaderCell.setCellValue("检查摘要");
        summaryHeaderCell.setCellStyle(headerStyle);

        // Summary stats
        JsonNode summary = results.get("summary");
        int totalRows = 0, totalErrors = 0, formatErrors = 0, resourceErrors = 0, contentErrors = 0, imageErrors = 0;

        if (summary != null) {
            totalRows = summary.path("totalRows").asInt(0);
            totalErrors = summary.path("totalErrors").asInt(0);
            formatErrors = summary.path("formatErrors").asInt(0);
            resourceErrors = summary.path("resourceErrors").asInt(0);
            contentErrors = summary.path("contentErrors").asInt(0);
            // Support both imageQualityErrors (from RunQualityCheckCommand) and imageErrors (legacy)
            imageErrors = summary.path("imageQualityErrors").asInt(0);
            if (imageErrors == 0) {
                imageErrors = summary.path("imageErrors").asInt(0);
            }

            createStatRow(sheet, rowNum++, "总行数", totalRows);
            createStatRow(sheet, rowNum++, "总错误数", totalErrors);
            createStatRow(sheet, rowNum++, "格式错误", formatErrors);
            createStatRow(sheet, rowNum++, "资源错误", resourceErrors);
            createStatRow(sheet, rowNum++, "内容错误", contentErrors);
            createStatRow(sheet, rowNum++, "图像质量错误", imageErrors);
        }

        rowNum++; // Empty row

        // Error distribution section
        if (totalErrors > 0) {
            Row distHeader = sheet.createRow(rowNum++);
            Cell distHeaderCell = distHeader.createCell(0);
            distHeaderCell.setCellValue("错误分类统计");
            distHeaderCell.setCellStyle(headerStyle);

            // Table header
            Row tableHeader = sheet.createRow(rowNum++);
            String[] headers = {"错误类型", "数量", "占比"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = tableHeader.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            createDistributionRow(sheet, rowNum++, "数据格式检查", formatErrors, totalErrors, percentStyle);
            createDistributionRow(sheet, rowNum++, "文件资源关联检查", resourceErrors, totalErrors, percentStyle);
            createDistributionRow(sheet, rowNum++, "内容比对检查", contentErrors, totalErrors, percentStyle);
            createDistributionRow(sheet, rowNum++, "图像质量检查", imageErrors, totalErrors, percentStyle);
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
    }

    private void createDistributionRow(Sheet sheet, int rowNum, String label, int count, int total, CellStyle percentStyle) {
        Row row = sheet.createRow(rowNum);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(count);
        Cell percentCell = row.createCell(2);
        if (total > 0) {
            percentCell.setCellValue((double) count / total);
            percentCell.setCellStyle(percentStyle);
        } else {
            percentCell.setCellValue("0.0%");
        }
    }

    private void createErrorsSheet(Workbook workbook, Sheet sheet, JsonNode results) {
        CellStyle headerStyle = createHeaderStyle(workbook);
        int rowNum = 0;

        // Header row
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"行号", "列名", "值", "错误类型", "错误信息", "分类"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Error rows
        JsonNode errors = results.get("errors");
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                Row row = sheet.createRow(rowNum++);
                String errorType = error.path("errorType").asText("");
                String column = error.path("column").asText("");
                String message = error.path("message").asText("");
                String value = error.path("value").asText("");
                String hiddenFileName = error.path("hiddenFileName").asText("");
                String category = error.path("category").asText("");

                row.createCell(0).setCellValue(error.path("rowIndex").asInt(0) + 1);
                row.createCell(1).setCellValue(getColumnDisplay(column, errorType, value, hiddenFileName, category));
                String valueDisplay = value.isEmpty() ? "(空)" : value;
                if ("empty_folder".equals(errorType) || "file_sequential".equals(errorType) ||
                    "folder_sequential".equals(errorType) || "file_sequence".equals(errorType) ||
                    "folder_sequence".equals(errorType)) {
                    valueDisplay = "(空)";
                }
                row.createCell(2).setCellValue(valueDisplay);
                row.createCell(3).setCellValue(getErrorTypeLabel(errorType));
                row.createCell(4).setCellValue(translateErrorMessage(message, errorType));
                row.createCell(5).setCellValue(getCategoryLabel(category));
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createStatRow(Sheet sheet, int rowNum, String label, int value) {
        Row row = sheet.createRow(rowNum);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
    }

    /**
     * Create the data sheet with all project data and error comments on cells.
     */
    private void createDataSheet(Workbook workbook, Sheet sheet, JsonNode results, Project project) {
        CellStyle headerStyle = createHeaderStyle(workbook);
        CreationHelper factory = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();

        // Build error map: key = "rowIndex_columnName", value = list of error messages
        Map<String, List<String>> errorMap = new HashMap<>();
        JsonNode errors = results.get("errors");
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                int rowIndex = error.path("rowIndex").asInt(-1);
                String column = error.path("column").asText("");
                if (rowIndex >= 0 && !column.isEmpty()) {
                    String key = rowIndex + "_" + column;
                    errorMap.computeIfAbsent(key, k -> new ArrayList<>());
                    String errorType = error.path("errorType").asText("");
                    String message = error.path("message").asText("");
                    String errorText = getErrorTypeLabel(errorType) + ": " + translateErrorMessage(message, errorType);
                    errorMap.get(key).add(errorText);
                }
            }
        }

        // Get columns
        java.util.List<Column> columns = project.columnModel.columns;
        int colCount = columns.size();

        // Create header row
        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < colCount; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(columns.get(c).getName());
            cell.setCellStyle(headerStyle);
        }

        // Create data rows
        int rowCount = project.rows.size();
        for (int r = 0; r < rowCount; r++) {
            com.google.refine.model.Row dataRow = project.rows.get(r);
            Row excelRow = sheet.createRow(r + 1);

            for (int c = 0; c < colCount; c++) {
                Column column = columns.get(c);
                int cellIndex = column.getCellIndex();
                com.google.refine.model.Cell dataCell = dataRow.getCell(cellIndex);

                Cell excelCell = excelRow.createCell(c);

                // Set cell value
                if (dataCell != null && dataCell.value != null) {
                    Object value = dataCell.value;
                    if (value instanceof Number) {
                        excelCell.setCellValue(((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        excelCell.setCellValue((Boolean) value);
                    } else {
                        excelCell.setCellValue(value.toString());
                    }
                }

                // Add comment if there are errors for this cell
                String errorKey = r + "_" + column.getName();
                if (errorMap.containsKey(errorKey)) {
                    List<String> cellErrors = errorMap.get(errorKey);
                    String commentText = String.join("\n", cellErrors);

                    // Create comment
                    ClientAnchor anchor = factory.createClientAnchor();
                    anchor.setCol1(c);
                    anchor.setRow1(r + 1);
                    anchor.setCol2(c + 3);
                    anchor.setRow2(r + 4);

                    Comment comment = drawing.createCellComment(anchor);
                    RichTextString str = factory.createRichTextString(commentText);
                    comment.setString(str);
                    comment.setAuthor("数据质量检查");
                    excelCell.setCellComment(comment);
                }
            }
        }

        // Auto-size columns (limit to first 20 columns to avoid performance issues)
        int autoSizeLimit = Math.min(colCount, 20);
        for (int c = 0; c < autoSizeLimit; c++) {
            sheet.autoSizeColumn(c);
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private String getCategoryLabel(String category) {
        switch (category) {
            case "format": return "数据格式";
            case "resource": return "文件资源";
            case "content": return "内容比对";
            case "image_quality": return "图像质量";
            default: return category;
        }
    }

    /**
     * Translate error type to Chinese.
     */
    private String getErrorTypeLabel(String errorType) {
        if (errorType == null || errorType.isEmpty()) return "(空)";
        switch (errorType) {
            case "repeat_image": return "重复图片检测";
            case "edgeRemove": return "黑边检测";
            case "damage": return "破损文件检测";
            case "non_empty": return "非空检查";
            case "unique": return "唯一性检查";
            case "regex": return "格式检查";
            case "date_format": return "日期格式检查";
            case "value_list": return "值列表检查";
            case "file_count": return "文件数量检查";
            case "file_sequential": return "文件序号连续";
            case "file_sequence": return "文件序号连续";
            case "folder_existence": return "文件夹存在检查";
            case "folder_sequential": return "文件夹序号连续";
            case "folder_sequence": return "文件夹序号连续";
            case "data_existence": return "数据条目存在检查";
            case "content_match": return "内容匹配检查";
            case "content_mismatch": return "内容不匹配";
            case "content_warning": return "内容相似度偏低";
            case "houseAngle": return "图像角度检测";
            case "blank": return "空白页检测";
            case "bias": return "图像倾斜检测";
            case "stain": return "污点检测";
            case "hole": return "装订孔检测";
            case "dpi": return "分辨率检测";
            case "kb": return "文件大小检测";
            case "quality": return "JPEG质量检测";
            case "bit_depth": return "位深度检测";
            case "edge": return "黑边检测";
            case "illegal_file": return "非法归档文件检测";
            case "empty_folder": return "空文件夹检查";
            default: return errorType;
        }
    }

    /**
     * Translate error message to Chinese.
     */
    private String translateErrorMessage(String message, String errorType) {
        if (message == null || message.isEmpty()) return "(空)";

        // Value not in allowed list
        if (message.equals("Value not in allowed list")) {
            return "不在允许值列表中";
        }
        // Value is empty
        if (message.equals("Value is empty")) {
            return "值为空";
        }
        // Duplicate value
        if (message.equals("Duplicate value")) {
            return "重复值";
        }
        // File count mismatch: expected X, actual Y
        if (message.startsWith("File count mismatch:")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("expected (\\d+), actual (\\d+)");
            java.util.regex.Matcher m = p.matcher(message);
            if (m.find()) {
                return "文件数量不匹配：期望 " + m.group(1) + " 个，实际 " + m.group(2) + " 个";
            }
        }
        // Sequence gap: missing number(s) between X and Y
        if (message.startsWith("Sequence gap:")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("between (\\d+) and (\\d+)");
            java.util.regex.Matcher m = p.matcher(message);
            if (m.find()) {
                return "序号不连续：" + m.group(1) + " 和 " + m.group(2) + " 之间有缺失";
            }
        }
        // Folder does not exist
        if (message.startsWith("Folder does not exist:")) {
            String path = message.substring("Folder does not exist:".length()).trim();
            return "文件夹不存在：" + path;
        }
        // Folder has no corresponding data entry
        if (message.startsWith("Folder has no corresponding data entry:")) {
            String folderName = message.substring("Folder has no corresponding data entry:".length()).trim();
            return "数据条目不存在：" + folderName;
        }
        // Folder name does not match format
        if (message.startsWith("Folder name does not match format:")) {
            String format = message.substring("Folder name does not match format:".length()).trim();
            return "文件夹命名格式错误：" + format;
        }
        // Folder name is not sequential
        if (message.startsWith("Folder name is not sequential:")) {
            String folderName = message.substring("Folder name is not sequential:".length()).trim();
            return "文件夹序号不连续：" + folderName;
        }
        // Does not match pattern
        if (message.startsWith("Does not match pattern:")) {
            String pattern = message.substring("Does not match pattern:".length()).trim();
            return "不匹配格式：" + pattern;
        }
        // Value does not match pattern
        if (message.startsWith("Value does not match pattern:")) {
            String pattern = message.substring("Value does not match pattern:".length()).trim();
            return "不匹配格式：" + pattern;
        }
        // Value does not match date format
        if (message.startsWith("Value does not match date format:")) {
            String format = message.substring("Value does not match date format:".length()).trim();
            return "日期格式错误：" + format;
        }

        // Image quality errors - blank page
        if (message.startsWith("Blank page detected:")) {
            String filename = message.substring("Blank page detected:".length()).trim();
            return "检测到空白页: " + filename;
        }

        // Image quality errors - skew
        if (message.startsWith("Skew detected:")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("angle: ([-]?\\d+)");
            java.util.regex.Matcher m = p.matcher(message);
            if (m.find()) {
                return "检测到图像倾斜, 角度: " + m.group(1) + "°";
            }
            return "检测到图像倾斜";
        }

        // Image quality errors - stain
        if (message.startsWith("Stain detected:")) {
            String filename = message.substring("Stain detected:".length()).trim();
            return "检测到污点: " + filename;
        }

        // Image quality errors - binding hole
        if (message.startsWith("Binding hole detected:")) {
            String filename = message.substring("Binding hole detected:".length()).trim();
            return "检测到装订孔: " + filename;
        }

        // Image quality errors - DPI
        if (message.startsWith("DPI too low:")) {
            String dpi = message.substring("DPI too low:".length()).trim();
            return "分辨率过低: " + dpi;
        }

        // Image quality errors - file size
        if (message.startsWith("File size too large:")) {
            String size = message.substring("File size too large:".length()).trim();
            return "文件过大: " + size;
        }

        // Image quality errors - edge
        if (message.startsWith("Edge detected:")) {
            String desc = message.substring("Edge detected:".length()).trim();
            return "检测到黑边: " + desc;
        }

        // Empty folder
        if (message.startsWith("Empty folder:")) {
            String path = message.substring("Empty folder:".length()).trim();
            return "空文件夹：" + path;
        }

        return message;
    }

    /**
     * Get column display value - return resource type based on file extension for resource errors.
     */
    private String getColumnDisplay(String column, String errorType, String value, String hiddenFileName, String category) {
        // For image quality errors, show full file name
        if ("image_quality".equals(category) && hiddenFileName != null && !hiddenFileName.isEmpty()) {
            return hiddenFileName;
        }
        // For empty folder error, show full path
        if ("empty_folder".equals(errorType) && value != null && !value.isEmpty()) {
            return value;
        }
        // For file_sequential and folder_sequential errors, show full path
        if ("file_sequential".equals(errorType) || "folder_sequential".equals(errorType) ||
            "file_sequence".equals(errorType) || "folder_sequence".equals(errorType)) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
            return column;
        }
        if (column == null || column.isEmpty()) {
            // For resource check errors, try to extract resource type from value (file path)
            if (value != null && !value.isEmpty()) {
                String ext = getFileExtension(value);
                String resourceType = getResourceType(ext);
                if (resourceType != null) {
                    return resourceType;
                }
            }
            return "(空)";
        }
        // For other resource errors, show resource type based on file extension
        if ("resource".equals(getCategoryFromErrorType(errorType))) {
            if (value != null && !value.isEmpty()) {
                String ext = getFileExtension(value);
                String resourceType = getResourceType(ext);
                if (resourceType != null) {
                    return resourceType;
                }
            }
        }
        return column;
    }

    /**
     * Get file extension from path.
     */
    private String getFileExtension(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < path.length() - 1) {
            return path.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * Get resource type display name based on file extension.
     */
    private String getResourceType(String ext) {
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
            case "tif":
            case "tiff":
                return "图像";
            case "pdf":
                return "PDF";
            case "ofd":
                return "OFD";
            case "doc":
            case "docx":
                return "Word";
            case "xls":
            case "xlsx":
                return "Excel";
            case "ppt":
            case "pptx":
                return "PowerPoint";
            default:
                return ext.toUpperCase();
        }
    }

    /**
     * Get category from error type.
     */
    private String getCategoryFromErrorType(String errorType) {
        if (errorType == null) {
            return "";
        }
        if (errorType.contains("format") || errorType.contains("Format")) {
            return "format";
        }
        if (errorType.contains("resource") || errorType.contains("Resource") ||
            errorType.contains("folder") || errorType.contains("file")) {
            return "resource";
        }
        if (errorType.contains("content") || errorType.contains("Content")) {
            return "content";
        }
        if (errorType.contains("image") || errorType.contains("Image") ||
            errorType.equals("blank") || errorType.equals("bias") || 
            errorType.equals("stain") || errorType.equals("hole") ||
            errorType.equals("dpi") || errorType.equals("kb") || 
            errorType.equals("quality") || errorType.equals("bit_depth") ||
            errorType.equals("edge")) {
            return "image_quality";
        }
        return "";
    }

    private void exportPdf(HttpServletResponse response, JsonNode results, Project project) throws IOException {
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=quality_report.pdf");

        try (OutputStream out = response.getOutputStream()) {
            Document document = new Document(PageSize.A4);
            PdfWriter.getInstance(document, out);
            document.open();

            BaseFont bfChinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(bfChinese, 18, com.itextpdf.text.Font.BOLD);
            com.itextpdf.text.Font sectHeaderFont = new com.itextpdf.text.Font(bfChinese, 14, com.itextpdf.text.Font.BOLD);
            com.itextpdf.text.Font headerFont = new com.itextpdf.text.Font(bfChinese, 12, com.itextpdf.text.Font.BOLD);
            com.itextpdf.text.Font normalFont = new com.itextpdf.text.Font(bfChinese, 10, com.itextpdf.text.Font.NORMAL);
            com.itextpdf.text.Font smallFont = new com.itextpdf.text.Font(bfChinese, 9, com.itextpdf.text.Font.NORMAL);

            Paragraph title = new Paragraph("数据质量检查报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            JsonNode summary = results.get("summary");
            JsonNode imageQualityResult = results.get("imageQualityResult");
            JsonNode fileStatistics = imageQualityResult != null ? imageQualityResult.get("fileStatistics") : null;
            JsonNode errors = results.get("errors");

            int totalRows = summary != null ? summary.path("totalRows").asInt(0) : 0;
            int totalErrors = summary != null ? summary.path("totalErrors").asInt(0) : 0;
            int formatErrors = summary != null ? summary.path("formatErrors").asInt(0) : 0;
            int resourceErrors = summary != null ? summary.path("resourceErrors").asInt(0) : 0;
            int contentErrors = summary != null ? summary.path("contentErrors").asInt(0) : 0;
            int imageErrors = summary.path("imageQualityErrors").asInt(0);
            if (imageErrors == 0) {
                imageErrors = summary.path("imageErrors").asInt(0);
            }

            int totalFolders = fileStatistics != null ? fileStatistics.path("totalFolders").asInt(0) : 0;
            int totalFiles = fileStatistics != null ? fileStatistics.path("totalFiles").asInt(0) : 0;

            QualityRulesConfig rulesConfig = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");
            if (rulesConfig == null) {
                rulesConfig = new QualityRulesConfig();
            }

            String checkTime = summary != null && summary.has("checkTime") ? summary.path("checkTime").asText("") : "";
        String checkTimeRange = "";
        long startTime = results.has("startTime") ? results.path("startTime").asLong(0) : 0;
        long endTime = results.has("endTime") ? results.path("endTime").asLong(0) : 0;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String startStr = startTime > 0 ? sdf.format(new java.util.Date(startTime)) : "";
        String endStr = endTime > 0 ? sdf.format(new java.util.Date(endTime)) : "";
        if (!startStr.isEmpty() && !endStr.isEmpty()) {
            checkTimeRange = startStr + " → " + endStr;
        } else if (!startStr.isEmpty()) {
            checkTimeRange = startStr;
        } else if (!endStr.isEmpty()) {
            checkTimeRange = endStr;
        }
            if (checkTimeRange.isEmpty()) {
                if (checkTime.isEmpty()) {
                    checkTimeRange = sdf.format(new java.util.Date());
                } else {
                    checkTimeRange = checkTime;
                }
            }
            String reportTime = sdf.format(new java.util.Date());

            String checkRange = getCheckRange(project);

            CheckStatistics checkStats = getCheckStatistics(project);

            addSectionTitle(document, "一、基本信息", sectHeaderFont);
            addBasicInfoTable(document, headerFont, normalFont, project, checkTimeRange, reportTime, checkRange);
            document.add(new Paragraph(" "));

            addSectionTitle(document, "二、检查概况", sectHeaderFont);
            addOverviewSection(document, headerFont, normalFont, totalRows, totalFolders, totalFiles, totalErrors, checkStats, results, formatErrors, resourceErrors, contentErrors, imageErrors);
        document.add(new Paragraph(" "));

            addSectionTitle(document, "三、检测结果汇总", sectHeaderFont);
            addSummarySection(document, headerFont, normalFont, totalErrors, formatErrors, resourceErrors, contentErrors, imageErrors, totalRows, totalFolders, totalFiles, results);
            document.add(new Paragraph(" "));

            addSectionTitle(document, "四、检测结果统计详情", sectHeaderFont);
            addFourPropertiesSection(document, headerFont, normalFont, smallFont, results, summary, fileStatistics, totalRows, totalFolders, totalFiles, rulesConfig);
            document.add(new Paragraph(" "));

            addSectionTitle(document, "五、问题分析与建议", sectHeaderFont);
            addAnalysisSection(document, headerFont, normalFont, totalErrors, imageErrors, resourceErrors, formatErrors, contentErrors);
            document.add(new Paragraph(" "));

            addSectionTitle(document, "六、结论", sectHeaderFont);
            addConclusionSection(document, headerFont, normalFont, totalErrors, totalRows);
            document.add(new Paragraph(" "));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(" "));
            addSectionTitle(document, "附录信息", sectHeaderFont);
            addReportInfoSection(document, headerFont, normalFont);

            document.close();
        } catch (DocumentException e) {
            logger.error("Error creating PDF", e);
            throw new IOException("Failed to create PDF: " + e.getMessage());
        }
    }

    private void addSectionTitle(Document document, String title, com.itextpdf.text.Font font) throws DocumentException {
        Paragraph p = new Paragraph(title, font);
        p.setAlignment(Element.ALIGN_LEFT);
        p.setSpacingAfter(10);
        document.add(p);
    }

    private void addBasicInfoTable(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont, 
                               Project project, String checkTimeRange, String reportTime, String checkRange) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setWidths(new float[]{25f, 75f});

        String projectName = "";
        ProjectMetadata metadata = project.getMetadata();
        if (metadata != null) {
            projectName = metadata.getName();
        }
        if (projectName == null || projectName.isEmpty()) {
            projectName = "未命名项目";
        }

        addInfoRow(table, "项目名称：", projectName, normalFont);
        addInfoRow(table, "检查时间：", checkTimeRange, normalFont);
        addInfoRow(table, "报告生成时间：", reportTime, normalFont);
        addInfoRow(table, "检查范围：", checkRange, normalFont);

        document.add(table);
    }

    private void addInfoRow(PdfPTable table, String label, String value, com.itextpdf.text.Font font) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPaddingTop(3);
        labelCell.setPaddingBottom(3);
        labelCell.setPaddingLeft(0);
        labelCell.setPaddingRight(5);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setPaddingTop(3);
        valueCell.setPaddingBottom(3);
        valueCell.setPaddingLeft(0);
        valueCell.setPaddingRight(3);
        table.addCell(valueCell);
    }

    private void addOverviewSection(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont,
                               int totalRows, int totalFolders, int totalFiles, int totalErrors, CheckStatistics checkStats, JsonNode results,
                               int formatErrors, int resourceErrors, int contentErrors, int imageErrors) throws DocumentException, IOException {
        Paragraph p1 = new Paragraph("总体统计", headerFont);
        p1.setSpacingAfter(5);
        document.add(p1);

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        String[] headers = {"检查维度", "数量", "备注"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, normalFont));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(3);
            table.addCell(cell);
        }

        addOverviewRow(table, "检查分类", checkStats.categoryCount + "类", checkStats.categoryRemark, normalFont);
        addOverviewRow(table, "检查项数", checkStats.itemCount + "项", checkStats.itemRemark, normalFont);
        addOverviewRow(table, "数据行数", String.format("%,d行", totalRows), "元数据条目", normalFont);
        addOverviewRow(table, "文件总数", String.format("%,d个", totalFiles), "图像及PDF文件", normalFont);
        addOverviewRow(table, "文件夹数", String.format("%,d个", totalFolders), "档案目录结构", normalFont);

        document.add(table);
        document.add(new Paragraph(" "));

        Paragraph p2 = new Paragraph("检查结果概览", headerFont);
        p2.setSpacingAfter(5);
        document.add(p2);

        double errorRate = totalRows > 0 ? (totalErrors * 100.0 / totalRows) : 0;
        String status = totalErrors == 0 ? "通过" : "不通过";

        PdfPTable resultTable = new PdfPTable(1);
        resultTable.setWidthPercentage(80);
        resultTable.setHorizontalAlignment(Element.ALIGN_LEFT);

        PdfPCell cell1 = new PdfPCell(new Phrase("检查完成率：100%", normalFont));
        cell1.setBorder(Rectangle.NO_BORDER);
        cell1.setPadding(3);
        resultTable.addCell(cell1);

        PdfPCell cell2 = new PdfPCell(new Phrase("通过状态：" + status, normalFont));
        cell2.setBorder(Rectangle.NO_BORDER);
        cell2.setPadding(3);
        resultTable.addCell(cell2);
        PdfPCell cell3 = new PdfPCell(new Phrase(String.format("错误类别、数量和比例分布图：", errorRate, totalErrors, totalRows), normalFont));
        cell3.setBorder(Rectangle.NO_BORDER);
        cell3.setPadding(3);
        resultTable.addCell(cell3);

        document.add(resultTable);
        document.add(new Paragraph(" "));

        addQualityChart(document, headerFont, normalFont, formatErrors, resourceErrors, contentErrors, imageErrors, totalErrors);
    }

    private void addOverviewRow(PdfPTable table, String dimension, String count, String remark, com.itextpdf.text.Font font) {
        PdfPCell cell1 = new PdfPCell(new Phrase(dimension, font));
        cell1.setPadding(3);
        table.addCell(cell1);

        PdfPCell cell2 = new PdfPCell(new Phrase(count, font));
        cell2.setPadding(3);
        table.addCell(cell2);

        PdfPCell cell3 = new PdfPCell(new Phrase(remark, font));
        cell3.setPadding(3);
        table.addCell(cell3);
    }

    private void addFourPropertiesSection(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont,
                                     com.itextpdf.text.Font smallFont, JsonNode results, JsonNode summary, JsonNode fileStatistics,
                                     int totalRows, int totalFolders, int totalFiles, QualityRulesConfig rulesConfig) throws DocumentException {
        addSubSectionTitle(document, "4.1 真实性检测", headerFont);
        addSubSectionDesc(document, "检查目的：确保电子档案及元数据真实存在", normalFont);
        
        document.add(new Paragraph("数据格式检查", smallFont));
        document.add(new Paragraph(" "));
        addDataFormatChecks(document, smallFont, results, rulesConfig, totalRows);
        document.add(new Paragraph(" "));
        
        document.add(new Paragraph("文件资源关联检查", smallFont));
        document.add(new Paragraph(" "));
        addFileResourceChecks(document, smallFont, results, rulesConfig, totalRows, totalFolders, totalFiles);
        document.add(new Paragraph(" "));
        
        document.add(new Paragraph("内容比对检查", smallFont));
        document.add(new Paragraph(" "));
        addContentComparisonChecks(document, smallFont, results, rulesConfig, totalRows);
        document.add(new Paragraph(" "));
        
        int authenticityErrors = countErrorsByType(results, "data_existence", "folder_existence", "file_count", 
            "empty_check", "unique_check", "content_mismatch");
        Paragraph summary1 = new Paragraph("小结：真实性检测发现" + authenticityErrors + "个问题，主要涉及数据条目与文件夹的对应关系及内容比对。", normalFont);
        summary1.setIndentationLeft(20);
        summary1.setSpacingBefore(5);
        summary1.setSpacingAfter(10);
        document.add(summary1);

        addSubSectionTitle(document, "4.2 完整性检测", headerFont);
        addSubSectionDesc(document, "检查目的：确保电子档案内容完整、齐全，无缺失", normalFont);
        String[] completenessTypes = {"empty_check", "unique_check", "empty_folder", "page_sequence", "file_sequential", "folder_sequential", "repeat_image"};
        String[] completenessNames = {"非空检查", "唯一性检查", "空文件夹检查", "页序号连续", "文件序号连续", "文件夹序号连续", "重复图像检查"};
        addCheckItemsTable(document, smallFont, results, completenessTypes, completenessNames, totalRows, totalFolders, totalFiles);
        document.add(new Paragraph(" "));

        int completenessErrors = countErrorsByType(results, "empty_check", "unique_check", "empty_folder", "page_sequence", "file_sequential", "folder_sequential", "repeat_image");
        Paragraph summary2 = new Paragraph("小结：完整性检测发现" + completenessErrors + "个问题，主要集中在文件连续性和空文件夹方面。", normalFont);
        summary2.setIndentationLeft(20);
        summary2.setSpacingBefore(5);
        summary2.setSpacingAfter(10);
        document.add(summary2);

        addSubSectionTitle(document, "4.3 可用性检测", headerFont);
        addSubSectionDesc(document, "检查目的：确保电子档案可读、可用，符合规范要求", normalFont);
        String[] usabilityTypes = {"date_format", "value_list", "regex_check", "file_name_format", "folder_name_format", "dpi", "bit_depth", "file-size", "quality", "damage"};
        String[] usabilityNames = {"日期格式检查", "值列表检查", "正则表达式检查", "文件命名格式", "文件夹命名格式", "DPI检查", "位深度检查", "文件大小检查", "质量检查", "损坏文件检查"};
        addCheckItemsTable(document, smallFont, results, usabilityTypes, usabilityNames, totalRows, totalFolders, totalFiles);
        document.add(new Paragraph(" "));

        int usabilityErrors = countErrorsByType(results, "date_format", "value_list", "regex_check", "file_name_format", "folder_name_format", "dpi", "bit_depth", "file-size", "quality", "damage");
        Paragraph summary3 = new Paragraph("小结：可用性检测发现" + usabilityErrors + "个问题，主要涉及格式规范和技术参数。", normalFont);
        summary3.setIndentationLeft(20);
        summary3.setSpacingBefore(5);
        summary3.setSpacingAfter(10);
        document.add(summary3);

        addSubSectionTitle(document, "4.4 安全性检测", headerFont);
        addSubSectionDesc(document, "检查目的：确保电子档案安全可靠，防止非法访问和篡改", normalFont);
        String[] securityTypes = {"illegal_file", "security_check"};
        String[] securityNames = {"非法文件检查", "杀毒软件检测"};
        addCheckItemsTable(document, smallFont, results, securityTypes, securityNames, totalRows, totalFolders, totalFiles);
        document.add(new Paragraph(" "));

        int securityErrors = countErrorsByType(results, "illegal_file", "security_check");
        Paragraph summary4 = new Paragraph("小结：安全性检测" + (securityErrors == 0 ? "全部通过，未发现非法文件。" : "发现" + securityErrors + "个问题。"), normalFont);
        summary4.setIndentationLeft(20);
        summary4.setSpacingBefore(5);
        summary4.setSpacingAfter(10);
        document.add(summary4);

        addSubSectionTitle(document, "4.5 技术参数检测", headerFont);
        addSubSectionDesc(document, "检查目的：确保图像质量符合档案数字化要求", normalFont);
        String[] techParamTypes = {"blank", "bias", "edge", "stain", "hole"};
        String[] techParamNames = {"空白页检查", "倾斜检查", "黑边检查", "污点检查", "装订孔检查"};
        addCheckItemsTable(document, smallFont, results, techParamTypes, techParamNames, totalRows, totalFolders, totalFiles);
        document.add(new Paragraph(" "));

        int techParamErrors = countErrorsByType(results, "blank", "bias", "edge", "stain", "hole");
        Paragraph summary5 = new Paragraph("小结：技术参数检测发现" + techParamErrors + "个问题，主要集中在图像质量方面。", normalFont);
        summary5.setIndentationLeft(20);
        summary5.setSpacingBefore(5);
        summary5.setSpacingAfter(10);
        document.add(summary5);

        addSubSectionTitle(document, "4.6 存储格式检测", headerFont);
        addSubSectionDesc(document, "检查目的：确保存储格式规范，便于长期保存", normalFont);
        String[] storageTypes = {"format"};
        String[] storageNames = {"图像格式检查"};
        addCheckItemsTable(document, smallFont, results, storageTypes, storageNames, totalRows, totalFolders, totalFiles);
        document.add(new Paragraph(" "));

        int storageErrors = countErrorsByType(results, "format");
        Paragraph summary6 = new Paragraph("小结：存储格式检测" + (storageErrors == 0 ? "全部通过。" : "发现" + storageErrors + "个问题。"), normalFont);
        summary6.setIndentationLeft(20);
        summary6.setSpacingBefore(5);
        summary6.setSpacingAfter(10);
        document.add(summary6);

        addSubSectionTitle(document, "4.7 其他检测", headerFont);
        addOtherSection(document, smallFont, results, totalRows, totalFolders, totalFiles, fileStatistics);
        document.add(new Paragraph(" "));
    }

    private int countErrorsByType(JsonNode results, String... errorTypes) {
        JsonNode errors = results.get("errors");
        int count = 0;
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String type = error.path("errorType").asText("");
                for (String errorType : errorTypes) {
                    if (type.equals(errorType) || type.contains(errorType)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private void addOtherSection(Document document, com.itextpdf.text.Font font, JsonNode results, int totalRows, int totalFolders, int totalFiles, JsonNode fileStatistics) throws DocumentException {
         PdfPTable table = new PdfPTable(2);
         table.setWidthPercentage(80);
         table.setHorizontalAlignment(Element.ALIGN_LEFT);

         String[] headers = {"检查项", "数量"};
         for (String h : headers) {
             PdfPCell cell = new PdfPCell(new Phrase(h, font));
             cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
             cell.setPadding(3);
             table.addCell(cell);
         }

         PdfPCell cell1 = new PdfPCell(new Phrase("图像数量统计", font));
         cell1.setPadding(3);
         table.addCell(cell1);

         PdfPCell cell2 = new PdfPCell(new Phrase(String.valueOf(totalFiles), font));
         cell2.setPadding(3);
         table.addCell(cell2);

         String pageSizeStats = getPageSizeStats(fileStatistics);
         PdfPCell cell3 = new PdfPCell(new Phrase("尺幅大小统计", font));
         cell3.setPadding(3);
         table.addCell(cell3);

         PdfPCell cell4 = new PdfPCell(new Phrase(pageSizeStats, font));
         cell4.setPadding(3);
         table.addCell(cell4);

         document.add(table);
     }

     private String getPageSizeStats(JsonNode fileStatistics) {
         if (fileStatistics == null || !fileStatistics.has("pageSizeDistribution")) {
             return "无数据";
         }

         JsonNode pageSizeDist = fileStatistics.get("pageSizeDistribution");
         if (pageSizeDist == null || !pageSizeDist.isObject()) {
             return "无数据";
         }

         Iterator<Map.Entry<String, JsonNode>> fields = pageSizeDist.fields();
         if (!fields.hasNext()) {
             return "无数据";
         }

         StringBuilder sb = new StringBuilder();
         while (fields.hasNext()) {
             Map.Entry<String, JsonNode> entry = fields.next();
             String size = entry.getKey();
             int count = entry.getValue().asInt(0);
             if (sb.length() > 0) {
                 sb.append("; ");
             }
             sb.append(size).append(" ").append(count).append("页");
         }
         return sb.toString();
     }

    private void addSubSectionTitle(Document document, String title, com.itextpdf.text.Font font) throws DocumentException {
        Paragraph p = new Paragraph(title, font);
        p.setSpacingBefore(10);
        p.setSpacingAfter(5);
        document.add(p);
    }

    private void addSubSectionDesc(Document document, String desc, com.itextpdf.text.Font font) throws DocumentException {
        Paragraph p = new Paragraph(desc, font);
        p.setSpacingAfter(5);
        document.add(p);
    }

    private void addCheckItemTable(Document document, com.itextpdf.text.Font font, JsonNode results, String errorType, String itemName) throws DocumentException {
        JsonNode errors = results.get("errors");
        int count = 0;
        int checkCount = 0;

        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String type = error.path("errorType").asText("");
                if (type.equals(errorType) || type.contains(errorType)) {
                    count++;
                }
            }
            checkCount = errors.size();
        }

        double errorRate = checkCount > 0 ? (count * 100.0 / checkCount) : 0;
        String status = count == 0 ? "✅" : "❌";

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        String[] headers = {"检查项", "检查数量", "错误数", "错误率", "状态"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(3);
            table.addCell(cell);
        }

        PdfPCell cell1 = new PdfPCell(new Phrase(itemName, font));
        cell1.setPadding(3);
        table.addCell(cell1);

        PdfPCell cell2 = new PdfPCell(new Phrase(String.valueOf(checkCount), font));
        cell2.setPadding(3);
        table.addCell(cell2);

        PdfPCell cell3 = new PdfPCell(new Phrase(String.valueOf(count), font));
        cell3.setPadding(3);
        table.addCell(cell3);

        PdfPCell cell4 = new PdfPCell(new Phrase(String.format("%.1f%%", errorRate), font));
        cell4.setPadding(3);
        table.addCell(cell4);

        PdfPCell cell5 = new PdfPCell(new Phrase(status, font));
        cell5.setPadding(3);
        cell5.setBackgroundColor(count > 0 ? new BaseColor(255, 220, 220) : BaseColor.WHITE);
        table.addCell(cell5);

        document.add(table);
    }

    private void addCheckItemsTable(Document document, com.itextpdf.text.Font font, JsonNode results, String[] errorTypes, String[] itemNames, int totalRows, int totalFolders, int totalFiles) throws DocumentException {
        JsonNode errors = results.get("errors");

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        String[] headers = {"检查项", "检查数量", "错误数", "错误率", "状态"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(3);
            table.addCell(cell);
        }

        for (int i = 0; i < errorTypes.length; i++) {
            String errorType = errorTypes[i];
            String itemName = itemNames[i];
            int count = 0;
            int checkCount = 0;

            if (errors != null && errors.isArray()) {
                for (JsonNode error : errors) {
                    String type = error.path("errorType").asText("");
                    if (type.equals(errorType) || type.contains(errorType)) {
                        count++;
                    }
                }
            }

            checkCount = getCheckCountForType(errorType, totalRows, totalFolders, totalFiles);

            double errorRate = checkCount > 0 ? (count * 100.0 / checkCount) : 0;
            String status = count == 0 ? "✅" : "❌";

            PdfPCell cell1 = new PdfPCell(new Phrase(itemName, font));
            cell1.setPadding(3);
            table.addCell(cell1);

            PdfPCell cell2 = new PdfPCell(new Phrase(String.valueOf(checkCount), font));
            cell2.setPadding(3);
            table.addCell(cell2);

            PdfPCell cell3 = new PdfPCell(new Phrase(String.valueOf(count), font));
            cell3.setPadding(3);
            table.addCell(cell3);

            PdfPCell cell4 = new PdfPCell(new Phrase(String.format("%.1f%%", errorRate), font));
            cell4.setPadding(3);
            table.addCell(cell4);

            PdfPCell cell5 = new PdfPCell(new Phrase(status, font));
            cell5.setPadding(3);
            cell5.setBackgroundColor(count > 0 ? new BaseColor(255, 220, 220) : BaseColor.WHITE);
            table.addCell(cell5);
        }

        document.add(table);
    }

    private int getCheckCountForType(String errorType, int totalRows, int totalFolders, int totalFiles) {
        switch (errorType) {
            case "data_existence":
            case "empty_check":
            case "unique_check":
            case "date_format":
            case "value_list":
            case "regex_check":
                return totalRows;
            case "folder_existence":
            case "folder_name_format":
            case "folder_sequential":
            case "empty_folder":
                return totalFolders;
            case "file_count":
            case "page_sequence":
            case "file_sequential":
            case "repeat_image":
            case "file_name_format":
            case "dpi":
            case "bit_depth":
            case "file-size":
            case "quality":
            case "damage":
            case "blank":
            case "bias":
            case "edge":
            case "stain":
            case "hole":
            case "pageSize":
            case "illegal_file":
                return totalFiles;
            case "pdf_image_uniformity":
                return 0;
            case "format":
                return totalFiles;
            case "security_check":
                return 1;
            case "counting":
                return totalFiles;
            default:
                return totalRows;
        }
    }

    private void addSummarySection(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont,
                                int totalErrors, int formatErrors, int resourceErrors, int contentErrors, int imageErrors,
                                int totalRows, int totalFolders, int totalFiles, JsonNode results) throws DocumentException, IOException {
        Paragraph p1 = new Paragraph("3.1 分类统计", headerFont);
        p1.setSpacingAfter(5);
        document.add(p1);

        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        String[] headers = {"检测分类", "检查项数", "检查数", "错误数", "错误率", "检测状态"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(5);
            table.addCell(cell);
        }

        int existenceChecks = totalRows + totalFolders + totalFiles;
        int existenceErrors = countErrorsByType(results, "data_existence", "folder_existence", "file_count");
        addSummaryRow(table, "真实性检测", 3, existenceChecks, existenceErrors, normalFont);

        int completenessChecks = totalRows + totalRows + totalFolders + totalFiles + totalFiles + totalFolders + totalFiles;
        int completenessErrors = countErrorsByType(results, "empty_check", "unique_check", "empty_folder", "page_sequence", "file_sequential", "folder_sequential", "repeat_image");
        addSummaryRow(table, "完整性检测", 7, completenessChecks, completenessErrors, normalFont);

        int usabilityChecks = totalRows + totalRows + totalRows + totalFiles + totalFolders + totalFiles + totalFiles + totalFiles + totalFiles + totalFiles;
        int usabilityErrors = countErrorsByType(results, "date_format", "value_list", "regex_check", "file_name_format", "folder_name_format", "dpi", "bit_depth", "file-size", "quality", "damage");
        addSummaryRow(table, "可用性检测", 10, usabilityChecks, usabilityErrors, normalFont);

        int securityChecks = totalFiles + 1;
        int securityErrors = countErrorsByType(results, "illegal_file", "security_check");
        addSummaryRow(table, "安全性检测", 2, securityChecks, securityErrors, normalFont);

        int techParamChecks = totalFiles * 5;
        int techParamErrors = countErrorsByType(results, "blank", "bias", "edge", "stain", "hole");
        addSummaryRow(table, "技术参数检测", 5, techParamChecks, techParamErrors, normalFont);

        int storageChecks = totalFiles;
        int storageErrors = countErrorsByType(results, "format");
        addSummaryRow(table, "存储格式检测", 1, storageChecks, storageErrors, normalFont);

        int otherChecks = totalFiles;
        int otherErrors = countErrorsByType(results, "counting", "pageSize");
        addSummaryRow(table, "其他检测", 2, otherChecks, otherErrors, normalFont);

        document.add(table);
        document.add(new Paragraph(" "));

        Paragraph p2 = new Paragraph("3.2 占比前10的错误项分布", headerFont);
        p2.setSpacingAfter(5);
        document.add(p2);

        Map<String, Integer> errorCounts = countErrorsByTypeDetailed(results);
        List<Map.Entry<String, Integer>> sortedErrors = new ArrayList<>(errorCounts.entrySet());
        sortedErrors.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int topCount = Math.min(sortedErrors.size(), 10);

        PdfPTable chartTable = new PdfPTable(4);
        chartTable.setWidthPercentage(80);
        chartTable.setHorizontalAlignment(Element.ALIGN_LEFT);

        String[] chartHeaders = {"错误类型", "数量", "占比", "分布"};
        for (String h : chartHeaders) {
            PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(5);
            chartTable.addCell(cell);
        }

        for (int i = 0; i < topCount; i++) {
            Map.Entry<String, Integer> entry = sortedErrors.get(i);
            String errorType = getErrorTypeDisplayName(entry.getKey());
            int count = entry.getValue();
            double percentage = totalErrors > 0 ? (count * 100.0 / totalErrors) : 0;
            addChartRow(chartTable, errorType, count, percentage, normalFont, getColorForIndex(i));
        }

        document.add(chartTable);
        document.add(new Paragraph(" "));
    }

    private void addSummaryRow(PdfPTable table, String label, int checkItems, int totalChecks, int errors, com.itextpdf.text.Font font) {
        table.addCell(new Phrase(label, font));
        table.addCell(new Phrase(String.valueOf(checkItems), font));
        table.addCell(new Phrase(String.valueOf(totalChecks), font));
        table.addCell(new Phrase(String.valueOf(errors), font));
        double errorRate = totalChecks > 0 ? (errors * 100.0 / totalChecks) : 0;
        table.addCell(new Phrase(String.format("%.1f%%", errorRate), font));
        String status = errors == 0 ? "通过" : "不通过";
        table.addCell(new Phrase(status, font));
    }

    private void addAnalysisSection(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont,
                                int totalErrors, int imageErrors, int resourceErrors, int formatErrors, int contentErrors) throws DocumentException {
        Paragraph p1 = new Paragraph("5.1 主要问题分析", headerFont);
        p1.setSpacingAfter(5);
        document.add(p1);

        if (imageErrors >= 10) {
            Paragraph p2 = new Paragraph("🔴 严重问题（错误数≥10）", normalFont);
            p2.setSpacingBefore(5);
            document.add(p2);

            Paragraph p3 = new Paragraph("• 技术参数问题：" + imageErrors + "个错误，占" + String.format("%.1f%%", (imageErrors * 100.0 / totalErrors)) + "%", normalFont);
            p3.setIndentationLeft(20);
            p3.setSpacingBefore(3);
            document.add(p3);

            Paragraph p4 = new Paragraph("  影响：影响图像质量和可读性", normalFont);
            p4.setIndentationLeft(30);
            p4.setSpacingBefore(2);
            document.add(p4);
        }

        if (resourceErrors >= 3 && resourceErrors < 10) {
            Paragraph p5 = new Paragraph("🟡 一般问题（错误数3-9）", normalFont);
            p5.setSpacingBefore(5);
            document.add(p5);

            Paragraph p6 = new Paragraph("• 完整性检测问题：" + resourceErrors + "个错误", normalFont);
            p6.setIndentationLeft(20);
            p6.setSpacingBefore(3);
            document.add(p6);

            Paragraph p7 = new Paragraph("  影响：档案完整性", normalFont);
            p7.setIndentationLeft(30);
            p7.setSpacingBefore(2);
            document.add(p7);
        }

        int otherErrors = formatErrors + contentErrors;
        if (otherErrors > 0 && otherErrors <= 2) {
            Paragraph p8 = new Paragraph("🟢 轻微问题（错误数≤2）", normalFont);
            p8.setSpacingBefore(5);
            document.add(p8);

            Paragraph p9 = new Paragraph("• 其他各类问题：" + otherErrors + "个错误", normalFont);
            p9.setIndentationLeft(20);
            p9.setSpacingBefore(3);
            document.add(p9);

            Paragraph p10 = new Paragraph("  影响：格式规范、数据条目等问题", normalFont);
            p10.setIndentationLeft(30);
            p10.setSpacingBefore(2);
            document.add(p10);
        }

        document.add(new Paragraph(" "));

        Paragraph p11 = new Paragraph("5.2 改进建议", headerFont);
        p11.setSpacingAfter(5);
        document.add(p11);

        Paragraph p12 = new Paragraph("立即整改", normalFont);
        p12.setSpacingBefore(5);
        document.add(p12);

        if (imageErrors > 0) {
            Paragraph p13 = new Paragraph("1. 图像质量问题", normalFont);
            p13.setIndentationLeft(20);
            p13.setSpacingBefore(3);
            document.add(p13);

            Paragraph p14 = new Paragraph("  - 重新扫描有问题的图像", normalFont);
            p14.setIndentationLeft(30);
            p14.setSpacingBefore(2);
            document.add(p14);

            Paragraph p15 = new Paragraph("  - 校正倾斜、黑边等问题", normalFont);
            p15.setIndentationLeft(30);
            p15.setSpacingBefore(2);
            document.add(p15);
        }

        if (resourceErrors > 0) {
            Paragraph p16 = new Paragraph("2. 完整性修复", normalFont);
            p16.setIndentationLeft(20);
            p16.setSpacingBefore(3);
            document.add(p16);

            Paragraph p17 = new Paragraph("  - 补充缺失的文件夹内容", normalFont);
            p17.setIndentationLeft(30);
            p17.setSpacingBefore(2);
            document.add(p17);

            Paragraph p18 = new Paragraph("  - 修复文件序号不连续问题", normalFont);
            p18.setIndentationLeft(30);
            p18.setSpacingBefore(2);
            document.add(p18);
        }

        document.add(new Paragraph(" "));

        Paragraph p19 = new Paragraph("流程优化和改进", normalFont);
        p19.setSpacingBefore(5);
        document.add(p19);

        Paragraph p20 = new Paragraph("1. 建立质量控制机制", normalFont);
        p20.setIndentationLeft(20);
        p20.setSpacingBefore(3);
        document.add(p20);

        Paragraph p21 = new Paragraph("  - 制定扫描质量检查标准", normalFont);
        p21.setIndentationLeft(30);
        p21.setSpacingBefore(2);
        document.add(p21);

        Paragraph p22 = new Paragraph("  - 实施扫描后质量复核", normalFont);
        p22.setIndentationLeft(30);
        p22.setSpacingBefore(2);
        document.add(p22);

        Paragraph p23 = new Paragraph("2. 完善归档流程", normalFont);
        p23.setIndentationLeft(20);
        p23.setSpacingBefore(3);
        document.add(p23);

        Paragraph p24 = new Paragraph("  - 建立文件归档检查清单", normalFont);
        p24.setIndentationLeft(30);
        p24.setSpacingBefore(2);
        document.add(p24);

        Paragraph p25 = new Paragraph("  - 加强归档人员培训", normalFont);
        p25.setIndentationLeft(30);
        p25.setSpacingBefore(2);
        document.add(p25);

        Paragraph p26 = new Paragraph("3. 质量管理体系", normalFont);
        p26.setIndentationLeft(20);
        p26.setSpacingBefore(3);
        document.add(p26);

        Paragraph p27 = new Paragraph("  - 建立质量监控机制", normalFont);
        p27.setIndentationLeft(30);
        p27.setSpacingBefore(2);
        document.add(p27);

        Paragraph p28 = new Paragraph("  - 实施持续改进", normalFont);
        p28.setIndentationLeft(30);
        p28.setSpacingBefore(2);
        document.add(p28);
    }

    private void addConclusionSection(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont,
                                  int totalErrors, int totalRows) throws DocumentException {
        Paragraph p1 = new Paragraph("6.1 总体评价", headerFont);
        p1.setSpacingAfter(5);
        document.add(p1);

        String status = totalErrors == 0 ? "建议通过" : "建议不通过";
        double errorRate = totalRows > 0 ? (totalErrors * 100.0 / totalRows) : 0;

        Paragraph p2 = new Paragraph("检查结果：" + status, normalFont);
        p2.setSpacingBefore(5);
        document.add(p2);

        if (totalErrors > 0) {
            Paragraph p3 = new Paragraph(String.format("本次检查共发现%d个问题，总体错误率%.1f%%。结果进攻参考，请仔细核对并分析整改关键的质量问题。", totalErrors, errorRate), normalFont);
            p3.setSpacingBefore(3);
            document.add(p3);
        } else {
            Paragraph p3 = new Paragraph("本次检查未发现任何问题，所有检测项均通过。", normalFont);
            p3.setSpacingBefore(3);
            document.add(p3);
        }
    }

    private void addReportInfoSection(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont) throws DocumentException {
        String reportTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setWidths(new float[]{15f, 85f});
        
        addInfoRow(table, "生成时间:", reportTime, normalFont);
        addInfoRow(table, "检查标准:", "依据《文书类电子档案检测一般要求》（DA/T 70-2018）、《档案著录规则》（DA/T 18-2022）、《纸质档案数字化规范》（DA/T 31-2017）", normalFont);
        addInfoRow(table, "检查工具:", "数据金炼系统 - 数据质量模块", normalFont);

        document.add(table);
    }

    /**
     * Add a chart row with percentage bar visualization.
     */
    private void addChartRow(PdfPTable table, String label, int count, double percentage,
                             com.itextpdf.text.Font font, BaseColor barColor) {
        // Label
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setPadding(5);
        table.addCell(labelCell);

        // Count
        PdfPCell countCell = new PdfPCell(new Phrase(String.valueOf(count), font));
        countCell.setPadding(5);
        countCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(countCell);

        // Percentage
        String percentText = String.format("%.1f%%", percentage);
        PdfPCell percentCell = new PdfPCell(new Phrase(percentText, font));
        percentCell.setPadding(5);
        percentCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(percentCell);

        // Visual bar (simplified as colored cell with text)
        PdfPCell barCell = new PdfPCell();
        barCell.setPadding(5);
        if (count > 0 && percentage > 0) {
            // Create a simple bar using nested table
            PdfPTable barTable = new PdfPTable(new float[]{(float)percentage, (float)(100 - percentage)});
            barTable.setWidthPercentage(100);

            PdfPCell filledCell = new PdfPCell();
            filledCell.setBackgroundColor(barColor);
            filledCell.setMinimumHeight(15);
            filledCell.setBorder(Rectangle.NO_BORDER);
            barTable.addCell(filledCell);

            PdfPCell emptyCell = new PdfPCell();
            emptyCell.setBackgroundColor(BaseColor.WHITE);
            emptyCell.setMinimumHeight(15);
            emptyCell.setBorder(Rectangle.NO_BORDER);
            barTable.addCell(emptyCell);

            barCell.addElement(barTable);
        }
        table.addCell(barCell);
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    private String getCheckRange(Project project) {
        try {
            QualityRulesConfig config = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");
            if (config == null || config.getResourceConfig() == null) {
                return "未配置";
            }

            ResourceCheckConfig resourceConfig = config.getResourceConfig();
            String pathMode = resourceConfig.getPathMode();
            List<String> pathFields = resourceConfig.getPathFields();

            if (pathFields == null || pathFields.isEmpty()) {
                return "未配置";
            }

            String firstArchiveNumber = null;
            String lastArchiveNumber = null;

            for (com.google.refine.model.Row row : project.rows) {
                String archiveNumber = extractArchiveNumber(row, pathFields, pathMode, resourceConfig, project);
                if (archiveNumber != null && !archiveNumber.isEmpty()) {
                    if (firstArchiveNumber == null) {
                        firstArchiveNumber = archiveNumber;
                    }
                    lastArchiveNumber = archiveNumber;
                }
            }

            if (firstArchiveNumber == null) {
                return "无数据";
            }

            if (firstArchiveNumber.equals(lastArchiveNumber)) {
                return firstArchiveNumber;
            }

            return firstArchiveNumber + " → " + lastArchiveNumber;

        } catch (Exception e) {
            logger.error("Error getting check range", e);
            return "获取失败";
        }
    }

    private String extractArchiveNumber(com.google.refine.model.Row row, List<String> pathFields, String pathMode, ResourceCheckConfig resourceConfig, Project project) {
        try {
            if ("separator".equals(pathMode)) {
                String separator = resourceConfig.getSeparator();
                if (pathFields.size() > 0) {
                    int lastFieldIndex = pathFields.size() - 1;
                    String fieldName = pathFields.get(lastFieldIndex);
                    return getCellValue(row, fieldName, project);
                }
            } else if ("template".equals(pathMode)) {
                String template = resourceConfig.getTemplate();
                if (template != null && !template.isEmpty()) {
                    String[] templateParts = template.split("\\\\");
                    if (templateParts.length > 0) {
                        String lastTemplatePart = templateParts[templateParts.length - 1];
                        StringBuilder archiveNumber = new StringBuilder();
                        String[] placeholders = lastTemplatePart.split("\\{\\d+\\}");
                        int fieldIndex = 0;
                        for (int i = 0; i < lastTemplatePart.length(); i++) {
                            if (i > 0 && lastTemplatePart.charAt(i - 1) == '{' && lastTemplatePart.charAt(i) >= '0' && lastTemplatePart.charAt(i) <= '9') {
                                continue;
                            }
                            if (lastTemplatePart.charAt(i) == '}' && i > 0 && lastTemplatePart.charAt(i - 1) >= '0' && lastTemplatePart.charAt(i - 1) <= '9') {
                                if (fieldIndex < pathFields.size()) {
                                    String value = getCellValue(row, pathFields.get(fieldIndex), project);
                                    if (value != null) {
                                        archiveNumber.append(value);
                                    }
                                    fieldIndex++;
                                }
                            } else if (lastTemplatePart.charAt(i) != '{' && lastTemplatePart.charAt(i) != '}') {
                                archiveNumber.append(lastTemplatePart.charAt(i));
                            }
                        }
                        return archiveNumber.toString();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting archive number", e);
        }
        return null;
    }

    private String getCellValue(com.google.refine.model.Row row, String columnName, Project project) {
        try {
            int cellIndex = project.columnModel.getColumnIndexByName(columnName);
            if (cellIndex >= 0 && cellIndex < row.cells.size()) {
                com.google.refine.model.Cell cell = row.getCell(cellIndex);
                if (cell != null && cell.value != null) {
                    return cell.value.toString().trim();
                }
            }
        } catch (Exception e) {
            logger.error("Error getting cell value for column: " + columnName, e);
        }
        return null;
    }

    private CheckStatistics getCheckStatistics(Project project) {
        CheckStatistics stats = new CheckStatistics();
        
        try {
            QualityRulesConfig config = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");
            if (config == null) {
                return stats;
            }

            List<String> enabledCategories = new ArrayList<>();
            List<String> enabledItemCategories = new ArrayList<>();
            int totalItemCount = 0;

            Map<String, FormatRule> formatRules = config.getFormatRules();
            if (formatRules != null && !formatRules.isEmpty()) {
                enabledCategories.add("数据格式检查");
                enabledItemCategories.add("真实性");
                totalItemCount += formatRules.size();
            }

            ResourceCheckConfig resourceConfig = config.getResourceConfig();
            if (resourceConfig != null && hasEnabledResourceChecks(resourceConfig)) {
                enabledCategories.add("文件资源关联检查");
                enabledItemCategories.add("完整性");
                totalItemCount += countResourceChecks(resourceConfig);
            }

            List<ContentComparisonRule> contentRules = config.getContentRules();
            if (contentRules != null && !contentRules.isEmpty()) {
                enabledCategories.add("内容比对检查");
                enabledItemCategories.add("完整性");
                totalItemCount += contentRules.size();
            }

            ImageQualityRule imageQualityRule = config.getImageQualityRule();
            if (imageQualityRule != null && imageQualityRule.isEnabled()) {
                enabledCategories.add("图像质量（压缩率）检查");
                List<ImageCheckCategory> categories = imageQualityRule.getCategories();
                if (categories != null) {
                    for (ImageCheckCategory category : categories) {
                        if (category.isEnabled()) {
                            List<ImageCheckItem> items = category.getItems();
                            if (items != null && !items.isEmpty()) {
                                totalItemCount += items.size();
                                String categoryCode = category.getCategoryCode();
                                String categoryName = mapCategoryCodeToName(categoryCode);
                                if (categoryName != null && !enabledItemCategories.contains(categoryName)) {
                                    enabledItemCategories.add(categoryName);
                                }
                            }
                        }
                    }
                }
            }

            stats.categoryCount = enabledCategories.size();
            stats.categoryRemark = String.join("、", enabledCategories);
            stats.itemCount = totalItemCount;
            if (enabledItemCategories.isEmpty()) {
                stats.itemRemark = "涵盖档案四性检测要求";
            } else {
                stats.itemRemark = "包含" + String.join("、", enabledItemCategories) + "，共" + enabledItemCategories.size() + "类";
            }

        } catch (Exception e) {
            logger.error("Error getting check statistics", e);
        }

        return stats;
    }

    private String mapCategoryCodeToName(String categoryCode) {
        if (categoryCode == null) {
            return null;
        }
        switch (categoryCode) {
            case "existence":
                return "真实性";
            case "completeness":
                return "完整性";
            case "usability":
                return "可用性";
            case "security":
                return "安全性";
            case "technical":
                return "技术参数";
            case "storage":
                return "存储格式";
            default:
                return null;
        }
    }

    private boolean hasEnabledResourceChecks(ResourceCheckConfig config) {
        if (config.getFolderChecks() != null) {
            if (config.getFolderChecks().isExistence() || 
                config.getFolderChecks().isDataExistence() || 
                config.getFolderChecks().isSequential() || 
                config.getFolderChecks().isEmptyFolder()) {
                return true;
            }
        }
        if (config.getFileChecks() != null) {
            if (config.getFileChecks().isCountMatch() || 
                config.getFileChecks().isSequential()) {
                return true;
            }
        }
        return false;
    }

    private int countResourceChecks(ResourceCheckConfig config) {
        int count = 0;
        if (config.getFolderChecks() != null) {
            if (config.getFolderChecks().isExistence()) count++;
            if (config.getFolderChecks().isDataExistence()) count++;
            if (config.getFolderChecks().isSequential()) count++;
            if (config.getFolderChecks().isEmptyFolder()) count++;
        }
        if (config.getFileChecks() != null) {
            if (config.getFileChecks().isCountMatch()) count++;
            if (config.getFileChecks().isSequential()) count++;
        }
        return count;
    }

    private void addQualityChart(Document document, com.itextpdf.text.Font headerFont, com.itextpdf.text.Font normalFont,
                                int formatErrors, int resourceErrors, int contentErrors, int imageErrors, int totalErrors) throws DocumentException, IOException {
        DefaultPieDataset dataset = new DefaultPieDataset();
        
        dataset.setValue("数据格式检查", formatErrors);
        dataset.setValue("文件资源关联检查", resourceErrors);
        dataset.setValue("内容比对检查", contentErrors);
        dataset.setValue("图像质量检查", imageErrors);

        JFreeChart chart = ChartFactory.createPieChart(
            null,
            dataset,
            false,
            false,
            false
        );

        PiePlot plot = (PiePlot) chart.getPlot();
        plot.setLabelGenerator(null);
        plot.setSectionPaint("数据格式检查", new Color(231, 76, 60));
        plot.setSectionPaint("文件资源关联检查", new Color(243, 156, 18));
        plot.setSectionPaint("内容比对检查", new Color(52, 152, 219));
        plot.setSectionPaint("图像质量检查", new Color(155, 89, 182));
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlinePaint(Color.WHITE);
        plot.setShadowPaint(null);
        plot.setShadowXOffset(0);
        plot.setShadowYOffset(0);

        BufferedImage bufferedImage = chart.createBufferedImage(150, 100);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        com.itextpdf.text.Image pdfImage = com.itextpdf.text.Image.getInstance(imageBytes);
        pdfImage.setAlignment(Element.ALIGN_LEFT);

        PdfPTable layoutTable = new PdfPTable(2);
        layoutTable.setWidthPercentage(80);
        layoutTable.setHorizontalAlignment(Element.ALIGN_LEFT);

        PdfPCell imageCell = new PdfPCell(pdfImage);
        imageCell.setBorder(Rectangle.NO_BORDER);
        imageCell.setPadding(5);
        layoutTable.addCell(imageCell);

        PdfPTable legendTable = new PdfPTable(1);
        legendTable.setWidthPercentage(100);

        addLegendRow(legendTable, "数据格式检查", formatErrors, totalErrors, normalFont, new BaseColor(231, 76, 60));
        addLegendRow(legendTable, "文件资源关联检查", resourceErrors, totalErrors, normalFont, new BaseColor(243, 156, 18));
        addLegendRow(legendTable, "内容比对检查", contentErrors, totalErrors, normalFont, new BaseColor(52, 152, 219));
        addLegendRow(legendTable, "图像质量检查", imageErrors, totalErrors, normalFont, new BaseColor(155, 89, 182));

        PdfPCell legendCell = new PdfPCell(legendTable);
        legendCell.setBorder(Rectangle.NO_BORDER);
        legendCell.setPadding(10);
        legendCell.setPaddingLeft(10);
        legendCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        layoutTable.addCell(legendCell);

        document.add(layoutTable);
    }

    private void addLegendRow(PdfPTable table, String label, int count, int total, com.itextpdf.text.Font font, BaseColor color) {
        try {
            PdfPTable rowTable = new PdfPTable(2);
            rowTable.setWidthPercentage(100);
            rowTable.setSpacingAfter(6);

            PdfPTable colorTable = new PdfPTable(1);
            colorTable.setTotalWidth(12);
            colorTable.setLockedWidth(true);
            PdfPCell colorInnerCell = new PdfPCell();
            colorInnerCell.setBackgroundColor(color);
            colorInnerCell.setBorder(Rectangle.NO_BORDER);
            colorInnerCell.setPadding(0);
            colorInnerCell.setLeading(0, 0);
            colorInnerCell.setFixedHeight(4);
            Paragraph p = new Paragraph(" ", font);
            p.setLeading(0, 0);
            p.setMultipliedLeading(0);
            colorInnerCell.addElement(p);
            colorTable.addCell(colorInnerCell);

            PdfPCell colorCell = new PdfPCell(colorTable);
            colorCell.setBorder(Rectangle.NO_BORDER);
            colorCell.setPadding(0);
            colorCell.setPaddingTop(2);
            colorCell.setPaddingBottom(2);
            colorCell.setHorizontalAlignment(Element.ALIGN_LEFT);
            rowTable.addCell(colorCell);

            double percentage = total > 0 ? (count * 100.0 / total) : 0;
            PdfPCell textCell = new PdfPCell(new Phrase(String.format("%s: %d (%.0f%%)", label, count, percentage), font));
            textCell.setBorder(Rectangle.NO_BORDER);
            textCell.setPaddingLeft(8);
            textCell.setPaddingTop(0);
            textCell.setPaddingBottom(0);
            textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            rowTable.addCell(textCell);

            float[] columnWidths = {12f, 100f};
            rowTable.setWidths(columnWidths);

            PdfPCell wrapperCell = new PdfPCell(rowTable);
            wrapperCell.setBorder(Rectangle.NO_BORDER);
            table.addCell(wrapperCell);
        } catch (DocumentException e) {
            logger.error("Error adding legend row", e);
        }
    }

    private static class CheckStatistics {
        int categoryCount;
        String categoryRemark;
        int itemCount;
        String itemRemark;
    }

    private Map<String, Integer> countErrorsByTypeDetailed(JsonNode results) {
        Map<String, Integer> errorCounts = new HashMap<>();
        JsonNode errors = results.get("errors");
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String type = error.path("errorType").asText("");
                errorCounts.merge(type, 1, Integer::sum);
            }
        }
        return errorCounts;
    }

    private String getErrorTypeDisplayName(String errorType) {
        switch (errorType) {
            case "data_existence": return "数据条目存在性";
            case "folder_existence": return "文件夹存在性";
            case "file_count": return "文件数量匹配";
            case "empty_check": return "非空检查";
            case "unique_check": return "唯一性检查";
            case "empty_folder": return "空文件夹检查";
            case "page_sequence": return "页序号连续";
            case "file_sequential": return "文件序号连续";
            case "folder_sequential": return "文件夹序号连续";
            case "repeat_image": return "重复图像检查";
            case "date_format": return "日期格式检查";
            case "value_list": return "值列表检查";
            case "regex": return "正则表达式检查";
            case "regex_check": return "正则表达式检查";
            case "file_name_format": return "文件命名格式";
            case "folder_name_format": return "文件夹命名格式";
            case "dpi": return "DPI检查";
            case "bit_depth": return "位深度检查";
            case "file-size": return "文件大小检查";
            case "quality": return "图像质量检查";
            case "damage": return "损坏文件检查";
            case "illegal_file": return "非法文件检查";
            case "security_check": return "杀毒软件检测";
            case "blank": return "空白页检查";
            case "bias": return "倾斜检查";
            case "edge": return "黑边检查";
            case "stain": return "污点检查";
            case "hole": return "装订孔检查";
            case "format": return "图像格式检查";
            case "counting": return "数量统计";
            case "pageSize": return "尺幅大小统计";
            case "content_mismatch": return "内容比对检查";
            default: return errorType;
        }
    }

    private BaseColor getColorForIndex(int index) {
        BaseColor[] colors = {
            new BaseColor(231, 76, 60),
            new BaseColor(243, 156, 18),
            new BaseColor(52, 152, 219),
            new BaseColor(155, 89, 182),
            new BaseColor(46, 204, 113),
            new BaseColor(26, 188, 156),
            new BaseColor(241, 196, 15),
            new BaseColor(230, 126, 34),
            new BaseColor(52, 73, 94),
            new BaseColor(149, 165, 166)
        };
        return colors[index % colors.length];
    }
    
    private void addDataFormatChecks(Document document, com.itextpdf.text.Font font, JsonNode results, 
                                     QualityRulesConfig config, int totalRows) throws DocumentException {
        Map<String, FormatRule> formatRules = config.getFormatRules();
        if (formatRules == null || formatRules.isEmpty()) {
            document.add(new Paragraph("暂无数据格式检查规则配置", font));
            return;
        }
        
        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        
        String[] headers = {"列名/资源名", "检查项", "检查数量", "错误数", "错误率", "状态"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(3);
            table.addCell(cell);
        }
        
        for (Map.Entry<String, FormatRule> entry : formatRules.entrySet()) {
            String columnName = entry.getKey();
            FormatRule rule = entry.getValue();
            StringBuilder checkItems = new StringBuilder();
            
            if (rule.isNonEmpty()) {
                if (checkItems.length() > 0) checkItems.append(", ");
                checkItems.append("非空");
            }
            if (rule.isUnique()) {
                if (checkItems.length() > 0) checkItems.append(", ");
                checkItems.append("唯一性");
            }
            if (rule.getRegex() != null && !rule.getRegex().isEmpty()) {
                if (checkItems.length() > 0) checkItems.append(", ");
                checkItems.append("正则:").append(rule.getRegex());
            }
            if (rule.getDateFormat() != null && !rule.getDateFormat().isEmpty()) {
                if (checkItems.length() > 0) checkItems.append(", ");
                checkItems.append("日期格式:").append(rule.getDateFormat());
            }
            if (rule.getValueList() != null && !rule.getValueList().isEmpty()) {
                if (checkItems.length() > 0) checkItems.append(", ");
                checkItems.append("值列表(").append(rule.getValueList().size()).append("项)");
            }
            
            if (checkItems.length() == 0) {
                checkItems.append("无");
            }
            
            int errorCount = countFormatErrorsForColumn(results, columnName, rule);
            double errorRate = totalRows > 0 ? (errorCount * 100.0 / totalRows) : 0;
            String status = errorCount == 0 ? "✅" : "❌";
            
            PdfPCell cell1 = new PdfPCell(new Phrase(columnName, font));
            cell1.setPadding(3);
            table.addCell(cell1);
            
            PdfPCell cell2 = new PdfPCell(new Phrase(checkItems.toString(), font));
            cell2.setPadding(3);
            table.addCell(cell2);
            
            PdfPCell cell3 = new PdfPCell(new Phrase(String.valueOf(totalRows), font));
            cell3.setPadding(3);
            table.addCell(cell3);
            
            PdfPCell cell4 = new PdfPCell(new Phrase(String.valueOf(errorCount), font));
            cell4.setPadding(3);
            table.addCell(cell4);
            
            PdfPCell cell5 = new PdfPCell(new Phrase(String.format("%.1f%%", errorRate), font));
            cell5.setPadding(3);
            table.addCell(cell5);
            
            PdfPCell cell6 = new PdfPCell(new Phrase(status, font));
            cell6.setPadding(3);
            cell6.setBackgroundColor(errorCount > 0 ? new BaseColor(255, 220, 220) : BaseColor.WHITE);
            table.addCell(cell6);
        }
        
        document.add(table);
    }
    
    private int countFormatErrorsForColumn(JsonNode results, String columnName, FormatRule rule) {
        JsonNode errors = results.get("errors");
        int count = 0;
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String errorColumn = error.path("column").asText("");
                String errorType = error.path("errorType").asText("");
                if (columnName.equals(errorColumn)) {
                    if ("empty_check".equals(errorType) && rule.isNonEmpty()) {
                        count++;
                    } else if ("unique_check".equals(errorType) && rule.isUnique()) {
                        count++;
                    } else if ("regex_check".equals(errorType) && rule.getRegex() != null && !rule.getRegex().isEmpty()) {
                        count++;
                    } else if ("date_format".equals(errorType) && rule.getDateFormat() != null && !rule.getDateFormat().isEmpty()) {
                        count++;
                    } else if ("value_list".equals(errorType) && rule.getValueList() != null && !rule.getValueList().isEmpty()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
    
    private void addFileResourceChecks(Document document, com.itextpdf.text.Font font, JsonNode results,
                                       QualityRulesConfig config, int totalRows, int totalFolders, int totalFiles) throws DocumentException {
        ResourceCheckConfig resourceConfig = config.getResourceConfig();
        if (resourceConfig == null) {
            document.add(new Paragraph("暂无文件资源关联检查配置", font));
            return;
        }
        
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        
        String[] headers = {"检查项", "检查数量", "错误数", "错误率", "状态"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(3);
            table.addCell(cell);
        }
        
        ResourceCheckConfig.FolderChecks folderChecks = resourceConfig.getFolderChecks();
        if (folderChecks != null) {
            addFileResourceRow(table, results, "数据条目存在性", totalRows, "data_existence", font);
            addFileResourceRow(table, results, "文件夹存在性", totalFolders, "folder_existence", font);
            addFileResourceRow(table, results, "文件夹命名格式", totalFolders, "folder_name_format", font);
            addFileResourceRow(table, results, "文件夹序号连续", totalFolders, "folder_sequential", font);
            addFileResourceRow(table, results, "空文件夹检查", totalFolders, "empty_folder", font);
        }
        
        ResourceCheckConfig.FileChecks fileChecks = resourceConfig.getFileChecks();
        if (fileChecks != null) {
            addFileResourceRow(table, results, "文件数量一致", totalFiles, "file_count", font);
            addFileResourceRow(table, results, "文件名格式", totalFiles, "file_name_format", font);
            addFileResourceRow(table, results, "文件序号连续", totalFiles, "file_sequential", font);
        }
        
        document.add(table);
    }
    
    private void addFileResourceRow(PdfPTable table, JsonNode results, String checkName, 
                                    int checkCount, String errorType, com.itextpdf.text.Font font) {
        int errorCount = countErrorsByType(results, errorType);
        
        PdfPCell cell1 = new PdfPCell(new Phrase(checkName, font));
        cell1.setPadding(3);
        table.addCell(cell1);
        
        PdfPCell cell2 = new PdfPCell(new Phrase(String.valueOf(checkCount), font));
        cell2.setPadding(3);
        table.addCell(cell2);
        
        PdfPCell cell3 = new PdfPCell(new Phrase(String.valueOf(errorCount), font));
        cell3.setPadding(3);
        table.addCell(cell3);
        
        double errorRate = checkCount > 0 ? (errorCount * 100.0 / checkCount) : 0;
        PdfPCell cell4 = new PdfPCell(new Phrase(String.format("%.1f%%", errorRate), font));
        cell4.setPadding(3);
        table.addCell(cell4);
        
        String status = errorCount == 0 ? "✅" : "❌";
        PdfPCell cell5 = new PdfPCell(new Phrase(status, font));
        cell5.setPadding(3);
        cell5.setBackgroundColor(errorCount > 0 ? new BaseColor(255, 220, 220) : BaseColor.WHITE);
        table.addCell(cell5);
    }
    
    private void addContentComparisonChecks(Document document, com.itextpdf.text.Font font, JsonNode results,
                                            QualityRulesConfig config, int totalRows) throws DocumentException {
        List<ContentComparisonRule> contentRules = config.getContentRules();
        if (contentRules == null || contentRules.isEmpty()) {
            document.add(new Paragraph("暂无内容比对检查规则配置", font));
            return;
        }
        
        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        
        String[] headers = {"规则名称", "相似度阈值", "检查数量", "错误数", "错误率", "状态"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
            cell.setPadding(3);
            table.addCell(cell);
        }
        
        for (ContentComparisonRule rule : contentRules) {
            String ruleName = rule.getColumn() + "内容抽取比对";
            int errorCount = countContentMismatchErrors(results, rule.getColumn(), rule.getExtractLabel());
            int checkCount = totalRows;
            double errorRate = checkCount > 0 ? (errorCount * 100.0 / checkCount) : 0;
            String status = errorCount == 0 ? "✅" : "❌";
            
            PdfPCell cell1 = new PdfPCell(new Phrase(ruleName, font));
            cell1.setPadding(3);
            table.addCell(cell1);
            
            PdfPCell cell2 = new PdfPCell(new Phrase(rule.getThreshold() + "%", font));
            cell2.setPadding(3);
            table.addCell(cell2);
            
            PdfPCell cell3 = new PdfPCell(new Phrase(String.valueOf(checkCount), font));
            cell3.setPadding(3);
            table.addCell(cell3);
            
            PdfPCell cell4 = new PdfPCell(new Phrase(String.valueOf(errorCount), font));
            cell4.setPadding(3);
            table.addCell(cell4);
            
            PdfPCell cell5 = new PdfPCell(new Phrase(String.format("%.1f%%", errorRate), font));
            cell5.setPadding(3);
            table.addCell(cell5);
            
            PdfPCell cell6 = new PdfPCell(new Phrase(status, font));
            cell6.setPadding(3);
            cell6.setBackgroundColor(errorCount > 0 ? new BaseColor(255, 220, 220) : BaseColor.WHITE);
            table.addCell(cell6);
        }
        
        document.add(table);
    }
    
    private int countContentMismatchErrors(JsonNode results, String columnName, String extractLabel) {
        JsonNode errors = results.get("errors");
        int count = 0;
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String errorColumn = error.path("column").asText("");
                String errorType = error.path("errorType").asText("");
                if (columnName.equals(errorColumn) && "content_mismatch".equals(errorType)) {
                    String actualExtractLabel = error.path("extractLabel").asText("");
                    if (extractLabel.equals(actualExtractLabel)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}

