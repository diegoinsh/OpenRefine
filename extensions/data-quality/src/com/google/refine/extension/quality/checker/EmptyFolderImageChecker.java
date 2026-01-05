/*
 * Data Quality Extension - Empty Folder Image Checker
 * Checks if a resource folder is empty
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class EmptyFolderImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(EmptyFolderImageChecker.class);

    @Override
    public String getItemCode() {
        return "empty_folder";
    }

    @Override
    public String getItemName() {
        return "空文件夹检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("empty_folder");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("empty_folder");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        for (Row row : rows) {
            String resourcePath = extractResourcePath(row, rule);
            if (resourcePath != null) {
                File folder = new File(resourcePath);
                if (folder.exists() && folder.isDirectory()) {
                    boolean isEmpty = isFolderEmpty(folder);
                    if (isEmpty) {
                        errors.add(ImageCheckError.createEmptyFolderError(
                            -1,
                            "resource",
                            resourcePath));
                    }
                }
            }
        }

        logger.info("空文件夹检查完成，发现 {} 个空文件夹", errors.size());
        return errors;
    }

    private boolean isFolderEmpty(File folder) {
        if (!folder.exists() || !folder.isDirectory()) {
            return false;
        }

        String[] files = folder.list();
        if (files == null) {
            return false;
        }

        for (String fileName : files) {
            File file = new File(folder, fileName);
            if (file.isFile() && isImageFile(fileName)) {
                return false;
            }
        }

        return true;
    }

    private boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".tif") || lower.endsWith(".tiff") ||
               lower.endsWith(".png") || lower.endsWith(".bmp") ||
               lower.endsWith(".gif") || lower.endsWith(".webp") ||
               lower.endsWith(".pdf");
    }

    private String extractResourcePath(Row row, ImageQualityRule rule) {
        ResourceCheckConfig resourceConfig = rule.getResourceConfig();
        if (resourceConfig == null) {
            return null;
        }
        if (resourceConfig.getPathFields() != null && !resourceConfig.getPathFields().isEmpty()) {
            String fieldName = resourceConfig.getPathFields().get(0);
            Integer cellIndex = rule.getProjectColumnIndex(fieldName);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                if (cell != null && cell.value != null) {
                    return cell.value.toString();
                }
            }
        }
        return resourceConfig.getBasePath();
    }
}
