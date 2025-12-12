/*
 * Data Quality Extension - Resource Checker
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

/**
 * Checker for file/folder resource association validation.
 */
public class ResourceChecker {

    private static final Logger logger = LoggerFactory.getLogger(ResourceChecker.class);

    private final Project project;
    private final QualityRulesConfig rules;
    private String systemSeparator;

    public ResourceChecker(Project project, QualityRulesConfig rules) {
        this.project = project;
        this.rules = rules;
        this.systemSeparator = File.separator;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("resource");
        ResourceCheckConfig config = rules.getResourceConfig();

        if (config == null || config.getPathFields() == null || config.getPathFields().isEmpty()) {
            result.complete();
            return result;
        }

        int totalRows = project.rows.size();
        result.setTotalRows(totalRows);

        // Build column index map
        Map<String, Integer> columnIndexMap = new HashMap<>();
        for (Column col : project.columnModel.columns) {
            columnIndexMap.put(col.getName(), col.getCellIndex());
        }

        // Get separator
        String sep = config.getSeparator();
        if (sep == null || sep.isEmpty()) {
            sep = systemSeparator;
        }

        int passedRows = 0;
        int failedRows = 0;

        // Check each row
        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            Row row = project.rows.get(rowIndex);
            boolean rowPassed = true;

            // Build resource path for this row
            String resourcePath = buildResourcePath(row, columnIndexMap, config, sep);
            if (resourcePath == null || resourcePath.isEmpty()) {
                continue; // Skip rows without valid path
            }

            File resourceDir = new File(resourcePath);

            // Folder existence check
            if (config.getFolderChecks().isExistence()) {
                if (!resourceDir.exists() || !resourceDir.isDirectory()) {
                    result.addError(new CheckError(rowIndex, "resource_path", resourcePath,
                        "folder_existence", "Folder does not exist: " + resourcePath));
                    rowPassed = false;
                }
            }

            // If folder exists, do additional checks
            if (resourceDir.exists() && resourceDir.isDirectory()) {
                File[] files = resourceDir.listFiles();
                List<File> fileList = files != null ? Arrays.asList(files) : new ArrayList<>();

                // File count check
                if (config.getFileChecks().isCountMatch()) {
                    String countColumn = config.getFileChecks().getCountColumn();
                    if (countColumn != null && !countColumn.isEmpty()) {
                        Integer countIdx = columnIndexMap.get(countColumn);
                        if (countIdx != null) {
                            Cell countCell = row.getCell(countIdx);
                            int expected = parseCount(countCell);
                            int actual = (int) fileList.stream().filter(File::isFile).count();
                            if (expected >= 0 && expected != actual) {
                                String severity = Math.abs(expected - actual) <= 1 ? "warning" : "error";
                                result.addError(new CheckError(rowIndex, countColumn, String.valueOf(actual),
                                    "file_count", "File count mismatch: expected " + expected + ", actual " + actual));
                                if (severity.equals("error")) rowPassed = false;
                            }
                        }
                    }
                }

                // File name format check
                String nameFormat = config.getFileChecks().getNameFormat();
                if (nameFormat != null && !nameFormat.isEmpty()) {
                    for (File f : fileList) {
                        if (f.isFile() && !matchesPattern(f.getName(), nameFormat)) {
                            result.addError(new CheckError(rowIndex, "file_name", f.getName(),
                                "file_name_format", "File name does not match format: " + nameFormat));
                            rowPassed = false;
                        }
                    }
                }

                // File sequential check
                if (config.getFileChecks().isSequential()) {
                    List<String> seqErrors = checkSequential(fileList);
                    for (String err : seqErrors) {
                        result.addError(new CheckError(rowIndex, resourcePath, "", "file_sequential", err));
                        rowPassed = false;
                    }
                }
            }

            if (rowPassed) passedRows++;
            else failedRows++;
        }

        result.setCheckedRows(totalRows);
        result.setPassedRows(passedRows);
        result.setFailedRows(failedRows);
        result.complete();

        return result;
    }

    /**
     * Build resource path from row data based on configuration.
     */
    private String buildResourcePath(Row row, Map<String, Integer> columnIndexMap,
                                      ResourceCheckConfig config, String sep) {
        List<String> pathFields = config.getPathFields();
        if (pathFields == null || pathFields.isEmpty()) {
            return null;
        }

        // Get field values
        List<String> values = new ArrayList<>();
        for (String fieldName : pathFields) {
            Integer idx = columnIndexMap.get(fieldName);
            if (idx == null) continue;
            Cell cell = row.getCell(idx);
            String val = getCellValue(cell);
            if (val != null && !val.isEmpty()) {
                values.add(val);
            }
        }

        if (values.isEmpty()) {
            return null;
        }

        StringBuilder path = new StringBuilder();

        // Add base path
        String basePath = config.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            path.append(basePath);
            if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
                path.append(sep);
            }
        }

        // Build path based on mode
        if ("template".equals(config.getPathMode()) && config.getTemplate() != null && !config.getTemplate().isEmpty()) {
            String template = config.getTemplate();
            for (int i = 0; i < values.size(); i++) {
                template = template.replace("{" + i + "}", values.get(i));
            }
            path.append(template);
        } else {
            // Separator mode
            path.append(String.join(sep, values));
        }

        return path.toString();
    }

    private String getCellValue(Cell cell) {
        if (cell == null || cell.value == null) return null;
        return cell.value.toString().trim();
    }

    private int parseCount(Cell cell) {
        if (cell == null || cell.value == null) return -1;
        try {
            return Integer.parseInt(cell.value.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean matchesPattern(String name, String pattern) {
        try {
            return Pattern.matches(pattern, name);
        } catch (PatternSyntaxException e) {
            logger.warn("Invalid file name pattern: " + pattern, e);
            return true;
        }
    }

    private List<String> checkSequential(List<File> files) {
        List<String> errors = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();

        // Extract numbers from file names
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            // Remove extension
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx > 0) {
                name = name.substring(0, dotIdx);
            }
            // Try to extract trailing number
            String numStr = name.replaceAll(".*?(\\d+)$", "$1");
            if (!numStr.isEmpty() && !numStr.equals(name)) {
                try {
                    numbers.add(Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }

        if (numbers.size() < 2) {
            return errors;
        }

        // Sort and check for gaps
        numbers.sort(Integer::compareTo);
        for (int i = 1; i < numbers.size(); i++) {
            int prev = numbers.get(i - 1);
            int curr = numbers.get(i);
            if (curr - prev > 1) {
                errors.add("Sequence gap: missing number(s) between " + prev + " and " + curr);
            }
        }

        return errors;
    }
}
