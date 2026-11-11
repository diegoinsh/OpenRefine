/*
 * Data Quality Extension - Archive Four-Nature Enum
 * 档案四性：真实性 / 完整性 / 可用性 / 安全性
 */
package com.google.refine.extension.quality.model;

/**
 * 档案四性（规则归属口径）。
 *
 * <p>编码沿用既有 {@link ImageCheckCategoryType} 的口径（完整性 = {@code integrity}），
 * 并接受 {@code completeness} 作为别名归一化，避免下游按另一套写法提交后被丢进"未归类"。</p>
 */
public enum NatureType {

    AUTHENTICITY("authenticity", "真实性"),
    INTEGRITY("integrity", "完整性"),
    USABILITY("usability", "可用性"),
    SECURITY("security", "安全性"),
    /** 未归类：既不是四性检查、也不宜硬塞进某一性的质量问题（如错别字、工具调用失败）。 */
    UNKNOWN("unknown", "未归类");

    private final String code;
    private final String displayName;

    NatureType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 是否计入四性合计（{@link #UNKNOWN} 不计）。 */
    public boolean isFourNature() {
        return this != UNKNOWN;
    }

    public static NatureType fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return UNKNOWN;
        }
        String c = code.trim().toLowerCase();
        // 兼容别名：completeness / complete / 完整 都归到 integrity
        if ("completeness".equals(c) || "complete".equals(c) || "完整".equals(c) || "完整性".equals(c)) {
            return INTEGRITY;
        }
        for (NatureType type : values()) {
            if (type.code.equals(c) || type.displayName.equals(code.trim())) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
