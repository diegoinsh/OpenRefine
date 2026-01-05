/*
 * Data Quality Extension - Content Checker
 * 调用AIMP服务进行图像内容分析
 */
package com.google.refine.extension.quality.checker;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class ContentChecker {

    private static final Logger logger = LoggerFactory.getLogger(ContentChecker.class);

    public static final String INTERFACE_MODE_7998 = "7998";
    public static final String INTERFACE_MODE_7999 = "7999";

    private static final String AIMP_ENDPOINT_7998 = "http://localhost:8089/api/ocr/extract";
    private static final int CONNECT_TIMEOUT = 30;
    private static final int READ_TIMEOUT = 60;
    private static final int RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private String aimpEndpoint;
    private String interfaceMode;
    private int connectTimeout;
    private int readTimeout;
    private final Project project;
    private final QualityRulesConfig rules;
    private com.google.refine.extension.quality.task.QualityCheckTask task;

    public ContentChecker() {
        this(AIMP_ENDPOINT_7998, INTERFACE_MODE_7998, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    public ContentChecker(String aimpEndpoint, String interfaceMode, int connectTimeout, int readTimeout) {
        this.aimpEndpoint = aimpEndpoint;
        this.interfaceMode = interfaceMode != null ? interfaceMode : INTERFACE_MODE_7998;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.project = null;
        this.rules = null;
    }

    public ContentChecker(Project project, QualityRulesConfig rules, String aimpEndpoint) {
        this.project = project;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint != null ? aimpEndpoint : AIMP_ENDPOINT_7998;
        this.interfaceMode = detectInterfaceMode(aimpEndpoint);
        this.connectTimeout = CONNECT_TIMEOUT;
        this.readTimeout = READ_TIMEOUT;
    }

    public ContentChecker(Project project, QualityRulesConfig rules, String aimpEndpoint, String interfaceMode) {
        this.project = project;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint != null ? aimpEndpoint : AIMP_ENDPOINT_7998;
        this.interfaceMode = determineInterfaceMode(aimpEndpoint, interfaceMode);
        this.connectTimeout = CONNECT_TIMEOUT;
        this.readTimeout = READ_TIMEOUT;
    }

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

    private String determineInterfaceMode(String serviceUrl, String explicitMode) {
        if (explicitMode != null && !explicitMode.isEmpty() && !explicitMode.equals("auto")) {
            return explicitMode;
        }
        return detectInterfaceMode(serviceUrl);
    }

    public String getCheckEndpoint(String checkType) {
        if (INTERFACE_MODE_7999.equals(interfaceMode)) {
            java.util.Map<String, String> endpoints = new java.util.HashMap<>();
            endpoints.put("blank", "/blank");
            endpoints.put("stain", "/stain");
            endpoints.put("edge", "/edge");
            endpoints.put("hole", "/hole");
            endpoints.put("skew", "/rectify");
            endpoints.put("dpi", "/dpi");
            endpoints.put("bitdepth", "/bitdepth");
            endpoints.put("quality", "/jpeg/quality");
            return aimpEndpoint.replaceAll("/$", "") + (endpoints.get(checkType) != null ? endpoints.get(checkType) : "/" + checkType);
        }
        return aimpEndpoint;
    }

    public boolean is7999Mode() {
        return INTERFACE_MODE_7999.equals(interfaceMode);
    }

    public boolean is7998Mode() {
        return INTERFACE_MODE_7998.equals(interfaceMode);
    }

    public String getInterfaceMode() {
        return interfaceMode;
    }

    public void setTask(com.google.refine.extension.quality.task.QualityCheckTask task) {
        this.task = task;
    }

    /**
     * 调用AIMP服务分析图像
     * @param imageFile 图像文件
     * @param params 检查参数
     * @return AI检查结果
     */
    public AiCheckResult checkImage(File imageFile, AiCheckParams params) {
        if (imageFile == null || !imageFile.exists()) {
            logger.warn("图像文件不存在: {}", imageFile);
            return createEmptyResult();
        }

        Exception lastException = null;

        for (int attempt = 0; attempt < RETRY_COUNT; attempt++) {
            try {
                return doCheckImage(imageFile, params);
            } catch (Exception e) {
                lastException = e;
                logger.warn("AI检查失败 (尝试 {}/{}): {}", attempt + 1, RETRY_COUNT, e.getMessage());
                if (attempt < RETRY_COUNT - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        logger.error("AI检查最终失败: {}", lastException != null ? lastException.getMessage() : "未知错误");
        AiCheckResult emptyResult = createEmptyResult();
        if (isConnectionRefused(lastException)) {
            emptyResult.setServiceUnavailable(true);
            emptyResult.setServiceUnavailableMessage("AI服务模块不可用，请检查或重启模块");
        }
        return emptyResult;
    }

    private AiCheckResult doCheckImage(File imageFile, AiCheckParams params) throws Exception {
        String imageBase64 = encodeImageToBase64(imageFile);

        if (INTERFACE_MODE_7999.equals(interfaceMode)) {
            return doCheckImage7999(imageFile, params);
        } else {
            String jsonRequest = buildRequestJson7998(imageFile.getName(), params);
            String response = sendPostRequest7998(jsonRequest, imageBase64);
            return parseResponse(response);
        }
    }

    private AiCheckResult doCheckImage7999(File imageFile, AiCheckParams params) throws Exception {
        logger.info("=== AIMP 7999 模式图像检测流程 ===");
        logger.info("图像文件: " + imageFile.getAbsolutePath());
        logger.info("AIMP端点: " + aimpEndpoint);
        logger.info("接口模式: " + interfaceMode);

        AiCheckResult result = new AiCheckResult();

        try {
            String baseUrl = aimpEndpoint.replaceAll("/$", "");
            String taskUrl = baseUrl + "/alot/chek";

            logger.info("步骤1: 创建审核任务 - " + taskUrl);
            logger.info("检查参数 - blank: " + params.isCheckBlank() + ", skew: " + params.isCheckSkew() + 
                       ", stain: " + params.isCheckStain() + ", hole: " + params.isCheckHole() + 
                       ", dpi: " + params.isCheckDpi() + ", kb: " + params.isCheckKb() + ", 篇幅统计：" + params.isCheckPageSize());

            boolean taskCreated = createInspectTask(taskUrl, params);
            if (!taskCreated) {
                logger.warn("创建审核任务失败，返回空结果");
                return result;
            }

            logger.info("步骤2: 发送图像检测");
            String inspectUrl = baseUrl + "/alot/chek/inspect";
            logger.info("检测URL: " + inspectUrl);

            String response = sendImageToInspect(inspectUrl, imageFile, params);
            logger.info("检测响应长度: " + response.length() + " 字符");
            logger.info("检测响应: " + response);

            parseInspectResponse(response, result);

            logger.info("解析后的结果 - blank: " + result.isBlank() + ", rectify: " + result.getRectify() + 
                       ", dpi: " + result.getDpi() + ", kb: " + result.getKb() + 
                       ", stain: " + result.hasStain() + ", hole: " + result.hasHole() + 
                       ", edge: " + result.hasEdgeRemove());

        } catch (Exception e) {
            logger.error("7999 模式检测失败: " + e.getMessage(), e);
            if (isConnectionRefused(e)) {
                result.setServiceUnavailable(true);
                result.setServiceUnavailableMessage("AI服务模块不可用，请检查或重启模块");
            }
            return result;
        }

        logger.info("返回检测结果");
        return result;
    }

    private boolean isConnectionRefused(Exception e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("connection refused") ||
               lowerMessage.contains("connect to host") ||
               lowerMessage.contains("no route to host") ||
               lowerMessage.contains("connection timed out") ||
               lowerMessage.contains("network is unreachable") ||
               lowerMessage.contains("connection reset") ||
               lowerMessage.contains("socket exception") ||
               lowerMessage.contains("eofexception") ||
               lowerMessage.contains("未知的主机") ||
               lowerMessage.contains("拒绝连接");
    }

    private boolean createInspectTask(String taskUrl, AiCheckParams params) throws IOException {
        StringBuilder queryParams = new StringBuilder();
        queryParams.append("flag_blank=").append(params.isCheckBlank());
        queryParams.append("&flag_house_angle=true");
        queryParams.append("&flag_rectify=").append(params.isCheckSkew());
        queryParams.append("&flag_edge_remove=").append(params.isCheckEdge());
        queryParams.append("&flag_stain=").append(params.isCheckStain());
        queryParams.append("&flag_hole=").append(params.isCheckHole());
        queryParams.append("&flag_dpi=").append(params.isCheckDpi());
        queryParams.append("&flag_format=").append(params.isCheckFormat());
        queryParams.append("&flag_kb=").append(params.isCheckKb());
        queryParams.append("&flag_page_size=").append(params.isCheckPageSize());
        queryParams.append("&flag_bit_depth=").append(params.isCheckBitDepth());

        String urlString = taskUrl + "?" + queryParams.toString();
        logger.info("创建任务URL: " + urlString);

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeout * 1000);
        conn.setReadTimeout(readTimeout * 1000);

        int responseCode = conn.getResponseCode();
        logger.info("创建任务响应码: " + responseCode);

        if (responseCode == 200 || responseCode == 201 || responseCode == 202) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                logger.info("创建任务响应: " + response.toString());
                return true;
            }
        }

        return false;
    }

    private String sendImageToInspect(String inspectUrl, File imageFile, AiCheckParams params) throws IOException {
        StringBuilder queryParams = new StringBuilder();
        queryParams.append("set_sensitivity=").append(params.getSensitivity());
        queryParams.append("&set_angle=").append(params.getSkewTolerance());
        queryParams.append("&set_stain=").append(params.getStainThreshold());
        queryParams.append("&set_hole=").append(params.getHoleThreshold());
        queryParams.append("&set_dpi=").append(params.isCheckDpi() ? String.valueOf(params.getDpi()) : "0");
        queryParams.append("&set_format=.jpeg&set_format=.tiff&set_format=.pdf");
        queryParams.append("&set_kb=").append(params.getSetKb());
        queryParams.append("&max_kb=").append(params.getMaxKb());
        queryParams.append("&set_quality=").append(params.getMinQuality());
        queryParams.append("&edge_strict=").append(params.getEdgeStrictMode());
        queryParams.append("&tolerance=").append(params.getTolerance());

        String urlString = inspectUrl + "?" + queryParams.toString();
        logger.info("检测URL: " + urlString);

        byte[] fileBytes = Files.readAllBytes(imageFile.toPath());
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();

        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"img\"; filename=\"").append(imageFile.getName()).append("\"\r\n");
        bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");
        byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bodyEnd = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(connectTimeout * 1000);
        conn.setReadTimeout(readTimeout * 1000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyStart);
            os.write(fileBytes);
            os.write(bodyEnd);
        }

        int responseCode = conn.getResponseCode();
        logger.info("检测响应码: " + responseCode);

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP请求失败, 响应码: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private void parseInspectResponse(String response, AiCheckResult result) {
        logger.info("开始解析检测响应，响应长度: " + response.length());
        logger.info("响应内容: " + response);

        try {
            if (response == null || response.isEmpty()) {
                logger.warn("响应为空");
                return;
            }

            if (response.contains("\"rectify\"")) {
                int start = response.indexOf("\"rectify\"") + 10;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到rectify值: " + value);
                result.setRectify(Float.parseFloat(value));
                logger.info("倾斜角度解析成功: " + result.getRectify());
            } else {
                logger.info("响应中未找到rectify字段");
            }

            if (response.contains("\"blank\":true") || response.contains("\"is_blank\":true")) {
                result.setBlank(true);
                logger.info("空白检测: 是");
            } else if (response.contains("\"blank\":false") || response.contains("\"is_blank\":false")) {
                result.setBlank(false);
                logger.info("空白检测: 否");
            } else {
                logger.info("响应中未找到blank字段，默认false");
            }

            if (response.contains("\"dpi\"")) {
                int start = response.indexOf("\"dpi\"") + 6;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到dpi值: " + value);
                result.setDpi(Integer.parseInt(value));
                logger.info("DPI解析成功: " + result.getDpi());
            } else {
                logger.info("响应中未找到dpi字段");
            }

            if (response.contains("\"kb\"")) {
                int start = response.indexOf("\"kb\"") + 5;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到kb值: " + value);
                result.setKb(Integer.parseInt(value));
                logger.info("文件大小(KB)解析成功: " + result.getKb());
            } else {
                logger.info("响应中未找到kb字段");
            }

            if (response.contains("\"quality\"")) {
                int start = response.indexOf("\"quality\"") + 10;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到quality值: " + value);
                result.setQuality(Integer.parseInt(value));
                logger.info("图像质量解析成功: " + result.getQuality());
            }

            if (response.contains("\"bit_depth\"")) {
                int start = response.indexOf("\"bit_depth\"") + 12;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到bit_depth值: " + value);
                result.setBitDepth(Integer.parseInt(value));
                logger.info("位深度解析成功: " + result.getBitDepth());
            }

            if (response.contains("\"expected_page_size\"")) {
                int start = response.indexOf("\"expected_page_size\"") + 20;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到expected_page_size值: " + value);
                result.setExpectedPageSize(Integer.parseInt(value));
                logger.info("预期篇幅解析成功: " + result.getExpectedPageSize());
            }

            if (response.contains("\"actual_page_size\"")) {
                int start = response.indexOf("\"actual_page_size\"") + 18;
                int end = response.indexOf(",", start);
                if (end == -1) end = response.indexOf("}", start);
                String value = response.substring(start, end).trim();
                logger.info("提取到actual_page_size值: " + value);
                result.setActualPageSize(Integer.parseInt(value));
                logger.info("实际篇幅解析成功: " + result.getActualPageSize());
            }

            boolean stainFound = false;
            if (response.contains("\"stain\"")) {
                stainFound = true;
                logger.info("检测到stain字段，开始解析污点坐标");
                parseStainOrHoleCoordinates(response, "stain", result);
                logger.info("污点解析完成，检测到" + result.getStainLocations().size() + "个污点");
            }
            logger.info("响应中是否包含stain字段: " + stainFound);

            boolean holeFound = false;
            if (response.contains("\"hole\"")) {
                holeFound = true;
                logger.info("检测到hole字段，开始解析装订孔坐标");
                parseStainOrHoleCoordinates(response, "hole", result);
                logger.info("装订孔解析完成，检测到" + result.getHoleLocations().size() + "个装订孔");
            }
            logger.info("响应中是否包含hole字段: " + holeFound);

            boolean edgeFound = false;
            if (response.contains("\"edge_remove\"") || response.contains("\"edge\"")) {
                edgeFound = true;
                logger.info("检测到edge_remove字段，开始解析黑边坐标");
                parseStainOrHoleCoordinates(response, "edge_remove", result);
                result.setHasEdgeRemove(result.getEdgeLocations() != null && !result.getEdgeLocations().isEmpty());
                logger.info("黑边解析完成，检测到" + result.getEdgeLocations().size() + "个黑边位置, hasEdgeRemove: " + result.hasEdgeRemove());
            }
            logger.info("响应中是否包含edge字段: " + edgeFound);

            logger.info("解析完成，结果汇总: blank=" + result.isBlank() + ", rectify=" + result.getRectify() + 
                       ", dpi=" + result.getDpi() + ", kb=" + result.getKb() + 
                       ", stain=" + result.hasStain() + ", hole=" + result.hasHole() + 
                       ", edge=" + result.hasEdgeRemove());

        } catch (Exception e) {
            logger.error("解析响应失败: " + e.getMessage(), e);
        }
    }

    private void parseLocationCoordinates(String response, String fieldName, List<int[]> locations) {
        if (locations == null) {
            return;
        }
        try {
            String fieldPattern = "\"" + fieldName + "\"";
            int fieldIndex = response.indexOf(fieldPattern);
            if (fieldIndex == -1) {
                logger.info("响应中未找到" + fieldName + "字段");
                return;
            }

            int arrayStart = response.indexOf("[", fieldIndex);
            int arrayEnd = response.indexOf("]", arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) {
                logger.info(fieldName + "数组格式不正确");
                return;
            }

            String coordsArray = response.substring(arrayStart, arrayEnd + 1);
            coordsArray = coordsArray.replaceAll("\\s+", "");
            if (coordsArray.startsWith("[[") && coordsArray.endsWith("]]")) {
                coordsArray = coordsArray.substring(2, coordsArray.length() - 2);
                String[] coordGroups = coordsArray.split("\\]\\,");
                for (String group : coordGroups) {
                    group = group.replaceAll("[\\[\\]]", "").trim();
                    if (!group.isEmpty()) {
                        String[] coords = group.split(",");
                        if (coords.length >= 4) {
                            try {
                                int x = (int) Double.parseDouble(coords[0].trim());
                                int y = (int) Double.parseDouble(coords[1].trim());
                                int width = (int) Double.parseDouble(coords[2].trim());
                                int height = (int) Double.parseDouble(coords[3].trim());
                                locations.add(new int[]{x, y, width, height});
                                logger.info("解析到" + fieldName + "坐标: [" + x + ", " + y + ", " + width + ", " + height + "]");
                            } catch (NumberFormatException e) {
                                logger.warn(fieldName + "坐标解析失败: " + group);
                            }
                        }
                    }
                }
            }
            logger.info(fieldName + "解析完成，共找到" + locations.size() + "个位置");
        } catch (Exception e) {
            logger.error("解析" + fieldName + "坐标失败: " + e.getMessage());
        }
    }

    private void parseStainOrHoleCoordinates(String response, String fieldName, AiCheckResult result) {
        try {
            String fieldPattern = "\"" + fieldName + "\"";
            int fieldIndex = response.indexOf(fieldPattern);
            if (fieldIndex == -1) {
                logger.info("响应中未找到" + fieldName + "字段");
                return;
            }

            int arrayStart = response.indexOf("[", fieldIndex);
            if (arrayStart == -1) {
                logger.info(fieldName + "未找到数组开始符");
                return;
            }

            int depth = 1;
            int arrayEnd = arrayStart + 1;
            while (depth > 0 && arrayEnd < response.length()) {
                char c = response.charAt(arrayEnd);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                arrayEnd++;
            }
            if (depth != 0) {
                logger.info(fieldName + "数组括号不匹配");
                return;
            }

            String coordsArray = response.substring(arrayStart, arrayEnd);
            coordsArray = coordsArray.replaceAll("\\s+", "");

            List<int[]> locations = new ArrayList<>();

            if (coordsArray.startsWith("[[") && coordsArray.endsWith("]]")) {
                coordsArray = coordsArray.substring(2, coordsArray.length() - 2);
                String[] coordGroups = coordsArray.split("\\]\\,");
                for (String group : coordGroups) {
                    group = group.replaceAll("[\\[\\]]", "").trim();
                    if (!group.isEmpty()) {
                        String[] coords = group.split(",");
                        if (coords.length >= 4) {
                            try {
                                int x = (int) Double.parseDouble(coords[0].trim());
                                int y = (int) Double.parseDouble(coords[1].trim());
                                int width = (int) Double.parseDouble(coords[2].trim());
                                int height = (int) Double.parseDouble(coords[3].trim());
                                locations.add(new int[]{x, y, width, height});
                                logger.info("解析到" + fieldName + "坐标: [" + x + ", " + y + ", " + width + ", " + height + "]");
                            } catch (NumberFormatException e) {
                                logger.warn(fieldName + "坐标解析失败: " + group);
                            }
                        }
                    }
                }
            }

            boolean hasIssue = locations.size() > 0;
            if ("stain".equals(fieldName)) {
                result.setHasStain(hasIssue);
                result.getStainLocations().clear();
                result.getStainLocations().addAll(locations);
            } else if ("hole".equals(fieldName)) {
                result.setHasHole(hasIssue);
                result.getHoleLocations().clear();
                result.getHoleLocations().addAll(locations);
            } else if ("edge_remove".equals(fieldName)) {
                result.setHasEdgeRemove(hasIssue);
                result.getEdgeLocations().clear();
                result.getEdgeLocations().addAll(locations);
            }

            logger.info(fieldName + "解析完成，共找到" + locations.size() + "个位置，检测到问题: " + hasIssue);
        } catch (Exception e) {
            logger.error("解析" + fieldName + "坐标失败: " + e.getMessage(), e);
        }
    }

    private String sendPostRequest7999(String endpoint, File imageFile) throws IOException {
        byte[] fileBytes = Files.readAllBytes(imageFile.toPath());
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();

        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"img\"; filename=\"").append(imageFile.getName()).append("\"\r\n");
        bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");
        byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bodyEnd = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(connectTimeout * 1000);
        conn.setReadTimeout(readTimeout * 1000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyStart);
            os.write(fileBytes);
            os.write(bodyEnd);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP请求失败, 响应码: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private boolean parseBlankResponse(String response) {
        if (response.contains("\"is_blank\":true") || response.contains("\"blank\":true")) {
            return true;
        }
        if (response.contains("\"is_blank\":false") || response.contains("\"blank\":false")) {
            return false;
        }
        return response.contains("\"blank\":true");
    }

    private boolean parseBooleanResponse(String response, String field) {
        String pattern = "\"" + field + "\":";
        int index = response.indexOf(pattern);
        if (index >= 0) {
            int valueStart = index + pattern.length();
            String value = response.substring(valueStart, Math.min(valueStart + 10, response.length())).trim();
            return value.startsWith("true");
        }
        return false;
    }

    private float parseFloatResponse(String response, String field) {
        String pattern = "\"" + field + "\":";
        int index = response.indexOf(pattern);
        if (index >= 0) {
            int valueStart = index + pattern.length();
            int valueEnd = valueStart;
            while (valueEnd < response.length() &&
                   (Character.isDigit(response.charAt(valueEnd)) ||
                    response.charAt(valueEnd) == '.' ||
                    response.charAt(valueEnd) == '-')) {
                valueEnd++;
            }
            try {
                return Float.parseFloat(response.substring(valueStart, valueEnd).trim());
            } catch (NumberFormatException e) {
                return 0f;
            }
        }
        return 0f;
    }

    private int parseIntResponse(String response, String field) {
        String pattern = "\"" + field + "\":";
        int index = response.indexOf(pattern);
        if (index >= 0) {
            int valueStart = index + pattern.length();
            int valueEnd = valueStart;
            while (valueEnd < response.length() &&
                   (Character.isDigit(response.charAt(valueEnd)) || response.charAt(valueEnd) == '-')) {
                valueEnd++;
            }
            try {
                return Integer.parseInt(response.substring(valueStart, valueEnd).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String encodeImageToBase64(File imageFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    private String buildRequestJson7998(String imageName, AiCheckParams params) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"imageName\":\"").append(escapeJson(imageName)).append("\",");
        json.append("\"checkConfig\":{");

        // 空白检测
        json.append("\"blank\":").append(params.isCheckBlank()).append(",");

        // 倾斜检测
        json.append("\"bias\":").append(params.isCheckSkew()).append(",");
        json.append("\"rectify\":\"").append(params.getSkewTolerance()).append("\",");

        // 黑边检测
        json.append("\"edgeRemove\":").append(params.isCheckEdge()).append(",");
        json.append("\"edgeStrict\":").append(params.getEdgeStrictMode()).append(",");

        // 污点检测
        json.append("\"stain\":").append(params.isCheckStain()).append(",");
        json.append("\"stainValue\":").append(params.getStainThreshold()).append(",");

        // 装订孔检测
        json.append("\"bindingHole\":").append(params.isCheckHole()).append(",");
        json.append("\"hole\":").append(params.getHoleThreshold()).append(",");

        // DPI检测
        json.append("\"dpi\":").append(params.isCheckDpi()).append(",");

        // 格式检测
        json.append("\"format\":").append(params.isCheckFormat()).append(",");

        // KB值检测
        json.append("\"kb\":").append(params.isCheckKb()).append(",");

        // 质量检测
        json.append("\"imageQuality\":").append(params.isCheckQuality()).append(",");

        json.append("}");
        json.append("}");
        return json.toString();
    }

    private String sendPostRequest7998(String jsonRequest, String imageBase64) throws IOException {
        URL url = new URL(aimpEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(connectTimeout * 1000);
        conn.setReadTimeout(readTimeout * 1000);
        conn.setDoOutput(true);

        String requestBody = "{\"data\":\"" + imageBase64 + "\",\"config\":" + jsonRequest + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP请求失败, 响应码: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private AiCheckResult parseResponse(String response) {
        AiCheckResult result = new AiCheckResult();

        try {
            // 简单解析JSON响应
            // 期望格式: {"code":0,"data":{...}}
            int codeIndex = response.indexOf("\"code\"");
            int dataIndex = response.indexOf("\"data\"");

            if (codeIndex >= 0 && dataIndex >= 0) {
                int codeValue = extractIntValue(response, codeIndex + 6);
                if (codeValue == 0) {
                    int dataStart = response.indexOf("{", dataIndex);
                    int dataEnd = response.lastIndexOf("}");
                    if (dataStart >= 0 && dataEnd > dataStart) {
                        String dataJson = response.substring(dataStart, dataEnd + 1);
                        parseAiData(result, dataJson);
                    }
                }
            }

            // 如果解析失败，尝试从原始响应解析
            if (result.isEmpty()) {
                parseRawResponse(result, response);
            }
        } catch (Exception e) {
            logger.warn("解析AI响应失败: {}", e.getMessage());
        }

        return result;
    }

    private void parseAiData(AiCheckResult result, String dataJson) {
        // 空白检测结果
        int blankIndex = dataJson.indexOf("\"blank\"");
        if (blankIndex >= 0) {
            result.setBlank(extractBooleanValue(dataJson, blankIndex));
        }

        // 倾斜角度
        int rectifyIndex = dataJson.indexOf("\"rectify\"");
        if (rectifyIndex >= 0) {
            result.setRectify(extractFloatValue(dataJson, rectifyIndex));
        }

        // 污点区域
        int stainIndex = dataJson.indexOf("\"stain\"");
        if (stainIndex >= 0) {
            result.setHasStain(extractBooleanValue(dataJson, stainIndex));
        }
        parseLocationCoordinates(dataJson, "stain_coords", result.getStainLocations());

        // 装订孔区域
        int holeIndex = dataJson.indexOf("\"hole\"");
        if (holeIndex >= 0) {
            result.setHasHole(extractBooleanValue(dataJson, holeIndex));
        }
        parseLocationCoordinates(dataJson, "hole_coords", result.getHoleLocations());

        // 黑边区域
        int edgeIndex = dataJson.indexOf("\"edge_remove\"");
        if (edgeIndex >= 0) {
            result.setHasEdgeRemove(extractBooleanValue(dataJson, edgeIndex));
        }
        parseLocationCoordinates(dataJson, "edge_coords", result.getEdgeLocations());

        // DPI值
        int dpiIndex = dataJson.indexOf("\"dpi\"");
        if (dpiIndex >= 0) {
            result.setDpi(extractIntValue(dataJson, dpiIndex));
        }

        // KB值
        int kbIndex = dataJson.indexOf("\"kb\"");
        if (kbIndex >= 0) {
            result.setKb(extractIntValue(dataJson, kbIndex));
        }

        // 质量分数
        int qualityIndex = dataJson.indexOf("\"quality\"");
        if (qualityIndex >= 0) {
            result.setQuality(extractIntValue(dataJson, qualityIndex));
        }
    }

    private void parseRawResponse(AiCheckResult result, String response) {
        // 尝试从常见响应格式解析
        if (response.contains("\"blank\":true")) {
            result.setBlank(true);
        }
        if (response.contains("\"rectify\":")) {
            result.setRectify(extractFloatValue(response, response.indexOf("\"rectify\":")));
        }
        if (response.contains("\"dpi\":")) {
            result.setDpi(extractIntValue(response, response.indexOf("\"dpi\":")));
        }
        if (response.contains("\"kb\":")) {
            result.setKb(extractIntValue(response, response.indexOf("\"kb\":")));
        }
    }

    private boolean extractBooleanValue(String json, int startIndex) {
        int valueStart = json.indexOf(":", startIndex) + 1;
        if (valueStart <= 0 || valueStart >= json.length()) {
            return false;
        }
        String value = json.substring(valueStart, Math.min(valueStart + 10, json.length())).trim();
        return value.startsWith("true");
    }

    private int extractIntValue(String json, int startIndex) {
        int valueStart = json.indexOf(":", startIndex) + 1;
        if (valueStart <= 0 || valueStart >= json.length()) {
            return 0;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length() &&
               (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        try {
            return Integer.parseInt(json.substring(valueStart, valueEnd).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private float extractFloatValue(String json, int startIndex) {
        int valueStart = json.indexOf(":", startIndex) + 1;
        if (valueStart <= 0 || valueStart >= json.length()) {
            return 0f;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length() &&
               (Character.isDigit(json.charAt(valueEnd)) ||
                json.charAt(valueEnd) == '.' ||
                json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        try {
            return Float.parseFloat(json.substring(valueStart, valueEnd).trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private AiCheckResult createEmptyResult() {
        return new AiCheckResult();
    }

    public void setEndpoint(String endpoint) {
        this.aimpEndpoint = endpoint;
    }

    public void setTimeouts(int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("content");

        logger.info("=== ContentChecker.runCheck() started ===");
        logger.info("project: " + (project != null ? "not null" : "null"));
        logger.info("rules: " + (rules != null ? "not null" : "null"));
        logger.info("aimpEndpoint: " + aimpEndpoint);

        if (project == null || rules == null) {
            logger.warn("Project or rules is null, skipping content check");
            result.complete();
            return result;
        }

        ResourceCheckConfig resourceConfig = rules.getResourceConfig();
        logger.info("resourceConfig: " + (resourceConfig != null ? "not null" : "null"));
        
        if (resourceConfig == null || resourceConfig.getPathFields() == null || resourceConfig.getPathFields().isEmpty()) {
            logger.warn("Resource config not set or pathFields is empty, skipping content check");
            logger.info("pathFields: " + (resourceConfig != null && resourceConfig.getPathFields() != null ? resourceConfig.getPathFields().toString() : "null or empty"));
            result.complete();
            return result;
        }

        if (aimpEndpoint == null || aimpEndpoint.isEmpty()) {
            logger.warn("AIMP endpoint not configured, skipping content check");
            result.complete();
            return result;
        }

        int totalRows = project.rows.size();
        result.setTotalRows(totalRows);

        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        String separator = resourceConfig.getSeparator();
        if (separator == null || separator.isEmpty()) {
            separator = File.separator;
        }

        int checkedRows = 0;
        int passedRows = 0;
        int failedRows = 0;

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            if (task != null && task.shouldStop()) {
                logger.info("Content check interrupted at row " + rowIndex);
                result.setCheckedRows(rowIndex);
                result.setPassedRows(passedRows);
                result.setFailedRows(failedRows);
                return result;
            }

            while (task != null && task.isPaused() && !task.shouldStop()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Row row = project.rows.get(rowIndex);
            String resourcePath = buildResourcePath(row, columnIndexMap, resourceConfig, separator);

            if (resourcePath == null || resourcePath.isEmpty()) {
                checkedRows++;
                continue;
            }

            File folder = new File(resourcePath);
            if (!folder.exists() || !folder.isDirectory()) {
                checkedRows++;
                continue;
            }

            File[] imageFiles = folder.listFiles((dir, name) -> {
                String lowerName = name.toLowerCase();
                return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                       lowerName.endsWith(".png") || lowerName.endsWith(".tif") ||
                       lowerName.endsWith(".tiff") || lowerName.endsWith(".bmp") ||
                       lowerName.endsWith(".gif") || lowerName.endsWith(".webp");
            });

            if (imageFiles == null || imageFiles.length == 0) {
                checkedRows++;
                continue;
            }

            Arrays.sort(imageFiles);

            AiCheckParams params = buildCheckParams();

            boolean rowPassed = true;
            for (File imageFile : imageFiles) {
                try {
                    AiCheckResult aiResult = checkImage(imageFile, params);
                    List<CheckError> errors = convertToCheckErrors(aiResult, imageFile, row, resourcePath);
                    for (CheckError error : errors) {
                        error.setRowIndex(rowIndex);
                        result.addError(error);
                        rowPassed = false;
                    }
                } catch (Exception e) {
                    logger.warn("Content check failed for image " + imageFile.getName() + ": " + e.getMessage());
                    rowPassed = false;
                }
            }

            if (rowPassed) {
                passedRows++;
            } else {
                failedRows++;
            }
            checkedRows++;

            if (task != null) {
                task.setCheckedRows(checkedRows);
                task.setPassedRows(passedRows);
                task.setFailedRows(failedRows);
            }
        }

        result.setCheckedRows(checkedRows);
        result.setPassedRows(passedRows);
        result.setFailedRows(failedRows);
        result.complete();

        logger.info("Content check completed. Checked: " + checkedRows + ", Passed: " + passedRows + ", Failed: " + failedRows);

        return result;
    }

    private String buildResourcePath(Row row, Map<String, Integer> columnIndexMap,
            ResourceCheckConfig config, String separator) {
        if (config == null) return null;

        String basePath = config.getBasePath();
        List<String> pathFields = config.getPathFields();

        if (pathFields == null || pathFields.isEmpty()) return basePath;

        StringBuilder path = new StringBuilder();

        if (basePath != null && !basePath.isEmpty()) {
            path.append(basePath);
            if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
                path.append(separator);
            }
        }

        List<String> values = new ArrayList<>();
        for (String fieldName : pathFields) {
            Integer cellIndex = columnIndexMap.get(fieldName);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                String value = cell != null && cell.value != null ? cell.value.toString().trim() : "";
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }

        if (!values.isEmpty()) {
            path.append(String.join(separator, values));
        }

        return path.length() > 0 ? path.toString() : basePath;
    }

    private AiCheckParams buildCheckParams() {
        AiCheckParams params = new AiCheckParams();

        ImageQualityRule imageRule = rules.getImageQualityRule();
        if (imageRule != null) {
            for (ImageCheckItem item : imageRule.getAllEnabledItems()) {
                String code = item.getItemCode();

                switch (code) {
                    case "blank-page":
                        params.setCheckBlank(true);
                        break;
                    case "stain":
                        params.setCheckStain(true);
                        Object stainValue = item.getParameter("stainValue", Object.class);
                        if (stainValue != null) {
                            params.setStainThreshold(Integer.parseInt(stainValue.toString()));
                        }
                        break;
                    case "hole":
                    case "binding-hole":
                        params.setCheckHole(true);
                        Object holeValue = item.getParameter("holeValue", Object.class);
                        if (holeValue != null) {
                            params.setHoleThreshold(Integer.parseInt(holeValue.toString()));
                        }
                        break;
                    case "skew":
                        params.setCheckSkew(true);
                        Object skewValue = item.getParameter("skewTolerance", Object.class);
                        if (skewValue != null) {
                            params.setSkewTolerance(skewValue.toString());
                        }
                        break;
                    case "edge":
                        params.setCheckEdge(true);
                        Object edgeMode = item.getParameter("checkMode", Object.class);
                        if (edgeMode != null) {
                            if ("strict".equals(edgeMode.toString())) {
                                params.setEdgeStrictMode(1);
                            } else {
                                params.setEdgeStrictMode(0);
                            }
                        }
                        break;
                    case "dpi":
                        params.setCheckDpi(true);
                        Object dpiValue = item.getParameter("dpi", Object.class);
                        if (dpiValue != null) {
                            params.setDpi(Integer.parseInt(dpiValue.toString()));
                        }
                        break;
                    case "kb":
                    case "file-size":
                        params.setCheckKb(true);
                        Object minKb = item.getParameter("minKb", Object.class);
                        if (minKb != null) {
                            params.setSetKb(Integer.parseInt(minKb.toString()));
                        }
                        Object maxKb = item.getParameter("maxKb", Object.class);
                        if (maxKb != null) {
                            params.setMaxKb(Integer.parseInt(maxKb.toString()));
                        }
                        break;
                    case "format":
                        params.setCheckFormat(true);
                        break;
                    case "bit_depth":
                        params.setCheckBitDepth(true);
                        Object minBitDepth = item.getParameter("minBitDepth", Object.class);
                        if (minBitDepth != null) {
                            params.setMinBitDepth(Integer.parseInt(minBitDepth.toString()));
                        }
                        break;
                    case "page_size":
                        params.setCheckPageSize(true);
                        break;
                }
            }
        }

        return params;
    }

    private List<CheckError> convertToCheckErrors(AiCheckResult aiResult, File imageFile,
            Row row, String resourcePath) {
        List<CheckError> errors = new ArrayList<>();

        if (aiResult.isBlank()) {
            CheckError error = new CheckError();
            error.setErrorType("blank-page");
            error.setMessage("Blank page detected: " + imageFile.getName());
            error.setColumn("resource");
            error.setValue(resourcePath);
            errors.add(error);
        }

        if (aiResult.getRectify() != null && Math.abs(aiResult.getRectify()) > 5) {
            CheckError error = new CheckError();
            error.setErrorType("skew");
            error.setMessage("Image skew detected: " + imageFile.getName() + ", angle: " + aiResult.getRectify());
            error.setColumn("resource");
            error.setValue(resourcePath);
            errors.add(error);
        }

        if (aiResult.hasStain()) {
            List<int[]> stainLocations = aiResult.getStainLocations();
            if (stainLocations != null && !stainLocations.isEmpty()) {
                for (int[] location : stainLocations) {
                    CheckError error = new CheckError();
                    error.setErrorType("stain");
                    error.setMessage("Stain detected: " + imageFile.getName());
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setLocation(location);
                    errors.add(error);
                }
            } else {
                CheckError error = new CheckError();
                error.setErrorType("stain");
                error.setMessage("Stain detected: " + imageFile.getName());
                error.setColumn("resource");
                error.setValue(resourcePath);
                errors.add(error);
            }
        }

        if (aiResult.hasHole()) {
            List<int[]> holeLocations = aiResult.getHoleLocations();
            if (holeLocations != null && !holeLocations.isEmpty()) {
                for (int[] location : holeLocations) {
                    CheckError error = new CheckError();
                    error.setErrorType("binding-hole");
                    error.setMessage("Binding hole detected: " + imageFile.getName());
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setLocation(location);
                    errors.add(error);
                }
            } else {
                CheckError error = new CheckError();
                error.setErrorType("binding-hole");
                error.setMessage("Binding hole detected: " + imageFile.getName());
                error.setColumn("resource");
                error.setValue(resourcePath);
                errors.add(error);
            }
        }

        if (aiResult.hasEdgeRemove()) {
            List<int[]> edgeLocations = aiResult.getEdgeLocations();
            if (edgeLocations != null && !edgeLocations.isEmpty()) {
                for (int[] location : edgeLocations) {
                    CheckError error = new CheckError();
                    error.setErrorType("edge");
                    error.setMessage("Edge detected: " + imageFile.getName());
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setLocation(location);
                    errors.add(error);
                }
            } else {
                CheckError error = new CheckError();
                error.setErrorType("edge");
                error.setMessage("Edge detected: " + imageFile.getName());
                error.setColumn("resource");
                error.setValue(resourcePath);
                errors.add(error);
            }
        }

        if (aiResult.getDpi() != null && aiResult.getDpi() > 0) {
            ImageQualityRule imageRule = rules.getImageQualityRule();
            if (imageRule != null) {
                ImageCheckItem dpiItem = imageRule.getItemByCode("dpi");
                if (dpiItem != null && dpiItem.isEnabled()) {
                    Object minDpiObj = dpiItem.getParameter("minDpi", Object.class);
                    if (minDpiObj != null) {
                        int minDpi = Integer.parseInt(minDpiObj.toString());
                        if (aiResult.getDpi() < minDpi) {
                            CheckError error = new CheckError();
                            error.setErrorType("dpi");
                            error.setMessage("Low DPI detected: " + imageFile.getName() + ", DPI: " + aiResult.getDpi() + " (minimum: " + minDpi + ")");
                            error.setColumn("resource");
                            error.setValue(resourcePath);
                            error.setExtractedValue(String.valueOf(aiResult.getDpi()));
                            errors.add(error);
                        }
                    }
                }
            }
        }

        if (aiResult.getKb() != null && aiResult.getKb() > 0) {
            ImageQualityRule imageRule = rules.getImageQualityRule();
            if (imageRule != null) {
                ImageCheckItem kbItem = imageRule.getItemByCode("file-size");
                if (kbItem != null && kbItem.isEnabled()) {
                    Object minKbObj = kbItem.getParameter("minKb", Object.class);
                    if (minKbObj != null) {
                        int minKb = Integer.parseInt(minKbObj.toString());
                        if (aiResult.getKb() < minKb) {
                            CheckError error = new CheckError();
                            error.setErrorType("file-size");
                            error.setMessage("File size too small: " + imageFile.getName() + ", Size: " + aiResult.getKb() + "KB (minimum: " + minKb + "KB)");
                            error.setColumn("resource");
                            error.setValue(resourcePath);
                            error.setExtractedValue(String.valueOf(aiResult.getKb()));
                            errors.add(error);
                        }
                    }
                }
            }
        }

        if (aiResult.getExpectedPageSize() != null && aiResult.getActualPageSize() != null) {
            int expectedPageSize = aiResult.getExpectedPageSize();
            int actualPageSize = aiResult.getActualPageSize();
            
            logger.info("篇幅统计检查 - expectedPageSize: " + expectedPageSize + ", actualPageSize: " + actualPageSize);
            
            if (actualPageSize != expectedPageSize) {
                CheckError error = new CheckError();
                error.setErrorType("page_size");
                error.setMessage("Page size mismatch: " + imageFile.getName() + ", Expected: " + expectedPageSize + ", Actual: " + actualPageSize);
                error.setColumn("resource");
                error.setValue(resourcePath);
                error.setExtractedValue(String.valueOf(actualPageSize));
                errors.add(error);
                logger.info("添加篇幅统计错误 - type: " + error.getErrorType() + ", message: " + error.getMessage());
            } else {
                logger.info("篇幅统计检查通过 - 页数匹配: " + actualPageSize);
            }
        } else {
            logger.info("篇幅统计参数缺失 - expectedPageSize: " + aiResult.getExpectedPageSize() + ", actualPageSize: " + aiResult.getActualPageSize());
        }

        logger.info("convertToCheckErrors完成，总错误数: " + errors.size());
        return errors;
    }
}
