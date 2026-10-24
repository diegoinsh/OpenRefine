package org.openrefine.extensions.files.importer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

/** 卷目录扫描、自然序与卷内页号解析的纯函数回归（下沉后与 OR 无关）。 */
public class UnitScannerTest {

    @Test
    public void naturalOrderComparesNumbersNumerically() {
        assertTrue(UnitScanner.NATURAL_ORDER.compare("2.jpg", "10.jpg") < 0);
        assertEquals(UnitScanner.NATURAL_ORDER.compare("0010.jpg", "10.jpg"), 0);
        assertTrue(UnitScanner.NATURAL_ORDER.compare("0009.jpg", "0010.jpg") < 0);
    }

    @Test
    public void parseInternalPageNoFollowsNamingRules() {
        assertEquals(UnitScanner.parseInternalPageNo("0001.jpg"), Integer.valueOf(1));
        assertEquals(UnitScanner.parseInternalPageNo("0016.jpg"), Integer.valueOf(16));
        assertNull(UnitScanner.parseInternalPageNo("0000-01.jpg"), "卷前材料不计卷内页号");
        assertNull(UnitScanner.parseInternalPageNo("0000.jpg"), "零号无区分度");
        assertNull(UnitScanner.parseInternalPageNo("封面.jpg"), "无编号回退列表下标");
        assertNull(UnitScanner.parseInternalPageNo("0010-关于下达补助资金的通知.jpg"), "编号不在末尾时回退列表下标");
    }

    @Test
    public void scanVolumesSortsPagesAndFlagsMixedContent() throws Exception {
        File root = Files.createTempDirectory("unit-scanner").toFile();
        try {
            File vol = new File(root, "卷2");
            assertTrue(vol.mkdirs());
            touch(vol, "0002.jpg");
            touch(vol, "0010.jpg");
            touch(vol, "0000-01.jpg");

            File mixed = new File(root, "卷3");
            assertTrue(mixed.mkdirs());
            touch(mixed, "a.jpg");
            touch(mixed, "b.pdf");

            List<UnitScanner.Volume> volumes = UnitScanner.scanVolumes(root.getAbsolutePath());
            assertEquals(volumes.size(), 1, "混合内容卷应被丢弃");
            UnitScanner.Volume v = volumes.get(0);
            assertEquals(v.name, "卷2");
            assertEquals(v.pages.size(), 3);
            assertTrue(v.pages.get(0).endsWith("0000-01.jpg"), "卷前材料按自然序在最前");
            assertTrue(v.pages.get(1).endsWith("0002.jpg"));
            assertTrue(v.pages.get(2).endsWith("0010.jpg"), "自然序：0002 在 0010 前");
            assertTrue(v.numberedPages);
            assertNull(v.pageNos.get(0), "0000-01 不计页号");
            assertEquals(v.pageNos.get(1), Integer.valueOf(2));
            assertEquals(v.pageNos.get(2), Integer.valueOf(10));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void scanCasesTreatsRootPdfAsSingleCase() throws Exception {
        File root = Files.createTempDirectory("unit-scanner-cases").toFile();
        try {
            File dir = new File(root, "卷1");
            assertTrue(dir.mkdirs());
            touch(dir, "1.jpg");
            touch(root, "独立案卷.pdf");

            List<UnitScanner.Volume> cases = UnitScanner.scanCases(root.getAbsolutePath());
            assertEquals(cases.size(), 2);
            UnitScanner.Volume pdfCase = cases.get(1);
            assertEquals(pdfCase.name, "独立案卷");
            assertTrue(pdfCase.pdfMode);
            assertFalse(pdfCase.numberedPages, "整份 PDF 的页号回退为列表下标");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void touch(File dir, String name) throws Exception {
        assertTrue(new File(dir, name).createNewFile());
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            Arrays.stream(children).forEach(UnitScannerTest::deleteRecursively);
        }
        file.delete();
    }
}
