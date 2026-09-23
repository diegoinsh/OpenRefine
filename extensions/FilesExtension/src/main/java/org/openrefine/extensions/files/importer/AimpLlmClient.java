package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AimpLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(AimpLlmClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;
    /** 异步任务状态查询响应较短，单独设置较小的读取超时，避免网络抖动时长时间阻塞 */
    private static final int STATUS_READ_TIMEOUT = 15000;
    private final String serviceUrl;

    public AimpLlmClient(String serviceUrl) {
        this.serviceUrl = serviceUrl != null ? serviceUrl.replaceAll("/+$", "") : "http://127.0.0.1:7998";
    }

    public boolean testConnection() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/health").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean disableCache;

    public void setDisableCache(boolean disableCache) {
        this.disableCache = disableCache;
    }

    public Map<String, String> extractContent(String filePath, String keyList) {
        Map<String, String> result = new HashMap<>();
        ExtractPageResult r = extractPage(filePath, keyList, null);
        if (r.success) result.putAll(r.values);
        return result;
    }

    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson) {
        return extractPage(filePath, keyList, customElementsJson, null, null);
    }

    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson,
                                         Integer currentPage, Integer totalPages) {
        return extractPage(filePath, keyList, customElementsJson, currentPage, totalPages, null);
    }

    /**
     * 提取单页信息。previousExtractionsJson 为卷/件内前面页面的已提取要素
     * （JSON 数组，元素形如 {"page_index":1,"elements":{"title":{"value":"..."}}}），
     * 作为 options.previous_extractions 回传给 AIMP 供提示词参考。
     */
    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson,
                                         Integer currentPage, Integer totalPages, String previousExtractionsJson) {
        return extractUpload(filePath, keyList, customElementsJson, currentPage, totalPages,
                previousExtractionsJson, true);
    }

    /**
     * 异步提交整份文档（PDF / 多页 TIFF / OFD）：AIMP 立即返回 task_id，
     * 调用方通过 {@link #getTaskStatus(String)} 轮询页级进度，完成后取回结果，
     * 避免同步等待在大页数文档上触发读取超时。
     */
    public ExtractPageResult submitAsync(String filePath, String keyList, String customElementsJson) {
        return extractUpload(filePath, keyList, customElementsJson, null, null, null, false);
    }

    private ExtractPageResult extractUpload(String filePath, String keyList, String customElementsJson,
                                            Integer currentPage, Integer totalPages, String previousExtractionsJson,
                                            boolean sync) {
        ExtractPageResult result = new ExtractPageResult();
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                result.error = "File not found: " + filePath;
                return result;
            }
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fileName = file.getName();

            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
            bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");

            byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);

            StringBuilder parts = new StringBuilder();
            parts.append("\r\n--").append(boundary).append("\r\n");
            parts.append("Content-Disposition: form-data; name=\"key_list\"\r\n\r\n");
            parts.append(keyList);

            parts.append("\r\n--").append(boundary).append("\r\n");
            parts.append("Content-Disposition: form-data; name=\"sync\"\r\n\r\n");
            parts.append(sync ? "true" : "false");

            if (customElementsJson != null && !customElementsJson.isEmpty()) {
                parts.append("\r\n--").append(boundary).append("\r\n");
                parts.append("Content-Disposition: form-data; name=\"custom_elements\"\r\n\r\n");
                parts.append(customElementsJson);
            }

            boolean hasPrev = previousExtractionsJson != null && !previousExtractionsJson.isEmpty();
            if (currentPage != null || totalPages != null || disableCache || hasPrev) {
                ObjectNode opts = mapper.createObjectNode();
                if (currentPage != null) opts.put("current_page", currentPage);
                if (totalPages != null) opts.put("total_pages", totalPages);
                if (disableCache) opts.put("disable_cache", true);
                if (hasPrev) {
                    try {
                        opts.set("previous_extractions", mapper.readTree(previousExtractionsJson));
                    } catch (Exception e) {
                        logger.warn("previous_extractions 不是合法JSON，已忽略: " + e.getMessage());
                    }
                }
                parts.append("\r\n--").append(boundary).append("\r\n");
                parts.append("Content-Disposition: form-data; name=\"options\"\r\n\r\n");
                parts.append(opts.toString());
            }

            parts.append("\r\n--").append(boundary).append("--\r\n");
            byte[] bodyEnd = parts.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/extract/upload").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bodyStart);
                os.write(fileBytes);
                os.write(bodyEnd);
            }
            if (c.getResponseCode() == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                JsonNode taskIdNode = json.get("task_id");
                if (taskIdNode != null && !taskIdNode.isNull()) result.taskId = taskIdNode.asText();
                if (json.has("results") && json.get("results").isObject()) {
                    json.get("results").fields().forEachRemaining(e ->
                            result.values.put(e.getKey(), e.getValue().asText()));
                }
                JsonNode dataNode = json.get("data");
                if (dataNode != null && dataNode.has("results") && dataNode.get("results").isObject()) {
                    dataNode.get("results").fields().forEachRemaining(e -> {
                        JsonNode info = e.getValue();
                        if (info != null && info.isObject() && info.has("confidence")) {
                            result.confidences.put(e.getKey(), info.get("confidence").asDouble(0.0));
                        }
                    });
                }
                if (result.values.isEmpty() && json.has("extracted_fields") && json.get("extracted_fields").isObject()) {
                    json.get("extracted_fields").fields().forEachRemaining(e ->
                            result.values.put(e.getKey(), e.getValue().asText()));
                }
                JsonNode pc = json.get("page_count");
                if (pc != null && pc.isNumber()) result.pageCount = pc.asInt(1);
                result.success = true;
            } else {
                result.error = "HTTP " + c.getResponseCode();
                logger.warn("AIMP extract failed: HTTP " + c.getResponseCode());
            }
        } catch (Exception e) {
            logger.error("Error extracting: " + filePath, e);
            result.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return result;
    }

    /** 查询异步任务状态（含页级进度）；网络抖动等瞬时错误通过 success=false 返回，由调用方决定是否重试 */
    public TaskStatusResult getTaskStatus(String taskId) {
        TaskStatusResult r = new TaskStatusResult();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/task/" + taskId).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(STATUS_READ_TIMEOUT);
            int code = c.getResponseCode();
            if (code == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                r.status = json.path("status").asText("");
                r.progress = json.path("progress").asDouble(0.0);
                r.processedPages = json.path("processed_pages").asInt(0);
                r.totalPages = json.path("total_pages").asInt(0);
                JsonNode errNode = json.get("error");
                if (errNode != null && !errNode.isNull()) r.error = errNode.asText();
                JsonNode resultNode = json.get("result");
                if (resultNode != null && !resultNode.isNull()) r.result = resultNode;
                r.success = true;
            } else {
                r.error = "HTTP " + code;
            }
            c.disconnect();
        } catch (Exception e) {
            logger.warn("Error querying AIMP task status: " + taskId, e);
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    /** 把 /task/{taskId} 返回的 result 解析为提取结果（结构与同步响应中的 results 一致） */
    public ExtractPageResult parseTaskResult(JsonNode result) {
        ExtractPageResult r = new ExtractPageResult();
        if (result == null || !result.has("results") || !result.get("results").isObject()) {
            r.error = "任务结果为空";
            return r;
        }
        result.get("results").fields().forEachRemaining(e -> {
            JsonNode info = e.getValue();
            if (info != null && info.isObject()) {
                r.values.put(e.getKey(), info.path("value").asText(""));
                if (info.has("confidence") && !info.get("confidence").isNull()) {
                    r.confidences.put(e.getKey(), info.get("confidence").asDouble(0.0));
                }
            } else if (info != null) {
                r.values.put(e.getKey(), info.asText(""));
            }
        });
        parsePageExtractions(result, r);
        r.success = true;
        return r;
    }

    /**
     * 解析多页任务结果中的页级明细（page_extractions），
     * 每页形如 {"page_index":3,"elements":{"responsible_party":{"value":"...","confidence":0.9}}}，
     * 转成「要素 → 候选页列表」，供前端点击单元格时定位到取值所在页。
     */
    private void parsePageExtractions(JsonNode result, ExtractPageResult r) {
        JsonNode pages = result.get("page_extractions");
        if (pages == null || !pages.isArray()) return;
        for (JsonNode pageNode : pages) {
            int page = pageNode.path("page_index").asInt(0);
            JsonNode elements = pageNode.get("elements");
            if (page <= 0 || elements == null || !elements.isObject()) continue;
            elements.fields().forEachRemaining(e -> {
                JsonNode info = e.getValue();
                if (info == null || !info.isObject()) return;
                String value = info.path("value").asText("");
                if (value.trim().isEmpty()) return;
                Double confidence = info.has("confidence") && !info.get("confidence").isNull()
                        ? info.get("confidence").asDouble(0.0) : null;
                r.candidates.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new ElementCandidate(value.trim(), confidence, page));
            });
        }
    }

    public LlmAnalyzeResult llmAnalyze(String prompt, String responseFormat) {
        LlmAnalyzeResult r = new LlmAnalyzeResult();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/api/llm/analyze").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            ObjectNode body = mapper.createObjectNode();
            body.put("prompt", prompt);
            body.put("response_format", responseFormat != null ? responseFormat : "json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                r.success = json.path("success").asBoolean(false);
                if (json.has("result")) r.result = json.get("result");
                if (json.has("error")) r.error = json.get("error").asText();
            } else {
                r.success = false;
                r.error = "HTTP " + c.getResponseCode();
            }
        } catch (Exception e) {
            logger.error("Error calling LLM analyze", e);
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    private String readStream(InputStream s) throws IOException {
        if (s == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    public static class LlmAnalyzeResult {
        public boolean success;
        public JsonNode result;
        public String error;
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean s) { this.success = s; }
        public JsonNode getResult() { return result; }
        public void setResult(JsonNode r) { this.result = r; }
        public String getError() { return error; }
        public void setError(String e) { this.error = e; }
    }

    public static class ExtractPageResult {
        public boolean success;
        public int pageCount = 1;
        /** 异步提交时 AIMP 返回的任务号 */
        public String taskId;
        public Map<String, String> values = new HashMap<>();
        public Map<String, Double> confidences = new LinkedHashMap<>();
        /** 页级候选：要素 key → 该要素在各页出现的取值与置信度（多页任务才有） */
        public Map<String, List<ElementCandidate>> candidates = new LinkedHashMap<>();
        public String error;
    }

    /** 某要素在某一页上的取值（page 为该文件/文件夹内的 1 起页序） */
    public static class ElementCandidate {
        public final String value;
        public final Double confidence;
        public final int page;
        /** 整目录成件的图片批次：该页对应的文件名，前端据此定位而不依赖目录列举顺序 */
        public final String fileName;

        public ElementCandidate(String value, Double confidence, int page) {
            this(value, confidence, page, null);
        }

        public ElementCandidate(String value, Double confidence, int page, String fileName) {
            this.value = value;
            this.confidence = confidence;
            this.page = page;
            this.fileName = fileName;
        }
    }

    public static class TaskStatusResult {
        public boolean success;
        public String status = "";
        public double progress;
        public int processedPages;
        public int totalPages;
        public String error;
        /** 任务完成时携带的结果（JSON） */
        public JsonNode result;
    }
}

