package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.refine.model.Column;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;

public class VolumeSummaryTest {

    @Test
    public void accumulatesResponsiblePartiesDatesAndPages() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");

        summary.add("张三", "20240105", 3, "关于2024年清明节放假的通知", null);
        summary.add("李四", "20231231", 5, "关于2024年春节放假的通知", null);
        summary.add("张三", "20240301", 2, "关于2024年端午节放假的通知", null);

        Assert.assertEquals(String.join(",", summary.responsibleParties), "张三,李四");
        Assert.assertEquals(summary.volumeTitle(),
                "关于2024年清明节放假、2024年春节放假、2024年端午节放假的通知");
        Assert.assertEquals(summary.minDate, "20231231");
        Assert.assertEquals(summary.maxDate, "20240301");
        Assert.assertEquals(summary.totalPages, 10);
        Assert.assertEquals(summary.pieceCount, 3);
        Assert.assertEquals(summary.folderPath, "D:\\cases\\卷1");
    }

    @Test
    public void ignoresBlankResponsiblePartyAndDate() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷2", "D:\\cases\\卷2");

        summary.add("", "", 1, "", null);
        summary.add(null, null, 4, null, null);
        summary.add("   ", "   ", 2, "   ", null);

        Assert.assertTrue(summary.responsibleParties.isEmpty());
        Assert.assertEquals(summary.volumeTitle(), "");
        Assert.assertNull(summary.minDate);
        Assert.assertNull(summary.maxDate);
        Assert.assertEquals(summary.totalPages, 7);
    }

    @Test
    public void rebuildsSummarySheetFromAccumulator() throws Exception {
        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                1L, "n/a", ExtractionTemplate.BATCH_TITLE_VOLUME,
                Collections.emptyList(), null, null, Collections.emptyList(),
                Collections.emptyList(), false);

        SheetData summarySheet = new SheetData(BatchExtractionCommand.SUMMARY_SHEET_ID,
                BatchExtractionCommand.SUMMARY_SHEET_NAME, "");
        List<String> columns = ExtractionTemplate.volumeSummaryColumns();
        for (int i = 0; i < columns.size(); i++) {
            summarySheet.columnModel.addColumn(i, new Column(i, columns.get(i)), false);
        }
        summarySheet.columnModel.update();
        task.summarySheet = summarySheet;

        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("张三", "20240105", 3, "关于XX工作的通知", null);
        task.volumeSummaries.put("卷1", summary);

        BatchExtractionManager.get().rebuildVolumeSummary(task);

        Assert.assertEquals(summarySheet.rows.size(), 1);
        Row row = summarySheet.rows.get(0);
        Assert.assertEquals(cellText(row, summarySheet, "案卷号"), "卷1");
        Assert.assertEquals(cellText(row, summarySheet, "题名"), "关于XX工作的通知");
        Assert.assertEquals(cellText(row, summarySheet, "责任者"), "张三");
        Assert.assertEquals(cellText(row, summarySheet, "起始时间"), "20240105");
        Assert.assertEquals(cellText(row, summarySheet, "终止时间"), "20240105");
        Assert.assertEquals(cellText(row, summarySheet, "总页数"), "3");
        Assert.assertEquals(cellText(row, summarySheet, "卷内文件份数"), "1");
        Assert.assertEquals(cellText(row, summarySheet, "文件夹路径"), "D:\\cases\\卷1");
    }

    @Test
    public void writesVolumeLevelElementsIntoSummaryAndLeavesMissingBlank() throws Exception {
        List<CustomElementType> elements = Arrays.asList(
                new CustomElementType("案由", "anyou", CustomElementType.ACTION_INCLUDE, ""),
                new CustomElementType("当事人", "dangshiren", CustomElementType.ACTION_INCLUDE, ""),
                new CustomElementType("密级", "miji", CustomElementType.ACTION_INCLUDE, ""),
                new CustomElementType("保管期限", "baoguan_qixian", CustomElementType.ACTION_INCLUDE, ""));

        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                3L, "n/a", ExtractionTemplate.BATCH_TITLE_VOLUME,
                elements, null, null, Collections.emptyList(),
                Collections.emptyList(), false);

        List<String> columns = ExtractionTemplate.volumeSummaryColumns();
        columns.addAll(columns.size() - 1, Arrays.asList("案由", "当事人", "密级", "保管期限"));
        SheetData summarySheet = new SheetData(BatchExtractionCommand.SUMMARY_SHEET_ID,
                BatchExtractionCommand.SUMMARY_SHEET_NAME, "");
        for (int i = 0; i < columns.size(); i++) {
            summarySheet.columnModel.addColumn(i, new Column(i, columns.get(i)), false);
        }
        summarySheet.columnModel.update();
        task.summarySheet = summarySheet;

        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("张三", "20240105", 1, "民事判决书", mapOf(
                "anyou", "买卖合同纠纷",
                "dangshiren", "沈建明，苏州恒盛精密机械有限公司",
                "baoguan_qixian", "60年"));
        task.volumeSummaries.put("卷1", summary);

        BatchExtractionManager.get().rebuildVolumeSummary(task);

        Row row = summarySheet.rows.get(0);
        Assert.assertEquals(cellText(row, summarySheet, "案由"), "买卖合同纠纷");
        // 当事人按件拆分后以全角逗号重新拼接
        Assert.assertEquals(cellText(row, summarySheet, "当事人"), "沈建明，苏州恒盛精密机械有限公司");
        Assert.assertEquals(cellText(row, summarySheet, "保管期限"), "60年");
        // 密级整卷无提取值：按卷级要素写空值，不臆造默认值
        Assert.assertEquals(cellText(row, summarySheet, "密级"), "");
    }

    @Test
    public void folderPathIsTheLastColumnOfBothTables() {
        String[] innerColumns = ExtractionTemplate.BATCH_TITLE_VOLUME.getColumns(true);
        Assert.assertEquals(innerColumns[innerColumns.length - 1], ExtractionTemplate.FOLDER_PATH_COLUMN);
        List<String> summaryColumns = ExtractionTemplate.volumeSummaryColumns();
        Assert.assertEquals(summaryColumns.get(summaryColumns.size() - 1), ExtractionTemplate.FOLDER_PATH_COLUMN);
    }

    @Test
    public void aggregatesVolumeElementsFromPieceLevelValues() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        // 件级值已是规范化后的「全角逗号连接」形式（见 normalizesPartyValueAtPieceLevel）
        // 件1：调解笔录，密级「公开」
        summary.add("苏州市吴中区人民法院", "20230310", 2, "调解笔录", mapOf(
                "dangshiren", "沈建明，苏州恒盛精密机械有限公司",
                "anyou", "买卖合同纠纷", "jiean_fangshi", "调解", "miji", "公开"));
        // 件2：判决书，成文日期更晚，密级「内部」
        summary.add("苏州市吴中区人民法院", "20230618", 4, "民事判决书", mapOf(
                "dangshiren", "沈建明，苏州恒盛精密机械有限公司",
                "anyou", "买卖合同纠纷", "jiean_fangshi", "判决", "miji", "内部"));
        summary.resolveElements();

        // 当事人：并集去重
        Assert.assertEquals(summary.volumeElements.get("dangshiren"), "沈建明，苏州恒盛精密机械有限公司");
        // 结案方式：取成文日期最晚一件的取值，过程性的「调解」被「判决」覆盖
        Assert.assertEquals(summary.volumeElements.get("jiean_fangshi"), "判决");
        // 密级：整卷取最严一档
        Assert.assertEquals(summary.volumeElements.get("miji"), "内部");
        // 案由：取众数
        Assert.assertEquals(summary.volumeElements.get("anyou"), "买卖合同纠纷");
    }

    /** 当事人值的规范化属件级策略：剥离称谓、空白也作分隔、件内去重、统一全角逗号 */
    @Test
    public void normalizesPartyValueAtPieceLevel() {
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames(
                        "原告沈建明，被告苏州恒盛精密机械有限公司"),
                "沈建明，苏州恒盛精密机械有限公司");
        // 空白（含全角空格）与顿号、分号都可作分隔，不能被粘成一个词
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("郭长海 郭秀萍"), "郭长海，郭秀萍");
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("袁连顺　袁风莲、袁凤仙；袁凤鸣"),
                "袁连顺，袁风莲，袁凤仙，袁凤鸣");
        // 件内重复项去掉
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("沈建明，沈建明"), "沈建明");
        // 整值都是诉讼地位称谓：不是当事人名称
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("原告，被告"), "");
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("被告苏州恒盛精密机械有限公司"),
                "苏州恒盛精密机械有限公司");
    }

    /** 身份证明类材料的责任者取证件本人／执照单位，模型回吐的签发机关要按无依据清空 */
    @Test
    public void dropsIssuerFromIdentityCertificateResponsibleParty() {
        Map<String, String> idCard = new LinkedHashMap<>();
        idCard.put("title", "原告身份证明");
        idCard.put("responsible_party", "苏州市公安局吴中分局");
        BatchExtractionManager.normalizePieceValues(idCard);
        Assert.assertEquals(idCard.get("responsible_party"), "");

        // 营业执照类：责任者本就是执照单位名称，不是签发机关，不能误伤
        Map<String, String> license = new LinkedHashMap<>();
        license.put("title", "被告身份证明");
        license.put("responsible_party", "苏州恒盛精密机械有限公司");
        BatchExtractionManager.normalizePieceValues(license);
        Assert.assertEquals(license.get("responsible_party"), "苏州恒盛精密机械有限公司");
    }

    /** 不编文号的材料（笔录／送达回证／送达地址确认书／缴费凭证／记录类表格）清掉回填的案号 */
    @Test
    public void clearsDocumentNumberForMaterialsThatHaveNone() {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("title", "证物处理单");
        evidence.put("document_number", "〔2025〕苏0506民初4821号");
        BatchExtractionManager.normalizePieceValues(evidence);
        Assert.assertEquals(evidence.get("document_number"), "");

        // 结案登记表确有案号，不能误伤
        Map<String, String> closing = new LinkedHashMap<>();
        closing.put("title", "结案登记表");
        closing.put("document_number", "〔2025〕苏0506民初4821号");
        BatchExtractionManager.normalizePieceValues(closing);
        Assert.assertEquals(closing.get("document_number"), "〔2025〕苏0506民初4821号");
    }

    /** 模型在「有栏位名、没有实际姓名」时会回吐占位值，这类垃圾不得进入件级与卷级结果 */
    @Test
    public void dropsPlaceholderPartyValues() {
        // 带冒号（「上诉人：XXX」）与并排（「原告XXX，被告XXX」）两种回吐形式都要清干净
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("上诉人：XXX，被上诉人：XXX"), "");
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("原告XXX，被告XXX"), "");
        // 混合：真实姓名保留，占位与剥离称谓后残留的标点一并剔除
        Assert.assertEquals(BatchExtractionManager.normalizePartyNames("郭长海，郭秀萍，：XXX"), "郭长海，郭秀萍");
        Assert.assertTrue(BatchExtractionManager.isPlaceholderValue("×××"));
        Assert.assertTrue(BatchExtractionManager.isPlaceholderValue("："));
        Assert.assertFalse(BatchExtractionManager.isPlaceholderValue("郭长海"));
    }

    @Test
    public void dropsOneOffPartyNamesOnlyWhenRepeatExists() {
        // 卷内存在重复取值：单次出现的取值没有旁证，视为该页误读，剔除
        BatchExtractionManager.VolumeSummary withNoise =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        withNoise.add("x", "20240101", 1, "民事判决书", mapOf("dangshiren", "沈建明，苏州恒盛精密机械有限公司"));
        withNoise.add("x", "20240102", 1, "民事判决书", mapOf("dangshiren", "沈建明，苏州恒盛精密机械有限公司"));
        withNoise.add("x", "20240103", 1, "民事判决书", mapOf("dangshiren", "苏州恒盈精密机械有限公司"));
        withNoise.resolveElements();
        Assert.assertEquals(withNoise.volumeElements.get("dangshiren"), "沈建明，苏州恒盛精密机械有限公司");

        // 整卷所有取值都只出现一次：没有可对照的一致性信号，全部计入，不做裁剪
        BatchExtractionManager.VolumeSummary allSingle =
                new BatchExtractionManager.VolumeSummary("卷2", "D:\\cases\\卷2");
        allSingle.add("x", "20240101", 1, "民事判决书", mapOf("dangshiren", "沈建明"));
        allSingle.add("x", "20240102", 1, "民事判决书", mapOf("dangshiren", "苏州恒盛精密机械有限公司"));
        allSingle.resolveElements();
        Assert.assertEquals(allSingle.volumeElements.get("dangshiren"), "沈建明，苏州恒盛精密机械有限公司");
    }

    @Test
    public void classifiesVolumeLevelElementKeys() {
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("anyou"));
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("dangshiren"));
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("shenji"));
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("jiean_fangshi"));
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("baoguan_qixian"));
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("miji"));
        Assert.assertTrue(ExtractionTemplate.isVolumeLevelElement("kaifang_zhuangtai"));
        // 件级要素不在此列：题名/责任者/文号/成文日期
        Assert.assertFalse(ExtractionTemplate.isVolumeLevelElement("title"));
        Assert.assertFalse(ExtractionTemplate.isVolumeLevelElement("responsible_party"));
        Assert.assertFalse(ExtractionTemplate.isVolumeLevelElement(null));
    }

    @Test
    public void rebuildIsNoOpForSingleSheetProject() throws Exception {
        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                2L, "n/a", ExtractionTemplate.BATCH_TITLE_CASE,
                Collections.emptyList(), null, null, Collections.emptyList(),
                Collections.emptyList(), false);

        BatchExtractionManager.get().rebuildVolumeSummary(task);

        Assert.assertNull(task.summarySheet);
        Assert.assertTrue(task.volumeSummaries.isEmpty());
    }

    @Test
    public void splitsStandardTitleIntoCauseAndDocType() {
        assertSplit("关于2024年清明节放假的通知", "2024年清明节放假", "通知");
        // 事由自带「的」时取最后一个「的」之后为文种
        assertSplit("关于印发《XX管理办法》的通知", "印发《XX管理办法》", "通知");
        // 尾段不是已知文种，整条作为事由、不计文种
        assertSplit("关于XX工作的其他事项", "关于XX工作的其他事项", "");
        // 不符合「关于…的…」结构，整条作为事由
        assertSplit("XX公司采购合同", "XX公司采购合同", "");
        assertSplit("   ", "", "");
        assertSplit(null, "", "");
    }

    @Test
    public void aggregatesVolumeTitleForSingleDocType() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");

        summary.add("张三", "20240105", 1, "关于2024年清明节放假的通知", null);
        summary.add("张三", "20240106", 1, "关于2024年春节放假的通知", null);

        Assert.assertEquals(summary.volumeTitle(), "关于2024年清明节放假、2024年春节放假的通知");
    }

    @Test
    public void deduplicatesRepeatedCauses() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷2", "D:\\cases\\卷2");

        summary.add("张三", "20240105", 1, "关于2024年清明节放假的通知", null);
        summary.add("李四", "20240106", 1, "关于2024年清明节放假的通知", null);

        Assert.assertEquals(summary.volumeTitle(), "关于2024年清明节放假的通知");
    }

    @Test
    public void fallsBackToMaterialWhenDocTypesDiffer() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷3", "D:\\cases\\卷3");

        summary.add("张三", "20240105", 1, "关于2024年清明节放假的通知", null);
        summary.add("张三", "20240106", 1, "关于XX项目评审的纪要", null);

        Assert.assertEquals(summary.volumeTitle(), "关于2024年清明节放假、XX项目评审的材料");
    }

    @Test
    public void fallsBackToMaterialWhenNoDocTypeRecognized() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷4", "D:\\cases\\卷4");

        summary.add("张三", "20240105", 1, "XX公司采购合同", null);
        summary.add("张三", "20240106", 1, "关于XX工作的其他事项", null);

        Assert.assertEquals(summary.volumeTitle(),
                "关于XX公司采购合同、关于XX工作的其他事项的材料");
    }

    @Test
    public void volumeTitleIsEmptyWhenNoTitle() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷5", "D:\\cases\\卷5");

        summary.add("张三", "20240105", 1, "", null);

        Assert.assertEquals(summary.volumeTitle(), "");
    }

    /** 卷内文件份数按件累加（件数），不是目录下扫描文件个数 */
    @Test
    public void countsInnerPiecesNotScannedFiles() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("张三", "20240101", 1, "民事判决书", null);
        summary.add("李四", "20240102", 3, "民事上诉状", null);
        summary.add("王五", "20240103", 2, "送达回证", null);

        Assert.assertEquals(summary.pieceCount, 3);
        Assert.assertEquals(summary.totalPages, 6);
    }

    /** 案卷级责任者只著录主要责任者：本卷立卷法院，不铺开卷内各件责任者 */
    @Test
    public void volumeResponsiblePartyKeepsOnlyTheFilingCourt() throws Exception {
        List<String> columns = ExtractionTemplate.volumeSummaryColumns();
        SheetData summarySheet = new SheetData(BatchExtractionCommand.SUMMARY_SHEET_ID,
                BatchExtractionCommand.SUMMARY_SHEET_NAME, "");
        for (int i = 0; i < columns.size(); i++) {
            summarySheet.columnModel.addColumn(i, new Column(i, columns.get(i)), false);
        }
        summarySheet.columnModel.update();
        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                5L, "n/a", ExtractionTemplate.BATCH_TITLE_VOLUME,
                Collections.emptyList(), null, null, Collections.emptyList(),
                Collections.emptyList(), false);
        task.summarySheet = summarySheet;

        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        // 立卷法院出 2 件、原审法院出 1 件、当事人材料 1 件
        summary.add("河北省张家口市中级人民法院", "20240102", 1, "受理案件通知书", null);
        summary.add("河北省张家口市中级人民法院", "20240301", 2, "民事判决书", null);
        summary.add("河北省怀安县人民法院", "20231120", 5, "民事判决书", null);
        summary.add("袁连顺，袁凤莲", "20240108", 2, "民事上诉状", null);
        task.volumeSummaries.put("卷1", summary);

        BatchExtractionManager.get().rebuildVolumeSummary(task);

        Row row = summarySheet.rows.get(0);
        Assert.assertEquals(cellText(row, summarySheet, "责任者"), "河北省张家口市中级人民法院");
    }

    /** 当事人超过 3 人时：列前 3 位、第 4 位起以「等」概称，避免题名冗长（GB/T 9705—2008 3.1.3.3） */
    @Test
    public void abbreviatesLongPartyListInVolumeTitle() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("河北省张家口市中级人民法院", "20240410", 1, "民事判决书", mapOf(
                "dangshiren", "袁连顺，袁凤莲，袁凤仙，袁凤鸣",
                "anyou", "继承纠纷", "shenji", "二审"));
        summary.resolveElements();

        Assert.assertEquals(summary.volumeTitle(),
                "河北省张家口市中级人民法院关于袁连顺，袁凤莲，袁凤仙等继承纠纷一案的二审诉讼档案");
    }

    /** 错字与正字从不同件出现：按「件数众数」把错字归并到正字 */
    @Test
    public void mergesTypoThatNeverCoexistsWithTheCorrectName() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        // 正字「袁凤莲」出现在 3 件
        summary.add("x", "20240101", 1, "民事判决书", mapOf("dangshiren", "袁凤莲，袁凤仙，袁凤鸣"));
        summary.add("x", "20240102", 1, "应诉通知书", mapOf("dangshiren", "袁凤莲，袁凤仙，袁凤鸣"));
        summary.add("x", "20240103", 1, "送达回证", mapOf("dangshiren", "袁凤莲，袁凤仙，袁凤鸣"));
        // 错字「袁风莲」只出现在答辩状、上诉状，与正字从不同件
        summary.add("x", "20240104", 1, "民事答辩状", mapOf("dangshiren", "袁风莲"));
        summary.add("x", "20240105", 1, "民事上诉状", mapOf("dangshiren", "袁风莲"));
        summary.resolveElements();

        Assert.assertEquals(summary.volumeElements.get("dangshiren"), "袁凤莲，袁凤仙，袁凤鸣");
    }

    /** 兄弟名字只差一字、但同件并列出现 ⇒ 两个不同的人，绝不归并 */
    @Test
    public void keepsSiblingsThatAppearInTheSamePiece() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("x", "20240101", 1, "民事判决书", mapOf("dangshiren", "袁凤仙，袁凤鸣"));
        summary.add("x", "20240102", 1, "应诉通知书", mapOf("dangshiren", "袁凤仙，袁凤鸣"));
        summary.resolveElements();

        Assert.assertEquals(summary.volumeElements.get("dangshiren"), "袁凤仙，袁凤鸣");
    }

    /** 两个名字从不共件、件数又相同：裁决不了谁是错字，保持原样 */
    @Test
    public void keepsNamesWithEqualPieceCount() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("x", "20240101", 1, "民事判决书", mapOf("dangshiren", "张伟"));
        summary.add("x", "20240102", 1, "民事判决书", mapOf("dangshiren", "张炜"));
        summary.resolveElements();

        Assert.assertEquals(summary.volumeElements.get("dangshiren"), "张伟，张炜");
    }

    private static void assertSplit(String title, String expectedCause, String expectedDocType) {
        String[] parts = TitleSplitter.splitCauseAndDocType(title);
        Assert.assertEquals(parts[0], expectedCause);
        Assert.assertEquals(parts[1], expectedDocType);
    }

    /**
     * 页型按「卷路径 → 页号」分组挂在页映射的保留键下：页号只在卷内唯一，两卷各自的第 1 页
     * 必须互不覆盖。页型为空（分类器未启用、或模型没给出）时不写——宁缺勿造。
     */
    @Test
    public void pageTypeMapGroupsByVolumeAndPage() {
        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                4L, "C:/tmp/root", ExtractionTemplate.BATCH_TITLE_VOLUME,
                Collections.<CustomElementType>emptyList(), null, null,
                Collections.<String>emptyList(), Collections.<UnitScanner.Volume>emptyList(), false);

        task.recordPageType("D:/arch/卷1", 1, "header_page");
        task.recordPageType("D:/arch/卷1", 2, "content_page");
        task.recordPageType("D:/arch/卷2", 1, "signature_page");
        task.recordPageType("D:/arch/卷2", 2, "");
        task.recordPageType(null, 3, "content_page");

        JsonNode types = task.pageMap.get(BatchExtractionManager.PAGE_TYPE_MAP_KEY);
        Assert.assertNotNull(types, "页型应写在页映射的保留键下");
        Assert.assertEquals(2, types.size());
        Assert.assertEquals("header_page", types.path("D:/arch/卷1").path("1").asText());
        Assert.assertEquals("content_page", types.path("D:/arch/卷1").path("2").asText());
        Assert.assertEquals("signature_page", types.path("D:/arch/卷2").path("1").asText());
        Assert.assertFalse(types.path("D:/arch/卷2").has("2"), "空页型不应写入");
    }

    /** 卷级行也要能点击定位到页：键加表前缀，要素列给来源页、非要素列给本卷首件首页 */
    @Test
    public void buildsSummaryPageMapForVolumeRows() throws Exception {
        List<CustomElementType> elements = Arrays.asList(
                new CustomElementType("案由", "anyou", CustomElementType.ACTION_INCLUDE, ""),
                new CustomElementType("当事人", "dangshiren", CustomElementType.ACTION_INCLUDE, ""));

        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                4L, "n/a", ExtractionTemplate.BATCH_TITLE_VOLUME,
                elements, null, null, Collections.emptyList(),
                Collections.emptyList(), false);

        List<String> columns = ExtractionTemplate.volumeSummaryColumns();
        columns.addAll(columns.size() - 1, Arrays.asList("案由", "当事人"));
        SheetData summarySheet = new SheetData(BatchExtractionCommand.SUMMARY_SHEET_ID,
                BatchExtractionCommand.SUMMARY_SHEET_NAME, "");
        for (int i = 0; i < columns.size(); i++) {
            summarySheet.columnModel.addColumn(i, new Column(i, columns.get(i)), false);
        }
        summarySheet.columnModel.update();
        task.summarySheet = summarySheet;

        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("苏州市吴中区人民法院", "20230401", 2, "民事起诉状", mapOf(
                        "dangshiren", "沈建明，苏州恒盛精密机械有限公司", "anyou", "买卖合同纠纷"),
                candidatesOf("dangshiren", "原告沈建明，被告苏州恒盛精密机械有限公司", 2,
                        "anyou", "买卖合同纠纷", 2),
                pieceStartNode(2));
        summary.add("苏州市吴中区人民法院", "20230618", 4, "民事判决书", mapOf(
                        "dangshiren", "沈建明，苏州恒盛精密机械有限公司", "anyou", "买卖合同纠纷"),
                candidatesOf("dangshiren", "沈建明，苏州恒盛精密机械有限公司", 5,
                        "anyou", "买卖合同纠纷", 5),
                pieceStartNode(5));
        task.volumeSummaries.put("卷1", summary);

        BatchExtractionManager.get().rebuildVolumeSummary(task);

        JsonNode rowNode = task.pageMap.get(BatchExtractionManager.SUMMARY_PAGE_MAP_PREFIX + 0);
        Assert.assertNotNull(rowNode, "卷级行应按「表id:行号」写入页映射");
        // 当事人：两位当事人的来源页按件序合并去重 → 第 2 页、第 5 页
        Assert.assertEquals(rowNode.get("当事人").size(), 2);
        Assert.assertEquals(rowNode.get("当事人").get(0).get("p").asInt(), 2);
        Assert.assertEquals(rowNode.get("当事人").get(1).get("p").asInt(), 5);
        // 案由：两件取值相同，来源页都保留
        Assert.assertEquals(rowNode.get("案由").size(), 2);
        // 要素以外的列：定位到本卷首件首页
        Assert.assertEquals(rowNode.get(BatchExtractionManager.PIECE_START_KEY).get("p").asInt(), 2);
    }

    /** 证件类件识别：身份证、户口簿、营业执照等证面上的签发日期不作为成文日期 */
    @Test
    public void classifiesCertificatePieces() {
        Assert.assertTrue(BatchExtractionManager.isCertificatePiece("居民身份证"));
        Assert.assertTrue(BatchExtractionManager.isCertificatePiece("被告身份证明"));
        Assert.assertTrue(BatchExtractionManager.isCertificatePiece("营业执照"));
        Assert.assertFalse(BatchExtractionManager.isCertificatePiece("民事判决书"));
        Assert.assertFalse(BatchExtractionManager.isCertificatePiece(null));
    }

    /** 件级成文日期加权择优：末页落款（高档）胜过首页正文里的叙述性日期（中档） */
    @Test
    public void picksDateByPositionWeightNotRawConfidence() {
        // 回归 JZ07-2024-M2-0158 件 002：0002 页正文里的 20231120 模型自评更高，
        // 但 AIMP 重估后它是「有据但夹在正文中」(0.5)，落款页的 20231218 是「有据且独立成行」(0.95)
        List<Map<String, String>> pageValues = Arrays.asList(
                mapOf("date", "20231120", "title", "民事判决书"),
                mapOf("date", "20240101"),
                mapOf("date", "20231218"));
        List<Map<String, Double>> pageConfidences = Arrays.asList(
                Map.of("date", 0.5), Map.of("date", 0.5), Map.of("date", 0.95));

        BatchExtractionManager.MergedElements merged =
                BatchExtractionManager.accumulateByConfidence(pageValues, pageConfidences);

        Assert.assertEquals(merged.values.get("date"), "20231218");
        Assert.assertEquals(merged.values.get("title"), "民事判决书");
    }

    /** 成文日期也可能在首页标题下方：首页独立成行的日期（高档）胜过末页正文日期（中档） */
    @Test
    public void picksIsolatedDateOnTheFirstPage() {
        List<Map<String, String>> pageValues = Arrays.asList(
                mapOf("date", "20240122", "title", "应诉通知书"),
                mapOf("date", "20240101"));
        List<Map<String, Double>> pageConfidences = Arrays.asList(
                Map.of("date", 0.95), Map.of("date", 0.5));

        BatchExtractionManager.MergedElements merged =
                BatchExtractionManager.accumulateByConfidence(pageValues, pageConfidences);

        Assert.assertEquals(merged.values.get("date"), "20240122");
    }

    /** 无署名材料识别：证物处理单等表格自身没有责任者栏，责任者应留空 */
    @Test
    public void classifiesPiecesWithoutResponsibleAuthor() {
        Assert.assertTrue(BatchExtractionManager.isNoResponsibleAuthorPiece("证物处理单"));
        Assert.assertTrue(BatchExtractionManager.isNoResponsibleAuthorPiece("卷内备考表"));
        Assert.assertFalse(BatchExtractionManager.isNoResponsibleAuthorPiece("送达回证"));
        Assert.assertFalse(BatchExtractionManager.isNoResponsibleAuthorPiece("民事判决书"));
        Assert.assertFalse(BatchExtractionManager.isNoResponsibleAuthorPiece(null));
    }

    /** 回归 JZ07-2024-M2-0241：错字「袁风莲」与正字从不同件 ⇒ 归并；与「袁凤仙」同件 ⇒ 不并 */
    @Test
    public void mergesTypoInRegressionVolume0241() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("x", "20231120", 5, "民事判决书", mapOf("dangshiren", "袁连顺，袁凤莲，袁凤仙，袁凤鸣"));
        summary.add("x", "20240122", 1, "应诉通知书", mapOf("dangshiren", "袁连顺，袁凤莲，袁凤仙，袁凤鸣"));
        summary.add("x", "20240125", 2, "民事答辩状", mapOf("dangshiren", "袁连顺，袁风莲，袁凤仙，袁凤鸣"));
        summary.add("x", "20240321", 7, "民事判决书", mapOf("dangshiren", "袁连顺，袁凤莲，袁凤仙，袁凤鸣"));
        summary.add("x", "20240410", 2, "退卷函", mapOf("dangshiren", "袁连顺，袁凤莲，袁凤仙，袁凤鸣"));
        // 送达回证的受送达人是手写签收，抽出的是错字「袁风莲」，但它与正字从不同件
        summary.add("x", "20240226", 5, "送达回证", mapOf("dangshiren", "袁连顺，袁风莲"));
        summary.resolveElements();

        Assert.assertEquals(summary.volumeElements.get("dangshiren"),
                "袁连顺，袁凤莲，袁凤仙，袁凤鸣");
    }

    /** 卷级「起始时间／终止时间」点击定位到取到该日期的那一页，而不是首件首页 */
    @Test
    public void mapsSummaryStartAndEndDatesToTheirSourcePages() throws Exception {
        BatchExtractionManager.Task task = new BatchExtractionManager.Task(
                6L, "n/a", ExtractionTemplate.BATCH_TITLE_VOLUME,
                Collections.emptyList(), null, null, Collections.emptyList(),
                Collections.emptyList(), false);

        List<String> columns = ExtractionTemplate.volumeSummaryColumns();
        SheetData summarySheet = new SheetData(BatchExtractionCommand.SUMMARY_SHEET_ID,
                BatchExtractionCommand.SUMMARY_SHEET_NAME, "");
        for (int i = 0; i < columns.size(); i++) {
            summarySheet.columnModel.addColumn(i, new Column(i, columns.get(i)), false);
        }
        summarySheet.columnModel.update();
        task.summarySheet = summarySheet;

        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1");
        summary.add("x", "20231120", 5, "民事判决书", null,
                candidatesOf("date", "20231120", 2), pieceStartNode(2));
        summary.add("x", "20240410", 2, "宣判笔录", null,
                candidatesOf("date", "20240410", 9), pieceStartNode(8));
        task.volumeSummaries.put("卷1", summary);

        BatchExtractionManager.get().rebuildVolumeSummary(task);

        JsonNode rowNode = task.pageMap.get(BatchExtractionManager.SUMMARY_PAGE_MAP_PREFIX + 0);
        Assert.assertNotNull(rowNode, "卷级行应按「表id:行号」写入页映射");
        Assert.assertEquals(rowNode.get("起始时间").get(0).get("p").asInt(), 2);
        Assert.assertEquals(rowNode.get("终止时间").get(0).get("p").asInt(), 9);
    }

    private static ObjectNode pieceStartNode(int page) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("v", "0001.jpg");
        node.put("c", 0.9);
        node.put("p", page);
        return node;
    }

    /** 构造候选页映射：key、取值、页码 三元一组 */
    private static Map<String, List<AimpLlmClient.ElementCandidate>> candidatesOf(Object... triples) {
        Map<String, List<AimpLlmClient.ElementCandidate>> map = new LinkedHashMap<>();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            map.put((String) triples[i], Collections.singletonList(
                    new AimpLlmClient.ElementCandidate((String) triples[i + 1], 0.9, (Integer) triples[i + 2], null)));
        }
        return map;
    }

    private static Map<String, String> mapOf(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String cellText(Row row, SheetData sheet, String columnName) {
        int index = sheet.columnModel.getColumnByName(columnName).getCellIndex();
        return String.valueOf(row.getCell(index).value);
    }
}
