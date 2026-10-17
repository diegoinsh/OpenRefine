/*
 * Data Quality Extension - Image Check Category
 * Represents a category of image quality checks (based on 档案四性)
 */
package com.google.refine.extension.quality.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ImageCheckCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("categoryCode")
    private String categoryCode;

    @JsonProperty("categoryName")
    private String categoryName;

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("items")
    private List<ImageCheckItem> items;

    @JsonProperty("settings")
    private Map<String, Object> settings;

    public ImageCheckCategory() {
        this.items = new ArrayList<>();
        this.settings = new HashMap<>();
        this.enabled = true;
    }

    @JsonCreator
    public ImageCheckCategory(
            @JsonProperty("categoryCode") String categoryCode,
            @JsonProperty("categoryName") String categoryName,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("items") List<ImageCheckItem> items,
            @JsonProperty("settings") Map<String, Object> settings) {
        this.categoryCode = categoryCode;
        this.categoryName = categoryName;
        this.enabled = enabled != null ? enabled : true;
        this.items = items != null ? items : new ArrayList<>();
        this.settings = settings != null ? settings : new HashMap<>();
    }

    public static ImageCheckCategory createFromType(ImageCheckCategoryType type, Map<String, Map<String, Object>> standardParams) {
        ImageCheckCategory category = new ImageCheckCategory();
        category.setCategoryCode(type.getCode());
        category.setCategoryName(type.getDisplayName());
        category.setEnabled(true);

        for (ImageCheckItemType itemType : ImageCheckItemType.values()) {
            if (itemType.getCategory() == type) {
                Map<String, Object> params = standardParams != null ? standardParams.get(itemType.getCode()) : null;
                ImageCheckItem item = ImageCheckItem.createFromType(itemType, params);
                category.getItems().add(item);
            }
        }

        return category;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ImageCheckItem> getItems() {
        return items;
    }

    public void setItems(List<ImageCheckItem> items) {
        this.items = items;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings;
    }

    public List<ImageCheckItem> getEnabledItems() {
        return items.stream()
                .filter(ImageCheckItem::isEnabled)
                .collect(Collectors.toList());
    }

    public ImageCheckItem getItemByCode(String itemCode) {
        return items.stream()
                .filter(item -> item.getItemCode().equals(itemCode))
                .findFirst()
                .orElse(null);
    }

    public ImageCheckCategoryType getCategoryType() {
        return ImageCheckCategoryType.fromCode(categoryCode);
    }
}
