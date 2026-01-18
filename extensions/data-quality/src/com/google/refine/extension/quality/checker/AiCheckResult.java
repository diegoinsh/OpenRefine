/*
 * Data Quality Extension - AI Check Result
 * AIMP服务返回结果，包含位置坐标数据
 */
package com.google.refine.extension.quality.checker;

import java.util.ArrayList;
import java.util.List;

public class AiCheckResult {

    private boolean blank;
    private Float rectify;
    private boolean hasEdgeRemove;
    private boolean hasStain;
    private boolean hasHole;
    private Integer dpi;
    private Integer kb;
    private Integer quality;
    private Boolean format;
    private Boolean hasSkew;
    private Integer skewAngle;
    private Integer bitDepth;
    private Integer houseAngle;
    private String pageSize;
    
    // 位置坐标数据 [x, y, width, height]
    private List<int[]> stainLocations;
    private List<int[]> holeLocations;
    private List<int[]> edgeLocations;
    private String imagePath;
    private boolean serviceUnavailable;
    private String serviceUnavailableMessage;

    public AiCheckResult() {
        this.stainLocations = new ArrayList<>();
        this.holeLocations = new ArrayList<>();
        this.edgeLocations = new ArrayList<>();
    }

    public boolean isBlank() {
        return blank;
    }

    public void setBlank(boolean blank) {
        this.blank = blank;
    }

    public Float getRectify() {
        return rectify;
    }

    public void setRectify(Float rectify) {
        this.rectify = rectify;
    }

    public boolean hasEdgeRemove() {
        return hasEdgeRemove;
    }

    public void setHasEdgeRemove(boolean hasEdgeRemove) {
        this.hasEdgeRemove = hasEdgeRemove;
    }

    public boolean hasStain() {
        return hasStain;
    }

    public void setHasStain(boolean hasStain) {
        this.hasStain = hasStain;
    }

    public boolean hasHole() {
        return hasHole;
    }

    public void setHasHole(boolean hasHole) {
        this.hasHole = hasHole;
    }

    public Integer getDpi() {
        return dpi;
    }

    public void setDpi(Integer dpi) {
        this.dpi = dpi;
    }

    public Integer getKb() {
        return kb;
    }

    public void setKb(Integer kb) {
        this.kb = kb;
    }

    public Integer getQuality() {
        return quality;
    }

    public void setQuality(Integer quality) {
        this.quality = quality;
    }

    public Boolean getFormat() {
        return format;
    }

    public void setFormat(Boolean format) {
        this.format = format;
    }

    public boolean hasSkew() {
        return hasSkew != null && hasSkew;
    }

    public void setHasSkew(boolean hasSkew) {
        this.hasSkew = hasSkew;
    }

    public Integer getSkewAngle() {
        return skewAngle;
    }

    public void setSkewAngle(Integer skewAngle) {
        this.skewAngle = skewAngle;
    }

    public Integer getBitDepth() {
        return bitDepth;
    }

    public void setBitDepth(Integer bitDepth) {
        this.bitDepth = bitDepth;
    }

    public Integer getHouseAngle() {
        return houseAngle;
    }

    public void setHouseAngle(Integer houseAngle) {
        this.houseAngle = houseAngle;
    }

    public String getPageSize() {
        return pageSize;
    }

    public void setPageSize(String pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isEmpty() {
        return !blank && rectify == null && !hasEdgeRemove && !hasStain && !hasHole
               && dpi == null && kb == null && quality == null && format == null
               && !hasSkew() && skewAngle == null && bitDepth == null
               && houseAngle == null && pageSize == null;
    }

    public List<int[]> getStainLocations() {
        return stainLocations;
    }

    public void setStainLocations(List<int[]> stainLocations) {
        this.stainLocations = stainLocations;
    }

    public void addStainLocation(int[] location) {
        if (this.stainLocations == null) {
            this.stainLocations = new ArrayList<>();
        }
        this.stainLocations.add(location);
    }

    public List<int[]> getHoleLocations() {
        return holeLocations;
    }

    public void setHoleLocations(List<int[]> holeLocations) {
        this.holeLocations = holeLocations;
    }

    public void addHoleLocation(int[] location) {
        if (this.holeLocations == null) {
            this.holeLocations = new ArrayList<>();
        }
        this.holeLocations.add(location);
    }

    public List<int[]> getEdgeLocations() {
        return edgeLocations;
    }

    public void setEdgeLocations(List<int[]> edgeLocations) {
        this.edgeLocations = edgeLocations;
    }

    public void addEdgeLocation(int[] location) {
        if (this.edgeLocations == null) {
            this.edgeLocations = new ArrayList<>();
        }
        this.edgeLocations.add(location);
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public boolean isServiceUnavailable() {
        return serviceUnavailable;
    }

    public void setServiceUnavailable(boolean serviceUnavailable) {
        this.serviceUnavailable = serviceUnavailable;
    }

    public String getServiceUnavailableMessage() {
        return serviceUnavailableMessage;
    }

    public void setServiceUnavailableMessage(String serviceUnavailableMessage) {
        this.serviceUnavailableMessage = serviceUnavailableMessage;
    }
}
