/*
 * JinRefine SPI · FilesExtension 注册实现
 *
 * 把原本写在 module/MOD-INF/controller.js 里的注册逻辑收敛到 Java 侧，
 * 只依赖框架无关的 com.jiongche.refine.spi.RefineModuleContext。
 */

package com.jiongche.extensions.files;

import com.google.refine.ProjectManager;
import com.google.refine.io.FileProjectManager;
import com.jiongche.refine.spi.RefineModuleContext;
import com.jiongche.refine.spi.RefineModuleSpi;
import org.openrefine.extensions.files.importer.BatchExtractionCommand;
import org.openrefine.extensions.files.importer.DataReviewCommand;
import org.openrefine.extensions.files.importer.ExtractionConfigCommand;
import org.openrefine.extensions.files.importer.ExtractionResultCommand;
import org.openrefine.extensions.files.importer.FilesImportingController;
import org.openrefine.extensions.files.importer.WorkspacePaths;

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
        // 下沉的纯文件工具不依赖 OR core：把 workspace 定位能力注入进去（延迟求值，
        // 避免与 ProjectManager 初始化顺序耦合）。未就绪时返回 null，工具按「无 workspace」处理。
        WorkspacePaths.configure(() -> {
            ProjectManager manager = ProjectManager.singleton;
            return manager instanceof FileProjectManager
                    ? ((FileProjectManager) manager).getWorkspaceDir()
                    : null;
        });

        // 导入器控制器
        ctx.registerImporterController("files-importing-controller", new FilesImportingController());

        // 批量著录命令
        ctx.registerCommand("batch-extraction", new BatchExtractionCommand());

        // 抽取结果分页读取（平台对外结果接口使用，只读）
        ctx.registerCommand("extraction-result", new ExtractionResultCommand());

        // 抽取控制台系统级配置命令（get/save）
        ctx.registerCommand("extraction-config", new ExtractionConfigCommand());

        // 数据校审命令（极简版：校审模式建项目 / 逐文件印章检测 / 结果写项目数据区）
        ctx.registerCommand("data-review", new DataReviewCommand());

        // 注入 /index 页的脚本
        ctx.addClientSidePaths(
                "index/scripts",
                "scripts/vendor/tiny-pinyin.js",
                "scripts/index/files-importing-controller.js",
                "scripts/index/import-from-local-dir.js",
                "scripts/index/extraction-console.js",
                "scripts/index/data-review-console.js");

        // 注入 /index 页的样式
        ctx.addClientSidePaths(
                "index/styles",
                "styles/files-importing-controller.css",
                "styles/index/extraction-console.css",
                "styles/index/data-review-console.css");

        // 注入 /project 页的脚本（抽取参数 tab、管理抽取规则弹窗、扩展栏菜单）
        ctx.addClientSidePaths(
                "project/scripts",
                "scripts/project-injection.js",
                "scripts/vendor/tiny-pinyin.js",
                "scripts/index/import-from-local-dir.js",
                "scripts/index/extraction-console.js",
                "scripts/extraction-project-panel.js",
                "scripts/dialogs/extraction-rules-dialog.js",
                "scripts/menu-bar-extension.js");

        // 注入 /project 页的样式
        ctx.addClientSidePaths(
                "project/styles",
                "styles/files-importing-controller.css",
                "styles/index/extraction-console.css",
                "styles/extraction-project-panel.css");
    }
}
