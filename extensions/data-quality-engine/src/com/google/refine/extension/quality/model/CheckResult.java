/*
 * Data Quality Extension - Check Result Model
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.extension.quality.checker.FileStatistics;

/**
 * Result of a quality check operation.
 */
public class CheckResult {

    @JsonProperty("totalRows")
    private int totalRows;

    @JsonProperty("checkedRows")
    private int checkedRows;

    @JsonProperty("passedRows")
    private int passedRows;

    @JsonProperty("failedRows")
    private int failedRows;

    @JsonProperty("errors")
    private List<CheckError> errors;

    @JsonProperty("checkType")
    private String checkType;

    @JsonProperty("startTime")
    private long startTime;

    @JsonProperty("endTime")
    private long endTime;

    @JsonProperty("serviceUnavailable")
    private boolean serviceUnavailable;

    @JsonProperty("serviceUnavailableMessage")
    private String serviceUnavailableMessage;

    @JsonProperty("imageQualityResult")
    private CheckResult imageQualityResult;

    @JsonProperty("fileStatistics")
    private FileStatistics fileStatistics;

    /** 文书级检测明细（category=document_quality 时才有）；其他检查段为 null 不输出。 */
    @JsonProperty("documents")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<DocumentCheckError> documents;

    /** 该检查段是否真正执行；null 表示沿用「有结果即已执行」的老语义（其他检查段不输出）。 */
    @JsonProperty("executed")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean executed;

    /** 未执行原因（executed=false 时才有）：dir_project / no_title_column / no_path_column / rule_disabled / aimp_unavailable。 */
    @JsonProperty("skipReason")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String skipReason;

    /** 合并结果里嵌套的文书级结果（与 imageQualityResult 同构，便于下游只读 combined 也能拿到 executed 等）。 */
    @JsonProperty("documentQualityResult")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private CheckResult documentQualityResult;

    /**
     * 档案四性汇总（按规则口径算好的检查项数与问题数）。
     *
     * <p>只由合并结果写入，其他检查段为 null 时不输出。下游（报告 / 平台）据此
     * 区分「通过」与「未检测」，不再把 0 错误一律当作合格。</p>
     */
    @JsonProperty("natureStats")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<NatureStat> natureStats;

    @JsonProperty("fourNatureRules")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer fourNatureRules;

    @JsonProperty("fourNatureErrors")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer fourNatureErrors;

    /** 是否至少执行了一项四性检查（false 表示整体「未检测」）。 */
    @JsonProperty("anyRuleExecuted")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean anyRuleExecuted;

    public CheckResult() {
        this.errors = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
        this.endTime = 0;
    }

    public CheckResult(String checkType) {
        this();
        this.checkType = checkType;
    }

    // Getters and setters
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    public int getCheckedRows() { return checkedRows; }
    public void setCheckedRows(int checkedRows) { this.checkedRows = checkedRows; }

    public int getPassedRows() { return passedRows; }
    public void setPassedRows(int passedRows) { this.passedRows = passedRows; }

    public int getFailedRows() { return failedRows; }
    public void setFailedRows(int failedRows) { this.failedRows = failedRows; }

    public List<CheckError> getErrors() { return errors; }
    public void setErrors(List<CheckError> errors) { this.errors = errors; }

    public String getCheckType() { return checkType; }
    public void setCheckType(String checkType) { this.checkType = checkType; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public boolean isServiceUnavailable() { return serviceUnavailable; }
    public void setServiceUnavailable(boolean serviceUnavailable) { this.serviceUnavailable = serviceUnavailable; }

    public String getServiceUnavailableMessage() { return serviceUnavailableMessage; }
    public void setServiceUnavailableMessage(String serviceUnavailableMessage) { this.serviceUnavailableMessage = serviceUnavailableMessage; }

    public CheckResult getImageQualityResult() { return imageQualityResult; }
    public void setImageQualityResult(CheckResult imageQualityResult) { this.imageQualityResult = imageQualityResult; }

    public FileStatistics getFileStatistics() { return fileStatistics; }
    public void setFileStatistics(FileStatistics fileStatistics) { this.fileStatistics = fileStatistics; }

    public List<DocumentCheckError> getDocuments() { return documents; }
    public void setDocuments(List<DocumentCheckError> documents) { this.documents = documents; }

    public Boolean getExecuted() { return executed; }
    public void setExecuted(Boolean executed) { this.executed = executed; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

    public CheckResult getDocumentQualityResult() { return documentQualityResult; }
    public void setDocumentQualityResult(CheckResult documentQualityResult) {
        this.documentQualityResult = documentQualityResult;
    }

    public List<NatureStat> getNatureStats() { return natureStats; }
    public void setNatureStats(List<NatureStat> natureStats) { this.natureStats = natureStats; }

    public Integer getFourNatureRules() { return fourNatureRules; }
    public void setFourNatureRules(Integer fourNatureRules) { this.fourNatureRules = fourNatureRules; }

    public Integer getFourNatureErrors() { return fourNatureErrors; }
    public void setFourNatureErrors(Integer fourNatureErrors) { this.fourNatureErrors = fourNatureErrors; }

    public Boolean getAnyRuleExecuted() { return anyRuleExecuted; }
    public void setAnyRuleExecuted(Boolean anyRuleExecuted) { this.anyRuleExecuted = anyRuleExecuted; }

    public void addError(CheckError error) {
        this.errors.add(error);
    }

    public void complete() {
        this.endTime = System.currentTimeMillis();
    }

    /**
     * Single check error entry.
     */
    public static class CheckError {
        @JsonProperty("rowIndex")
        private int rowIndex;

        @JsonProperty("column")
        private String column;

        @JsonProperty("value")
        private String value;

        @JsonProperty("errorType")
        private String errorType;

        @JsonProperty("message")
        private String message;

        @JsonProperty("extractedValue")
        private String extractedValue;

        @JsonProperty("category")
        private String category;

        @JsonProperty("locationX")
        private Integer locationX;

        @JsonProperty("locationY")
        private Integer locationY;

        @JsonProperty("locationWidth")
        private Integer locationWidth;

        @JsonProperty("locationHeight")
        private Integer locationHeight;

        @JsonProperty("hiddenFileName")
        private String hiddenFileName;

        @JsonProperty("details")
        private ImageCheckErrorDetails details;

        @JsonProperty("duplicateImagePaths")
        private java.util.List<String> duplicateImagePaths;

        @JsonProperty("typoInfos")
        private java.util.List<TypoInfo> typoInfos;

        /**
         * 档案四性归属（{@code authenticity | integrity | usability | security | unknown}）。
         *
         * <p>由规则显式声明或 {@link NatureMapping} 兜底映射写入；报告按此分组、统计。
         * 缺省为 null（序列化省略），由汇总层补全。</p>
         */
        @JsonProperty("nature")
        private String nature;

        public CheckError() {}

        public CheckError(int rowIndex, String column, String value, String errorType, String message) {
            this.rowIndex = rowIndex;
            this.column = column;
            this.value = value;
            this.errorType = errorType;
            this.message = message;
        }

        public CheckError(int rowIndex, String column, String value, String errorType, String message, String extractedValue) {
            this.rowIndex = rowIndex;
            this.column = column;
            this.value = value;
            this.errorType = errorType;
            this.message = message;
            this.extractedValue = extractedValue;
        }

        // Getters and setters
        public int getRowIndex() { return rowIndex; }
        public void setRowIndex(int rowIndex) { this.rowIndex = rowIndex; }

        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getExtractedValue() { return extractedValue; }
        public void setExtractedValue(String extractedValue) { this.extractedValue = extractedValue; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getNature() { return nature; }
        public void setNature(String nature) { this.nature = nature; }

        public Integer getLocationX() { return locationX; }
        public void setLocationX(Integer locationX) { this.locationX = locationX; }

        public Integer getLocationY() { return locationY; }
        public void setLocationY(Integer locationY) { this.locationY = locationY; }

        public Integer getLocationWidth() { return locationWidth; }
        public void setLocationWidth(Integer locationWidth) { this.locationWidth = locationWidth; }

        public Integer getLocationHeight() { return locationHeight; }
        public void setLocationHeight(Integer locationHeight) { this.locationHeight = locationHeight; }

        public void setLocation(int[] location) {
            if (location != null && location.length >= 4) {
                this.locationX = location[0];
                this.locationY = location[1];
                this.locationWidth = location[2];
                this.locationHeight = location[3];
            }
        }

        public int[] getLocation() {
            if (locationX != null && locationY != null && locationWidth != null && locationHeight != null) {
                return new int[]{locationX, locationY, locationWidth, locationHeight};
            }
            return null;
        }

        public String getHiddenFileName() { return hiddenFileName; }
        public void setHiddenFileName(String hiddenFileName) { this.hiddenFileName = hiddenFileName; }

        public ImageCheckErrorDetails getDetails() { return details; }
        public void setDetails(ImageCheckErrorDetails details) { this.details = details; }

        public java.util.List<String> getDuplicateImagePaths() { return duplicateImagePaths; }
        public void setDuplicateImagePaths(java.util.List<String> duplicateImagePaths) { this.duplicateImagePaths = duplicateImagePaths; }

        public java.util.List<TypoInfo> getTypoInfos() { return typoInfos; }
        public void setTypoInfos(java.util.List<TypoInfo> typoInfos) { this.typoInfos = typoInfos; }
    }

    /** 单维四性统计：检查项数 / 问题数 / 是否执行 / 是否通过。 */
    public static class NatureStat {
        @JsonProperty("nature")
        private String nature;

        @JsonProperty("name")
        private String name;

        @JsonProperty("rules")
        private int rules;

        @JsonProperty("errors")
        private int errors;

        @JsonProperty("executed")
        private boolean executed;

        @JsonProperty("passed")
        private boolean passed;

        public NatureStat() {}

        public NatureStat(String nature, String name, int rules, int errors, boolean executed) {
            this.nature = nature;
            this.name = name;
            this.rules = rules;
            this.errors = errors;
            this.executed = executed;
            this.passed = executed && errors == 0;
        }

        public String getNature() { return nature; }
        public void setNature(String nature) { this.nature = nature; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getRules() { return rules; }
        public void setRules(int rules) { this.rules = rules; }

        public int getErrors() { return errors; }
        public void setErrors(int errors) { this.errors = errors; }

        public boolean isExecuted() { return executed; }
        public void setExecuted(boolean executed) { this.executed = executed; }

        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
    }
}

