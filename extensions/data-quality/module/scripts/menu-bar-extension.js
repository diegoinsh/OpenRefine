/**
 * Data Quality Extension - Menu Bar Extension
 *
 * Registers the "Data Quality" menu in the ExtensionBar
 */

I18NUtil.init("data-quality");

// Extend the ExtensionBar with Data Quality menu
$(function() {
  ExtensionBar.MenuItems.push({
    "id": "data-quality",
    "label": $.i18n('data-quality-extension/menu-label'),
    "submenu": [
      {
        id: "data-quality/edit-rules",
        label: $.i18n('data-quality-extension/edit-rules'),
        click: function() {
          QualityAlignment.launch(false);
        }
      },
      {
        id: "data-quality/manage-rules",
        label: $.i18n('data-quality-extension/manage-rules'),
        click: function() {
          new ManageRulesDialog();
        }
      },
      {},  // separator
      {
        id: "data-quality/run-check",
        label: $.i18n('data-quality-extension/run-check'),
        click: function() {
          new RunCheckDialog();
        }
      }
    ]
  });

  // Auto-launch quality alignment tabs if rules or results exist
  // Use a small delay to ensure the UI is fully loaded
  setTimeout(function() {
    if (typeof QualityAlignment !== 'undefined') {
      QualityAlignment.autoLaunchIfNeeded();
    }
  }, 500);
});

