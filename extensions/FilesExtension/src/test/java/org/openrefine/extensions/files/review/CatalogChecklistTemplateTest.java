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
        Map<String, Object> out = CatalogChecklist.evaluate(catalog("filing", null), template());
        Assert.assertEquals(summary(out).get("baselineSource"), "builtin");
        Assert.assertEquals(summary(out).get("outOfScope"), 0);
        Assert.assertEquals(summary(out).get("completenessScope"), "casual");
        // 内置基线仍然是 32 项口径（含卷级材料），不受模板条目影响
        Assert.assertTrue(rows(out).size() > 2);
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
}
