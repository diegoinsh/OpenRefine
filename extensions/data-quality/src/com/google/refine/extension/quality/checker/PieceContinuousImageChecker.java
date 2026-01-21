/*
 * Data Quality Extension - Piece Continuous Image Checker
 * Checks for continuous piece numbers in image files
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

public class PieceContinuousImageChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(PieceContinuousImageChecker.class);

    private static final Pattern PIECE_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    @Override
    public String getItemCode() {
        return "piece_continuous";
    }

    @Override
    public String getItemName() {
        return "件号连续性检查";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("piece_continuous");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("piece_continuous");
        if (item == null || !item.isEnabled()) {
            return errors;
        }

        Map<String, Map<Integer, String>> folderToPieceMap = new HashMap<>();

        for (Row row : rows) {
            String resourcePath = extractResourcePath(row, rule);
            if (resourcePath != null) {
                File folder = new File(resourcePath);
                if (folder.exists() && folder.isDirectory()) {
                    Map<Integer, String> pieceMap = folderToPieceMap.computeIfAbsent(resourcePath, k -> new HashMap<>());
                    List<File> imageFiles = extractImageFiles(folder);
                    for (File imageFile : imageFiles) {
                        Integer pieceNumber = extractPieceNumber(imageFile.getName());
                        if (pieceNumber != null) {
                            pieceMap.put(pieceNumber, imageFile.getName());
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, Map<Integer, String>> folderEntry : folderToPieceMap.entrySet()) {
            String folderPath = folderEntry.getKey();
            Map<Integer, String> pieceMap = folderEntry.getValue();

            if (pieceMap.isEmpty()) {
                continue;
            }

            List<Integer> sortedPieces = new ArrayList<>(pieceMap.keySet());
            java.util.Collections.sort(sortedPieces);

            int minPiece = sortedPieces.get(0);
            int maxPiece = sortedPieces.get(sortedPieces.size() - 1);

            Set<Integer> missingPieces = new HashSet<>();
            for (int i = minPiece; i <= maxPiece; i++) {
                if (!pieceMap.containsKey(i)) {
                    missingPieces.add(i);
                }
            }

            for (Integer missingPiece : missingPieces) {
                errors.add(ImageCheckError.createPieceNumberError(
                    -1,
                    "resource",
                    folderPath,
                    String.format("缺失件号: %d (应在 %d - %d 范围内)", missingPiece, minPiece, maxPiece),
                    missingPiece));
            }

            if (!missingPieces.isEmpty()) {
                logger.info("文件夹 {} 发现缺失件号: {}", folderPath, missingPieces);
            }
        }

        logger.info("件号连续性检查完成，发现 {} 个缺失件号问题", errors.size());
        return errors;
    }

    private Integer extractPieceNumber(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        Matcher matcher = PIECE_NUMBER_PATTERN.matcher(fileName);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                logger.warn("无法解析文件名中的件号: {}", fileName);
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
