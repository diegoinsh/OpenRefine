/*
 * Data Quality Extension - Image Check Standard Enum
 * Defines available check standards with default parameters
 */
package com.google.refine.extension.quality.model;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageCheckStandard {

    @JsonProperty("DEFAULT")
    DEFAULT("DEFAULT", "默认标准", createDefaultParameters());

    private final String code;
    private final String displayName;
    private final Map<String, Object> defaultParameters;

    ImageCheckStandard(String code, String displayName, Map<String, Object> defaultParameters) {
        this.code = code;
        this.displayName = displayName;
        this.defaultParameters = defaultParameters;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Map<String, Object> getDefaultParameters() {
        return defaultParameters;
    }

    private static Map<String, Object> createDefaultParameters() {
        Map<String, Object> params = new HashMap<>();

        params.put("format", createFormatParams());
        params.put("dpi", createDpiParams());
        params.put("size", createSizeParams());
        params.put("kb", createKbParams());
        params.put("quality", createQualityParams());
        params.put("blank", createBlankParams());
        params.put("stain", createStainParams());
        params.put("hole", createHoleParams());
        params.put("skew", createSkewParams());
        params.put("edge", createEdgeParams());
        params.put("bit_depth", createBitDepthParams());
        
        // 添加新检查项参数
        params.put("damage", createDamageParams());
        params.put("house_angle", createHouseAngleParams());
        params.put("quantity", createQuantityParams());
        params.put("empty_folder", createEmptyFolderParams());
        params.put("repeat_image", createRepeatImageParams());
        params.put("piece_continuous", createPieceContinuousParams());
        params.put("page_continuous", createPageContinuousParams());
        params.put("pdf_image_uniformity", createPdfImageUniformityParams());
        params.put("illegal_files", createIllegalFilesParams());

        return params;
    }

    private static Map<String, Object> createFormatParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("allowedFormats", new String[]{"JPEG", "JPG", "TIFF", "TIF", "PDF"});
        return params;
    }

    private static Map<String, Object> createDpiParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("minDpi", 300);
        params.put("maxDpi", null);
        return params;
    }

    private static Map<String, Object> createSizeParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("minWidth", null);
        params.put("maxWidth", null);
        params.put("minHeight", null);
        params.put("maxHeight", null);
        return params;
    }

    private static Map<String, Object> createKbParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("minKb", 10);
        params.put("maxKb", 10000);
        return params;
    }

    private static Map<String, Object> createQualityParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("minQualityRatio", 80);
        return params;
    }

    private static Map<String, Object> createBlankParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("detectBothSides", true);
        return params;
    }

    private static Map<String, Object> createStainParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("stainThreshold", 10);
        return params;
    }

    private static Map<String, Object> createHoleParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("holeThreshold", 1);
        return params;
    }

    private static Map<String, Object> createSkewParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("skewTolerance", 0.5f);
        return params;
    }

    private static Map<String, Object> createEdgeParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("edgeThreshold", null);
        params.put("detectionMode", "strict");
        return params;
    }

    private static Map<String, Object> createBitDepthParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("requiredBitDepth", 24);
        return params;
    }

    private static Map<String, Object> createResolutionParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("minWidth", 800);
        params.put("minHeight", 600);
        return params;
    }
    
    // 新增检查项的参数创建方法
    private static Map<String, Object> createDamageParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        return params;
    }
    
    private static Map<String, Object> createHouseAngleParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("tolerance", 0.5);
        return params;
    }
    
    private static Map<String, Object> createQuantityParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("expectedQuantity", 1);
        return params;
    }
    
    private static Map<String, Object> createEmptyFolderParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        return params;
    }
    
    private static Map<String, Object> createRepeatImageParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("similarityThreshold", 0.95f);
        return params;
    }
    
    private static Map<String, Object> createPieceContinuousParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        return params;
    }
    
    private static Map<String, Object> createPageContinuousParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        return params;
    }
    
    private static Map<String, Object> createPdfImageUniformityParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        return params;
    }
    
    private static Map<String, Object> createIllegalFilesParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", true);
        params.put("allowedFormats", new String[]{"JPEG", "JPG", "TIFF", "TIF", "PDF", "OFD"});
        return params;
    }

    public static ImageCheckStandard fromCode(String code) {
        for (ImageCheckStandard standard : values()) {
            if (standard.code.equals(code)) {
                return standard;
            }
        }
        return DEFAULT;
    }
}
