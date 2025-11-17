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

  // Importer registration is now performed in RecordsDBModuleImpl.init()
  // on the server side to avoid Rhino class loading issues.
  // This script is only responsible for registering client-side resources.

  // Script files to inject into /index page
  ClientSideResourceManager.addPaths(
    "index/scripts",
    module,
    [
      "scripts/index/records-db-filter-builder.js",
      "scripts/index/records-db-wizard-steps.js",
      "scripts/index/records-db-import-controller.js"
    ]
  );
  
  // Style files to inject into /index page
  ClientSideResourceManager.addPaths(
    "index/styles",
    module,
    [
      "styles/records-db-wizard.css"
    ]
  );

  // DEBUG: dump current index/styles registrations to locate wrong mount origins
  try {
    var styles = ClientSideResourceManager.getPaths("index/styles");
    for (var key in styles) {
      if (styles.hasOwnProperty(key)) {
        var qp = styles[key];
        var modName = (qp.module && typeof qp.module.getName === 'function') ? qp.module.getName() : 'unknown';
        logger.warn("[records-db:init] index/styles => " + qp.fullPath + " (module=" + modName + ")");
      }
    }
  } catch (e) {
    logger.warn("[records-db:init] Failed to enumerate index/styles: " + e);
  }
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

