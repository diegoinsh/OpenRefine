/*
 * Data Quality Extension - Skew Image Checker
 * 使用AI检测图像倾斜
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class SkewImageChecker implements ImageChecker {

    private static final float DEFAULT_SKEW_THRESHOLD = 0.5f;

    @Override
    public String getItemCode() {
        return "skew";
    }

    @Override
    public String getItemName() {
        return "倾斜检测";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("skew");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("skew");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        QualityRulesConfig rulesConfig = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");
        if (rulesConfig == null || rulesConfig.getAimpConfig() == null) {
            return errors;
        }

        String aimpEndpoint = rulesConfig.getAimpConfig().getServiceUrl();
        ImageQualityChecker imageQualityChecker = new ImageQualityChecker(project, rulesConfig, aimpEndpoint);

        boolean checkSkew = item.isEnabled();
        Object toleranceParam = item.getParameter("tolerance", Object.class);
        float skewTolerance = toleranceParam != null ? Float.parseFloat(toleranceParam.toString()) : DEFAULT_SKEW_THRESHOLD;

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                AiCheckParams params = new AiCheckParams();
                params.setCheckSkew(checkSkew);
                params.setSkewTolerance(String.valueOf(skewTolerance));

                AiCheckResult result = imageQualityChecker.checkImage(imageFile, params);

                Float rectifyAngle = result.getRectify();
                if (rectifyAngle != null && Math.abs(rectifyAngle) > skewTolerance) {
                    errors.add(ImageCheckError.createSkewError(
                        -1,
                        "resource",
                        imageFile.getParent(),
                        imageFile.getName(),
                        rectifyAngle,
                        skewTolerance));
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
            Integer cellIndex = getColumnIndex(row, rule, fieldName);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                if (cell != null && cell.value != null) {
                    return cell.value.toString();
                }
            }
        }
        return resourceConfig.getBasePath();
    }

    private Integer getColumnIndex(Row row, ImageQualityRule rule, String fieldName) {
        return rule.getProjectColumnIndex(fieldName);
    }
}
