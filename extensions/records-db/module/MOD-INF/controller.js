/*
 * Controller for Records Database Import Extension
 * 
 * This is run in the Butterfly (ie Refine) server context using the Rhino
 * Javascript interpreter.
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";
var ClientSideResourceManager = Packages.com.google.refine.ClientSideResourceManager;

var logger = Packages.org.slf4j.LoggerFactory.getLogger("records-db-extension"),
File = Packages.java.io.File,
refineServlet = Packages.com.google.refine.RefineServlet;

/*
 * Register our custom commands.
 */
function registerCommands() {
  logger.trace("Registering Records-DB Extension Commands......");
  // Commands will be added here as needed
  logger.trace("Records-DB Extension Command Registration done!!");
}

function registerOperations() {
}

function registerFunctions() {
}

/*
 * Function invoked to initialize the extension.
 */
function init() {
  logger.trace("Initializing OpenRefine Records-DB Extension...");
  logger.trace("Records-DB Extension Mount point " + module.getMountPoint());

  registerCommands();
  registerOperations();
  registerFunctions();

  // Register importer
  var IM = Packages.com.google.refine.importing.ImportingManager;
  
  IM.registerController(
    module,
    "records-db-import-controller",
    new Packages.com.google.refine.extension.records.db.RecordsDatabaseImportController()
  );

  // Script files to inject into /index page
  ClientSideResourceManager.addPaths(
    "index/scripts",
    module,
    [
      "scripts/index/records-db-import-controller.js",
      "scripts/index/records-db-source-ui.js"
    ]
  );
  
  // Style files to inject into /index page
  ClientSideResourceManager.addPaths(
    "index/styles",
    module,
    [
      "styles/records-db-import.css"
    ]
  );
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

