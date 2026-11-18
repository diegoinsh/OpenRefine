package org.openrefine.extensions.files.importer;

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
}
