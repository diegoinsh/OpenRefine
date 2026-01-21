/*
 * Data Quality Extension - Image Check Result
 * Result class for image quality check operations
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ImageCheckResult {

    @JsonProperty("totalImages")
    private int totalImages;

    @JsonProperty("checkedImages")
    private int checkedImages;

    @JsonProperty("passedImages")
    private int passedImages;

    @JsonProperty("failedImages")
    private int failedImages;

    @JsonProperty("errors")
    private List<ImageCheckError> errors;

    @JsonProperty("checkType")
    private String checkType;

    @JsonProperty("ruleId")
    private String ruleId;

    @JsonProperty("startTime")
    private long startTime;

    @JsonProperty("endTime")
    private long endTime;

    public ImageCheckResult() {
        this.errors = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
        this.checkType = "image_quality";
    }

    public ImageCheckResult(String ruleId) {
        this();
        this.ruleId = ruleId;
    }

    public int getTotalImages() {
        return totalImages;
    }

    public void setTotalImages(int totalImages) {
        this.totalImages = totalImages;
    }

    public int getCheckedImages() {
        return checkedImages;
    }

    public void setCheckedImages(int checkedImages) {
        this.checkedImages = checkedImages;
    }

    public int getPassedImages() {
        return passedImages;
    }

    public void setPassedImages(int passedImages) {
        this.passedImages = passedImages;
    }

    public int getFailedImages() {
        return failedImages;
    }

    public void setFailedImages(int failedImages) {
        this.failedImages = failedImages;
    }

    public List<ImageCheckError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImageCheckError> errors) {
        this.errors = errors;
    }

    public String getCheckType() {
        return checkType;
    }

    public void setCheckType(String checkType) {
        this.checkType = checkType;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public void addError(ImageCheckError error) {
        this.errors.add(error);
    }

    public void complete() {
        this.endTime = System.currentTimeMillis();
        if (totalImages > 0) {
            this.passedImages = totalImages - this.errors.size();
            this.failedImages = this.errors.size();
            this.checkedImages = totalImages;
        }
    }
}
