/*
 * JinRefine SPI · OR 侧读狗端口（baseline §6 要点③「平台 SDK 接口层」· 步骤 Y1）
 *
 * 本接口是 OR 侧对「读加密狗」能力的**声明**（port）：生产读狗已统一收敛到 SDK
 * {@code com.jiongche.sdk.authz.LicenseGate}（内部经 {@code ProcessDogBridge}），
 * 与下沉能力引擎共用同一份实现与缓存；本端口保留为宿主/测试注入点。
 *
 * 与平台侧 SPI 的 {@code LicenseService} / {@code LicenseInfo}
 * **对齐**（同名 / 同签名 / 同语义；跨仓跨框架不共享同一 Java 类型，故两侧各自声明镜像类型）：
 *
 *   read()          ↔ LicenseService.licenseInfo().modules
 *                     （同一份桥 stdout 末行 JSON 的 modules 字段）
 *   licenseId()     ↔ 读狗配置项 license-id（平台 app.yml、OR ruyi.license.id）
 *   resolveBridge() ↔ BridgeLocator（显式配置优先，其次三布局上溯探测）
 */

package com.jiongche.refine.spi.platform;

import java.nio.file.Path;
import java.util.Set;

/**
 * 读狗能力端口：返回加密狗里的授权模块集合，并暴露所用 license-id 与桥定位。
 *
 * <p>生产路径由 SDK {@code LicenseGate} 承担；实现本接口仅用于宿主自定义或单测注入。
 */
public interface DogReadPort {

    /** 狗里的授权模块名集合；读不到（无桥 / 无狗 / 桥失败）返回 {@code null}。 */
    Set<String> read();

    /** 读狗所用的 license-id。 */
    int licenseId();

    /** 定位读狗桥；找不到返回 {@code null}。 */
    Path resolveBridge();
}
