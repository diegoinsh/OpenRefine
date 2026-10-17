/*
 * Data Quality Extension - AIMP Client
 * Client for Jinshu-aimp information extraction service
 */
package com.google.refine.extension.quality.aimp;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiongche.sdk.aimp.AimpEndpoints;
import com.jiongche.sdk.aimp.AimpFields;
import com.jiongche.sdk.aimp.AimpMode;
import com.jiongche.sdk.aimp.AimpPort;
import com.jiongche.sdk.aimp.BatchCompareResult;
import com.jiongche.sdk.aimp.ElementResult;
import com.jiongche.sdk.aimp.LlmAnalyzeResult;
import com.jiongche.sdk.aimp.Multipart;
import com.jiongche.sdk.aimp.OcrCropResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for communicating with Jinshu-aimp service.
 * Provides OCR and content extraction capabilities.
 * Supports two interface modes:
 * - 7998: Uses unified /api/ocr/extract endpoint
 * - 7999: Uses individual endpoints (/blank, /stain, /edge, etc.)
 *
 * <p>线上契约（端点路径 / 字段名 / 模式判定 / multipart 编码 / 结果 DTO）与能力端口
 * {@link AimpPort} 均已下沉平台 SDK（{@code com.jiongche.sdk.aimp}），本类为**零 OR 依赖**
 * 的能力实现（P2 下沉）：只保留 HTTP 传输与 Jackson 响应解析。</p>
 */
public class AimpClient implements AimpPort {

    public static final String INTERFACE_MODE_7998 = AimpMode.M7998;
    public static final String INTERFACE_MODE_7999 = AimpMode.M7999;

    private static final Logger logger = LoggerFactory.getLogger(AimpClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int TIMEOUT_MS = 30000;

    /** 抽取上传的缺省键表（与改造前一致）。 */
    private static final String DEFAULT_KEY_LIST = "题名,责任者,文号,成文日期";

    private String serviceUrl;
    private String interfaceMode;

    public AimpClient(String serviceUrl) {
        this.serviceUrl = AimpMode.normalizeServiceUrl(serviceUrl);
        this.interfaceMode = AimpMode.detect(serviceUrl);
    }

    public AimpClient(String serviceUrl, String interfaceMode) {
        this.serviceUrl = AimpMode.normalizeServiceUrl(serviceUrl);
        this.interfaceMode = AimpMode.determine(serviceUrl, interfaceMode);
    }

    /**
     * Get the appropriate health check URL based on interface mode
     */
    private String getHealthCheckUrl() {
        return serviceUrl + AimpEndpoints.healthEndpoint(INTERFACE_MODE_7999.equals(interfaceMode));
    }

    /**
     * Get the appropriate content check endpoint based on interface mode and check type
     */
    public String getCheckEndpoint(String checkType) {
        if (INTERFACE_MODE_7999.equals(interfaceMode)) {
            return serviceUrl + AimpEndpoints.checkEndpoint(checkType);
        }
        return serviceUrl + AimpEndpoints.OCR_EXTRACT;
    }

    /**
     * Test connection to AIMP service
     * Supports both /health and /docs endpoints
     * Returns true if either endpoint is accessible
     */
    public boolean testConnection() {
        HttpURLConnection conn = null;
        try {
            String baseUrl = serviceUrl.endsWith("/") ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;

            String[] endpoints = {AimpEndpoints.HEALTH, AimpEndpoints.DOCS};
            for (String endpoint : endpoints) {
                String testUrl = baseUrl + endpoint;
                try {
                    URL url = new URL(testUrl);
                    logger.info("Testing AIMP connection: " + url.toString() + " (mode: " + interfaceMode + ")");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    int responseCode = conn.getResponseCode();
                    logger.info("AIMP health check response code (" + testUrl + "): " + responseCode);

                    if (responseCode == 200) {
                        return true;
                    }
                } catch (Exception e) {
                    logger.debug("Endpoint {} failed: {}", endpoint, e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                        conn = null;
                    }
                }
            }

            logger.warn("AIMP connection test failed for all endpoints");
            return false;
        } catch (Exception e) {
            logger.warn("AIMP connection test failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Check if using 7999 interface mode
     */
    public boolean is7999Mode() {
        return INTERFACE_MODE_7999.equals(interfaceMode);
    }

    /**
     * Check if using 7998 interface mode
     */
    public boolean is7998Mode() {
        return INTERFACE_MODE_7998.equals(interfaceMode);
    }

    /**
     * Get the current interface mode
     */
    public String getInterfaceMode() {
        return interfaceMode;
    }

    /**
     * Extract content from a file using OCR
     * Uses multipart form upload to match AIMP service API
     * @param filePath Path to the file
     * @return Map of extracted labels to values
     */
    public Map<String, String> extractContent(String filePath) {
        return extractContent(filePath, DEFAULT_KEY_LIST);
    }

    /**
     * Extract content from a file using OCR with specified key list
     * @param filePath Path to the file
     * @param keyList Comma-separated list of keys to extract
     * @return Map of extracted labels to values
     */
    public Map<String, String> extractContent(String filePath, String keyList) {
        Map<String, String> result = new HashMap<>();

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                logger.warn("File not found: " + filePath);
                return result;
            }

            // Prepare multipart request（形状：文件件 + key_list + sync，由平台 SDK 构造）
            Multipart multipart = Multipart.webKitBoundary()
                    .file(AimpFields.FILE, file.getName(), Files.readAllBytes(file.toPath()))
                    .field(AimpFields.KEY_LIST, keyList)
                    .field(AimpFields.SYNC, "true");

            // Send request to /extract/upload endpoint
            URL url = new URL(serviceUrl + AimpEndpoints.EXTRACT_UPLOAD);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", multipart.contentType());
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(multipart.bytes());
            }

            int responseCode = conn.getResponseCode();
            logger.info("AIMP extraction response code: " + responseCode);

            if (responseCode == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }

                    logger.debug("AIMP extraction response: " + response.toString());
                    JsonNode responseJson = mapper.readTree(response.toString());

                    // Check for results field (AIMP response format)
                    if (responseJson.has(AimpFields.RESULTS)) {
                        JsonNode results = responseJson.get(AimpFields.RESULTS);
                        if (results.isObject()) {
                            results.fields().forEachRemaining(entry -> {
                                result.put(entry.getKey(), entry.getValue().asText());
                            });
                        }
                    }
                    // Fallback: check for extracted_fields
                    if (result.isEmpty() && responseJson.has(AimpFields.EXTRACTED_FIELDS)) {
                        JsonNode fields = responseJson.get(AimpFields.EXTRACTED_FIELDS);
                        fields.fields().forEachRemaining(entry -> {
                            result.put(entry.getKey(), entry.getValue().asText());
                        });
                    }
                }
            } else {
                // Read error response
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    logger.warn("AIMP extraction failed with code: " + responseCode + ", error: " + errorResponse);
                }
            }
        } catch (Exception e) {
            logger.error("Error extracting content from file: " + filePath, e);
        }

        return result;
    }

    /**
     * Calculate similarity between two strings
     * Uses Levenshtein distance based similarity
     */
    public static double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 100.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int maxLen = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        return (1.0 - (double) distance / maxLen) * 100.0;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }

    public String getServiceUrl() { return serviceUrl; }
    public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }

    /**
     * Batch compare archive elements using AIMP service.
     *
     * @param taskId Unique task identifier
     * @param excelData List of Excel row data (dataKey, rowNum, element values)
     * @param imageData List of image data (path, dataKey, imageNames, imageCount)
     * @param elements List of element keys to extract (e.g., "title", "responsible_party")
     * @param confidenceThreshold OCR confidence threshold
     * @param similarityThreshold Text similarity threshold
     * @return BatchCompareResult containing comparison results
     */
    public BatchCompareResult batchCompare(
            String taskId,
            List<Map<String, Object>> excelData,
            List<Map<String, Object>> imageData,
            List<String> elements,
            double confidenceThreshold,
            double similarityThreshold) {

        BatchCompareResult result = new BatchCompareResult();
        result.setTaskId(taskId);

        try {
            // 总是使用 image_data 格式，因为我们有完整的信息（path, dataKey, imageNames, imageCount）
            // partNumber 可以为 null，Python 端会正确处理
            logger.info("Using image_data format with full info");
            logger.info("image_data content: " + mapper.writeValueAsString(imageData));

            // Build multipart request（形状：仅文本字段，由平台 SDK 构造）
            Multipart multipart = Multipart.aimpBoundary()
                    .field(AimpFields.TASK_ID, taskId)
                    .field(AimpFields.EXCEL_DATA, mapper.writeValueAsString(excelData))
                    .field(AimpFields.IMAGE_DATA, mapper.writeValueAsString(imageData))
                    .field(AimpFields.ELEMENTS, mapper.writeValueAsString(elements))
                    .field(AimpFields.CONFIDENCE_THRESHOLD, String.valueOf(confidenceThreshold))
                    .field(AimpFields.SIMILARITY_THRESHOLD, String.valueOf(similarityThreshold))
                    .field(AimpFields.ENABLE_STAMP_PROCESSING, "true")
                    .field(AimpFields.STAMP_CONFIDENCE_THRESHOLD, "0.5")
                    .field(AimpFields.ENABLE_PREPROCESSING, "true");

            URL url = new URL(serviceUrl + AimpEndpoints.ARCHIVE_BATCH_COMPARE);

            logger.info("Calling AIMP batch_compare: " + url.toString());
            logger.info("excel_data count: " + excelData.size() + ", image_data count: " + imageData.size());

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", multipart.contentType());
            conn.setConnectTimeout(30000);  // 30 seconds to establish connection
            conn.setReadTimeout(600000);    // 10 minutes read timeout (AIMP may take long time for batch processing)
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(multipart.bytes());
            }

            int responseCode = conn.getResponseCode();
            logger.info("AIMP batch_compare response code: " + responseCode);

            if (responseCode == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }

                    logger.debug("AIMP batch_compare response: " + response.toString());
                    JsonNode responseJson = mapper.readTree(response.toString());

                    result.setSuccess(responseJson.has(AimpFields.SUCCESS) && responseJson.get(AimpFields.SUCCESS).asBoolean());

                    if (responseJson.has(AimpFields.COMPARISON_RESULT)) {
                        JsonNode compResult = responseJson.get(AimpFields.COMPARISON_RESULT);
                        Map<String, Map<String, ElementResult>> comparisonResult = new HashMap<>();

                        compResult.fieldNames().forEachRemaining(dataKey -> {
                            JsonNode rowData = compResult.get(dataKey);
                            Map<String, ElementResult> elementResults = new HashMap<>();

                            rowData.fieldNames().forEachRemaining(elementKey -> {
                                JsonNode elemData = rowData.get(elementKey);
                                ElementResult elemResult = new ElementResult();
                                elemResult.setHasError(elemData.has("has_error") && elemData.get("has_error").asBoolean());
                                elemResult.setExcelValue(elemData.has("excel_value") ? elemData.get("excel_value").asText() : "");
                                elemResult.setExtractedValue(elemData.has("extracted_value") ? elemData.get("extracted_value").asText() : "");
                                elemResult.setSimilarity(elemData.has("similarity") ? elemData.get("similarity").asDouble() : 0.0);
                                elemResult.setSuggestion(elemData.has("suggestion") ? elemData.get("suggestion").asText() : "");
                                elementResults.put(elementKey, elemResult);
                            });

                            comparisonResult.put(dataKey, elementResults);
                        });

                        result.setComparisonResult(comparisonResult);
                    }

                    if (responseJson.has(AimpFields.ERROR)) {
                        result.setError(responseJson.get(AimpFields.ERROR).asText());
                    }
                }
            } else {
                // Read error response
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    logger.warn("AIMP batch_compare failed: " + responseCode + ", error: " + errorResponse);
                    result.setSuccess(false);
                    result.setError("HTTP " + responseCode + ": " + errorResponse);
                }
            }
        } catch (Exception e) {
            logger.error("Error calling AIMP batch_compare", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * Generic LLM analysis using prompt
     *
     * @param prompt The prompt text to send to LLM
     * @param context Optional context information (can be null)
     * @param responseFormat Expected response format ("json" or "text")
     * @return LlmAnalyzeResult containing the analysis result
     */
    public LlmAnalyzeResult llmAnalyze(String prompt, Map<String, Object> context, String responseFormat) {
        LlmAnalyzeResult result = new LlmAnalyzeResult();

        try {
            String endpoint = serviceUrl + AimpEndpoints.LLM_ANALYZE;
            logger.info("Calling LLM analyze: " + endpoint);

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);

            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put(AimpFields.PROMPT, prompt);
            if (context != null && !context.isEmpty()) {
                requestBody.putPOJO(AimpFields.CONTEXT, context);
            }
            requestBody.put(AimpFields.RESPONSE_FORMAT, responseFormat != null ? responseFormat : "json");

            logger.info("LLM analyze request: " + requestBody.toString());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            logger.info("LLM analyze response code: " + responseCode);

            if (responseCode == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }

                    logger.debug("LLM analyze response: " + response.toString());
                    JsonNode responseJson = mapper.readTree(response.toString());

                    result.setSuccess(responseJson.has(AimpFields.SUCCESS) && responseJson.get(AimpFields.SUCCESS).asBoolean());

                    if (responseJson.has("result")) {
                        result.setResultJson(responseJson.get("result").toString());
                    }

                    if (responseJson.has(AimpFields.ERROR)) {
                        result.setError(responseJson.get(AimpFields.ERROR).asText());
                    }
                }
            } else {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    logger.warn("LLM analyze failed: " + responseCode + ", error: " + errorResponse);
                    result.setSuccess(false);
                    result.setError("HTTP " + responseCode + ": " + errorResponse);
                }
            }
        } catch (Exception e) {
            logger.error("Error calling LLM analyze", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * 裁剪区域 OCR：调用 AIMP /ocr 接口，由服务端完成版面分类后再路由识别流程。
     *
     * @param imageBytes 裁剪后的图片字节（PNG）
     * @param fileName   上传时使用的文件名
     * @param mode       识别模式：auto（自动分类）/ text / seal
     * @return OcrCropResult 识别结果
     */
    public OcrCropResult ocrCrop(byte[] imageBytes, String fileName, String mode) {
        OcrCropResult result = new OcrCropResult();

        try {
            Multipart multipart = Multipart.aimpOcrBoundary()
                    .file(AimpFields.IMG,
                            fileName != null && !fileName.isEmpty() ? fileName : "crop.png",
                            "image/png", imageBytes)
                    .field(AimpFields.MODE, mode != null && !mode.isEmpty() ? mode : "auto");

            URL url = new URL(serviceUrl + AimpEndpoints.OCR);
            logger.info("Calling AIMP ocr: {}", url.toString());

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", multipart.contentType());
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(multipart.bytes());
            }

            int responseCode = conn.getResponseCode();
            logger.info("AIMP ocr response code: {}", responseCode);

            InputStream stream = responseCode == 200 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder response = new StringBuilder();
            if (stream != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }
            }

            if (responseCode == 200) {
                JsonNode json = mapper.readTree(response.toString());
                result.setSuccess(json.has(AimpFields.SUCCESS) && json.get(AimpFields.SUCCESS).asBoolean());
                result.setText(json.has(AimpFields.TEXT) ? json.get(AimpFields.TEXT).asText() : "");
                result.setCategory(json.has(AimpFields.CATEGORY) ? json.get(AimpFields.CATEGORY).asText() : "");
                result.setConfidence(json.has(AimpFields.CONFIDENCE) ? json.get(AimpFields.CONFIDENCE).asDouble() : 0.0);
                if (json.has(AimpFields.ERROR)) {
                    result.setError(json.get(AimpFields.ERROR).asText());
                }
            } else {
                logger.warn("AIMP ocr failed: {}, error: {}", responseCode, response);
                result.setSuccess(false);
                result.setError("HTTP " + responseCode + ": " + response);
            }
        } catch (Exception e) {
            logger.error("Error calling AIMP ocr", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        return result;
    }
}

