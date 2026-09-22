package org.google.refine.filesExtension.importer;

import java.util.Arrays;
import java.util.List;

import org.openrefine.extensions.files.importer.ExtractionTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 「文件名」列可见性：提取对象全是单页图片（无 PDF 等多页文件）时，
 * 件级/卷级批量模板都不生成该列；存在 PDF 等多页文件时保留。
 */
public class FileNameColumnVisibilityTest {

    private static List<String> columnsOf(ExtractionTemplate template, boolean includeFileNameColumn) {
        return Arrays.asList(template.getColumns(includeFileNameColumn));
    }

    @Test
    public void volumeTemplateKeepsFileNameColumnWhenMultiPageFilesExist() {
        List<String> columns = columnsOf(ExtractionTemplate.BATCH_TITLE_VOLUME, true);
        Assert.assertTrue(columns.contains(ExtractionTemplate.FILE_NAME_COLUMN));
        Assert.assertEquals(columns.size(), ExtractionTemplate.BATCH_TITLE_VOLUME.getColumns().length);
    }

    @Test
    public void volumeTemplateDropsFileNameColumnForSinglePageImages() {
        List<String> columns = columnsOf(ExtractionTemplate.BATCH_TITLE_VOLUME, false);
        Assert.assertFalse(columns.contains(ExtractionTemplate.FILE_NAME_COLUMN));
        // 只删该列，其余列与顺序保持不变
        Assert.assertEquals(columns, Arrays.asList("案卷号", "件号", "起止页号", "页数",
                "题名", "责任者", "文号", "成文日期", "文件夹路径", "提取状态", "备注"));
    }

    @Test
    public void caseTemplateKeepsFileNameColumnWhenMultiPageFilesExist() {
        List<String> columns = columnsOf(ExtractionTemplate.BATCH_TITLE_CASE, true);
        Assert.assertTrue(columns.contains(ExtractionTemplate.FILE_NAME_COLUMN));
        Assert.assertEquals(columns.size(), ExtractionTemplate.BATCH_TITLE_CASE.getColumns().length);
    }

    @Test
    public void caseTemplateDropsFileNameColumnForSinglePageImages() {
        List<String> columns = columnsOf(ExtractionTemplate.BATCH_TITLE_CASE, false);
        Assert.assertFalse(columns.contains(ExtractionTemplate.FILE_NAME_COLUMN));
        Assert.assertEquals(columns, Arrays.asList("文件夹名", "件号", "页数", "题名",
                "责任者", "文号", "成文日期", "文件夹路径", "提取状态", "备注"));
    }

    /** 非批量模板本就没有文件名列，开关与否结果一致 */
    @Test
    public void nonBatchTemplatesAreUnaffected() {
        for (ExtractionTemplate template : new ExtractionTemplate[]{
                ExtractionTemplate.ARCHIVE_CASE, ExtractionTemplate.ARCHIVE_VOLUME}) {
            Assert.assertEquals(columnsOf(template, false), columnsOf(template, true));
        }
    }

    /** getColumns() 返回副本，调用方修改不应污染模板 */
    @Test
    public void returnedColumnsAreDefensiveCopies() {
        String[] first = ExtractionTemplate.BATCH_TITLE_CASE.getColumns(false);
        first[0] = "被篡改";
        Assert.assertEquals(ExtractionTemplate.BATCH_TITLE_CASE.getColumns(false)[0], "文件夹名");
    }
}