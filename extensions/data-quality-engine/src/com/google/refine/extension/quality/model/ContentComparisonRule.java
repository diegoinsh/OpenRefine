/*
 * Data Quality Extension - Content Comparison Rule Model
 */
package com.google.refine.extension.quality.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rule for comparing content between data columns and extracted values.
 */
public class ContentComparisonRule {

    @JsonProperty("column")
    private String column;

    @JsonProperty("extractLabel")
    private String extractLabel;

    @JsonProperty("threshold")
    private int threshold;

    @JsonProperty("checkType")
    private String checkType;

    /** 档案四性归属（可选）：为空按真实性处理，取值见 {@link NatureType}。 */
    @JsonProperty("nature")
    private String nature;

    public ContentComparisonRule() {
        this.column = "";
        this.extractLabel = "";
        this.threshold = 100;
        this.checkType = "image";
        this.nature = "";
    }

    @JsonCreator
    public ContentComparisonRule(
            @JsonProperty("column") String column,
            @JsonProperty("extractLabel") String extractLabel,
            @JsonProperty("threshold") int threshold,
            @JsonProperty("checkType") String checkType,
            @JsonProperty("nature") String nature) {
        this.column = column != null ? column : "";
        this.extractLabel = extractLabel != null ? extractLabel : "";
        this.threshold = threshold > 0 ? threshold : 90;
        this.checkType = checkType != null ? checkType : "image";
        this.nature = nature != null ? nature : "";
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getExtractLabel() {
        return extractLabel;
    }

    public void setExtractLabel(String extractLabel) {
        this.extractLabel = extractLabel;
    }

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public String getCheckType() {
        return checkType;
    }

    public void setCheckType(String checkType) {
        this.checkType = checkType;
    }

    public String getNature() {
        return nature;
    }

    public void setNature(String nature) {
        this.nature = nature == null ? "" : nature;
    }
}

