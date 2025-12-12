/*
 * Data Quality Extension - Format Checker
 */
package com.google.refine.extension.quality.checker;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.FormatRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

/**
 * Checker for format validation rules (non-empty, unique, regex, date format).
 */
public class FormatChecker {

    private static final Logger logger = LoggerFactory.getLogger(FormatChecker.class);

    private final Project project;
    private final QualityRulesConfig rules;

    public FormatChecker(Project project, QualityRulesConfig rules) {
        this.project = project;
        this.rules = rules;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("format");
        Map<String, FormatRule> formatRules = rules.getFormatRules();

        if (formatRules == null || formatRules.isEmpty()) {
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

        // Track unique values for uniqueness check
        Map<String, Set<String>> uniqueValues = new HashMap<>();
        for (String colName : formatRules.keySet()) {
            FormatRule rule = formatRules.get(colName);
            if (rule.isUnique()) {
                uniqueValues.put(colName, new HashSet<>());
            }
        }

        int passedRows = 0;
        int failedRows = 0;

        // First pass: check each row
        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            Row row = project.rows.get(rowIndex);
            boolean rowPassed = true;

            for (Map.Entry<String, FormatRule> entry : formatRules.entrySet()) {
                String colName = entry.getKey();
                FormatRule rule = entry.getValue();

                Integer cellIndex = columnIndexMap.get(colName);
                if (cellIndex == null) continue;

                Cell cell = row.getCell(cellIndex);
                String value = getCellValue(cell);

                // Non-empty check
                if (rule.isNonEmpty() && isEmpty(value)) {
                    result.addError(new CheckError(rowIndex, colName, value, "non_empty", "Value is empty"));
                    rowPassed = false;
                }

                // Regex check
                if (rule.getRegex() != null && !rule.getRegex().isEmpty() && !isEmpty(value)) {
                    if (!matchesRegex(value, rule.getRegex())) {
                        result.addError(new CheckError(rowIndex, colName, value, "regex", 
                            "Value does not match pattern: " + rule.getRegex()));
                        rowPassed = false;
                    }
                }

                // Date format check
                if (rule.getDateFormat() != null && !rule.getDateFormat().isEmpty() && !isEmpty(value)) {
                    if (!matchesDateFormat(value, rule.getDateFormat())) {
                        result.addError(new CheckError(rowIndex, colName, value, "date_format",
                            "Value does not match date format: " + rule.getDateFormat()));
                        rowPassed = false;
                    }
                }

                // Value list check
                List<String> valueList = rule.getValueList();
                if (valueList != null && !valueList.isEmpty() && !isEmpty(value)) {
                    if (!valueList.contains(value)) {
                        result.addError(new CheckError(rowIndex, colName, value, "value_list",
                            "Value not in allowed list"));
                        rowPassed = false;
                    }
                }

                // Track for uniqueness (will check after first pass)
                if (rule.isUnique() && !isEmpty(value)) {
                    Set<String> seen = uniqueValues.get(colName);
                    if (seen.contains(value)) {
                        result.addError(new CheckError(rowIndex, colName, value, "unique",
                            "Duplicate value found"));
                        rowPassed = false;
                    } else {
                        seen.add(value);
                    }
                }
            }

            if (rowPassed) {
                passedRows++;
            } else {
                failedRows++;
            }
        }

        result.setCheckedRows(totalRows);
        result.setPassedRows(passedRows);
        result.setFailedRows(failedRows);
        result.complete();

        return result;
    }

    private String getCellValue(Cell cell) {
        if (cell == null || cell.value == null) return null;
        return cell.value.toString();
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean matchesRegex(String value, String regex) {
        try {
            return Pattern.matches(regex, value);
        } catch (PatternSyntaxException e) {
            logger.warn("Invalid regex pattern: " + regex, e);
            return true; // Don't fail on invalid regex
        }
    }

    private boolean matchesDateFormat(String value, String format) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(format);
            sdf.setLenient(false);
            sdf.parse(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

