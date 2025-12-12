/*
 * Data Quality Extension - Export Quality Report Command
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
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
                exportPdf(response, results);
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
        int totalRows = 0, totalErrors = 0, formatErrors = 0, resourceErrors = 0, contentErrors = 0;

        if (summary != null) {
            totalRows = summary.path("totalRows").asInt(0);
            totalErrors = summary.path("totalErrors").asInt(0);
            formatErrors = summary.path("formatErrors").asInt(0);
            resourceErrors = summary.path("resourceErrors").asInt(0);
            contentErrors = summary.path("contentErrors").asInt(0);

            createStatRow(sheet, rowNum++, "总行数", totalRows);
            createStatRow(sheet, rowNum++, "总错误数", totalErrors);
            createStatRow(sheet, rowNum++, "格式错误", formatErrors);
            createStatRow(sheet, rowNum++, "资源错误", resourceErrors);
            createStatRow(sheet, rowNum++, "内容错误", contentErrors);
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

                row.createCell(0).setCellValue(error.path("rowIndex").asInt(0) + 1);
                row.createCell(1).setCellValue(getColumnDisplay(column, errorType));
                row.createCell(2).setCellValue(value.isEmpty() ? "(空)" : value);
                row.createCell(3).setCellValue(getErrorTypeLabel(errorType));
                row.createCell(4).setCellValue(translateErrorMessage(message, errorType));
                row.createCell(5).setCellValue(getCategoryLabel(error.path("category").asText("")));
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
            default: return category;
        }
    }

    /**
     * Translate error type to Chinese.
     */
    private String getErrorTypeLabel(String errorType) {
        if (errorType == null || errorType.isEmpty()) return "(空)";
        switch (errorType) {
            case "non_empty": return "非空检查";
            case "unique": return "唯一性检查";
            case "regex": return "格式检查";
            case "value_list": return "值列表检查";
            case "file_count": return "文件数量检查";
            case "file_sequential": return "文件序号连续";
            case "file_sequence": return "文件序号连续";
            case "folder_existence": return "文件夹存在检查";
            case "folder_sequential": return "文件夹顺序连续";
            case "folder_sequence": return "文件夹顺序连续";
            case "content_match": return "内容匹配检查";
            case "content_mismatch": return "内容不匹配";
            case "content_warning": return "内容相似度偏低";
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
        // Does not match pattern
        if (message.startsWith("Does not match pattern:")) {
            String pattern = message.substring("Does not match pattern:".length()).trim();
            return "不匹配格式：" + pattern;
        }

        return message;
    }

    /**
     * Get column display value - return "(空)" for empty or special values.
     */
    private String getColumnDisplay(String column, String errorType) {
        if (column == null || column.isEmpty()) return "(空)";
        // For resource check errors, column might be a path, show "(空)"
        if ("file_sequential".equals(errorType) || "folder_sequential".equals(errorType) ||
            "file_sequence".equals(errorType) || "folder_sequence".equals(errorType)) {
            return "(空)";
        }
        return column;
    }

    private void exportPdf(HttpServletResponse response, JsonNode results) throws IOException {
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=quality_report.pdf");

        try (OutputStream out = response.getOutputStream()) {
            Document document = new Document(PageSize.A4);
            PdfWriter.getInstance(document, out);
            document.open();

            // Chinese font
            BaseFont bfChinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(bfChinese, 18, com.itextpdf.text.Font.BOLD);
            com.itextpdf.text.Font headerFont = new com.itextpdf.text.Font(bfChinese, 12, com.itextpdf.text.Font.BOLD);
            com.itextpdf.text.Font normalFont = new com.itextpdf.text.Font(bfChinese, 10, com.itextpdf.text.Font.NORMAL);

            // Title
            Paragraph title = new Paragraph("数据质量检查报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            // Summary section
            JsonNode summary = results.get("summary");
            if (summary != null) {
                Paragraph summaryTitle = new Paragraph("检查摘要", headerFont);
                summaryTitle.setSpacingAfter(10);
                document.add(summaryTitle);

                PdfPTable summaryTable = new PdfPTable(2);
                summaryTable.setWidthPercentage(50);
                summaryTable.setHorizontalAlignment(Element.ALIGN_LEFT);

                addSummaryRow(summaryTable, "总行数", summary.path("totalRows").asInt(0), normalFont);
                addSummaryRow(summaryTable, "总错误数", summary.path("totalErrors").asInt(0), normalFont);
                addSummaryRow(summaryTable, "格式错误", summary.path("formatErrors").asInt(0), normalFont);
                addSummaryRow(summaryTable, "资源错误", summary.path("resourceErrors").asInt(0), normalFont);
                addSummaryRow(summaryTable, "内容错误", summary.path("contentErrors").asInt(0), normalFont);

                document.add(summaryTable);
                document.add(new Paragraph(" "));

                // Error distribution chart section
                int totalErrors = summary.path("totalErrors").asInt(0);
                int formatErrors = summary.path("formatErrors").asInt(0);
                int resourceErrors = summary.path("resourceErrors").asInt(0);
                int contentErrors = summary.path("contentErrors").asInt(0);

                if (totalErrors > 0) {
                    Paragraph chartTitle = new Paragraph("错误分类统计", headerFont);
                    chartTitle.setSpacingBefore(10);
                    chartTitle.setSpacingAfter(10);
                    document.add(chartTitle);

                    PdfPTable chartTable = new PdfPTable(4);
                    chartTable.setWidthPercentage(80);
                    chartTable.setHorizontalAlignment(Element.ALIGN_LEFT);

                    // Header
                    String[] chartHeaders = {"错误类型", "数量", "占比", "分布"};
                    for (String h : chartHeaders) {
                        PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                        cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
                        cell.setPadding(5);
                        chartTable.addCell(cell);
                    }

                    // Data rows with percentage bars
                    addChartRow(chartTable, "数据格式检查", formatErrors, totalErrors, normalFont, new BaseColor(66, 133, 244));
                    addChartRow(chartTable, "文件资源关联检查", resourceErrors, totalErrors, normalFont, new BaseColor(251, 188, 4));
                    addChartRow(chartTable, "内容比对检查", contentErrors, totalErrors, normalFont, new BaseColor(52, 168, 83));

                    document.add(chartTable);
                    document.add(new Paragraph(" "));
                }
            }

            // Errors section
            JsonNode errors = results.get("errors");
            if (errors != null && errors.isArray() && errors.size() > 0) {
                Paragraph errorsTitle = new Paragraph("错误详情", headerFont);
                errorsTitle.setSpacingBefore(20);
                errorsTitle.setSpacingAfter(10);
                document.add(errorsTitle);

                PdfPTable errorsTable = new PdfPTable(6);
                errorsTable.setWidthPercentage(100);
                errorsTable.setWidths(new float[]{8, 15, 20, 15, 27, 15});

                // Header
                String[] headers = {"行号", "列名", "值", "错误类型", "错误信息", "分类"};
                for (String h : headers) {
                    PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                    cell.setBackgroundColor(BaseColor.LIGHT_GRAY);
                    cell.setPadding(5);
                    errorsTable.addCell(cell);
                }

                // Data rows (limit to 100 for PDF)
                int count = 0;
                for (JsonNode error : errors) {
                    if (count++ >= 100) break;
                    String errorType = error.path("errorType").asText("");
                    String column = error.path("column").asText("");
                    String message = error.path("message").asText("");
                    String value = error.path("value").asText("");

                    errorsTable.addCell(new Phrase(String.valueOf(error.path("rowIndex").asInt(0) + 1), normalFont));
                    errorsTable.addCell(new Phrase(getColumnDisplay(column, errorType), normalFont));
                    errorsTable.addCell(new Phrase(truncate(value.isEmpty() ? "(空)" : value, 30), normalFont));
                    errorsTable.addCell(new Phrase(getErrorTypeLabel(errorType), normalFont));
                    errorsTable.addCell(new Phrase(truncate(translateErrorMessage(message, errorType), 40), normalFont));
                    errorsTable.addCell(new Phrase(getCategoryLabel(error.path("category").asText("")), normalFont));
                }

                document.add(errorsTable);

                if (errors.size() > 100) {
                    Paragraph note = new Paragraph("注：仅显示前100条错误，完整列表请导出Excel", normalFont);
                    note.setSpacingBefore(10);
                    document.add(note);
                }
            }

            document.close();
        } catch (DocumentException e) {
            logger.error("Error creating PDF", e);
            throw new IOException("Failed to create PDF: " + e.getMessage());
        }
    }

    private void addSummaryRow(PdfPTable table, String label, int value, com.itextpdf.text.Font font) {
        table.addCell(new Phrase(label, font));
        table.addCell(new Phrase(String.valueOf(value), font));
    }

    /**
     * Add a chart row with percentage bar visualization.
     */
    private void addChartRow(PdfPTable table, String label, int count, int total,
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
        double percentage = total > 0 ? (count * 100.0 / total) : 0;
        String percentText = String.format("%.1f%%", percentage);
        PdfPCell percentCell = new PdfPCell(new Phrase(percentText, font));
        percentCell.setPadding(5);
        percentCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(percentCell);

        // Visual bar (simplified as colored cell with text)
        PdfPCell barCell = new PdfPCell();
        barCell.setPadding(5);
        if (count > 0 && total > 0) {
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
}

