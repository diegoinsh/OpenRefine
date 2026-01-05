/*
 * Data Quality Extension - DPI Image Checker
 * Checks if image DPI meets the minimum requirement
 */
package com.google.refine.extension.quality.checker;

import java.awt.image.BufferedImage;
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

public class DpiImageChecker implements ImageChecker {

    @Override
    public String getItemCode() {
        return "dpi";
    }

    @Override
    public String getItemName() {
        return "DPI检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("dpi");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("dpi");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Integer minDpi = item.getParameter("minDpi", Integer.class);
        if (minDpi == null || minDpi <= 0) {
            minDpi = 300;
        }

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                int actualDpi = getImageDpi(imageFile);
                if (actualDpi > 0 && actualDpi < minDpi) {
                    errors.add(ImageCheckError.createDpiError(
                        -1, // Row index will be set later in ImageQualityChecker
                        "resource", imageFile.getParent(), imageFile.getName(),
                        minDpi, actualDpi));
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

    private int getImageDpi(File imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                return 0;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width == 0 || height == 0) {
                return 0;
            }
            int horizontalSize = imageFile.getName().toLowerCase().endsWith(".tif") ||
                                 imageFile.getName().toLowerCase().endsWith(".tiff") ? 2480 : 0;
            if (horizontalSize > 0) {
                return horizontalSize * 254 / 1000;
            }
            return 96;
        } catch (IOException e) {
            return 0;
        }
    }
}
