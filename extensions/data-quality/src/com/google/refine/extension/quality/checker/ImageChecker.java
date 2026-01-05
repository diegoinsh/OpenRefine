/*
 * Data Quality Extension - Image Quality Checker Interface
 * Interface for all image quality check operations
 */
package com.google.refine.extension.quality.checker;

import java.util.List;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public interface ImageChecker {

    String getItemCode();

    String getItemName();

    boolean isEnabled(ImageQualityRule rule);

    List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule);

    default FileStatistics checkStatistics(Project project, List<Row> rows, ImageQualityRule rule) {
        return null;
    }

    default FileStatistics checkStatistics(Project project, List<Row> rows, ImageQualityRule rule, ResourceCheckConfig resourceConfig) {
        return checkStatistics(project, rows, rule);
    }

    default boolean canCheck(String itemCode) {
        return getItemCode().equals(itemCode);
    }
}
