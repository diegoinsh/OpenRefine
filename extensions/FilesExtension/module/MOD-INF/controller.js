/*
 * Controller for Files extension.
 *
 * This is run in the Butterfly (ie Refine) server context using the Rhino
 * Javascript interpreter.
 *
 * 注册逻辑已收敛到 Java SPI：com.jiongche.extensions.files.FilesExtensionSpi
 */

var html = "text/html";
var encoding = "UTF-8";
var version = "0.1";

/*
 * Function invoked to initialize the extension.
 */
function init() {
  Packages.com.jiongche.refine.spi.SpiBootstrap.register(
    module,
    null
  );
}
