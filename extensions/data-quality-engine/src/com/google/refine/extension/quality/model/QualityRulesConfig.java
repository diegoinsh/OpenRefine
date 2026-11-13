/*
 * Data Quality Extension - Quality Rules Configuration Model
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration model for quality rules stored in project overlay.
 */
public class QualityRulesConfig {

    @JsonProperty("formatRules")
    private Map<String, FormatRule> formatRules;

    /**
     * 模板类别（如 {@code fyss-litigation}）。决定规则界面呈现哪一套定制 UI；
     * 缺省为 null，序列化时省略，老项目产物不受影响。
     */
    @JsonProperty("templateType")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String templateType;

    /**
     * 文书级（案件质量评查）规则，结构见设计稿 §4.1.2。
     *
     * <p>[移交校审·待删] 2026-11-13：该能力已整体移交数据校审，合规侧不再执行与渲染。
     * 字段保留仅为向后兼容历史项目与模板导出，校审侧沿用同名 key。</p>
     */
    @JsonProperty("documentQualityRule")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> documentQualityRule;

    /**
     * 题名规范检查规则：四级定制模板 + 10 项检查开关。
     * 缺省为 null（不执行），序列化时省略。结构见
     * {@code TitleNormChecker} 类注释与平台模板 {@code titleNormRule} 段。
     */
    @JsonProperty("titleNormRule")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> titleNormRule;

    @JsonProperty("resourceConfig")
    private ResourceCheckConfig resourceConfig;

    @JsonProperty("contentRules")
    private List<ContentComparisonRule> contentRules;

    @JsonProperty("aimpConfig")
    private AimpConfig aimpConfig;

    @JsonProperty("imageQualityRule")
    private ImageQualityRule imageQualityRule;

    /**
     * 报告视图（可选）：{@code archive-four-nature}（档案数据质量检测，缺省）或
     * {@code court-case-review}（案件质量评查）。缺省为 null，序列化时省略。
     */
    @JsonProperty("reportProfile")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reportProfile;

    /** 报告只统计哪些四性（空 = 全部）；元素取值同 {@link NatureType}，{@code completeness} 归一化为 {@code integrity}。 */
    @JsonProperty("reportNatures")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> reportNatures;

    public QualityRulesConfig() {
        this.formatRules = new HashMap<>();
        this.resourceConfig = new ResourceCheckConfig();
        this.contentRules = new ArrayList<>();
        this.aimpConfig = new AimpConfig();
        this.imageQualityRule = new ImageQualityRule();
    }

    @JsonCreator
    public QualityRulesConfig(
            @JsonProperty("formatRules") Map<String, FormatRule> formatRules,
            @JsonProperty("resourceConfig") ResourceCheckConfig resourceConfig,
            @JsonProperty("contentRules") List<ContentComparisonRule> contentRules,
            @JsonProperty("aimpConfig") AimpConfig aimpConfig,
            @JsonProperty("imageQualityRule") ImageQualityRule imageQualityRule,
            @JsonProperty("reportProfile") String reportProfile,
            @JsonProperty("reportNatures") List<String> reportNatures) {
        this.formatRules = formatRules != null ? formatRules : new HashMap<>();
        this.resourceConfig = resourceConfig != null ? resourceConfig : new ResourceCheckConfig();
        this.contentRules = contentRules != null ? contentRules : new ArrayList<>();
        this.aimpConfig = aimpConfig != null ? aimpConfig : new AimpConfig();
        this.imageQualityRule = imageQualityRule != null ? imageQualityRule : new ImageQualityRule();
        this.reportProfile = reportProfile;
        this.reportNatures = reportNatures;
    }

    public Map<String, FormatRule> getFormatRules() {
        return formatRules;
    }

    public void setFormatRules(Map<String, FormatRule> formatRules) {
        this.formatRules = formatRules;
    }

    public String getTemplateType() {
        return templateType;
    }

    public void setTemplateType(String templateType) {
        this.templateType = templateType;
    }

    public Map<String, Object> getDocumentQualityRule() {
        return documentQualityRule;
    }

    public void setDocumentQualityRule(Map<String, Object> documentQualityRule) {
        this.documentQualityRule = documentQualityRule;
    }

    public Map<String, Object> getTitleNormRule() {
        return titleNormRule;
    }

    public void setTitleNormRule(Map<String, Object> titleNormRule) {
        this.titleNormRule = titleNormRule;
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

    public ImageQualityRule getImageQualityRule() {
        return imageQualityRule;
    }

    public void setImageQualityRule(ImageQualityRule imageQualityRule) {
        this.imageQualityRule = imageQualityRule;
    }

    public String getReportProfile() {
        return reportProfile;
    }

    public void setReportProfile(String reportProfile) {
        this.reportProfile = reportProfile;
    }

    public List<String> getReportNatures() {
        return reportNatures;
    }

    public void setReportNatures(List<String> reportNatures) {
        this.reportNatures = reportNatures;
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

