/*
 * Menu File Path Extension
 * Adds "Extension" > "As File Path" menu to column header dropdown
 */

(function() {
  'use strict';

  /**
   * Extend column header menu with file path options
   * @param {Object} column - The column object
   * @param {Object} columnHeaderUI - The column header UI object
   * @param {Array} menu - The menu items array
   */
  function extendColumnHeaderMenu(column, columnHeaderUI, menu) {
    var columnName = column.name;
    var isFilePath = ResourceExplorer.isFilePathColumn(columnName);

    // Add separator before extension menu
    menu.push({});

    // Add "Extension" menu with "As File Path" submenu
    menu.push({
      id: "records-assets/extension",
      label: $.i18n('records.assets.menu.extension') || 'Extension',
      submenu: [
        {
          id: "records-assets/as-file-path",
          label: (isFilePath ? '✓ ' : '') + ($.i18n('records.assets.menu.asFilePath') || 'As File Path'),
          click: function() {
            var newState = ResourceExplorer.toggleFilePathColumn(columnName);
            
            // Show notification
            var message = newState 
              ? ($.i18n('records.assets.menu.filePathEnabled') || 'Column marked as file path. Click cells to explore resources.')
              : ($.i18n('records.assets.menu.filePathDisabled') || 'File path mode disabled for this column.');
            
            // Use simple alert or toast if available
            if (typeof Refine !== 'undefined' && Refine.showNotification) {
              Refine.showNotification(message);
            } else {
              console.log('[ResourceExplorer]', message);
            }
          }
        },
        {
          id: "records-assets/set-root-path",
          label: $.i18n('records.assets.menu.setRootPath') || 'Set Root Path...',
          click: function() {
            showSetRootPathDialog(columnName);
          }
        }
      ]
    });
  }

  /**
   * Show dialog to set root path for file exploration
   * @param {string} columnName - Column name
   */
  function showSetRootPathDialog(columnName) {
    var currentPath = ResourceExplorer.getRootPath(columnName);
    
    var frame = $(
      '<div class="dialog-frame">' +
        '<div class="dialog-header" id="set-root-path-header">' +
          '<span>' + ($.i18n('records.assets.menu.setRootPathTitle') || 'Set Root Path') + '</span>' +
        '</div>' +
        '<div class="dialog-body">' +
          '<p>' + ($.i18n('records.assets.menu.setRootPathDesc') || 'Enter the root path for file exploration:') + '</p>' +
          '<input type="text" class="root-path-input" style="width: 100%; padding: 8px; box-sizing: border-box;" />' +
          '<p class="dialog-hint" style="color: #666; font-size: 12px; margin-top: 8px;">' +
            ($.i18n('records.assets.menu.setRootPathHint') || 'Cell values will be treated as relative paths from this root.') +
          '</p>' +
        '</div>' +
        '<div class="dialog-footer">' +
          '<button class="button button-primary ok-button">' + ($.i18n('core-buttons/ok') || 'OK') + '</button>' +
          '<button class="button cancel-button">' + ($.i18n('core-buttons/cancel') || 'Cancel') + '</button>' +
        '</div>' +
      '</div>'
    );

    var input = frame.find('.root-path-input');
    input.val(currentPath);

    var level = DialogSystem.showDialog(frame);

    frame.find('.ok-button').on('click', function() {
      var newPath = input.val().trim();
      ResourceExplorer.setRootPath(columnName, newPath);
      DialogSystem.dismissUntil(level - 1);
    });

    frame.find('.cancel-button').on('click', function() {
      DialogSystem.dismissUntil(level - 1);
    });

    // Focus input
    input.trigger('focus');
  }

  // Register the menu extender when DataTableColumnHeaderUI is available
  function registerMenuExtender() {
    if (typeof DataTableColumnHeaderUI !== 'undefined' && 
        typeof DataTableColumnHeaderUI.extendMenu === 'function') {
      DataTableColumnHeaderUI.extendMenu(extendColumnHeaderMenu);
      console.log('[ResourceExplorer] Menu extender registered successfully');
    } else {
      // Retry after a short delay
      setTimeout(registerMenuExtender, 100);
    }
  }

  // Initialize when document is ready
  $(document).ready(function() {
    registerMenuExtender();
  });

})();

