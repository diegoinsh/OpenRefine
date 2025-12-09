/*
 * File Path Cell Renderer
 * Renders cells in file path columns as clickable links
 */

var FilePathCellRenderer = {};

(function() {
  'use strict';

  /**
   * FilePathCellRenderer class
   * Implements the cell renderer interface for file path columns
   */
  FilePathCellRenderer = function() {};

  /**
   * Render a cell as a clickable file path link
   * @param {number} rowIndex - Row index
   * @param {number} cellIndex - Cell index  
   * @param {Object} cell - Cell data
   * @param {Object} cellUI - Cell UI controller
   * @returns {HTMLElement|null} Rendered element or null if not applicable
   */
  FilePathCellRenderer.prototype.render = function(rowIndex, cellIndex, cell, cellUI) {
    // Get column info
    var column = Refine.cellIndexToColumn(cellIndex);
    if (!column) {
      return null;
    }

    // Check if this column is marked as file path
    if (!ResourceExplorer.isFilePathColumn(column.name)) {
      return null;
    }

    // Check if cell has a value
    if (!cell || cell.v === null || cell.v === undefined || cell.v === '') {
      return null;
    }

    var value = cell.v;
    var self = this;

    // Create the clickable element
    var container = document.createElement('div');
    container.className = 'file-path-cell-container';

    var link = document.createElement('a');
    link.href = 'javascript:void(0)';
    link.className = 'file-path-cell-link';
    link.title = $.i18n('records.assets.cell.clickToExplore') || 'Click to explore resource';
    
    // Truncate long paths for display
    var displayValue = value;
    if (displayValue.length > 50) {
      displayValue = '...' + displayValue.substring(displayValue.length - 47);
    }
    link.textContent = displayValue;

    // Add click handler
    link.addEventListener('click', function(event) {
      event.preventDefault();
      event.stopPropagation();
      self._handleCellClick(column.name, value, rowIndex);
    });

    container.appendChild(link);
    return container;
  };

  /**
   * Handle cell click - open resource explorer
   * @param {string} columnName - Column name
   * @param {string} filePath - File path value from cell
   * @param {number} rowIndex - Row index
   */
  FilePathCellRenderer.prototype._handleCellClick = function(columnName, filePath, rowIndex) {
    var rootPath = ResourceExplorer.getRootPath(columnName);
    
    console.log('[FilePathCellRenderer] Opening resource explorer:', {
      columnName: columnName,
      filePath: filePath,
      rootPath: rootPath,
      rowIndex: rowIndex
    });

    // Show resource explorer panel
    if (typeof ResourceExplorerPanel !== 'undefined') {
      ResourceExplorerPanel.show(rootPath, filePath);
    } else {
      // Fallback: show simple preview dialog
      this._showSimplePreview(rootPath, filePath);
    }
  };

  /**
   * Show simple preview dialog (fallback)
   * @param {string} rootPath - Root path
   * @param {string} filePath - Relative file path
   */
  FilePathCellRenderer.prototype._showSimplePreview = function(rootPath, filePath) {
    var fullPath = rootPath ? (rootPath + '/' + filePath) : filePath;
    
    // Request file preview from API
    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: {
        root: rootPath,
        path: filePath
      },
      dataType: 'json',
      success: function(data) {
        if (data.status === 'ok' || data.status === 'success') {
          FilePathCellRenderer._showPreviewDialog(data, fullPath);
        } else {
          alert(data.message || $.i18n('records.assets.preview.error') || 'Error loading file');
        }
      },
      error: function(xhr, status, error) {
        console.error('[FilePathCellRenderer] Preview error:', error);
        alert($.i18n('records.assets.errors.connectionError') || 'Connection error');
      }
    });
  };

  /**
   * Show preview dialog
   * @param {Object} data - Preview data from API
   * @param {string} fullPath - Full file path
   */
  FilePathCellRenderer._showPreviewDialog = function(data, fullPath) {
    var frame = $(
      '<div class="dialog-frame file-preview-dialog">' +
        '<div class="dialog-header" id="file-preview-header">' +
          '<span class="file-preview-title">' + (data.name || fullPath) + '</span>' +
        '</div>' +
        '<div class="dialog-body file-preview-body"></div>' +
        '<div class="dialog-footer">' +
          '<button class="button cancel-button">' + ($.i18n('core-buttons/close') || 'Close') + '</button>' +
        '</div>' +
      '</div>'
    );

    var body = frame.find('.file-preview-body');
    
    // Render preview based on type
    if (data.previewType === 'image' && data.preview) {
      var img = $('<img>').attr('src', data.preview).css({
        'max-width': '100%',
        'max-height': '500px'
      });
      body.append(img);
    } else if (data.previewType === 'text' && data.preview) {
      var pre = $('<pre>').text(data.preview).css({
        'max-height': '400px',
        'overflow': 'auto'
      });
      body.append(pre);
    } else {
      body.append('<p>' + ($.i18n('records.assets.preview.noPreview') || 'Cannot preview this file') + '</p>');
    }

    var level = DialogSystem.showDialog(frame);

    frame.find('.cancel-button').on('click', function() {
      DialogSystem.dismissUntil(level - 1);
    });
  };

  /**
   * Register the renderer to CellRendererRegistry
   */
  function registerRenderer() {
    if (typeof CellRendererRegistry !== 'undefined') {
      // Insert at the beginning to have higher priority
      CellRendererRegistry.renderers.unshift({
        name: 'file-path',
        renderer: new FilePathCellRenderer()
      });
      console.log('[FilePathCellRenderer] Renderer registered successfully');
    } else {
      setTimeout(registerRenderer, 100);
    }
  }

  $(document).ready(function() {
    registerRenderer();
  });

})();

