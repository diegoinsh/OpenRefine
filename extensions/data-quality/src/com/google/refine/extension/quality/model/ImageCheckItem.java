/*
 * Data Quality Extension - Image Check Item
 * Represents a single image quality check item with its configuration
 */
package com.google.refine.extension.quality.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ImageCheckItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("itemCode")
    private String itemCode;

    @JsonProperty("itemName")
    private String itemName;

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("category")
    private String category;

    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    public ImageCheckItem() {
        this.parameters = new HashMap<>();
        this.enabled = true;
    }

    @JsonCreator
    public ImageCheckItem(
            @JsonProperty("itemCode") String itemCode,
            @JsonProperty("itemName") String itemName,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("category") String category,
            @JsonProperty("parameters") Map<String, Object> parameters) {
        this.itemCode = itemCode;
        this.itemName = itemName;
        this.enabled = enabled != null ? enabled : true;
        this.category = category;
        this.parameters = parameters != null ? parameters : new HashMap<>();
    }

    public static ImageCheckItem createFromType(ImageCheckItemType type, Map<String, Object> defaultParams) {
        ImageCheckItem item = new ImageCheckItem();
        item.setItemCode(type.getCode());
        item.setItemName(type.getDisplayName());
        item.setCategory(type.getCategory().getCode());
        item.setEnabled(true);
        item.setParameters(defaultParams != null ? new HashMap<>(defaultParams) : new HashMap<>());
        return item;
    }

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public <T> T getParameter(String key, Class<T> type) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }

    public void setParameter(String key, Object value) {
        this.parameters.put(key, value);
    }

    public ImageCheckItemType getItemType() {
        return ImageCheckItemType.fromCode(itemCode);
    }
}
