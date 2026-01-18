/*
 * Data Quality Extension - Hole Image Checker
 * 使用AI检测装订孔
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
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class HoleImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(HoleImageChecker.class);
    private static final int DEFAULT_HOLE_THRESHOLD = 100;

    @Override
    public String getItemCode() {
        return "hole";
    }

    @Override
    public String getItemName() {
        return "装订孔检测";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("hole");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("hole");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        QualityRulesConfig rulesConfig = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");
        if (rulesConfig == null || rulesConfig.getAimpConfig() == null) {
            return errors;
        }

        String aimpEndpoint = rulesConfig.getAimpConfig().getServiceUrl();
        ImageQualityChecker imageQualityChecker = new ImageQualityChecker(project, rulesConfig, aimpEndpoint);

        boolean checkHole = item.isEnabled();
        Object thresholdParam = item.getParameter("threshold", Object.class);
        int holeThreshold = thresholdParam != null ? Integer.parseInt(thresholdParam.toString()) : DEFAULT_HOLE_THRESHOLD;

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                AiCheckParams params = new AiCheckParams();
                params.setCheckHole(checkHole);
                params.setHoleThreshold(holeThreshold);

                AiCheckResult result = imageQualityChecker.checkImage(imageFile, params);

                if (result.hasHole()) {
                    String imagePath = imageFile.getParent();
                    String imageName = imageFile.getName();
                    logger.info("[HoleImageChecker] 检测到装订孔 - imagePath: {}, imageName: {}, holeLocations: {}",
                        imagePath, imageName, result.getHoleLocations());

                    List<int[]> holeLocations = result.getHoleLocations();
                    if (holeLocations != null && !holeLocations.isEmpty()) {
                        for (int[] location : holeLocations) {
                            Integer locationX = location.length > 0 ? location[0] : null;
                            Integer locationY = location.length > 1 ? location[1] : null;
                            logger.info("[HoleImageChecker] 添加装订孔错误 - locationX: {}, locationY: {}", locationX, locationY);
                            errors.add(ImageCheckError.createHoleError(
                                -1,
                                "resource",
                                imagePath,
                                imageName,
                                holeLocations.size(),
                                holeThreshold,
                                locationX,
                                locationY));
                        }
                    } else {
                        logger.info("[HoleImageChecker] 有装订孔但没有位置信息，使用默认位置");
                        errors.add(ImageCheckError.createHoleError(
                            -1,
                            "resource",
                            imagePath,
                            imageName,
                            1,
                            holeThreshold,
                            null,
                            null));
                    }
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
