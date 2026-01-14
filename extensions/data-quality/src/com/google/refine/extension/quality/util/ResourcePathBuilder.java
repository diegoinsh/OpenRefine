/*
 * Data Quality Extension - Resource Path Builder Utility
 * Provides utility methods for building resource paths from row data
 */
package com.google.refine.extension.quality.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Row;

public class ResourcePathBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ResourcePathBuilder.class);

    public static String buildResourcePath(Row row, Map<String, Integer> columnIndexMap,
            ResourceCheckConfig config, String separator) {
        logger.info("buildResourcePath - 开始构建路径");
        
        if (config == null) {
            logger.info("buildResourcePath - config为null，返回null");
            return null;
        }

        List<String> pathFields = config.getPathFields();
        logger.info("buildResourcePath - pathFields: {}", pathFields);
        
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
                Cell cell = row.getCell(cellIndex);
                String value = cell != null && cell.value != null ? cell.value.toString().trim() : "";
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

        if ("template".equals(pathMode) && template != null && !template.isEmpty()) {
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
}
