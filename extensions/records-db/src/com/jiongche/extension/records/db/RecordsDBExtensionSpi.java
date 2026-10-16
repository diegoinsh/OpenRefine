/*
 * JinRefine SPI · Records-DB 扩展注册实现
 *
 * 把原本分散在 RecordsDBModuleImpl.init()（Java）与 module/MOD-INF/controller.js（Rhino）
 * 的注册逻辑收敛到 Java 侧，只依赖框架无关的 com.jiongche.refine.spi.RefineModuleContext。
 */

package com.jiongche.extension.records.db;

import com.jiongche.refine.spi.RefineModuleContext;
import com.jiongche.refine.spi.RefineModuleSpi;

import com.google.refine.extension.records.db.RecordsDatabaseImportController;
import com.google.refine.extension.records.db.cmd.ExportBoundAssetsCommand;
import com.google.refine.extension.records.db.model.RecordsDBOverlayModel;

/**
 * Records-DB 扩展的 SPI 实现。
 *
 * <p>既有的业务实现类包名保持不变（{@code com.google.refine.extension.records.db.*}），
 * 本类仅承担注册职责。</p>
 */
public class RecordsDBExtensionSpi implements RefineModuleSpi {

    @Override
    public String getName() {
        return "records-db";
    }

    @Override
    public void register(RefineModuleContext ctx) {
        // 导入器控制器（原在 RecordsDBModuleImpl.init() 注册）
        ctx.registerImporterController("records-db-import-controller", new RecordsDatabaseImportController());

        // schema profile overlay 模型（原在 RecordsDBModuleImpl.init() 注册）
        ctx.registerOverlayModel(RecordsDBOverlayModel.OVERLAY_MODEL_KEY, RecordsDBOverlayModel.class);

        // 导出绑定资产命令
        ctx.registerCommand("export-bound-assets", new ExportBoundAssetsCommand());

        // 注入 /index 页的脚本
        ctx.addClientSidePaths(
                "index/scripts",
                "scripts/index/records-db-filter-builder.js",
                "scripts/index/records-db-wizard-steps.js",
                "scripts/index/records-db-import-controller.js");

        // 注入 /index 页的样式
        ctx.addClientSidePaths(
                "index/styles",
                "styles/records-db-wizard.css");

        // 注入 /project 页的脚本
        ctx.addClientSidePaths(
                "project/scripts",
                "scripts/dialogs/export-bound-assets-dialog.js",
                "scripts/project/menu-extensions.js");

        // 注入 /project 页的样式
        ctx.addClientSidePaths(
                "project/styles",
                "styles/dialogs/export-bound-assets-dialog.css");
    }
}
