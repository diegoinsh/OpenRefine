package org.openrefine.extensions.files.importer;

import java.util.Collections;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.refine.model.Column;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;

public class VolumeSummaryTest {

    @Test
    public void accumulatesResponsiblePartiesDatesAndPages() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1", 7);

        summary.add("张三", "20240105", 3, "关于2024年清明节放假的通知", null);
        summary.add("李四", "20231231", 5, "关于2024年春节放假的通知", null);
        summary.add("张三", "20240301", 2, "关于2024年端午节放假的通知", null);

        Assert.assertEquals(String.join(",", summary.responsibleParties), "张三,李四");
        Assert.assertEquals(summary.volumeTitle(),
                "关于2024年清明节放假、2024年春节放假、2024年端午节放假的通知");
        Assert.assertEquals(summary.minDate, "20231231");
        Assert.assertEquals(summary.maxDate, "20240301");
        Assert.assertEquals(summary.totalPages, 10);
        Assert.assertEquals(summary.fileCount, 7);
        Assert.assertEquals(summary.folderPath, "D:\\cases\\卷1");
    }

    @Test
    public void ignoresBlankResponsiblePartyAndDate() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷2", "D:\\cases\\卷2", 2);

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
        String[] columns = ExtractionTemplate.VOLUME_SUMMARY_COLUMNS;
        for (int i = 0; i < columns.length; i++) {
            summarySheet.columnModel.addColumn(i, new Column(i, columns[i]), false);
        }
        summarySheet.columnModel.update();
        task.summarySheet = summarySheet;

        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1", 7);
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
        Assert.assertEquals(cellText(row, summarySheet, "卷内文件份数"), "7");
        Assert.assertEquals(cellText(row, summarySheet, "文件夹路径"), "D:\\cases\\卷1");
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
                new BatchExtractionManager.VolumeSummary("卷1", "D:\\cases\\卷1", 2);

        summary.add("张三", "20240105", 1, "关于2024年清明节放假的通知", null);
        summary.add("张三", "20240106", 1, "关于2024年春节放假的通知", null);

        Assert.assertEquals(summary.volumeTitle(), "关于2024年清明节放假、2024年春节放假的通知");
    }

    @Test
    public void deduplicatesRepeatedCauses() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷2", "D:\\cases\\卷2", 2);

        summary.add("张三", "20240105", 1, "关于2024年清明节放假的通知", null);
        summary.add("李四", "20240106", 1, "关于2024年清明节放假的通知", null);

        Assert.assertEquals(summary.volumeTitle(), "关于2024年清明节放假的通知");
    }

    @Test
    public void fallsBackToMaterialWhenDocTypesDiffer() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷3", "D:\\cases\\卷3", 2);

        summary.add("张三", "20240105", 1, "关于2024年清明节放假的通知", null);
        summary.add("张三", "20240106", 1, "关于XX项目评审的纪要", null);

        Assert.assertEquals(summary.volumeTitle(), "关于2024年清明节放假、XX项目评审的材料");
    }

    @Test
    public void fallsBackToMaterialWhenNoDocTypeRecognized() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷4", "D:\\cases\\卷4", 2);

        summary.add("张三", "20240105", 1, "XX公司采购合同", null);
        summary.add("张三", "20240106", 1, "关于XX工作的其他事项", null);

        Assert.assertEquals(summary.volumeTitle(),
                "关于XX公司采购合同、关于XX工作的其他事项的材料");
    }

    @Test
    public void volumeTitleIsEmptyWhenNoTitle() {
        BatchExtractionManager.VolumeSummary summary =
                new BatchExtractionManager.VolumeSummary("卷5", "D:\\cases\\卷5", 1);

        summary.add("张三", "20240105", 1, "", null);

        Assert.assertEquals(summary.volumeTitle(), "");
    }

    private static void assertSplit(String title, String expectedCause, String expectedDocType) {
        String[] parts = TitleSplitter.splitCauseAndDocType(title);
        Assert.assertEquals(parts[0], expectedCause);
        Assert.assertEquals(parts[1], expectedDocType);
    }

    private static String cellText(Row row, SheetData sheet, String columnName) {
        int index = sheet.columnModel.getColumnByName(columnName).getCellIndex();
        return String.valueOf(row.getCell(index).value);
    }
}
