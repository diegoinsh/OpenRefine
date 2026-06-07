package com.google.refine.extension.quality.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TypoInfo {

    @JsonProperty("position")
    private int position;

    @JsonProperty("typoChar")
    private String typoChar;

    @JsonProperty("correctChar")
    private String correctChar;

    @JsonProperty("errorType")
    private String errorType;

    @JsonProperty("confidence")
    private double confidence;

    public TypoInfo() {}

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getTypoChar() {
        return typoChar;
    }

    public void setTypoChar(String typoChar) {
        this.typoChar = typoChar;
    }

    public String getCorrectChar() {
        return correctChar;
    }

    public void setCorrectChar(String correctChar) {
        this.correctChar = correctChar;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
