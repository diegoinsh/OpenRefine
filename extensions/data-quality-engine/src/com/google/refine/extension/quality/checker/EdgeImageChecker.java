/*
 * Data Quality Extension - Edge Image Checker
 * 使用AI检测黑边
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

public class EdgeImageChecker implements ImageChecker {

    private static final int DEFAULT_EDGE_STRICT_MODE = 1;

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
    public List<ImageCheckError> check(TableView view, List<Integer> rowIndexes, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("edge");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        QualityRulesConfig rulesConfig = TableViews.overlay(view, "qualityRulesConfig", QualityRulesConfig.class);
        if (rulesConfig == null || rulesConfig.getAimpConfig() == null) {
            return errors;
        }

        String aimpEndpoint = rulesConfig.getAimpConfig().getServiceUrl();
        ImageQualityChecker imageQualityChecker = new ImageQualityChecker(view, rulesConfig, aimpEndpoint);

        boolean checkEdge = item.isEnabled();
        Object strictModeParam = item.getParameter("strictMode", Object.class);
        int edgeStrictMode = strictModeParam != null ? Integer.parseInt(strictModeParam.toString()) : DEFAULT_EDGE_STRICT_MODE;

        for (Integer rowIndex : rowIndexes) {
            List<File> imageFiles = extractImageFiles(rule);
            for (File imageFile : imageFiles) {
                AiCheckParams params = new AiCheckParams();
                params.setCheckEdge(checkEdge);
                params.setEdgeStrictMode(edgeStrictMode);

                AiCheckResult result = imageQualityChecker.checkImage(imageFile, params);

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
