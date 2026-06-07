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
import java.util.HashMap;
import java.util.Map;

public class AimpLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(AimpLlmClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;
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

    public Map<String, String> extractContent(String filePath, String keyList) {
        Map<String, String> result = new HashMap<>();
        try {
            File file = new File(filePath);
            if (!file.exists()) return result;
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fileName = file.getName();

            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
            bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");

            byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);

            StringBuilder keyListPart = new StringBuilder();
            keyListPart.append("\r\n--").append(boundary).append("\r\n");
            keyListPart.append("Content-Disposition: form-data; name=\"key_list\"\r\n\r\n");
            keyListPart.append(keyList);

            keyListPart.append("\r\n--").append(boundary).append("\r\n");
            keyListPart.append("Content-Disposition: form-data; name=\"sync\"\r\n\r\n");
            keyListPart.append("true");

            keyListPart.append("\r\n--").append(boundary).append("--\r\n");
            byte[] bodyEnd = keyListPart.toString().getBytes(StandardCharsets.UTF_8);

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
                if (json.has("results") && json.get("results").isObject())
                    json.get("results").fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
                if (result.isEmpty() && json.has("extracted_fields"))
                    json.get("extracted_fields").fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
            } else {
                logger.warn("AIMP extract failed: HTTP " + c.getResponseCode());
            }
        } catch (Exception e) {
            logger.error("Error extracting: " + filePath, e);
        }
        return result;
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
}

