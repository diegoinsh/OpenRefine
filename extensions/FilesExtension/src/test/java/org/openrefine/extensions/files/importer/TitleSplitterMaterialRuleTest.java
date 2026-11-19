package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 四级口径规则分件（(文种, 当事人, 对象) 三元组 + 件首门控 + 材料级合并）的回归测试。
 *
 * <p>对象是法院文书切件的第三元：传票、送达回证、执行通知书、询问/讯问笔录的责任者都是法院，
 * 只有相对人能区分同文种的多份材料——缺对象时刑事卷会大幅并件（实测 70 件并成 39 件）。
 */
public class TitleSplitterMaterialRuleTest {

    private static String ranges(List<TitleSplitter.Piece> pieces) {
        StringBuilder sb = new StringBuilder();
        for (TitleSplitter.Piece p : pieces) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.startPage);
            if (p.endPage != p.startPage) sb.append('-').append(p.endPage);
        }
        return sb.toString();
    }

    /** 同文种（开庭传票）但对象不同 → 三件 */
    @Test
    public void sameBreedDifferentObjectSplits() {
        List<String> titles = Arrays.asList("开庭传票", "开庭传票", "开庭传票");
        List<String> parties = Arrays.asList("", "", "");
        List<String> objects = Arrays.asList("王梓豪", "刘美琪", "张宇航");

        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(titles, parties, objects);

        Assert.assertEquals(ranges(pieces), "1,2,3", "对象不同的同文种材料必须各自成件");
    }

    /** 同文种但对象都没抽到 → 无法区分，合并为一件（退化为两元口径） */
    @Test
    public void sameBreedWithoutObjectMerges() {
        List<String> titles = Arrays.asList("开庭传票", "开庭传票", "开庭传票");
        List<String> parties = Arrays.asList("", "", "");
        List<String> objects = Arrays.asList("", "", "");

        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(titles, parties, objects);

        Assert.assertEquals(ranges(pieces), "1-3", "对象缺失时不得凭空切件");
    }

    /** 对象相同、文种相同 → 同一份多页文书，一件 */
    @Test
    public void sameBreedSameObjectMerges() {
        List<String> titles = Arrays.asList("刑事判决书", "刑事判决书", "刑事判决书", "刑事判决书");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, Arrays.asList("", "", "", ""), Arrays.asList("", "", "", ""));

        Assert.assertEquals(ranges(pieces), "1-4", "续页应并入同一件");
    }

    /** 题名不同 → 各自成件 */
    @Test
    public void differentBreedSplits() {
        List<String> titles = Arrays.asList("起诉书", "逮捕证");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(titles, null, null);

        Assert.assertEquals(ranges(pieces), "1,2");
    }

    /** 续页被页级误报题名（置信度 0.5 < 0.6、无版面标题块）→ 并回当前件，不误切 */
    @Test
    public void lowConfidenceTitleIsContinuation() {
        List<String> titles = Arrays.asList("刑事判决书", "如不服本判决", "宣判笔录");
        Map<String, Double> high = new HashMap<>();
        high.put(TitleSplitter.TITLE_KEY, 0.95);
        Map<String, Double> low = new HashMap<>();
        low.put(TitleSplitter.TITLE_KEY, 0.5);
        List<Map<String, Double>> confs = Arrays.asList(high, low, high);
        List<Integer> regions = Arrays.asList(1, 0, 1);

        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, null, null, confs, regions,
                TitleSplitter.MATERIAL_TITLE_CONFIDENCE_THRESHOLD);

        Assert.assertEquals(ranges(pieces), "1-2,3", "证据不足的页应视作续页");
    }

    /** 诉状与其附件属同一件（编目规则「××状及附件」） */
    @Test
    public void pleadingAttachmentMerges() {
        List<String> titles = Arrays.asList("民事起诉状", "附件");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(titles, null, null);

        Assert.assertEquals(ranges(pieces), "1-2", "诉状所附附件不另起一件");
    }

    /** 送达回证与其后的 EMS 底单属同一件 */
    @Test
    public void deliveryReceiptWithMailSlipMerges() {
        List<String> titles = Arrays.asList("送达回证", "EMS邮件查询单");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(titles, null, null);

        Assert.assertEquals(ranges(pieces), "1-2", "卷末送达回证与邮件底单为一件");
    }

    /** 无对象时仍按当事人区分（授权委托书按委托方分别成件） */
    @Test
    public void differentPartySplits() {
        List<String> titles = Arrays.asList("授权委托书", "授权委托书");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, Arrays.asList("原告", "被告"), Collections.emptyList());

        Assert.assertEquals(ranges(pieces), "1,2", "不同委托方的授权委托书各自成件");
    }

    /** 同一法院制作、被询问人不同的多份笔录必须各成一件（对象是硬边界） */
    @Test
    public void bodyPagesWithDifferentObjectSplit() {
        List<String> titles = Arrays.asList("调查笔录", "调查笔录", "调查笔录");
        List<String> objects = Arrays.asList("汪静怡", "陶欣然", "汪静怡");

        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, Arrays.asList("", "", ""), objects);

        Assert.assertEquals(ranges(pieces), "1,2,3", "不同被询问人的笔录不得并件");
    }

    /** 同一份笔录的多页：对象相同或缺失时并成一件 */
    @Test
    public void bodyPagesWithSameObjectMerge() {
        List<String> titles = Arrays.asList("调查笔录", "调查笔录", "调查笔录");
        List<String> objects = Arrays.asList("汪静怡", "", "汪静怡");

        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, Arrays.asList("", "", ""), objects);

        Assert.assertEquals(ranges(pieces), "1-3");
    }

    /** 正文类只对同一题名生效：判决书后跟调解书仍各自成件 */
    @Test
    public void bodyPagesDoNotMergeAcrossBreed() {
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                Arrays.asList("民事判决书", "民事调解书"), null, null);

        Assert.assertEquals(ranges(pieces), "1,2");
    }

    /** 卷级模型逐页切开的笔录，后处理应并回一件，并去掉「（续）」后缀 */
    @Test
    public void mergeBodyPiecesCollapsesModelOverSplit() {
        List<TitleSplitter.Piece> pieces = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            TitleSplitter.Piece p = new TitleSplitter.Piece();
            p.startPage = i;
            p.endPage = i;
            p.title = i == 8 ? "宣判笔录（续）" : "宣判笔录";
            pieces.add(p);
        }
        List<String> parties = Collections.nCopies(8, "上海市徐汇区人民法院");

        List<TitleSplitter.Piece> merged = TitleSplitter.mergeBodyPieces(
                pieces, parties, Collections.nCopies(8, ""));

        Assert.assertEquals(merged.size(), 1);
        Assert.assertEquals(merged.get(0).startPage, 1);
        Assert.assertEquals(merged.get(0).endPage, 8);
        Assert.assertEquals(merged.get(0).title, "宣判笔录", "合并后不应保留「（续）」");
    }

    /** 不同当事人的同题名正文不得被后处理并件 */
    @Test
    public void mergeBodyPiecesRespectsParty() {
        TitleSplitter.Piece a = new TitleSplitter.Piece();
        a.startPage = 1;
        a.endPage = 1;
        a.title = "询问笔录";
        TitleSplitter.Piece b = new TitleSplitter.Piece();
        b.startPage = 2;
        b.endPage = 2;
        b.title = "询问笔录";

        List<TitleSplitter.Piece> merged = TitleSplitter.mergeBodyPieces(
                Arrays.asList(a, b), Arrays.asList("张三", "李四"), null);

        Assert.assertEquals(merged.size(), 2, "不同被询问人的笔录不得并件");
    }

    /** 对象不同时，模型结果后处理同样不得并件 */
    @Test
    public void mergeBodyPiecesRespectsObject() {
        TitleSplitter.Piece a = new TitleSplitter.Piece();
        a.startPage = 1;
        a.endPage = 1;
        a.title = "讯问笔录";
        TitleSplitter.Piece b = new TitleSplitter.Piece();
        b.startPage = 2;
        b.endPage = 2;
        b.title = "讯问笔录";

        List<TitleSplitter.Piece> merged = TitleSplitter.mergeBodyPieces(
                Arrays.asList(a, b), null, Arrays.asList("张三", "李四"));

        Assert.assertEquals(merged.size(), 2);
    }

    /** 「××（续）」是前件的续页，不独立成件（前件带「（截图）」等修饰也能识别） */
    @Test
    public void continuedPieceMerges() {
        List<String> pieces = Arrays.asList("催收短信记录（截图）", "催收短信记录（续）");
        List<TitleSplitter.Piece> result = TitleSplitter.splitByMaterial(
                pieces, Arrays.asList("", ""), Arrays.asList("", ""));

        Assert.assertEquals(ranges(result), "1-2", "「（续）」应并入前一件");
    }

    /** 连续同题名单据：对象被抽成标题本身（借条页 object=借条）不构成切件依据 */
    @Test
    public void objectEchoingTitleDoesNotSplit() {
        List<String> titles = Arrays.asList("借条", "借条", "借条");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, Arrays.asList("", "", ""), Arrays.asList("借条", "借条", ""));

        Assert.assertEquals(ranges(pieces), "1-3", "对象与题名同化时应并成一件");
    }

    /** 两侧对象都是真实相对人且不同时，仍按三元组切开（传票/笔录口径不能丢） */
    @Test
    public void differentRealObjectSplits() {
        List<String> titles = Arrays.asList("借条", "借条");
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                titles, Arrays.asList("", ""), Arrays.asList("赵建明", "王秀兰"));

        Assert.assertEquals(ranges(pieces), "1,2");
    }

    /** 单据类只在同题名内合并：借条后跟收条仍各自成件 */
    @Test
    public void receiptDifferentBreedSplits() {
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                Arrays.asList("借条", "收条"), null, null);

        Assert.assertEquals(ranges(pieces), "1,2");
    }

    /** 页码/页眉角标类纯数字题名不是文种，按续页并入前件 */
    @Test
    public void numericCornerMarkIsContinuation() {
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                Arrays.asList("起诉书", "3"), null, null);

        Assert.assertEquals(ranges(pieces), "1-2", "纯数字角标不得独立成件");
    }

    /** 题名**尾部**括号补注统一去掉（全/半角都算） */
    @Test
    public void cleanTitleDropsTrailingBracket() {
        Assert.assertEquals(TitleSplitter.cleanTitle("催收短信记录（截图）"), "催收短信记录");
        Assert.assertEquals(TitleSplitter.cleanTitle("微信聊天记录(1)"), "微信聊天记录");
        Assert.assertEquals(TitleSplitter.cleanTitle("请假单（补办）"), "请假单");
        Assert.assertEquals(TitleSplitter.cleanTitle("移送函（存根）"), "移送函");
    }

    /** 中间的括号是文种的一部分，不得去掉 */
    @Test
    public void cleanTitleKeepsMiddleBracket() {
        Assert.assertEquals(TitleSplitter.cleanTitle("证据核对（质证）记录"),
                "证据核对（质证）记录");
        Assert.assertEquals(TitleSplitter.cleanTitle("证据材料（一）银行流水"),
                "证据材料（一）银行流水");
        Assert.assertEquals(TitleSplitter.cleanTitle("聊天记录（二）派单与提成"),
                "聊天记录（二）派单与提成");
    }

    /** 多重尾部括号一并去掉；整条题名只有括号时保持原样 */
    @Test
    public void cleanTitleDropsMultipleTrailingBrackets() {
        Assert.assertEquals(TitleSplitter.cleanTitle("对账单（1）（副本）"), "对账单");
        Assert.assertEquals(TitleSplitter.cleanTitle("（附件）"), "（附件）");
    }

    /** 纯数字题名清洗后为空 */
    @Test
    public void cleanTitleDropsNumericNoise() {
        Assert.assertEquals(TitleSplitter.cleanTitle("3"), "");
        Assert.assertEquals(TitleSplitter.cleanTitle("（3）"), "");
        Assert.assertTrue(TitleSplitter.isNoiseTitle("12"));
        Assert.assertFalse(TitleSplitter.isNoiseTitle("起诉书"));
    }

    /** 带区分信息的尾部括号（第22次/第28次）不得并件：去括号只影响输出题名 */
    @Test
    public void trailingBracketWithDistinctNotesSplits() {
        List<TitleSplitter.Piece> pieces = TitleSplitter.splitByMaterial(
                Arrays.asList("监理例会纪要（第22次）", "监理例会纪要（第28次）"),
                Arrays.asList("原告", "原告"), Arrays.asList("", ""));

        Assert.assertEquals(ranges(pieces), "1,2", "两份不同场次的纪要保持独立成件");
    }

    /** 模型把连续借条逐页切成两件时，后处理按三元组并回一件（不限于正文类） */
    @Test
    public void mergeBodyPiecesCollapsesNonBodySameTitle() {
        TitleSplitter.Piece a = new TitleSplitter.Piece();
        a.startPage = 1;
        a.endPage = 1;
        a.title = "借条";
        TitleSplitter.Piece b = new TitleSplitter.Piece();
        b.startPage = 2;
        b.endPage = 3;
        b.title = "借条";

        List<TitleSplitter.Piece> merged = TitleSplitter.mergeBodyPieces(
                Arrays.asList(a, b), Arrays.asList("", ""), Arrays.asList("借条", "借条"));

        Assert.assertEquals(merged.size(), 1);
        Assert.assertEquals(merged.get(0).endPage, 3);
    }

    /** 模型结果里带「（续）」的非正文类件，后处理也应并回前件 */
    @Test
    public void mergeBodyPiecesCollapsesContinuedPiece() {
        TitleSplitter.Piece a = new TitleSplitter.Piece();
        a.startPage = 1;
        a.endPage = 1;
        a.title = "微信聊天记录（截图）";
        TitleSplitter.Piece b = new TitleSplitter.Piece();
        b.startPage = 2;
        b.endPage = 2;
        b.title = "微信聊天记录（续）";

        List<TitleSplitter.Piece> merged = TitleSplitter.mergeBodyPieces(
                Arrays.asList(a, b), null, null);

        Assert.assertEquals(merged.size(), 1);
        Assert.assertEquals(merged.get(0).startPage, 1);
        Assert.assertEquals(merged.get(0).endPage, 2);
        Assert.assertEquals(merged.get(0).title, "微信聊天记录（截图）",
                "合并后应保留前件题名");
    }
}
