/*
 * Data Quality Extension - Report Export Integration Test
 * 报告导出：报告视图归一化 + 四性汇总分组（命令 → POI 工作簿）
 */
package com.google.refine.extension.quality.commands;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.testng.annotations.Test;

import com.google.refine.extension.quality.model.FormatRule;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.OverlayQualityRulesConfig;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Project;

/**
 * 集成测试：用真实 XSSFWorkbook 承载摘要页，反射调用命令的私有入口，
 * 断言最终单元格内容。覆盖：
 * <ul>
 *   <li>报告视图归一化（缺省 / court / case-review / 非法值）；</li>
 *   <li>两类报告标题；</li>
 *   <li>FR-CFG-08：未启用（检查项数为 0）的四性不出章节；</li>
 *   <li>旧结果没有 {@code natureStats} 时按错误明细 + 规则配置兜底统计；</li>
 *   <li>模板 {@code reportNatures} 声明的四性范围限制。</li>
 * </ul>
 *
 * <p>注：本模块的 impsort 插件不支持文本块（Text Block），JSON 一律用普通字符串拼接。</p>
 */
public class ExportQualityReportCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ARCHIVE_TITLE = "档案数据质量检测报告";
    private static final String COURT_TITLE = "案件质量评查报告";

    private static final String EMPTY_RESULT =
            "{\"summary\":{\"totalRows\":1,\"totalErrors\":0},\"errors\":[]}";

    // ------------------------------------------------------------------ 归一化

    @Test
    public void normalizesProfileAliases() throws Exception {
        assertEquals(normalizeProfile(null), "archive-four-nature");
        assertEquals(normalizeProfile(""), "archive-four-nature");
        assertEquals(normalizeProfile("   "), "archive-four-nature");
        assertEquals(normalizeProfile("court"), "court-case-review");
        assertEquals(normalizeProfile("case-review"), "court-case-review");
        assertEquals(normalizeProfile("Case-Review"), "court-case-review");
        assertEquals(normalizeProfile("COURT-CASE-REVIEW"), "court-case-review");
        assertEquals(normalizeProfile("archive-four-nature"), "archive-four-nature");
        assertEquals(normalizeProfile("unknown-profile"), "archive-four-nature");
    }

    // ------------------------------------------------------------------ 标题

    @Test
    public void courtProfileWritesCaseReviewTitle() throws Exception {
        // 与真实请求一致：先归一化，再进报告生成
        List<List<String>> rows = summarySheet(MAPPER.readTree(EMPTY_RESULT), null,
                normalizeProfile("court"));
        assertEquals(rows.get(0).get(0), COURT_TITLE);
    }

    @Test
    public void defaultProfileWritesArchiveTitle() throws Exception {
        List<List<String>> rows = summarySheet(MAPPER.readTree(EMPTY_RESULT), null, null);
        assertEquals(rows.get(0).get(0), ARCHIVE_TITLE);
    }

    // ------------------------------------------------------- 四性汇总（统计已就绪）

    @Test
    public void writesFourNatureSectionAndSkipsUnenabledNature() throws Exception {
        String json = "{\"summary\":{\"totalRows\":10,\"totalErrors\":2,"
                + "\"formatErrors\":1,\"contentErrors\":1,\"natureStats\":["
                + "{\"nature\":\"authenticity\",\"name\":\"真实性\",\"rules\":2,\"errors\":1},"
                + "{\"nature\":\"integrity\",\"name\":\"完整性\",\"rules\":5,\"errors\":0},"
                + "{\"nature\":\"usability\",\"name\":\"可用性\",\"rules\":4,\"errors\":1},"
                + "{\"nature\":\"security\",\"name\":\"安全性\",\"rules\":0,\"errors\":0}"
                + "]},\"errors\":[]}";
        List<List<String>> rows = summarySheet(MAPPER.readTree(json), null, "archive-four-nature");

        List<String> auth = natureRow(rows, "真实性");
        assertNotNull(auth, "真实性章节应存在");
        assertEquals(auth.get(1), "2");
        assertEquals(auth.get(2), "1");
        assertEquals(auth.get(3), "不通过");

        List<String> integrity = natureRow(rows, "完整性");
        assertNotNull(integrity);
        assertEquals(integrity.get(1), "5");
        assertEquals(integrity.get(2), "0");
        assertEquals(integrity.get(3), "通过");

        assertNotNull(natureRow(rows, "可用性"));
        // FR-CFG-08：检查项数为 0 的四性不出现章节，避免被读成「0 问题 = 合格」
        assertNull(natureRow(rows, "安全性"), "未启用的安全性不应出现章节");
        assertNull(natureRow(rows, "未归类"), "无未归类问题时不应出现该章节");
    }

    // ------------------------------------------------ 四性汇总（旧结果兜底统计）

    @Test
    public void derivesNatureStatsFromErrorsWhenResultIsLegacy() throws Exception {
        String json = "{\"summary\":{\"totalRows\":4,\"totalErrors\":2,\"formatErrors\":2},\"errors\":["
                + "{\"rowIndex\":0,\"column\":\"档号\",\"value\":\"\",\"errorType\":\"non_empty\","
                + "\"category\":\"format\",\"message\":\"不能为空\"},"
                + "{\"rowIndex\":1,\"column\":\"题名\",\"value\":\"甲\",\"errorType\":\"typo\","
                + "\"category\":\"format\",\"message\":\"疑似错别字\"}"
                + "]}";
        // 只启用一条「非空」格式规则；资源检查与图像检查全部关闭
        QualityRulesConfig rules = disabledRules();
        Map<String, FormatRule> formatRules = new HashMap<>();
        FormatRule nonEmpty = new FormatRule();
        nonEmpty.setNonEmpty(true);
        formatRules.put("档号", nonEmpty);
        rules.setFormatRules(formatRules);

        List<List<String>> rows = summarySheet(MAPPER.readTree(json), rules, "archive-four-nature");

        List<String> integrity = natureRow(rows, "完整性");
        assertNotNull(integrity, "non_empty 应归入完整性");
        assertEquals(integrity.get(1), "1");
        assertEquals(integrity.get(2), "1");
        assertEquals(integrity.get(3), "不通过");

        // typo 不是四性检查项，单列「未归类」，不计入四性
        List<String> unknown = natureRow(rows, "未归类");
        assertNotNull(unknown, "错别字应单列未归类");
        assertEquals(unknown.get(2), "1");

        assertNull(natureRow(rows, "真实性"));
        assertNull(natureRow(rows, "可用性"));
        assertNull(natureRow(rows, "安全性"));
    }

    @Test
    public void explicitRuleNatureOverridesBuiltinMapping() throws Exception {
        String json = "{\"summary\":{\"totalRows\":2,\"totalErrors\":0},\"errors\":[]}";
        QualityRulesConfig rules = disabledRules();
        Map<String, FormatRule> formatRules = new HashMap<>();
        // 客户把「受理日期」的非空归属显式改成真实性
        FormatRule born = new FormatRule();
        born.setNonEmpty(true);
        born.setNature("authenticity");
        formatRules.put("受理日期", born);
        rules.setFormatRules(formatRules);

        List<List<String>> rows = summarySheet(MAPPER.readTree(json), rules, "archive-four-nature");

        assertNotNull(natureRow(rows, "真实性"), "显式声明应优先于内置映射");
        assertNull(natureRow(rows, "完整性"));
    }

    // ------------------------------------------------ 模板声明 reportNatures 限制

    @Test
    public void reportNaturesRestrictsFourNatureSections() throws Exception {
        String json = "{\"summary\":{\"totalRows\":2,\"totalErrors\":0},\"errors\":[]}";
        QualityRulesConfig rules = disabledRules();
        Map<String, FormatRule> formatRules = new HashMap<>();
        FormatRule auth = new FormatRule();
        auth.setNonEmpty(true);
        auth.setNature("authenticity");
        formatRules.put("承办人", auth);
        FormatRule integrity = new FormatRule();
        integrity.setNonEmpty(true);
        formatRules.put("档号", integrity);
        rules.setFormatRules(formatRules);
        rules.setReportNatures(List.of("integrity"));

        List<List<String>> rows = summarySheet(MAPPER.readTree(json), rules, "court");

        assertNull(natureRow(rows, "真实性"), "模板未声明的四性不出章节");
        assertNotNull(natureRow(rows, "完整性"));
    }

    // ------------------------------------------------------------ 无四性可出时提示

    @Test
    public void showsHintWhenNothingToReport() throws Exception {
        List<List<String>> rows = summarySheet(MAPPER.readTree(EMPTY_RESULT), disabledRules(),
                "archive-four-nature");

        assertNotNull(natureSectionHeader(rows));
        assertTrue(rows.stream().anyMatch(r -> !r.isEmpty() && r.get(0).startsWith("本次未执行四性检查")),
                "无任何四性统计时应给出提示行");
    }

    // ------------------------------------------------------------------ 工具

    private static String normalizeProfile(String profile) throws Exception {
        Method m = ExportQualityReportCommand.class.getDeclaredMethod("normalizeProfile", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, profile);
    }

    /** 关闭资源 / 图像检查的规则配置，便于隔离格式规则的计数。 */
    private static QualityRulesConfig disabledRules() {
        QualityRulesConfig rules = new QualityRulesConfig();
        rules.setFormatRules(new HashMap<>());
        rules.setResourceConfig(new ResourceCheckConfig("", new ArrayList<>(), "separator", "", "",
                new ResourceCheckConfig.FolderChecks(false, false, "", false, false),
                new ResourceCheckConfig.FileChecks(false, "", "", false)));
        ImageQualityRule image = new ImageQualityRule();
        image.setEnabled(false);
        image.setCategories(new ArrayList<>());
        rules.setImageQualityRule(image);
        return rules;
    }

    /**
     * 用真实工作簿走一遍摘要页生成逻辑，再把单元格读成文本。
     *
     * <p>规则配置经 {@code project.overlayModels} 的 {@code qualityRulesConfig} 槽注入，
     * 与运行时取配置的路径一致。</p>
     */
    private static List<List<String>> summarySheet(JsonNode results, QualityRulesConfig rules, String profile)
            throws Exception {
        Project project = new Project();
        if (rules != null) {
            OverlayQualityRulesConfig overlay = new OverlayQualityRulesConfig();
            overlay.setFormatRules(rules.getFormatRules());
            overlay.setResourceConfig(rules.getResourceConfig());
            overlay.setContentRules(rules.getContentRules());
            overlay.setImageQualityRule(rules.getImageQualityRule());
            overlay.setDocumentQualityRule(rules.getDocumentQualityRule());
            overlay.setReportNatures(rules.getReportNatures());
            overlay.setReportProfile(rules.getReportProfile());
            project.overlayModels.put("qualityRulesConfig", overlay);
        }

        Workbook workbook = new XSSFWorkbook();
        try {
            Sheet sheet = workbook.createSheet("检查摘要");
            Method m = ExportQualityReportCommand.class.getDeclaredMethod("createSummarySheet",
                    Workbook.class, Sheet.class, JsonNode.class, Project.class, String.class);
            m.setAccessible(true);
            m.invoke(new ExportQualityReportCommand(), workbook, sheet, results, project, profile);
            return readRows(sheet);
        } finally {
            workbook.close();
        }
    }

    private static List<List<String>> readRows(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            List<String> cells = new ArrayList<>();
            if (row != null) {
                for (int c = 0; c < 4; c++) {
                    cells.add(cellText(row.getCell(c)));
                }
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        return "";
    }

    /** 「四性汇总」表头行的下一行起、四列齐全的行。 */
    private static List<String> natureRow(List<List<String>> rows, String displayName) {
        for (List<String> row : rows) {
            if (!row.isEmpty() && displayName.equals(row.get(0)) && row.size() >= 4) {
                return row;
            }
        }
        return null;
    }

    private static List<String> natureSectionHeader(List<List<String>> rows) {
        for (List<String> row : rows) {
            if (!row.isEmpty() && "四性汇总".equals(row.get(0))) {
                return row;
            }
        }
        return null;
    }
}
