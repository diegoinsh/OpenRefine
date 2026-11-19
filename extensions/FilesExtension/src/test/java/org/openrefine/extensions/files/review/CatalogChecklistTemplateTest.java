package org.openrefine.extensions.files.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * 完备性模板双读（{@code completeness.templateDriven}）与提交范围语义的回归测试。
 *
 * <p>开关默认关闭时行为必须与内置基线逐字一致；打开后只认模板条目，
 * 且随案/单件补交不得把"未纳入本次范围"的必备件报成缺件（设计 §七）。</p>
 */
public class CatalogChecklistTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEMPLATE = "{\"complete\":{\"matchThreshold\":0.82,"
            + "\"mergeAttachments\":true,\"pouchElements\":true,\"missingAction\":\"warning\","
            + "\"conditions\":[],\"items\":["
            + "{\"typeCode\":\"item-1\",\"title\":\"起诉状及相关材料\",\"level2\":\"起诉状及相关材料\","
            + "\"aliases\":[\"起诉状\",\"民事诉状\"],\"required\":true,\"order\":1,"
            + "\"volumeType\":\"main\",\"status\":\"enabled\"},"
            + "{\"typeCode\":\"item-2\",\"title\":\"送达回证\",\"level2\":\"送达回证\","
            + "\"aliases\":[\"送达回证\",\"送达凭证\"],\"required\":true,\"order\":2,"
            + "\"volumeType\":\"main\",\"status\":\"enabled\"}]}}";

    @AfterMethod
    public void resetSwitch() {
        System.clearProperty("completeness.templateDriven");
    }

    private static CatalogChecklist.Catalog catalog(String stage, String submission) throws Exception {
        String sub = submission == null ? "" : ",\"submission\":" + submission;
        String json = "{\"archiveNo\":\"A1\",\"stage\":\"" + stage + "\"" + sub + ","
                + "\"volumes\":[{\"volume\":{\"volumeNo\":\"1\",\"volumeType\":\"main\"},"
                + "\"items\":["
                + "{\"pieceNo\":\"1\",\"title\":\"民事起诉状\",\"level2\":\"起诉状及相关材料\",\"pageCount\":3},"
                + "{\"pieceNo\":\"2\",\"title\":\"原告举证材料\",\"level2\":\"原告举证\",\"pageCount\":5}"
                + "]}]}";
        return CatalogChecklist.parse(MAPPER.readTree(json));
    }

    private static CatalogChecklist.Template template() throws Exception {
        return CatalogChecklist.parseTemplate(MAPPER.readTree(TEMPLATE));
    }

    private static Map<String, Object> summary(Map<String, Object> checklist) {
        return asMap(checklist.get("summary"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> checklist) {
        return (List<Map<String, Object>>) checklist.get("items");
    }

    private static long issuesOf(Map<String, Object> checklist, String rule) {
        List<?> issues = (List<?>) checklist.get("issues");
        long n = 0;
        for (Object o : issues) {
            if (rule.equals(asMap(o).get("rule"))) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void switchOffKeepsBuiltinBaseline() throws Exception {
        // 2026-11-19 起模板驱动缺省开启；显式关闭才回落内置基线
        System.setProperty("completeness.templateDriven", "false");
        Map<String, Object> out = CatalogChecklist.evaluate(catalog("filing", null), template());
        Assert.assertEquals(summary(out).get("baselineSource"), "builtin");
        Assert.assertEquals(summary(out).get("outOfScope"), 0);
        Assert.assertEquals(summary(out).get("completenessScope"), "casual");
        // 内置基线仍然是 32 项口径（含卷级材料），不受模板条目影响
        Assert.assertTrue(rows(out).size() > 2);
    }

    /** 未显式配置时按模板判定（模板即选项集，平台下发即生效）。 */
    @Test
    public void defaultIsTemplateDriven() throws Exception {
        Map<String, Object> out = CatalogChecklist.evaluate(catalog("archived", null), template());
        Assert.assertEquals(summary(out).get("baselineSource"), "template");
    }

    /** 选项集参数覆盖模板阈值：下调 matchThreshold 后近似题名由「缺」变为「近似」。 */
    @Test
    public void ruleParamsOverrideMatchThreshold() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        String json = "{\"archiveNo\":\"A1\",\"stage\":\"archived\",\"submission\":{\"mode\":\"full\"},"
                + "\"volumes\":[{\"volume\":{\"volumeNo\":\"1\",\"volumeType\":\"main\"},\"items\":["
                + "{\"pieceNo\":\"1\",\"title\":\"举证材料\",\"pageCount\":2}]}]}";
        CatalogChecklist.Catalog catalog = CatalogChecklist.parse(MAPPER.readTree(json));
        CatalogChecklist.Template template =
                CatalogChecklist.parseTemplate(MAPPER.readTree(TEMPLATE_MATCH));

        Map<String, Object> strict = CatalogChecklist.evaluate(catalog, template);
        Assert.assertEquals(rowOf(strict, "原告举证材料").get("status"), "suspect");

        Map<String, Map<String, Object>> params =
                Map.of("missing_piece", Map.of("matchThreshold", 0.99));
        Map<String, Object> loose = CatalogChecklist.evaluate(catalog, template, null, params);
        Assert.assertEquals(rowOf(loose, "原告举证材料").get("status"), "missing",
                "上调 matchThreshold 后近似题名应判为 missing");
    }

    @Test
    public void casualWithoutScopeTreatsUnsubmittedAsOutOfScope() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        Map<String, Object> out = CatalogChecklist.evaluate(catalog("filing", null), template());
        Assert.assertEquals(summary(out).get("baselineSource"), "template");
        Assert.assertEquals(summary(out).get("outOfScope"), 1);
        Assert.assertEquals(summary(out).get("requiredChecked"), 1);
        Assert.assertEquals(summary(out).get("missing"), 0);
        Assert.assertEquals(issuesOf(out, "missing_piece"), 0L, "随案未提交的必备件不出缺件 issue");
        String status = null;
        for (Map<String, Object> row : rows(out)) {
            if ("item-2".equals(row.get("typeCode"))) {
                status = String.valueOf(row.get("status"));
            }
        }
        Assert.assertEquals(status, "out-of-scope");
    }

    @Test
    public void fullArchivedReportsMissingWithExpectedTitle() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        Map<String, Object> out = CatalogChecklist.evaluate(
                catalog("archived", "{\"mode\":\"full\"}"), template());
        Assert.assertEquals(summary(out).get("outOfScope"), 0);
        Assert.assertEquals(summary(out).get("missing"), 1);
        Map<String, Object> issue = null;
        for (Object o : (List<?>) out.get("issues")) {
            if ("missing_piece".equals(asMap(o).get("rule"))) {
                issue = asMap(o);
            }
        }
        Assert.assertNotNull(issue);
        Assert.assertEquals(issue.get("severity"), "error");
        Assert.assertEquals(issue.get("expectedTitle"), "送达回证");
        Assert.assertEquals(issue.get("pieceTypeCode"), "item-2");
    }

    @Test
    public void supplementNeverReportsMissing() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        Map<String, Object> out = CatalogChecklist.evaluate(
                catalog("archived", "{\"mode\":\"supplement\"}"), template());
        Assert.assertEquals(summary(out).get("missing"), 0);
        Assert.assertEquals(summary(out).get("outOfScope"), 1);
        Assert.assertEquals(issuesOf(out, "missing_piece"), 0L);
    }

    @Test
    public void scopedCasualOnlyJudgesDeclaredScope() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        Map<String, Object> out = CatalogChecklist.evaluate(
                catalog("archived", "{\"mode\":\"casual\",\"scope\":{\"pieceTypes\":[\"送达回证\"]}}"),
                template());
        Assert.assertEquals(summary(out).get("outOfScope"), 0);
        Assert.assertEquals(summary(out).get("missing"), 1);
        Assert.assertEquals(issuesOf(out, "missing_piece"), 1L);
    }

    /** 相似度达到阈值但未满 1：命中记 suspect 并出 warning，不判缺件。 */
    @Test
    public void nearMatchIsSuspectNotMissing() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        String json = "{\"archiveNo\":\"A1\",\"stage\":\"archived\","
                + "\"volumes\":[{\"volume\":{\"volumeNo\":\"1\",\"volumeType\":\"main\"},\"items\":["
                + "{\"pieceNo\":\"1\",\"title\":\"原告举证材料及附件\",\"pageCount\":2}]}]}";
        Map<String, Object> out = CatalogChecklist.evaluate(
                CatalogChecklist.parse(MAPPER.readTree(json)),
                CatalogChecklist.parseTemplate(MAPPER.readTree(TEMPLATE_MATCH)));
        Assert.assertEquals(summary(out).get("missing"), 0);
        Map<String, Object> row = rowOf(out, "原告举证材料");
        Assert.assertEquals(row.get("status"), "suspect");
        Assert.assertEquals(issuesOf(out, "piece_suspect"), 1L);
    }

    /** 一件只能归属一个条目：同分按条目序贪心分配，另一个条目仍判缺。 */
    @Test
    public void onePieceAssignedToSingleEntry() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        String json = "{\"archiveNo\":\"A1\",\"stage\":\"archived\",\"submission\":{\"mode\":\"full\"},"
                + "\"volumes\":[{\"volume\":{\"volumeNo\":\"1\",\"volumeType\":\"main\"},\"items\":["
                + "{\"pieceNo\":\"1\",\"title\":\"送达回证\",\"pageCount\":1}]}]}";
        Map<String, Object> out = CatalogChecklist.evaluate(
                CatalogChecklist.parse(MAPPER.readTree(json)),
                CatalogChecklist.parseTemplate(MAPPER.readTree(TEMPLATE_OVERLAP)));
        Assert.assertEquals(summary(out).get("present"), 1);
        Assert.assertEquals(summary(out).get("missing"), 1);
        Assert.assertEquals(rowOf(out, "送达回证").get("status"), "present");
        Assert.assertEquals(rowOf(out, "送达凭证").get("status"), "missing");
    }

    /** 附件并入：附件命中时挂到主件下（不占独立件）；主件缺时主件仍判缺并附提示。 */
    @Test
    public void attachmentMergesIntoMainAndNotesMissingMain() throws Exception {
        System.setProperty("completeness.templateDriven", "true");
        String json = "{\"archiveNo\":\"A1\",\"stage\":\"archived\",\"submission\":{\"mode\":\"full\"},"
                + "\"volumes\":[{\"volume\":{\"volumeNo\":\"1\",\"volumeType\":\"main\"},\"items\":["
                + "{\"pieceNo\":\"1\",\"title\":\"证据清单\",\"pageCount\":2}]}]}";
        Map<String, Object> out = CatalogChecklist.evaluate(
                CatalogChecklist.parse(MAPPER.readTree(json)),
                CatalogChecklist.parseTemplate(MAPPER.readTree(TEMPLATE_ATTACH)));
        Map<String, Object> att = rowOf(out, "证据清单");
        Map<String, Object> main = rowOf(out, "原告举证材料");
        Assert.assertEquals(att.get("status"), "merged");
        Assert.assertEquals(att.get("mergedInto"), "原告举证材料");
        Assert.assertEquals(main.get("status"), "missing");
        Assert.assertEquals(main.get("note"), "附件已提交、主件缺失");
    }

    private static final String TEMPLATE_MATCH = "{\"complete\":{\"matchThreshold\":0.85,"
            + "\"mergeAttachments\":true,\"conditions\":[],\"items\":["
            + "{\"typeCode\":\"m1\",\"title\":\"原告举证材料\",\"level2\":\"原告举证材料\","
            + "\"aliases\":[\"原告举证材料\"],\"required\":true,\"order\":1}]}}";

    private static final String TEMPLATE_OVERLAP = "{\"complete\":{\"matchThreshold\":0.85,"
            + "\"mergeAttachments\":true,\"conditions\":[],\"items\":["
            + "{\"typeCode\":\"o1\",\"title\":\"送达回证\",\"level2\":\"送达回证\","
            + "\"aliases\":[\"送达回证\"],\"required\":true,\"order\":1},"
            + "{\"typeCode\":\"o2\",\"title\":\"送达凭证\",\"level2\":\"送达凭证\","
            + "\"aliases\":[\"送达回证\",\"送达凭证\"],\"required\":true,\"order\":2}]}}";

    private static final String TEMPLATE_ATTACH = "{\"complete\":{\"matchThreshold\":0.85,"
            + "\"mergeAttachments\":true,\"conditions\":[],\"items\":["
            + "{\"typeCode\":\"att\",\"title\":\"证据清单\",\"level2\":\"原告举证\","
            + "\"aliases\":[\"证据清单\"],\"required\":false,\"order\":1,\"mergeAs\":\"attachment\"},"
            + "{\"typeCode\":\"main\",\"title\":\"原告举证材料\",\"level2\":\"原告举证\","
            + "\"aliases\":[\"原告举证\"],\"required\":true,\"order\":2}]}}";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowOf(Map<String, Object> checklist, String title) {
        for (Map<String, Object> row : rows(checklist)) {
            if (title.equals(row.get("title"))) {
                return row;
            }
        }
        return null;
    }
}
