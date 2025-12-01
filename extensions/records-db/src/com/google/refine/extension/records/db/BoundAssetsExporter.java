/*
 * Bound Assets Exporter
 * Core logic for exporting files based on project data
 */

package com.google.refine.extension.records.db;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.util.ParsingUtilities;

/**
 * Handles the export of bound assets (files) from project data.
 */
public class BoundAssetsExporter {

    private static final Logger logger = LoggerFactory.getLogger("BoundAssetsExporter");

    private final Project project;

    public BoundAssetsExporter(Project project) {
        this.project = project;
    }

    /**
     * Exports assets based on project data.
     *
     * @param pathFieldsNode Array of column names to use for building paths
     * @param sourceRootPath Root path for source files
     * @param targetPath Target directory for export
     * @param mode "copy" or "move"
     * @param options Additional options
     * @return Result object with statistics
     */
    public ObjectNode exportAssets(ArrayNode pathFieldsNode, String sourceRootPath,
            String targetPath, String mode, JsonNode options) {
        
        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        int total = 0;
        int success = 0;
        int failed = 0;
        int skipped = 0;
        ArrayNode errors = result.putArray("errors");

        // Parse path fields
        List<Integer> pathColumnIndices = new ArrayList<>();
        if (pathFieldsNode != null) {
            for (int i = 0; i < pathFieldsNode.size(); i++) {
                String fieldName = pathFieldsNode.get(i).asText();
                Column col = project.columnModel.getColumnByName(fieldName);
                if (col != null) {
                    pathColumnIndices.add(col.getCellIndex());
                }
            }
        }

        // Options
        boolean preserveStructure = options.has("preserveStructure") ? 
                options.get("preserveStructure").asBoolean(true) : true;
        boolean skipExisting = options.has("skipExisting") ? 
                options.get("skipExisting").asBoolean(true) : true;
        String separator = options.has("separator") ? 
                options.get("separator").asText("/") : "/";

        // Process each row
        for (int rowIndex = 0; rowIndex < project.rows.size(); rowIndex++) {
            total++;
            Row row = project.rows.get(rowIndex);

            try {
                // Build source path from columns
                String sourcePath = buildPath(row, pathColumnIndices, 
                        sourceRootPath, separator);
                
                if (sourcePath == null || sourcePath.isEmpty()) {
                    skipped++;
                    continue;
                }

                File sourceFile = new File(sourcePath);
                if (!sourceFile.exists()) {
                    failed++;
                    addError(errors, rowIndex, "Source file not found: " + sourcePath);
                    continue;
                }

                // Build target path
                String targetFilePath = buildTargetPath(targetPath, sourceFile,
                        preserveStructure, sourceRootPath);
                File targetFile = new File(targetFilePath);

                // Handle directory vs file
                if (sourceFile.isDirectory()) {
                    // Recursively copy/move directory
                    int[] counts = copyOrMoveDirectory(sourceFile, targetFile, mode, skipExisting);
                    if (counts[0] > 0) {
                        success++;
                    } else if (counts[1] > 0) {
                        failed++;
                        addError(errors, rowIndex, "Failed to copy some files in: " + sourcePath);
                    } else {
                        skipped++;
                    }
                } else {
                    // Single file
                    // Check if target exists
                    if (targetFile.exists() && skipExisting) {
                        skipped++;
                        continue;
                    }

                    // Create parent directories
                    if (!targetFile.getParentFile().exists()) {
                        targetFile.getParentFile().mkdirs();
                    }

                    // Copy or move
                    Path source = sourceFile.toPath();
                    Path target = targetFile.toPath();

                    if ("move".equals(mode)) {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }

                    success++;
                }

            } catch (Exception e) {
                failed++;
                addError(errors, rowIndex, e.getMessage());
                logger.warn("Error processing row {}: {}", rowIndex, e.getMessage());
            }
        }

        result.put("status", failed == 0 ? "ok" : "partial");
        result.put("total", total);
        result.put("success", success);
        result.put("failed", failed);
        result.put("skipped", skipped);

        return result;
    }

    private String buildPath(Row row, List<Integer> columnIndices,
            String rootPath, String separator) {
        StringBuilder path = new StringBuilder();

        if (rootPath != null && !rootPath.isEmpty()) {
            path.append(rootPath);
            if (!rootPath.endsWith("/") && !rootPath.endsWith("\\")) {
                path.append(File.separator);
            }
        }

        for (int i = 0; i < columnIndices.size(); i++) {
            int cellIndex = columnIndices.get(i);
            Cell cell = row.getCell(cellIndex);
            if (cell != null && cell.value != null) {
                String value = cell.value.toString().trim();
                if (!value.isEmpty()) {
                    // If this is not the first field and path doesn't end with separator
                    if (path.length() > 0 &&
                            !path.toString().endsWith("/") &&
                            !path.toString().endsWith("\\") &&
                            !path.toString().endsWith(File.separator)) {
                        // Only add separator if value doesn't start with one
                        if (!value.startsWith("/") && !value.startsWith("\\")) {
                            path.append(File.separator);
                        }
                    }
                    path.append(value);
                }
            }
        }

        return path.toString();
    }

    private String buildTargetPath(String targetRoot, File sourceFile,
            boolean preserveStructure, String sourceRootPath) {
        if (preserveStructure && sourceRootPath != null && !sourceRootPath.isEmpty()) {
            // Preserve directory structure relative to source root
            String sourcePath = sourceFile.getAbsolutePath();
            String normalizedSourceRoot = new File(sourceRootPath).getAbsolutePath();

            if (sourcePath.startsWith(normalizedSourceRoot)) {
                String relativePath = sourcePath.substring(normalizedSourceRoot.length());
                if (relativePath.startsWith(File.separator)) {
                    relativePath = relativePath.substring(1);
                }
                return Paths.get(targetRoot, relativePath).toString();
            }
        }

        // Just use the filename
        return Paths.get(targetRoot, sourceFile.getName()).toString();
    }

    private void addError(ArrayNode errors, int rowIndex, String message) {
        ObjectNode error = ParsingUtilities.mapper.createObjectNode();
        error.put("row", rowIndex);
        error.put("error", message);
        errors.add(error);
    }

    /**
     * Recursively copy or move a directory and its contents.
     *
     * @param sourceDir Source directory
     * @param targetDir Target directory
     * @param mode "copy" or "move"
     * @param skipExisting Whether to skip existing files
     * @return int array: [success count, failed count, skipped count]
     */
    private int[] copyOrMoveDirectory(File sourceDir, File targetDir, String mode, boolean skipExisting) {
        int success = 0;
        int failed = 0;
        int skipped = 0;

        // Create target directory if it doesn't exist
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        File[] files = sourceDir.listFiles();
        if (files == null) {
            return new int[]{0, 1, 0};
        }

        for (File file : files) {
            File targetFile = new File(targetDir, file.getName());

            try {
                if (file.isDirectory()) {
                    // Recursively process subdirectory
                    int[] subCounts = copyOrMoveDirectory(file, targetFile, mode, skipExisting);
                    success += subCounts[0];
                    failed += subCounts[1];
                    skipped += subCounts[2];
                } else {
                    // Process file
                    if (targetFile.exists() && skipExisting) {
                        skipped++;
                        continue;
                    }

                    Path source = file.toPath();
                    Path target = targetFile.toPath();

                    if ("move".equals(mode)) {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    success++;
                }
            } catch (Exception e) {
                failed++;
                logger.warn("Error copying file {}: {}", file.getAbsolutePath(), e.getMessage());
            }
        }

        // If moving, delete source directory if empty
        if ("move".equals(mode) && success > 0 && failed == 0) {
            deleteEmptyDirectories(sourceDir);
        }

        return new int[]{success, failed, skipped};
    }

    /**
     * Delete empty directories recursively (used after move operation).
     */
    private void deleteEmptyDirectories(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteEmptyDirectories(file);
                    }
                }
            }
            // Try to delete if empty
            if (dir.listFiles() == null || dir.listFiles().length == 0) {
                dir.delete();
            }
        }
    }

    /**
     * Preview the export operation without actually copying/moving files.
     *
     * @param pathFieldsNode Array of column names to use for building paths
     * @param sourceRootPath Root path for source files
     * @param limit Maximum number of rows to preview
     * @return Preview result with file list
     */
    public ObjectNode previewExport(ArrayNode pathFieldsNode, String sourceRootPath,
            String separator, int limit) {

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        ArrayNode files = result.putArray("files");
        int count = 0;
        int existing = 0;
        int missing = 0;

        // Parse path fields
        List<Integer> pathColumnIndices = new ArrayList<>();
        if (pathFieldsNode != null) {
            for (int i = 0; i < pathFieldsNode.size(); i++) {
                String fieldName = pathFieldsNode.get(i).asText();
                Column col = project.columnModel.getColumnByName(fieldName);
                if (col != null) {
                    pathColumnIndices.add(col.getCellIndex());
                }
            }
        }

        int maxRows = Math.min(limit > 0 ? limit : 10, project.rows.size());

        for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
            Row row = project.rows.get(rowIndex);
            String sourcePath = buildPath(row, pathColumnIndices, sourceRootPath, separator);

            if (sourcePath != null && !sourcePath.isEmpty()) {
                ObjectNode fileInfo = ParsingUtilities.mapper.createObjectNode();
                fileInfo.put("row", rowIndex);
                fileInfo.put("path", sourcePath);

                File file = new File(sourcePath);
                boolean exists = file.exists();
                fileInfo.put("exists", exists);

                if (exists) {
                    fileInfo.put("size", file.length());
                    existing++;
                } else {
                    missing++;
                }

                files.add(fileInfo);
                count++;
            }
        }

        result.put("status", "ok");
        result.put("totalRows", project.rows.size());
        result.put("previewCount", count);
        result.put("existing", existing);
        result.put("missing", missing);

        return result;
    }
}

