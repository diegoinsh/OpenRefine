/*
 * Data Quality Extension - Image Quality Checker
 * 图像质量检查：包括DPI、文件大小等参数检查
 * 独立于ResourceConfig运行
 * 
 * 长期优化方案TODO：
 * 1. 引入数据库存储哈希映射：使用SQLite或H2数据库存储文件哈希和路径，降低内存占用
 *    - 实现位置：在calculateFileHash方法中，将哈希直接写入数据库而非内存Map
 *    - 优势：支持亿级文件处理，内存占用极低
 *    - 参考：https://github.com/xerial/sqlite-jdbc
 * 
 * 2. 分布式处理：支持多机并行处理大规模图片检查
 *    - 实现位置：在check方法中，将文件夹列表分片到不同工作节点
 *    - 优势：线性扩展处理能力，缩短处理时间
 *    - 参考：使用消息队列（RabbitMQ/Kafka）或分布式任务调度
 * 
 * 3. 增量检查：只检查新增或修改的文件
 *    - 实现位置：在检查前，读取上次检查的哈希数据库，对比文件修改时间
 *    - 优势：大幅减少重复检查时间，提高效率
 *    - 参考：维护文件哈希索引表，记录文件路径、哈希、最后检查时间
 */
package com.google.refine.extension.quality.checker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.FileInfo;
import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckErrorDetails;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.util.ResourcePathBuilder;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class ImageQualityChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(ImageQualityChecker.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int CONNECT_TIMEOUT = 60;
    private static final int READ_TIMEOUT = 120;
    private static final int RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final String INTERFACE_MODE_7998 = "7998";
    private static final String INTERFACE_MODE_7999 = "7999";

    private final Project project;
    private final QualityRulesConfig rules;
    private final String aimpEndpoint;
    private final String interfaceMode;
    private final int connectTimeout;
    private final int readTimeout;
    private boolean taskCreated = false;
    private com.google.refine.extension.quality.task.QualityCheckTask task;

    public ImageQualityChecker(Project project, QualityRulesConfig rules, String aimpEndpoint) {
        this.project = project;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint;
        this.interfaceMode = detectInterfaceMode(aimpEndpoint);
        this.connectTimeout = CONNECT_TIMEOUT;
        this.readTimeout = READ_TIMEOUT;
    }

    public ImageQualityChecker(Project project, QualityRulesConfig rules, String aimpEndpoint, String interfaceMode) {
        this.project = project;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint;
        this.interfaceMode = interfaceMode != null ? interfaceMode : detectInterfaceMode(aimpEndpoint);
        this.connectTimeout = CONNECT_TIMEOUT;
        this.readTimeout = READ_TIMEOUT;
    }

    public void resetTaskCreated() {
        this.taskCreated = false;
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
        return INTERFACE_MODE_7998;
    }

    public void setTask(com.google.refine.extension.quality.task.QualityCheckTask task) {
        this.task = task;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("image_quality");
        
        logger.info("=== ImageQualityChecker.runCheck() started ===");
        logger.info("project: " + (project != null ? "not null" : "null"));
        logger.info("rules: " + (rules != null ? "not null" : "null"));
        logger.info("aimpEndpoint: " + aimpEndpoint);

        if (project == null || rules == null) {
            logger.warn("Project or rules is null, skipping image quality check");
            result.complete();
            return result;
        }

        ImageQualityRule imageRule = rules.getImageQualityRule();
        logger.info("imageQualityRule: " + (imageRule != null ? "not null" : "null"));
        
        if (imageRule == null || !hasEnabledChecks(imageRule)) {
            logger.info("No image quality checks enabled, skipping");
            result.complete();
            return result;
        }

        if (aimpEndpoint == null || aimpEndpoint.isEmpty()) {
            logger.warn("AIMP endpoint not configured, skipping image quality check");
            result.complete();
            return result;
        }

        int totalRows = project.rows.size();
        result.setTotalRows(totalRows);
        logger.info("Total rows to check: " + totalRows);

        ResourceCheckConfig resourceConfig = rules.getResourceConfig();
        logger.info("ResourceCheckConfig: " + (resourceConfig != null ? "not null" : "null"));
        if (resourceConfig != null) {
            logger.info("BasePath: " + resourceConfig.getBasePath());
            logger.info("PathFields: " + (resourceConfig.getPathFields() != null ? resourceConfig.getPathFields().toString() : "null"));
            logger.info("Separator: " + resourceConfig.getSeparator());
        }

        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }
        logger.info("Columns in project: " + columnIndexMap.keySet().toString());

        String separator = "/";
        if (resourceConfig != null) {
            separator = resourceConfig.getSeparator();
            if (separator == null || separator.isEmpty()) {
                separator = File.separator;
            }
        }

        int checkedRows = 0;
        int passedRows = 0;
        int failedRows = 0;
        int totalImageCount = 0;

        Map<String, List<FileInfo>> hashToFiles = new Object2ObjectOpenHashMap<>();

        this.resetTaskCreated();

        if (task != null) {
            for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
                Row row = project.rows.get(rowIndex);
                String resourcePath = ResourcePathBuilder.buildResourcePath(row, columnIndexMap, resourceConfig, separator);

                if (resourcePath == null || resourcePath.isEmpty()) {
                    continue;
                }

                File folder = new File(resourcePath);
                if (!folder.exists() || !folder.isDirectory()) {
                    continue;
                }

                File[] imageFiles = folder.listFiles((dir, name) -> {
                    String lowerName = name.toLowerCase();
                    return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                           lowerName.endsWith(".png") || lowerName.endsWith(".tif") ||
                           lowerName.endsWith(".tiff") || lowerName.endsWith(".bmp") ||
                           lowerName.endsWith(".gif") || lowerName.endsWith(".webp");
                });

                if (imageFiles != null) {
                    totalImageCount += imageFiles.length;
                }
            }
            task.setImageQualityCheckTotal(totalImageCount);
            task.setImageQualityCheckProcessed(0);
        }

        logger.info("开始遍历所有行，共 " + totalRows + " 行，总图像数: " + totalImageCount);

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            logger.info("========================================");
            logger.info("[外层循环] 开始处理第 " + (rowIndex + 1) + "/" + totalRows + " 行");
            logger.info("========================================");

            if (task != null && task.shouldStop()) {
                logger.warn("[外层循环] 任务被终止，停止处理，停在第 " + rowIndex + " 行");
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
                    logger.warn("[外层循环] 暂停等待被中断");
                    break;
                }
            }

            Row row = project.rows.get(rowIndex);
            String resourcePath = ResourcePathBuilder.buildResourcePath(row, columnIndexMap, resourceConfig, separator);

            logger.info("[外层循环] 第 " + rowIndex + " 行 resourcePath=" + resourcePath);

            if (resourcePath == null || resourcePath.isEmpty()) {
                logger.warn("[外层循环] 第 " + rowIndex + " 行 resourcePath 为空，跳过");
                checkedRows++;
                if (task != null) {
                    task.setCheckedRows(checkedRows);
                }
                continue;
            }

            File folder = new File(resourcePath);
            logger.info("[外层循环] 第 " + rowIndex + " 行 folder.exists=" + folder.exists() + ", isDirectory=" + folder.isDirectory());
            if (!folder.exists() || !folder.isDirectory()) {
                logger.warn("[外层循环] 第 " + rowIndex + " 行文件夹不存在或不是目录，跳过");
                checkedRows++;
                if (task != null) {
                    task.setCheckedRows(checkedRows);
                }
                continue;
            }

            File[] imageFiles = folder.listFiles((dir, name) -> {
                String lowerName = name.toLowerCase();
                return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                       lowerName.endsWith(".png") || lowerName.endsWith(".tif") ||
                       lowerName.endsWith(".tiff") || lowerName.endsWith(".bmp") ||
                       lowerName.endsWith(".gif") || lowerName.endsWith(".webp");
            });

            logger.info("[外层循环] 第 " + rowIndex + " 行找到 " + (imageFiles != null ? imageFiles.length : 0) + " 个图片文件");
            if (imageFiles == null || imageFiles.length == 0) {
                logger.warn("[外层循环] 第 " + rowIndex + " 行没有图片文件，跳过");
                checkedRows++;
                if (task != null) {
                    task.setCheckedRows(checkedRows);
                }
                continue;
            }

            Arrays.sort(imageFiles);

            AiCheckParams params = buildCheckParams(imageRule);

            boolean rowPassed = true;
            int filesProcessed = 0;
            for (File imageFile : imageFiles) {
                try {
                    logger.info("[循环开始] 处理图片 " + (filesProcessed + 1) + "/" + imageFiles.length + ": " + imageFile.getAbsolutePath());
                    AiCheckResult aiResult = this.checkImage(imageFile, params);
                    filesProcessed++;
                    logger.info("[循环进度] 已处理 " + filesProcessed + "/" + imageFiles.length + " 张图片");
                    
                    logger.info("AIMP返回结果 - blank: " + aiResult.isBlank() + 
                               ", rectify: " + aiResult.getRectify() + 
                               ", dpi: " + aiResult.getDpi() + 
                               ", kb: " + aiResult.getKb() + 
                               ", stain: " + aiResult.hasStain() + 
                               ", hole: " + aiResult.hasHole() + 
                               ", edge: " + aiResult.hasEdgeRemove());

                    if (aiResult.isServiceUnavailable()) {
                        result.setServiceUnavailable(true);
                        result.setServiceUnavailableMessage(aiResult.getServiceUnavailableMessage());
                        logger.warn("AI服务不可用: " + aiResult.getServiceUnavailableMessage());
                        return result;
                    }

                    logger.info("[图像检查] 调用convertToCheckErrors，aiResult.isBlank=" + aiResult.isBlank() + 
                               ", rectify=" + aiResult.getRectify() + ", imageFile=" + imageFile.getName());
                    List<CheckError> errors = convertToCheckErrors(aiResult, imageFile, row, resourcePath, imageRule);
                    logger.info("转换后的错误数量: " + errors.size());
                    
                    for (CheckError error : errors) {
                        error.setRowIndex(rowIndex);
                        result.addError(error);
                        logger.info("添加错误 - type: " + error.getErrorType() + ", category: " + error.getCategory() + ", message: " + error.getMessage());
                        rowPassed = false;
                    }
                    
                    if (errors.isEmpty()) {
                        logger.info("图像检查通过: " + imageFile.getName());
                    }

                    String hash = calculateFileHash(imageFile);
                    if (hash != null) {
                        FileInfo fileInfo = new FileInfo(resourcePath, imageFile.getName());
                        logger.info("添加FileInfo到hashToFiles - resourcePath: {}, fileName: {}, hash: {}", 
                            fileInfo.getResourcePath(), fileInfo.getFileName(), hash);
                        hashToFiles.computeIfAbsent(hash, k -> new ObjectArrayList<>())
                                   .add(fileInfo);
                    }
                } catch (Exception e) {
                    logger.warn("图像质量检查失败 for " + imageFile.getName() + ": " + e.getMessage(), e);
                    filesProcessed++;
                    rowPassed = false;
                }
                
                if (task != null) {
                    task.incrementImageQualityCheckProcessed();
                }
            }
            logger.info("[循环完成] 文件夹 " + folder.getName() + " 处理完成，共 " + imageFiles.length + " 张图片，成功处理 " + filesProcessed + " 张");

            if (rowPassed) {
                passedRows++;
            } else {
                failedRows++;
            }
            checkedRows++;
            logger.info("更新统计 - checkedRows: {}, passedRows: {}, failedRows: {}", checkedRows, passedRows, failedRows);

            if (task != null) {
                task.setCheckedRows(checkedRows);
                task.setPassedRows(passedRows);
                task.setFailedRows(failedRows);
            }
        }
        
        logger.info("所有行处理完成，准备开始数量统计");

        result.setCheckedRows(checkedRows);
        result.setPassedRows(passedRows);
        result.setFailedRows(failedRows);
        
        logger.info("=== 开始执行数量统计 ===");
        QuantityImageChecker quantityChecker = new QuantityImageChecker();
        if (quantityChecker.isEnabled(imageRule)) {
            logger.info("数量统计已启用");
            List<Row> allRows = project.rows;
            FileStatistics statistics = quantityChecker.checkStatistics(project, allRows, imageRule, resourceConfig);
            result.setFileStatistics(statistics);
            logger.info("数量统计完成 - 文件夹: {}, 总文件: {}, 图片文件: {}, 其他文件: {}, 空白页: {}, 空文件夹: {}", 
                       statistics.getTotalFolders(), statistics.getTotalFiles(), 
                       statistics.getImageFiles(), statistics.getOtherFiles(),
                       statistics.getBlankPages(), statistics.getEmptyFolders());
        } else {
            logger.info("数量统计未启用");
        }
        logger.info("=== 数量统计结束 ===");
        
        logger.info("=== 开始执行重复图片审查 ===");
        RepeatImageChecker repeatImageChecker = new RepeatImageChecker();
        if (repeatImageChecker.isEnabled(imageRule)) {
            logger.info("重复图片审查已启用");
            List<ImageCheckError> repeatErrors = repeatImageChecker.checkWithHashes(hashToFiles);
            for (ImageCheckError error : repeatErrors) {
                error.setRowIndex(0);
                logger.info("[ImageQualityChecker] 处理ImageCheckError - imageName: {}, duplicateImagePaths: {}", 
                    error.getImageName(), error.getDuplicateImagePaths());
                CheckError checkError = new CheckError(
                    error.getRowIndex(),
                    error.getColumnName(),
                    error.getImagePath(),
                    "repeat_image",
                    error.getMessage() != null ? error.getMessage() : "发现重复图片: " + error.getImageName()
                );
                checkError.setCategory(error.getCategory());
                checkError.setHiddenFileName(error.getHiddenFileName());
                checkError.setDuplicateImagePaths(error.getDuplicateImagePaths());
                logger.info("[ImageQualityChecker] 转换后CheckError - duplicateImagePaths: {}", 
                    checkError.getDuplicateImagePaths());
                result.addError(checkError);
            }
            logger.info("重复图片审查完成，发现 {} 个重复图片错误", repeatErrors.size());
        } else {
            logger.info("重复图片审查未启用");
        }
        logger.info("=== 重复图片审查结束 ===");
        
        logger.info("=== 开始执行非法归档文件检测 ===");
        IllegalFilesChecker illegalFilesChecker = new IllegalFilesChecker();
        if (illegalFilesChecker.isEnabled(imageRule)) {
            logger.info("非法归档文件检测已启用");
            List<Row> allRows = project.rows;
            List<ImageCheckError> illegalFileErrors = illegalFilesChecker.check(project, allRows, imageRule, resourceConfig);
            for (ImageCheckError error : illegalFileErrors) {
                CheckError checkError = new CheckError(
                    error.getRowIndex(),
                    error.getColumnName(),
                    error.getImagePath(),
                    "illegal_file",
                    error.getMessage() != null ? error.getMessage() : "发现非法归档文件: " + error.getImageName()
                );
                checkError.setCategory(error.getCategory());
                checkError.setHiddenFileName(error.getHiddenFileName());
                result.addError(checkError);
            }
            logger.info("非法归档文件检测完成，发现 {} 个非法文件错误", illegalFileErrors.size());
        } else {
            logger.info("非法归档文件检测未启用");
        }
        logger.info("=== 非法归档文件检测结束 ===");
        
        logger.info("=== 开始执行空白页和页面尺寸统计 ===");
        collectBlankPageAndPageSizeStatistics(result, project, resourceConfig, imageRule);
        logger.info("=== 空白页和页面尺寸统计结束 ===");
        
        result.complete();

        logger.info("Image quality check completed. Checked: " + checkedRows + ", Passed: " + passedRows + ", Failed: " + failedRows + ", Errors: " + result.getErrors().size());

        return result;
    }

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
        if (INTERFACE_MODE_7999.equals(interfaceMode)) {
            return doCheckImage7999(imageFile, params);
        } else {
            String imageBase64 = encodeImageToBase64(imageFile);
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

            logger.info("检查任务是否已创建: " + taskCreated);

            if (!taskCreated) {
                logger.info("步骤1: 创建审核任务 - " + taskUrl);
                logger.info("检查参数 - blank: " + params.isCheckBlank() + ", skew: " + params.isCheckSkew() + 
                           ", stain: " + params.isCheckStain() + ", hole: " + params.isCheckHole() + 
                           ", dpi: " + params.isCheckDpi() + ", kb: " + params.isCheckKb() + ", 篇幅统计：" + params.isCheckPageSize());

                boolean createSuccess = createInspectTask(taskUrl, params);
                if (!createSuccess) {
                    logger.warn("创建审核任务失败，返回空结果");
                    return result;
                }
                taskCreated = true;
                logger.info("任务创建成功，已设置taskCreated标志");
            } else {
                logger.info("任务已存在，跳过创建步骤，直接进行检测");
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
                       ", edge: " + result.hasEdgeRemove() + ", house_angle: " + result.getHouseAngle());

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
        queryParams.append("&set_bit_depth=").append(params.isCheckBitDepth() ? String.valueOf(params.getMinBitDepth()) : "8");
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

            JsonNode jsonNode = mapper.readTree(response);
            
            StringBuilder fieldsBuilder = new StringBuilder("JSON响应的所有字段: [");
            Iterator<String> fieldNames = jsonNode.fieldNames();
            while (fieldNames.hasNext()) {
                fieldsBuilder.append(fieldNames.next());
                if (fieldNames.hasNext()) {
                    fieldsBuilder.append(", ");
                }
            }
            fieldsBuilder.append("]");
            logger.info(fieldsBuilder.toString());

            if (jsonNode.has("rectify")) {
                float rectify = jsonNode.get("rectify").floatValue();
                logger.info("提取到rectify值: " + rectify);
                result.setRectify(rectify);
                logger.info("倾斜角度解析成功: " + result.getRectify());
            } else {
                logger.info("响应中未找到rectify字段");
            }

            if (jsonNode.has("house_angle")) {
                int houseAngle = jsonNode.get("house_angle").asInt();
                logger.info("提取到house_angle值: " + houseAngle);
                result.setHouseAngle(houseAngle);
                logger.info("文本方向解析成功: " + result.getHouseAngle() + "度");
            } else {
                logger.info("响应中未找到house_angle字段");
            }

            if (jsonNode.has("blank")) {
                boolean blank = jsonNode.get("blank").asBoolean();
                result.setBlank(blank);
                logger.info("空白检测: " + (blank ? "是" : "否"));
            } else if (jsonNode.has("is_blank")) {
                boolean blank = jsonNode.get("is_blank").asBoolean();
                result.setBlank(blank);
                logger.info("空白检测: " + (blank ? "是" : "否"));
            } else {
                logger.info("响应中未找到blank字段，默认false");
            }

            if (jsonNode.has("dpi")) {
                int dpi = jsonNode.get("dpi").asInt();
                logger.info("提取到dpi值: " + dpi);
                result.setDpi(dpi);
                logger.info("DPI解析成功: " + result.getDpi());
            } else {
                logger.info("响应中未找到dpi字段");
            }

            if (jsonNode.has("kb")) {
                int kb = jsonNode.get("kb").asInt();
                logger.info("提取到kb值: " + kb);
                result.setKb(kb);
                logger.info("文件大小(KB)解析成功: " + result.getKb());
            } else {
                logger.info("响应中未找到kb字段");
            }

            if (jsonNode.has("quality")) {
                int quality = jsonNode.get("quality").asInt();
                logger.info("提取到quality值: " + quality);
                result.setQuality(quality);
                logger.info("图像质量解析成功: " + result.getQuality());
            }

            if (jsonNode.has("bit_depth")) {
                int bitDepth = jsonNode.get("bit_depth").asInt();
                logger.info("提取到bit_depth值: " + bitDepth);
                result.setBitDepth(bitDepth);
                logger.info("位深度解析成功: " + result.getBitDepth());
            } else {
                logger.warn("响应中未找到bit_depth字段，这可能导致位深度检查失败");
            }

            if (jsonNode.has("page_size")) {
                String pageSize = jsonNode.get("page_size").asText();
                logger.info("提取到page_size值: " + pageSize);
                result.setPageSize(pageSize);
                logger.info("篇幅解析成功: " + result.getPageSize());
            }

            boolean stainFound = false;
            if (jsonNode.has("stain")) {
                stainFound = true;
                logger.info("检测到stain字段，开始解析污点坐标");
                parseStainOrHoleCoordinates(jsonNode, "stain", result);
                result.setHasStain(!result.getStainLocations().isEmpty());
                logger.info("污点解析完成，检测到" + result.getStainLocations().size() + "个污点，hasStain: " + result.hasStain());
            }
            logger.info("响应中是否包含stain字段: " + stainFound);

            boolean holeFound = false;
            if (jsonNode.has("hole")) {
                holeFound = true;
                logger.info("检测到hole字段，开始解析孔洞坐标");
                parseStainOrHoleCoordinates(jsonNode, "hole", result);
                result.setHasHole(!result.getHoleLocations().isEmpty());
                logger.info("孔洞解析完成，检测到" + result.getHoleLocations().size() + "个孔洞，hasHole: " + result.hasHole());
            }
            logger.info("响应中是否包含hole字段: " + holeFound);

            boolean edgeFound = false;
            if (jsonNode.has("edge_remove")) {
                edgeFound = true;
                logger.info("检测到edge_remove字段，开始解析边缘坐标");
                parseStainOrHoleCoordinates(jsonNode, "edge_remove", result);
                result.setHasEdgeRemove(!result.getEdgeLocations().isEmpty());
                logger.info("边缘解析完成，检测到" + result.getEdgeLocations().size() + "个边缘，hasEdgeRemove: " + result.hasEdgeRemove());
            } else if (jsonNode.has("edge")) {
                edgeFound = true;
                logger.info("检测到edge字段，开始解析边缘坐标");
                parseStainOrHoleCoordinates(jsonNode, "edge", result);
                result.setHasEdgeRemove(!result.getEdgeLocations().isEmpty());
                logger.info("边缘解析完成，检测到" + result.getEdgeLocations().size() + "个边缘，hasEdgeRemove: " + result.hasEdgeRemove());
            }
            logger.info("响应中是否包含edge字段: " + edgeFound);

        } catch (Exception e) {
            logger.error("解析检测响应失败: " + e.getMessage(), e);
        }
    }

    private void parseStainOrHoleCoordinates(JsonNode jsonNode, String fieldName, AiCheckResult result) {
        try {
            JsonNode fieldNode = jsonNode.get(fieldName);
            if (fieldNode.isArray()) {
                for (JsonNode item : fieldNode) {
                    if (item.isArray() && item.size() >= 4) {
                        int[] location = new int[4];
                        location[0] = item.get(0).asInt();
                        location[1] = item.get(1).asInt();
                        location[2] = item.get(2).asInt();
                        location[3] = item.get(3).asInt();

                        if ("stain".equals(fieldName)) {
                            result.addStainLocation(location);
                        } else if ("hole".equals(fieldName)) {
                            result.addHoleLocation(location);
                        } else if ("edge_remove".equals(fieldName) || "edge".equals(fieldName)) {
                            result.addEdgeLocation(location);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("解析" + fieldName + "坐标失败: " + e.getMessage(), e);
        }
    }

    private String encodeImageToBase64(File imageFile) throws IOException {
        byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    private String buildRequestJson7998(String fileName, AiCheckParams params) {
        return "{}";
    }

    private String sendPostRequest7998(String jsonRequest, String imageBase64) throws IOException {
        return "{}";
    }

    private AiCheckResult parseResponse(String response) {
        return new AiCheckResult();
    }

    private AiCheckResult createEmptyResult() {
        return new AiCheckResult();
    }

    private boolean hasEnabledChecks(ImageQualityRule imageRule) {
        if (imageRule == null) return false;
        return imageRule.getItemByCode("dpi") != null ||
               imageRule.getItemByCode("file-size") != null ||
               imageRule.getItemByCode("blank-page") != null ||
               imageRule.getItemByCode("skew") != null ||
               imageRule.getItemByCode("stain") != null ||
               imageRule.getItemByCode("hole") != null ||
               imageRule.getItemByCode("binding-hole") != null ||
               imageRule.getItemByCode("edge") != null ||
               imageRule.getItemByCode("page_size") != null ||
               imageRule.getItemByCode("countStats") != null ||
               imageRule.getItemByCode("repeat_image") != null ||
               imageRule.getItemByCode("duplicate") != null;
    }

    private AiCheckParams buildCheckParams(ImageQualityRule imageRule) {
        AiCheckParams params = new AiCheckParams();

        if (imageRule == null) {
            params.setCheckDpi(true);
            params.setCheckKb(true);
            return params;
        }

        for (ImageCheckItem item : imageRule.getAllEnabledItems()) {
            String code = item.getItemCode();

            switch (code) {
                case "dpi":
                    params.setCheckDpi(true);
                    Object dpiValue = item.getParameter("dpi", Object.class);
                    if (dpiValue != null) {
                        params.setDpi(Integer.parseInt(dpiValue.toString()));
                    }
                    break;
                case "kb":
                    params.setCheckKb(true);
                    Object minKbValue = item.getParameter("minKb", Object.class);
                    if (minKbValue != null) {
                        params.setSetKb(Integer.parseInt(minKbValue.toString()));
                    }
                    Object maxKbValue = item.getParameter("maxKb", Object.class);
                    if (maxKbValue != null) {
                        params.setMaxKb(Integer.parseInt(maxKbValue.toString()));
                    }
                    break;
                case "format":
                    params.setCheckFormat(true);
                    Object formatValue = item.getParameter("allowedFormats", Object.class);
                    if (formatValue != null) {
                        params.setAllowedFormats(formatValue.toString());
                    }
                    break;
                case "blank-page":
                case "blank":
                    params.setCheckBlank(true);
                    break;
                case "skew":
                    params.setCheckSkew(true);
                    Object skewValue = item.getParameter("skewTolerance", Object.class);
                    if (skewValue != null) {
                        params.setSkewTolerance(skewValue.toString());
                    }
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
                case "edge":
                    params.setCheckEdge(true);
                    Object edgeValue = item.getParameter("checkMode", Object.class);
                    if (edgeValue != null) {
                        String edgeMode = edgeValue.toString();
                        if ("strict".equals(edgeMode)) {
                            params.setEdgeStrictMode(1);
                        } else {
                            params.setEdgeStrictMode(0);
                        }
                    }
                    break;
                case "bit_depth":
                case "minBitDepth":
                    params.setCheckBitDepth(true);
                    Object minBitDepthValue = item.getParameter("minBitDepth", Object.class);
                    if (minBitDepthValue != null) {
                        params.setMinBitDepth(Integer.parseInt(minBitDepthValue.toString()));
                    }
                    break;
                case "quality":
                    params.setCheckQuality(true);
                    Object qualityValue = item.getParameter("minQuality", Object.class);
                    if (qualityValue != null) {
                        params.setMinQuality(Integer.parseInt(qualityValue.toString()));
                    }
                    break;
                case "page_size":
                    params.setCheckPageSize(true);
                    break;
            }
        }

        if (!params.isCheckDpi()) params.setCheckDpi(true);
        if (!params.isCheckKb()) params.setCheckKb(true);

        return params;
    }

    private List<CheckError> convertToCheckErrors(AiCheckResult aiResult, File imageFile,
            Row row, String resourcePath, ImageQualityRule imageRule) {
        List<CheckError> errors = new ArrayList<>();
        logger.info("[convertToCheckErrors] 开始转换，aiResult.isBlank=" + aiResult.isBlank() + 
                   ", rectify=" + aiResult.getRectify() + ", imageFile=" + (imageFile != null ? imageFile.getName() : "null") +
                   ", resourcePath=" + resourcePath);

        if (aiResult.isBlank()) {
            logger.info("[convertToCheckErrors] 检测到空白图片，开始创建CheckError");
            CheckError error = new CheckError();
            error.setErrorType("blank");
            error.setMessage("Blank page detected: " + imageFile.getName());
            error.setCategory("image_quality");
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());
            errors.add(error);
            logger.info("[convertToCheckErrors] 已添加blank错误到列表，当前错误数量: " + errors.size());
        }

        if (aiResult.getRectify() != null && aiResult.getRectify() != 0) {
            logger.info("[convertToCheckErrors] 检测到倾斜图片，角度=" + aiResult.getRectify());
            CheckError error = new CheckError();
            error.setErrorType("bias");
            error.setCategory("image_quality");
            error.setMessage("Image skew detected: " + imageFile.getName() + ", angle: " + aiResult.getRectify());
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());
            errors.add(error);
        } else if (aiResult.getRectify() != null) {
            logger.info("[convertToCheckErrors] 倾斜角度为0，不需要生成错误");
        }

        if (aiResult.getHouseAngle() != null && aiResult.getHouseAngle() != 0) {
            logger.info("[convertToCheckErrors] 检测到文本方向异常，角度=" + aiResult.getHouseAngle());
            CheckError error = new CheckError();
            error.setErrorType("houseAngle");
            error.setCategory("image_quality");
            error.setMessage("文本方向异常: " + imageFile.getName() + ", 角度: " + aiResult.getHouseAngle());
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());
            errors.add(error);
        } else if (aiResult.getHouseAngle() != null) {
            logger.info("[convertToCheckErrors] 文本方向角度为0，不需要生成错误");
        }

        if (aiResult.hasStain()) {
            List<int[]> stainLocations = aiResult.getStainLocations();
            logger.info("[ImageQualityChecker] stain detected, stainLocations: {}", stainLocations);
            if (stainLocations != null && !stainLocations.isEmpty()) {
                CheckError error = new CheckError();
            error.setErrorType("stain");
            error.setCategory("image_quality");
            error.setMessage("检测到污点: " + imageFile.getName() + " (总数: " + stainLocations.size() + ")");
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());

                List<int[]> convertedLocations = new ArrayList<>();
                for (int[] location : stainLocations) {
                    if (location != null && location.length >= 4) {
                        convertedLocations.add(new int[]{
                            location[0], location[1],
                            location[2] - location[0],
                            location[3] - location[1]
                        });
                    }
                }
                error.setDetails(new ImageCheckErrorDetails(convertedLocations));

                if (!convertedLocations.isEmpty()) {
                    int[] firstLoc = convertedLocations.get(0);
                    error.setLocationX(firstLoc[0]);
                    error.setLocationY(firstLoc[1]);
                    error.setLocationWidth(firstLoc[2]);
                    error.setLocationHeight(firstLoc[3]);
                }

                errors.add(error);
            }
        }

        if (aiResult.hasHole()) {
            List<int[]> holeLocations = aiResult.getHoleLocations();
            logger.info("[ImageQualityChecker] hole detected, holeLocations: {}", holeLocations);
            if (holeLocations != null && !holeLocations.isEmpty()) {
                CheckError error = new CheckError();
                error.setErrorType("hole");
                error.setCategory("image_quality");
                error.setMessage("Binding hole detected: " + imageFile.getName() + " (Total: " + holeLocations.size() + ")");
                error.setColumn("resource");
                error.setValue(resourcePath);
                error.setHiddenFileName(imageFile.getName());

                List<int[]> convertedLocations = new ArrayList<>();
                for (int[] location : holeLocations) {
                    if (location != null && location.length >= 4) {
                        convertedLocations.add(new int[]{
                            location[0], location[1],
                            location[2] - location[0],
                            location[3] - location[1]
                        });
                    }
                }
                error.setDetails(new ImageCheckErrorDetails(convertedLocations));

                if (!convertedLocations.isEmpty()) {
                    int[] firstLoc = convertedLocations.get(0);
                    error.setLocationX(firstLoc[0]);
                    error.setLocationY(firstLoc[1]);
                    error.setLocationWidth(firstLoc[2]);
                    error.setLocationHeight(firstLoc[3]);
                }

                errors.add(error);
            }
        }

        if (aiResult.hasEdgeRemove()) {
            List<int[]> edgeLocations = aiResult.getEdgeLocations();
            logger.info("[ImageQualityChecker] edge detected, edgeLocations: {}", edgeLocations);
            if (edgeLocations != null && !edgeLocations.isEmpty()) {
                CheckError error = new CheckError();
                error.setErrorType("edge");
                error.setCategory("image_quality");
                error.setMessage("检测到黑边: " + imageFile.getName() + " (总数: " + edgeLocations.size() + ")");
                error.setColumn("resource");
                error.setValue(resourcePath);
                error.setHiddenFileName(imageFile.getName());

                List<int[]> convertedLocations = new ArrayList<>();
                for (int[] location : edgeLocations) {
                    if (location != null && location.length >= 4) {
                        convertedLocations.add(new int[]{
                            location[0], location[1],
                            location[2] - location[0],
                            location[3] - location[1]
                        });
                    }
                }
                error.setDetails(new ImageCheckErrorDetails(convertedLocations));

                if (!convertedLocations.isEmpty()) {
                    int[] firstLoc = convertedLocations.get(0);
                    error.setLocationX(firstLoc[0]);
                    error.setLocationY(firstLoc[1]);
                    error.setLocationWidth(firstLoc[2]);
                    error.setLocationHeight(firstLoc[3]);
                }

                errors.add(error);
            }
        }

        if (aiResult.getDpi() != null && aiResult.getDpi() > 0) {
            if (imageRule != null) {
                ImageCheckItem dpiItem = imageRule.getItemByCode("dpi");
                if (dpiItem != null && dpiItem.isEnabled()) {
                    Object minDpiObj = dpiItem.getParameter("minDpi", Object.class);
                    int minDpi = minDpiObj != null ? Integer.parseInt(minDpiObj.toString()) : 300;
                    CheckError error = new CheckError();
                    error.setErrorType("dpi");
                    error.setCategory("image_quality");
                    error.setMessage("分辨率过低: " + imageFile.getName() + ", DPI: " + aiResult.getDpi() + " (最低要求: " + minDpi + ")");
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setExtractedValue(String.valueOf(aiResult.getDpi()));
                    error.setHiddenFileName(imageFile.getName());
                    errors.add(error);
                }
            }
        }

        if (aiResult.getKb() != null && aiResult.getKb() > 0) {
            if (imageRule != null) {
                ImageCheckItem kbItem = imageRule.getItemByCode("file-size");
                if (kbItem != null && kbItem.isEnabled()) {
                    Object minKbObj = kbItem.getParameter("minKb", Object.class);
                    Object maxKbObj = kbItem.getParameter("maxKb", Object.class);
                    int minKb = minKbObj != null ? Integer.parseInt(minKbObj.toString()) : 10;
                    int maxKb = maxKbObj != null ? Integer.parseInt(maxKbObj.toString()) : 10000;
                    String sizeMessage = aiResult.getKb() < minKb ? "File size too small" : "File size too large";
                    CheckError error = new CheckError();
                    error.setErrorType("file-size");
                    error.setCategory("image_quality");
                    error.setMessage(sizeMessage + ": " + imageFile.getName() + ", Size: " + aiResult.getKb() + "KB (expected: " + minKb + "-" + maxKb + "KB)");
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setExtractedValue(String.valueOf(aiResult.getKb()));
                    error.setHiddenFileName(imageFile.getName());
                    errors.add(error);
                }
            }
        }

        if (aiResult.getQuality() != null && aiResult.getQuality() > 0) {
            if (imageRule != null) {
                ImageCheckItem qualityItem = imageRule.getItemByCode("quality");
                if (qualityItem != null && qualityItem.isEnabled()) {
                    Object minQualityObj = qualityItem.getParameter("minQuality", Object.class);
                    int minQuality = minQualityObj != null ? Integer.parseInt(minQualityObj.toString()) : 80;
                    CheckError error = new CheckError();
                    error.setErrorType("quality");
                    error.setCategory("image_quality");
                    error.setMessage("JPEG质量过低: " + imageFile.getName() + ", 质量: " + aiResult.getQuality() + " (最低要求: " + minQuality + ")");
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setExtractedValue(String.valueOf(aiResult.getQuality()));
                    error.setHiddenFileName(imageFile.getName());
                    errors.add(error);
                }
            }
        }

        if (aiResult.getBitDepth() != null && aiResult.getBitDepth() > 0) {
            if (imageRule != null) {
                ImageCheckItem bitDepthItem = imageRule.getItemByCode("bit_depth");
                if (bitDepthItem != null && bitDepthItem.isEnabled()) {
                    Object minBitDepthObj = bitDepthItem.getParameter("minBitDepth", Object.class);
                    int minBitDepth = minBitDepthObj != null ? Integer.parseInt(minBitDepthObj.toString()) : 24;
                    CheckError error = new CheckError();
                    error.setErrorType("bit_depth");
                    error.setCategory("image_quality");
                    error.setMessage("位深度过低: " + imageFile.getName() + ", 位深度: " + aiResult.getBitDepth() + " (最低要求: " + minBitDepth + ")");
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setExtractedValue(String.valueOf(aiResult.getBitDepth()));
                    error.setHiddenFileName(imageFile.getName());
                    errors.add(error);
                }
            }
        }

        logger.info("[convertToCheckErrors] 转换完成，返回错误数量: " + errors.size());
        return errors;
    }

    private void collectBlankPageAndPageSizeStatistics(CheckResult result, Project project, 
            ResourceCheckConfig resourceConfig, ImageQualityRule imageRule) {
        if (result.getFileStatistics() == null) {
            result.setFileStatistics(new FileStatistics());
        }

        FileStatistics statistics = result.getFileStatistics();
        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        String separator = "/";
        if (resourceConfig != null) {
            separator = resourceConfig.getSeparator();
            if (separator == null || separator.isEmpty()) {
                separator = File.separator;
            }
        }

        for (int rowIndex = 0; rowIndex < project.rows.size(); rowIndex++) {
            Row row = project.rows.get(rowIndex);
            String resourcePath = ResourcePathBuilder.buildResourcePath(row, columnIndexMap, resourceConfig, separator);

            if (resourcePath == null || resourcePath.isEmpty()) {
                continue;
            }

            File folder = new File(resourcePath);
            if (!folder.exists() || !folder.isDirectory()) {
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
                continue;
            }

            AiCheckParams params = buildCheckParams(imageRule);
            params.setCheckBlank(true);
            params.setCheckPageSize(true);

            for (File imageFile : imageFiles) {
                try {
                    AiCheckResult aiResult = this.checkImage(imageFile, params);

                    if (aiResult.isBlank()) {
                        statistics.incrementBlankPages(1);
                    }

                    String pageSize = determinePageSize(aiResult);
                    statistics.addPageSize(pageSize);
                } catch (Exception e) {
                    logger.warn("Failed to check image for statistics: " + imageFile.getName(), e);
                }
            }
        }
    }

    private String determinePageSize(AiCheckResult aiResult) {
        String pageSize = aiResult.getPageSize();
        if (pageSize == null || pageSize.isEmpty()) {
            return "UNKNOWN";
        }
        if ("CUSTOM".equals(pageSize)) {
            return "尺寸无匹配";
        }
        return pageSize;
    }

    private String calculateFileHash(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
            byte[] digest = md.digest();
            BigInteger bigInt = new BigInteger(1, digest);
            return bigInt.toString(16);
        } catch (IOException e) {
            logger.warn("计算文件哈希失败: {} - {}", file.getAbsolutePath(), e.getMessage());
            return null;
        } catch (NoSuchAlgorithmException e) {
            logger.error("MD5算法不存在", e);
            return null;
        }
    }
}
