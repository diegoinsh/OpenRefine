package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Grounding 降级分件后的件题名规范化回归测试。
 *
 * <p>grounding 只回原文标题，纸面简写（「民事诉状」「传票」「庭审判笔录」）与
 * 编目规范名不一致，继续沿用会让四级件名与甲方《案件编目规则》对不上。</p>
 */
public class CourtTitleCanonTest {

    private static TitleSplitter.Piece piece(String title, int start, int end) {
        TitleSplitter.Piece p = new TitleSplitter.Piece();
        p.title = title;
        p.startPage = start;
        p.endPage = end;
        return p;
    }

    /** 纸面简写按编目词表改写成规范名 */
    @Test
    public void aliasesAreCanonicalized() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>();
        pieces.add(piece("民事诉状", 1, 4));
        pieces.add(piece("传票", 31, 31));
        pieces.add(piece("庭审判笔录", 32, 33));
        pieces.add(piece("调查笔录", 29, 29));
        pieces.add(piece("调解笔录", 30, 30));
        pieces.add(piece("地址确认书", 9, 10));

        BatchExtractionManager.canonicalizePieceTitles(pieces, "一审");

        Assert.assertEquals(pieces.get(0).title, "民事起诉状");
        Assert.assertEquals(pieces.get(1).title, "开庭传票");
        Assert.assertEquals(pieces.get(2).title, "庭审笔录");
        Assert.assertEquals(pieces.get(3).title, "询问、调查取证材料");
        Assert.assertEquals(pieces.get(4).title, "调解笔录及调解材料");
        Assert.assertEquals(pieces.get(5).title, "送达地址确认书");
    }

    /** 非税收入一般缴款书的条项名随审级改写 */
    @Test
    public void receiptTitleDependsOnTrialLevel() {
        List<TitleSplitter.Piece> first = new ArrayList<>();
        first.add(piece("非税收入一般缴款书", 6, 6));
        BatchExtractionManager.canonicalizePieceTitles(first, "一审");
        Assert.assertEquals(first.get(0).title, "一审案件受理费缴费凭证");

        List<TitleSplitter.Piece> second = new ArrayList<>();
        second.add(piece("非税收入一般缴款书", 6, 6));
        BatchExtractionManager.canonicalizePieceTitles(second, "二审");
        Assert.assertEquals(second.get(0).title, "上诉案件受理费缴费凭证");
    }

    /** 只改题名，不动件边界；未命中映射的题名原样保留 */
    @Test
    public void boundariesAndUnknownTitlesAreUntouched() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>();
        pieces.add(piece("借条", 22, 23));
        pieces.add(piece("民事调解书", 34, 35));

        BatchExtractionManager.canonicalizePieceTitles(pieces, "一审");

        Assert.assertEquals(pieces.get(0).title, "借条");
        Assert.assertEquals(pieces.get(0).startPage, 22);
        Assert.assertEquals(pieces.get(0).endPage, 23);
        Assert.assertEquals(pieces.get(1).title, "民事调解书");
        Assert.assertEquals(pieces.get(1).startPage, 34);
        Assert.assertEquals(pieces.get(1).endPage, 35);
    }
}
