/*
 * JinRefine SPI · Butterfly 装载器适配器
 *
 * 把 RefineModuleContext 的调用转投到 OR 现有的静态注册表，
 * 以及 Butterfly 的 ButterflyModule（命名空间/资源）。
 */

package com.jiongche.refine.spi;

import java.io.File;
import java.net.URL;

import com.jiongche.refine.data.DataSourceRegistry;
import com.jiongche.refine.kernel.ButterflyModuleView;
import com.jiongche.refine.kernel.ModuleView;
import com.jiongche.sdk.data.DataSource;
import edu.mit.simile.butterfly.ButterflyModule;

import com.google.refine.ClientSideResourceManager;
import com.google.refine.RefineServlet;
import com.google.refine.clustering.binning.Keyer;
import com.google.refine.clustering.binning.KeyerFactory;
import com.google.refine.clustering.knn.DistanceFactory;
import com.google.refine.clustering.knn.SimilarityDistance;
import com.google.refine.commands.Command;
import com.google.refine.commands.CommandGuard;
import com.google.refine.exporters.Exporter;
import com.google.refine.exporters.ExporterRegistry;
import com.google.refine.expr.LanguageSpecificParser;
import com.google.refine.expr.MetaParser;
import com.google.refine.importing.FormatGuesser;
import com.google.refine.importing.ImportingController;
import com.google.refine.importing.ImportingManager;
import com.google.refine.importing.ImportingParser;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;
import com.google.refine.model.recon.ReconConfig;
import com.google.refine.operations.OperationRegistry;

/**
 * {@link RefineModuleContext} 的 Butterfly 实现：包装一个 {@link ButterflyModule}，
 * 各注册面转投现有静态注册表（key = module.getName() + "/" + name）。
 */
public class ButterflyModuleContext implements RefineModuleContext {

    private final ModuleView _module;

    public ButterflyModuleContext(ButterflyModule module) {
        this(new ButterflyModuleView(module));
    }

    public ButterflyModuleContext(ModuleView module) {
        if (module == null) {
            throw new IllegalArgumentException("module must not be null");
        }
        _module = module;
    }

    /** 底层 ButterflyModule（仅过渡期使用；A7 后移除）。 */
    public ButterflyModule getModule() {
        return _module instanceof ButterflyModuleView
                ? ((ButterflyModuleView) _module).unwrap()
                : null;
    }

    @Override
    public String name() {
        return _module.getName();
    }

    @Override
    public boolean registerCommand(String name, Command cmd) {
        return RefineServlet.registerCommand(name(), name, cmd);
    }

    @Override
    public void registerCommandGuard(CommandGuard guard) {
        RefineServlet.registerCommandGuard(guard);
    }

    @Override
    public void registerClassMapping(String from, String to) {
        RefineServlet.registerClassMapping(from, to);
    }

    @Override
    public void cacheClass(Class<?> klass) {
        RefineServlet.cacheClass(klass);
    }

    @Override
    public void registerOperation(String name, Class<? extends AbstractOperation> klass) {
        OperationRegistry.registerOperation(name(), name, klass);
    }

    @Override
    public void registerImporterFormat(String format, String label) {
        ImportingManager.registerFormat(format, label);
    }

    @Override
    public void registerImporterFormat(String format, String label, String uiClass, ImportingParser parser) {
        ImportingManager.registerFormat(format, label, uiClass, parser);
    }

    @Override
    public void registerImporterExtension(String extension, String format) {
        ImportingManager.registerExtension(extension, format);
    }

    @Override
    public void registerImporterMimeType(String mimeType, String format) {
        ImportingManager.registerMimeType(mimeType, format);
    }

    @Override
    public void registerImporterController(String name, ImportingController controller) {
        ImportingManager.registerController(name(), name, controller);
    }

    @Override
    public void registerImporterGuesser(String format, FormatGuesser guesser) {
        ImportingManager.registerFormatGuesser(format, guesser);
    }

    @Override
    public void registerExporter(String format, Exporter exporter) {
        ExporterRegistry.registerExporter(format, exporter);
    }

    @Override
    public void registerReconConfig(String name, Class<? extends ReconConfig> klass) {
        ReconConfig.registerReconConfig(name(), name, klass);
    }

    @Override
    public void registerLanguageParser(String languagePrefix, String name, LanguageSpecificParser parser,
            String defaultExpression) {
        MetaParser.registerLanguageParser(languagePrefix, name, parser, defaultExpression);
    }

    @Override
    public void registerKeyer(String key, Keyer keyer) {
        KeyerFactory.put(key, keyer);
    }

    @Override
    public void registerDistance(String key, SimilarityDistance distance) {
        DistanceFactory.put(key, distance);
    }

    @Override
    public void registerOverlayModel(String modelName, Class<? extends OverlayModel> klass) {
        Project.registerOverlayModel(modelName, klass);
    }

    @Override
    public void addClientSidePaths(String bundleName, String... paths) {
        ClientSideResourceManager.addPaths(bundleName, _module, paths);
    }

    @Override
    public void registerDataSource(String name, DataSource ds) {
        DataSourceRegistry.register(name(), name, ds);
    }

    @Override
    public URL openResource(String path) {
        return _module.getResource(path);
    }

    @Override
    public File getModulePath() {
        return _module.getPath();
    }
}
