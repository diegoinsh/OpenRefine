/*
 * Data Quality Extension - Stain Image Checker
 * 使用AI检测图像污点
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.jiongche.sdk.data.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.util.TableViews;

public class StainImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(StainImageChecker.class);
    private static final int DEFAULT_STAIN_THRESHOLD = 100;

    @Override
    public String getItemCode() {
        return "stain";
    }

    @Override
    public String getItemName() {
        return "污点检测";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("stain");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(TableView view, List<Integer> rowIndexes, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("stain");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        QualityRulesConfig rulesConfig = TableViews.overlay(view, "qualityRulesConfig", QualityRulesConfig.class);
        if (rulesConfig == null || rulesConfig.getAimpConfig() == null) {
            return errors;
        }

        String aimpEndpoint = rulesConfig.getAimpConfig().getServiceUrl();
        ImageQualityChecker imageQualityChecker = new ImageQualityChecker(view, rulesConfig, aimpEndpoint);

        boolean checkStain = item.isEnabled();
        Object thresholdParam = item.getParameter("threshold", Object.class);
        int stainThreshold = thresholdParam != null ? Integer.parseInt(thresholdParam.toString()) : DEFAULT_STAIN_THRESHOLD;

        for (Integer rowIndex : rowIndexes) {
            List<File> imageFiles = extractImageFiles(rule);
            for (File imageFile : imageFiles) {
                AiCheckParams params = new AiCheckParams();
                params.setCheckStain(checkStain);
                params.setStainThreshold(stainThreshold);

                AiCheckResult result = imageQualityChecker.checkImage(imageFile, params);

                if (result.hasStain()) {
                    String imagePath = imageFile.getParent();
                    String imageName = imageFile.getName();
                    logger.info("[StainImageChecker] 检测到污点 - imagePath: {}, imageName: {}, stainLocations: {}",
                        imagePath, imageName, result.getStainLocations());

                    List<int[]> stainLocations = result.getStainLocations();
                    if (stainLocations != null && !stainLocations.isEmpty()) {
                        for (int[] location : stainLocations) {
                            Integer locationX = location.length > 0 ? location[0] : null;
                            Integer locationY = location.length > 1 ? location[1] : null;
                            logger.info("[StainImageChecker] 添加污点错误 - locationX: {}, locationY: {}", locationX, locationY);
                            errors.add(ImageCheckError.createStainError(
                                -1,
                                "resource",
                                imagePath,
                                imageName,
                                stainLocations.size(),
                                stainThreshold,
                                locationX,
                                locationY));
                        }
                    } else {
                        logger.info("[StainImageChecker] 有污点但没有位置信息，使用默认位置");
                        errors.add(ImageCheckError.createStainError(
                            -1,
                            "resource",
                            imagePath,
                            imageName,
                            1,
                            stainThreshold,
                            null,
                            null));
                    }
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
