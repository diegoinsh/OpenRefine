/*
 * JinRefine SPI · Data-Quality 扩展注册实现
 *
 * 把原本写在 module/MOD-INF/controller.js 里的注册逻辑收敛到 Java 侧，
 * 只依赖框架无关的 com.jiongche.refine.spi.RefineModuleContext。
 */

package com.jiongche.extension.quality;

import com.jiongche.refine.spi.RefineModuleContext;
import com.jiongche.refine.spi.RefineModuleSpi;

import com.google.refine.extension.quality.commands.CheckAimpConnectionCommand;
import com.google.refine.extension.quality.commands.ExportQualityReportCommand;
import com.google.refine.extension.quality.commands.GetCheckProgressCommand;
import com.google.refine.extension.quality.commands.GetConfigCommand;
import com.google.refine.extension.quality.commands.GetImageQualityRuleCommand;
import com.google.refine.extension.quality.commands.GetQualityResultCommand;
import com.google.refine.extension.quality.commands.GetQualityRulesCommand;
import com.google.refine.extension.quality.commands.IngestProjectCommand;
import com.google.refine.extension.quality.commands.ListGlobalRulesCommand;
import com.google.refine.extension.quality.commands.ListTasksCommand;
import com.google.refine.extension.quality.commands.OcrCropCommand;
import com.google.refine.extension.quality.commands.RenderFilePageCommand;
import com.google.refine.extension.quality.commands.RunImageQualityCheckCommand;
import com.google.refine.extension.quality.commands.RunQualityCheckCommand;
import com.google.refine.extension.quality.commands.SaveConfigCommand;
import com.google.refine.extension.quality.commands.SaveImageQualityRuleCommand;
import com.google.refine.extension.quality.commands.SaveQualityResultCommand;
import com.google.refine.extension.quality.commands.SaveQualityRulesCommand;
import com.google.refine.extension.quality.commands.TaskControlCommand;
import com.google.refine.extension.quality.commands.TestAimpConnectionCommand;
import com.google.refine.extension.quality.model.OverlayQualityCheckResult;
import com.google.refine.extension.quality.model.OverlayQualityRulesConfig;
import com.google.refine.extension.quality.operations.SaveQualityResultOperation;
import com.google.refine.extension.quality.operations.SaveQualityRulesOperation;

/**
 * Data-Quality 扩展的 SPI 实现。
 *
 * <p>既有的业务实现类包名保持不变（{@code com.google.refine.extension.quality.*}），
 * 本类仅承担注册职责。</p>
 */
public class DataQualityExtensionSpi implements RefineModuleSpi {

    @Override
    public String getName() {
        return "data-quality";
    }

    @Override
    public void register(RefineModuleContext ctx) {
        registerCommands(ctx);
        registerOperations(ctx);
        registerClientSideResources(ctx);
    }

    private void registerCommands(RefineModuleContext ctx) {
        // 质量规则相关命令
        ctx.registerCommand("save-quality-rules", new SaveQualityRulesCommand());
        ctx.registerCommand("get-quality-rules", new GetQualityRulesCommand());
        ctx.registerCommand("run-quality-check", new RunQualityCheckCommand());
        ctx.registerCommand("check-aimp-connection", new CheckAimpConnectionCommand());
        ctx.registerCommand("export-quality-report", new ExportQualityReportCommand());
        ctx.registerCommand("get-check-progress", new GetCheckProgressCommand());
        ctx.registerCommand("save-quality-result", new SaveQualityResultCommand());
        ctx.registerCommand("get-quality-result", new GetQualityResultCommand());

        // 任务控制命令（暂停/恢复/取消）
        ctx.registerCommand("task-control", new TaskControlCommand());
        ctx.registerCommand("list-tasks", new ListTasksCommand());

        // 配置命令
        ctx.registerCommand("get-config", new GetConfigCommand());
        ctx.registerCommand("save-config", new SaveConfigCommand());
        ctx.registerCommand("test-aimp-connection", new TestAimpConnectionCommand());

        // 全局规则管理命令
        ctx.registerCommand("list-global-rules", new ListGlobalRulesCommand());

        // 图像质量命令
        ctx.registerCommand("get-image-quality-rule", new GetImageQualityRuleCommand());
        ctx.registerCommand("save-image-quality-rule", new SaveImageQualityRuleCommand());
        ctx.registerCommand("run-image-quality-check", new RunImageQualityCheckCommand());

        // SSO 工程导入命令（外部系统推送入口数据）
        ctx.registerCommand("ingest-project", new IngestProjectCommand());

        // OCR 裁切命令（文件视图面板）
        ctx.registerCommand("render-file-page", new RenderFilePageCommand());
        ctx.registerCommand("ocr-crop", new OcrCropCommand());
    }

    private void registerOperations(RefineModuleContext ctx) {
        // 保存质量规则 operation
        ctx.registerOperation("save-quality-rules-operation", SaveQualityRulesOperation.class);

        // 历史记录变更类（沿用 wikibase 模式）
        ctx.registerClassMapping(
                "com.google.refine.extension.quality.operations.SaveQualityRulesOperation$QualityRulesChange",
                "com.google.refine.extension.quality.operations.SaveQualityRulesOperation$QualityRulesChange");
        ctx.cacheClass(SaveQualityRulesOperation.QualityRulesChange.class);

        // 质量规则 overlay 模型（OR 侧薄壳子类：内核实体已下沉，overlay 需 OverlayModel 能力）
        ctx.registerOverlayModel("qualityRulesConfig", OverlayQualityRulesConfig.class);

        // 保存质量结果 operation
        ctx.registerOperation("save-quality-result-operation", SaveQualityResultOperation.class);

        // 结果历史记录变更类
        ctx.registerClassMapping(
                "com.google.refine.extension.quality.operations.SaveQualityResultOperation$QualityResultChange",
                "com.google.refine.extension.quality.operations.SaveQualityResultOperation$QualityResultChange");
        ctx.cacheClass(SaveQualityResultOperation.QualityResultChange.class);

        // 质量结果 overlay 模型（OR 侧薄壳子类）
        ctx.registerOverlayModel("qualityCheckResult", OverlayQualityCheckResult.class);
    }

    private void registerClientSideResources(RefineModuleContext ctx) {
        // 注入 /index 页（首页）的脚本
        ctx.addClientSidePaths(
                "index/scripts",
                "scripts/index/data-quality-ui.js");

        // 注入 /index 页（首页）的样式
        ctx.addClientSidePaths(
                "index/styles",
                "styles/index/data-quality-ui.css");

        // 注入 /project 页的脚本
        ctx.addClientSidePaths(
                "project/scripts",
                "scripts/menu-bar-extension.js",
                "scripts/quality-alignment.js",
                "scripts/quality-cell-renderer.js",
                "scripts/extraction-quality-cell-renderer.js",
                "scripts/dialogs/manage-rules-dialog.js",
                "scripts/dialogs/run-check-dialog.js",
                "scripts/dialogs/image-quality-tab.js",
                "scripts/dialogs/regex-editor-dialog.js",
                "scripts/file-view-panel.js");

        // 注入 /project 页的样式
        ctx.addClientSidePaths(
                "project/styles",
                "styles/quality-alignment.css",
                "styles/dialogs/manage-rules-dialog.css",
                "styles/dialogs/run-check-dialog.css",
                "styles/dialogs/image-quality-tab.css",
                "styles/dialogs/regex-editor-dialog.css",
                "styles/file-view-panel.css");
    }
}
