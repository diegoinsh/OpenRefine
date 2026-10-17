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

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import com.jiongche.refine.spi.platform.DogReadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;
import com.google.refine.commands.CommandGuard;

/**
 * OR 侧模块授权守卫（纵深防御）：命令分发前按「命令 → 加密狗模块」校验。
 *
 * <p>复用的落点是已有的 {@link CommandGuard}（{@code RefineServlet} 在每个 POST 命令
 * 分发前逐个咨询）；OR 的命令 API 以 POST 承载全部动作，故一处覆盖所有能力入口，
 * 不存在「漏挂一处」。
 *
 * <p>三态开关（JVM 系统属性 {@code ruyi.license.enforce}，与能力平台 {@code -Druyi.license.dev} 同风格）：
 *
 * <ul>
 *   <li>{@code off}（默认）：不检查，行为与改造前完全一致（零影响、可回退）；</li>
 *   <li>{@code log}：只记日志放行——用于上线前观察真实调用面；</li>
 *   <li>{@code block}：未授权的命令返回错误拒绝。</li>
 * </ul>
 *
 * <p>读不到狗时按 {@code block} **失败关闭**（与能力平台过滤器、AIMP 守卫同口径）：
 * 狗不在 / 读不到 ⇒ 受门禁的命令一律拒绝。
 *
 * <p>相关系统属性：{@code ruyi.license.bridge}（读狗桥路径，缺省自动探测）、
 * {@code ruyi.license.id}（缺省 80）、{@code ruyi.license.cache-seconds}（缺省 60）。
 */
public class LicenseGuard implements CommandGuard {

    /** 校验状态。 */
    public enum Mode {
        OFF, LOG, BLOCK
    }

    private static final Logger logger = LoggerFactory.getLogger(LicenseGuard.class);
    private static final String BUNDLE_NAME = "com.google.refine.licensing.LicenseMessages";

    private final Mode mode;
    private final DogReadPort reader;

    public LicenseGuard(Mode mode, DogReadPort reader) {
        this.mode = mode;
        this.reader = reader;
    }

    /** 从 JVM 系统属性装配（{@code -Druyi.license.*}，如写在 refine.ini 的 JAVA_OPTIONS 中）。 */
    public static LicenseGuard fromConfiguration() {
        Mode mode = parseMode(System.getProperty("ruyi.license.enforce", "off"));
        DogReadPort reader = new SdkDogReader(
                System.getProperty("ruyi.license.bridge"),
                intProperty("ruyi.license.id", 80),
                intProperty("ruyi.license.cache-seconds", 60));
        return new LicenseGuard(mode, reader);
    }

    private static int intProperty(String name, int def) {
        String raw = System.getProperty(name);
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("无法解析 {}={}，回退为 {}", name, raw, def);
            return def;
        }
    }

    static Mode parseMode(String raw) {
        if (raw == null) {
            return Mode.OFF;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "log":
                return Mode.LOG;
            case "block":
                return Mode.BLOCK;
            case "off":
                return Mode.OFF;
            default:
                logger.warn("未知的 ruyi.license.enforce={}，按 off 处理（不检查）", raw);
                return Mode.OFF;
        }
    }

    public Mode mode() {
        return mode;
    }

    /** 启动日志用的一句话说明。 */
    public String describe() {
        if (mode == Mode.OFF) {
            return "模块授权校验：关闭（ruyi.license.enforce=off）";
        }
        java.nio.file.Path bridge = reader.resolveBridge();
        return String.format("模块授权校验：%s（license-id=%d，读狗桥=%s）",
                mode.name().toLowerCase(Locale.ROOT), reader.licenseId(),
                bridge == null ? "未找到" : bridge.toString());
    }

    /** 注册到 servlet（幂等）。 */
    public static void register(RefineServlet servlet) {
        LicenseGuard guard = fromConfiguration();
        servlet.registerCommandGuard(guard);
        logger.info("{}", guard.describe());
    }

    @Override
    public String intercept(String module, String commandName, String projectId, Locale locale) {
        if (mode == Mode.OFF) {
            return null;
        }

        String commandKey = (module == null ? "" : module) + "/" + (commandName == null ? "" : commandName);
        List<String> needed = LicenseModuleMap.modulesFor(commandKey);
        if (needed.isEmpty()) {
            return null; // 免授权或未纳入映射的命令
        }

        Set<String> granted = reader.read();
        String reason = null;
        if (granted == null) {
            reason = "dog_unavailable";
        } else {
            for (String m : needed) {
                if (!granted.contains(m)) {
                    reason = "module_not_licensed";
                    break;
                }
            }
        }
        if (reason == null) {
            return null; // 所需模块全部在授权集内
        }

        if (mode == Mode.BLOCK) {
            logger.warn("[授权] 拒绝 {}：所需模块 {}（{}）", commandKey, needed, reason);
            return message(reason, needed, locale);
        }
        logger.warn("[授权] 观察模式放行 {}：所需模块 {}（{}）", commandKey, needed, reason);
        return null;
    }

    /** 按客户端语言取拒绝消息（缺失时回退键名，绝不因资源缺失而放行）。 */
    static String message(String reason, List<String> needed, Locale locale) {
        String key = "dog_unavailable".equals(reason)
                ? "license.dogUnavailable"
                : "license.moduleNotLicensed";
        try {
            Locale effective = locale != null ? locale : Locale.ROOT;
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, effective,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            return MessageFormat.format(bundle.getString(key), String.join(",", needed));
        } catch (Exception e) {
            logger.warn("授权消息资源不可用，回退为键名: {}", key);
            return key;
        }
    }
}
