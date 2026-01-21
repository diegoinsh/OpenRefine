/*
 * Data Quality Extension - Format Rule Model
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Format check rule for a specific column.
 */
public class FormatRule {

    @JsonProperty("nonEmpty")
    private boolean nonEmpty;

    @JsonProperty("unique")
    private boolean unique;

    @JsonProperty("regex")
    private String regex;

    @JsonProperty("dateFormat")
    private String dateFormat;

    @JsonProperty("valueList")
    private List<String> valueList;

    public FormatRule() {
        this.nonEmpty = false;
        this.unique = false;
        this.regex = "";
        this.dateFormat = "";
        this.valueList = new ArrayList<>();
    }

    @JsonCreator
    public FormatRule(
            @JsonProperty("nonEmpty") boolean nonEmpty,
            @JsonProperty("unique") boolean unique,
            @JsonProperty("regex") String regex,
            @JsonProperty("dateFormat") String dateFormat,
            @JsonProperty("valueList") List<String> valueList) {
        this.nonEmpty = nonEmpty;
        this.unique = unique;
        this.regex = regex != null ? regex : "";
        this.dateFormat = dateFormat != null ? dateFormat : "";
        this.valueList = valueList != null ? valueList : new ArrayList<>();
    }

    public boolean isNonEmpty() {
        return nonEmpty;
    }

    public void setNonEmpty(boolean nonEmpty) {
        this.nonEmpty = nonEmpty;
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
        this.unique = unique;
    }

    public String getRegex() {
        return regex;
    }

    public void setRegex(String regex) {
        this.regex = regex;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }

    public List<String> getValueList() {
        return valueList;
    }

    public void setValueList(List<String> valueList) {
        this.valueList = valueList;
    }
}

