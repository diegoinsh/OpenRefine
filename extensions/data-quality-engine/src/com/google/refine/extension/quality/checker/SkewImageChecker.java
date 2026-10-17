/*
 * Data Quality Extension - Skew Image Checker
 * 使用AI检测图像倾斜
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.jiongche.sdk.data.TableView;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.util.TableViews;

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
    public List<ImageCheckError> check(TableView view, List<Integer> rowIndexes, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("skew");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        QualityRulesConfig rulesConfig = TableViews.overlay(view, "qualityRulesConfig", QualityRulesConfig.class);
        if (rulesConfig == null || rulesConfig.getAimpConfig() == null) {
            return errors;
        }

        String aimpEndpoint = rulesConfig.getAimpConfig().getServiceUrl();
        ImageQualityChecker imageQualityChecker = new ImageQualityChecker(view, rulesConfig, aimpEndpoint);

        boolean checkSkew = item.isEnabled();
        Object toleranceParam = item.getParameter("tolerance", Object.class);
        float skewTolerance = toleranceParam != null ? Float.parseFloat(toleranceParam.toString()) : DEFAULT_SKEW_THRESHOLD;

        for (Integer rowIndex : rowIndexes) {
            List<File> imageFiles = extractImageFiles(rule);
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

    private List<File> extractImageFiles(ImageQualityRule rule) {
        List<File> files = new ArrayList<>();
        String resourcePath = extractResourcePath(rule);
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

    private String extractResourcePath(ImageQualityRule rule) {
        ResourceCheckConfig resourceConfig = rule.getResourceConfig();
        return resourceConfig == null ? null : resourceConfig.getBasePath();
    }
}
