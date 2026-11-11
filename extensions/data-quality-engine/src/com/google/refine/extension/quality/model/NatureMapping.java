/*
 * Data Quality Extension - Nature Mapping
 * 规则 / 问题类型 → 档案四性 的唯一映射口径
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四性归属映射（唯一口径）。
 *
 * <p>为什么需要它：规则目录里的检查项（格式、资源、内容、图像、文书）各有自己的命名，
 * 报告需要按"真实性 / 完整性 / 可用性 / 安全性"分组。映射以 **检查项类型（errorType）** 为准，
 * 未命中时按检查类别（format/resource/content/image_quality/document_quality）兜底；
 * 两处都没命中记 {@link NatureType#UNKNOWN}，报告里单列"未归类"、不计入四性合计。</p>
 *
 * <p>旧模板没有声明显式 nature 时，本映射即兜底口径，保证历史项目也能出四性报告。</p>
 */
public final class NatureMapping {

    /** 报告按此顺序输出四性，最后是未归类。 */
    public static final List<NatureType> ORDER = Collections.unmodifiableList(java.util.Arrays.asList(
            NatureType.AUTHENTICITY, NatureType.INTEGRITY, NatureType.USABILITY,
            NatureType.SECURITY, NatureType.UNKNOWN));

    /** 检查项类型（errorType）→ 四性。 */
    private static final Map<String, NatureType> BY_ERROR_TYPE = new LinkedHashMap<>();

    /** 检查类别（CheckError.category）→ 四性，仅作兜底。 */
    private static final Map<String, NatureType> BY_CATEGORY = new LinkedHashMap<>();

    static {
        // 真实性：内容/题名是否与实体一致、有无重复图像充数；错别字属内容失真
        put(NatureType.AUTHENTICITY,
                "content_mismatch", "content_warning", "volume_title_mismatch",
                "repeat_image", "duplicate_image", "image_duplicate", "fake", "typo");

        // 完整性：有没有缺（必备要素、空值、目录/文件缺失、数量不符、编号不连续、页数不符）
        put(NatureType.INTEGRITY,
                "non_empty", "unique", "data_existence", "folder_existence", "empty_folder",
                "file_count", "file_sequential", "folder_sequential", "file_name_format",
                "folder_name_format", "piece_continuous", "page_continuous", "page_count",
                "missing_page", "missing_piece", "sequence", "field_missing");

        // 可用性：能不能用（格式规范、分辨率、清晰度、空白/污损/倾斜/边缘/装订孔、文件大小）
        put(NatureType.USABILITY,
                "regex", "date_format", "value_list", "dpi", "resolution", "quality", "blank",
                "stain", "edge", "houseangle", "house_angle", "bias", "bit_depth", "file-size",
                "file_size", "hole", "skew", "damage", "format", "color_mode", "ocr", "blurry");

        // 安全性：非法文件、病毒、敏感信息
        put(NatureType.SECURITY,
                "illegal_files", "illegal_file", "virus", "sensitive_info", "hidden_file");

        // 类别兜底
        BY_CATEGORY.put("format", NatureType.INTEGRITY);
        BY_CATEGORY.put("resource", NatureType.INTEGRITY);
        BY_CATEGORY.put("content", NatureType.AUTHENTICITY);
        BY_CATEGORY.put("image_quality", NatureType.USABILITY);
        // 案件质量评查：只剩文书必备要素检查，归完整性
        BY_CATEGORY.put("document_quality", NatureType.INTEGRITY);
        // 图像检查的错误里 category 直接是中文四性名（ImageCheckCategoryType.name()）
        BY_CATEGORY.put("真实性", NatureType.AUTHENTICITY);
        BY_CATEGORY.put("完整性", NatureType.INTEGRITY);
        BY_CATEGORY.put("可用性", NatureType.USABILITY);
        BY_CATEGORY.put("安全性", NatureType.SECURITY);
    }

    private NatureMapping() {
    }

    private static void put(NatureType nature, String... errorTypes) {
        for (String type : errorTypes) {
            BY_ERROR_TYPE.put(type.toLowerCase(), nature);
        }
    }

    /**
     * 按问题类型归类；未命中时按检查类别兜底；仍未知返回 {@link NatureType#UNKNOWN}。
     *
     * <p>错别字（{@code typo}）改变的是文字内容本身，归"真实性"；
     * 工具错误（{@code llm_error}）不是四性检查项，固定归"未归类"。</p>
     */
    public static NatureType ofErrorType(String errorType, String category) {
        String type = errorType == null ? "" : errorType.trim().toLowerCase();
        if ("llm_error".equals(type)) {
            return NatureType.UNKNOWN;
        }
        NatureType hit = BY_ERROR_TYPE.get(type);
        if (hit != null) {
            return hit;
        }
        return ofCategory(category);
    }

    public static NatureType ofCategory(String category) {
        if (category == null) {
            return NatureType.UNKNOWN;
        }
        NatureType hit = BY_CATEGORY.get(category.trim().toLowerCase());
        return hit != null ? hit : NatureType.UNKNOWN;
    }

    /**
     * 规则显式声明优先；未声明时按问题类型兜底。
     *
     * <p>模板里的 {@code nature} 可以写 {@code completeness}，这里统一归一化为 {@code integrity}。</p>
     */
    public static NatureType ofRuleNature(String explicitNature, String errorType, String category) {
        NatureType declared = NatureType.fromCode(explicitNature);
        if (declared != NatureType.UNKNOWN) {
            return declared;
        }
        return ofErrorType(errorType, category);
    }

    public static NatureType ofImageCategory(ImageCheckCategoryType category) {
        if (category == null) {
            return NatureType.UNKNOWN;
        }
        switch (category) {
            case 真实性:
                return NatureType.AUTHENTICITY;
            case 完整性:
                return NatureType.INTEGRITY;
            case 可用性:
                return NatureType.USABILITY;
            case 安全性:
                return NatureType.SECURITY;
            default:
                return NatureType.UNKNOWN;
        }
    }

    /**
     * 统计各性**启用的检查项数**（报告"检查项数"一列）。
     *
     * <p>只数开关打开且条件有效的项：格式规则按每个开关计 1（regex/dateFormat 需非空、valueList 需非空）；
     * 资源检查按每个开关计 1；内容比对每项计 1；图像按启用类别下的启用项；文书按启用文书类型的检查项。</p>
     */
    public static Map<String, Integer> ruleStats(QualityRulesConfig rules) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (NatureType nature : ORDER) {
            stats.put(nature.getCode(), 0);
        }
        if (rules == null) {
            return stats;
        }
        // ---------------- 条目：格式规则 ----------------
        Map<String, FormatRule> formatRules = rules.getFormatRules();
        if (formatRules != null) {
            for (FormatRule rule : formatRules.values()) {
                if (rule == null) {
                    continue;
                }
                String declared = rule.getNature();
                if (rule.isNonEmpty()) {
                    bump(stats, declared, "non_empty");
                }
                if (rule.isUnique()) {
                    bump(stats, declared, "unique");
                }
                if (rule.getRegex() != null && !rule.getRegex().isEmpty()) {
                    bump(stats, declared, "regex");
                }
                if (rule.getDateFormat() != null && !rule.getDateFormat().isEmpty()) {
                    bump(stats, declared, "date_format");
                }
                if (rule.getValueList() != null && !rule.getValueList().isEmpty()) {
                    bump(stats, declared, "value_list");
                }
                if (rule.isTypoCheckEnabled()) {
                    bump(stats, declared, "typo");
                }
            }
        }
        // ---------------- 条目：资源检查 ----------------
        ResourceCheckConfig resource = rules.getResourceConfig();
        if (resource != null) {
            ResourceCheckConfig.FolderChecks folder = resource.getFolderChecks();
            if (folder != null) {
                int count = 0;
                if (folder.isExistence()) {
                    count++;
                }
                if (folder.isDataExistence()) {
                    count++;
                }
                if (folder.getNameFormat() != null && !folder.getNameFormat().isEmpty()) {
                    count++;
                }
                if (folder.isSequential()) {
                    count++;
                }
                if (folder.isEmptyFolder()) {
                    count++;
                }
                add(stats, NatureType.INTEGRITY, count);
            }
            ResourceCheckConfig.FileChecks file = resource.getFileChecks();
            if (file != null) {
                int count = 0;
                if (file.isCountMatch()) {
                    count++;
                }
                if (file.getNameFormat() != null && !file.getNameFormat().isEmpty()) {
                    count++;
                }
                if (file.isSequential()) {
                    count++;
                }
                add(stats, NatureType.INTEGRITY, count);
            }
        }
        // ---------------- 条目：内容比对 ----------------
        List<ContentComparisonRule> content = rules.getContentRules();
        if (content != null) {
            for (ContentComparisonRule rule : content) {
                if (rule == null) {
                    continue;
                }
                NatureType nature = NatureType.fromCode(rule.getNature());
                add(stats, nature == NatureType.UNKNOWN ? NatureType.AUTHENTICITY : nature, 1);
            }
        }
        // ---------------- 图像质量 ----------------
        ImageQualityRule image = rules.getImageQualityRule();
        if (image != null && image.isEnabled()) {
            for (ImageCheckCategory category : image.getCategories()) {
                if (category == null || !category.isEnabled()) {
                    continue;
                }
                NatureType nature = NatureType.fromCode(category.getCategoryCode());
                if (nature == NatureType.UNKNOWN) {
                    nature = ofImageCategory(ImageCheckCategoryType.fromCode(category.getCategoryCode()));
                }
                int count = 0;
                for (ImageCheckItem item : category.getItems()) {
                    if (item != null && item.isEnabled()) {
                        count++;
                    }
                }
                add(stats, nature, count);
            }
        }
        // ---------------- 文书级（必备要素，归完整性） ----------------
        Map<String, Object> document = rules.getDocumentQualityRule();
        if (document != null && Boolean.TRUE.equals(document.get("enabled"))) {
            int count = 0;
            Object types = document.get("documentTypes");
            if (types instanceof List) {
                for (Object type : (List<?>) types) {
                    if (!(type instanceof Map)) {
                        continue;
                    }
                    // 评查规则按「文书类型 + 必备要素」计数：声明了必备要素的类型算 1 项
                    Object fields = ((Map<?, ?>) type).get("requiredFields");
                    if (fields instanceof List && !((List<?>) fields).isEmpty()) {
                        count++;
                    }
                }
            }
            add(stats, NatureType.INTEGRITY, count);
        }
        return stats;
    }

    private static void bump(Map<String, Integer> stats, String declaredNature, String errorType) {
        NatureType nature = NatureType.fromCode(declaredNature);
        if (nature == NatureType.UNKNOWN) {
            nature = ofErrorType(errorType, null);
        }
        add(stats, nature, 1);
    }

    private static void add(Map<String, Integer> stats, NatureType nature, int count) {
        if (count <= 0) {
            return;
        }
        String code = nature.getCode();
        Integer current = stats.get(code);
        stats.put(code, (current == null ? 0 : current) + count);
    }

    /** 空统计（各性为 0），便于结果与报告缺省填充。 */
    public static Map<String, Integer> emptyStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (NatureType nature : ORDER) {
            stats.put(nature.getCode(), 0);
        }
        return stats;
    }

    /** 各性显示名（报告表头）。 */
    public static List<String> displayNames() {
        List<String> names = new ArrayList<>();
        for (NatureType nature : ORDER) {
            names.add(nature.getDisplayName());
        }
        return names;
    }
}
