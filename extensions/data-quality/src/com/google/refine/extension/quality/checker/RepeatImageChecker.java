/*
 * Data Quality Extension - Repeat Image Checker
 * Checks for duplicate images using file hashing
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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

public class RepeatImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(RepeatImageChecker.class);

    @Override
    public String getItemCode() {
        return "repeat_image";
    }

    @Override
    public String getItemName() {
        return "重复图片审查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("repeat_image");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("repeat_image");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Map<String, List<String>> hashToFiles = new HashMap<>();

        for (Row row : rows) {
            String resourcePath = extractResourcePath(row, rule);
            if (resourcePath != null) {
                File folder = new File(resourcePath);
                if (folder.exists() && folder.isDirectory()) {
                    List<File> imageFiles = extractImageFiles(folder);
                    for (File imageFile : imageFiles) {
                        String hash = calculateFileHash(imageFile);
                        if (hash != null) {
                            hashToFiles.computeIfAbsent(hash, k -> new ArrayList<>())
                                       .add(imageFile.getAbsolutePath());
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : hashToFiles.entrySet()) {
            List<String> files = entry.getValue();
            if (files.size() > 1) {
                Set<String> reportedPaths = new HashSet<>();
                for (String filePath : files) {
                    if (!reportedPaths.contains(filePath)) {
                        errors.add(ImageCheckError.createRepeatImageError(
                            -1,
                            "resource",
                            new File(filePath).getParent(),
                            new File(filePath).getName(),
                            files.size() - 1));
                        reportedPaths.add(filePath);
                    }
                }
            }
        }

        logger.info("重复图片审查完成，发现 {} 组重复图片", errors.size());
        return errors;
    }

    private String calculateFileHash(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
            byte[] digest = md.digest();
            BigInteger bigInt = new BigInteger(1, digest);
            return bigInt.toString(16);
        } catch (IOException e) {
            logger.warn("计算文件哈希失败: {} - {}", file.getAbsolutePath(), e.getMessage());
            return null;
        } catch (NoSuchAlgorithmException e) {
            logger.error("MD5算法不存在", e);
            return null;
        }
    }

    private List<File> extractImageFiles(File folder) {
        List<File> files = new ArrayList<>();
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
