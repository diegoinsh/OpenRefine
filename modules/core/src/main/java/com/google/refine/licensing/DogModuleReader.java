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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiongche.refine.spi.platform.DogReadPort;
import com.ruyi.sdk.dog.BridgeLocator;
import com.ruyi.sdk.dog.DogLicense;
import com.ruyi.sdk.dog.ProcessDogBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 加密狗读取桥（OR 侧）：以子进程调用读狗桥。
 *
 * <p>与能力平台（{@code PythonDogBridge}）、AIMP（{@code src/licensing/bridge.py}）复用同一条桥、
 * 同一份 {@code dog_read.exe}（AIMP 侧已验证），避免在 OR 里重写 ctypes/JNI 代码。调用形式::
 *
 * <pre>dog_read.exe --mode modules --license-id 80</pre>
 *
 * <p>stdout **最后一行**为单行 JSON：{@code {"ok":true,"modules":[...]}}，失败时
 * {@code {"ok":false,"reason":"dll_missing|no_dog|init_failed"}}。读不到（无桥、无狗、失败）
 * 一律返回 {@code null}，由 {@link LicenseGuard} 决定放行还是拒绝。
 *
 * <p>读一次要起一个进程（几十毫秒），故结果按 {@code cacheSeconds} 缓存；缺省 60 秒——
 * 既能让「拔狗 / 过期」在秒级生效，又不会每个请求都起一个读取进程。
 */
public class DogModuleReader implements DogReadPort {

    private static final Logger logger = LoggerFactory.getLogger(DogModuleReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BRIDGE_NAME = isWindows() ? "dog_read.exe" : "dog_read";

    /** 桥调用硬超时（毫秒）：无狗机器上不能让请求被拖住。 */
    private static final long TIMEOUT_MS = 5000L;

    /** 候选路径从锚点向上探测的层数（覆盖到 platform 与 OR 根同级的那一层）。 */
    private static final int MAX_WALK_UP = 5;

    /** SDK 开关属性名：{@code on} 时读狗走平台 SDK 单份实现；缺省 {@code off} 走本类原实现。 */
    private static final String SDK_PROPERTY = "ruyi.platform.sdk";

    private final String configuredBridge;
    private final int licenseId;
    private final long cacheMs;

    private volatile Set<String> cached;
    private volatile long cachedAt;

    public DogModuleReader(String configuredBridge, int licenseId, int cacheSeconds) {
        this.configuredBridge = configuredBridge;
        this.licenseId = licenseId;
        this.cacheMs = Math.max(0, cacheSeconds) * 1000L;
    }

    public int licenseId() {
        return licenseId;
    }

    /** 返回狗里的授权模块名集合；读不到返回 {@code null}。 */
    public Set<String> read() {
        if (cacheMs > 0 && cachedAt != 0L && System.currentTimeMillis() - cachedAt < cacheMs) {
            return cached;
        }
        synchronized (this) {
            if (cacheMs > 0 && cachedAt != 0L && System.currentTimeMillis() - cachedAt < cacheMs) {
                return cached;
            }
            Set<String> value = readUncached();
            cached = value;
            cachedAt = System.currentTimeMillis();
            return value;
        }
    }

    private Set<String> readUncached() {
        if (sdkEnabled()) {
            return readUncachedViaSdk();
        }
        Path exe = resolveBridge();
        if (exe == null) {
            logger.warn("未找到读狗桥 {}，视为读不到加密狗", BRIDGE_NAME);
            return null;
        }

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toString(),
                    "--mode", "modules", "--license-id", String.valueOf(licenseId));
            pb.redirectErrorStream(true);
            proc = pb.start();
            String out;
            try (InputStream in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("读狗桥超时（{}ms）", TIMEOUT_MS);
                return null;
            }
            return parse(out);
        } catch (Exception e) {
            logger.warn("调用读狗桥失败: {}", e.toString());
            return null;
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /**
     * {@code ruyi.platform.sdk=on} 时经平台 SDK 读狗——与能力平台 {@code PythonDogBridge}
     * 用**同一份**实现（SDK {@code ProcessDogBridge}），消除两侧各写一遍的漂移。
     *
     * <p>语义与原实现一致：{@code --mode modules}（只取 {@code modules}）、5s 硬超时、桥定位
     * 同为「显式配置优先 → 三布局上溯探测」；读不到（无桥 / 无狗 / 失败）一律返回 {@code null}。
     */
    private Set<String> readUncachedViaSdk() {
        try {
            ProcessDogBridge bridge = new ProcessDogBridge(configuredBridge, BridgeLocator.BridgeKind.EXE, null);
            DogLicense lic = bridge.read(licenseId, "modules", null);
            return lic.ok() ? new LinkedHashSet<>(lic.modules()) : null;
        } catch (Throwable t) {
            logger.warn("经 SDK 读狗失败: {}", t.toString());
            return null;
        }
    }

    /** 取 stdout 最后一行 JSON 解析（桥把日志写 stderr，故只有一行结果）。 */
    static Set<String> parse(String out) {
        String json = null;
        for (String line : out.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("{")) {
                json = t;
            }
        }
        if (json == null) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.path("ok").asBoolean(false)) {
                return null;
            }
            Set<String> modules = new LinkedHashSet<>();
            for (JsonNode m : n.path("modules")) {
                modules.add(m.asText());
            }
            return modules;
        } catch (Exception e) {
            return null;
        }
    }

    /** 定位读狗桥：显式配置优先，其次按部署布局探测；找不到返回 {@code null}。 */
    public Path resolveBridge() {
        if (configuredBridge != null && !configuredBridge.isBlank()) {
            Path p = Paths.get(configuredBridge);
            return Files.isReadable(p) ? p : null;
        }
        for (Path cand : candidates()) {
            if (Files.isReadable(cand)) {
                return cand;
            }
        }
        return null;
    }

    /** 按部署布局给出读狗桥的候选路径（首个可读者胜出）。
     *
     * <p>锚点取两处：本类所在 jar 的位置、进程工作目录（{@code user.dir}）。
     * 从每个锚点**逐级上溯**（最多 5 层），每层测试三种相对布局——以兼容
     * 「OR 根 / 管理器根 / jar 落在 {@code webapp/WEB-INF/lib} 或 {@code server/target/lib}」
     * 等差异：
     *
     * <pre>
     *   &lt;anchor&gt;/platform/deploy/bridge/   # deploy/jinrefine 布局（platform 与 OR 根同级）
     *   &lt;anchor&gt;/deploy/bridge/            # 打包布局
     *   &lt;anchor&gt;/bridge/
     * </pre>
     */
    static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        for (Path anchor : anchors()) {
            Path level = anchor;
            for (int i = 0; level != null && i < MAX_WALK_UP; i++, level = level.getParent()) {
                out.add(level.resolve("platform").resolve("deploy").resolve("bridge").resolve(BRIDGE_NAME));
                out.add(level.resolve("deploy").resolve("bridge").resolve(BRIDGE_NAME));
                out.add(level.resolve("bridge").resolve(BRIDGE_NAME));
            }
        }
        return out;
    }

    /** 候选路径的上溯锚点：jar 位置、进程工作目录。 */
    private static List<Path> anchors() {
        List<Path> anchors = new ArrayList<>();
        Path root = orRoot();
        if (root != null) {
            anchors.add(root.toAbsolutePath());
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            anchors.add(Paths.get(userDir).toAbsolutePath());
        }
        return anchors;
    }

    /** 本类代码所在位置（jar 文件或 {@code classes/} 目录）的父目录。 */
    private static Path orRoot() {
        try {
            Path code = Paths.get(DogModuleReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(code) ? code : code.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /** SDK 开关是否打开（系统属性 {@code ruyi.platform.sdk}，缺省 {@code off}）。 */
    private static boolean sdkEnabled() {
        return "on".equalsIgnoreCase(System.getProperty(SDK_PROPERTY, "off").trim());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
