package com.google.refine.extension.quality.checker;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;

import com.jiongche.sdk.authz.AuthorizationException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.refine.extension.quality.aimp.AimpClient;

/**
 * 授权门「进程内、不可绕过」的入口测试：{@code block} 模式下即使**直接 new 引擎类**，
 * 也会在真正调用 AIMP 前的第一层被拒绝（读不到狗 ⇒ 失败关闭）。
 */
public class LicenseGateEntryTest {

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
    public void directAimpClientCallIsRejected() {
        AimpClient client = new AimpClient("http://127.0.0.1:7998");
        try {
            client.llmAnalyze("probe", null, "text");
            fail("block 模式下直调 AimpClient 应被拒绝");
        } catch (AuthorizationException expected) {
            assertTrue(expected.getMessage().contains("加密狗"), expected.getMessage());
        }
    }

    @Test
    public void directImageCheckerCallIsRejected() {
        ImageQualityChecker checker = new ImageQualityChecker(null, null, null);
        try {
            checker.checkImage(new File("target", "no-such-image.jpg"), null);
            fail("block 模式下直调 ImageQualityChecker 应被拒绝");
        } catch (AuthorizationException expected) {
            assertTrue(expected.getMessage().contains("加密狗"), expected.getMessage());
        }
    }
}
