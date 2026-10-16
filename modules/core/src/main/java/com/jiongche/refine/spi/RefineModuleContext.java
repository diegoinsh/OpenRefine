/*
 * JinRefine SPI · 框架无关的模块注册门面
 *
 * 业务扩展面向本接口声明它要注册到 OR 的「注册面」，
 * 由装载器（当前 Butterfly，后续 Solon）提供实现，从而把业务与容器/装载机制解耦。
 */

package com.jiongche.refine.spi;

import java.io.File;
import java.net.URL;

import com.google.refine.commands.Command;
import com.google.refine.commands.CommandGuard;
import com.google.refine.exporters.Exporter;
import com.google.refine.expr.LanguageSpecificParser;
import com.google.refine.importing.FormatGuesser;
import com.google.refine.importing.ImportingController;
import com.google.refine.importing.ImportingParser;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.recon.ReconConfig;

/**
 * 框架无关的注册门面，覆盖 OR 暴露给扩展的名注册面。
 *
 * <p>实现方（如 {@link ButterflyModuleContext}）负责把这些调用转投到底层静态注册表，
 * 业务扩展只依赖本接口，不直接触碰容器/装载器细节。</p>
 */
public interface RefineModuleContext {

    /** 该模块在 OR 中的命名空间（用于 key = name + "/" + x 的拼接）。 */
    String name();

    // ---- 1 commands / guards / class mapping ----

    /** 注册命令；返回 false 表示同名命令已存在。 */
    boolean registerCommand(String name, Command cmd);

    /** 注册 POST 命令守卫（幂等）。 */
    void registerCommandGuard(CommandGuard guard);

    /** 注册历史记录里的旧类名 → 新类名映射。 */
    void registerClassMapping(String from, String to);

    /** 缓存类，供跨模块解析（Butterfly 类名解析历史遗留）。 */
    void cacheClass(Class<?> klass);

    // ---- 2 operations ----

    void registerOperation(String name, Class<? extends AbstractOperation> klass);

    // ---- 3 importing ----

    void registerImporterFormat(String format, String label);

    void registerImporterFormat(String format, String label, String uiClass, ImportingParser parser);

    void registerImporterExtension(String extension, String format);

    void registerImporterMimeType(String mimeType, String format);

    void registerImporterController(String name, ImportingController controller);

    void registerImporterGuesser(String format, FormatGuesser guesser);

    // ---- 4 exporters ----

    void registerExporter(String format, Exporter exporter);

    // ---- 5 reconciliation ----

    void registerReconConfig(String name, Class<? extends ReconConfig> klass);

    // ---- 6 expression languages ----

    void registerLanguageParser(String languagePrefix, String name, LanguageSpecificParser parser,
            String defaultExpression);

    // ---- 7 clustering ----

    void registerKeyer(String key, com.google.refine.clustering.binning.Keyer keyer);

    void registerDistance(String key, com.google.refine.clustering.knn.SimilarityDistance distance);

    // ---- overlay model ----

    void registerOverlayModel(String modelName, Class<? extends OverlayModel> klass);

    // ---- 8 client-side resources ----

    /** 把若干资源路径注入到某个 bundle（如 "index/scripts"、"project/styles"）。 */
    void addClientSidePaths(String bundleName, String... paths);

    // ---- 9 i18n / 10 resources ----

    /** 以 URL 打开模块内资源（如 langs/ 下的语言包）。 */
    URL openResource(String path);

    /** 模块根目录。 */
    File getModulePath();
}
