/*
 * Controller for Records Database Import Extension
 * 
 * This is run in the Butterfly (ie Refine) server context using the Rhino
 * Javascript interpreter.
 *
 * 注册逻辑（命令 / 导入器控制器 / overlay 模型 / 客户端资源）已收敛到 Java SPI：
 * com.jiongche.extension.records.db.RecordsDBExtensionSpi。
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";

var logger = Packages.org.slf4j.LoggerFactory.getLogger("records-db-extension");

/*
 * Function invoked to initialize the extension.
 */
function init() {
  logger.trace("Initializing OpenRefine Records-DB Extension...");

  Packages.com.jiongche.refine.spi.SpiBootstrap.register(module, null);
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
