/*
 * Data Quality Extension - Image Check Category Enum
 * Defines categories based on 档案四性检测标准 (Four Properties of Archives)
 */
package com.google.refine.extension.quality.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageCheckCategoryType {

    @JsonProperty("authenticity")
    真实性("authenticity", "真实性检查"),

    @JsonProperty("integrity")
    完整性("integrity", "完整性检查"),

    @JsonProperty("usability")
    可用性("usability", "可用性检查"),

    @JsonProperty("security")
    安全性("security", "安全性检查");

    private final String code;
    private final String displayName;

    ImageCheckCategoryType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ImageCheckCategoryType fromCode(String code) {
        for (ImageCheckCategoryType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
