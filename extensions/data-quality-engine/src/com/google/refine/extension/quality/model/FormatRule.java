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

    @JsonProperty("typoCheckEnabled")
    private boolean typoCheckEnabled;

    /**
     * 本列规则的档案四性归属（可选）。
     *
     * <p>为空时按检查项类型走 {@link NatureMapping} 内置映射；显式声明用于客户定制口径
     * （如把"受理日期格式"算作可用性而非完整性）。取值见 {@link NatureType}，接受 {@code completeness} 别名。</p>
     */
    @JsonProperty("nature")
    private String nature;

    public FormatRule() {
        this.nonEmpty = false;
        this.unique = false;
        this.regex = "";
        this.dateFormat = "";
        this.valueList = new ArrayList<>();
        this.typoCheckEnabled = false;
        this.nature = "";
    }

    @JsonCreator
    public FormatRule(
            @JsonProperty("nonEmpty") boolean nonEmpty,
            @JsonProperty("unique") boolean unique,
            @JsonProperty("regex") String regex,
            @JsonProperty("dateFormat") String dateFormat,
            @JsonProperty("valueList") List<String> valueList,
            @JsonProperty("typoCheckEnabled") boolean typoCheckEnabled,
            @JsonProperty("nature") String nature) {
        this.nonEmpty = nonEmpty;
        this.unique = unique;
        this.regex = regex != null ? regex : "";
        this.dateFormat = dateFormat != null ? dateFormat : "";
        this.valueList = valueList != null ? valueList : new ArrayList<>();
        this.typoCheckEnabled = typoCheckEnabled;
        this.nature = nature != null ? nature : "";
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

    public boolean isTypoCheckEnabled() {
        return typoCheckEnabled;
    }

    public void setTypoCheckEnabled(boolean typoCheckEnabled) {
        this.typoCheckEnabled = typoCheckEnabled;
    }

    public String getNature() {
        return nature;
    }

    public void setNature(String nature) {
        this.nature = nature == null ? "" : nature;
    }
}

