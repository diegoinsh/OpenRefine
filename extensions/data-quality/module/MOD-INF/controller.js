/*
 * Controller for Data Quality Extension
 *
 * This is run in the Butterfly (ie Refine) server context using the Rhino
 * Javascript interpreter.
 *
 * 注册逻辑已收敛到 Java SPI：com.jiongche.extension.quality.DataQualityExtensionSpi
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";

var logger = Packages.org.slf4j.LoggerFactory.getLogger("data-quality-extension");

/*
 * Function invoked to initialize the extension.
 */
function init() {
  logger.info("Initializing JinRefine Data-Quality Extension...");
  logger.trace("Data-Quality Extension Mount point " + module.getMountPoint());

  Packages.com.jiongche.refine.spi.SpiBootstrap.register(
    module,
    "com.jiongche.extension.quality.DataQualityExtensionSpi"
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
