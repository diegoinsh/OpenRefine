/*
 * Data Quality Extension - Quality Rules Configuration Model
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

/**
 * Configuration model for quality rules stored in project overlay.
 */
public class QualityRulesConfig implements OverlayModel {

    @JsonProperty("formatRules")
    private Map<String, FormatRule> formatRules;

    @JsonProperty("resourceConfig")
    private ResourceCheckConfig resourceConfig;

    @JsonProperty("contentRules")
    private List<ContentComparisonRule> contentRules;

    @JsonProperty("aimpConfig")
    private AimpConfig aimpConfig;

    public QualityRulesConfig() {
        this.formatRules = new HashMap<>();
        this.resourceConfig = new ResourceCheckConfig();
        this.contentRules = new ArrayList<>();
        this.aimpConfig = new AimpConfig();
    }

    @JsonCreator
    public QualityRulesConfig(
            @JsonProperty("formatRules") Map<String, FormatRule> formatRules,
            @JsonProperty("resourceConfig") ResourceCheckConfig resourceConfig,
            @JsonProperty("contentRules") List<ContentComparisonRule> contentRules,
            @JsonProperty("aimpConfig") AimpConfig aimpConfig) {
        this.formatRules = formatRules != null ? formatRules : new HashMap<>();
        this.resourceConfig = resourceConfig != null ? resourceConfig : new ResourceCheckConfig();
        this.contentRules = contentRules != null ? contentRules : new ArrayList<>();
        this.aimpConfig = aimpConfig != null ? aimpConfig : new AimpConfig();
    }

    public Map<String, FormatRule> getFormatRules() {
        return formatRules;
    }

    public void setFormatRules(Map<String, FormatRule> formatRules) {
        this.formatRules = formatRules;
    }

    public ResourceCheckConfig getResourceConfig() {
        return resourceConfig;
    }

    public void setResourceConfig(ResourceCheckConfig resourceConfig) {
        this.resourceConfig = resourceConfig;
    }

    public List<ContentComparisonRule> getContentRules() {
        return contentRules;
    }

    public void setContentRules(List<ContentComparisonRule> contentRules) {
        this.contentRules = contentRules;
    }

    public AimpConfig getAimpConfig() {
        return aimpConfig;
    }

    public void setAimpConfig(AimpConfig aimpConfig) {
        this.aimpConfig = aimpConfig;
    }

    @Override
    public void onBeforeSave(Project project) {
        // Nothing special needed before save
    }

    @Override
    public void onAfterSave(Project project) {
        // Nothing special needed after save
    }

    @Override
    public void dispose(Project project) {
        // Clean up resources if needed
    }

    /**
     * AIMP service configuration
     */
    public static class AimpConfig {
        @JsonProperty("serviceUrl")
        private String serviceUrl;

        @JsonProperty("batchSize")
        private int batchSize;

        @JsonProperty("similarityThreshold")
        private double similarityThreshold;

        @JsonProperty("confidenceThreshold")
        private double confidenceThreshold;

        public AimpConfig() {
            this.serviceUrl = "";
            this.batchSize = 1; // Default: 1 folder per batch
            this.similarityThreshold = 0.8;
            this.confidenceThreshold = 0.5;
        }

        @JsonCreator
        public AimpConfig(
                @JsonProperty("serviceUrl") String serviceUrl,
                @JsonProperty("batchSize") Integer batchSize,
                @JsonProperty("similarityThreshold") Double similarityThreshold,
                @JsonProperty("confidenceThreshold") Double confidenceThreshold) {
            this.serviceUrl = serviceUrl != null ? serviceUrl : "";
            this.batchSize = batchSize != null ? batchSize : 1;
            this.similarityThreshold = similarityThreshold != null ? similarityThreshold : 0.8;
            this.confidenceThreshold = confidenceThreshold != null ? confidenceThreshold : 0.5;
        }

        public String getServiceUrl() { return serviceUrl; }
        public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

        public double getConfidenceThreshold() { return confidenceThreshold; }
        public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    }
}

