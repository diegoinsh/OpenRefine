/*
 * Records-DB Extension - Project Menu Extensions
 * Adds export menu items for bound assets
 */

(function() {
  // Initialize i18n for records-db module
  var i18nInitialized = false;
  var initI18n = function(callback) {
    if (i18nInitialized) {
      callback();
      return;
    }
    // Load records-db translations
    if (typeof I18NUtil !== 'undefined') {
      I18NUtil.init("records-db");
      i18nInitialized = true;
    }
    callback();
  };

  // Wait for ExporterManager to be available
  var checkExporterManager = function() {
    if (typeof ExporterManager !== 'undefined' && ExporterManager.MenuItems) {
      initI18n(registerExportMenuItem);
    } else {
      setTimeout(checkExporterManager, 100);
    }
  };

  var registerExportMenuItem = function() {
    // Add a spacer before our menu item to separate it as a standalone group
    ExporterManager.MenuItems.push({});  // spacer

    // Add our export menu item
    ExporterManager.MenuItems.push({
      "id": "records-db/export-bound-assets",
      "label": $.i18n("records-db-export/dialog-title"),
      "click": function() {
        new ExportBoundAssetsDialog();
      }
    });
  };
  
  // Start checking when DOM is ready
  $(document).ready(function() {
    checkExporterManager();
  });
})();

