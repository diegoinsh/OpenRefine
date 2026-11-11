/*
 * Data Quality Extension - AIMP /review/element Result
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

/**
 * AIMP {@code /review/element} 的**存在类**检测结果（印章 / 指纹）。
 *
 * <p>AIMP 用版面模型出框后按框内墨迹分布分流：{@code sealCount} 为印章数，
 * {@code fingerprintCount} 为疑似指纹数。除计数外还保留每个框的像素坐标
 * （{@link #regions}），使调用方可以自行按「落款区域」过滤，避免为区域内外各调一次。</p>
 */
public class ReviewElementResult {

    private boolean ok;
    private String message;
    private int sealCount;
    private int fingerprintCount;
    private int imageWidth;
    private int imageHeight;
    private List<Region> regions = new ArrayList<>();

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

    public List<Region> getRegions() { return regions; }
    public void setRegions(List<Region> regions) { this.regions = regions; }

    /** 单个检出框（像素坐标，原点左上）。 */
    public static class Region {
        /** {@code seal} / {@code fingerprint} / 其它。 */
        private String type;
        private int x1;
        private int y1;
        private int x2;
        private int y2;

        public Region() {}

        public Region(String type, int x1, int y1, int x2, int y2) {
            this.type = type;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getX1() { return x1; }
        public void setX1(int x1) { this.x1 = x1; }

        public int getY1() { return y1; }
        public void setY1(int y1) { this.y1 = y1; }

        public int getX2() { return x2; }
        public void setX2(int x2) { this.x2 = x2; }

        public int getY2() { return y2; }
        public void setY2(int y2) { this.y2 = y2; }

        /** 是否印章类框（AIMP 的 regionType 可能是 seal / seal_red 等）。 */
        public boolean isSeal() {
            return type == null || type.toLowerCase().contains("seal");
        }
    }
}
