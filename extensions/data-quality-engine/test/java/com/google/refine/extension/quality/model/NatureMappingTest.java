/*
 * Data Quality Extension - Nature Mapping Tests
 * 四性映射与规则计数的单元测试
 */
package com.google.refine.extension.quality.model;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * 覆盖四性归一的唯一口径：别名归一、按错误类型/类别兜底、显式声明优先、规则计数。
 */
public class NatureMappingTest {

    @Test
    public void fromCodeNormalizesAliases() {
        assertEquals(NatureType.fromCode("completeness"), NatureType.INTEGRITY);
        assertEquals(NatureType.fromCode("complete"), NatureType.INTEGRITY);
        assertEquals(NatureType.fromCode("完整"), NatureType.INTEGRITY);
        assertEquals(NatureType.fromCode("完整性"), NatureType.INTEGRITY);
        assertEquals(NatureType.fromCode("Authenticity"), NatureType.AUTHENTICITY);
        assertEquals(NatureType.fromCode("真实性"), NatureType.AUTHENTICITY);
        assertEquals(NatureType.fromCode("security"), NatureType.SECURITY);
        assertEquals(NatureType.fromCode(""), NatureType.UNKNOWN);
        assertEquals(NatureType.fromCode(null), NatureType.UNKNOWN);
        assertEquals(NatureType.fromCode("not-a-nature"), NatureType.UNKNOWN);
    }

    @Test
    public void fourNatureFlagExcludesUnknown() {
        assertTrue(NatureType.AUTHENTICITY.isFourNature());
        assertTrue(NatureType.INTEGRITY.isFourNature());
        assertTrue(NatureType.USABILITY.isFourNature());
        assertTrue(NatureType.SECURITY.isFourNature());
        assertFalse(NatureType.UNKNOWN.isFourNature());
        assertEquals(NatureType.INTEGRITY.getDisplayName(), "完整性");
        assertEquals(NatureType.UNKNOWN.getDisplayName(), "未归类");
    }

    @Test
    public void errorTypeMappingIsCaseInsensitive() {
        assertEquals(NatureMapping.ofErrorType("NON_EMPTY", null), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofErrorType("CONTENT_MISMATCH", null), NatureType.AUTHENTICITY);
        assertEquals(NatureMapping.ofErrorType("dpi", null), NatureType.USABILITY);
        assertEquals(NatureMapping.ofErrorType("illegal_files", null), NatureType.SECURITY);
        assertEquals(NatureMapping.ofErrorType("repeat_image", null), NatureType.AUTHENTICITY);
        assertEquals(NatureMapping.ofErrorType("REPEAT_IMAGE", null), NatureType.AUTHENTICITY);
    }

    @Test
    public void typoIsAuthenticityAndToolErrorIsUnknown() {
        assertEquals(NatureMapping.ofErrorType("typo", null), NatureType.AUTHENTICITY);
        assertEquals(NatureMapping.ofErrorType("llm_error", "格式"), NatureType.UNKNOWN);
        assertEquals(NatureMapping.ofErrorType(" TYPO ", null), NatureType.AUTHENTICITY);
        // 文书必备要素缺失（评查项）是「缺」而不是「失真」，固定归完整性
        assertEquals(NatureMapping.ofErrorType("field_missing", "document_quality"), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofErrorType("field_missing", null), NatureType.INTEGRITY);
    }

    @Test
    public void fallsBackToCategoryThenUnknown() {
        assertEquals(NatureMapping.ofErrorType("unknown_type", "format"), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofErrorType("unknown_type", "resource"), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofErrorType("unknown_type", "content"), NatureType.AUTHENTICITY);
        assertEquals(NatureMapping.ofErrorType("unknown_type", "document_quality"), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofErrorType("unknown_type", "image_quality"), NatureType.USABILITY);
        assertEquals(NatureMapping.ofErrorType("unknown_type", null), NatureType.UNKNOWN);
        assertEquals(NatureMapping.ofCategory("image_quality"), NatureType.USABILITY);
        assertEquals(NatureMapping.ofCategory(null), NatureType.UNKNOWN);
    }

    @Test
    public void explicitRuleNatureWins() {
        assertEquals(NatureMapping.ofRuleNature("security", "dpi", null), NatureType.SECURITY);
        assertEquals(NatureMapping.ofRuleNature("completeness", "content_mismatch", null), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofRuleNature("", "non_empty", null), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofRuleNature(null, "illegal_file", null), NatureType.SECURITY);
    }

    @Test
    public void imageCategoryMapping() {
        assertEquals(NatureMapping.ofImageCategory(ImageCheckCategoryType.真实性), NatureType.AUTHENTICITY);
        assertEquals(NatureMapping.ofImageCategory(ImageCheckCategoryType.完整性), NatureType.INTEGRITY);
        assertEquals(NatureMapping.ofImageCategory(ImageCheckCategoryType.可用性), NatureType.USABILITY);
        assertEquals(NatureMapping.ofImageCategory(ImageCheckCategoryType.安全性), NatureType.SECURITY);
        assertEquals(NatureMapping.ofImageCategory(null), NatureType.UNKNOWN);
    }

    @Test
    public void emptyStatsAndDisplayNamesFollowOrder() {
        Map<String, Integer> stats = NatureMapping.emptyStats();
        assertEquals(stats.size(), 5);
        assertEquals(stats.get("authenticity"), Integer.valueOf(0));
        assertEquals(stats.get("unknown"), Integer.valueOf(0));
        assertEquals(NatureMapping.displayNames(),
                Arrays.asList("真实性", "完整性", "可用性", "安全性", "未归类"));
    }

    @Test
    public void ruleStatsCountsEnabledItems() {
        QualityRulesConfig config = new QualityRulesConfig();

        Map<String, FormatRule> formatRules = new LinkedHashMap<>();
        FormatRule title = new FormatRule();
        title.setNonEmpty(true);
        title.setUnique(true);
        formatRules.put("题名", title);
        FormatRule secret = new FormatRule();
        secret.setNonEmpty(true);
        secret.setNature("security"); // 显式声明覆盖 non_empty 的默认完整性
        formatRules.put("密级", secret);
        FormatRule page = new FormatRule();
        page.setRegex("^\\d+$");
        page.setTypoCheckEnabled(true);
        formatRules.put("页号", page);
        config.setFormatRules(formatRules);

        ResourceCheckConfig resource = new ResourceCheckConfig();
        resource.setFolderChecks(new ResourceCheckConfig.FolderChecks(true, false, "", true, false));
        resource.setFileChecks(new ResourceCheckConfig.FileChecks(true, "", "", false));
        config.setResourceConfig(resource);

        ContentComparisonRule comparison = new ContentComparisonRule();
        config.setContentRules(new ArrayList<>(Arrays.asList(comparison)));

        ImageQualityRule image = new ImageQualityRule();
        image.setEnabled(false);
        config.setImageQualityRule(image);

        Map<String, Integer> stats = NatureMapping.ruleStats(config);
        // 完整性：non_empty + unique（2）＋ 文件夹 existence/sequential（2）＋ 文件 countMatch（1）
        assertEquals(stats.get("integrity"), Integer.valueOf(5));
        // 真实性：内容比对每项计 1 ＋ 页号启用了错别字检查（typo 归真实性）
        assertEquals(stats.get("authenticity"), Integer.valueOf(2));
        // 可用性：regex
        assertEquals(stats.get("usability"), Integer.valueOf(1));
        // 安全性：显式 nature=security 的 non_empty
        assertEquals(stats.get("security"), Integer.valueOf(1));
        // 未归类：无
        assertEquals(stats.get("unknown"), Integer.valueOf(0));
    }

    @Test
    public void ruleStatsCountsImageCategories() {
        QualityRulesConfig config = new QualityRulesConfig();
        config.setResourceConfig(new ResourceCheckConfig(
                "", new ArrayList<>(), "separator", "", "",
                new ResourceCheckConfig.FolderChecks(false, false, "", false, false),
                new ResourceCheckConfig.FileChecks(false, "", "", false)));

        ImageQualityRule image = new ImageQualityRule();
        image.setEnabled(true);
        image.setCategories(Arrays.asList(
                category("可用性", true, true, item("dpi", true), item("blank", true), item("skew", false)),
                category("安全性", true, true, item("illegal_files", true)),
                category("真实性", false, false, item("repeat_image", true))));
        config.setImageQualityRule(image);

        Map<String, Integer> stats = NatureMapping.ruleStats(config);
        assertEquals(stats.get("usability"), Integer.valueOf(2));
        assertEquals(stats.get("security"), Integer.valueOf(1));
        // 类别未启用时不计
        assertEquals(stats.get("authenticity"), Integer.valueOf(0));
    }

    @Test
    public void ruleStatsExcludesDocumentChecksMovedToReview() {
        QualityRulesConfig config = new QualityRulesConfig();
        config.setResourceConfig(new ResourceCheckConfig(
                "", new ArrayList<>(), "separator", "", "",
                new ResourceCheckConfig.FolderChecks(false, false, "", false, false),
                new ResourceCheckConfig.FileChecks(false, "", "", false)));
        config.setImageQualityRule(disabledImageRule());

        Map<String, Object> document = new HashMap<>();
        document.put("enabled", true);
        Map<String, Object> judgment = new HashMap<>();
        judgment.put("requiredFields", Arrays.asList("案号", "日期", "责任者"));
        Map<String, Object> mediation = new HashMap<>();
        mediation.put("requiredFields", new ArrayList<>());
        document.put("documentTypes", Arrays.asList(judgment, mediation));
        config.setDocumentQualityRule(document);

        // 文书必备要素校验已移交数据校审（2026-11-13），合规四性不再计入该项
        assertEquals(NatureMapping.ruleStats(config).get("integrity"), Integer.valueOf(0));
        assertEquals(NatureMapping.ruleStats(config).get("authenticity"), Integer.valueOf(0));
    }

    @Test
    public void ruleStatsNullSafe() {
        Map<String, Integer> stats = NatureMapping.ruleStats(null);
        assertEquals(stats.size(), 5);
        for (Integer value : stats.values()) {
            assertEquals(value, Integer.valueOf(0));
        }
    }

    private static ImageCheckItem item(String code, boolean enabled) {
        ImageCheckItem item = new ImageCheckItem();
        item.setItemCode(code);
        item.setItemName(code);
        item.setEnabled(enabled);
        return item;
    }

    private static ImageCheckCategory category(String code, boolean enabled, boolean withItems,
                                               ImageCheckItem... items) {
        ImageCheckCategory category = new ImageCheckCategory();
        category.setCategoryCode(code);
        category.setCategoryName(code);
        category.setEnabled(enabled);
        List<ImageCheckItem> list = new ArrayList<>();
        if (withItems) {
            list.addAll(Arrays.asList(items));
        }
        category.setItems(list);
        return category;
    }

    private static ImageQualityRule disabledImageRule() {
        ImageQualityRule image = new ImageQualityRule();
        image.setEnabled(false);
        return image;
    }
}
