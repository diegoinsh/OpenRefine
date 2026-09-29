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
}
