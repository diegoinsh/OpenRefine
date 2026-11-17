/*
 * Data Quality Extension - catalog 导入口径兼容测试
 *
 * 回归：下游回传的 catalog 有两套三级字段口径——
 *  · 新口径（2026-11-15 起）：level3Title = 三级材料类别，level4Title = 定制后的四级件题名；
 *  · 旧口径：没有 level4Title，level3Title 里放的是成品题名（实为四级）。
 * 旧口径不能把成品题名当材料类别；一级/二级分类的序号前缀也要在导入时裁掉。
 */
package com.google.refine.extension.quality.commands;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.refine.model.Cell;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.Row;

public class IngestProjectCommandCatalogTest {

    @Test
    public void legacyCatalogKeepsLevel3AsLevel4AndBlankCategory() throws Exception {
        String json = "{"
                + "\"volume\":{\"archiveNo\":\"SD01-2025-M1-5273\",\"caseNo\":\"(2025)鲁0102民初5273号\","
                + "\"level1\":\"正卷\"},"
                + "\"items\":["
                + "{\"pieceNo\":\"06\",\"title\":\"原告赵建明调解笔录20250228\","
                + "\"level1\":\"正卷\",\"level2\":\"20 庭前会议笔录、法庭笔录及相关材料\","
                + "\"level3Title\":\"原告赵建明调解笔录20250228\",\"pageCount\":2,"
                + "\"pages\":[{\"file\":\"0006.jpg\"}]},"
                + "{\"pieceNo\":\"13\",\"title\":\"济南恒盛精密机械有限公司民事答辩状\","
                + "\"level1\":\"正卷\",\"level2\":\"答辩状及相关材料\","
                + "\"level3Title\":\"恒盛精密机械有限公司民事答辩状\",\"pageCount\":3,"
                + "\"pages\":[{\"file\":\"0013.jpg\"}]}"
                + "]}";

        ColumnModel columnModel = new ColumnModel();
        List<Row> rows = new ArrayList<>();
        String semantics = applyCatalog(json, columnModel, rows);

        assertEquals("legacy", semantics, "没有 level4Title 的历史 catalog 应识别为旧口径");
        assertEquals(2, rows.size());
        Row first = rows.get(0);
        assertEquals("原告赵建明调解笔录20250228", cell(columnModel, first, "三级题名"));
        assertEquals("原告赵建明调解笔录20250228", cell(columnModel, first, "四级题名"),
                "旧口径的 level3Title 实为四级题名，应回填四级题名列");
        assertEquals("", cell(columnModel, first, "材料类别"), "旧口径没有材料类别，必须留空");
        assertEquals("庭前会议笔录、法庭笔录及相关材料", cell(columnModel, first, "二级分类"),
                "二级分类的序号前缀应在导入时裁掉");
        assertEquals("恒盛精密机械有限公司民事答辩状", cell(columnModel, rows.get(1), "四级题名"));
        assertTrue(columnModel.getColumnByName("四级题名") != null);
        assertTrue(columnModel.getColumnByName("材料类别") != null);
    }

    @Test
    public void modernCatalogSplitsMaterialCategoryFromLevel4() throws Exception {
        String json = "{"
                + "\"volume\":{\"archiveNo\":\"SD01-2025-M1-5273\",\"caseNo\":\"(2025)鲁0102民初5273号\","
                + "\"level1\":\"正卷\"},"
                + "\"items\":["
                + "{\"pieceNo\":\"06\",\"title\":\"原告赵建明调解笔录20250228\","
                + "\"level1\":\"正卷\",\"level2\":\"庭前会议笔录、法庭笔录及相关材料\","
                + "\"level3Title\":\"庭前调解笔录\","
                + "\"level4Title\":\"原告赵建明调解笔录20250228\",\"pageCount\":2,"
                + "\"pages\":[{\"file\":\"0006.jpg\"}]}"
                + "]}";

        ColumnModel columnModel = new ColumnModel();
        List<Row> rows = new ArrayList<>();
        String semantics = applyCatalog(json, columnModel, rows);

        assertEquals("new", semantics);
        Row first = rows.get(0);
        assertEquals("庭前调解笔录", cell(columnModel, first, "材料类别"),
                "新口径的 level3Title 是三级材料类别");
        assertEquals("原告赵建明调解笔录20250228", cell(columnModel, first, "四级题名"));
    }

    private static String applyCatalog(String json, ColumnModel columnModel, List<Row> rows) throws Exception {
        File temp = Files.createTempFile("catalog-", ".json").toFile();
        try {
            Files.write(temp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Method method = IngestProjectCommand.class.getDeclaredMethod(
                    "applyCatalog", ColumnModel.class, List.class, File.class, File.class);
            method.setAccessible(true);
            return (String) method.invoke(null, columnModel, rows, temp, null);
        } finally {
            temp.delete();
        }
    }

    private static String cell(ColumnModel columnModel, Row row, String columnName) {
        int index = columnModel.getColumnByName(columnName).getCellIndex();
        Cell cell = row.getCell(index);
        return cell == null || cell.value == null ? "" : cell.value.toString();
    }
}
