/*
 * Data Quality Extension - Content Checker
 * Compares data column values with OCR extracted content using batch AIMP API
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.aimp.AimpClient;
import com.google.refine.extension.quality.aimp.AimpClient.BatchCompareResult;
import com.google.refine.extension.quality.aimp.AimpClient.ElementResult;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.ContentComparisonRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.QualityRulesConfig.AimpConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

/**
 * Checker for content comparison between data columns and OCR extracted values.
 * Uses batch AIMP API with configurable batch size for progress tracking.
 */
public class ContentChecker {

    private static final Logger logger = LoggerFactory.getLogger(ContentChecker.class);

    // Element key mapping: Chinese label -> English API key
    private static final Map<String, String> ELEMENT_KEY_MAP = new HashMap<>();
    static {
        ELEMENT_KEY_MAP.put("题名", "title");
        ELEMENT_KEY_MAP.put("责任者", "responsible_party");
        ELEMENT_KEY_MAP.put("文号", "document_number");
        ELEMENT_KEY_MAP.put("成文日期", "issue_date");
    }

    private final Project project;
    private final QualityRulesConfig rules;
    private final AimpClient aimpClient;
    private final AimpConfig aimpConfig;
    private QualityCheckTask task;

    public ContentChecker(Project project, QualityRulesConfig rules, String aimpServiceUrl) {
        this.project = project;
        this.rules = rules;
        this.aimpClient = new AimpClient(aimpServiceUrl);
        this.aimpConfig = rules.getAimpConfig() != null ? rules.getAimpConfig() : new AimpConfig();
    }

    public void setTask(QualityCheckTask task) {
        this.task = task;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("content");
        List<ContentComparisonRule> contentRules = rules.getContentRules();
        ResourceCheckConfig resourceConfig = rules.getResourceConfig();

        logger.info("Content check started. Rules count: " + (contentRules != null ? contentRules.size() : 0));

        if (contentRules == null || contentRules.isEmpty()) {
            logger.info("No content rules configured, skipping content check");
            result.complete();
            return result;
        }

        // Check AIMP connection
        if (!aimpClient.testConnection()) {
            logger.warn("AIMP service not available, skipping content check");
            result.complete();
            return result;
        }

        int totalRows = project.rows.size();
        result.setTotalRows(totalRows);

        // Build column index map
        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        // Collect valid rows with resource paths
        List<RowData> validRows = collectValidRows(columnIndexMap, resourceConfig);
        logger.info("Found " + validRows.size() + " valid rows with resources out of " + totalRows);

        if (validRows.isEmpty()) {
            logger.info("No valid rows to process");
            result.complete();
            return result;
        }

        // Update task progress info
        if (task != null) {
            task.setContentCheckTotal(validRows.size());
        }

        // Get batch size from config (default 1)
        int batchSize = aimpConfig.getBatchSize() > 0 ? aimpConfig.getBatchSize() : 1;
        double similarityThreshold = aimpConfig.getSimilarityThreshold();
        double confidenceThreshold = aimpConfig.getConfidenceThreshold();

        logger.info("Using batch size: " + batchSize + ", similarity threshold: " + similarityThreshold);

        // Build element list from rules
        List<String> elements = contentRules.stream()
                .map(rule -> ELEMENT_KEY_MAP.getOrDefault(rule.getExtractLabel(), rule.getExtractLabel()))
                .distinct()
                .collect(Collectors.toList());
        logger.info("Elements to extract: " + elements);

        // Process in batches
        String taskId = "content-check-" + project.id + "-" + System.currentTimeMillis();
        int processedCount = 0;

        for (int i = 0; i < validRows.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, validRows.size());
            List<RowData> batch = validRows.subList(i, endIndex);

            logger.info("Processing batch " + (i / batchSize + 1) + ": rows " + i + " to " + (endIndex - 1));

            // Prepare batch data
            List<Map<String, Object>> excelData = prepareBatchExcelData(batch, columnIndexMap, contentRules);
            List<Map<String, Object>> imageData = prepareBatchImageData(batch);

            // Call AIMP batch compare
            BatchCompareResult batchResult = aimpClient.batchCompare(
                    taskId, excelData, imageData, elements, confidenceThreshold, similarityThreshold);

            // Process results
            if (batchResult.isSuccess() && batchResult.getComparisonResult() != null) {
                processComparisonResults(result, batchResult, batch, contentRules, columnIndexMap);
            } else {
                logger.warn("Batch compare failed: " + batchResult.getError());
            }

            // Update progress
            processedCount += batch.size();
            if (task != null) {
                for (int j = 0; j < batch.size(); j++) {
                    task.incrementContentCheckProcessed();
                }
            }
            logger.info("Content check progress: " + processedCount + "/" + validRows.size());
        }

        logger.info("Content check completed. Errors: " + result.getErrors().size());
        result.complete();
        return result;
    }

    /**
     * Collect valid rows that have existing resource folders with images
     */
    private List<RowData> collectValidRows(Map<String, Integer> columnIndexMap, ResourceCheckConfig resourceConfig) {
        List<RowData> validRows = new ArrayList<>();
        int totalRows = project.rows.size();

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            Row row = project.rows.get(rowIndex);
            String resourcePath = buildResourcePath(row, columnIndexMap, resourceConfig);

            if (resourcePath == null || resourcePath.isEmpty()) {
                continue;
            }

            File folder = new File(resourcePath);
            if (!folder.exists() || !folder.isDirectory()) {
                continue;
            }

            // Get image files in folder
            File[] imageFiles = folder.listFiles((dir, name) -> {
                String lowerName = name.toLowerCase();
                return lowerName.endsWith(".pdf") || lowerName.endsWith(".jpg") ||
                       lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
                       lowerName.endsWith(".tif") || lowerName.endsWith(".tiff") ||
                       lowerName.endsWith(".bmp") || lowerName.endsWith(".gif") ||
                       lowerName.endsWith(".webp");
            });

            if (imageFiles != null && imageFiles.length > 0) {
                // Sort files to ensure consistent ordering
                Arrays.sort(imageFiles);
                List<String> imageNames = Arrays.stream(imageFiles)
                        .map(File::getName)
                        .collect(Collectors.toList());

                validRows.add(new RowData(rowIndex, row, resourcePath, imageNames));

                if (rowIndex < 3) {
                    logger.info("Row " + rowIndex + ": path=" + resourcePath + ", images=" + imageNames.size());
                }
            }
        }

        return validRows;
    }

    /**
     * Prepare Excel data for batch API call
     */
    private List<Map<String, Object>> prepareBatchExcelData(
            List<RowData> batch,
            Map<String, Integer> columnIndexMap,
            List<ContentComparisonRule> contentRules) {

        List<Map<String, Object>> excelData = new ArrayList<>();

        for (RowData rowData : batch) {
            Map<String, Object> rowMap = new HashMap<>();
            rowMap.put("dataKey", String.valueOf(rowData.rowIndex));
            rowMap.put("rowNum", rowData.rowIndex + 1); // 1-based for display

            // Add element values from row
            for (ContentComparisonRule rule : contentRules) {
                String columnName = rule.getColumn();
                String elementKey = ELEMENT_KEY_MAP.getOrDefault(rule.getExtractLabel(), rule.getExtractLabel());

                Integer cellIndex = columnIndexMap.get(columnName);
                if (cellIndex != null) {
                    Cell cell = rowData.row.getCell(cellIndex);
                    String value = cell != null && cell.value != null ? cell.value.toString().trim() : "";
                    rowMap.put(elementKey, value);
                }
            }

            excelData.add(rowMap);
        }

        return excelData;
    }

    /**
     * Prepare image data for batch API call
     */
    private List<Map<String, Object>> prepareBatchImageData(List<RowData> batch) {
        List<Map<String, Object>> imageData = new ArrayList<>();

        for (RowData rowData : batch) {
            Map<String, Object> imageMap = new HashMap<>();
            imageMap.put("path", rowData.resourcePath);
            imageMap.put("dataKey", String.valueOf(rowData.rowIndex));
            imageMap.put("imageNames", String.join(",", rowData.imageNames));
            imageMap.put("imageCount", rowData.imageNames.size());
            imageData.add(imageMap);
        }

        return imageData;
    }

    /**
     * Process comparison results from AIMP and add errors to result
     */
    private void processComparisonResults(
            CheckResult result,
            BatchCompareResult batchResult,
            List<RowData> batch,
            List<ContentComparisonRule> contentRules,
            Map<String, Integer> columnIndexMap) {

        Map<String, Map<String, ElementResult>> comparisonResult = batchResult.getComparisonResult();

        for (RowData rowData : batch) {
            String dataKey = String.valueOf(rowData.rowIndex);
            Map<String, ElementResult> rowResults = comparisonResult.get(dataKey);

            if (rowResults == null) {
                // Try with rowNum suffix format (dataKey_rowNum)
                rowResults = comparisonResult.get(dataKey + "_" + (rowData.rowIndex + 1));
            }

            if (rowResults == null) {
                logger.debug("No results for row " + rowData.rowIndex);
                continue;
            }

            // Check each rule
            for (ContentComparisonRule rule : contentRules) {
                String elementKey = ELEMENT_KEY_MAP.getOrDefault(rule.getExtractLabel(), rule.getExtractLabel());
                ElementResult elemResult = rowResults.get(elementKey);

                if (elemResult != null && elemResult.isHasError()) {
                    String columnName = rule.getColumn();
                    String errorType = elemResult.getSimilarity() < 50 ? "content_mismatch" : "content_warning";
                    String message = String.format("相似度 %.1f%% < 阈值 %d%% (抽取值: %s)",
                            elemResult.getSimilarity() * 100, rule.getThreshold(), elemResult.getExtractedValue());

                    result.addError(new CheckError(rowData.rowIndex, columnName,
                            elemResult.getExcelValue(), errorType, message, elemResult.getExtractedValue()));
                }
            }
        }
    }

    private String buildResourcePath(Row row, Map<String, Integer> columnIndexMap, ResourceCheckConfig config) {
        if (config == null) return null;

        String basePath = config.getBasePath();
        List<String> pathFields = config.getPathFields();
        String pathMode = config.getPathMode();
        String separator = config.getSeparator();
        String template = config.getTemplate();

        if (pathFields == null || pathFields.isEmpty()) return basePath;

        String sep = (separator != null && !separator.isEmpty()) ? separator : File.separator;

        // Get field values
        List<String> values = new ArrayList<>();
        for (String fieldName : pathFields) {
            Integer cellIndex = columnIndexMap.get(fieldName);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                String value = cell != null && cell.value != null ? cell.value.toString() : "";
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }

        if (values.isEmpty()) return basePath;

        StringBuilder path = new StringBuilder();

        // Add base path
        if (basePath != null && !basePath.isEmpty()) {
            path.append(basePath);
            if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
                path.append(sep);
            }
        }

        if ("template".equals(pathMode) && template != null && !template.isEmpty()) {
            String pathResult = template;
            for (int i = 0; i < values.size(); i++) {
                pathResult = pathResult.replace("{" + i + "}", values.get(i));
            }
            path.append(pathResult);
        } else {
            // Separator mode - join values with separator
            path.append(String.join(sep, values));
        }

        return path.toString();
    }

    /**
     * Internal class to hold row data during processing
     */
    private static class RowData {
        final int rowIndex;
        final Row row;
        final String resourcePath;
        final List<String> imageNames;

        RowData(int rowIndex, Row row, String resourcePath, List<String> imageNames) {
            this.rowIndex = rowIndex;
            this.row = row;
            this.resourcePath = resourcePath;
            this.imageNames = imageNames;
        }
    }
}

