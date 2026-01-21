/*
 * Data Quality Extension - Size Image Checker
 * Checks if image dimensions are within the allowed range
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

public class SizeImageChecker implements ImageChecker {

    @Override
    public String getItemCode() {
        return "size";
    }

    @Override
    public String getItemName() {
        return "图像尺寸检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("size");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("size");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Integer minWidth = item.getParameter("minWidth", Integer.class);
        Integer maxWidth = item.getParameter("maxWidth", Integer.class);
        Integer minHeight = item.getParameter("minHeight", Integer.class);
        Integer maxHeight = item.getParameter("maxHeight", Integer.class);

        if (minWidth == null || minWidth <= 0) minWidth = 100;
        if (maxWidth == null || maxWidth <= 0) maxWidth = 10000;
        if (minHeight == null || minHeight <= 0) minHeight = 100;
        if (maxHeight == null || maxHeight <= 0) maxHeight = 10000;

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                int[] dimensions = getImageDimensions(imageFile);
                if (dimensions == null) {
                    continue;
                }
                int actualWidth = dimensions[0];
                int actualHeight = dimensions[1];

                boolean widthError = actualWidth < minWidth || actualWidth > maxWidth;
                boolean heightError = actualHeight < minHeight || actualHeight > maxHeight;

                if (widthError || heightError) {
                    StringBuilder message = new StringBuilder("图像尺寸超出范围");
                    message.append(String.format(": 期望 %dx%d - %dx%d, 实际 %dx%d",
                        minWidth, minHeight, maxWidth, maxHeight, actualWidth, actualHeight));
                    errors.add(createSizeError(-1, "resource", imageFile.getParent(),
                        imageFile.getName(), minWidth, maxWidth, minHeight, maxHeight,
                        actualWidth, actualHeight, message.toString()));
                }
            }
        }

        return errors;
    }

    private ImageCheckError createSizeError(int rowIndex, String columnName, String imagePath,
            String imageName, Integer minWidth, Integer maxWidth, Integer minHeight, Integer maxHeight,
            int actualWidth, int actualHeight, String message) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "size_error", message);
        error.setErrorCode("SIZE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory("可用性");
        error.setSeverity("error");
        ImageCheckError.ImageCheckErrorDetails details = new ImageCheckError.ImageCheckErrorDetails();
        details.setExpectedValue(String.format("%dx%d - %dx%d", minWidth, minHeight, maxWidth, maxHeight));
        details.setActualValue(String.format("%dx%d", actualWidth, actualHeight));
        error.setDetails(details);
        return error;
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

    private int[] getImageDimensions(File imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                return null;
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (IOException e) {
            return null;
        }
    }
}
