/*
 * Data Quality Extension - Quality Image Checker
 * Checks if image compression quality meets the minimum ratio
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class QualityImageChecker implements ImageChecker {

    @Override
    public String getItemCode() {
        return "quality";
    }

    @Override
    public String getItemName() {
        return "图像质量检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("quality");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("quality");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Integer minQualityRatio = item.getParameter("minQualityRatio", Integer.class);
        if (minQualityRatio == null || minQualityRatio <= 0) {
            minQualityRatio = 80;
        }

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                int qualityRatio = calculateQualityRatio(imageFile);
                if (qualityRatio > 0 && qualityRatio < minQualityRatio) {
                    errors.add(ImageCheckError.createQualityError(
                        -1, // Row index will be set later in ImageQualityChecker
                        "resource", imageFile.getParent(), imageFile.getName(),
                        minQualityRatio, qualityRatio));
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
                           lower.endsWith(".png") || lower.endsWith(".bmp");
                });
                if (found != null) {
                    files.addAll(Arrays.asList(found));
                }
            }
        }
        return files;
    }

    private String extractResourcePath(Row row, ImageQualityRule rule) {
        var resourceConfig = rule.getResourceConfig();
        if (resourceConfig == null || resourceConfig.getPathFields() == null) {
            return resourceConfig != null ? resourceConfig.getBasePath() : null;
        }
        return null;
    }

    private int calculateQualityRatio(File imageFile) {
        try {
            long originalSize = imageFile.length();
            var image = ImageIO.read(imageFile);
            if (image == null) {
                return 100;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            int pixelCount = width * height;
            int bytesPerPixel = 3;
            int rawSize = pixelCount * bytesPerPixel;

            double compressionRatio = (double) originalSize / rawSize * 100;
            int qualityRatio = (int) Math.min(100, Math.max(1, compressionRatio * 10));

            return qualityRatio;
        } catch (IOException e) {
            return 100;
        }
    }
}
