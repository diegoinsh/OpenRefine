package com.google.refine.extension.quality.checker;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.testng.annotations.Test;

import com.google.refine.extension.quality.model.ReviewElementResult;

/**
 * 回归：裁判文书盖章的「宽松策略」判定。
 *
 * <p>全页只检测一次，再按落款区域过滤印章框中心：区域内达标 → 通过；
 * 区域不足但全页达标 → 仍通过并标记 sealOutsideRegion；两者都不足才报 seal_missing。
 * 区域过滤与印章/指纹类型判定是本策略的核心，必须与平台侧 documents 口径一致。
 */
public class DocumentSealRegionTest {

    private static final int W = 2000;
    private static final int H = 3000;

    private static Map<String, Object> sealRegion(double x, double y, double w, double h) {
        Map<String, Object> region = new HashMap<>();
        region.put("x", x);
        region.put("y", y);
        region.put("w", w);
        region.put("h", h);
        return region;
    }

    private static Map<String, Object> defaultSealRegion() {
        return sealRegion(0.5, 0.6, 0.5, 0.4);
    }

    private static ReviewElementResult.Region seal(int x1, int y1, int x2, int y2) {
        return new ReviewElementResult.Region("seal", x1, y1, x2, y2);
    }

    @Test
    public void detectsSealInsideBottomRegion() {
        // 中心 (0.65, 0.733) 落在 x>=0.5 / y>=0.6 的末页下部
        assertTrue(DocumentSealChecker.insideRegion(seal(1200, 2100, 1400, 2300), defaultSealRegion(), W, H));
    }

    @Test
    public void rejectsSealAtPageTop() {
        // 中心 (0.65, 0.133)：全页能测到，但不在落款区域
        assertFalse(DocumentSealChecker.insideRegion(seal(1200, 300, 1400, 500), defaultSealRegion(), W, H));
    }

    @Test
    public void rejectsSealOnLeftHalf() {
        // 中心 (0.25, 0.733)：纵向在区域内，横向不在
        assertFalse(DocumentSealChecker.insideRegion(seal(400, 2100, 600, 2300), defaultSealRegion(), W, H));
    }

    @Test
    public void regionEdgeIsInclusive() {
        // 中心恰为 (0.5, 0.6)
        assertTrue(DocumentSealChecker.insideRegion(seal(900, 1700, 1100, 1900), defaultSealRegion(), W, H));
    }

    @Test
    public void missingRegionMeansWholePage() {
        assertTrue(DocumentSealChecker.insideRegion(seal(100, 100, 200, 200), null, W, H));
    }

    @Test
    public void unknownImageSizeMeansWholePage() {
        assertTrue(DocumentSealChecker.insideRegion(seal(100, 100, 200, 200), defaultSealRegion(), 0, 0));
    }

    @Test
    public void regionWidthHeightDefaultToFullPage() {
        // 只给 x/y 时宽度高度缺省为 1.0，整页仍判在区域内
        Map<String, Object> partial = new HashMap<>();
        partial.put("x", 0.0);
        partial.put("y", 0.0);
        assertTrue(DocumentSealChecker.insideRegion(seal(1800, 2800, 1900, 2900), partial, W, H));
    }

    @Test
    public void treatsOnlySealRegionsAsSeals() {
        assertTrue(seal(0, 0, 10, 10).isSeal());
        assertTrue(new ReviewElementResult.Region(null, 0, 0, 10, 10).isSeal());
        assertFalse(new ReviewElementResult.Region("fingerprint", 0, 0, 10, 10).isSeal());
    }
}
