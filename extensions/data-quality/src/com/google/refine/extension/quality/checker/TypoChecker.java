package com.google.refine.extension.quality.checker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.aimp.AimpClient;
import com.google.refine.extension.quality.aimp.AimpClient.LlmAnalyzeResult;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.FormatRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.TypoInfo;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.util.ParsingUtilities;

public class TypoChecker {

    private static final Logger logger = LoggerFactory.getLogger(TypoChecker.class);

    private static final int DEFAULT_BATCH_SIZE = 20;

    private static final String BATCH_TYPO_CHECK_PROMPT_TEMPLATE =
        "你是一个专业的中文错别字检查专家。请检查以下多条文本中是否存在错别字。\n\n" +
        "检查规则：\n" +
        "1. 识别形近字错误（如：己/已/巳、戊/戌/戍、拔/拨）\n" +
        "2. 识别音近字错误（如：在/再、做/作、的/得/地）\n" +
        "3. 识别多字、少字错误\n" +
        "4. 忽略专有名词和行业术语\n\n" +
        "字段名称：%s\n" +
        "待检查数据（JSON数组，每项包含id和text）：\n%s\n\n" +
        "请按以下JSON格式返回结果：\n" +
        "{\n" +
        "    \"results\": [\n" +
        "        {\n" +
        "            \"id\": 对应的id值,\n" +
        "            \"hasTypo\": true或false,\n" +
        "            \"typos\": [\n" +
        "                {\n" +
        "                    \"position\": 错别字在文本中的字符位置索引(从0开始),\n" +
        "                    \"typoChar\": \"错误的字\",\n" +
        "                    \"correctChar\": \"建议的正确字\",\n" +
        "                    \"errorType\": \"形近字或音近字或多字或少字\",\n" +
        "                    \"confidence\": 0.95\n" +
        "                }\n" +
        "            ]\n" +
        "        }\n" +
        "    ]\n" +
        "}\n\n" +
        "如果没有错别字，对应项的typos为空数组。\n" +
        "只返回JSON，不要返回其他内容。";

    private static final String SINGLE_TYPO_CHECK_PROMPT_TEMPLATE =
        "你是一个专业的中文错别字检查专家。请检查以下文本中是否存在错别字。\n\n" +
        "检查规则：\n" +
        "1. 识别形近字错误（如：己/已/巳、戊/戌/戍、拔/拨）\n" +
        "2. 识别音近字错误（如：在/再、做/作、的/得/地）\n" +
        "3. 识别多字、少字错误\n" +
        "4. 忽略专有名词和行业术语\n\n" +
        "待检查文本：%s\n" +
        "字段名称：%s\n\n" +
        "请按以下JSON格式返回结果：\n" +
        "{\n" +
        "    \"hasTypo\": true或false,\n" +
        "    \"typos\": [\n" +
        "        {\n" +
        "            \"position\": 错别字在文本中的字符位置索引(从0开始),\n" +
        "            \"typoChar\": \"错误的字\",\n" +
        "            \"correctChar\": \"建议的正确字\",\n" +
        "            \"errorType\": \"形近字或音近字或多字或少字\",\n" +
        "            \"confidence\": 0.95\n" +
        "        }\n" +
        "    ]\n" +
        "}\n\n" +
        "如果没有错别字，返回：{\"hasTypo\": false, \"typos\": []}\n" +
        "只返回JSON，不要返回其他内容。";

    private final Project project;
    private final QualityRulesConfig rules;
    private final AimpClient aimpClient;
    private QualityCheckTask task;
    private int batchSize;

    public TypoChecker(Project project, QualityRulesConfig rules, String aimpServiceUrl) {
        this(project, rules, aimpServiceUrl, DEFAULT_BATCH_SIZE);
    }

    public TypoChecker(Project project, QualityRulesConfig rules, String aimpServiceUrl, int batchSize) {
        this.project = project;
        this.rules = rules;
        this.aimpClient = new AimpClient(aimpServiceUrl);
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    public void setTask(QualityCheckTask task) {
        this.task = task;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("typo");

        Map<String, FormatRule> formatRules = rules.getFormatRules();
        if (formatRules == null || formatRules.isEmpty()) {
            result.complete();
            return result;
        }

        Map<String, FormatRule> typoRules = new HashMap<>();
        for (Map.Entry<String, FormatRule> entry : formatRules.entrySet()) {
            if (entry.getValue().isTypoCheckEnabled()) {
                typoRules.put(entry.getKey(), entry.getValue());
            }
        }

        if (typoRules.isEmpty()) {
            logger.info("No typo check rules configured, skipping");
            result.complete();
            return result;
        }

        if (!aimpClient.testConnection()) {
            logger.warn("AIMP service not available, skipping typo check");
            result.setServiceUnavailable(true);
            result.setServiceUnavailableMessage("AI服务模块不可用，错别字检查需要AI服务支持");
            result.complete();
            return result;
        }

        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        int totalRows = project.rows.size();
        result.setTotalRows(totalRows);

        List<String> typoColumns = new ArrayList<>(typoRules.keySet());
        int totalChecks = totalRows * typoColumns.size();

        if (task != null) {
            task.setTypoCheckTotal(totalChecks);
        }

        int processedChecks = 0;

        for (String colName : typoColumns) {
            if (task != null && task.shouldStop()) {
                logger.info("Typo check interrupted at column " + colName);
                break;
            }

            Integer cellIndex = columnIndexMap.get(colName);
            if (cellIndex == null) {
                processedChecks += totalRows;
                if (task != null) {
                    task.setTypoCheckProcessed(processedChecks);
                }
                continue;
            }

            List<Integer> rowIndices = new ArrayList<>();
            List<String> values = new ArrayList<>();

            for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
                Row row = project.rows.get(rowIndex);
                Cell cell = row.getCell(cellIndex);
                String value = getCellValue(cell);

                if (value == null || value.trim().isEmpty()) {
                    processedChecks++;
                    if (task != null) {
                        task.setTypoCheckProcessed(processedChecks);
                    }
                    continue;
                }

                rowIndices.add(rowIndex);
                values.add(value);

                if (values.size() >= batchSize) {
                    if (task != null && task.shouldStop()) break;

                    while (task != null && task.isPaused() && !task.shouldStop()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    Map<Integer, List<TypoInfo>> batchResults = checkTypoForBatch(values, rowIndices, colName);

                    for (Map.Entry<Integer, List<TypoInfo>> entry : batchResults.entrySet()) {
                        int rowIdx = entry.getKey();
                        List<TypoInfo> typoInfos = entry.getValue();
                        if (typoInfos != null && !typoInfos.isEmpty()) {
                            String originalValue = getCellValue(project.rows.get(rowIdx).getCell(cellIndex));
                            StringBuilder message = new StringBuilder("发现错别字：");
                            for (TypoInfo ti : typoInfos) {
                                message.append("'").append(ti.getTypoChar()).append("'")
                                       .append("→'").append(ti.getCorrectChar()).append("'")
                                       .append("(").append(ti.getErrorType()).append(") ");
                            }
                            CheckError error = new CheckError(rowIdx, colName, originalValue, "typo", message.toString());
                            error.setTypoInfos(typoInfos);
                            result.addError(error);
                        }
                    }

                    processedChecks += values.size();
                    if (task != null) {
                        task.setTypoCheckProcessed(processedChecks);
                    }

                    rowIndices.clear();
                    values.clear();
                }
            }

            if (!values.isEmpty() && (task == null || !task.shouldStop())) {
                Map<Integer, List<TypoInfo>> batchResults = checkTypoForBatch(values, rowIndices, colName);

                for (Map.Entry<Integer, List<TypoInfo>> entry : batchResults.entrySet()) {
                    int rowIdx = entry.getKey();
                    List<TypoInfo> typoInfos = entry.getValue();
                    if (typoInfos != null && !typoInfos.isEmpty()) {
                        String originalValue = getCellValue(project.rows.get(rowIdx).getCell(cellIndex));
                        StringBuilder message = new StringBuilder("发现错别字：");
                        for (TypoInfo ti : typoInfos) {
                            message.append("'").append(ti.getTypoChar()).append("'")
                                   .append("→'").append(ti.getCorrectChar()).append("'")
                                   .append("(").append(ti.getErrorType()).append(") ");
                        }
                        CheckError error = new CheckError(rowIdx, colName, originalValue, "typo", message.toString());
                        error.setTypoInfos(typoInfos);
                        result.addError(error);
                    }
                }

                processedChecks += values.size();
                if (task != null) {
                    task.setTypoCheckProcessed(processedChecks);
                }
            }
        }

        logger.info("Typo check completed. Batch size: {}, Errors: {}", batchSize, result.getErrors().size());
        result.complete();
        return result;
    }

    private Map<Integer, List<TypoInfo>> checkTypoForBatch(List<String> values, List<Integer> rowIndices, String columnName) {
        Map<Integer, List<TypoInfo>> resultMap = new HashMap<>();

        try {
            ArrayNode itemsArray = ParsingUtilities.mapper.createArrayNode();
            for (int i = 0; i < values.size(); i++) {
                ObjectNode item = ParsingUtilities.mapper.createObjectNode();
                item.put("id", rowIndices.get(i));
                item.put("text", values.get(i));
                itemsArray.add(item);
            }

            String itemsJson = ParsingUtilities.mapper.writeValueAsString(itemsArray);
            String prompt = String.format(BATCH_TYPO_CHECK_PROMPT_TEMPLATE, columnName, itemsJson);

            Map<String, Object> context = new HashMap<>();
            context.put("task", "typo_check_batch");
            context.put("field_name", columnName);
            context.put("batch_size", values.size());

            LlmAnalyzeResult analyzeResult = aimpClient.llmAnalyze(prompt, context, "json");

            if (!analyzeResult.isSuccess()) {
                logger.warn("LLM batch analyze failed for typo check: " + analyzeResult.getError());
                return resultMap;
            }

            JsonNode resultNode = analyzeResult.getResult();
            if (resultNode == null || !resultNode.has("results")) return resultMap;

            JsonNode resultsArray = resultNode.get("results");
            if (!resultsArray.isArray()) return resultMap;

            for (JsonNode itemResult : resultsArray) {
                int id = itemResult.has("id") ? itemResult.get("id").asInt() : -1;
                boolean hasTypo = itemResult.has("hasTypo") && itemResult.get("hasTypo").asBoolean();

                if (hasTypo && itemResult.has("typos") && itemResult.get("typos").isArray()) {
                    List<TypoInfo> typoInfos = new ArrayList<>();
                    for (JsonNode typoNode : itemResult.get("typos")) {
                        TypoInfo info = new TypoInfo();
                        info.setPosition(typoNode.has("position") ? typoNode.get("position").asInt() : 0);
                        info.setTypoChar(typoNode.has("typoChar") ? typoNode.get("typoChar").asText() : "");
                        info.setCorrectChar(typoNode.has("correctChar") ? typoNode.get("correctChar").asText() : "");
                        info.setErrorType(typoNode.has("errorType") ? typoNode.get("errorType").asText() : "");
                        info.setConfidence(typoNode.has("confidence") ? typoNode.get("confidence").asDouble() : 0.0);
                        typoInfos.add(info);
                    }
                    resultMap.put(id, typoInfos);
                }
            }

        } catch (Exception e) {
            logger.error("Error in batch typo check for column: " + columnName, e);
        }

        return resultMap;
    }

    private String getCellValue(Cell cell) {
        if (cell == null || cell.value == null) return null;
        return cell.value.toString();
    }
}
