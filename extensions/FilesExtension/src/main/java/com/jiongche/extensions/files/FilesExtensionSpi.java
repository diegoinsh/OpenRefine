/*
 * JinRefine SPI · FilesExtension 注册实现
 *
 * 把原本写在 module/MOD-INF/controller.js 里的注册逻辑收敛到 Java 侧，
 * 只依赖框架无关的 com.jiongche.refine.spi.RefineModuleContext。
 */

package com.jiongche.extensions.files;

import com.jiongche.refine.spi.RefineModuleContext;
import com.jiongche.refine.spi.RefineModuleSpi;
import org.openrefine.extensions.files.importer.BatchExtractionCommand;
import org.openrefine.extensions.files.importer.ExtractionConfigCommand;
import org.openrefine.extensions.files.importer.FilesImportingController;

/**
 * Files extension 的 SPI 实现。
 *
 * <p>既有的业务实现类包名保持不变（{@code org.openrefine.extensions.files.*}），
 * 本类仅承担注册职责。</p>
 */
public class FilesExtensionSpi implements RefineModuleSpi {

    @Override
    public String getName() {
        return "files";
    }

    @Override
    public void register(RefineModuleContext ctx) {
        // 导入器控制器
        ctx.registerImporterController("files-importing-controller", new FilesImportingController());

        // 批量著录命令
        ctx.registerCommand("batch-extraction", new BatchExtractionCommand());

        // 抽取控制台系统级配置命令（get/save）
        ctx.registerCommand("extraction-config", new ExtractionConfigCommand());

        // 注入 /index 页的脚本
        ctx.addClientSidePaths(
                "index/scripts",
                "scripts/vendor/tiny-pinyin.js",
                "scripts/index/files-importing-controller.js",
                "scripts/index/import-from-local-dir.js",
                "scripts/index/extraction-console.js");

        // 注入 /index 页的样式
        ctx.addClientSidePaths(
                "index/styles",
                "styles/files-importing-controller.css",
                "styles/index/extraction-console.css");

        // 注入 /project 页的脚本
        ctx.addClientSidePaths(
                "project/scripts",
                "scripts/project-injection.js");

        // 注入 /project 页的样式
        ctx.addClientSidePaths(
                "project/styles",
                "styles/files-importing-controller.css");
    }
}
