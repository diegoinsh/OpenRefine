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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiongche.sdk.aimp.AimpEndpoints;
import com.jiongche.sdk.aimp.AimpFields;
import com.jiongche.sdk.aimp.AimpTransport;
import com.jiongche.sdk.aimp.HttpAimpTransport;
import com.jiongche.sdk.aimp.Multipart;
import com.jiongche.sdk.authz.LicenseGate;
import com.jiongche.sdk.data.TableView;
import com.jiongche.sdk.module.ModuleNames;
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
import com.google.refine.extension.quality.util.TableViews;

public class ImageQualityChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(ImageQualityChecker.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CONNECT_TIMEOUT = 60;
    private static final int READ_TIMEOUT = 120;
    private static final int RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 2000;
    /**
     * 图像检查并发度：沿用老档案合规「按件 4 并发」的口径（ImageChechRule 的 newScheduledThreadPool(4)），
     * 可用 {@code -Dquality.imageCheck.concurrency=N} 覆盖，1 即退回串行。
     */
    private static final int IMAGE_CHECK_CONCURRENCY = readConcurrency();
    /** 扁平顺序号命名：0001.jpg / 0000-00.jpg / 0001-02.jpeg（第一段 4 位数字即页序号）。 */
    private static final java.util.regex.Pattern FLAT_PAGE_PATTERN =
            java.util.regex.Pattern.compile("^(\\d{4})(?:-\\d{1,2})?\\.(?:jpe?g)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 起止页号：0001-0004 / 1~4 / 1—4。 */
    private static final java.util.regex.Pattern PAGE_RANGE_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*[-~～—－]\\s*(\\d+)");

    private final TableView view;
    private final QualityRulesConfig rules;
    private final String aimpEndpoint;
    private final int connectTimeout;
    private final int readTimeout;
    private final AimpTransport transport;
    private volatile boolean taskCreated = false;
    private com.google.refine.extension.quality.task.QualityCheckTask task;

    public ImageQualityChecker(TableView view, QualityRulesConfig rules, String aimpEndpoint) {
        this.view = view;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint;
        this.connectTimeout = CONNECT_TIMEOUT;
        this.readTimeout = READ_TIMEOUT;
        this.transport = new HttpAimpTransport(aimpEndpoint);
    }

    public void resetTaskCreated() {
        this.taskCreated = false;
    }

    public void setTask(com.google.refine.extension.quality.task.QualityCheckTask task) {
        this.task = task;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("image_quality");
        
        logger.info("=== ImageQualityChecker.runCheck() started ===");
        logger.info("view: " + (view != null ? "not null" : "null"));
        logger.info("rules: " + (rules != null ? "not null" : "null"));
        logger.info("aimpEndpoint: " + aimpEndpoint);

        if (view == null || rules == null) {
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

        // 进程内授权门：图像检查直连 inspection 端点，不经过 AimpClient，须在此校验
        LicenseGate.fromSystemProperties().require(ModuleNames.INSPECTION);

        int totalRows = view.rowCount();
        result.setTotalRows(totalRows);
        logger.info("Total rows to check: " + totalRows);

        ResourceCheckConfig resourceConfig = rules.getResourceConfig();
        logger.info("ResourceCheckConfig: " + (resourceConfig != null ? "not null" : "null"));
        if (resourceConfig != null) {
            logger.info("BasePath: " + resourceConfig.getBasePath());
            logger.info("PathFields: " + (resourceConfig.getPathFields() != null ? resourceConfig.getPathFields().toString() : "null"));
            logger.info("Separator: " + resourceConfig.getSeparator());
        }

        Map<String, Integer> columnIndexMap = TableViews.columnIndexMap(view);
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

        Map<String, List<FileInfo>> hashToFiles = new Object2ObjectOpenHashMap<>();
        FileStatistics statistics = new FileStatistics();

        this.resetTaskCreated();

        // 预扫描并按物理文件归属到行：同一卷的多个条目通常共用同一个「文件路径」
        // （只到卷目录，页码在文件名里），若按行各自扫描整目录，同一张图会被送检 N 次
        // （N=条目行数 → 慢 N 倍），且在哈希统计里出现 N 次 → 误报「重复图片 N 次」。
        // 归属规则（优先级从高到低）：
        //   1) 文件名含 `_<件号>-`（带档号的命名）→ 归该件所在行；
        //   2) 文件名是扁平顺序号（0001.jpg / 0000-00.jpg）→ 用「起止页号」列判定归属；
        //   3) 认领不到的页（封面/卷内目录/备考表/卷底等）归到首个使用该目录的行。
        // 第 2 条是必须的：真实卷宗经常按 0001.jpg 扁平落盘，只认 `_件号-` 会把整卷页
        // 全堆到第一行，界面上表现为「同一行反复报错」。
        Map<String, File[]> folderCache = new HashMap<>();
        Map<Integer, List<File>> rowImages = new HashMap<>();
        Set<String> claimed = new HashSet<>();
        Integer pieceColumnIndex = columnIndexMap.get("件号");
        Integer pageRangeColumnIndex = columnIndexMap.get("起止页号");

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            rowImages.put(rowIndex, new ArrayList<>());
            String resourcePath = ResourcePathBuilder.buildResourcePath(view, rowIndex, columnIndexMap, resourceConfig, separator);
            if (resourcePath == null || resourcePath.isEmpty()) {
                continue;
            }
            File[] files = scanImages(folderCache, resourcePath);
            if (files == null) {
                continue;
            }
            String pieceNo = cellText(view, rowIndex, pieceColumnIndex);
            int[] pageRange = parsePageRange(cellText(view, rowIndex, pageRangeColumnIndex));
            for (File file : files) {
                boolean hit = matchesPiece(file.getName(), pieceNo);
                if (!hit && pageRange != null) {
                    Integer pageNo = flatPageNo(file.getName());
                    hit = pageNo != null && pageNo >= pageRange[0] && pageNo <= pageRange[1];
                }
                if (hit && claimed.add(file.getAbsolutePath())) {
                    rowImages.get(rowIndex).add(file);
                }
            }
        }
        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            String resourcePath = ResourcePathBuilder.buildResourcePath(view, rowIndex, columnIndexMap, resourceConfig, separator);
            if (resourcePath == null || resourcePath.isEmpty()) {
                continue;
            }
            File[] files = folderCache.get(new File(resourcePath).getAbsolutePath());
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (claimed.add(file.getAbsolutePath())) {
                    rowImages.get(rowIndex).add(file);
                }
            }
        }
        for (List<File> files : rowImages.values()) {
            files.sort(Comparator.comparing(File::getName));
        }

        int totalImageCount = claimed.size();
        if (task != null) {
            task.setImageQualityCheckTotal(totalImageCount);
            task.setImageQualityCheckProcessed(0);
        }

        logger.info("开始遍历所有行，共 " + totalRows + " 行，总图像数: " + totalImageCount);

        // 第一遍：逐行解析行上下文（跳过口径、行归属去重与串行版一致）
        List<RowCheckContext> contexts = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            if (task != null && task.shouldStop()) {
                logger.warn("[外层循环] 任务被终止，停止处理，停在第 " + rowIndex + " 行");
                result.setCheckedRows(rowIndex);
                result.setPassedRows(passedRows);
                result.setFailedRows(failedRows);
                return result;
            }
            waitWhilePaused();

            String resourcePath = ResourcePathBuilder.buildResourcePath(view, rowIndex, columnIndexMap, resourceConfig, separator);
            if (resourcePath == null || resourcePath.isEmpty()) {
                logger.warn("[外层循环] 第 " + rowIndex + " 行 resourcePath 为空，跳过");
                checkedRows++;
                if (task != null) {
                    task.setCheckedRows(checkedRows);
                }
                continue;
            }

            File folder = new File(resourcePath);
            if (!folder.exists() || !folder.isDirectory()) {
                logger.warn("[外层循环] 第 " + rowIndex + " 行文件夹不存在或不是目录，跳过");
                checkedRows++;
                if (task != null) {
                    task.setCheckedRows(checkedRows);
                }
                continue;
            }

            List<File> imageFiles = rowImages.getOrDefault(rowIndex, new ArrayList<>());
            logger.info("[外层循环] 第 " + rowIndex + " 行归属 " + imageFiles.size() + " 个图片文件");
            if (imageFiles.isEmpty()) {
                logger.info("[外层循环] 第 " + rowIndex + " 行没有归属图片（同目录的页已归其它件），跳过");
                checkedRows++;
                if (task != null) {
                    task.setCheckedRows(checkedRows);
                }
                continue;
            }
            contexts.add(new RowCheckContext(rowIndex, resourcePath, imageFiles));
        }

        // 第二遍：全卷图片提交到同一个有界线程池（逐图检查仍复用 checkImage 这条链路），
        // 回填/统计在调用线程按「行序、文件名序」串行聚合，聚合状态无需加锁，错误顺序与串行版一致。
        List<ImageCheckJob> jobs = new ArrayList<>();
        for (RowCheckContext context : contexts) {
            for (File imageFile : context.files) {
                jobs.add(new ImageCheckJob(context, imageFile, buildCheckParams(imageRule)));
            }
        }
        logger.info("[并发检查] 待检查图片 " + jobs.size() + " 张，并发度 " + IMAGE_CHECK_CONCURRENCY);

        Map<Integer, Boolean> rowPassed = new HashMap<>();
        for (RowCheckContext context : contexts) {
            rowPassed.put(context.rowIndex, Boolean.TRUE);
        }

        ExecutorService pool = Executors.newFixedThreadPool(IMAGE_CHECK_CONCURRENCY, r -> {
            Thread thread = new Thread(r, "image-quality-check");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<ImageCheckOutcome>> futures = new ArrayList<>(jobs.size());
            for (ImageCheckJob job : jobs) {
                futures.add(pool.submit(() -> runImageCheckJob(job, imageRule)));
            }

            for (Future<ImageCheckOutcome> future : futures) {
                ImageCheckOutcome outcome = future.get();
                ImageCheckJob job = outcome.job;

                if (task != null) {
                    task.incrementImageQualityCheckProcessed();
                }

                if (outcome.serviceUnavailable) {
                    result.setServiceUnavailable(true);
                    result.setServiceUnavailableMessage(outcome.serviceUnavailableMessage);
                    logger.warn("AI服务不可用: " + outcome.serviceUnavailableMessage);
                    for (Future<ImageCheckOutcome> rest : futures) {
                        rest.cancel(true);
                    }
                    return result;
                }

                if (outcome.failed) {
                    logger.warn("图像质量检查失败 for " + job.file.getName() + ": " + outcome.failureMessage);
                    rowPassed.put(job.row.rowIndex, Boolean.FALSE);
                    continue;
                }

                for (CheckError error : outcome.errors) {
                    error.setRowIndex(job.row.rowIndex);
                    result.addError(error);
                }
                if (!outcome.errors.isEmpty()) {
                    rowPassed.put(job.row.rowIndex, Boolean.FALSE);
                }

                if (outcome.fileInfo != null) {
                    logger.info("添加FileInfo到hashToFiles - resourcePath: {}, fileName: {}, hash: {}",
                            outcome.fileInfo.getResourcePath(), outcome.fileInfo.getFileName(), outcome.hash);
                    hashToFiles.computeIfAbsent(outcome.hash, k -> new ObjectArrayList<>())
                               .add(outcome.fileInfo);
                }
                if (outcome.blank) {
                    statistics.incrementBlankPages(1);
                }
                statistics.addPageSize(outcome.pageSize);
            }

            for (RowCheckContext context : contexts) {
                if (Boolean.TRUE.equals(rowPassed.get(context.rowIndex))) {
                    passedRows++;
                } else {
                    failedRows++;
                }
                checkedRows++;
            }
            if (task != null) {
                task.setCheckedRows(checkedRows);
                task.setPassedRows(passedRows);
                task.setFailedRows(failedRows);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("图像检查被中断: " + e.getMessage());
        } catch (ExecutionException e) {
            logger.error("图像检查任务异常: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
        
        logger.info("所有行处理完成，准备开始数量统计");

        result.setCheckedRows(checkedRows);
        result.setPassedRows(passedRows);
        result.setFailedRows(failedRows);
        
        logger.info("=== 开始执行数量统计 ===");
        QuantityImageChecker quantityChecker = new QuantityImageChecker();
        // 数量统计默认执行（含文件总体积）：汇总报告需要文件夹/文件/图像数与数据体积，
        // 与「数量统计」检查项是否启用无关；启用与否只影响是否产生该类错误。
        logger.info("数量统计已启用: {}", quantityChecker.isEnabled(imageRule));
        FileStatistics quantityStatistics = quantityChecker.checkStatistics(view, TableViews.allRowIndexes(view), imageRule, resourceConfig);
        statistics.setTotalFolders(quantityStatistics.getTotalFolders());
        statistics.setTotalFiles(quantityStatistics.getTotalFiles());
        statistics.setImageFiles(quantityStatistics.getImageFiles());
        statistics.setOtherFiles(quantityStatistics.getOtherFiles());
        statistics.setEmptyFolders(quantityStatistics.getEmptyFolders());
        statistics.setTotalSizeBytes(quantityStatistics.getTotalSizeBytes());
        logger.info("数量统计完成 - 文件夹: {}, 总文件: {}, 图片文件: {}, 其他文件: {}, 空白页: {}, 空文件夹: {}, 总字节: {}",
                   statistics.getTotalFolders(), statistics.getTotalFiles(),
                   statistics.getImageFiles(), statistics.getOtherFiles(),
                   statistics.getBlankPages(), statistics.getEmptyFolders(),
                   statistics.getTotalSizeBytes());
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
            List<ImageCheckError> illegalFileErrors = illegalFilesChecker.check(view, TableViews.allRowIndexes(view), imageRule, resourceConfig);
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
        
        logger.info("=== 开始执行杀毒软件检测 ===");
        AntivirusChecker antivirusChecker = new AntivirusChecker();
        if (antivirusChecker.isEnabled(imageRule)) {
            logger.info("杀毒软件检测已启用");
            List<ImageCheckError> antivirusErrors = antivirusChecker.check(view, null, imageRule);
            for (ImageCheckError error : antivirusErrors) {
                CheckError checkError = new CheckError(
                    error.getRowIndex(),
                    error.getColumnName(),
                    error.getImagePath(),
                    "security_check",
                    error.getMessage() != null ? error.getMessage() : "杀毒软件检测失败"
                );
                checkError.setCategory(error.getCategory());
                checkError.setHiddenFileName(error.getHiddenFileName());
                result.addError(checkError);
            }
            logger.info("杀毒软件检测完成，发现 {} 个错误", antivirusErrors.size());
        } else {
            logger.info("杀毒软件检测未启用");
        }
        logger.info("=== 杀毒软件检测结束 ===");
        
        result.setFileStatistics(statistics);
        
        result.complete();

        logger.info("Image quality check completed. Checked: " + checkedRows + ", Passed: " + passedRows + ", Failed: " + failedRows + ", Errors: " + result.getErrors().size());

        return result;
    }

    public AiCheckResult checkImage(File imageFile, AiCheckParams params) {
        // 直调入口同样经授权门（防绕过 runCheck）
        LicenseGate.fromSystemProperties().require(ModuleNames.INSPECTION);

        if (imageFile == null || !imageFile.exists()) {
            logger.warn("图像文件不存在: {}", imageFile);
            return createEmptyResult();
        }

        Exception lastException = null;

        for (int attempt = 0; attempt < RETRY_COUNT; attempt++) {
            try {
                return doCheckImage7998(imageFile, params);
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

    /**
     * 创建 AIMP 审核任务（全卷只建一次）：并发检查时多线程会同时进来，用双检 + synchronized 保证只建一次，
     * 其余线程等首个线程建完后直接走检测，避免重复建任务。
     */
    private synchronized boolean ensureInspectTask(AiCheckParams params) throws IOException {
        if (taskCreated) {
            return true;
        }
        logger.info("步骤1: 创建审核任务 - " + aimpEndpoint.replaceAll("/$", "") + AimpEndpoints.INSPECT_TASK_CREATE);
        logger.info("检查参数 - blank: " + params.isCheckBlank() + ", skew: " + params.isCheckSkew() + 
                   ", stain: " + params.isCheckStain() + ", hole: " + params.isCheckHole() + 
                   ", dpi: " + params.isCheckDpi() + ", kb: " + params.isCheckKb() + ", 篇幅统计：" + params.isCheckPageSize());
        if (!createInspectTask(AimpEndpoints.INSPECT_TASK_CREATE, params)) {
            return false;
        }
        taskCreated = true;
        logger.info("任务创建成功，已设置taskCreated标志");
        return true;
    }

    private AiCheckResult doCheckImage7998(File imageFile, AiCheckParams params) throws Exception {
        logger.info("=== AIMP 7998 模式图像检测流程 ===");
        logger.info("图像文件: " + imageFile.getAbsolutePath());
        logger.info("AIMP端点: " + aimpEndpoint);

        AiCheckResult result = new AiCheckResult();

        try {
            String baseUrl = aimpEndpoint.replaceAll("/$", "");

            // 并发下多张图会同时走到这里：创建任务只做一次（双检 + synchronized），其余线程等它建完
            if (!ensureInspectTask(params)) {
                logger.warn("创建审核任务失败，返回空结果");
                return result;
            }

            logger.info("步骤2: 发送图像检测");
            String inspectUrl = baseUrl + AimpEndpoints.INSPECT;
            logger.info("检测URL: " + inspectUrl);

            String response = sendImageToInspect(AimpEndpoints.INSPECT, imageFile, params);
            logger.info("检测响应长度: " + response.length() + " 字符");
            logger.info("检测响应: " + response);

            parseInspectResponse(response, result);

            logger.info("解析后的结果 - blank: " + result.isBlank() + ", rectify: " + result.getRectify() + 
                       ", dpi: " + result.getDpi() + ", kb: " + result.getKb() + 
                       ", stain: " + result.hasStain() + ", hole: " + result.hasHole() + 
                       ", edge: " + result.hasEdgeRemove() + ", house_angle: " + result.getHouseAngle());

        } catch (Exception e) {
            logger.error("AIMP 图像检测失败: " + e.getMessage(), e);
            if (isConnectionRefused(e)) {
                result.setServiceUnavailable(true);
                result.setServiceUnavailableMessage("AI服务模块不可用，请检查或重启模块");
            }
            return result;
        }

        logger.info("返回检测结果");
        return result;
    }

    /** 行上下文（只读快照）：行号、资源路径、该行归属的图片（已按文件名升序、跨行去重）。 */
    private static final class RowCheckContext {
        final int rowIndex;
        final String resourcePath;
        final List<File> files;

        RowCheckContext(int rowIndex, String resourcePath, List<File> files) {
            this.rowIndex = rowIndex;
            this.resourcePath = resourcePath;
            this.files = files;
        }
    }

    /** 并发粒度：一张图片 + 所属行 + 本次规则参数。检查逻辑复用串行版的 checkImage。 */
    private static final class ImageCheckJob {
        final RowCheckContext row;
        final File file;
        final AiCheckParams params;

        ImageCheckJob(RowCheckContext row, File file, AiCheckParams params) {
            this.row = row;
            this.file = file;
            this.params = params;
        }
    }

    /** 单张图片的检查结果：检查在池线程完成，聚合在调用线程完成，聚合状态因此不需要加锁。 */
    private static final class ImageCheckOutcome {
        final ImageCheckJob job;
        List<CheckError> errors = new ArrayList<>();
        String hash;
        FileInfo fileInfo;
        boolean blank;
        String pageSize;
        boolean serviceUnavailable;
        String serviceUnavailableMessage;
        boolean failed;
        String failureMessage;

        ImageCheckOutcome(ImageCheckJob job) {
            this.job = job;
        }
    }

    /**
     * 池线程里执行一张图片：调用 AIMP、转错误、算哈希。不触碰任何共享聚合状态。
     */
    private ImageCheckOutcome runImageCheckJob(ImageCheckJob job, ImageQualityRule imageRule) {
        ImageCheckOutcome outcome = new ImageCheckOutcome(job);
        try {
            AiCheckResult aiResult = this.checkImage(job.file, job.params);
            if (aiResult.isServiceUnavailable()) {
                outcome.serviceUnavailable = true;
                outcome.serviceUnavailableMessage = aiResult.getServiceUnavailableMessage();
                return outcome;
            }
            outcome.errors = convertToCheckErrors(aiResult, job.file, job.row.resourcePath, imageRule);
            outcome.blank = aiResult.isBlank();
            outcome.pageSize = determinePageSize(aiResult);
            outcome.hash = calculateFileHash(job.file);
            if (outcome.hash != null) {
                outcome.fileInfo = new FileInfo(job.row.resourcePath, job.file.getName());
            }
        } catch (Exception e) {
            outcome.failed = true;
            outcome.failureMessage = e.getMessage();
            logger.warn("图像质量检查失败 for " + job.file.getName() + ": " + e.getMessage(), e);
        }
        return outcome;
    }

    /** 任务暂停时等待（每 1s 轮询），与串行版口径一致。 */
    private void waitWhilePaused() {
        while (task != null && task.isPaused() && !task.shouldStop()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[外层循环] 暂停等待被中断");
                break;
            }
        }
    }

    private static int readConcurrency() {
        String value = System.getProperty("quality.imageCheck.concurrency", "4");
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            logger.warn("quality.imageCheck.concurrency 非法（{}），回落到 4", value);
            return 4;
        }
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

    private boolean createInspectTask(String taskPath, AiCheckParams params) throws IOException {
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

        String pathWithQuery = taskPath + "?" + queryParams;
        logger.info("创建任务URL: " + pathWithQuery);

        AimpTransport.Response response = transport.execute(AimpTransport.Request.postEmpty(
                pathWithQuery, connectTimeout * 1000, readTimeout * 1000));

        int responseCode = response.status();
        logger.info("创建任务响应码: " + responseCode);

        if (responseCode == 200 || responseCode == 201 || responseCode == 202) {
            logger.info("创建任务响应: " + response.text());
            return true;
        }
        return false;
    }

    private String sendImageToInspect(String inspectPath, File imageFile, AiCheckParams params) throws IOException {
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

        String pathWithQuery = inspectPath + "?" + queryParams;
        logger.info("检测URL: " + pathWithQuery);

        byte[] fileBytes = Files.readAllBytes(imageFile.toPath());
        Multipart multipart = Multipart.webKitBoundary()
                .file(AimpFields.IMG, imageFile.getName(), fileBytes);

        AimpTransport.Response response = transport.execute(AimpTransport.Request.post(
                pathWithQuery, multipart.contentType(), multipart.bytes(),
                connectTimeout * 1000, readTimeout * 1000));

        logger.info("检测响应码: " + response.status());
        if (response.status() != 200) {
            throw new IOException("HTTP请求失败, 响应码: " + response.status());
        }
        return response.text();
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

    /**
     * 按候选键名依次读取整数参数：规则模板（ImageCheckStandard/平台合规模板）用 stainThreshold、
     * holeThreshold、edgeThreshold，旧的项目内规则配置用 stainValue、holeValue、edgeValue，
     * 两种键名都要能取到，否则会静默回落到硬编码默认值。
     */
    static Integer readIntParameter(ImageCheckItem item, String... keys) {
        if (item == null) {
            return null;
        }
        for (String key : keys) {
            Object value = item.getParameter(key, Object.class);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                logger.warn("[ImageQualityChecker] 参数 {} 的值无法解析为整数: {}", key, text);
            }
        }
        return null;
    }

    /** 按候选键名依次读取字符串参数（黑边检测模式：detectionMode / checkMode） */
    static String readStringParameter(ImageCheckItem item, String... keys) {
        if (item == null) {
            return null;
        }
        for (String key : keys) {
            Object value = item.getParameter(key, Object.class);
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString().trim();
            }
        }
        return null;
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
                    Integer stainThresholdValue = readIntParameter(item, "stainThreshold", "stainValue");
                    if (stainThresholdValue != null) {
                        params.setStainThreshold(stainThresholdValue);
                    }
                    break;
                case "hole":
                case "binding-hole":
                    params.setCheckHole(true);
                    Integer holeThresholdValue = readIntParameter(item, "holeThreshold", "holeValue");
                    if (holeThresholdValue != null) {
                        params.setHoleThreshold(holeThresholdValue);
                    }
                    break;
                case "edge":
                    params.setCheckEdge(true);
                    String edgeMode = readStringParameter(item, "detectionMode", "checkMode");
                    if (edgeMode != null) {
                        if ("strict".equals(edgeMode)) {
                            params.setEdgeStrictMode(2);
                        } else if ("standard".equals(edgeMode)) {
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
            String resourcePath, ImageQualityRule imageRule) {
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
            error.setMessage("文本角度为" + aiResult.getHouseAngle()+ "度");
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());
            errors.add(error);
        } else if (aiResult.getHouseAngle() != null) {
            logger.debug("[convertToCheckErrors] 文本方向角度为0，不需要生成错误");
        }

        if (aiResult.hasStain()) {
            List<int[]> stainLocations = aiResult.getStainLocations();
            logger.info("[ImageQualityChecker] stain detected, stainLocations: {}", stainLocations);
            int stainThreshold = 10;
            if (imageRule != null) {
                ImageCheckItem stainItem = imageRule.getItemByCode("stain");
                Integer configured = readIntParameter(stainItem, "stainThreshold", "stainValue");
                if (configured != null) {
                    stainThreshold = configured;
                }
            }
            // 污点属「数量阈值」类：未超过阈值不报，避免出现「检测到2个污点，超过设定阈值10」
            boolean stainOverThreshold = stainLocations != null && stainLocations.size() > stainThreshold;
            if (stainOverThreshold) {
                CheckError error = new CheckError();
            error.setErrorType("stain");
            error.setCategory("image_quality");

            error.setMessage("检测到" + stainLocations.size() + "个污点，超过阈值" + stainThreshold);
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
            // 装订孔属「数量阈值」类：达到阈值才报（默认 1 个即报）
            int holeThreshold = 1;
            if (imageRule != null) {
                ImageCheckItem holeItem = imageRule.getItemByCode("hole");
                if (holeItem == null) {
                    holeItem = imageRule.getItemByCode("binding-hole");
                }
                Integer configured = readIntParameter(holeItem, "holeThreshold", "holeValue");
                if (configured != null) {
                    holeThreshold = configured;
                }
            }
            boolean holeOverThreshold = holeLocations != null && holeLocations.size() >= holeThreshold;
            if (holeOverThreshold) {
                CheckError error = new CheckError();
            error.setErrorType("hole");
            error.setCategory("image_quality");

            error.setMessage("检测到" + holeLocations.size() + "个装订孔（阈值" + holeThreshold + "）");
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

            // 黑边不设数量阈值：灵敏度只由三档（宽松/标准/严格）决定，检测到即报
            error.setMessage("检测到黑边（" + edgeLocations.size() + "处）");
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
                    error.setMessage("分辨率错误，当前DPI为" + aiResult.getDpi() + "，低于最低要求" + minDpi);
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
                    error.setMessage("图片质量过低，最低要求为: " + minQuality);
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

    /** 目录 → 图像文件（同一目录只列一次，跨行共享，避免同一张图被送检多次）。 */
    private static File[] scanImages(Map<String, File[]> cache, String resourcePath) {
        File folder = new File(resourcePath);
        if (!folder.exists() || !folder.isDirectory()) {
            return null;
        }
        String key = folder.getAbsolutePath();
        File[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        File[] files = folder.listFiles((dir, name) -> {
            String lowerName = name.toLowerCase();
            return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                    || lowerName.endsWith(".png") || lowerName.endsWith(".tif")
                    || lowerName.endsWith(".tiff") || lowerName.endsWith(".bmp")
                    || lowerName.endsWith(".gif") || lowerName.endsWith(".webp");
        });
        if (files == null) {
            files = new File[0];
        }
        cache.put(key, files);
        return files;
    }

    /** 文件名是否属于该件号：命名约定为 {@code {档号}_{件号}-{题名}_P{页码}.jpg}，兼容 1 与 01。 */
    private static boolean matchesPiece(String fileName, String pieceNo) {
        if (pieceNo == null || pieceNo.trim().isEmpty()) {
            return false;
        }
        String no = pieceNo.trim();
        if (fileName.contains("_" + no + "-") || fileName.contains("_" + no + "_")) {
            return true;
        }
        try {
            String plain = String.valueOf(Integer.parseInt(no));
            return !plain.equals(no)
                    && (fileName.contains("_" + plain + "-") || fileName.contains("_" + plain + "_"));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 取单元格文本（去空白）；列不存在返回空串。 */
    private static String cellText(TableView view, int rowIndex, Integer columnIndex) {
        if (columnIndex == null) {
            return "";
        }
        Object value = view.value(rowIndex, columnIndex);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 扁平顺序号命名（0001.jpg / 0000-00.jpg / 0001-02.jpeg）的页序号；其它命名返回 null。 */
    private static Integer flatPageNo(String fileName) {
        if (fileName == null) {
            return null;
        }
        java.util.regex.Matcher m = FLAT_PAGE_PATTERN.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "0001-0004" / "1~4" / "1—4" → {1,4}；无法解析返回 null。 */
    private static int[] parsePageRange(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = PAGE_RANGE_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a <= b ? new int[]{a, b} : new int[]{b, a};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

