/*
 * Data Quality Extension - Edge Image Checker
 * 使用AI检测黑边
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class EdgeImageChecker implements ImageChecker {

    private static final int DEFAULT_EDGE_STRICT_MODE = 0;

    @Override
    public String getItemCode() {
        return "edge";
    }

    @Override
    public String getItemName() {
        return "黑边检测";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("edge");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("edge");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        ContentChecker contentChecker = new ContentChecker();
        boolean checkEdge = item.isEnabled();
        Object strictModeParam = item.getParameter("strictMode", Object.class);
        int edgeStrictMode = strictModeParam != null ? Integer.parseInt(strictModeParam.toString()) : DEFAULT_EDGE_STRICT_MODE;

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                AiCheckParams params = new AiCheckParams();
                params.setCheckEdge(checkEdge);
                params.setEdgeStrictMode(edgeStrictMode);

                AiCheckResult result = contentChecker.checkImage(imageFile, params);

                if (result.hasEdgeRemove()) {
                    errors.add(ImageCheckError.createEdgeError(
                        -1,
                        "resource",
                        imageFile.getParent(),
                        imageFile.getName()));
                }
            }
        }

        return errors;
    }

    private List<File> extractImageFiles(Row row, ImageQualityRule rule) {
        List<File> files = new ArrayList<>();
        String resourcePath = extractResourcePath(row, rule);
        if (resourcePath != null) {
            File folder = new File(resourcePath);
            if (folder.exists() && folder.isDirectory()) {
                File[] found = folder.listFiles((dir, name) -> {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                           lower.endsWith(".tif") || lower.endsWith(".tiff") ||
                           lower.endsWith(".png") || lower.endsWith(".bmp") ||
                           lower.endsWith(".gif") || lower.endsWith(".webp");
                });
                if (found != null) {
                    for (File f : found) {
                        if (f.isFile()) {
                            files.add(f);
                        }
                    }
                }
            }
        }
        return files;
    }

    private String extractResourcePath(Row row, ImageQualityRule rule) {
        ResourceCheckConfig resourceConfig = rule.getResourceConfig();
        if (resourceConfig == null) {
            return null;
        }
        if (resourceConfig.getPathFields() != null && !resourceConfig.getPathFields().isEmpty()) {
            String fieldName = resourceConfig.getPathFields().get(0);
            Integer cellIndex = getColumnIndex(rule, fieldName);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                if (cell != null && cell.value != null) {
                    return cell.value.toString();
                }
            }
        }
        return resourceConfig.getBasePath();
    }

    private Integer getColumnIndex(ImageQualityRule rule, String fieldName) {
        return rule.getProjectColumnIndex(fieldName);
    }
}
