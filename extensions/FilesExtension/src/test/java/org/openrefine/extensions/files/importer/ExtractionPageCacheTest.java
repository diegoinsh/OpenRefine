package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 页级缓存 JSON 的往返：命中缓存时必须把识别结果原样带回。
 *
 * 这里只测纯函数（{@link ExtractionPageCache#toJson} / {@link ExtractionPageCache#fromJson} /
 * {@link ExtractionPageCache#keyFor}），不走 workspace 文件读写——那两个由 load/save 负责。
 *
 * 回归背景：缓存曾漏写 titleRegionCount（分件双证据门限里「本页有版面标题块」这一条），
 * 命中缓存时该值恒为 0，整卷认不出件首、塌成一件——实测同一批 18 页文书档案
 * `disableCache=false` 得 3 件、`disableCache=true` 得 7 件。
 */
public class ExtractionPageCacheTest {

    @Test
    public void roundTripKeepsAllFields() {
        AimpLlmClient.ExtractPageResult src = new AimpLlmClient.ExtractPageResult();
        src.success = true;
        src.skipped = false;
        src.pageCount = 1;
        src.pageType = "header_page";
        src.titleRegionCount = 2;
        src.textLength = 321;
        src.evidenceHits = new ArrayList<>(List.of("responsible_party"));
        src.values = new LinkedHashMap<>();
        src.values.put("title", "关于下达补助资金的通知");
        src.confidences = new LinkedHashMap<>();
        src.confidences.put("title", 0.95);
        src.candidates = new LinkedHashMap<>();
        src.candidates.put("title", new ArrayList<>(List.of(
                new AimpLlmClient.ElementCandidate("关于下达补助资金的通知", 0.95, 1, "0016.jpg"))));

        ObjectNode node = ExtractionPageCache.toJson(src);
        AimpLlmClient.ExtractPageResult back = ExtractionPageCache.fromJson(node);

        // 关键断言：版面标题块数量必须随缓存带回，否则分件双证据门限失效
        Assert.assertEquals(back.titleRegionCount, 2, "titleRegionCount 必须能往返，否则分件失效");
        Assert.assertEquals(back.pageType, "header_page");
        Assert.assertEquals(back.pageCount, 1);
        Assert.assertEquals(back.skipped, false);
        Assert.assertEquals(back.textLength, Integer.valueOf(321));
        Assert.assertEquals(back.values.get("title"), "关于下达补助资金的通知");
        Assert.assertEquals(back.confidences.get("title"), Double.valueOf(0.95));
        Assert.assertEquals(back.evidenceHits, List.of("responsible_party"));
        Assert.assertEquals(back.candidates.get("title").size(), 1);
        Assert.assertEquals(back.candidates.get("title").get(0).page, 1);
    }

    @Test
    public void missingTitleRegionCountFallsBackToZero() {
        // 旧格式缓存（无该字段）读出来是 0——正因如此，键里带 CACHE_FORMAT_VERSION，
        // 格式升级后旧文件全部 miss、不会被当作有效命中
        AimpLlmClient.ExtractPageResult src = new AimpLlmClient.ExtractPageResult();
        src.success = true;
        ObjectNode node = ExtractionPageCache.toJson(src);
        node.remove("titleRegionCount");
        Assert.assertEquals(ExtractionPageCache.fromJson(node).titleRegionCount, 0);
    }

    @Test
    public void cacheKeyIsStableAndContextSensitive() {
        String a = ExtractionPageCache.keyFor("/tmp/x.jpg", "title", "{}", "文书档案", "", null);
        String b = ExtractionPageCache.keyFor("/tmp/x.jpg", "title", "{}", "文书档案", "", null);
        Assert.assertEquals(a, b, "同页同参数同上下文：键必须可复现");
        // 上下文不同则键不同：串行路径靠它避免复用在别的上下文里算出的结果
        String c = ExtractionPageCache.keyFor("/tmp/x.jpg", "title", "{}", "文书档案", "", "前页要素");
        Assert.assertNotEquals(a, c, "页间上下文变化必须导致 miss");
    }
}
