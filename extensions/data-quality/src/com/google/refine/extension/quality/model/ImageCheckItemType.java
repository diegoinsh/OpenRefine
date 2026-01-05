/*
 * Data Quality Extension - Image Check Item Enum
 * Defines all individual image quality check items
 */
package com.google.refine.extension.quality.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageCheckItemType {
    // 真实性类别
    @JsonProperty("repeat_image")
    重复图片审查("repeat_image", "重复图片审查", ImageCheckCategoryType.真实性),
    
    @JsonProperty("pdf_image_uniformity")
    PDF与图片一致性("pdf_image_uniformity", "PDF与图片一致性", ImageCheckCategoryType.真实性),
    
    // 完整性类别
    @JsonProperty("quantity")
    数量统计("quantity", "数量统计", ImageCheckCategoryType.完整性),
    
    @JsonProperty("empty_folder")
    空文件夹检查("empty_folder", "空文件夹检查", ImageCheckCategoryType.完整性),
    
    @JsonProperty("piece_continuous")
    件号连续性检查("piece_continuous", "件号连续性检查", ImageCheckCategoryType.完整性),
    
    @JsonProperty("page_continuous")
    图片页号连续性检查("page_continuous", "图片页号连续性检查", ImageCheckCategoryType.完整性),
    
    // 可用性类别
    @JsonProperty("damage")
    破损文件检查("damage", "破损文件检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("blank")
    空白图片审查("blank", "空白图片审查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("format")
    文件格式检查("format", "文件格式检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("image_quality")
    图像质量检查("image_quality", "图像质量检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("house_angle")
    文本方向检测("house_angle", "文本方向检测", ImageCheckCategoryType.可用性),
    
    @JsonProperty("skew")
    倾斜检测("skew", "倾斜检测", ImageCheckCategoryType.可用性),
    
    @JsonProperty("edge")
    黑边检测("edge", "黑边检测", ImageCheckCategoryType.可用性),
    
    @JsonProperty("stain")
    污点检测("stain", "污点检测", ImageCheckCategoryType.可用性),
    
    @JsonProperty("hole")
    装订孔检测("hole", "装订孔检测", ImageCheckCategoryType.可用性),
    
    @JsonProperty("dpi")
    DPI检查("dpi", "DPI检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("kb")
    KB值检查("kb", "KB值检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("size")
    图像尺寸检查("size", "图像尺寸检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("bit_depth")
    位深度检查("bit_depth", "位深度检查", ImageCheckCategoryType.可用性),
    
    @JsonProperty("resolution")
    分辨率检查("resolution", "分辨率检查", ImageCheckCategoryType.可用性),
    
    // 安全性类别
    // 目前没有明确属于安全性的检查项，可以根据实际需求添加
    // 例如：病毒检查、加密检查等
    ;
    
    private final String code;
    private final String displayName;
    private final ImageCheckCategoryType category;

    ImageCheckItemType(String code, String displayName, ImageCheckCategoryType category) {
        this.code = code;
        this.displayName = displayName;
        this.category = category;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ImageCheckCategoryType getCategory() {
        return category;
    }

    public static ImageCheckItemType fromCode(String code) {
        for (ImageCheckItemType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
