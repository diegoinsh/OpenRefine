/*
 * Data Quality Extension - Resource Path Builder Utility
 * Provides utility methods for building resource paths from row data
 */
package com.google.refine.extension.quality.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jiongche.sdk.data.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ResourceCheckConfig;

public class ResourcePathBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ResourcePathBuilder.class);

    public static String buildResourcePath(TableView view, int rowIndex, Map<String, Integer> columnIndexMap,
            ResourceCheckConfig config, String separator) {
        logger.info("buildResourcePath - 开始构建路径");
        
        if (config == null) {
            logger.info("buildResourcePath - config为null，返回null");
            return null;
        }

        List<String> pathFields = config.getPathFields();
        logger.info("buildResourcePath - pathFields: {}", pathFields);

        // 「仅图像审查」项目没有条目列（如「文件路径」），此时按目录层级列逐级拼路径
        boolean levelMode = false;
        if (!resolves(pathFields, columnIndexMap)) {
            List<String> levels = levelFields(columnIndexMap);
            if (!levels.isEmpty()) {
                logger.info("buildResourcePath - 目录层级模式，改用列: {}", levels);
                pathFields = levels;
                levelMode = true;
            }
        }

        if (pathFields == null || pathFields.isEmpty()) {
            String basePath = config.getBasePath();
            logger.info("buildResourcePath - pathFields为空，直接返回basePath: {}", basePath);
            return basePath;
        }

        List<String> values = new ArrayList<>();
        for (String fieldName : pathFields) {
            Integer cellIndex = columnIndexMap.get(fieldName);
            logger.info("buildResourcePath - 字段名: {}, cellIndex: {}", fieldName, cellIndex);
            
            if (cellIndex != null) {
                Object cellValue = view.value(rowIndex, cellIndex);
                String value = cellValue != null ? cellValue.toString().trim() : "";
                logger.info("buildResourcePath - 字段值: {}", value);
                
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }

        logger.info("buildResourcePath - 提取的values: {}", values);

        if (values.isEmpty()) {
            String basePath = config.getBasePath();
            logger.info("buildResourcePath - values为空，返回basePath: {}", basePath);
            return basePath;
        }

        StringBuilder path = new StringBuilder();

        String basePath = config.getBasePath();
        logger.info("buildResourcePath - basePath: {}", basePath);
        
        if (basePath != null && !basePath.isEmpty()) {
            path.append(basePath);
            if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
                path.append(separator);
            }
            logger.info("buildResourcePath - 添加basePath后: {}", path);
        }

        String pathMode = config.getPathMode();
        String template = config.getTemplate();
        logger.info("buildResourcePath - pathMode: {}, template: {}", pathMode, template);

        if (!levelMode && "template".equals(pathMode) && template != null && !template.isEmpty()) {
            logger.info("buildResourcePath - 使用template模式");
            for (int i = 0; i < values.size(); i++) {
                template = template.replace("{" + i + "}", values.get(i));
            }
            path.append(template);
            logger.info("buildResourcePath - template替换后: {}", template);
        } else {
            logger.info("buildResourcePath - 使用普通拼接模式");
            path.append(String.join(separator, values));
        }

        String finalPath = path.toString();
        logger.info("buildResourcePath - 最终路径: {}", finalPath);
        return finalPath;
    }

    /** 配置里的列名在表中是否至少能命中一个（命中即按配置拼路径）。 */
    private static boolean resolves(List<String> pathFields, Map<String, Integer> columnIndexMap) {
        if (pathFields == null || pathFields.isEmpty()) {
            return false;
        }
        for (String field : pathFields) {
            if (columnIndexMap.get(field) != null) {
                return true;
            }
        }
        return false;
    }

    /** 目录层级列（一级目录、二级目录…），按列存在顺序收集。 */
    private static List<String> levelFields(Map<String, Integer> columnIndexMap) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 99; i++) {
            String name = chineseNumber(i) + "级目录";
            if (columnIndexMap.get(name) == null) {
                break;
            }
            out.add(name);
        }
        return out;
    }

    private static String chineseNumber(int n) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 10) {
            return digits[n];
        }
        if (n < 20) {
            return "十" + (n % 10 == 0 ? "" : digits[n % 10]);
        }
        if (n < 100) {
            return digits[n / 10] + "十" + (n % 10 == 0 ? "" : digits[n % 10]);
        }
        return String.valueOf(n);
    }
}
