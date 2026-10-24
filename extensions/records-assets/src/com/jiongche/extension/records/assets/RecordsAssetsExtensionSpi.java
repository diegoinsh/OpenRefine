/*
 * JinRefine SPI · Records-Assets 扩展注册实现
 *
 * 把原本写在 module/MOD-INF/controller.js（Rhino）里的注册逻辑收敛到 Java 侧，
 * 只依赖框架无关的 com.jiongche.refine.spi.RefineModuleContext。
 */

package com.jiongche.extension.records.assets;

import com.jiongche.refine.spi.RefineModuleContext;
import com.jiongche.refine.spi.RefineModuleSpi;

import com.google.refine.extension.records.assets.FileCommand;
import com.google.refine.extension.records.assets.ListCommand;
import com.google.refine.extension.records.assets.PreviewCommand;
import com.google.refine.extension.records.assets.RecordsAssetsController;

/**
 * Records-Assets 扩展的 SPI 实现。
 *
 * <p>既有的业务实现类包名保持不变（{@code com.google.refine.extension.records.assets.*}），
 * 本类仅承担注册职责。</p>
 */
public class RecordsAssetsExtensionSpi implements RefineModuleSpi {

    @Override
    public String getName() {
        return "records-assets";
    }

    @Override
    public void register(RefineModuleContext ctx) {
        // 资源浏览命令
        ctx.registerCommand("list", new ListCommand());
        ctx.registerCommand("preview", new PreviewCommand());
        ctx.registerCommand("file", new FileCommand());

        // 导入器控制器
        ctx.registerImporterController("records-assets-controller", new RecordsAssetsController());

        // 注入 /project 页的脚本
        ctx.addClientSidePaths(
                "project/scripts",
                "scripts/index/records-assets-controller.js",
                "scripts/project/resource-explorer.js",
                "scripts/project/menu-file-path.js",
                "scripts/project/file-path-cell-renderer.js",
                "scripts/project/resource-explorer-panel.js",
                "scripts/project/file-preview-dialog.js");

        // 注入 /project 页的样式
        ctx.addClientSidePaths(
                "project/styles",
                "styles/records-assets.css",
                "styles/project/file-path-cell.css",
                "styles/project/resource-explorer.css",
                "styles/project/dialogs.css");
    }
}
