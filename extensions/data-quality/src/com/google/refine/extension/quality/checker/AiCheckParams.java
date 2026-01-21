/*
 * Data Quality Extension - AI Check Parameters
 * AIMP服务调用参数
 */
package com.google.refine.extension.quality.checker;

public class AiCheckParams {

    private boolean checkBlank = true;

    private boolean checkSkew = false;
    private String skewTolerance = "0.5";

    private boolean checkEdge = false;
    private int edgeStrictMode = 0;

    private boolean checkStain = false;
    private int stainThreshold = 10;

    private boolean checkHole = false;
    private int holeThreshold = 1;

    private boolean checkDpi = false;

    private int dpi = 300;

    private boolean checkFormat = false;

    private String allowedFormats;

    private boolean checkKb = false;

    private boolean checkQuality = false;

    private boolean checkPageSize = false;

    private boolean checkBitDepth = false;

    private int sensitivity = 3;

    private int setKb = 10;

    private int maxKb = 10000;

    private int quality = 80;

    private int minQuality = 80;

    private int tolerance = 0;

    private int minBitDepth = 8;

    public boolean isCheckBlank() {
        return checkBlank;
    }

    public void setCheckBlank(boolean checkBlank) {
        this.checkBlank = checkBlank;
    }

    public boolean isCheckSkew() {
        return checkSkew;
    }

    public void setCheckSkew(boolean checkSkew) {
        this.checkSkew = checkSkew;
    }

    public String getSkewTolerance() {
        return skewTolerance;
    }

    public void setSkewTolerance(String skewTolerance) {
        this.skewTolerance = skewTolerance;
    }

    public boolean isCheckEdge() {
        return checkEdge;
    }

    public void setCheckEdge(boolean checkEdge) {
        this.checkEdge = checkEdge;
    }

    public int getEdgeStrictMode() {
        return edgeStrictMode;
    }

    public void setEdgeStrictMode(int edgeStrictMode) {
        this.edgeStrictMode = edgeStrictMode;
    }

    public boolean isCheckStain() {
        return checkStain;
    }

    public void setCheckStain(boolean checkStain) {
        this.checkStain = checkStain;
    }

    public int getStainThreshold() {
        return stainThreshold;
    }

    public void setStainThreshold(int stainThreshold) {
        this.stainThreshold = stainThreshold;
    }

    public boolean isCheckHole() {
        return checkHole;
    }

    public void setCheckHole(boolean checkHole) {
        this.checkHole = checkHole;
    }

    public int getHoleThreshold() {
        return holeThreshold;
    }

    public void setHoleThreshold(int holeThreshold) {
        this.holeThreshold = holeThreshold;
    }

    public boolean isCheckDpi() {
        return checkDpi;
    }

    public void setCheckDpi(boolean checkDpi) {
        this.checkDpi = checkDpi;
    }

    public int getDpi() {
        return dpi;
    }

    public int getMinBitDepth() {
        return minBitDepth;
    }

    public void setMinBitDepth(int minBitDepth) {
        this.minBitDepth = minBitDepth;
    }

    public void setDpi(int dpi) {
        this.dpi = dpi;
    }

    public boolean isCheckFormat() {
        return checkFormat;
    }

    public void setCheckFormat(boolean checkFormat) {
        this.checkFormat = checkFormat;
    }

    public String getAllowedFormats() {
        return allowedFormats;
    }

    public void setAllowedFormats(String allowedFormats) {
        this.allowedFormats = allowedFormats;
    }

    public boolean isCheckKb() {
        return checkKb;
    }

    public void setCheckKb(boolean checkKb) {
        this.checkKb = checkKb;
    }

    public boolean isCheckQuality() {
        return checkQuality;
    }

    public void setCheckQuality(boolean checkQuality) {
        this.checkQuality = checkQuality;
    }

    public boolean isCheckPageSize() {
        return checkPageSize;
    }

    public void setCheckPageSize(boolean checkPageSize) {
        this.checkPageSize = checkPageSize;
    }

    public boolean isCheckBitDepth() {
        return checkBitDepth;
    }

    public void setCheckBitDepth(boolean checkBitDepth) {
        this.checkBitDepth = checkBitDepth;
    }

    public int getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = sensitivity;
    }

    public int getSetKb() {
        return setKb;
    }

    public void setSetKb(int setKb) {
        this.setKb = setKb;
    }

    public int getMaxKb() {
        return maxKb;
    }

    public void setMaxKb(int maxKb) {
        this.maxKb = maxKb;
    }

    public int getQuality() {
        return quality;
    }

    public void setQuality(int quality) {
        this.quality = quality;
    }

    public int getMinQuality() {
        return minQuality;
    }

    public void setMinQuality(int minQuality) {
        this.minQuality = minQuality;
    }

    public int getTolerance() {
        return tolerance;
    }

    public void setTolerance(int tolerance) {
        this.tolerance = tolerance;
    }
}
