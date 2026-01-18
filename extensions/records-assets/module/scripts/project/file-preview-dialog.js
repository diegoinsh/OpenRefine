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
  FilePreviewDialog._isDraggingImage = false;
  FilePreviewDialog._dragStartX = 0;
  FilePreviewDialog._dragStartY = 0;
  FilePreviewDialog._currentOffsetX = 0;
  FilePreviewDialog._currentOffsetY = 0;
  FilePreviewDialog._dragNamespace = '.imageDrag_' + Math.random().toString(36).substr(2, 9);

  /**
   * Show file preview dialog
   * @param {string} rootPath - Root path
   * @param {string} filePath - File path
   * @param {Object} fileItem - File item data
   */
  FilePreviewDialog.show = function(rootPath, filePath, fileItem) {
    // Save dragged state if dialog exists
    var wasDragged = false;
    var lastPosition = null;
    if (FilePreviewDialog._currentDialog) {
      wasDragged = FilePreviewDialog._currentDialog.data('dragged') || false;
      if (wasDragged) {
        lastPosition = {
          left: FilePreviewDialog._currentDialog.css('left'),
          top: FilePreviewDialog._currentDialog.css('top'),
          width: FilePreviewDialog._currentDialog.css('width'),
          height: FilePreviewDialog._currentDialog.css('height')
        };
      }
      FilePreviewDialog._currentDialog.remove();
    }

    // Reset zoom level
    FilePreviewDialog._zoomLevel = 1;
    
    // Reset annotation state
    FilePreviewDialog._showAnnotations = false;
    FilePreviewDialog._currentErrors = [];

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
    if (wasDragged && lastPosition) {
      dialog.css({
        left: lastPosition.left,
        top: lastPosition.top,
        right: 'auto',
        width: lastPosition.width,
        height: lastPosition.height
      });
      dialog.data('dragged', true);
    } else {
      FilePreviewDialog._positionDialog(dialog);
      dialog.data('dragged', false);
    }

    // Adjust data table container to allow scrolling past the preview panel
    FilePreviewDialog._adjustDataTablePadding(dialog);

    // Make draggable by header
    FilePreviewDialog._makeDraggable(dialog, header);

    // Handle window resize (only if not dragged)
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
   * Show file preview dialog with error annotations
   * @param {string} filePath - Full file path (root + path)
   * @param {Array} errors - Array of error objects with location data
   */
  FilePreviewDialog.showWithAnnotations = function(filePath, errors) {
    var self = this;
    
    var lastSlash = filePath.lastIndexOf('/');
    var rootPath = lastSlash > 0 ? filePath.substring(0, lastSlash) : '';
    var pathOnly = lastSlash > 0 ? filePath.substring(lastSlash + 1) : filePath;
    
    this.show(rootPath, pathOnly, null);
    
    this._currentErrors = errors || [];
    this._showAnnotations = errors && errors.length > 0;
    
    var checkAnnotationInterval = setInterval(function() {
      if (FilePreviewDialog._currentDialog && FilePreviewDialog._currentDialog.find('.preview-image').length > 0) {
        clearInterval(checkAnnotationInterval);
        FilePreviewDialog._updateAnnotationToggle(errors);
      }
    }, 100);
    
    setTimeout(function() {
      clearInterval(checkAnnotationInterval);
    }, 5000);
  };
  
  FilePreviewDialog._updateAnnotationToggle = function(errors) {
    var footer = FilePreviewDialog._currentDialog.find('.file-preview-float-footer');
    if (footer.length === 0) return;
    
    var actions = footer.find('.file-actions');
    if (actions.length === 0) return;
    
    var existingToggle = actions.find('.annotation-toggle-btn');
    if (existingToggle.length > 0) {
      existingToggle.remove();
    }
    
    var toggleBtn = $('<button>')
      .addClass('button annotation-toggle-btn')
      .text(FilePreviewDialog._showAnnotations ? '隐藏标注' : '显示标注')
      .on('click', function() {
        FilePreviewDialog._showAnnotations = !FilePreviewDialog._showAnnotations;
        $(this).text(FilePreviewDialog._showAnnotations ? '隐藏标注' : '显示标注');
        FilePreviewDialog._renderAnnotations();
      });
    
    actions.find('.button:last').before(toggleBtn);
    
    if (errors && errors.length > 0) {
      FilePreviewDialog._currentErrors = errors;
      FilePreviewDialog._renderAnnotations();
    }
  };
  
  FilePreviewDialog._renderAnnotations = function() {
    if (!FilePreviewDialog._currentDialog) return;
    
    var imgContainer = FilePreviewDialog._currentDialog.find('.image-preview-container');
    if (imgContainer.length === 0) return;
    
    imgContainer.find('.error-marker, .annotation-legend, .annotation-tooltip').remove();
    
    if (!FilePreviewDialog._showAnnotations || !FilePreviewDialog._currentErrors || FilePreviewDialog._currentErrors.length === 0) {
      return;
    }
    
    var img = imgContainer.find('.preview-image');
    if (img.length === 0) return;
    
    var renderMarkers = function() {
      var naturalWidth = img[0].naturalWidth;
      var naturalHeight = img[0].naturalHeight;
      
      // console.log('[FilePreviewDialog] 图像尺寸检查 - naturalWidth:', naturalWidth, 'naturalHeight:', naturalHeight);
      
      if (!naturalWidth || !naturalHeight || naturalWidth === 0 || naturalHeight === 0) {
        // console.log('[FilePreviewDialog] 图像未加载完成，等待100ms后重试');
        setTimeout(renderMarkers, 100);
        return;
      }
      
      var displayedWidth = img.width();
      var displayedHeight = img.height();
      
      var scaleX = displayedWidth / naturalWidth;
      var scaleY = displayedHeight / naturalHeight;
      
      scaleX *= FilePreviewDialog._zoomLevel;
      scaleY *= FilePreviewDialog._zoomLevel;
      
      var containerWidth = imgContainer.width();
      var containerHeight = imgContainer.height();
      
      var imgOffset = img.offset();
      var containerOffset = imgContainer.offset();
      
      var offsetX = imgOffset.left - containerOffset.left;
      var offsetY = imgOffset.top - containerOffset.top;
      
      // 考虑缩放对偏移量的影响
      // transform-origin: top left，所以缩放后图片的左上角位置不变
      // 但是图片的实际显示尺寸会改变，需要使用 getBoundingClientRect 获取实际尺寸
      var imgRect = img[0].getBoundingClientRect();
      var containerRect = imgContainer[0].getBoundingClientRect();
      
      // 计算图片相对于容器的实际位置（考虑缩放）
      // getBoundingClientRect 返回的是相对于视口的位置，需要减去容器的位置
      var actualOffsetX = imgRect.left - containerRect.left;
      var actualOffsetY = imgRect.top - containerRect.top;
      
      // 考虑容器的滚动位置
      // 当容器滚动时，图片相对于容器的位置会改变
      actualOffsetX += imgContainer.scrollLeft();
      actualOffsetY += imgContainer.scrollTop();
      
      // 使用实际偏移量
      offsetX = actualOffsetX;
      offsetY = actualOffsetY;
      
      // 手动调整偏移量 - 视觉上发现的偏移
      offsetX += -5;   // 横向往左偏移5px
      offsetY += -5;  // 纵向往上偏移5px
      
      // console.log('[FilePreviewDialog] 缩放信息 - zoomLevel:', FilePreviewDialog._zoomLevel,
                  // 'displayedWidth:', displayedWidth, 'displayedHeight:', displayedHeight,
                  // 'imgRect.width:', imgRect.width, 'imgRect.height:', imgRect.height,
                  // 'offsetX:', offsetX.toFixed(1), 'offsetY:', offsetY.toFixed(1),
                  // 'scaleX:', scaleX.toFixed(3), 'scaleY:', scaleY.toFixed(3),
                  // 'scrollLeft:', imgContainer.scrollLeft(), 'scrollTop:', imgContainer.scrollTop());
      
      var contentDiv = FilePreviewDialog._currentDialog.find('.file-preview-float-content');
      var contentOffset = contentDiv.offset();
      var contentPaddingTop = parseInt(contentDiv.css('padding-top')) || 0;
      var contentPaddingBottom = parseInt(contentDiv.css('padding-bottom')) || 0;
      
      var headerDiv = FilePreviewDialog._currentDialog.find('.file-preview-float-header');
      var headerHeight = headerDiv.outerHeight();
      var headerPaddingTop = parseInt(headerDiv.css('padding-top')) || 0;
      var headerPaddingBottom = parseInt(headerDiv.css('padding-bottom')) || 0;
      
      // console.log('[FilePreviewDialog] 布局详情:');
      // console.log('  headerHeight:', headerHeight, 'headerPadding:', headerPaddingTop, '+', headerPaddingBottom);
      // console.log('  contentPaddingTop:', contentPaddingTop, 'contentPaddingBottom:', contentPaddingBottom);
      console.log('  contentOffset.top:', contentOffset.top);
      console.log('  imgOffset.top:', imgOffset.top);
      console.log('  containerOffset.top:', containerOffset.top);
      
      // console.log('[FilePreviewDialog] 标注计算 - natural:', naturalWidth, 'x', naturalHeight, 
                  // 'displayed:', displayedWidth, 'x', displayedHeight,
                  // 'container:', containerWidth, 'x', containerHeight,
                  // 'scale:', scaleX.toFixed(3), 'x', scaleY.toFixed(3),
                  // 'offset:', offsetX.toFixed(1), ',', offsetY.toFixed(1));
      
      var legendHtml = '<div class="annotation-legend" style="display: none;">';
      var errorTypes = {};
      var nonLocationErrors = {};
    
    FilePreviewDialog._currentErrors.forEach(function(error, index) {
      if (!error.locationX && !error.locationY) {
        var nonLocationType = error.errorType;
        if (!nonLocationErrors[nonLocationType]) {
          nonLocationErrors[nonLocationType] = { count: 0, extractedValue: error.extractedValue, message: error.message || '' };
        }
        nonLocationErrors[nonLocationType].count++;
        return;
      }
      
      var markerClass = 'error-marker-';
      var legendClass = '';
      var extractedValue = '';
      
      if (error.errorType === 'stainValue' || error.errorType === 'stain') {
        markerClass += 'stain';
        legendClass = 'stain';
      } else if (error.errorType === 'hole') {
        markerClass += 'hole';
        legendClass = 'hole';
      } else if (error.errorType === 'edgeRemove' || error.errorType === 'edge') {
        markerClass += 'edge';
        legendClass = 'edge';
      } else if (error.errorType === 'bias' || error.errorType === 'bias') {
        markerClass += 'bias';
        legendClass = 'bias';
      } else {
        markerClass += 'other';
        legendClass = 'other';
      }
      
      // Count errors by type
      if (!errorTypes[legendClass]) {
        errorTypes[legendClass] = { count: 0, label: '', extractedValue: '' };
      }
      errorTypes[legendClass].count++;
      if (extractedValue) {
        errorTypes[legendClass].extractedValue = extractedValue;
      }
      
      var x = (error.locationX || 0) * scaleX + offsetX;
      var y = (error.locationY || 0) * scaleY + offsetY;
      var width = (error.locationWidth || 100) * scaleX;
      var height = (error.locationHeight || 100) * scaleY;
      
      // console.log('[FilePreviewDialog] 标注位置 - type:', error.errorType, 
                    // '原始位置:', (error.locationX || 0), ',', (error.locationY || 0),
                    // '原始尺寸:', (error.locationWidth || 100), 'x', (error.locationHeight || 100),
                    // '计算位置:', x.toFixed(1), ',', y.toFixed(1),
                    // '计算尺寸:', width.toFixed(1), 'x', height.toFixed(1));
      
      width = Math.max(width, 10);
      height = Math.max(height, 10);
      
      var marker = $('<div class="error-marker ' + markerClass + '"></div>');
      marker.css({
        left: x + 'px',
        top: y + 'px',
        width: width + 'px',
        height: height + 'px',
        position: 'absolute',
        zIndex: 1000
      });
      
      var errorLabel = error.errorType || 'error';
      var translatedLabel = errorLabel;
      if (errorLabel === 'stain' || errorLabel === 'stainValue') {
        translatedLabel = $.i18n('records.assets.annotation.stain') || '污点';
      } else if (errorLabel === 'hole') {
        translatedLabel = $.i18n('records.assets.annotation.hole') || '装订孔';
      } else if (errorLabel === 'edge' || errorLabel === 'edgeRemove') {
        translatedLabel = $.i18n('records.assets.annotation.edge') || '黑边';
      } else if (errorLabel === 'bias' || errorLabel === 'bias') {
        translatedLabel = $.i18n('records.assets.annotation.bias') || '偏斜';
      } else {
        translatedLabel = $.i18n('records.assets.annotation.other') || '其他';
      }
      
      // if (error.message) {
      //   marker.attr('title', translatedLabel + ': ' + error.message);
      // } else {
      //   marker.attr('title', translatedLabel);
      // }
      
      marker.on('mouseenter', function(e) {
        var tooltip = imgContainer.find('.annotation-tooltip');
        if (tooltip.length === 0) {
          tooltip = $('<div class="annotation-tooltip"></div>').appendTo(imgContainer);
        }
        tooltip.html('<div class="tooltip-title">' + (translatedLabel) + '</div>' +
                     '<div class="tooltip-message">' + (error.message || '') + '</div>');
        tooltip.css({
          left: (x + width + 10) + 'px',
          top: y + 'px',
          display: 'block'
        });
      });
      
      marker.on('mouseleave', function() {
        imgContainer.find('.annotation-tooltip').hide();
      });
      
      imgContainer.append(marker);
      
      if (!errorTypes[legendClass].label) {
        var typeLabel;
        if (legendClass === 'stain') {
          typeLabel = $.i18n('records.assets.annotation.stain') || '污点';
        } else if (legendClass === 'hole') {
          typeLabel = $.i18n('records.assets.annotation.hole') || '装订孔';
        } else if (legendClass === 'edge') {
          typeLabel = $.i18n('records.assets.annotation.edge') || '黑边';
        } else {
          typeLabel = $.i18n('records.assets.annotation.other') || '其他';
        }
        errorTypes[legendClass].label = typeLabel;
      }
    });
    
    // Build legend HTML with counts
    Object.keys(errorTypes).forEach(function(legendClass) {
      var typeInfo = errorTypes[legendClass];
      var typeLabel = typeInfo.label || '其他';
      var count = typeInfo.count || 0;
      
      legendHtml += '<div class="annotation-legend-item">' +
                    '<div class="annotation-legend-color annotation-legend-' + legendClass + '"></div>' +
                    '<span>' + typeLabel + '(' + count + ')</span></div>';
    });
    
    // Add non-location errors to legend
    Object.keys(nonLocationErrors).forEach(function(errorType) {
      var errorInfo = nonLocationErrors[errorType];
      var count = errorInfo.count || 0;
      var extractedValue = errorInfo.extractedValue || '';
      var label = '';
      
      switch (errorType) {
        case 'dpi':
          label = 'DPI';
          break;
        case 'file-size':
        case 'kb':
          label = '文件大小';
          break;
        case 'quality':
          label = 'JPEG质量';
          break;
        case 'bit_depth':
          label = '位深度';
          break;
        case 'bias':
          label = '倾斜';
          var angleMatch = (errorInfo.message || '').match(/angle: ([-]?\d+(?:\.\d+)?)/);
          if (angleMatch) {
            errorInfo.extractedValue = angleMatch[1] + '°';
          }
          break;
        default:
          label = errorType;
      }
      
      extractedValue = errorInfo.extractedValue || '';
      
      legendHtml += '<div class="annotation-legend-item annotation-legend-nonlocation">' +
                    '<span class="annotation-legend-dot">·</span>' +
                    '<span>' + label;
      if (extractedValue) {
        legendHtml += '(' + extractedValue + ')';
      }
      legendHtml += '</span></div>';
    });
    
    legendHtml += '</div>';
    
    if (Object.keys(errorTypes).length > 0 || Object.keys(nonLocationErrors).length > 0) {
      var errorTypeTitle = $.i18n('records.assets.annotation.errorType') || '错误类型';
      var legend = $(legendHtml);
      legend.prepend('<div class="annotation-legend-title">' + errorTypeTitle + '</div>');
      imgContainer.append(legend);
      legend.fadeIn(200);
      
      FilePreviewDialog._makeDraggable(legend, legend);
    }
    };
    
    renderMarkers();
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
    var dragNamespace = '.drag_' + Math.random().toString(36).substr(2, 9);

    handle.css('cursor', 'move');

    handle.on('mousedown' + dragNamespace, function(e) {
      if ($(e.target).is('button')) return;
      isDragging = true;
      startX = e.clientX;
      startY = e.clientY;

      var position = element.position();
      startLeft = position.left;
      startTop = position.top;

      e.preventDefault();
    });

    $(document).on('mousemove' + dragNamespace, function(e) {
      if (!isDragging) return;

      var newLeft = startLeft + e.clientX - startX;
      var newTop = startTop + e.clientY - startY;

      element.css({
        left: newLeft + 'px',
        top: newTop + 'px',
        right: 'auto'
      });

      element.data('dragged', true);
    });

    $(document).on('mouseup' + dragNamespace, function() {
      isDragging = false;
    });
    
    element.on('remove', function() {
      $(document).off(dragNamespace);
      handle.off(dragNamespace);
    });
  };

  /**
   * Load file preview
   */
  FilePreviewDialog._loadPreview = function(rootPath, filePath, container, footer) {
    console.log('[FilePreviewDialog._loadPreview] 接收参数:');
    console.log('  rootPath:', rootPath);
    console.log('  filePath:', filePath);
    console.log('  rootPath类型:', typeof rootPath);
    console.log('  filePath类型:', typeof filePath);
    
    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: { root: rootPath, path: filePath },
      dataType: 'json',
      success: function(data) {
        console.log('[FilePreviewDialog._loadPreview] AJAX成功返回:', data);
        if (data.status === 'ok' || data.status === 'success') {
          FilePreviewDialog._renderPreview(container, data, rootPath, filePath);
          FilePreviewDialog._renderFooter(footer, data, rootPath, filePath);
        } else {
          console.log('[FilePreviewDialog._loadPreview] 返回状态不是ok:', data.status);
          container.html('<div class="error">' + (data.message || 'Error') + '</div>');
        }
      },
      error: function(xhr, status, error) {
        console.log('[FilePreviewDialog._loadPreview] AJAX错误:', status, error);
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
      
      // Initialize image drag functionality
      FilePreviewDialog._initImageDrag(imgContainer);

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
        FilePreviewDialog._currentOffsetX = 0;
        FilePreviewDialog._currentOffsetY = 0;
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
        window.open('/command/records-assets/file?root=' + encodeURIComponent(rootPath) + '&path=' + encodeURIComponent(filePath) + '&download=true', '_blank');
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
        img.css('transform', 'translate(' + FilePreviewDialog._currentOffsetX + 'px, ' + FilePreviewDialog._currentOffsetY + 'px) scale(' + FilePreviewDialog._zoomLevel + ')');
        img.css('transform-origin', 'top left');
        setTimeout(function() {
          FilePreviewDialog._renderAnnotations();
        }, 200);
      }
    }
  };

  /**
   * Initialize image drag functionality
   */
  FilePreviewDialog._initImageDrag = function(imgContainer) {
    var img = imgContainer.find('.preview-image');
    var content = FilePreviewDialog._currentDialog.find('.file-preview-float-content');
    
    var namespace = FilePreviewDialog._dragNamespace;
    
    img.on('mousedown' + namespace, function(e) {
      if (e.target.classList.contains('error-marker') || e.target.classList.contains('annotation-legend')) return;
      
      FilePreviewDialog._isDraggingImage = true;
      FilePreviewDialog._dragStartX = e.clientX;
      FilePreviewDialog._dragStartY = e.clientY;
      img.addClass('dragging');
      e.preventDefault();
    });
    
    $(document).on('mousemove' + namespace, function(e) {
      if (!FilePreviewDialog._isDraggingImage) return;
      
      var deltaX = e.clientX - FilePreviewDialog._dragStartX;
      var deltaY = e.clientY - FilePreviewDialog._dragStartY;
      
      FilePreviewDialog._currentOffsetX += deltaX;
      FilePreviewDialog._currentOffsetY += deltaY;
      
      FilePreviewDialog._dragStartX = e.clientX;
      FilePreviewDialog._dragStartY = e.clientY;
      
      FilePreviewDialog._applyZoom();
      e.preventDefault();
    });
    
    $(document).on('mouseup' + namespace, function() {
      if (FilePreviewDialog._isDraggingImage) {
        FilePreviewDialog._isDraggingImage = false;
        img.removeClass('dragging');
        FilePreviewDialog._renderAnnotations();
      }
    });
    
    // Clean up event listeners when dialog is removed
    imgContainer.on('remove', function() {
      $(document).off(namespace);
      img.off(namespace);
    });
    
    // Add mouse wheel zoom functionality
    imgContainer.on('wheel' + namespace, function(e) {
      e.preventDefault();
      
      var delta = e.originalEvent.deltaY;
      var zoomStep = delta > 0 ? -0.1 : 0.1;
      
      FilePreviewDialog._zoomLevel = Math.max(0.1, Math.min(5, FilePreviewDialog._zoomLevel + zoomStep));
      FilePreviewDialog._applyZoom();
    });
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

