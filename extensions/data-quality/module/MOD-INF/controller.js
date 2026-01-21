/*
 * Controller for Data Quality Extension
 * 
 * This is run in the Butterfly (ie Refine) server context using the Rhino
 * Javascript interpreter.
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";
var ClientSideResourceManager = Packages.com.google.refine.ClientSideResourceManager;

var logger = Packages.org.slf4j.LoggerFactory.getLogger("data-quality-extension"),
    File = Packages.java.io.File,
    refineServlet = Packages.com.google.refine.RefineServlet;

/*
 * Register our custom commands.
 */
function registerCommands() {
  logger.trace("Registering Data-Quality Extension Commands...");

  var RS = Packages.com.google.refine.RefineServlet;

  // Register quality rules commands
  RS.registerCommand(module, "save-quality-rules",
      new Packages.com.google.refine.extension.quality.commands.SaveQualityRulesCommand());
  RS.registerCommand(module, "get-quality-rules",
      new Packages.com.google.refine.extension.quality.commands.GetQualityRulesCommand());
  RS.registerCommand(module, "run-quality-check",
      new Packages.com.google.refine.extension.quality.commands.RunQualityCheckCommand());
  RS.registerCommand(module, "check-aimp-connection",
      new Packages.com.google.refine.extension.quality.commands.CheckAimpConnectionCommand());
  RS.registerCommand(module, "export-quality-report",
      new Packages.com.google.refine.extension.quality.commands.ExportQualityReportCommand());
  RS.registerCommand(module, "get-check-progress",
      new Packages.com.google.refine.extension.quality.commands.GetCheckProgressCommand());
  RS.registerCommand(module, "save-quality-result",
      new Packages.com.google.refine.extension.quality.commands.SaveQualityResultCommand());
  RS.registerCommand(module, "get-quality-result",
      new Packages.com.google.refine.extension.quality.commands.GetQualityResultCommand());

  // Task control commands (pause/resume/cancel)
  RS.registerCommand(module, "task-control",
      new Packages.com.google.refine.extension.quality.commands.TaskControlCommand());
  RS.registerCommand(module, "list-tasks",
      new Packages.com.google.refine.extension.quality.commands.ListTasksCommand());

  // Config commands
  RS.registerCommand(module, "get-config",
      new Packages.com.google.refine.extension.quality.commands.GetConfigCommand());
  RS.registerCommand(module, "save-config",
      new Packages.com.google.refine.extension.quality.commands.SaveConfigCommand());
  RS.registerCommand(module, "test-aimp-connection",
      new Packages.com.google.refine.extension.quality.commands.TestAimpConnectionCommand());

  // Global rules management commands
  RS.registerCommand(module, "list-global-rules",
      new Packages.com.google.refine.extension.quality.commands.ListGlobalRulesCommand());

  // Image quality commands
  RS.registerCommand(module, "get-image-quality-rule",
      new Packages.com.google.refine.extension.quality.commands.GetImageQualityRuleCommand());
  RS.registerCommand(module, "save-image-quality-rule",
      new Packages.com.google.refine.extension.quality.commands.SaveImageQualityRuleCommand());
  RS.registerCommand(module, "run-image-quality-check",
      new Packages.com.google.refine.extension.quality.commands.RunImageQualityCheckCommand());

  logger.trace("Data-Quality Extension Command Registration done!");
}

function registerOperations() {
  var OR = Packages.com.google.refine.operations.OperationRegistry;
  var RS = Packages.com.google.refine.RefineServlet;

  // Register save quality rules operation
  OR.registerOperation(module, "save-quality-rules-operation",
      Packages.com.google.refine.extension.quality.operations.SaveQualityRulesOperation);

  // Register change class for history (following wikibase pattern)
  RS.registerClassMapping(
      "com.google.refine.extension.quality.operations.SaveQualityRulesOperation$QualityRulesChange",
      "com.google.refine.extension.quality.operations.SaveQualityRulesOperation$QualityRulesChange");
  RS.cacheClass(Packages.com.google.refine.extension.quality.operations.SaveQualityRulesOperation$QualityRulesChange);

  // Register overlay model for quality rules
  Packages.com.google.refine.model.Project.registerOverlayModel(
      "qualityRulesConfig",
      Packages.com.google.refine.extension.quality.model.QualityRulesConfig);

  // Register save quality result operation
  OR.registerOperation(module, "save-quality-result-operation",
      Packages.com.google.refine.extension.quality.operations.SaveQualityResultOperation);

  // Register change class for result history
  RS.registerClassMapping(
      "com.google.refine.extension.quality.operations.SaveQualityResultOperation$QualityResultChange",
      "com.google.refine.extension.quality.operations.SaveQualityResultOperation$QualityResultChange");
  RS.cacheClass(Packages.com.google.refine.extension.quality.operations.SaveQualityResultOperation$QualityResultChange);

  // Register overlay model for quality result
  Packages.com.google.refine.model.Project.registerOverlayModel(
      "qualityCheckResult",
      Packages.com.google.refine.extension.quality.model.CheckResult);
}

function registerFunctions() {
  // TODO: Register GREL functions if needed
}

/*
 * Function invoked to initialize the extension.
 */
function init() {
  logger.info("Initializing JinRefine Data-Quality Extension...");
  logger.trace("Data-Quality Extension Mount point " + module.getMountPoint());

  registerCommands();
  registerOperations();
  registerFunctions();

  // Script files to inject into /index page (homepage)
  ClientSideResourceManager.addPaths(
    "index/scripts",
    module,
    [
      "scripts/index/data-quality-ui.js"
    ]
  );

  // Style files to inject into /index page (homepage)
  ClientSideResourceManager.addPaths(
    "index/styles",
    module,
    [
      "styles/index/data-quality-ui.css"
    ]
  );

  // Script files to inject into /project page
  ClientSideResourceManager.addPaths(
    "project/scripts",
    module,
    [
      "scripts/menu-bar-extension.js",
      "scripts/quality-alignment.js",
      "scripts/quality-cell-renderer.js",
      "scripts/dialogs/manage-rules-dialog.js",
      "scripts/dialogs/run-check-dialog.js",
      "scripts/dialogs/image-quality-tab.js",
      // "scripts/dialogs/image-annotation.js",
      "scripts/dialogs/regex-editor-dialog.js"
    ]
  );

  // Style files to inject into /project page
  ClientSideResourceManager.addPaths(
    "project/styles",
    module,
    [
      "styles/quality-alignment.css",
      "styles/dialogs/manage-rules-dialog.css",
      "styles/dialogs/run-check-dialog.css",
      "styles/dialogs/image-quality-tab.css",
      "styles/dialogs/regex-editor-dialog.css"
    ]
  );

  logger.info("Data-Quality Extension initialized successfully!");
}

/*
 * Function invoked to handle each request in a custom way.
 */
function process(path, request, response) {
  var method = request.getMethod();
  logger.trace('receiving request for ' + path);
  
  if (path == "/" || path == "") {
    var context = {};
    context.version = version;
    send(request, response, "index.vt", context);
  }
}

function send(request, response, template, context) {
  butterfly.sendTextFromTemplate(request, response, context, template, encoding, html);
}

