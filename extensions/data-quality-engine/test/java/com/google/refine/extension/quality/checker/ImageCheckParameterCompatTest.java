package com.google.refine.extension.quality.checker;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.testng.annotations.Test;

import com.google.refine.extension.quality.model.ImageCheckItem;

/**
 * 回归：图像检查参数键名兼容。
 *
 * <p>规则模板（{@code ImageCheckStandard}/平台合规模板）用 stainThreshold / holeThreshold /
 * edgeThreshold / detectionMode，项目内旧规则配置用 stainValue / holeValue / edgeValue / checkMode。
 * 两者都必须能取到，否则阈值会静默回落到硬编码默认值（曾导致检查结果一律显示「阈值10」）。
 */
public class ImageCheckParameterCompatTest {

    private static ImageCheckItem item(Map<String, Object> parameters) {
        return new ImageCheckItem("stain", "污点检测", true, "usability", parameters);
    }

    @Test
    public void readsTemplateThresholdKey() {
        Map<String, Object> params = new HashMap<>();
        params.put("stainThreshold", 3);
        params.put("holeThreshold", 2);
        params.put("edgeThreshold", 4);
        ImageCheckItem item = item(params);

        assertEquals(ImageQualityChecker.readIntParameter(item, "stainThreshold", "stainValue"), Integer.valueOf(3));
        assertEquals(ImageQualityChecker.readIntParameter(item, "holeThreshold", "holeValue"), Integer.valueOf(2));
        assertEquals(ImageQualityChecker.readIntParameter(item, "edgeThreshold", "edgeValue"), Integer.valueOf(4));
    }

    @Test
    public void readsLegacyProjectRuleKey() {
        Map<String, Object> params = new HashMap<>();
        params.put("stainValue", "10");
        params.put("holeValue", "1");
        ImageCheckItem item = item(params);

        assertEquals(ImageQualityChecker.readIntParameter(item, "stainThreshold", "stainValue"), Integer.valueOf(10));
        assertEquals(ImageQualityChecker.readIntParameter(item, "holeThreshold", "holeValue"), Integer.valueOf(1));
    }

    @Test
    public void prefersTemplateKeyOverLegacyKey() {
        Map<String, Object> params = new HashMap<>();
        params.put("stainThreshold", 7);
        params.put("stainValue", 99);
        ImageCheckItem item = item(params);

        assertEquals(ImageQualityChecker.readIntParameter(item, "stainThreshold", "stainValue"), Integer.valueOf(7));
    }

    @Test
    public void returnsNullForMissingEmptyOrInvalidValue() {
        Map<String, Object> params = new HashMap<>();
        params.put("stainThreshold", null);
        params.put("stainValue", "  ");
        params.put("holeThreshold", "abc");
        ImageCheckItem item = item(params);

        assertNull(ImageQualityChecker.readIntParameter(item, "stainThreshold", "stainValue"),
                "空值/空白串应按取不到处理，由调用方回落默认值");
        assertNull(ImageQualityChecker.readIntParameter(item, "holeThreshold", "holeValue"),
                "非数字值不应抛出异常，按取不到处理");
        assertNull(ImageQualityChecker.readIntParameter(null, "stainThreshold"));
    }

    @Test
    public void readsEdgeDetectionMode() {
        Map<String, Object> params = new HashMap<>();
        params.put("detectionMode", "strict");
        ImageCheckItem item = item(params);

        assertEquals(ImageQualityChecker.readStringParameter(item, "detectionMode", "checkMode"), "strict");

        params.put("detectionMode", null);
        params.put("checkMode", "standard");
        assertEquals(ImageQualityChecker.readStringParameter(item, "detectionMode", "checkMode"), "standard");
    }
}
