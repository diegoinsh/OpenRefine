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

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.jiongche.refine.spi.platform.DogReadPort;
import com.jiongche.sdk.dog.BridgeLocator;
import com.jiongche.sdk.dog.DogLicense;
import com.jiongche.sdk.dog.ProcessDogBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OR 侧读狗端口实现：**纯经平台 SDK**（{@link ProcessDogBridge}）读狗。
 *
 * <p>OR 不再自留桥定位 / 子进程 / JSON 解析等原生实现——这些与能力平台
 * {@code PythonDogBridge} 重复的知识已收敛为 SDK 单一实现。本类只保留 OR 需要的两点：
 * ① 按 {@code licenseId} 读 {@code --mode modules} 并缓存（{@code cacheSeconds}）；
 * ② 暴露 {@link #resolveBridge} 供启动日志。
 *
 * <p>读不到（无桥 / 无狗 / 桥失败）一律返回 {@code null}，由 {@link LicenseGuard} 决定放行还是拒绝。
 */
public class SdkDogReader implements DogReadPort {

    private static final Logger logger = LoggerFactory.getLogger(SdkDogReader.class);

    private final String configuredBridge;
    private final int licenseId;
    private final long cacheMs;

    private volatile Set<String> cached;
    private volatile long cachedAt;

    public SdkDogReader(String configuredBridge, int licenseId, int cacheSeconds) {
        this.configuredBridge = configuredBridge;
        this.licenseId = licenseId;
        this.cacheMs = Math.max(0, cacheSeconds) * 1000L;
    }

    @Override
    public int licenseId() {
        return licenseId;
    }

    /** 返回狗里的授权模块名集合；读不到返回 {@code null}。 */
    @Override
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

    /** 经 SDK 读一次狗；失败返回 {@code null}。 */
    private Set<String> readUncached() {
        try {
            ProcessDogBridge bridge = new ProcessDogBridge(configuredBridge, BridgeLocator.BridgeKind.EXE, null);
            DogLicense license = bridge.read(licenseId, "modules", null);
            return license.ok() ? new LinkedHashSet<String>(license.modules()) : null;
        } catch (Throwable t) {
            logger.warn("经 SDK 读狗失败: {}", t.toString());
            return null;
        }
    }

    /** 定位读狗桥（SDK {@link BridgeLocator}：显式配置优先，其次按部署布局上溯探测）；找不到返回 {@code null}。 */
    @Override
    public Path resolveBridge() {
        return BridgeLocator.locate(configuredBridge, BridgeLocator.BridgeKind.EXE);
    }
}
