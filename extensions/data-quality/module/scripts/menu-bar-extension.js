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
  console.log('[menu-bar-extension] Setting up auto-launch timeout...');
  setTimeout(function() {
    console.log('[menu-bar-extension] Timeout fired, QualityAlignment defined:', typeof QualityAlignment !== 'undefined');
    if (typeof QualityAlignment !== 'undefined') {
      // Check URL hash to activate specific tab
      var hash = window.location.hash;
      console.log('[menu-bar-extension] URL hash:', hash);

      // Always use autoLaunchIfNeeded to load saved rules/results first
      // Then switch to the requested tab based on hash
      if (hash === '#quality-rules' || hash === '#quality-results') {
        // Clear hash to prevent infinite loop on refresh
        history.replaceState(null, '', window.location.pathname + window.location.search);

        // Load saved data and launch with target tab
        QualityAlignment.autoLaunchIfNeededWithTarget(hash === '#quality-results' ? 'results' : 'rules');
      } else {
        // Normal auto-launch based on existing rules/results
        console.log('[menu-bar-extension] Calling autoLaunchIfNeeded...');
        QualityAlignment.autoLaunchIfNeeded();
      }
    } else {
      console.error('[menu-bar-extension] QualityAlignment is not defined!');
    }
  }, 500);
});

