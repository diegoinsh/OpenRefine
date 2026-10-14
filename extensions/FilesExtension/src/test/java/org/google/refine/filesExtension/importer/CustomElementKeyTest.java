package org.google.refine.filesExtension.importer;

import org.openrefine.extensions.files.importer.CustomElementType;
import org.openrefine.extensions.files.importer.ExtractionTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CustomElementKeyTest {

    @Test
    public void testChineseNameFallsBackToCustomPrefix() {
        Assert.assertEquals(CustomElementType.generateKeyFromName("密级"), "custom_");
        Assert.assertEquals(CustomElementType.generateKeyFromName("保管期限"), "custom_");
    }

    @Test
    public void testAsciiNameIsSanitized() {
        Assert.assertEquals(CustomElementType.generateKeyFromName("Security Level"), "security_level");
        Assert.assertEquals(CustomElementType.generateKeyFromName("seal-count"), "seal_count");
        Assert.assertEquals(CustomElementType.generateKeyFromName("__密级__"), "custom_");
    }

    @Test
    public void testLeadingDigitGetsPrefix() {
        Assert.assertEquals(CustomElementType.generateKeyFromName("2024年度"), "x2024");
    }

    @Test
    public void testBlankName() {
        Assert.assertEquals(CustomElementType.generateKeyFromName(null), "custom_");
        Assert.assertEquals(CustomElementType.generateKeyFromName("   "), "custom_");
    }

    private CustomElementType adjust(String name, String description) {
        CustomElementType t = new CustomElementType();
        t.setName(name);
        t.setKey("");
        t.setAction(CustomElementType.ACTION_ADJUST);
        t.setDescription(description);
        return t;
    }

    @Test
    public void testAdjustMayReuseFixedColumnName() {
        List<CustomElementType> types = Collections.singletonList(adjust("成文日期", "落款页取印发日期，无印发字样则取落款日期"));
        Assert.assertTrue(CustomElementType.validate(types, ExtractionTemplate.BATCH_TITLE_CASE).isEmpty());
    }

    @Test
    public void testAdjustDoesNotRequireKey() {
        CustomElementType t = adjust("责任者", "多个责任者用全角逗号分隔");
        Assert.assertEquals(t.getKey(), "");
        Assert.assertTrue(CustomElementType.validate(Collections.singletonList(t), ExtractionTemplate.BATCH_TITLE_VOLUME).isEmpty());
    }

    @Test
    public void testAdjustRequiresDescription() {
        List<CustomElementType> blankDesc = Collections.singletonList(adjust("成文日期", "   "));
        List<String> result = CustomElementType.validate(blankDesc, ExtractionTemplate.BATCH_TITLE_CASE);
        Assert.assertEquals(result.size(), 1);
        Assert.assertTrue(result.get(0).contains("微调说明不能为空"));
    }

    @Test
    public void testIncludeStillRejectsFixedColumnName() {
        CustomElementType t = new CustomElementType("成文日期", "issue_date_dup", CustomElementType.ACTION_INCLUDE, "");
        List<String> result = CustomElementType.validate(Arrays.asList(t), ExtractionTemplate.BATCH_TITLE_CASE);
        Assert.assertEquals(result.size(), 1);
        Assert.assertTrue(result.get(0).contains("与固定列冲突"));
    }
}