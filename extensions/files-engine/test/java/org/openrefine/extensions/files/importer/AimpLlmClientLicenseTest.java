package org.openrefine.extensions.files.importer;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;

import com.jiongche.sdk.authz.AuthorizationException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * 授权门「进程内、不可绕过」：{@code block} 模式下直接 new {@link AimpLlmClient} 调用触网方法，
 * 也会在发请求前被拒绝（读不到狗 ⇒ 失败关闭）。探活方法不设门禁。
 */
public class AimpLlmClientLicenseTest {

    private static final String PROP_ENFORCE = "ruyi.license.enforce";
    private static final String PROP_BRIDGE = "ruyi.license.bridge";
    private static final String PROP_CACHE = "ruyi.license.cache-seconds";

    private String previousEnforce;
    private String previousBridge;
    private String previousCache;

    @BeforeMethod
    public void forceBlockWithMissingDog() {
        previousEnforce = System.getProperty(PROP_ENFORCE);
        previousBridge = System.getProperty(PROP_BRIDGE);
        previousCache = System.getProperty(PROP_CACHE);
        System.setProperty(PROP_ENFORCE, "block");
        System.setProperty(PROP_BRIDGE, new File("target", "no-such-bridge").getAbsolutePath());
        System.setProperty(PROP_CACHE, "0");
    }

    @AfterMethod
    public void restoreProperties() {
        restore(PROP_ENFORCE, previousEnforce);
        restore(PROP_BRIDGE, previousBridge);
        restore(PROP_CACHE, previousCache);
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    public void pageExtractionIsRejectedBeforeRequest() {
        AimpLlmClient client = new AimpLlmClient("http://127.0.0.1:7998");
        try {
            client.extractPage(new File("target", "no-such-page.jpg").getAbsolutePath(), "题名", null);
            fail("block 模式下直调 AimpLlmClient.extractPage 应被拒绝");
        } catch (AuthorizationException expected) {
            assertTrue(expected.getMessage().contains("加密狗"), expected.getMessage());
        }
    }

    @Test
    public void llmAnalyzeIsRejectedBeforeRequest() {
        AimpLlmClient client = new AimpLlmClient("http://127.0.0.1:7998");
        try {
            client.llmAnalyze("probe", "text");
            fail("block 模式下直调 AimpLlmClient.llmAnalyze 应被拒绝");
        } catch (AuthorizationException expected) {
            assertTrue(expected.getMessage().contains("加密狗"), expected.getMessage());
        }
    }

    @Test
    public void connectionProbeIsNotGated() {
        // 探活不设门禁：无狗时不应抛授权异常（只返回不可达）
        AimpLlmClient client = new AimpLlmClient("http://127.0.0.1:1");
        assertTrue(!client.testConnection());
    }
}
