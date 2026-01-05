/*
 * Data Quality Extension - Quantity Image Checker
 * Counts the number of images in resource folders
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

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class QuantityImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(QuantityImageChecker.class);

    @Override
    public String getItemCode() {
        return "quantity";
    }

    @Override
    public String getItemName() {
        return "数量统计";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("countStats");
        if (item == null) {
            item = rule.getItemByCode("quantity");
        }
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        return new ArrayList<>();
    }

    @Override
    public FileStatistics checkStatistics(Project project, List<Row> rows, ImageQualityRule rule, ResourceCheckConfig resourceConfig) {
        logger.info("checkStatistics方法开始执行");
        FileStatistics statistics = new FileStatistics();

        logger.info("开始数量统计，共 {} 行数据", rows.size());

        logger.info("开始构建columnIndexMap");
        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (com.google.refine.model.Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }
        logger.info("Columns in project: {}", columnIndexMap.keySet());

        logger.info("ResourceConfig: {}", resourceConfig);
        if (resourceConfig == null) {
            logger.info("ResourceConfig为null");
            return statistics;
        }

        logger.info("开始遍历行数据");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            logger.info("处理第 {} 行", i);
            String resourcePath = extractResourcePath(row, resourceConfig, columnIndexMap);
            logger.info("提取到的资源路径: {}", resourcePath);
            if (resourcePath != null) {
                File folder = new File(resourcePath);
                logger.info("文件夹存在: {}, 是目录: {}", folder.exists(), folder.isDirectory());
                if (folder.exists() && folder.isDirectory()) {
                    statistics.incrementFolders(1);
                    logger.info("开始统计文件夹: {}", resourcePath);
                    countFilesInFolder(folder, statistics);
                    logger.info("文件夹统计完成: {}", resourcePath);
                }
            }
        }

        logger.info("数量统计完成 - 文件夹: {}, 总文件: {}, 图片文件: {}, 其他文件: {}", 
                   statistics.getTotalFolders(), statistics.getTotalFiles(), 
                   statistics.getImageFiles(), statistics.getOtherFiles());
        return statistics;
    }

    private void countFilesInFolder(File folder, FileStatistics statistics) {
        File[] items = folder.listFiles();
        if (items == null) return;

        for (File item : items) {
            if (item.isDirectory()) {
                countFilesInFolder(item, statistics);
            } else if (item.isFile()) {
                statistics.incrementFiles(1);
                if (isImageFile(item)) {
                    statistics.incrementImageFiles(1);
                } else {
                    statistics.incrementOtherFiles(1);
                }
            }
        }
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") ||
               name.endsWith(".tif") || name.endsWith(".tiff") ||
               name.endsWith(".png") || name.endsWith(".bmp") ||
               name.endsWith(".gif") || name.endsWith(".webp") ||
               name.endsWith(".pdf");
    }

    private List<File> extractImageFiles(File folder) {
        List<File> files = new ArrayList<>();
        if (folder.exists() && folder.isDirectory()) {
            File[] found = folder.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                       lower.endsWith(".tif") || lower.endsWith(".tiff") ||
                       lower.endsWith(".png") || lower.endsWith(".bmp") ||
                       lower.endsWith(".gif") || lower.endsWith(".webp") ||
                       lower.endsWith(".pdf");
            });
            if (found != null) {
                files.addAll(Arrays.asList(found));
            }
        }
        return files;
    }

    private String extractResourcePath(Row row, ResourceCheckConfig resourceConfig, Map<String, Integer> columnIndexMap) {
        logger.info("ResourceConfig: {}", resourceConfig);
        if (resourceConfig == null) {
            logger.info("ResourceConfig为null");
            return null;
        }
        
        String basePath = resourceConfig.getBasePath();
        logger.info("BasePath: {}", basePath);
        
        if (basePath == null) {
            return null;
        }
        
        List<String> pathFields = resourceConfig.getPathFields();
        logger.info("PathFields: {}", pathFields);
        
        if (pathFields == null || pathFields.isEmpty()) {
            return basePath;
        }
        
        List<String> values = new ArrayList<>();
        for (String fieldName : pathFields) {
            logger.info("字段名称: {}", fieldName);
            Integer cellIndex = columnIndexMap.get(fieldName);
            logger.info("单元格索引: {}", cellIndex);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                logger.info("单元格: {}", cell);
                if (cell != null && cell.value != null) {
                    String value = cell.value.toString().trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                        logger.info("追加值: {}", value);
                    }
                }
            }
        }
        
        if (values.isEmpty()) {
            return basePath;
        }
        
        StringBuilder path = new StringBuilder(basePath);
        if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
            path.append(File.separator);
        }
        
        String pathMode = resourceConfig.getPathMode();
        String template = resourceConfig.getTemplate();
        logger.info("PathMode: {}, Template: {}", pathMode, template);
        
        if ("template".equals(pathMode) && template != null && !template.isEmpty()) {
            String resultTemplate = template;
            for (int i = 0; i < values.size(); i++) {
                resultTemplate = resultTemplate.replace("{" + i + "}", values.get(i));
            }
            path.append(resultTemplate);
            logger.info("使用模板构建路径: {}", resultTemplate);
        } else {
            path.append(String.join(File.separator, values));
            logger.info("使用分隔符构建路径");
        }
        
        String resultPath = path.toString();
        logger.info("最终路径: {}", resultPath);
        return resultPath;
    }
}
