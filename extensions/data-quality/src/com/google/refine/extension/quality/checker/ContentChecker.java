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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.aimp.AimpClient;
import com.google.refine.extension.quality.aimp.AimpClient.BatchCompareResult;
import com.google.refine.extension.quality.aimp.AimpClient.ElementResult;
import com.google.refine.extension.quality.aimp.AimpClient.LlmAnalyzeResult;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.ContentComparisonRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.QualityRulesConfig.AimpConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.util.ColumnSemantics;
import com.google.refine.extension.quality.util.ColumnSemantics.Slot;
import com.google.refine.extension.quality.util.ResourceSelector;
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

    /** 列头未匹配时写入 serviceUnavailableMessage 的前缀，前端据此渲染国际化提示 */
    public static final String UNMATCHED_COLUMNS_PREFIX = "UNMATCHED_COLUMNS:";

    // Element key mapping: Chinese label -> English API key
    private static final Map<String, String> ELEMENT_KEY_MAP = new HashMap<>();
    static {
        ELEMENT_KEY_MAP.put("题名", "title");
        ELEMENT_KEY_MAP.put("责任者", "responsible_party");
        ELEMENT_KEY_MAP.put("文号", "document_number");
        ELEMENT_KEY_MAP.put("成文日期", "issue_date");
        // 规则界面默认模板的 extractLabel 是「成文时间」，漏映射会让该键原样下发到 AIMP，
        // 与 AIMP 侧按要素键做的日期降权/归一化对不上（降权会被静默跳过）
        ELEMENT_KEY_MAP.put("成文时间", "issue_date");
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

        // 列头归一化：规则列名解析不到实际列时必须阻断，
        // 否则该要素不参与比对，会产出「无错误」的假通过，掩盖真正的比对错位
        List<String> columnNames = new ArrayList<>();
        for (Column col : project.columnModel.columns) {
            columnNames.add(col.getName());
        }
        List<String> unmatchedColumns = new ArrayList<>();
        for (ContentComparisonRule rule : contentRules) {
            if (ColumnSemantics.resolveColumnName(rule.getColumn(), columnNames) == null) {
                unmatchedColumns.add(rule.getColumn());
            }
        }
        if (!unmatchedColumns.isEmpty()) {
            logger.error("Content comparison columns not matched: " + unmatchedColumns
                    + ", project columns: " + columnNames);
            result.setServiceUnavailable(true);
            result.setServiceUnavailableMessage(UNMATCHED_COLUMNS_PREFIX + String.join("、", unmatchedColumns));
            result.complete();
            return result;
        }

        // Check AIMP connection
        if (!aimpClient.testConnection()) {
            logger.warn("AIMP service not available, skipping content check");
            result.complete();
            return result;
        }

        // Check for multi-sheet scenario and perform volume title check
        if (isMultiSheetProject()) {
            logger.info("Multi-sheet project detected, checking for volume title comparison");
            CheckResult volumeTitleResult = checkVolumeTitleComparison(contentRules);
            if (volumeTitleResult != null) {
                // Merge volume title check results
                for (CheckError error : volumeTitleResult.getErrors()) {
                    result.addError(error);
                }
                logger.info("Volume title comparison completed. Errors: " + volumeTitleResult.getErrors().size());
            }
        }

        int totalRows = project.rows.size();
        result.setTotalRows(totalRows);

        // Build column index map
        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        // Collect valid rows with resource paths
        List<RowData> validRows = collectValidRows(columnNames, columnIndexMap, resourceConfig);
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

        // Get start index from checkpoint if resuming
        int startIndex = 0;
        if (task != null && task.getCheckpoint() != null && "内容检查".equals(task.getCheckpoint().getLastPhase())) {
            startIndex = task.getCheckpoint().getContentCheckProcessed();
            processedCount = startIndex;
            logger.info("Resuming content check from index " + startIndex);
        }

        for (int i = startIndex; i < validRows.size(); i += batchSize) {
            // Check for task interruption
            if (task != null && task.shouldStop()) {
                logger.info("Content check interrupted at index " + i);
                task.setContentCheckpoint(i);
                return result;
            }

            // Wait if paused
            while (task != null && task.isPaused() && !task.shouldStop()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            int endIndex = Math.min(i + batchSize, validRows.size());
            List<RowData> batch = validRows.subList(i, endIndex);

            logger.info("Processing batch " + (i / batchSize + 1) + ": rows " + i + " to " + (endIndex - 1));

            // 上报「正在处理的件」：AIMP 以件为原子单位同步处理，件内全部页完成前没有任何信号，
            // 界面据此显示件名、本件页数与已耗时，避免长时间无变化被误判为卡死。
            if (task != null && !batch.isEmpty()) {
                RowData currentItem = batch.get(0);
                task.setContentCheckCurrentItem(currentItem.dataKey != null
                        ? currentItem.dataKey : ("第 " + (currentItem.rowIndex + 1) + " 行"));
                task.setContentCheckCurrentItemPages(currentItem.imageNames.size());
            }

            // Prepare batch data
            List<Map<String, Object>> excelData = prepareBatchExcelData(batch, columnNames, columnIndexMap, contentRules);
            List<Map<String, Object>> imageData = prepareBatchImageData(batch);

            // Call AIMP batch compare
            BatchCompareResult batchResult = aimpClient.batchCompare(
                    taskId, excelData, imageData, elements, confidenceThreshold, similarityThreshold);

            // 本件已回包，清空「正在处理」标记，界面回到「已完成 X/N 件」
            if (task != null) {
                task.setContentCheckCurrentItem(null);
                task.setContentCheckCurrentItemPages(0);
            }

            // Process results
            if (batchResult.isSuccess() && batchResult.getComparisonResult() != null) {
                processComparisonResults(result, batchResult, batch, columnNames, contentRules);
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
     * 收集有效行并**按件确定本行的资源文件清单**。
     *
     * 两种数据来源的资源粒度不同：
     *  - 人工录入 + 批量导入：一行 → 一个文件夹 → 文件夹下全部图片（整目录成件）；
     *  - 案卷级自动提取：同一卷下按题名自动分件，同一卷的 N 件共用同一个「文件夹路径」，
     *    必须按每件的页区间（起止页号 / 起始页号+终止页号 / 起始页号+页数）切出本件页清单，
     *    否则每件都会拿到整卷全部页，比对结果整体错位。
     */
    private List<RowData> collectValidRows(List<String> columnNames,
                                           Map<String, Integer> columnIndexMap,
                                           ResourceCheckConfig resourceConfig) {
        List<RowData> validRows = new ArrayList<>();
        int totalRows = project.rows.size();

        String volumeColumn = ColumnSemantics.findColumnNameBySlot(Slot.VOLUME_NO, columnNames);
        String pieceColumn = ColumnSemantics.findColumnNameBySlot(Slot.PIECE_NO, columnNames);
        String rangeColumn = ColumnSemantics.findColumnNameBySlot(Slot.PAGE_RANGE, columnNames);
        String startColumn = ColumnSemantics.findColumnNameBySlot(Slot.START_PAGE, columnNames);
        String endColumn = ColumnSemantics.findColumnNameBySlot(Slot.END_PAGE, columnNames);
        ResourceSelector.PageColumns pageColumns = ResourceSelector.resolvePageColumns(columnNames);

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            Row row = project.rows.get(rowIndex);
            String resourcePath = buildResourcePath(row, columnNames, columnIndexMap, resourceConfig);

            if (resourcePath == null || resourcePath.isEmpty()) {
                continue;
            }

            // 本件页区间（无页号信息时为 null，表示整目录成件）
            int[] range = pageColumns.resolveRange(row, columnIndexMap);
            String fileName = ResourceSelector.cellValue(row, columnIndexMap, pageColumns.file);
            List<File> pieceFiles = ResourceSelector.resolvePieceFiles(resourcePath, fileName, range);
            if (pieceFiles == null || pieceFiles.isEmpty()) {
                if (range != null) {
                    logger.warn("Row " + rowIndex + ": page range " + range[0] + "-" + range[1]
                            + " cannot be resolved under " + resourcePath + ", skipped");
                }
                continue;
            }

            List<String> imageFiles = new ArrayList<>();
            List<String> imageNames = new ArrayList<>();
            for (File f : pieceFiles) {
                imageFiles.add(f.getAbsolutePath());
                imageNames.add(f.getName());
            }

            validRows.add(new RowData(rowIndex, row, resourcePath, imageFiles, imageNames,
                    buildDataKey(row, columnIndexMap, volumeColumn, pieceColumn, resourcePath,
                            rangeColumn, startColumn, endColumn)));

            if (rowIndex < 3) {
                logger.info("Row " + rowIndex + ": path=" + resourcePath + ", files=" + imageNames.size()
                        + ", range=" + (range == null ? "whole" : range[0] + "-" + range[1]));
            }
        }

        return validRows;
    }

    /**
     * 比对结果的对齐键。用「案卷号|件号」替代行号：
     * 行号只在单项目内有效，且批量提取与人工录入的行序不一致，会造成错位。
     * 无件号时回退「文件夹路径|起始页」，仍无则回退行号。
     */
    private String buildDataKey(Row row, Map<String, Integer> columnIndexMap,
                                String volumeColumn, String pieceColumn, String resourcePath,
                                String rangeColumn, String startColumn, String endColumn) {
        String volumeNo = ResourceSelector.cellValue(row, columnIndexMap, volumeColumn);
        String pieceNo = ResourceSelector.cellValue(row, columnIndexMap, pieceColumn);
        if (!pieceNo.isEmpty()) {
            return volumeNo.isEmpty() ? pieceNo : volumeNo + "|" + pieceNo;
        }
        if (resourcePath != null && !resourcePath.isEmpty()) {
            Integer start = ResourceSelector.parseInt(ResourceSelector.cellValue(row, columnIndexMap, startColumn));
            if (start == null) {
                int[] range = ColumnSemantics.parsePageRange(
                        ResourceSelector.cellValue(row, columnIndexMap, rangeColumn));
                if (range != null) start = range[0];
            }
            if (start == null) {
                start = ResourceSelector.parseInt(ResourceSelector.cellValue(row, columnIndexMap, endColumn));
            }
            return resourcePath + "|" + (start == null ? "" : start);
        }
        return null;
    }

    /**
     * Prepare Excel data for batch API call
     */
    private List<Map<String, Object>> prepareBatchExcelData(
            List<RowData> batch,
            List<String> columnNames,
            Map<String, Integer> columnIndexMap,
            List<ContentComparisonRule> contentRules) {

        List<Map<String, Object>> excelData = new ArrayList<>();

        for (RowData rowData : batch) {
            Map<String, Object> rowMap = new HashMap<>();
            rowMap.put("dataKey", rowData.dataKey);
            rowMap.put("rowNum", rowData.rowIndex + 1); // 1-based for display

            // Add element values from row
            for (ContentComparisonRule rule : contentRules) {
                String columnName = ColumnSemantics.resolveColumnName(rule.getColumn(), columnNames);
                String elementKey = ELEMENT_KEY_MAP.getOrDefault(rule.getExtractLabel(), rule.getExtractLabel());

                Integer cellIndex = columnName == null ? null : columnIndexMap.get(columnName);
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
     * Prepare image data for batch API call.
     * imageFiles 为本件切分后的绝对路径清单，AIMP 侧优先使用它，避免同卷多件共用目录时相互覆盖。
     */
    private List<Map<String, Object>> prepareBatchImageData(List<RowData> batch) {
        List<Map<String, Object>> imageData = new ArrayList<>();

        for (RowData rowData : batch) {
            Map<String, Object> imageMap = new HashMap<>();
            imageMap.put("path", rowData.resourcePath);
            imageMap.put("dataKey", rowData.dataKey);
            imageMap.put("imageNames", String.join(",", rowData.imageNames));
            imageMap.put("imageCount", rowData.imageNames.size());
            imageMap.put("imageFiles", rowData.imageFiles);
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
            List<String> columnNames,
            List<ContentComparisonRule> contentRules) {

        Map<String, Map<String, ElementResult>> comparisonResult = batchResult.getComparisonResult();

        for (RowData rowData : batch) {
            String dataKey = rowData.dataKey;
            Map<String, ElementResult> rowResults = dataKey == null ? null : comparisonResult.get(dataKey);

            if (rowResults == null) {
                // Try with rowNum suffix format (dataKey_rowNum)
                rowResults = comparisonResult.get(String.valueOf(rowData.rowIndex));
            }
            if (rowResults == null) {
                rowResults = comparisonResult.get(rowData.rowIndex + "_" + (rowData.rowIndex + 1));
            }

            if (rowResults == null) {
                logger.debug("No results for dataKey " + dataKey);
                continue;
            }

            // Check each rule
            for (ContentComparisonRule rule : contentRules) {
                String elementKey = ELEMENT_KEY_MAP.getOrDefault(rule.getExtractLabel(), rule.getExtractLabel());
                ElementResult elemResult = rowResults.get(elementKey);

                if (elemResult == null) {
                    continue;
                }

                // 最终判定以「规则级相似度阈值」为准：该阈值由内容比对规则界面按要素设置（0~100）。
                // aimpConfig.similarityThreshold 没有界面入口、恒为默认值，只能作为下发给 AIMP 的参考值，
                // 不能作为判定依据，否则界面上配置的阈值形同虚设。
                double similarityPercent = elemResult.getSimilarity() * 100;
                if (similarityPercent >= rule.getThreshold()) {
                    continue;
                }

                // 用项目实际列名标记错误，前端才能定位到单元格
                String columnName = ColumnSemantics.resolveColumnName(rule.getColumn(), columnNames);
                if (columnName == null) {
                    columnName = rule.getColumn();
                }
                // 相似度低于 50% 视为严重不符，否则视为疑似差异（原写法误用百分数导致该分支恒为 mismatch）
                String errorType = similarityPercent < 50 ? "content_mismatch" : "content_warning";
                String message = String.format("相似度 %.1f%% < 阈值 %d%% (抽取值: %s)",
                        similarityPercent, rule.getThreshold(), elemResult.getExtractedValue());

                result.addError(new CheckError(rowData.rowIndex, columnName,
                        elemResult.getExcelValue(), errorType, message, elemResult.getExtractedValue()));
            }
        }
    }

    private String buildResourcePath(Row row, List<String> columnNames,
                                     Map<String, Integer> columnIndexMap, ResourceCheckConfig config) {
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
            // 列名归一化：人工录入用「源路径」、批量提取用「文件夹路径」，语义相同
            String resolvedField = ColumnSemantics.resolveColumnName(fieldName, columnNames);
            Integer cellIndex = resolvedField == null ? null : columnIndexMap.get(resolvedField);
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
     * Check if project is multi-sheet
     */
    private boolean isMultiSheetProject() {
        try {
            Object projectMetadataObj = project.getMetadata().getCustomMetadata("project");
            if (projectMetadataObj != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode projectMetadata = mapper.valueToTree(projectMetadataObj);
                if (projectMetadata.has("sheetDataMap")) {
                    JsonNode sheetDataMap = projectMetadata.get("sheetDataMap");
                    return sheetDataMap.size() > 1;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to check multi-sheet status", e);
        }
        return false;
    }

    /**
     * Check volume title comparison for multi-sheet scenario
     * Compares volume title with all item titles in the same volume
     */
    private CheckResult checkVolumeTitleComparison(List<ContentComparisonRule> contentRules) {
        CheckResult result = new CheckResult("volume_title_comparison");

        try {
            // Find title rule
            ContentComparisonRule titleRule = contentRules.stream()
                    .filter(rule -> "题名".equals(rule.getExtractLabel()))
                    .findFirst()
                    .orElse(null);

            if (titleRule == null) {
                logger.info("No title rule found, skipping volume title comparison");
                return null;
            }

            // Check if rule has cross_sheet check type
            if (!"cross_sheet".equals(titleRule.getCheckType())) {
                logger.info("Title rule is not cross_sheet type, skipping volume title comparison");
                return null;
            }

            // Find volume sheet (has "卷号" column)
            String volumeSheetId = findSheetWithColumn("卷号");
            if (volumeSheetId == null) {
                logger.info("No volume sheet found (missing '卷号' column)");
                return null;
            }

            // Find item sheet (has both "卷号" and "件号" columns)
            String itemSheetId = findSheetWithColumns(Arrays.asList("卷号", "件号"));
            if (itemSheetId == null) {
                logger.info("No item sheet found (missing '卷号' or '件号' columns)");
                return null;
            }

            logger.info("Volume sheet: " + volumeSheetId + ", Item sheet: " + itemSheetId);

            // Get volume title column index
            Integer volumeTitleColumnIndex = findColumnIndex(volumeSheetId, "题名");
            if (volumeTitleColumnIndex == null) {
                logger.warn("No '题名' column found in volume sheet");
                return null;
            }

            // Get volume number column index in volume sheet
            Integer volumeVolumeNumberColumnIndex = findColumnIndex(volumeSheetId, "卷号");
            if (volumeVolumeNumberColumnIndex == null) {
                logger.warn("No '卷号' column found in volume sheet");
                return null;
            }

            // Get item title column index
            Integer itemTitleColumnIndex = findColumnIndex(itemSheetId, "题名");
            if (itemTitleColumnIndex == null) {
                logger.warn("No '题名' column found in item sheet");
                return null;
            }

            // Get volume number column index in item sheet
            Integer itemVolumeNumberColumnIndex = findColumnIndex(itemSheetId, "卷号");
            if (itemVolumeNumberColumnIndex == null) {
                logger.warn("No '卷号' column found in item sheet");
                return null;
            }

            // Collect all volume titles and their corresponding item titles
            Map<String, List<String>> volumeTitleToItemTitlesMap = new HashMap<>();
            for (int rowIndex = 0; rowIndex < project.rows.size(); rowIndex++) {
                Row row = project.rows.get(rowIndex);

                // Get volume number from item sheet
                Cell volumeNumberCell = row.getCell(itemVolumeNumberColumnIndex);
                if (volumeNumberCell == null || volumeNumberCell.value == null) {
                    continue;
                }
                String volumeNumber = volumeNumberCell.value.toString().trim();
                if (volumeNumber.isEmpty()) {
                    continue;
                }

                // Get item title
                Cell itemTitleCell = row.getCell(itemTitleColumnIndex);
                if (itemTitleCell == null || itemTitleCell.value == null) {
                    continue;
                }
                String itemTitle = itemTitleCell.value.toString().trim();
                if (itemTitle.isEmpty()) {
                    continue;
                }

                // Add to map
                if (!volumeTitleToItemTitlesMap.containsKey(volumeNumber)) {
                    volumeTitleToItemTitlesMap.put(volumeNumber, new ArrayList<>());
                }
                volumeTitleToItemTitlesMap.get(volumeNumber).add(itemTitle);
            }

            logger.info("Found " + volumeTitleToItemTitlesMap.size() + " volumes with item titles");

            // Check each volume title against its item titles
            for (Map.Entry<String, List<String>> entry : volumeTitleToItemTitlesMap.entrySet()) {
                String volumeNumber = entry.getKey();
                List<String> itemTitles = entry.getValue();

                // Find volume title row by matching volume number in volume sheet
                String volumeTitle = null;
                int volumeTitleRowIndex = -1;
                for (int rowIndex = 0; rowIndex < project.rows.size(); rowIndex++) {
                    Row row = project.rows.get(rowIndex);
                    Cell volumeNumberCell = row.getCell(volumeVolumeNumberColumnIndex);
                    if (volumeNumberCell != null && volumeNumberCell.value != null) {
                        String cellValue = volumeNumberCell.value.toString().trim();
                        if (volumeNumber.equals(cellValue)) {
                            // Found matching volume number, now get the title
                            Cell titleCell = row.getCell(volumeTitleColumnIndex);
                            if (titleCell != null && titleCell.value != null) {
                                volumeTitle = titleCell.value.toString().trim();
                                volumeTitleRowIndex = rowIndex;
                                break;
                            }
                        }
                    }
                }

                if (volumeTitle == null) {
                    logger.warn("No volume title found for volume number: " + volumeNumber);
                    continue;
                }

                // Build prompt for LLM
                StringBuilder promptBuilder = new StringBuilder();
                promptBuilder.append("请判断以下案卷题名是否与卷内题名集合匹配：\n\n");
                promptBuilder.append("案卷题名：").append(volumeTitle).append("\n\n");
                promptBuilder.append("卷内题名列表：\n");
                for (int i = 0; i < itemTitles.size(); i++) {
                    promptBuilder.append(i + 1).append(". ").append(itemTitles.get(i)).append("\n");
                }
                promptBuilder.append("\n判断标准：\n");
                promptBuilder.append("1. 案卷题名与卷内题名主题是否一致\n");
                promptBuilder.append("2. 案卷题名是否体现了所有卷内题名的主题\n\n");
                promptBuilder.append("请返回JSON格式：\n");
                promptBuilder.append("{\n");
                promptBuilder.append("  \"passed\": true/false,\n");
                promptBuilder.append("  \"reason\": \"判断原因说明\"\n");
                promptBuilder.append("}");

                // Call LLM
                LlmAnalyzeResult llmResult = aimpClient.llmAnalyze(
                        promptBuilder.toString(),
                        null,
                        "json");

                if (!llmResult.isSuccess()) {
                    logger.error("LLM analyze failed for volume " + volumeNumber + ": " + llmResult.getError());
                    result.addError(new CheckError(volumeTitleRowIndex, "题名",
                            volumeTitle, "llm_error", "LLM调用失败: " + llmResult.getError()));
                    continue;
                }

                // Parse LLM result
                JsonNode llmResultJson = llmResult.getResult();
                boolean passed = llmResultJson.has("passed") && llmResultJson.get("passed").asBoolean();
                String reason = llmResultJson.has("reason") ? llmResultJson.get("reason").asText() : "";

                logger.info("Volume " + volumeNumber + " title check result: passed=" + passed + ", reason=" + reason);

                if (!passed) {
                    result.addError(new CheckError(volumeTitleRowIndex, "题名",
                            volumeTitle, "volume_title_mismatch", reason));
                }
            }

            result.complete();
            return result;

        } catch (Exception e) {
            logger.error("Error in volume title comparison", e);
            result.complete();
            return result;
        }
    }

    /**
     * Find sheet that contains a specific column
     */
    private String findSheetWithColumn(String columnName) {
        try {
            Object projectMetadataObj = project.getMetadata().getCustomMetadata("project");
            if (projectMetadataObj != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode projectMetadata = mapper.valueToTree(projectMetadataObj);
                if (projectMetadata.has("sheetDataMap")) {
                    JsonNode sheetDataMap = projectMetadata.get("sheetDataMap");
                    java.util.Iterator<String> sheetIdIterator = sheetDataMap.fieldNames();
                    while (sheetIdIterator.hasNext()) {
                        String sheetId = sheetIdIterator.next();
                        JsonNode sheetData = sheetDataMap.get(sheetId);
                        if (sheetData.has("columnModel") && sheetData.get("columnModel").has("columns")) {
                            JsonNode columns = sheetData.get("columnModel").get("columns");
                            for (JsonNode column : columns) {
                                if (column.has("name") && columnName.equals(column.get("name").asText())) {
                                    return sheetId;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to find sheet with column: " + columnName, e);
        }
        return null;
    }

    /**
     * Find sheet that contains all specified columns
     */
    private String findSheetWithColumns(List<String> columnNames) {
        try {
            Object projectMetadataObj = project.getMetadata().getCustomMetadata("project");
            if (projectMetadataObj != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode projectMetadata = mapper.valueToTree(projectMetadataObj);
                if (projectMetadata.has("sheetDataMap")) {
                    JsonNode sheetDataMap = projectMetadata.get("sheetDataMap");
                    java.util.Iterator<String> sheetIdIterator = sheetDataMap.fieldNames();
                    while (sheetIdIterator.hasNext()) {
                        String sheetId = sheetIdIterator.next();
                        JsonNode sheetData = sheetDataMap.get(sheetId);
                        if (sheetData.has("columnModel") && sheetData.get("columnModel").has("columns")) {
                            JsonNode columns = sheetData.get("columnModel").get("columns");
                            List<String> sheetColumnNames = new ArrayList<>();
                            for (JsonNode column : columns) {
                                if (column.has("name")) {
                                    sheetColumnNames.add(column.get("name").asText());
                                }
                            }
                            // Check if all required columns exist
                            if (sheetColumnNames.containsAll(columnNames)) {
                                return sheetId;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to find sheet with columns: " + columnNames, e);
        }
        return null;
    }

    /**
     * Find column index in a specific sheet
     */
    private Integer findColumnIndex(String sheetId, String columnName) {
        try {
            Object projectMetadataObj = project.getMetadata().getCustomMetadata("project");
            if (projectMetadataObj != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode projectMetadata = mapper.valueToTree(projectMetadataObj);
                if (projectMetadata.has("sheetDataMap")) {
                    JsonNode sheetDataMap = projectMetadata.get("sheetDataMap");
                    JsonNode sheetData = sheetDataMap.get(sheetId);
                    if (sheetData != null && sheetData.has("columnModel") && sheetData.get("columnModel").has("columns")) {
                        JsonNode columns = sheetData.get("columnModel").get("columns");
                        for (JsonNode column : columns) {
                            if (column.has("name") && columnName.equals(column.get("name").asText())) {
                                if (column.has("cellIndex")) {
                                    return column.get("cellIndex").asInt();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to find column index: " + columnName + " in sheet: " + sheetId, e);
        }
        return null;
    }

    /**
     * Internal class to hold row data during processing
     */
    private static class RowData {
        final int rowIndex;
        final Row row;
        final String resourcePath;
        /** 本件切分后的资源文件绝对路径清单 */
        final List<String> imageFiles;
        final List<String> imageNames;
        /** 与 AIMP 结果对齐使用的键：案卷号|件号 */
        final String dataKey;

        RowData(int rowIndex, Row row, String resourcePath, List<String> imageFiles,
                List<String> imageNames, String dataKey) {
            this.rowIndex = rowIndex;
            this.row = row;
            this.resourcePath = resourcePath;
            this.imageFiles = imageFiles;
            this.imageNames = imageNames;
            this.dataKey = dataKey;
        }
    }
}

