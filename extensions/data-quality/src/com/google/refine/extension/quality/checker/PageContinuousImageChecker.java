/*
 * Data Quality Extension - Page Continuous Image Checker
 * Checks for continuous page numbers in image files
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class PageContinuousImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(PageContinuousImageChecker.class);

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("(?:page|_|\\-)(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public String getItemCode() {
        return "page_continuous";
    }

    @Override
    public String getItemName() {
        return "图片页号连续性检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("page_continuous");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("page_continuous");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Map<String, Map<Integer, String>> folderToPageMap = new HashMap<>();

        for (Row row : rows) {
            String resourcePath = extractResourcePath(row, rule);
            if (resourcePath != null) {
                File folder = new File(resourcePath);
                if (folder.exists() && folder.isDirectory()) {
                    Map<Integer, String> pageMap = folderToPageMap.computeIfAbsent(resourcePath, k -> new HashMap<>());
                    List<File> imageFiles = extractImageFiles(folder);
                    for (File imageFile : imageFiles) {
                        Integer pageNumber = extractPageNumber(imageFile.getName());
                        if (pageNumber != null) {
                            if (!pageMap.containsKey(pageNumber)) {
                                pageMap.put(pageNumber, imageFile.getName());
                            }
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, Map<Integer, String>> folderEntry : folderToPageMap.entrySet()) {
            String folderPath = folderEntry.getKey();
            Map<Integer, String> pageMap = folderEntry.getValue();

            if (pageMap.isEmpty()) {
                continue;
            }

            List<Integer> sortedPages = new ArrayList<>(pageMap.keySet());
            java.util.Collections.sort(sortedPages);

            int minPage = sortedPages.get(0);
            int maxPage = sortedPages.get(sortedPages.size() - 1);

            Set<Integer> missingPages = new HashSet<>();
            for (int i = minPage; i <= maxPage; i++) {
                if (!pageMap.containsKey(i)) {
                    missingPages.add(i);
                }
            }

            for (Integer missingPage : missingPages) {
                errors.add(ImageCheckError.createPageNumberError(
                    -1,
                    "resource",
                    folderPath,
                    String.format("缺失页号: %d (应在 %d - %d 范围内)", missingPage, minPage, maxPage),
                    missingPage));
            }

            if (!missingPages.isEmpty()) {
                logger.info("文件夹 {} 发现缺失页号: {}", folderPath, missingPages);
            }
        }

        logger.info("图片页号连续性检查完成，发现 {} 个缺失页号问题", errors.size());
        return errors;
    }

    private Integer extractPageNumber(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        Matcher matcher = PAGE_NUMBER_PATTERN.matcher(fileName);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                logger.warn("无法解析文件名中的页号: {}", fileName);
                return null;
            }
        }

        Pattern simplePattern = Pattern.compile("(\\d+)(?:\\.[a-zA-Z]+)?$");
        Matcher simpleMatcher = simplePattern.matcher(fileName);
        if (simpleMatcher.find()) {
            try {
                String numStr = simpleMatcher.group(1);
                if (numStr != null && numStr.length() <= 5) {
                    return Integer.parseInt(numStr);
                }
            } catch (NumberFormatException e) {
                logger.warn("无法解析文件名中的页号: {}", fileName);
                return null;
            }
        }

        return null;
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
