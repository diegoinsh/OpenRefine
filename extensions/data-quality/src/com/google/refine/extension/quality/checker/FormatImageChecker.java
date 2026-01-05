/*
 * Data Quality Extension - Format Image Checker
 * Checks if image format is in the allowed list
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class FormatImageChecker implements ImageChecker {

    private static final java.util.Set<String> SUPPORTED_FORMATS = java.util.Set.of(
        "jpeg", "jpg", "tiff", "tif", "pdf", "png", "bmp", "gif", "webp"
    );

    @Override
    public String getItemCode() {
        return "format";
    }

    @Override
    public String getItemName() {
        return "格式检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("format");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("format");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        @SuppressWarnings("unchecked")
        List<String> allowedFormats = (List<String>) item.getParameter("allowedFormats", List.class);
        Set<String> allowedSet = allowedFormats != null
            ? allowedFormats.stream().map(String::toLowerCase).collect(Collectors.toSet())
            : java.util.Set.of("jpeg", "jpg", "tiff", "tif", "pdf");

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                String extension = getFileExtension(imageFile.getName()).toLowerCase();
                if (!allowedSet.contains(extension)) {
                    String allowedFormatsStr = String.join(", ", allowedSet);
                    errors.add(ImageCheckError.createFormatError(
                        -1, // Row index will be set later in ImageQualityChecker
                        "resource", imageFile.getParent(), imageFile.getName(),
                        allowedFormatsStr.toUpperCase(), extension.toUpperCase()));
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
                           lower.endsWith(".pdf") || lower.endsWith(".png") ||
                           lower.endsWith(".bmp") || lower.endsWith(".gif") ||
                           lower.endsWith(".webp");
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

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}
