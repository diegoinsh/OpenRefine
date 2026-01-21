/*
 * Controller for Records Assets Extension
 * Runs in the Butterfly server context via Rhino
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";
var ClientSideResourceManager = Packages.com.google.refine.ClientSideResourceManager;
var logger = Packages.org.slf4j.LoggerFactory.getLogger("records-assets-extension");

function registerCommands() {
  logger.trace("Registering Records-Assets Extension Commands......");

  var RS = Packages.com.google.refine.RefineServlet;
  RS.registerCommand(module, "list",
      new Packages.com.google.refine.extension.records.assets.ListCommand());
  RS.registerCommand(module, "preview",
      new Packages.com.google.refine.extension.records.assets.PreviewCommand());
  RS.registerCommand(module, "file",
      new Packages.com.google.refine.extension.records.assets.FileCommand());

  logger.trace("Records-Assets Extension Command Registration done!!");
}

function registerOperations() {}
function registerFunctions() {}

function init() {
  logger.trace("Initializing OpenRefine Records-Assets Extension...");
  logger.trace("Records-Assets Extension Mount point " + module.getMountPoint());

  registerCommands();
  registerOperations();
  registerFunctions();

  var IM = Packages.com.google.refine.importing.ImportingManager;
  var RecordsAssetsController = Packages.com.google.refine.extension.records.assets.RecordsAssetsController;

  IM.registerController(
    module,
    "records-assets-controller",
    new RecordsAssetsController()
  );

  // Inject resources on project page only (for assets browsing UI)
  ClientSideResourceManager.addPaths(
    "project/scripts",
    module,
    [
      "scripts/index/records-assets-controller.js",
      "scripts/project/resource-explorer.js",
      "scripts/project/menu-file-path.js",
      "scripts/project/file-path-cell-renderer.js",
      "scripts/project/resource-explorer-panel.js",
      "scripts/project/file-preview-dialog.js"
    ]
  );

  ClientSideResourceManager.addPaths(
    "project/styles",
    module,
    [
      "styles/records-assets.css",
      "styles/project/file-path-cell.css",
      "styles/project/resource-explorer.css",
      "styles/project/dialogs.css"
    ]
  );
}

function process(path, request, response) {
  // No templated pages served by this extension
}

