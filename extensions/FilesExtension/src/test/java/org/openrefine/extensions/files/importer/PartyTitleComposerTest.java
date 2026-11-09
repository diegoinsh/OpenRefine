package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 件题名「方别 + 当事人」规则渲染的回归测试。
 *
 * <p>非生成式引擎（Grounding）不把方别/当事人写进页题名，降级分件后由
 * {@link PartyTitleComposer} 按卷宗封面名单与页级责任者渲染件名；生成式引擎路径不调用，
 * 既有基线题名保持不变。
 */
public class PartyTitleComposerTest {

    private static final String ROLES_BOTH =
            "{\"原告\":[\"赵建明\"],\"被告\":[\"济南恒盛精密机械有限公司\"]}";

    private static TitleSplitter.Piece piece(int start, int end, String title) {
        TitleSplitter.Piece p = new TitleSplitter.Piece();
        p.startPage = start;
        p.endPage = end;
        p.title = title;
        return p;
    }

    private static List<String> pages(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    @Test
    public void pagePartyResolvesSideAndName() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(piece(1, 1, "借条")));
        PartyTitleComposer.compose(pieces, pages("赵建明"), ROLES_BOTH);
        TitleSplitter.Piece p = pieces.get(0);
        Assert.assertEquals(p.title, "借条");                       // 三级题名不被覆盖
        Assert.assertEquals(p.partySide, "原告");
        Assert.assertEquals(p.partyName, "赵建明");
        Assert.assertEquals(p.docType, "借条");
        Assert.assertEquals(p.level4Title, "原告赵建明借条");
        Assert.assertEquals(p.titleSource, "mixed");
        Assert.assertEquals(PartyTitleComposer.displayTitle(p), "原告赵建明借条");
    }

    @Test
    public void defendantEntityUsesFullName() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(piece(1, 1, "答辩状")));
        PartyTitleComposer.compose(pieces, pages("济南恒盛精密机械有限公司"), ROLES_BOTH);
        Assert.assertEquals(pieces.get(0).level4Title, "被告济南恒盛精密机械有限公司答辩状");
    }

    @Test
    public void courtMadeDocNotPrefixed() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(piece(1, 1, "庭审笔录")));
        PartyTitleComposer.compose(pieces, pages("赵建明"), ROLES_BOTH);
        Assert.assertEquals(pieces.get(0).level4Title, "庭审笔录");
        Assert.assertEquals(pieces.get(0).titleSource, "model");
    }

    @Test
    public void twoSidesWithoutPagePartyDoesNotGuess() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(piece(1, 1, "借条")));
        PartyTitleComposer.compose(pieces, pages(""), ROLES_BOTH);
        Assert.assertNull(pieces.get(0).partySide);
        Assert.assertEquals(pieces.get(0).level4Title, "借条");
    }

    @Test
    public void singleSideWithSeveralNamesOmitsName() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(piece(1, 1, "借条")));
        PartyTitleComposer.compose(pieces, pages(""),
                "{\"原告\":[\"赵建明\",\"王秀兰\"]}");
        Assert.assertEquals(pieces.get(0).partySide, "原告");
        Assert.assertEquals(pieces.get(0).partyName, "");
        Assert.assertEquals(pieces.get(0).level4Title, "原告借条");
    }

    @Test
    public void titleSideWithoutPageParty() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(
                piece(1, 1, "授权委托书（原告）")));
        PartyTitleComposer.compose(pieces, pages(""), ROLES_BOTH);
        Assert.assertEquals(pieces.get(0).partySide, "原告");
        Assert.assertEquals(pieces.get(0).docType, "授权委托书");
        Assert.assertEquals(pieces.get(0).level4Title, "原告赵建明授权委托书");
    }

    @Test
    public void idempotentOnComposedTitle() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(
                piece(1, 1, "原告赵建明借条")));
        PartyTitleComposer.compose(pieces, pages("赵建明"), ROLES_BOTH);
        Assert.assertEquals(pieces.get(0).docType, "借条");
        Assert.assertEquals(pieces.get(0).level4Title, "原告赵建明借条");
    }

    @Test
    public void missingRolesLeavesTitlesUntouched() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(Collections.singletonList(piece(1, 1, "借条")));
        PartyTitleComposer.compose(pieces, pages("赵建明"), null);
        Assert.assertEquals(pieces.get(0).level4Title, "");
        Assert.assertEquals(PartyTitleComposer.displayTitle(pieces.get(0)), "借条");
    }
}
