/*
 * Controller for Files extension.
 *
 * This is run in the Butterfly (ie Refine) server context using the Rhino
 * Javascript interpreter.
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";

// Register our Javascript (and CSS) files to get loaded
var ClientSideResourceManager = Packages.com.google.refine.ClientSideResourceManager;

/*
 * Function invoked to initialize the extension.
 */
function init() {

  // Register importer and exporter
  var IM = Packages.com.google.refine.importing.ImportingManager;

  IM.registerController(
    module,
    "files-importing-controller",
    new Packages.org.openrefine.extensions.files.importer.FilesImportingController()
  );

  // Register batch title extraction command
  var RS = Packages.com.google.refine.RefineServlet;
  RS.registerCommand(
    module,
    "batch-extraction",
    new Packages.org.openrefine.extensions.files.importer.BatchExtractionCommand()
  );

  // Script files to inject into /index page
  ClientSideResourceManager.addPaths(
    "index/scripts",
    module,
    [
      "scripts/vendor/tiny-pinyin.js",
      "scripts/index/files-importing-controller.js",
      "scripts/index/import-from-local-dir.js"
    ]
  );


  // Style files to inject into /index page
  ClientSideResourceManager.addPaths(
    "index/styles",
    module,
    [
      "styles/files-importing-controller.css"
    ]
  );

  // Script files to inject into /project page
  ClientSideResourceManager.addPaths(
    "project/scripts",
    module,
    [
      "scripts/project-injection.js",
      "scripts/project/file-view-panel.js"
    ]
  );

  // Style files to inject into /project page
  ClientSideResourceManager.addPaths(
    "project/styles",
    module,
    [
      "styles/files-importing-controller.css",
      "styles/file-view-panel.css"
    ]
  );

}
