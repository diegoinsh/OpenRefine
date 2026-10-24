/*
 * Controller for Records Assets Extension
 * Runs in the Butterfly server context via Rhino
 *
 * 注册逻辑（命令 / 导入器控制器 / 客户端资源）已收敛到 Java SPI：
 * com.jiongche.extension.records.assets.RecordsAssetsExtensionSpi。
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";
var logger = Packages.org.slf4j.LoggerFactory.getLogger("records-assets-extension");

function init() {
  logger.trace("Initializing OpenRefine Records-Assets Extension...");

  Packages.com.jiongche.refine.spi.SpiBootstrap.register(module, null);
}

function process(path, request, response) {
  // No templated pages served by this extension
}
