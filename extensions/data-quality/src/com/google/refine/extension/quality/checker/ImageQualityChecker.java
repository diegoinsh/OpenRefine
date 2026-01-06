/*
 * Data Quality Extension - Image Quality Checker
 * 图像质量检查：包括DPI、文件大小等参数检查
 * 独立于ResourceConfig运行
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.ImageCheckErrorDetails;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class ImageQualityChecker {

    private static final Logger logger = LoggerFactory.getLogger(ImageQualityChecker.class);
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final Project project;
    private final QualityRulesConfig rules;
    private final String aimpEndpoint;
    private final String interfaceMode;
    private com.google.refine.extension.quality.task.QualityCheckTask task;

    public ImageQualityChecker(Project project, QualityRulesConfig rules, String aimpEndpoint) {
        this.project = project;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint;
        this.interfaceMode = detectInterfaceMode(aimpEndpoint);
    }

    public ImageQualityChecker(Project project, QualityRulesConfig rules, String aimpEndpoint, String interfaceMode) {
        this.project = project;
        this.rules = rules;
        this.aimpEndpoint = aimpEndpoint;
        this.interfaceMode = interfaceMode != null ? interfaceMode : detectInterfaceMode(aimpEndpoint);
    }

    private String detectInterfaceMode(String serviceUrl) {
        if (serviceUrl == null) {
            return ContentChecker.INTERFACE_MODE_7998;
        }
        if (serviceUrl.contains(":7999")) {
            return ContentChecker.INTERFACE_MODE_7999;
        }
        if (serviceUrl.contains(":7998")) {
            return ContentChecker.INTERFACE_MODE_7998;
        }
        return ContentChecker.INTERFACE_MODE_7998;
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

        ContentChecker contentChecker = new ContentChecker(project, rules, aimpEndpoint, interfaceMode);
        contentChecker.setTask(task);

        logger.info("开始遍历所有行，共 " + totalRows + " 行");

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
            String resourcePath = buildResourcePath(row, columnIndexMap, resourceConfig, separator);

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
                    AiCheckResult aiResult = contentChecker.checkImage(imageFile, params);
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
                } catch (Exception e) {
                    logger.warn("图像质量检查失败 for " + imageFile.getName() + ": " + e.getMessage(), e);
                    filesProcessed++;
                    rowPassed = false;
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
        
        logger.info("=== 开始执行空白页和页面尺寸统计 ===");
        collectBlankPageAndPageSizeStatistics(result, project, resourceConfig, imageRule);
        logger.info("=== 空白页和页面尺寸统计结束 ===");
        
        result.complete();

        logger.info("Image quality check completed. Checked: " + checkedRows + ", Passed: " + passedRows + ", Failed: " + failedRows + ", Errors: " + result.getErrors().size());

        return result;
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
               imageRule.getItemByCode("countStats") != null;
    }

    private String buildResourcePath(Row row, Map<String, Integer> columnIndexMap,
            ResourceCheckConfig config, String separator) {
        if (config == null) return null;

        List<String> pathFields = config.getPathFields();
        if (pathFields == null || pathFields.isEmpty()) {
            return config.getBasePath();
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

        if (values.isEmpty()) {
            return config.getBasePath();
        }

        StringBuilder path = new StringBuilder();

        String basePath = config.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            path.append(basePath);
            if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
                path.append(separator);
            }
        }

        String pathMode = config.getPathMode();
        String template = config.getTemplate();

        if ("template".equals(pathMode) && template != null && !template.isEmpty()) {
            for (int i = 0; i < values.size(); i++) {
                template = template.replace("{" + i + "}", values.get(i));
            }
            path.append(template);
        } else {
            path.append(String.join(separator, values));
        }

        return path.toString();
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

        if (aiResult.isBlank()) {
            CheckError error = new CheckError();
            error.setErrorType("blank");
            error.setMessage("Blank page detected: " + imageFile.getName());
            error.setCategory("image_quality");
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());
            errors.add(error);
        }

        if (aiResult.getRectify() != null && Math.abs(aiResult.getRectify()) > 5) {
            CheckError error = new CheckError();
            error.setErrorType("skew");
            error.setCategory("image_quality");
            error.setMessage("Image skew detected: " + imageFile.getName() + ", angle: " + aiResult.getRectify());
            error.setColumn("resource");
            error.setValue(resourcePath);
            error.setHiddenFileName(imageFile.getName());
            errors.add(error);
        }

        if (aiResult.hasStain()) {
            List<int[]> stainLocations = aiResult.getStainLocations();
            logger.info("[ImageQualityChecker] stain detected, stainLocations: {}", stainLocations);
            if (stainLocations != null && !stainLocations.isEmpty()) {
                CheckError error = new CheckError();
                error.setErrorType("stain");
                error.setCategory("image_quality");
                error.setMessage("Stain detected: " + imageFile.getName() + " (Total: " + stainLocations.size() + ")");
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
                error.setMessage("Edge issue detected: " + imageFile.getName() + " (Total: " + edgeLocations.size() + ")");
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
                    error.setMessage("Low DPI detected: " + imageFile.getName() + ", DPI: " + aiResult.getDpi() + " (minimum: " + minDpi + ")");
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
                    error.setMessage("Low JPEG quality detected: " + imageFile.getName() + ", Quality: " + aiResult.getQuality() + " (minimum: " + minQuality + ")");
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
                    error.setMessage("Low bit depth detected: " + imageFile.getName() + ", Bit depth: " + aiResult.getBitDepth() + " (minimum: " + minBitDepth + ")");
                    error.setColumn("resource");
                    error.setValue(resourcePath);
                    error.setExtractedValue(String.valueOf(aiResult.getBitDepth()));
                    error.setHiddenFileName(imageFile.getName());
                    errors.add(error);
                }
            }
        }

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

        ContentChecker contentChecker = new ContentChecker(project, rules, aimpEndpoint, interfaceMode);
        contentChecker.setTask(task);

        for (int rowIndex = 0; rowIndex < project.rows.size(); rowIndex++) {
            Row row = project.rows.get(rowIndex);
            String resourcePath = buildResourcePath(row, columnIndexMap, resourceConfig, separator);

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
                    AiCheckResult aiResult = contentChecker.checkImage(imageFile, params);

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
}
