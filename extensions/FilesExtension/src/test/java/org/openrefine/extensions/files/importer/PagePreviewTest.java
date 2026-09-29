package org.openrefine.extensions.files.importer;

import java.util.Collections;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 页级预览缓冲的行为：只保留最近若干页、按页号有序、进入新卷时清空。
 *
 * 件级行要等整卷抽完并分件后才写入，长卷（生产线上 2-300 页很常见）期间界面会长时间空白，
 * 页级预览就是这段时间里唯一能看到的内容，故其容量与清理行为需要有测试兜住。
 */
public class PagePreviewTest {

    private static BatchExtractionManager.Task newTask() {
        return new BatchExtractionManager.Task(
                1L, "C:/tmp/root", ExtractionTemplate.BATCH_TITLE_VOLUME,
                Collections.emptyList(), null, null, null, Collections.emptyList(), false);
    }

    @Test
    public void keepsOnlyTheMostRecentPagePreviews() {
        BatchExtractionManager.Task task = newTask();
        for (int page = 1; page <= 50; page++) {
            task.recordPagePreview(page, "p" + page + ".jpg",
                    Collections.singletonMap("title", "题名" + page));
        }
        // 容量上限 40：最旧的 10 页被丢弃，保留的仍是最近 40 页且按页号有序
        Assert.assertEquals(40, task.recentPages.size());
        Assert.assertEquals(Integer.valueOf(11), task.recentPages.firstKey());
        Assert.assertEquals(Integer.valueOf(50), task.recentPages.lastKey());
        Assert.assertEquals("题名50", task.recentPages.get(50).values.get("title"));
    }

    @Test
    public void clearsPreviewsWhenEnteringANewVolume() {
        BatchExtractionManager.Task task = newTask();
        task.recordPagePreview(1, "a.jpg", Collections.singletonMap("title", "卷宗封面"));
        task.recordPagePreview(2, "b.jpg",
                Collections.singletonMap("title", "民事判决书"));

        task.clearPagePreviews();

        // 页号只在卷内唯一，跨卷累积会把不同卷的页混在一起，故换卷必须清空
        Assert.assertTrue(task.recentPages.isEmpty());
    }

    @Test
    public void keepsPagesSortedByPageNumberRegardlessOfCompletionOrder() {
        BatchExtractionManager.Task task = newTask();
        // 并发抽取时回收顺序可能与页序不同，预览展示仍需按页号有序
        task.recordPagePreview(3, "c.jpg", Collections.singletonMap("title", "第三页"));
        task.recordPagePreview(1, "a.jpg", Collections.singletonMap("title", "第一页"));
        task.recordPagePreview(2, "b.jpg", Collections.singletonMap("title", "第二页"));

        Assert.assertEquals("[1, 2, 3]", task.recentPages.keySet().toString());
    }

    @Test
    public void averagesPageTimingOverRecentBatchesSkippingFirst() {
        BatchExtractionManager.Task task = newTask();
        // 首批含模型预热/首次推理，明显偏离稳定速度，不计入平均
        task.recordPageTiming(30000, 4);
        Assert.assertEquals(0, task.avgPageMillis());
        // 后续批按「批耗时 ÷ 批页数」计入：4000/4=1000、8000/4=2000，平均 1500
        task.recordPageTiming(4000, 4);
        task.recordPageTiming(8000, 4);
        Assert.assertEquals(1500, task.avgPageMillis());
    }

    @Test
    public void keepsOnlyTheMostRecentTimingBatches() {
        BatchExtractionManager.Task task = newTask();
        task.recordPageTiming(1, 1);                  // 首批：跳过
        for (int i = 1; i <= 10; i++) {
            task.recordPageTiming(1000L * i, 1);      // 批耗时 1000..10000
        }
        // 窗口 8：只保留最近 8 批（3000..10000），平均 = 52000 / 8 = 6500
        Assert.assertEquals(6500, task.avgPageMillis());
    }

    @Test
    public void resetsTimingWindowPerVolume() {
        BatchExtractionManager.Task task = newTask();
        task.recordPageTiming(5000, 4);               // 首批跳过
        task.recordPageTiming(4000, 4);
        task.recordPageTiming(8000, 4);
        Assert.assertEquals(1500, task.avgPageMillis());

        task.resetPageTiming();

        // 换卷后历史归零，新卷首批同样跳过（含新一轮启动开销）
        Assert.assertEquals(0, task.avgPageMillis());
        task.recordPageTiming(60000, 4);
        Assert.assertEquals(0, task.avgPageMillis());
        task.recordPageTiming(4000, 4);
        Assert.assertEquals(1000, task.avgPageMillis());
    }
}
