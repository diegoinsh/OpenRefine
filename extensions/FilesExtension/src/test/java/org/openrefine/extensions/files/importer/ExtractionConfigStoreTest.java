package org.openrefine.extensions.files.importer;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 抽取配置里布尔项的解析：前端复选框写入原生布尔，手改配置文件则可能是字符串。
 * 两种来源都要能正确判定，非法值一律退回 fallback，避免配置写错时静默反转行为。
 *
 * 这里只测纯函数（不走 workspace 文件读写），配置项的落盘与合并由 load() 负责。
 */
public class ExtractionConfigStoreTest {

    @Test
    public void readsNativeBooleans() {
        Assert.assertTrue(ExtractionConfigStore.parseBoolean(Boolean.TRUE, false));
        Assert.assertFalse(ExtractionConfigStore.parseBoolean(Boolean.FALSE, true));
    }

    @Test
    public void readsTruthyStrings() {
        for (String truthy : new String[] { "true", "TRUE", " true ", "1", "yes", "YES", "on", "On" }) {
            Assert.assertTrue(ExtractionConfigStore.parseBoolean(truthy, false),
                    "应判为真：" + truthy);
        }
    }

    @Test
    public void readsFalsyStrings() {
        for (String falsy : new String[] { "false", "FALSE", " false ", "0", "no", "off", "OFF" }) {
            Assert.assertFalse(ExtractionConfigStore.parseBoolean(falsy, true),
                    "应判为假：" + falsy);
        }
    }

    @Test
    public void fallsBackOnMissingOrUnparsable() {
        Assert.assertTrue(ExtractionConfigStore.parseBoolean(null, true));
        Assert.assertFalse(ExtractionConfigStore.parseBoolean(null, false));
        Assert.assertTrue(ExtractionConfigStore.parseBoolean("", true));
        Assert.assertFalse(ExtractionConfigStore.parseBoolean("", false));
        // 非法值（如手误写成 "maybe"）不能猜，直接退回 fallback
        Assert.assertFalse(ExtractionConfigStore.parseBoolean("maybe", false));
        Assert.assertTrue(ExtractionConfigStore.parseBoolean("maybe", true));
    }

    @Test
    public void documentPagesIndependentDefaultsOn() {
        // 默认打开：文书档案与诉讼档案一样按页并发、且不传页间上下文
        // （2026-10-15 A/B：18 页并发 33s vs 串行 70s；关掉即退回串行+上下文，慢约一倍）
        Assert.assertTrue((Boolean) ExtractionConfigStore.defaults()
                .get("extraction.documentPagesIndependent"));
    }
}
