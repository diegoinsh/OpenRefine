/*
 * File Preview Dialog
 * Fixed-position dialog for previewing file contents (images, PDFs, text)
 * Positioned at right edge, aligned with rightPanelDiv
 */

var FilePreviewDialog = {};

(function() {
  'use strict';

  FilePreviewDialog._currentDialog = null;
  FilePreviewDialog._zoomLevel = 1;
  FilePreviewDialog._originalPaddingRight = null;

  /**
   * Show file preview dialog
   * @param {string} rootPath - Root path
   * @param {string} filePath - File path
   * @param {Object} fileItem - File item data
   */
  FilePreviewDialog.show = function(rootPath, filePath, fileItem) {
    // Close existing dialog
    if (FilePreviewDialog._currentDialog) {
      FilePreviewDialog._currentDialog.remove();
    }

    // Reset zoom level
    FilePreviewDialog._zoomLevel = 1;

    var dialog = $('<div>')
      .addClass('file-preview-dialog-float')
      .appendTo('body');

    // Header
    var header = $('<div>')
      .addClass('file-preview-float-header')
      .appendTo(dialog);

    var fileName = fileItem ? fileItem.name : filePath.split('/').pop();
    $('<span>')
      .addClass('file-preview-float-title')
      .text(fileName)
      .attr('title', filePath)
      .appendTo(header);

    $('<button>')
      .addClass('file-preview-float-close-btn')
      .html('&times;')
      .on('click', function() {
        FilePreviewDialog.close();
      })
      .appendTo(header);

    // Content container
    var content = $('<div>')
      .addClass('file-preview-float-content')
      .appendTo(dialog);

    content.html('<div class="loading">' + ($.i18n('records.assets.preview.loading') || 'Loading...') + '</div>');

    // Footer with actions
    var footer = $('<div>')
      .addClass('file-preview-float-footer')
      .appendTo(dialog);

    // Position dialog: right edge of screen, aligned with rightPanelDiv
    FilePreviewDialog._positionDialog(dialog);

    // Adjust data table container to allow scrolling past the preview panel
    FilePreviewDialog._adjustDataTablePadding(dialog);

    // Make draggable by header
    FilePreviewDialog._makeDraggable(dialog, header);

    // Handle window resize (only if not dragged)
    dialog.data('dragged', false);
    $(window).on('resize.filePreview', function() {
      if (!dialog.data('dragged')) {
        FilePreviewDialog._positionDialog(dialog);
      }
    });

    // Handle ESC key to close dialog
    $(document).on('keydown.filePreview', function(e) {
      if (e.keyCode === 27) { // ESC key
        e.preventDefault();
        FilePreviewDialog.close();
      }
    });

    FilePreviewDialog._currentDialog = dialog;

    // Load file preview
    FilePreviewDialog._loadPreview(rootPath, filePath, content, footer);
  };

  /**
   * Close the file preview dialog
   */
  FilePreviewDialog.close = function() {
    if (FilePreviewDialog._currentDialog) {
      FilePreviewDialog._currentDialog.remove();
      FilePreviewDialog._currentDialog = null;
      $(window).off('resize.filePreview');
      $(document).off('keydown.filePreview');
      $(document).off('mousemove.filePreviewDrag');
      $(document).off('mouseup.filePreviewDrag');

      // Restore data table container padding
      FilePreviewDialog._restoreDataTablePadding();
    }
  };

  /**
   * Adjust data table container padding to allow scrolling past the preview panel
   */
  FilePreviewDialog._adjustDataTablePadding = function(dialog) {
    var dataTableContainer = $('.data-table-container');
    if (dataTableContainer.length === 0) return;

    // Save original padding-right
    if (FilePreviewDialog._originalPaddingRight === null) {
      FilePreviewDialog._originalPaddingRight = dataTableContainer.css('padding-right') || '0px';
    }

    // Get dialog width
    var dialogWidth = dialog.outerWidth() || 700;

    // Add padding-right to data table container so content can scroll past the preview
    dataTableContainer.css('padding-right', (dialogWidth + 10) + 'px');
  };

  /**
   * Restore data table container padding when preview is closed
   */
  FilePreviewDialog._restoreDataTablePadding = function() {
    var dataTableContainer = $('.data-table-container');
    if (dataTableContainer.length === 0) return;

    // Restore original padding
    if (FilePreviewDialog._originalPaddingRight !== null) {
      dataTableContainer.css('padding-right', FilePreviewDialog._originalPaddingRight);
      FilePreviewDialog._originalPaddingRight = null;
    }
  };

  /**
   * Position dialog: right edge of screen, same height as rightPanelDiv
   */
  FilePreviewDialog._positionDialog = function(dialog) {
    var rightPanel = $('#right-panel');
    var viewportWidth = $(window).width();

    // Get rightPanel position and dimensions
    var panelTop = 0;
    var panelHeight = $(window).height();

    if (rightPanel.length > 0) {
      var offset = rightPanel.offset();
      panelTop = offset.top;
      panelHeight = rightPanel.outerHeight();
    }

    // Dialog width - responsive
    var dialogWidth = Math.min(700, viewportWidth * 0.4);

    dialog.css({
      top: panelTop + 'px',
      right: '0px',
      left: 'auto',
      width: dialogWidth + 'px',
      height: panelHeight + 'px'
    });
  };

  /**
   * Make element draggable by header
   */
  FilePreviewDialog._makeDraggable = function(element, handle) {
    var isDragging = false;
    var startX, startY, startLeft, startTop;

    handle.css('cursor', 'move');

    handle.on('mousedown', function(e) {
      if ($(e.target).is('button')) return;
      isDragging = true;
      startX = e.clientX;
      startY = e.clientY;

      // Get current position
      var offset = element.offset();
      startLeft = offset.left;
      startTop = offset.top;

      e.preventDefault();
    });

    $(document).on('mousemove.filePreviewDrag', function(e) {
      if (!isDragging) return;

      var newLeft = startLeft + e.clientX - startX;
      var newTop = startTop + e.clientY - startY;

      element.css({
        left: newLeft + 'px',
        top: newTop + 'px',
        right: 'auto'
      });

      // Mark as dragged so resize won't reset position
      element.data('dragged', true);
    });

    $(document).on('mouseup.filePreviewDrag', function() {
      isDragging = false;
    });
  };

  /**
   * Load file preview
   */
  FilePreviewDialog._loadPreview = function(rootPath, filePath, container, footer) {
    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: { root: rootPath, path: filePath },
      dataType: 'json',
      success: function(data) {
        if (data.status === 'ok' || data.status === 'success') {
          FilePreviewDialog._renderPreview(container, data, rootPath, filePath);
          FilePreviewDialog._renderFooter(footer, data, rootPath, filePath);
        } else {
          container.html('<div class="error">' + (data.message || 'Error') + '</div>');
        }
      },
      error: function() {
        container.html('<div class="error">' + ($.i18n('records.assets.errors.connectionError') || 'Error') + '</div>');
      }
    });
  };

  /**
   * Render file preview content
   */
  FilePreviewDialog._renderPreview = function(container, data, rootPath, filePath) {
    container.empty();

    var previewType = data.previewType || 'unknown';
    var preview = data.preview;

    // Store preview type for footer rendering
    container.data('previewType', previewType);

    if (previewType === 'image' && preview) {
      // Image preview - full container size
      var imgContainer = $('<div>').addClass('image-preview-container').appendTo(container);
      var img = $('<img>')
        .attr('src', preview)
        .addClass('preview-image')
        .appendTo(imgContainer);

      // Store image reference for zoom controls
      container.data('previewImage', img);

    } else if (previewType === 'pdf') {
      // PDF preview using iframe with direct file endpoint
      var pdfContainer = $('<div>').addClass('pdf-preview-container').appendTo(container);

      // Build URL to file endpoint
      var pdfUrl = '/command/records-assets/file?root=' + encodeURIComponent(rootPath) +
                   '&path=' + encodeURIComponent(filePath);

      $('<iframe>')
        .attr('src', pdfUrl)
        .addClass('pdf-iframe')
        .appendTo(pdfContainer);

    } else if (previewType === 'text' && preview) {
      // Text preview
      var textContainer = $('<div>').addClass('text-preview-container').appendTo(container);
      $('<pre>')
        .addClass('text-preview')
        .text(preview)
        .appendTo(textContainer);

    } else {
      // Unknown type or no preview
      container.html(
        '<div class="no-preview">' +
        '<div class="file-icon">📄</div>' +
        '<div>' + ($.i18n('records.assets.preview.noPreview') || 'Cannot preview this file') + '</div>' +
        '</div>'
      );
    }
  };

  /**
   * Render footer with file info, zoom controls (for images), and actions
   */
  FilePreviewDialog._renderFooter = function(footer, data, rootPath, filePath) {
    footer.empty();

    // Left section: file info
    var info = $('<div>').addClass('file-info').appendTo(footer);

    if (data.size) {
      var sizeStr = FilePreviewDialog._formatFileSize(data.size);
      $('<span>').text(($.i18n('records.assets.list.size') || 'Size') + ': ' + sizeStr).appendTo(info);
    }

    if (data.mimeType) {
      $('<span>').text(' | ' + data.mimeType).appendTo(info);
    }

    // Action buttons container
    var actions = $('<div>').addClass('file-actions').appendTo(footer);

    // Zoom controls for images (in footer)
    var previewType = data.previewType || 'unknown';
    if (previewType === 'image') {
      var zoomControls = $('<div>').addClass('zoom-controls').appendTo(actions);

      $('<button>').addClass('button zoom-btn').text('−').on('click', function() {
        FilePreviewDialog._zoomLevel = Math.max(0.1, FilePreviewDialog._zoomLevel - 0.2);
        FilePreviewDialog._applyZoom();
      }).appendTo(zoomControls);

      $('<button>').addClass('button zoom-btn').text('100%').on('click', function() {
        FilePreviewDialog._zoomLevel = 1;
        FilePreviewDialog._applyZoom();
      }).appendTo(zoomControls);

      $('<button>').addClass('button zoom-btn').text('+').on('click', function() {
        FilePreviewDialog._zoomLevel = Math.min(5, FilePreviewDialog._zoomLevel + 0.2);
        FilePreviewDialog._applyZoom();
      }).appendTo(zoomControls);
    }

    // Download button
    $('<button>')
      .addClass('button')
      .text($.i18n('records.assets.preview.download') || 'Download')
      .on('click', function() {
        window.open('/command/records-assets/download?root=' + encodeURIComponent(rootPath) + '&path=' + encodeURIComponent(filePath), '_blank');
      })
      .appendTo(actions);

    // Close button
    $('<button>')
      .addClass('button')
      .text($.i18n('core-buttons/close') || 'Close')
      .on('click', function() {
        FilePreviewDialog.close();
      })
      .appendTo(actions);
  };

  /**
   * Apply zoom level to preview image
   */
  FilePreviewDialog._applyZoom = function() {
    if (FilePreviewDialog._currentDialog) {
      var img = FilePreviewDialog._currentDialog.find('.preview-image');
      if (img.length) {
        img.css('transform', 'scale(' + FilePreviewDialog._zoomLevel + ')');
      }
    }
  };

  /**
   * Format file size to human readable string
   */
  FilePreviewDialog._formatFileSize = function(bytes) {
    if (bytes === 0) return '0 B';
    var k = 1024;
    var sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

})();

