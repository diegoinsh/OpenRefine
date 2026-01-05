/*
 * Data Quality Extension - AIMP Client
 * Client for Jinshu-aimp information extraction service
 */
package com.google.refine.extension.quality.aimp;

import java.io.BufferedReader;
import java.io.File;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for communicating with Jinshu-aimp service.
 * Provides OCR and content extraction capabilities.
 * Supports two interface modes:
 * - 7998: Uses unified /api/ocr/extract endpoint
 * - 7999: Uses individual endpoints (/blank, /stain, /edge, etc.)
 */
public class AimpClient {

    public static final String INTERFACE_MODE_7998 = "7998";
    public static final String INTERFACE_MODE_7999 = "7999";

    private static final Logger logger = LoggerFactory.getLogger(AimpClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int TIMEOUT_MS = 30000;

    private String serviceUrl;
    private String interfaceMode;

    public AimpClient(String serviceUrl) {
        this.serviceUrl = normalizeServiceUrl(serviceUrl);
        this.interfaceMode = detectInterfaceMode(serviceUrl);
    }

    public AimpClient(String serviceUrl, String interfaceMode) {
        this.serviceUrl = normalizeServiceUrl(serviceUrl);
        this.interfaceMode = determineInterfaceMode(serviceUrl, interfaceMode);
    }

    /**
     * Detect interface mode from service URL
     */
    private String detectInterfaceMode(String serviceUrl) {
        if (serviceUrl == null) {
            return INTERFACE_MODE_7998;
        }

        if (serviceUrl.contains(":7999")) {
            return INTERFACE_MODE_7999;
        }
        if (serviceUrl.contains(":7998")) {
            return INTERFACE_MODE_7998;
        }
        if (serviceUrl.contains(":8089")) {
            return INTERFACE_MODE_7998;
        }

        return INTERFACE_MODE_7998;
    }

    /**
     * Determine interface mode, prioritizing explicit mode setting
     */
    private String determineInterfaceMode(String serviceUrl, String explicitMode) {
        if (explicitMode != null && !explicitMode.isEmpty() && !explicitMode.equals("auto")) {
            return explicitMode;
        }
        return detectInterfaceMode(serviceUrl);
    }

    /**
     * Get the appropriate health check URL based on interface mode
     */
    private String getHealthCheckUrl() {
        if (INTERFACE_MODE_7999.equals(interfaceMode)) {
            return serviceUrl + "/docs";
        }
        return serviceUrl + "/health";
    }

    /**
     * Get the appropriate content check endpoint based on interface mode and check type
     */
    public String getCheckEndpoint(String checkType) {
        if (INTERFACE_MODE_7999.equals(interfaceMode)) {
            Map<String, String> endpoints = new HashMap<>();
            endpoints.put("blank", "/blank");
            endpoints.put("stain", "/stain");
            endpoints.put("edge", "/edge");
            endpoints.put("hole", "/hole");
            endpoints.put("skew", "/rectify");
            endpoints.put("dpi", "/dpi");
            endpoints.put("bitdepth", "/bitdepth");
            endpoints.put("quality", "/jpeg/quality");
            return serviceUrl + (endpoints.get(checkType) != null ? endpoints.get(checkType) : "/" + checkType);
        }
        return serviceUrl + "/api/ocr/extract";
    }

    /**
     * Test connection to AIMP service
     */
    public boolean testConnection() {
        try {
            String healthUrl = getHealthCheckUrl();
            URL url = new URL(healthUrl);
            logger.info("Testing AIMP connection: " + url.toString() + " (mode: " + interfaceMode + ")");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            logger.info("AIMP health check response code: " + responseCode);

            if (responseCode == 200) {
                return true;
            }

            if (INTERFACE_MODE_7999.equals(interfaceMode)) {
                logger.info("Trying 7998 mode fallback...");
                String oldMode = interfaceMode;
                interfaceMode = INTERFACE_MODE_7998;
                boolean result = testConnection();
                interfaceMode = oldMode;
                return result;
            }

            return false;
        } catch (Exception e) {
            logger.warn("AIMP connection test failed: " + e.getMessage());
            return false;
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
     * Normalize service URL to extract base URL (scheme://host:port)
     */
    private String normalizeServiceUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            URL parsed = new URL(url.replaceAll("/$", ""));
            StringBuilder baseUrl = new StringBuilder();
            baseUrl.append(parsed.getProtocol()).append("://").append(parsed.getHost());
            if (parsed.getPort() != -1 && parsed.getPort() != parsed.getDefaultPort()) {
                baseUrl.append(":").append(parsed.getPort());
            }
            return baseUrl.toString();
        } catch (Exception e) {
            logger.warn("Failed to parse service URL: " + url);
            return url.replaceAll("/$", "");
        }
    }

    /**
     * Extract content from a file using OCR
     * Uses multipart form upload to match AIMP service API
     * @param filePath Path to the file
     * @return Map of extracted labels to values
     */
    public Map<String, String> extractContent(String filePath) {
        return extractContent(filePath, "题名,责任者,文号,成文日期");
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

            // Prepare multipart request
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fileName = file.getName();

            // Build multipart body
            StringBuilder bodyBuilder = new StringBuilder();

            // File part
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
            bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");

            byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);

            // Key list part
            StringBuilder keyListPart = new StringBuilder();
            keyListPart.append("\r\n--").append(boundary).append("\r\n");
            keyListPart.append("Content-Disposition: form-data; name=\"key_list\"\r\n\r\n");
            keyListPart.append(keyList);

            // Sync part
            keyListPart.append("\r\n--").append(boundary).append("\r\n");
            keyListPart.append("Content-Disposition: form-data; name=\"sync\"\r\n\r\n");
            keyListPart.append("true");

            keyListPart.append("\r\n--").append(boundary).append("--\r\n");
            byte[] bodyEnd = keyListPart.toString().getBytes(StandardCharsets.UTF_8);

            // Send request to /extract/upload endpoint
            URL url = new URL(serviceUrl + "/extract/upload");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyStart);
                os.write(fileBytes);
                os.write(bodyEnd);
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
                    if (responseJson.has("results")) {
                        JsonNode results = responseJson.get("results");
                        if (results.isObject()) {
                            results.fields().forEachRemaining(entry -> {
                                result.put(entry.getKey(), entry.getValue().asText());
                            });
                        }
                    }
                    // Fallback: check for extracted_fields
                    if (result.isEmpty() && responseJson.has("extracted_fields")) {
                        JsonNode fields = responseJson.get("extracted_fields");
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
            // Build multipart request
            String boundary = "----AimpBoundary" + System.currentTimeMillis();
            URL url = new URL(serviceUrl + "/extract/archive/batch_compare");

            logger.info("Calling AIMP batch_compare: " + url.toString());
            logger.info("excel_data count: " + excelData.size() + ", image_data count: " + imageData.size());

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(30000);  // 30 seconds to establish connection
            conn.setReadTimeout(600000);    // 10 minutes read timeout (AIMP may take long time for batch processing)
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                // task_id
                writeFormField(os, boundary, "task_id", taskId);
                // excel_data
                writeFormField(os, boundary, "excel_data", mapper.writeValueAsString(excelData));

                // 总是使用 image_data 格式，因为我们有完整的信息（path, dataKey, imageNames, imageCount）
                // partNumber 可以为 null，Python 端会正确处理
                logger.info("Using image_data format with full info");
                logger.info("image_data content: " + mapper.writeValueAsString(imageData));
                writeFormField(os, boundary, "image_data", mapper.writeValueAsString(imageData));

                // elements
                writeFormField(os, boundary, "elements", mapper.writeValueAsString(elements));
                // thresholds
                writeFormField(os, boundary, "confidence_threshold", String.valueOf(confidenceThreshold));
                writeFormField(os, boundary, "similarity_threshold", String.valueOf(similarityThreshold));
                writeFormField(os, boundary, "enable_stamp_processing", "true");
                writeFormField(os, boundary, "stamp_confidence_threshold", "0.5");
                writeFormField(os, boundary, "enable_preprocessing", "true");

                // End boundary
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
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

                    result.setSuccess(responseJson.has("success") && responseJson.get("success").asBoolean());

                    if (responseJson.has("comparison_result")) {
                        JsonNode compResult = responseJson.get("comparison_result");
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

                    if (responseJson.has("error")) {
                        result.setError(responseJson.get("error").asText());
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

    private void writeFormField(OutputStream os, String boundary, String name, String value) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        sb.append(value).append("\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Result of batch compare operation
     */
    public static class BatchCompareResult {
        private boolean success;
        private String taskId;
        private Map<String, Map<String, ElementResult>> comparisonResult;
        private String error;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public Map<String, Map<String, ElementResult>> getComparisonResult() { return comparisonResult; }
        public void setComparisonResult(Map<String, Map<String, ElementResult>> comparisonResult) { this.comparisonResult = comparisonResult; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /**
     * Result for a single element comparison
     */
    public static class ElementResult {
        private boolean hasError;
        private String excelValue;
        private String extractedValue;
        private double similarity;
        private String suggestion;

        public boolean isHasError() { return hasError; }
        public void setHasError(boolean hasError) { this.hasError = hasError; }
        public String getExcelValue() { return excelValue; }
        public void setExcelValue(String excelValue) { this.excelValue = excelValue; }
        public String getExtractedValue() { return extractedValue; }
        public void setExtractedValue(String extractedValue) { this.extractedValue = extractedValue; }
        public double getSimilarity() { return similarity; }
        public void setSimilarity(double similarity) { this.similarity = similarity; }
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    }
}

