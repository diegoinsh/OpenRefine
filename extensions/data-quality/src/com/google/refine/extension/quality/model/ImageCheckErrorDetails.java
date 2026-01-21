/*
 * Data Quality Extension - Image Check Error Details
 * 用于存储图像检查错误的位置详情，支持多个位置记录
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ImageCheckErrorDetails {

    @JsonProperty("locations")
    private List<int[]> locations;

    public ImageCheckErrorDetails() {
        this.locations = new ArrayList<>();
    }

    public ImageCheckErrorDetails(List<int[]> locations) {
        this.locations = locations != null ? locations : new ArrayList<>();
    }

    public List<int[]> getLocations() {
        return locations;
    }

    public void setLocations(List<int[]> locations) {
        this.locations = locations;
    }

    public void addLocation(int[] location) {
        if (location != null && location.length >= 4) {
            this.locations.add(location);
        }
    }

    public int getLocationCount() {
        return locations != null ? locations.size() : 0;
    }
}
