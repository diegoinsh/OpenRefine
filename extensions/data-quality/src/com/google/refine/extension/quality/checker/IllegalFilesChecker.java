/*
 * Data Quality Extension - Illegal Files Checker
 * Checks for files with extensions that are not allowed for archival
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.util.AntivirusDetector;
import com.google.refine.extension.quality.util.ResourcePathBuilder;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class IllegalFilesChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(IllegalFilesChecker.class);

    private static final java.util.Set<String> ALLOWED_FORMATS = java.util.Set.of(
        "jpeg", "jpg", "tiff", "tif", "pdf", "ofd"
    );

    @Override
    public String getItemCode() {
        return "illegal_files";
    }

    @Override
    public String getItemName() {
        return "非法归档文件检测";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("illegalFiles");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        return check(project, rows, rule, null);
    }

    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule, ResourceCheckConfig externalResourceConfig) {
        logger.info("=== 开始非法归档文件检测 ===");
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("illegalFiles");
        if (item == null || !isEnabled(rule)) {
            logger.info("非法归档文件检测未启用: item={}, enabled={}", item, item != null ? item.isEnabled() : false);
            return errors;
        }

        logger.info("非法归档文件检测已启用");

        @SuppressWarnings("unchecked")
        List<String> allowedFormats = (List<String>) item.getParameter("allowedFormats", List.class);
        Set<String> allowedSet = allowedFormats != null
            ? allowedFormats.stream().map(String::toLowerCase).collect(Collectors.toSet())
            : ALLOWED_FORMATS;

        logger.info("允许的文件格式: {}", allowedSet);

        String antivirusCheck = (String) item.getParameter("antivirusCheck", String.class);
        String antivirusProcess = (String) item.getParameter("antivirusProcess", String.class);
        
        if ("auto".equals(antivirusCheck)) {
            logger.info("杀毒软件检测模式: 自动");
            List<AntivirusDetector.AntivirusInfo> antivirusList = AntivirusDetector.detectAntivirus();
            if (antivirusList.isEmpty()) {
                logger.warn("未检测到杀毒软件，安全性检测不通过");
                errors.add(ImageCheckError.createSecurityError(
                    -1,
                    "system",
                    "",
                    "未检测到杀毒软件",
                    "请安装并启用杀毒软件以保障档案安全"));
                return errors;
            } else {
                logger.info("检测到杀毒软件: {}", AntivirusDetector.getAntivirusStatus());
            }
        } else if ("custom".equals(antivirusCheck) && antivirusProcess != null && !antivirusProcess.trim().isEmpty()) {
            logger.info("杀毒软件检测模式: 自定义，进程名称: {}", antivirusProcess);
            boolean processRunning = AntivirusDetector.isProcessRunning(antivirusProcess);
            if (!processRunning) {
                logger.warn("指定的杀毒软件进程未运行: {}", antivirusProcess);
                errors.add(ImageCheckError.createSecurityError(
                    -1,
                    "system",
                    "",
                    "杀毒软件未运行",
                    "指定的杀毒软件进程 " + antivirusProcess + " 未运行，请检查杀毒软件是否已启动"));
                return errors;
            } else {
                logger.info("指定的杀毒软件进程正在运行: {}", antivirusProcess);
            }
        } else {
            logger.info("未启用杀毒软件检测");
        }

        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        String separator = "/";
        ResourceCheckConfig resourceConfig = externalResourceConfig != null ? externalResourceConfig : rule.getResourceConfig();
        if (resourceConfig != null && resourceConfig.getSeparator() != null) {
            separator = resourceConfig.getSeparator();
        }

        logger.info("路径分隔符: {}", separator);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            logger.info("处理第 {} 行", i);
            
            String resourcePath = ResourcePathBuilder.buildResourcePath(row, columnIndexMap, resourceConfig, separator);
            logger.info("第 {} 行资源路径: {}", i, resourcePath);
            
            List<File> allFiles = extractAllFiles(resourcePath);
            logger.info("第 {} 行找到 {} 个文件", i, allFiles.size());
            
            for (File file : allFiles) {
                String extension = getFileExtension(file.getName()).toLowerCase();
                logger.info("检查文件: {}, 扩展名: {}, 是否允许: {}", file.getName(), extension, allowedSet.contains(extension));
                
                if (!allowedSet.contains(extension)) {
                    String allowedFormatsStr = String.join(", ", allowedSet);
                    logger.warn("发现非法归档文件: {}, 路径: {}, 允许的格式: {}, 实际格式: {}", 
                        file.getName(), file.getParent(), allowedFormatsStr.toUpperCase(), extension.toUpperCase());
                    errors.add(ImageCheckError.createIllegalFileError(
                        -1,
                        "resource",
                        file.getParent(),
                        file.getName(),
                        allowedFormatsStr.toUpperCase(),
                        extension.toUpperCase()));
                }
            }
        }

        logger.info("非法归档文件检测完成，发现 {} 个非法文件", errors.size());
        logger.info("=== 非法归档文件检测结束 ===");
        return errors;
    }

    private List<File> extractAllFiles(String resourcePath) {
        List<File> files = new ArrayList<>();
        if (resourcePath != null) {
            File folder = new File(resourcePath);
            if (folder.exists() && folder.isDirectory()) {
                File[] found = folder.listFiles((dir, name) -> {
                    String lower = name.toLowerCase();
                    return !lower.startsWith(".");
                });
                if (found != null) {
                    files.addAll(Arrays.asList(found));
                }
            }
        }
        return files;
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}
