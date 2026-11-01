package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 卷尾条目页判定的回归测试。
 *
 * <p>背景（实测 SD01-2025-M1-5273）：末件「庭审笔录」的第 2 页（0030.jpg）无题名，
 * 旧逻辑只按「结尾连续无题名」就把它当卷尾材料（备考表/证物袋/卷底）剔除，
 * 导致分件只到 0029、该页既不进条目也拿不到件级归并。</p>
 */
public class TrailingItemPageTest {

    private static UnitScanner.Volume volume(int pageCount) {
        UnitScanner.Volume volume = new UnitScanner.Volume();
        volume.name = "测试卷";
        volume.path = "测试卷";
        for (int i = 1; i <= pageCount; i++) {
            volume.pages.add(String.format("%04d.jpg", i));
            volume.pageNos.add(i);
        }
        volume.numberedPages = true;
        return volume;
    }

    private static List<String> titles(int pageCount, int titledCount) {
        List<String> titles = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            titles.add(i < titledCount ? "文书" + (i + 1) : "");
        }
        return titles;
    }

    /** 有正文的末页续页（页型 content_page、未跳过）必须留在条目页里 */
    @Test
    public void untitledContinuationPageIsKept() {
        int pageCount = 30;
        List<String> titles = titles(pageCount, pageCount - 1);
        List<Boolean> skipped = new ArrayList<>(Collections.nCopies(pageCount, false));
        List<String> pageTypes = new ArrayList<>(Collections.nCopies(pageCount, "content_page"));
        List<Map<String, String>> values = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            Map<String, String> v = new HashMap<>();
            v.put("title", titles.get(i));
            v.put("date", "20250315");
            values.add(v);
        }

        List<Integer> indexes = BatchExtractionManager.itemPageIndexes(
                volume(pageCount), titles, skipped, pageTypes, values);

        Assert.assertEquals(indexes.size(), pageCount, "末尾的正文续页不应被当卷尾材料剔除");
        Assert.assertEquals((int) indexes.get(indexes.size() - 1), pageCount - 1);
    }

    /** 卷尾空白材料（判跳过 / 短文本页）仍按位置兜底剔除 */
    @Test
    public void blankTrailingPagesAreExcluded() {
        int pageCount = 30;
        List<String> titles = titles(pageCount, pageCount - 3);
        List<Boolean> skipped = new ArrayList<>();
        List<String> pageTypes = new ArrayList<>();
        List<Map<String, String>> values = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            boolean tail = i >= pageCount - 3;
            skipped.add(tail);
            pageTypes.add(tail ? "short_text" : "content_page");
            values.add(Collections.emptyMap());
        }

        List<Integer> indexes = BatchExtractionManager.itemPageIndexes(
                volume(pageCount), titles, skipped, pageTypes, values);

        Assert.assertEquals(indexes.size(), pageCount - 3, "卷尾空白材料应被剔除");
    }

    /** 分件区间未覆盖的末页并入前一件，并同步卷内页号 */
    @Test
    public void uncoveredTailPageIsAppendedToPreviousPiece() {
        int pageCount = 30;
        TitleSplitter.Piece piece = new TitleSplitter.Piece();
        piece.startPage = 1;
        piece.endPage = 29;
        piece.startPageNo = 1;
        piece.endPageNo = 29;
        piece.title = "庭审笔录";
        List<TitleSplitter.Piece> pieces = new ArrayList<>();
        pieces.add(piece);

        List<Integer> itemIndexes = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) itemIndexes.add(i);

        BatchExtractionManager.appendUncoveredItemPages(pieces, itemIndexes, volume(pageCount));

        Assert.assertEquals(pieces.size(), 1, "漏页应并入已有件，不另起新件");
        Assert.assertEquals(piece.endPage, pageCount);
        Assert.assertEquals((Integer) piece.endPageNo, pageCount);
    }

    /** 件与件之间的缺口同样并入前一件 */
    @Test
    public void gapBetweenPiecesIsAppendedToPreviousPiece() {
        TitleSplitter.Piece first = new TitleSplitter.Piece();
        first.startPage = 1;
        first.endPage = 5;
        first.title = "起诉状";
        TitleSplitter.Piece second = new TitleSplitter.Piece();
        second.startPage = 11;
        second.endPage = 20;
        second.startPageNo = 11;
        second.endPageNo = 20;
        second.title = "判决书";
        List<TitleSplitter.Piece> pieces = new ArrayList<>();
        pieces.add(first);
        pieces.add(second);

        List<Integer> itemIndexes = new ArrayList<>();
        for (int i = 0; i < 20; i++) itemIndexes.add(i);

        BatchExtractionManager.appendUncoveredItemPages(pieces, itemIndexes, volume(20));

        Assert.assertEquals(pieces.size(), 2);
        Assert.assertEquals(first.endPage, 10, "第 6-10 页应并入前一件");
        Assert.assertEquals(second.startPage, 11);
    }
}
