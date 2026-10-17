/*
 * Data Quality Extension - Image Quality Checker Interface
 * Interface for all image quality check operations
 */
package com.google.refine.extension.quality.checker;

import java.util.List;

import com.jiongche.sdk.data.TableView;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.ResourceCheckConfig;

public interface ImageChecker {

    String getItemCode();

    String getItemName();

    boolean isEnabled(ImageQualityRule rule);

    List<ImageCheckError> check(TableView view, List<Integer> rowIndexes, ImageQualityRule rule);

    default FileStatistics checkStatistics(TableView view, List<Integer> rowIndexes, ImageQualityRule rule) {
        return null;
    }

    default FileStatistics checkStatistics(TableView view, List<Integer> rowIndexes, ImageQualityRule rule, ResourceCheckConfig resourceConfig) {
        return checkStatistics(view, rowIndexes, rule);
    }

    default boolean canCheck(String itemCode) {
        return getItemCode().equals(itemCode);
    }
}
