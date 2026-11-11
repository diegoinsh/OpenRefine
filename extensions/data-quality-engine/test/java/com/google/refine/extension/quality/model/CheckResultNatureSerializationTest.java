/*
 * Data Quality Extension - CheckResult Nature Serialization Tests
 * 合并结果的四性字段序列化约定
 */
package com.google.refine.extension.quality.model;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

/**
 * 四性字段随结果落盘：下游（平台结果接口 / 报告）只读 JSON 即可判断「通过 / 不通过 / 未执行」。
 */
public class CheckResultNatureSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializesNatureStatsAndErrorNature() {
        CheckResult result = new CheckResult("combined");
        CheckResult.CheckError error = new CheckResult.CheckError(0, "题名", "", "non_empty", "值不能为空");
        error.setNature("integrity");
        result.addError(error);
        result.setNatureStats(Arrays.asList(
                new CheckResult.NatureStat("integrity", "完整性", 5, 1, true),
                new CheckResult.NatureStat("usability", "可用性", 0, 0, false)));
        result.setFourNatureRules(5);
        result.setFourNatureErrors(1);
        result.setAnyRuleExecuted(true);

        JsonNode node = mapper.valueToTree(result);
        assertEquals(node.path("errors").get(0).path("nature").asText(), "integrity");
        assertEquals(node.path("natureStats").get(0).path("rules").asInt(), 5);
        assertEquals(node.path("natureStats").get(0).path("errors").asInt(), 1);
        assertTrue(node.path("natureStats").get(0).path("executed").asBoolean());
        assertFalse(node.path("natureStats").get(0).path("passed").asBoolean());
        // 未执行的一维：0 问题也必须判为不通过，避免"未检测=合格"
        assertFalse(node.path("natureStats").get(1).path("executed").asBoolean());
        assertFalse(node.path("natureStats").get(1).path("passed").asBoolean());
        assertEquals(node.path("fourNatureRules").asInt(), 5);
        assertEquals(node.path("fourNatureErrors").asInt(), 1);
        assertTrue(node.path("anyRuleExecuted").asBoolean());
    }

    @Test
    public void omitsNatureTablesForSingleSegmentResults() {
        CheckResult segment = new CheckResult("format");
        JsonNode node = mapper.valueToTree(segment);
        assertFalse(node.has("natureStats"));
        assertFalse(node.has("fourNatureRules"));
        assertFalse(node.has("fourNatureErrors"));
        assertFalse(node.has("anyRuleExecuted"));
    }

    @Test
    public void natureStatDerivesPassedFromExecutedAndErrors() {
        assertTrue(new CheckResult.NatureStat("integrity", "完整性", 3, 0, true).isPassed());
        assertFalse(new CheckResult.NatureStat("integrity", "完整性", 3, 2, true).isPassed());
        assertFalse(new CheckResult.NatureStat("security", "安全性", 0, 0, false).isPassed());
    }
}
