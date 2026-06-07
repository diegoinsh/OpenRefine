/*
 * File View Panel
 * Inline panel within rightPanelDiv for previewing resource files associated with data rows.
 * Positioned as a 700px-wide div to the right of viewPanelDiv/summaryBarDiv/quality-rules-panel.
 */

var FileViewPanel = {};

(function() {
  'use strict';

  FileViewPanel._panel = null;
  FileViewPanel._isVisible = false;
  FileViewPanel._currentRow = null;
  FileViewPanel._currentFiles = [];
  FileViewPanel._currentFileIndex = 0;
  FileViewPanel._zoomLevel = 1;
  FileViewPanel._currentOffsetX = 0;
  FileViewPanel._currentOffsetY = 0;
  FileViewPanel._isDraggingImage = false;
  FileViewPanel._dragStartX = 0;
  FileViewPanel._dragStartY = 0;
  FileViewPanel._dragNamespace = '.fileViewDrag_' + Math.random().toString(36).substr(2, 9);
  FileViewPanel._keyNamespace = '.fileViewKey_' + Math.random().toString(36).substr(2, 9);
  FileViewPanel.PANEL_WIDTH = 700;

  FileViewPanel.show = function(rowIndex) {
    if (typeof QualityAlignment === 'undefined' || !QualityAlignment._resourceConfig) {
      return;
    }

    var resourceConfig = QualityAlignment._resourceConfig;
    if (!resourceConfig.pathFields || resourceConfig.pathFields.length === 0) {
      return;
    }

    var resourcePath = FileViewPanel._buildResourcePath(rowIndex);
    if (!resourcePath) {
      return;
    }

    FileViewPanel._currentRow = rowIndex;
    FileViewPanel._currentFileIndex = 0;
    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;

    if (!FileViewPanel._panel) {
      FileViewPanel._createPanel();
    }

    FileViewPanel._panel.show();
    FileViewPanel._isVisible = true;

    var content = FileViewPanel._panel.find('.file-view-content');
    var title = FileViewPanel._panel.find('.file-view-title');
    title.text($.i18n('data-quality-extension/file-view-loading') || 'Loading...');
    content.html('<div class="file-view-loading">' + ($.i18n('data-quality-extension/file-view-loading') || 'Loading...') + '</div>');

    FileViewPanel._adjustRightPanel();

    FileViewPanel._fetchFilesForResourcePath(resourcePath, function(files, error) {
      if (!files || files.length === 0) {
        var content = FileViewPanel._panel.find('.file-view-content');
        var title = FileViewPanel._panel.find('.file-view-title');
        var footer = FileViewPanel._panel.find('.file-view-footer');
        title.text($.i18n('data-quality-extension/file-view-title') || 'File Preview');
        footer.empty();
        content.html(
          '<div class="file-view-no-preview">' +
          '<div class="file-view-no-preview-icon">📂</div>' +
          '<div>' + (error || $.i18n('data-quality-extension/file-view-no-resource') || 'No resource files') + '</div>' +
          '<div style="font-size:11px;color:#999;margin-top:8px;word-break:break-all;">' + resourcePath + '</div>' +
          '</div>'
        );
        FileViewPanel._panel.focus();
        return;
      }

      FileViewPanel._currentFiles = files;
      FileViewPanel._renderThumbnails();
      FileViewPanel._loadCurrentFile();
      FileViewPanel._panel.focus();
    });

    FileViewPanel._panel.off('keydown' + FileViewPanel._keyNamespace).on('keydown' + FileViewPanel._keyNamespace, FileViewPanel._handleKeyDown);
  };

  FileViewPanel.hide = function() {
    if (FileViewPanel._panel) {
      FileViewPanel._panel.hide();
    }
    FileViewPanel._isVisible = false;
    FileViewPanel._currentRow = null;
    FileViewPanel._currentFiles = [];
    FileViewPanel._currentFileIndex = 0;

    FileViewPanel._restoreRightPanel();

    FileViewPanel._panel.off(FileViewPanel._keyNamespace);
  };

  FileViewPanel.toggle = function(rowIndex) {
    if (FileViewPanel._isVisible && FileViewPanel._currentRow === rowIndex) {
      FileViewPanel.hide();
    } else {
      FileViewPanel.show(rowIndex);
    }
  };

  FileViewPanel.isVisible = function() {
    return FileViewPanel._isVisible;
  };

  FileViewPanel._createPanel = function() {
    var panel = $('<div>')
      .attr('id', 'file-view-panel')
      .addClass('file-view-panel')
      .attr('tabindex', '0');

    var header = $('<div>')
      .addClass('file-view-header')
      .appendTo(panel);

    $('<span>')
      .addClass('file-view-title')
      .appendTo(header);

    $('<button>')
      .addClass('file-view-close-btn')
      .html('&times;')
      .on('click', function() {
        FileViewPanel.hide();
      })
      .appendTo(header);

    var body = $('<div>')
      .addClass('file-view-body')
      .appendTo(panel);

    var mainArea = $('<div>')
      .addClass('file-view-main')
      .appendTo(body);

    var content = $('<div>')
      .addClass('file-view-content')
      .appendTo(mainArea);

    var footer = $('<div>')
      .addClass('file-view-footer')
      .appendTo(mainArea);

    var thumbArea = $('<div>')
      .addClass('file-view-thumbnails')
      .appendTo(body);

    var thumbList = $('<div>')
      .addClass('file-view-thumb-list')
      .appendTo(thumbArea);

    var rightPanel = $('#right-panel');
    if (rightPanel.length > 0) {
      panel.appendTo(rightPanel);
    }

    FileViewPanel._panel = panel;
    panel.hide();
  };

  FileViewPanel._buildResourcePath = function(rowIndex) {
    var resourceConfig = QualityAlignment._resourceConfig;
    console.log('[FileViewPanel] _buildResourcePath called, rowIndex:', rowIndex);
    console.log('[FileViewPanel] resourceConfig:', JSON.stringify(resourceConfig));

    var rows = theProject.rowModel.rows;

    var row = null;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].i === rowIndex) {
        row = rows[i];
        break;
      }
    }
    if (!row) {
      console.log('[FileViewPanel] row not found for rowIndex:', rowIndex);
      return null;
    }

    var cells = row.cells;

    var basePath = resourceConfig.basePath || '';
    var pathFields = resourceConfig.pathFields || [];
    var pathMode = resourceConfig.pathMode || 'separator';
    var separator = resourceConfig.separator || QualityAlignment._getPathSeparator();
    var template = resourceConfig.template || '';

    console.log('[FileViewPanel] basePath:', basePath, 'pathFields:', pathFields, 'pathMode:', pathMode, 'template:', template);

    var fieldValues = [];
    var columns = theProject.columnModel.columns;
    pathFields.forEach(function(fieldName) {
      var cellIndex = -1;
      for (var c = 0; c < columns.length; c++) {
        if (columns[c].name === fieldName) {
          cellIndex = columns[c].cellIndex;
          break;
        }
      }
      console.log('[FileViewPanel] fieldName:', fieldName, 'cellIndex:', cellIndex, 'cells.length:', cells.length);
      var cell = (cellIndex >= 0 && cellIndex < cells.length) ? cells[cellIndex] : null;
      var value = (cell && cell.v !== undefined && cell.v !== null) ? String(cell.v).trim() : '';
      console.log('[FileViewPanel] cell:', cell, 'value:', value);
      if (value) {
        fieldValues.push(value);
      }
    });

    console.log('[FileViewPanel] fieldValues:', fieldValues);

    if (fieldValues.length === 0) {
      return null;
    }

    var resourcePath;
    if (pathMode === 'template' && template) {
      resourcePath = template;
      for (var fi = 0; fi < fieldValues.length; fi++) {
        resourcePath = resourcePath.split('{' + fi + '}').join(fieldValues[fi]);
      }
    } else {
      resourcePath = fieldValues.join(separator);
    }

    if (basePath) {
      if (!basePath.endsWith('/') && !basePath.endsWith('\\') && !resourcePath.startsWith('/') && !resourcePath.startsWith('\\')) {
        resourcePath = basePath + separator + resourcePath;
      } else {
        resourcePath = basePath + resourcePath;
      }
    }

    resourcePath = resourcePath.replace(/\\/g, '/');

    return resourcePath;
  };

  FileViewPanel._fetchFilesForResourcePath = function(resourcePath, callback) {
    $.ajax({
      url: '/command/records-assets/list',
      type: 'GET',
      data: { root: '', path: resourcePath, page: 1, pageSize: 100 },
      dataType: 'json',
      success: function(data) {
        var files = [];
        if ((data.status === 'ok' || data.status === 'success') && data.items) {
          var fullPath = data.fullPath || resourcePath;
          data.items.forEach(function(item) {
            if (!item.isDirectory) {
              files.push({
                name: item.name,
                path: fullPath + '/' + item.name,
                rootPath: fullPath,
                size: item.size || 0,
                mimeType: item.mimeType || ''
              });
            }
          });
          callback(files, null);
        } else {
          callback([], data.message || 'Error');
        }
      },
      error: function() {
        callback([], $.i18n('data-quality-extension/file-view-error') || 'Error');
      }
    });
  };

  FileViewPanel._renderThumbnails = function() {
    var thumbList = FileViewPanel._panel.find('.file-view-thumb-list');
    thumbList.empty();

    var thumbArea = FileViewPanel._panel.find('.file-view-thumbnails');

    if (FileViewPanel._currentFiles.length <= 1) {
      thumbArea.hide();
      return;
    }

    thumbArea.show();

    FileViewPanel._currentFiles.forEach(function(file, index) {
      var thumb = $('<div>')
        .addClass('file-view-thumb-item')
        .attr('data-index', index)
        .appendTo(thumbList);

      if (index === FileViewPanel._currentFileIndex) {
        thumb.addClass('active');
      }

      var ext = file.name.split('.').pop().toLowerCase();
      var isImage = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp', 'ico', 'tiff'].indexOf(ext) !== -1;

      if (isImage) {
        var thumbImg = $('<img>')
          .addClass('file-view-thumb-img')
          .attr('alt', file.name)
          .css({ width: '100%', height: 'auto', display: 'block' })
          .appendTo(thumb);

        $.ajax({
          url: '/command/records-assets/preview',
          type: 'GET',
          data: { root: file.rootPath, path: file.name, thumbnail: 'true' },
          dataType: 'json',
          success: function(data) {
            if ((data.status === 'ok' || data.status === 'success') && data.previewType === 'image' && data.preview) {
              thumbImg.attr('src', data.preview);
            } else {
              thumbImg.replaceWith(FileViewPanel._getFileIconHtml(file.name));
            }
          },
          error: function() {
            thumbImg.replaceWith(FileViewPanel._getFileIconHtml(file.name));
          }
        });
      } else {
        thumb.append(FileViewPanel._getFileIconHtml(file.name));
      }

      $('<div>')
        .addClass('file-view-thumb-name')
        .text(file.name)
        .attr('title', file.name)
        .appendTo(thumb);

      thumb.on('click', function() {
        FileViewPanel._currentFileIndex = index;
        FileViewPanel._zoomLevel = 1;
        FileViewPanel._currentOffsetX = 0;
        FileViewPanel._currentOffsetY = 0;
        FileViewPanel._loadCurrentFile();
        FileViewPanel._updateThumbnailSelection();
      });
    });
  };

  FileViewPanel._getFileIconHtml = function(filename) {
    var ext = filename.split('.').pop().toLowerCase();
    var basePath = '/images/extensions/';
    var iconMap = {
      'pdf': 'file-pdf.svg',
      'doc': 'file-word.svg', 'docx': 'file-word.svg',
      'xls': 'file-excel.svg', 'xlsx': 'file-excel.svg', 'csv': 'file-excel.svg',
      'txt': 'file-text.svg', 'md': 'file-text.svg', 'json': 'file-text.svg',
      'xml': 'file-text.svg', 'html': 'file-text.svg', 'htm': 'file-text.svg',
      'zip': 'file-zipper.svg', 'rar': 'file-zipper.svg', '7z': 'file-zipper.svg',
      'mp4': 'file-video.svg', 'avi': 'file-video.svg', 'mov': 'file-video.svg',
      'mp3': 'file-audio.svg', 'wav': 'file-audio.svg', 'flac': 'file-audio.svg',
      'jpg': 'file-image.svg', 'jpeg': 'file-image.svg', 'png': 'file-image.svg',
      'gif': 'file-image.svg', 'bmp': 'file-image.svg', 'svg': 'file-image.svg',
      'webp': 'file-image.svg', 'tiff': 'file-image.svg', 'ico': 'file-image.svg'
    };
    var iconFile = iconMap[ext] || 'file-text.svg';
    return '<img src="' + basePath + iconFile + '" class="file-view-thumb-icon" alt="" />';
  };

  FileViewPanel._updateThumbnailSelection = function() {
    FileViewPanel._panel.find('.file-view-thumb-item').removeClass('active');
    FileViewPanel._panel.find('.file-view-thumb-item[data-index="' + FileViewPanel._currentFileIndex + '"]').addClass('active');
  };

  FileViewPanel._loadCurrentFile = function() {
    if (FileViewPanel._currentFiles.length === 0) return;

    var file = FileViewPanel._currentFiles[FileViewPanel._currentFileIndex];
    var content = FileViewPanel._panel.find('.file-view-content');
    var footer = FileViewPanel._panel.find('.file-view-footer');
    var title = FileViewPanel._panel.find('.file-view-title');

    title.text(file.name);
    content.html('<div class="file-view-loading">' + ($.i18n('data-quality-extension/file-view-loading') || 'Loading...') + '</div>');
    footer.empty();

    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;

    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: { root: file.rootPath, path: file.name },
      dataType: 'json',
      success: function(data) {
        if (data.status === 'ok' || data.status === 'success') {
          FileViewPanel._renderFileContent(content, data, file);
          FileViewPanel._renderFooter(footer, data, file);
        } else {
          content.html('<div class="file-view-error">' + (data.message || 'Error') + '</div>');
        }
      },
      error: function() {
        content.html('<div class="file-view-error">' + ($.i18n('data-quality-extension/file-view-error') || 'Error') + '</div>');
      }
    });
  };

  FileViewPanel._renderFileContent = function(container, data, file) {
    container.empty();

    var previewType = data.previewType || 'unknown';
    var preview = data.preview;

    if (previewType === 'image' && preview) {
      var imgContainer = $('<div>').addClass('file-view-image-container').appendTo(container);
      var img = $('<img>')
        .attr('src', preview)
        .addClass('file-view-preview-image')
        .appendTo(imgContainer);

      container.data('previewImage', img);
      FileViewPanel._initImageDrag(imgContainer);

    } else if (previewType === 'pdf') {
      var pdfContainer = $('<div>').addClass('file-view-pdf-container').appendTo(container);
      var pdfUrl = '/command/records-assets/file?root=' + encodeURIComponent(file.rootPath) +
                   '&path=' + encodeURIComponent(file.name) + '#navpanes=0&toolbar=1&page=1';
      $('<iframe>')
        .attr('src', pdfUrl)
        .addClass('file-view-pdf-iframe')
        .appendTo(pdfContainer);

    } else if (previewType === 'text' && preview) {
      var textContainer = $('<div>').addClass('file-view-text-container').appendTo(container);
      $('<pre>')
        .addClass('file-view-text-preview')
        .text(preview)
        .appendTo(textContainer);

    } else {
      container.html(
        '<div class="file-view-no-preview">' +
        '<div class="file-view-no-preview-icon">📄</div>' +
        '<div>' + ($.i18n('data-quality-extension/file-view-no-preview') || '当前文件无法预览') + '</div>' +
        '</div>'
      );
    }
  };

  FileViewPanel._renderFooter = function(footer, data, file) {
    footer.empty();

    var previewType = data.previewType || 'unknown';

    var info = $('<div>').addClass('file-view-file-info').appendTo(footer);

    if (data.size) {
      $('<span>').text(FileViewPanel._formatFileSize(data.size)).appendTo(info);
    }

    if (FileViewPanel._currentFiles.length > 1) {
      $('<span>').addClass('file-view-file-counter')
        .text((FileViewPanel._currentFileIndex + 1) + ' / ' + FileViewPanel._currentFiles.length)
        .appendTo(info);
    }

    var actions = $('<div>').addClass('file-view-actions').appendTo(footer);

    if (previewType === 'image') {
      var zoomControls = $('<div>').addClass('file-view-zoom-controls').appendTo(actions);

      $('<button>').addClass('button file-view-zoom-btn').text('−').on('click', function() {
        FileViewPanel._zoomLevel = Math.max(0.1, FileViewPanel._zoomLevel - 0.2);
        FileViewPanel._applyZoom();
      }).appendTo(zoomControls);

      $('<button>').addClass('button file-view-zoom-btn').text('100%').on('click', function() {
        FileViewPanel._zoomLevel = 1;
        FileViewPanel._currentOffsetX = 0;
        FileViewPanel._currentOffsetY = 0;
        FileViewPanel._applyZoom();
      }).appendTo(zoomControls);

      $('<button>').addClass('button file-view-zoom-btn').text('+').on('click', function() {
        FileViewPanel._zoomLevel = Math.min(5, FileViewPanel._zoomLevel + 0.2);
        FileViewPanel._applyZoom();
      }).appendTo(zoomControls);
    }

    $('<button>')
      .addClass('button')
      .text($.i18n('data-quality-extension/file-view-download') || 'Download')
      .on('click', function() {
        window.open('/command/records-assets/file?root=' + encodeURIComponent(file.rootPath) +
                    '&path=' + encodeURIComponent(file.name) + '&download=true', '_blank');
      })
      .appendTo(actions);
  };

  FileViewPanel._applyZoom = function() {
    if (!FileViewPanel._panel) return;
    var img = FileViewPanel._panel.find('.file-view-preview-image');
    if (img.length) {
      img.css('transform', 'translate(' + FileViewPanel._currentOffsetX + 'px, ' +
              FileViewPanel._currentOffsetY + 'px) scale(' + FileViewPanel._zoomLevel + ')');
      img.css('transform-origin', 'top left');
    }
  };

  FileViewPanel._initImageDrag = function(imgContainer) {
    var img = imgContainer.find('.file-view-preview-image');
    var ns = FileViewPanel._dragNamespace;

    img.on('mousedown' + ns, function(e) {
      FileViewPanel._isDraggingImage = true;
      FileViewPanel._dragStartX = e.clientX;
      FileViewPanel._dragStartY = e.clientY;
      img.addClass('dragging');
      e.preventDefault();
    });

    $(document).on('mousemove' + ns, function(e) {
      if (!FileViewPanel._isDraggingImage) return;

      var deltaX = e.clientX - FileViewPanel._dragStartX;
      var deltaY = e.clientY - FileViewPanel._dragStartY;

      FileViewPanel._currentOffsetX += deltaX;
      FileViewPanel._currentOffsetY += deltaY;

      FileViewPanel._dragStartX = e.clientX;
      FileViewPanel._dragStartY = e.clientY;

      FileViewPanel._applyZoom();
      e.preventDefault();
    });

    $(document).on('mouseup' + ns, function() {
      if (FileViewPanel._isDraggingImage) {
        FileViewPanel._isDraggingImage = false;
        img.removeClass('dragging');
      }
    });

    imgContainer.on('wheel' + ns, function(e) {
      e.preventDefault();
      var delta = e.originalEvent.deltaY;
      var zoomStep = delta > 0 ? -0.1 : 0.1;
      FileViewPanel._zoomLevel = Math.max(0.1, Math.min(5, FileViewPanel._zoomLevel + zoomStep));
      FileViewPanel._applyZoom();
    });

    imgContainer.on('remove', function() {
      $(document).off(ns);
      img.off(ns);
    });
  };

  FileViewPanel._handleKeyDown = function(e) {
    if (!FileViewPanel._isVisible) return;

    if (e.keyCode === 37) {
      e.preventDefault();
      FileViewPanel._navigatePrev();
    } else if (e.keyCode === 39) {
      e.preventDefault();
      FileViewPanel._navigateNext();
    } else if (e.keyCode === 27) {
      e.preventDefault();
      FileViewPanel.hide();
    }
  };

  FileViewPanel._navigatePrev = function() {
    if (FileViewPanel._currentFiles.length <= 1) return;
    if (FileViewPanel._currentFileIndex > 0) {
      FileViewPanel._currentFileIndex--;
    } else {
      FileViewPanel._currentFileIndex = FileViewPanel._currentFiles.length - 1;
    }
    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;
    FileViewPanel._loadCurrentFile();
    FileViewPanel._updateThumbnailSelection();
    FileViewPanel._scrollToThumbnail();
  };

  FileViewPanel._navigateNext = function() {
    if (FileViewPanel._currentFiles.length <= 1) return;
    if (FileViewPanel._currentFileIndex < FileViewPanel._currentFiles.length - 1) {
      FileViewPanel._currentFileIndex++;
    } else {
      FileViewPanel._currentFileIndex = 0;
    }
    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;
    FileViewPanel._loadCurrentFile();
    FileViewPanel._updateThumbnailSelection();
    FileViewPanel._scrollToThumbnail();
  };

  FileViewPanel._scrollToThumbnail = function() {
    var thumbList = FileViewPanel._panel.find('.file-view-thumb-list');
    var activeThumb = thumbList.find('.file-view-thumb-item.active');
    if (activeThumb.length) {
      var containerTop = thumbList.scrollTop();
      var containerHeight = thumbList.height();
      var thumbTop = activeThumb.position().top + containerTop;
      var thumbHeight = activeThumb.outerHeight();

      if (thumbTop < containerTop) {
        thumbList.scrollTop(thumbTop);
      } else if (thumbTop + thumbHeight > containerTop + containerHeight) {
        thumbList.scrollTop(thumbTop + thumbHeight - containerHeight);
      }
    }
  };

  FileViewPanel._adjustRightPanel = function() {
    var panelWidth = FileViewPanel.PANEL_WIDTH;

    $('#view-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');
    $('#tool-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');
    $('#quality-rules-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');
    $('#quality-results-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');

    FileViewPanel._panel.css({
      position: 'absolute',
      top: 0,
      right: 0,
      width: panelWidth + 'px',
      height: '100%'
    });
  };

  FileViewPanel._restoreRightPanel = function() {
    $('#view-panel').css('width', '');
    $('#tool-panel').css('width', '');
    $('#quality-rules-panel').css('width', '');
    $('#quality-results-panel').css('width', '');
  };

  FileViewPanel._formatFileSize = function(bytes) {
    if (bytes === 0) return '0 B';
    var k = 1024;
    var sizes = ['B', 'KB', 'MB', 'GB'];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  };

  FileViewPanel.resize = function() {
    if (!FileViewPanel._panel || !FileViewPanel._isVisible) return;
    FileViewPanel._adjustRightPanel();
  };

})();
