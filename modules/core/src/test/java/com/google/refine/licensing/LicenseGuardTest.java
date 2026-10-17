/*

Copyright 2024, Open Refine.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Open Refine nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine.licensing;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.jiongche.sdk.authz.LicenseGate;
import org.testng.annotations.Test;

/**
 * OR 侧模块授权守卫的单元测试：三态开关、命令映射、失败关闭与消息资源。
 *
 * <p>不接触真狗——经 {@link LicenseGate#create} 注入「授权模块集合」。
 */
public class LicenseGuardTest {

    private static LicenseGuard guard(LicenseGuard.Mode mode, final Set<String> modules) {
        LicenseGate.Mode gateMode;
        switch (mode) {
            case BLOCK:
                gateMode = LicenseGate.Mode.BLOCK;
                break;
            case LOG:
                gateMode = LicenseGate.Mode.LOG;
                break;
            default:
                gateMode = LicenseGate.Mode.OFF;
        }
        return new LicenseGuard(LicenseGate.create(gateMode, () -> modules));
    }

    @Test
    public void testOffModeAllowsEverything() {
        // off 模式下即便读不到狗、命令需要模块，也一律放行（零影响、可回退）
        assertNull(guard(LicenseGuard.Mode.OFF, null)
                .intercept("data-quality", "ocr-crop", null, Locale.ROOT));
        assertNull(guard(LicenseGuard.Mode.OFF, Set.of())
                .intercept("data-quality", "run-image-quality-check", null, Locale.ROOT));
    }

    @Test
    public void testUnmappedCommandIsAlwaysAllowed() {
        // 未纳入映射的命令（项目管理、配置、规则读写、探活）不设门禁
        assertNull(guard(LicenseGuard.Mode.BLOCK, Set.of())
                .intercept("core", "create-project", null, Locale.ROOT));
        assertNull(guard(LicenseGuard.Mode.BLOCK, null)
                .intercept("data-quality", "check-aimp-connection", null, Locale.ROOT));
    }

    @Test
    public void testLicensedCommandIsAllowed() {
        assertNull(guard(LicenseGuard.Mode.BLOCK, Set.of("inspection"))
                .intercept("data-quality", "run-image-quality-check", null, Locale.ROOT));
        assertNull(guard(LicenseGuard.Mode.BLOCK, Set.of("ocr"))
                .intercept("data-quality", "ocr-crop", null, Locale.ROOT));
    }

    @Test
    public void testUnlicensedCommandIsBlocked() {
        String veto = guard(LicenseGuard.Mode.BLOCK, Set.of("ocr"))
                .intercept("data-quality", "run-image-quality-check", null, Locale.ROOT);
        assertNotNull(veto);
    }

    @Test
    public void testMultiModuleCommandRequiresAll() {
        // batch-extraction 同时依赖 info_extraction 与 aimp-chat：只授权其中一个仍拒绝
        assertNotNull(guard(LicenseGuard.Mode.BLOCK, Set.of("info_extraction"))
                .intercept("files", "batch-extraction", null, Locale.ROOT));
        assertNull(guard(LicenseGuard.Mode.BLOCK, Set.of("info_extraction", "aimp-chat"))
                .intercept("files", "batch-extraction", null, Locale.ROOT));
    }

    @Test
    public void testDogUnavailableFailsClosedInBlockMode() {
        assertNotNull(guard(LicenseGuard.Mode.BLOCK, null)
                .intercept("data-quality", "ocr-crop", null, Locale.ROOT));
    }

    @Test
    public void testDogUnavailableObservedOnlyInLogMode() {
        assertNull(guard(LicenseGuard.Mode.LOG, null)
                .intercept("data-quality", "ocr-crop", null, Locale.ROOT));
        assertNull(guard(LicenseGuard.Mode.LOG, Set.of())
                .intercept("data-quality", "ocr-crop", null, Locale.ROOT));
    }

    @Test
    public void testModeParsing() {
        assertEquals(LicenseGuard.parseMode("block"), LicenseGuard.Mode.BLOCK);
        assertEquals(LicenseGuard.parseMode(" LOG "), LicenseGuard.Mode.LOG);
        assertEquals(LicenseGuard.parseMode("off"), LicenseGuard.Mode.OFF);
        assertEquals(LicenseGuard.parseMode(null), LicenseGuard.Mode.OFF);
        // 未知取值保守回退到 off（不检查）
        assertEquals(LicenseGuard.parseMode("bogus"), LicenseGuard.Mode.OFF);
    }

    @Test
    public void testMessageBundle() {
        String zh = LicenseGuard.message("module_not_licensed", List.of("ocr"), Locale.SIMPLIFIED_CHINESE);
        assertNotNull(zh);
        assertEquals(true, zh.contains("ocr"), "拒绝消息应带上模块名：" + zh);
        assertEquals(true, zh.contains("加密狗"), "中文消息应读取 LicenseMessages_zh：" + zh);

        String en = LicenseGuard.message("dog_unavailable", List.of("inspection"), Locale.ENGLISH);
        assertEquals(true, en.contains("inspection"), "拒绝消息应带上模块名：" + en);
    }

    @Test
    public void testModuleMap() {
        assertEquals(LicenseModuleMap.modulesFor("data-quality/ocr-crop"), List.of("ocr"));
        assertEquals(LicenseModuleMap.modulesFor("data-quality/run-image-quality-check"), List.of("inspection"));
        assertEquals(LicenseModuleMap.modulesFor("files/batch-extraction"), List.of("info_extraction", "aimp-chat"));
        assertEquals(LicenseModuleMap.modulesFor("unknown/command"), List.of());
        assertEquals(LicenseModuleMap.modulesFor(null), List.of());
    }
}
