/*
 * Data Quality Extension - Bit Depth Image Checker
 * Checks if image bit depth meets the minimum requirement
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

public class BitDepthImageChecker implements ImageChecker {

    @Override
    public String getItemCode() {
        return "bit_depth";
    }

    @Override
    public String getItemName() {
        return "位深度检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("bit_depth");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("bit_depth");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Integer minBitDepth = item.getParameter("minBitDepth", Integer.class);
        if (minBitDepth == null || minBitDepth <= 0) {
            minBitDepth = 8;
        }

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                int actualBitDepth = getImageBitDepth(imageFile);
                if (actualBitDepth > 0 && actualBitDepth < minBitDepth) {
                    errors.add(createBitDepthError(-1, "resource", imageFile.getParent(),
                        imageFile.getName(), minBitDepth, actualBitDepth));
                }
            }
        }

        return errors;
    }

    private ImageCheckError createBitDepthError(int rowIndex, String columnName, String imagePath,
            String imageName, int minBitDepth, int actualBitDepth) {
        String message = String.format("位深度不足: 期望 >= %d位, 实际 %d位", minBitDepth, actualBitDepth);
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "bit_depth_error", message);
        error.setErrorCode("BIT_DEPTH_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory("可用性");
        error.setSeverity("error");
        ImageCheckError.ImageCheckErrorDetails details = new ImageCheckError.ImageCheckErrorDetails();
        details.setExpectedValue(minBitDepth);
        details.setActual(actualBitDepth);
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

    private int getImageBitDepth(File imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                return 0;
            }
            int bitDepth = image.getColorModel().getPixelSize();
            return bitDepth > 0 ? bitDepth : 24;
        } catch (IOException e) {
            return 0;
        }
    }
}
