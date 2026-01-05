/*
 * Data Quality Extension - PDF Image Uniformity Checker
 * Checks for consistency between PDF files and images
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class PdfImageUniformityChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(PdfImageUniformityChecker.class);

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".tif", ".tiff", ".png", ".bmp", ".gif", ".webp"};
    private static final String PDF_EXTENSION = ".pdf";

    @Override
    public String getItemCode() {
        return "pdf_image_uniformity";
    }

    @Override
    public String getItemName() {
        return "PDF与图片一致性";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("pdf_image_uniformity");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("pdf_image_uniformity");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        for (Row row : rows) {
            String resourcePath = extractResourcePath(row, rule);
            if (resourcePath != null) {
                File folder = new File(resourcePath);
                if (folder.exists() && folder.isDirectory()) {
                    Map<String, FileInfo> fileMap = scanFolder(folder);
                    checkUniformity(folder.getAbsolutePath(), fileMap, errors);
                }
            }
        }

        logger.info("PDF与图片一致性检查完成，发现 {} 个一致性问题", errors.size());
        return errors;
    }

    private Map<String, FileInfo> scanFolder(File folder) {
        Map<String, FileInfo> fileMap = new HashMap<>();

        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        String baseName = getBaseName(file.getName());
                        String extension = getExtension(file.getName()).toLowerCase();

                        FileInfo info = fileMap.computeIfAbsent(baseName, k -> new FileInfo());
                        if (isImageExtension(extension)) {
                            info.imageFile = file;
                        } else if (PDF_EXTENSION.equals(extension)) {
                            info.pdfFile = file;
                        }
                    }
                }
            }
        }

        return fileMap;
    }

    private void checkUniformity(String folderPath, Map<String, FileInfo> fileMap, List<ImageCheckError> errors) {
        Set<String> reported = new HashSet<>();

        for (Map.Entry<String, FileInfo> entry : fileMap.entrySet()) {
            String baseName = entry.getKey();
            FileInfo info = entry.getValue();

            if (info.hasPdf() && !info.hasImage()) {
                String message = String.format("存在PDF文件但无对应图片: %s.pdf", baseName);
                if (!reported.contains(message)) {
                    errors.add(ImageCheckError.createPdfImageUniformityError(
                        -1,
                        "resource",
                        folderPath,
                        message,
                        baseName));
                    reported.add(message);
                }
            } else if (info.hasImage() && !info.hasPdf()) {
                String message = String.format("存在图片文件但无对应PDF: %s%s", baseName, getImageExtensionsString());
                if (!reported.contains(message)) {
                    errors.add(ImageCheckError.createPdfImageUniformityError(
                        -1,
                        "resource",
                        folderPath,
                        message,
                        baseName));
                    reported.add(message);
                }
            }
        }

        if (!reported.isEmpty()) {
            logger.info("文件夹 {} 发现 {} 个PDF/图片一致性问题", folderPath, reported.size());
        }
    }

    private String getBaseName(String fileName) {
        if (fileName == null) {
            return "";
        }

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(0, lastDot);
        }
        return fileName;
    }

    private String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0) {
            return fileName.substring(lastDot);
        }
        return "";
    }

    private boolean isImageExtension(String extension) {
        String lower = extension.toLowerCase();
        for (String imgExt : IMAGE_EXTENSIONS) {
            if (lower.equals(imgExt)) {
                return true;
            }
        }
        return false;
    }

    private String getImageExtensionsString() {
        return String.join(", ", IMAGE_EXTENSIONS);
    }

    private String extractResourcePath(Row row, ImageQualityRule rule) {
        ResourceCheckConfig resourceConfig = rule.getResourceConfig();
        if (resourceConfig == null) {
            return null;
        }
        if (resourceConfig.getPathFields() != null && !resourceConfig.getPathFields().isEmpty()) {
            String fieldName = resourceConfig.getPathFields().get(0);
            Integer cellIndex = rule.getProjectColumnIndex(fieldName);
            if (cellIndex != null) {
                Cell cell = row.getCell(cellIndex);
                if (cell != null && cell.value != null) {
                    return cell.value.toString();
                }
            }
        }
        return resourceConfig.getBasePath();
    }

    private static class FileInfo {
        File imageFile;
        File pdfFile;

        boolean hasImage() {
            return imageFile != null && imageFile.exists();
        }

        boolean hasPdf() {
            return pdfFile != null && pdfFile.exists();
        }
    }
}
