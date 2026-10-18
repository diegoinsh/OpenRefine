/*
 * JinRefine SPI · 模块上下文实现
 *
 * 把 RefineModuleContext 的调用转投 OR 现有静态注册表；
 * 只依赖框架无关的 ModuleView，不再绑定 Butterfly。
 */

package com.jiongche.refine.spi;

import java.io.File;
import java.net.URL;

import com.jiongche.refine.data.DataSourceRegistry;
import com.jiongche.refine.kernel.ModuleView;
import com.jiongche.sdk.data.DataSource;

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

public class DefaultModuleContext implements RefineModuleContext {

    private final ModuleView module;

    public DefaultModuleContext(ModuleView module) {
        if (module == null) {
            throw new IllegalArgumentException("module must not be null");
        }
        this.module = module;
    }

    @Override
    public String name() {
        return module.getName();
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
        OperationRegistry.registerOperation(module, name, klass);
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
        ImportingManager.registerController(module, name, controller);
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
        ReconConfig.registerReconConfig(module, name, klass);
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
        ClientSideResourceManager.addPaths(bundleName, module, paths);
    }

    @Override
    public void registerDataSource(String name, DataSource ds) {
        DataSourceRegistry.register(name(), name, ds);
    }

    @Override
    public URL openResource(String path) {
        return module.getResource(path);
    }

    @Override
    public File getModulePath() {
        return module.getPath();
    }
}
