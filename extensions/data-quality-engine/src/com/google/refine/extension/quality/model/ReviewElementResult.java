/*
 * Data Quality Extension - AIMP /review/element Result
 */
package com.google.refine.extension.quality.model;

/**
 * AIMP {@code /review/element} 的**存在类**检测结果（印章 / 指纹）。
 *
 * <p>AIMP 用版面模型出框后按框内墨迹分布分流：{@code sealCount} 为印章数，
 * {@code fingerprintCount} 为疑似指纹数；传入 {@code region} 时只统计落在该区域的框。</p>
 */
public class ReviewElementResult {

    private boolean ok;
    private String message;
    private int sealCount;
    private int fingerprintCount;
    private int imageWidth;
    private int imageHeight;

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public int getSealCount() { return sealCount; }
    public void setSealCount(int sealCount) { this.sealCount = sealCount; }

    public int getFingerprintCount() { return fingerprintCount; }
    public void setFingerprintCount(int fingerprintCount) { this.fingerprintCount = fingerprintCount; }

    public int getImageWidth() { return imageWidth; }
    public void setImageWidth(int imageWidth) { this.imageWidth = imageWidth; }

    public int getImageHeight() { return imageHeight; }
    public void setImageHeight(int imageHeight) { this.imageHeight = imageHeight; }
}
