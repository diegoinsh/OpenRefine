/*
 * Data Quality Extension - Check Result Model
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.extension.quality.checker.FileStatistics;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

/**
 * Result of a quality check operation.
 */
public class CheckResult implements OverlayModel {

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

    public CheckResult() {
        this.errors = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
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

    public void addError(CheckError error) {
        this.errors.add(error);
    }

    public void complete() {
        this.endTime = System.currentTimeMillis();
    }

    // OverlayModel interface implementations
    @Override
    public void onBeforeSave(Project project) {
        // Nothing special needed before save
    }

    @Override
    public void onAfterSave(Project project) {
        // Nothing special needed after save
    }

    @Override
    public void dispose(Project project) {
        // Clean up resources if needed
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
    }
}

