/*
 * Data Quality Extension - Image Quality Rule
 * Main configuration class for image quality checking rules
 */
package com.google.refine.extension.quality.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

public class ImageQualityRule implements Serializable, OverlayModel {

    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("standard")
    private String standard;

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("categories")
    private List<ImageCheckCategory> categories;

    @JsonProperty("resourceConfig")
    private ResourceCheckConfig resourceConfig;

    @JsonProperty("customSettings")
    private Map<String, Object> customSettings;

    @JsonProperty("createdAt")
    private long createdAt;

    @JsonProperty("updatedAt")
    private long updatedAt;

    public ImageQualityRule() {
        this.categories = new ArrayList<>();
        this.resourceConfig = new ResourceCheckConfig();
        this.customSettings = new HashMap<>();
        this.enabled = true;
        this.standard = ImageCheckStandard.DEFAULT.getCode();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        initializeDefaultCategories();
    }

    @JsonCreator
    public ImageQualityRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("standard") String standard,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("categories") List<ImageCheckCategory> categories,
            @JsonProperty("resourceConfig") ResourceCheckConfig resourceConfig,
            @JsonProperty("customSettings") Map<String, Object> customSettings,
            @JsonProperty("createdAt") Long createdAt,
            @JsonProperty("updatedAt") Long updatedAt) {
        this.id = id;
        this.name = name != null ? name : "默认图像质量规则";
        this.description = description;
        this.standard = standard != null ? standard : ImageCheckStandard.DEFAULT.getCode();
        this.enabled = enabled != null ? enabled : true;
        this.categories = categories != null ? categories : new ArrayList<>();
        this.resourceConfig = resourceConfig != null ? resourceConfig : new ResourceCheckConfig();
        this.customSettings = customSettings != null ? customSettings : new HashMap<>();
        this.createdAt = createdAt != null ? createdAt : System.currentTimeMillis();
        this.updatedAt = updatedAt != null ? updatedAt : System.currentTimeMillis();

        if (this.categories.isEmpty()) {
            initializeDefaultCategories();
        }
    }

    public static ImageQualityRule createDefault() {
        ImageQualityRule rule = new ImageQualityRule();
        rule.setId("default-image-quality-rule");
        rule.setName("默认图像质量规则");
        rule.setDescription("默认的图像质量检查规则配置");
        return rule;
    }

    private void initializeDefaultCategories() {
        // Convert Map<String, Object> to Map<String, Map<String, Object>>
        Map<String, Map<String, Object>> standardParams = new HashMap<>();
        Map<String, Object> rawParams = ImageCheckStandard.DEFAULT.getDefaultParameters();
        
        for (Map.Entry<String, Object> entry : rawParams.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paramMap = (Map<String, Object>) entry.getValue();
                standardParams.put(entry.getKey(), paramMap);
            }
        }

        for (ImageCheckCategoryType categoryType : ImageCheckCategoryType.values()) {
            ImageCheckCategory category = ImageCheckCategory.createFromType(categoryType, standardParams);
            this.categories.add(category);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStandard() {
        return standard;
    }

    public void setStandard(String standard) {
        this.standard = standard;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ImageCheckCategory> getCategories() {
        return categories;
    }

    public void setCategories(List<ImageCheckCategory> categories) {
        this.categories = categories;
    }

    public ResourceCheckConfig getResourceConfig() {
        return resourceConfig;
    }

    public void setResourceConfig(ResourceCheckConfig resourceConfig) {
        this.resourceConfig = resourceConfig;
    }

    public Map<String, Object> getCustomSettings() {
        return customSettings;
    }

    public void setCustomSettings(Map<String, Object> customSettings) {
        this.customSettings = customSettings;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public ImageCheckCategory getCategoryByCode(String categoryCode) {
        return categories.stream()
                .filter(cat -> cat.getCategoryCode().equals(categoryCode))
                .findFirst()
                .orElse(null);
    }

    public ImageCheckItem getItemByCode(String itemCode) {
        for (ImageCheckCategory category : categories) {
            ImageCheckItem item = category.getItemByCode(itemCode);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    public List<ImageCheckItem> getAllEnabledItems() {
        List<ImageCheckItem> enabledItems = new ArrayList<>();
        for (ImageCheckCategory category : categories) {
            if (category.isEnabled()) {
                enabledItems.addAll(category.getEnabledItems());
            }
        }
        return enabledItems;
    }

    @Override
    public void onBeforeSave(Project project) {
        touch();
    }

    @Override
    public void onAfterSave(Project project) {
    }

    @Override
    public void dispose(Project project) {
    }

    public Integer getProjectColumnIndex(String columnName) {
        if (columnName == null || columnName.isEmpty()) {
            return null;
        }
        return null;
    }
}
