package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 分件层「双证据件首门限」与「件级取值权威」的回归测试。
 *
 * <p>双证据 = 题名相似度不同（相似度 &lt; 阈值）+ 题名置信度达标 + 本页存在版面标题块。
 * 证据不足时归入当前件（视作续页），用于拦住模型在正文续页上编造的幻觉题名被误切成新件。
 */
public class TitleSplitterDualEvidenceTest {

    private static Map<String, Double> conf(double titleConf) {
        Map<String, Double> m = new HashMap<>();
        m.put(TitleSplitter.TITLE_KEY, titleConf);
        return m;
    }

    private static String ranges(List<TitleSplitter.Piece> pieces) {
        StringBuilder sb = new StringBuilder();
        for (TitleSplitter.Piece p : pieces) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.startPage);
            if (p.endPage != p.startPage) sb.append('-').append(p.endPage);
        }
        return sb.toString();
    }

    /** 幻觉题名（正文句）：置信度 0.85 < 0.9 且无版面标题块 → 并回当前件 */
    @Test
    public void hallucinatedTitleIsMergedNotSplit() {
        List<String> titles = Arrays.asList(
                "关于规范乡镇街道名称的通知",
                "关指标和直达资金标识，导入直达资金监控系统，确保数据真实",
                "关于做好安全工作的通知");
        List<Map<String, Double>> confs = Arrays.asList(conf(0.95), conf(0.85), conf(0.95));
        List<Integer> regions = Arrays.asList(1, 0, 1);

        List<TitleSplitter.Piece> pieces = TitleSplitter.split(titles, 0.9, false,
                confs, regions, TitleSplitter.DEFAULT_TITLE_CONFIDENCE_THRESHOLD);

        Assert.assertEquals(ranges(pieces), "1-2,3", "幻觉题名的正文续页应并回当前件");
        Assert.assertEquals(pieces.get(0).title, "关于规范乡镇街道名称的通知");
    }

    /** 高置信度题名（0.95）但本页无版面标题块 → 证据不足，仍并回当前件 */
    @Test
    public void highConfidenceWithoutLayoutTitleIsMerged() {
        List<String> titles = Arrays.asList("关于甲的通知", "关于乙的报告");
        List<Map<String, Double>> confs = Arrays.asList(conf(0.95), conf(0.95));
        List<Integer> regions = Arrays.asList(1, 0);

        List<TitleSplitter.Piece> pieces = TitleSplitter.split(titles, 0.9, false,
                confs, regions, TitleSplitter.DEFAULT_TITLE_CONFIDENCE_THRESHOLD);

        Assert.assertEquals(ranges(pieces), "1-2", "缺版面标题块时不应另起新件");
    }

    /** 高置信度题名（0.95）+ 版面标题块 → 双重证据成立，另起新件 */
    @Test
    public void highConfidenceWithLayoutTitleSplits() {
        List<String> titles = Arrays.asList("关于甲的通知", "关于乙的报告");
        List<Map<String, Double>> confs = Arrays.asList(conf(0.95), conf(0.95));
        List<Integer> regions = Arrays.asList(1, 1);

        List<TitleSplitter.Piece> pieces = TitleSplitter.split(titles, 0.9, false,
                confs, regions, TitleSplitter.DEFAULT_TITLE_CONFIDENCE_THRESHOLD);

        Assert.assertEquals(ranges(pieces), "1,2");
    }

    /** 未传证据（旧三参重载）时行为不变：题名不同即另起新件 */
    @Test
    public void legacyOverloadKeepsOldBehavior() {
        List<String> titles = Arrays.asList("关于甲的通知", "关于乙的报告");
        List<TitleSplitter.Piece> pieces =
                TitleSplitter.split(titles, 0.9, false);

        Assert.assertEquals(ranges(pieces), "1,2");
    }

    /** 件级取值权威：件首页非空取值覆盖跨页择优结果 */
    @Test
    public void pieceStartAuthorityOverridesMergedValue() {
        Map<String, String> rowValues = new LinkedHashMap<>();
        rowValues.put("title", "串入的旧题名");
        rowValues.put("document_number", "");
        rowValues.put("date", "20240101");

        Map<String, String> startPage = new LinkedHashMap<>();
        startPage.put("title", "件首页真题名");
        startPage.put("document_number", "通府办发〔2018〕39号");

        BatchExtractionManager.applyPieceStartAuthority(rowValues, startPage);

        Assert.assertEquals(rowValues.get("title"), "件首页真题名");
        Assert.assertEquals(rowValues.get("document_number"), "通府办发〔2018〕39号");
        Assert.assertEquals(rowValues.get("date"), "20240101", "非身份属性要素不受影响");
    }

    /** 件级取值权威：件首页空值不覆盖（缺失仍由跨页择优按序补） */
    @Test
    public void pieceStartAuthorityKeepsMergedWhenStartPageEmpty() {
        Map<String, String> rowValues = new LinkedHashMap<>();
        rowValues.put("responsible_party", "择优得到的责任者");

        Map<String, String> startPage = new LinkedHashMap<>();
        startPage.put("responsible_party", "   ");

        BatchExtractionManager.applyPieceStartAuthority(rowValues, startPage);

        Assert.assertEquals(rowValues.get("responsible_party"), "择优得到的责任者");
    }

    /** 件首页确有取值而行表为空时，应新建行表写入 */
    @Test
    public void pieceStartAuthorityCreatesRowWhenNull() {
        Map<String, String> startPage = new LinkedHashMap<>();
        startPage.put("title", "件首页真题名");

        Map<String, String> row = BatchExtractionManager.applyPieceStartAuthority(null, startPage);

        Assert.assertNotNull(row);
        Assert.assertEquals(row.get("title"), "件首页真题名");
    }

    /** 证据列表短于页数（尾页缺证据）时按证据不足处理，归并而非误切 */
    @Test
    public void missingEvidenceAtTailMerges() {
        List<String> titles = Arrays.asList("关于甲的通知", "关于乙的报告");
        List<Map<String, Double>> confs = new ArrayList<>(Arrays.asList(conf(0.95)));
        List<Integer> regions = new ArrayList<>(Arrays.asList(1));

        List<TitleSplitter.Piece> pieces = TitleSplitter.split(titles, 0.9, false,
                confs, regions, TitleSplitter.DEFAULT_TITLE_CONFIDENCE_THRESHOLD);

        Assert.assertEquals(ranges(pieces), "1-2");
    }
}
