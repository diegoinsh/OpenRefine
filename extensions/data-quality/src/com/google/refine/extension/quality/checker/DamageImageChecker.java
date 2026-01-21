/*
 * Data Quality Extension - Damage Image Checker
 * Checks if an image file is damaged or corrupted
 */
package com.google.refine.extension.quality.checker;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class DamageImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(DamageImageChecker.class);

    @Override
    public String getItemCode() {
        return "damage";
    }

    @Override
    public String getItemName() {
        return "破损文件检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("damage");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("damage");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        for (Row row : rows) {
            List<File> imageFiles = extractImageFiles(row, rule);
            for (File imageFile : imageFiles) {
                boolean isValid = validateImageFile(imageFile);
                if (!isValid) {
                    errors.add(ImageCheckError.createDamageError(
                        -1,
                        "resource",
                        imageFile.getParent(),
                        imageFile.getName()));
                }
            }
        }

        logger.info("破损文件检查完成，发现 {} 个损坏文件", errors.size());
        return errors;
    }

    private boolean validateImageFile(File imageFile) {
        if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) {
            return false;
        }

        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                logger.warn("无法读取图像文件 (返回null): {}", imageFile.getAbsolutePath());
                return false;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                logger.warn("图像尺寸无效: {}x{}", width, height);
                return false;
            }

            return true;
        } catch (IOException e) {
            logger.warn("读取图像文件失败: {} - {}", imageFile.getAbsolutePath(), e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("验证图像文件时发生异常: {} - {}", imageFile.getAbsolutePath(), e.getMessage());
            return false;
        }
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
}
