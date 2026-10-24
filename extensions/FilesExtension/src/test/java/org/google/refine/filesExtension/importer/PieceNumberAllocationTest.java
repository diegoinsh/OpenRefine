package org.google.refine.filesExtension.importer;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.openrefine.extensions.files.importer.BatchExtractionManager;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * 件号分配规则：优先取文件名中「从后往前的 3 位、0 开头数字子串」；
 * 取不到或同卷内冲突时，以「前一件号」为基数追加 -1、-2 … 递增。
 */
public class PieceNumberAllocationTest {

    private BatchExtractionManager manager;
    private Method allocate;
    private Method parse;

    @BeforeMethod
    public void setup() throws Exception {
        manager = BatchExtractionManager.get();
        allocate = BatchExtractionManager.class.getDeclaredMethod(
                "allocatePieceNumber", String.class, Set.class, String.class);
        allocate.setAccessible(true);
        parse = BatchExtractionManager.class.getDeclaredMethod(
                "extractTrailingZeroPrefixed3", String.class);
        parse.setAccessible(true);
    }

    private String alloc(String fileName, Set<String> used, String lastPieceNo) throws Exception {
        return (String) allocate.invoke(manager, fileName, used, lastPieceNo);
    }

    private String parse3(String fileName) throws Exception {
        return (String) parse.invoke(null, fileName);
    }

    @Test
    public void takesTrailingZeroPrefixedThreeDigits() throws Exception {
        // 从后往前：括号中的 004 优于年份 2024 里出现的 024
        Assert.assertEquals(parse3("立鼎行发（2024-004）号.pdf"), "004");
        // 年份 2024 不以 0 开头，被跳过；取到前缀序号 001
        Assert.assertEquals(parse3("001-关于2024年清明节的放假通知.pdf"), "001");
        Assert.assertEquals(parse3("案卷（2020-012）.pdf"), "012");
        // 长数字串取末 3 位（扫描件常见的四位页序号）
        Assert.assertEquals(parse3("logo0010.pdf"), "010");
        Assert.assertEquals(parse3("0082.pdf"), "082");
        // 没有「0 开头的数字串」
        Assert.assertNull(parse3("2024-05.pdf"));
        Assert.assertNull(parse3("无编号文件.pdf"));
        Assert.assertNull(parse3(null));
    }

    @Test
    public void allocatesParsedNumberWhenFree() throws Exception {
        Set<String> used = new HashSet<>();
        Assert.assertEquals(alloc("001-甲.pdf", used, null), "001");
        Assert.assertEquals(alloc("002-乙.pdf", used, "001"), "002");
        Assert.assertTrue(used.contains("001"));
        Assert.assertTrue(used.contains("002"));
    }

    @Test
    public void conflictingNameFallsBackToSuffixIncrement() throws Exception {
        Set<String> used = new HashSet<>();
        String first = alloc("001-甲.pdf", used, null);
        String second = alloc("001-乙.pdf", used, first);
        String third = alloc("001-丙.pdf", used, second);
        Assert.assertEquals(first, "001");
        Assert.assertEquals(second, "001-1");
        // 连续冲突在同一基数上递增，而不是叠加为 001-1-1
        Assert.assertEquals(third, "001-2");
    }

    @Test
    public void unparsableNameContinuesFromPreviousPiece() throws Exception {
        Set<String> used = new HashSet<>();
        String first = alloc("001-甲.pdf", used, null);
        String second = alloc("无编号文件.pdf", used, first);
        Assert.assertEquals(second, "001-1");
    }

    @Test
    public void unparsableFirstPieceFallsBackToSequence() throws Exception {
        Set<String> used = new HashSet<>();
        Assert.assertEquals(alloc("无编号文件.pdf", used, null), "001");
    }
}