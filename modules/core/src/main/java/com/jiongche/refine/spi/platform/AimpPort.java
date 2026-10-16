/*
 * JinRefine SPI · OR 侧 AIMP 能力端口（baseline §6 要点③「平台 SDK 接口层」· 步骤 Y1）
 *
 * 本接口声明 OR 对「AIMP（AI 引擎）」的能力面，与平台侧
 * com.ruyi.platform.spi.service.AimpClient（postJson / postMultipart）在**线上契约（wire contract）**
 * 上对齐：端点路径、请求/响应字段名、multipart 编码、7998/7999 模式判定
 * —— Y2 起由平台 SDK 以「单一权威常量 + 无依赖 multipart 构造器」提供，两侧共用。
 *
 * <p><b>未纳入</b>的方法：{@code batchCompare} / {@code llmAnalyze} / {@code ocrCrop} 的返回值是
 * 实现类 {@code AimpClient} 的**内嵌结果类**（{@code BatchCompareResult} / {@code LlmAnalyzeResult} /
 * {@code OcrCropResult}），纳入 port 会让接口反向依赖实现类；Y2 下沉 SDK 时再定是否提升为独立 DTO。
 */

package com.jiongche.refine.spi.platform;

import java.util.Map;

/**
 * AIMP（AI 引擎）能力端口。
 *
 * <p>实现方可为 OR 现实现
 * {@code com.google.refine.extension.quality.aimp.AimpClient}。
 */
public interface AimpPort {

    /** 探活 AIMP 服务（{@code /health} 或 {@code /docs}）；任一可达即视为连得上。 */
    boolean testConnection();

    /** 按检查类型取内容检查端点（7999 走各专项端点，7998 走统一端点）。 */
    String getCheckEndpoint(String checkType);

    /** 用 OCR 从文件抽取内容，返回「标签 → 取值」映射（缺省键表）。 */
    Map<String, String> extractContent(String filePath);

    /** 用 OCR 从文件抽取内容，指定逗号分隔的键表。 */
    Map<String, String> extractContent(String filePath, String keyList);

    /** AIMP 服务基址（已归一化为 {@code scheme://host:port}）。 */
    String getServiceUrl();

    /** 当前接口模式（{@code 7998} / {@code 7999}）。 */
    String getInterfaceMode();

    /** 是否为 7998（统一 {@code /api/ocr/extract}）模式。 */
    boolean is7998Mode();

    /** 是否为 7999（各专项端点）模式。 */
    boolean is7999Mode();
}
