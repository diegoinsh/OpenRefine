/**
 * Data Quality Extension - Image Annotation Module
 *
 * Provides visual annotation of error locations on images
 * Supports drawing bounding boxes for stains, holes, and edge issues
 */

var ImageAnnotation = {
  _isSetUp: false,
  _overlayContainer: null,
  _tooltip: null,
  _legend: null,

  /**
   * Initialize image annotation functionality
   * Creates overlay container if not provided
   */
  init: function() {
    if (this._isSetUp) {
      return;
    }

    this._createStyles();
    this._createOverlay();
    this._bindEvents();

    this._isSetUp = true;
    // // console.log('[ImageAnnotation] Initialized successfully');
  },

  /**
   * Create CSS styles for image annotations
   */
  _createStyles: function() {
    if ($('#image-annotation-styles').length > 0) {
      return;
    }

    var styles = `
      #image-annotation-styles {
        display: none;
      }

      .image-annotation-overlay {
        position: absolute;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        pointer-events: none;
        z-index: 100;
      }

      .image-annotation-overlay canvas {
        position: absolute;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        pointer-events: auto;
      }

      .annotation-tooltip {
        position: absolute;
        background: rgba(0, 0, 0, 0.85);
        color: #fff;
        padding: 8px 12px;
        border-radius: 4px;
        font-size: 12px;
        max-width: 250px;
        z-index: 1000;
        box-shadow: 0 2px 8px rgba(0,0,0,0.3);
        display: none;
        pointer-events: none;
      }

      .annotation-tooltip .tooltip-title {
        font-weight: bold;
        margin-bottom: 4px;
        color: #ff6b6b;
      }

      .annotation-tooltip .tooltip-location {
        color: #aaa;
        font-size: 11px;
        margin-top: 4px;
      }

      .annotation-legend {
        position: absolute;
        top: 10px;
        right: 10px;
        background: rgba(255, 255, 255, 0.95);
        padding: 8px 12px;
        border-radius: 4px;
        font-size: 11px;
        z-index: 9999;
        box-shadow: 0 2px 3px rgba(0,0,0,0.15);
      }

      .annotation-legend-item {
        display: flex;
        align-items: center;
        margin: 4px 0;
      }

      .annotation-legend-color {
        width: 10px;
        height: 10px;
        margin-right: 6px;
        border-radius: 2px;
      }

      .annotation-legend-stain {
        background: rgba(255, 0, 0, 0.2);
        border: 1px solid #ff0000;
      }

      .annotation-legend-hole {
        background: rgba(255, 165, 0, 0.2);
        border: 1px solid #ffa500;
      }

      .annotation-legend-edge {
        background: rgba(128, 0, 128, 0.2);
        border: 1px solid #800080;
      }

      .error-marker {
        position: absolute;
        border: 1px solid;
        border-radius: 1px;
        pointer-events: auto;
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .error-marker:hover {
        transform: scale(1.05);
        box-shadow: 0 0 10px rgba(255,255,255,0.2);
      }

      .error-marker-stain {
        border-color: #ff0000;
        background: rgba(255, 0, 0, 0.2);
      }

      .error-marker-hole {
        border-color: #ffa500;
        background: rgba(255, 165, 0, 0.2);
      }

      .error-marker-edge {
        border-color: #800080;
        background: rgba(128, 0, 128, 0.2);
      }

      .error-marker-skew {
        border-color: #ffa500;
        background: rgba(255, 165, 0, 0.2);
      }
    `;

    $('<style id="image-annotation-styles">' + styles + '</style>').appendTo('head');
  },

  /**
   * Create overlay container for annotations
   */
  _createOverlay: function() {
    this._overlayContainer = $('<div class="image-annotation-overlay"></div>');
    this._tooltip = $('<div class="annotation-tooltip"></div>');
    this._legend = $(`
      <div class="annotation-legend">
        <div class="annotation-legend-item">
          <div class="annotation-legend-color annotation-legend-stain"></div>
          <span>污点</span>
        </div>
        <div class="annotation-legend-item">
          <div class="annotation-legend-color annotation-legend-hole"></div>
          <span>装订孔</span>
        </div>
        <div class="annotation-legend-item">
          <div class="annotation-legend-color annotation-legend-edge"></div>
          <span>黑边</span>
        </div>
      </div>
    `);

    this._overlayContainer.append(this._tooltip);
    this._overlayContainer.append(this._legend);
  },

  /**
   * Bind mouse events for annotation interaction
   */
  _bindEvents: function() {
    var self = this;

    this._overlayContainer.on('mouseenter', '.error-marker', function(e) {
      var data = $(this).data('annotationData');
      if (data) {
        self._showTooltip(e, data);
      }
    });

    this._overlayContainer.on('mouseleave', '.error-marker', function() {
      self._hideTooltip();
    });

    this._overlayContainer.on('mousemove', '.error-marker', function(e) {
      var data = $(this).data('annotationData');
      if (data) {
        self._updateTooltipPosition(e);
      }
    });
  },

  /**
   * Show tooltip with error details
   * @param {Event} event - Mouse event
   * @param {Object} data - Annotation data
   */
  _showTooltip: function(event, data) {
    var title = '';
    var color = '';

    switch (data.errorType) {
      case 'stain':
      case 'stainValue':
        title = '检测到污点';
        color = '#ff6b6b';
        break;
      case 'hole':
        title = '检测到装订孔';
        color = '#ffa500';
        break;
      case 'edge':
      case 'edgeRemove':
        title = '检测到黑边';
        color = '#a855f7';
        break;
      case 'skew':
        title = '检测到倾斜';
        color = '#ffa500';
        break;
      default:
        title = '质量问题';
        color = '#ff6b6b';
    }

    var content = '<div class="tooltip-title" style="color: ' + color + '">' + title + '</div>';
    content += '<div>' + (data.message || '') + '</div>';
    content += '<div class="tooltip-location">';
    content += '位置: x=' + data.x + ', y=' + data.y;
    if (data.width && data.height) {
      content += ', 宽=' + data.width + ', 高=' + data.height;
    }
    content += '</div>';

    this._tooltip.html(content);
    this._tooltip.css({
      display: 'block'
    });
    this._updateTooltipPosition(event);
  },

  /**
   * Update tooltip position based on mouse event
   * @param {Event} event - Mouse event
   */
  _updateTooltipPosition: function(event) {
    var tooltip = this._tooltip;
    var offset = 15;

    var left = event.pageX + offset;
    var top = event.pageY + offset;

    var tooltipWidth = tooltip.outerWidth();
    var tooltipHeight = tooltip.outerHeight();
    var windowWidth = $(window).width();
    var windowHeight = $(window).height();

    if (left + tooltipWidth > windowWidth - 20) {
      left = event.pageX - tooltipWidth - offset;
    }
    if (top + tooltipHeight > windowHeight - 20) {
      top = event.pageY - tooltipHeight - offset;
    }

    tooltip.css({
      left: left,
      top: top
    });
  },

  /**
   * Hide tooltip
   */
  _hideTooltip: function() {
    this._tooltip.hide();
  },

  /**
   * Create annotation overlay for an image element
   * @param {jQuery} image - jQuery object for the image element
   * @param {Array} errors - Array of error objects with location data
   */
  _createAnnotationOverlay: function(image, errors) {
    if (image.length === 0) {
      console.error('[ImageAnnotation] Image not found');
      return null;
    }

    var imageOffset = image.offset();
    var imageWidth = image.width();
    var imageHeight = image.height();

    if (imageWidth === 0 || imageHeight === 0) {
      console.warn('[ImageAnnotation] Image has zero dimensions, skipping annotation');
      return null;
    }

    var overlay = $('<div class="image-annotation-overlay"></div>');
    overlay.css({
      top: imageOffset.top,
      left: imageOffset.left,
      width: imageWidth,
      height: imageHeight
    });

    var tooltip = $('<div class="annotation-tooltip"></div>');
    var legend = $(`
      <div class="annotation-legend">
        <div class="annotation-legend-item">
          <div class="annotation-legend-color annotation-legend-stain"></div>
          <span>污点</span>
        </div>
        <div class="annotation-legend-item">
          <div class="annotation-legend-color annotation-legend-hole"></div>
          <span>装订孔</span>
        </div>
        <div class="annotation-legend-item">
          <div class="annotation-legend-color annotation-legend-edge"></div>
          <span>黑边</span>
        </div>
      </div>
    `);

    overlay.append(tooltip);
    overlay.append(legend);

    if (!errors || errors.length === 0) {
      legend.hide();
    }

    var hasErrors = false;
    errors.forEach(function(error) {
      if (!error.locationX && !error.locationY) {
        return;
      }

      hasErrors = true;
      var markerClass = 'error-marker-';
      switch (error.errorType) {
        case 'stain':
        case 'stainValue':
          markerClass += 'stain';
          break;
        case 'hole':
          markerClass += 'hole';
          break;
        case 'edge':
        case 'edgeRemove':
          markerClass += 'edge';
          break;
        case 'skew':
          markerClass += 'skew';
          break;
        default:
          markerClass += 'stain';
      }

      var scaleX = imageWidth / (error.imageWidth || imageWidth);
      var scaleY = imageHeight / (error.imageHeight || imageHeight);

      var x = (error.locationX || 0) * scaleX;
      var y = (error.locationY || 0) * scaleY;
      var width = (error.locationWidth || 100) * scaleX;
      var height = (error.locationHeight || 100) * scaleY;

      width = Math.max(width, 10);
      height = Math.max(height, 10);

      var marker = $('<div class="error-marker ' + markerClass + '"></div>');
      marker.css({
        left: x,
        top: y,
        width: width,
        height: height
      });

      marker.data('annotationData', {
        errorType: error.errorType,
        message: error.message,
        x: error.locationX,
        y: error.locationY,
        width: error.locationWidth,
        height: error.locationHeight,
        imageWidth: error.imageWidth,
        imageHeight: error.imageHeight
      });

      overlay.append(marker);
    });

    if (!hasErrors) {
      legend.hide();
    }

    return overlay;
  },

  /**
   * Annotate an image element with error locations
   * @param {string} imageSelector - CSS selector for the image element
   * @param {Array} errors - Array of error objects with location data
   */
  annotateImage: function(imageSelector, errors) {
    var self = this;
    var image = $(imageSelector);

    if (image.length === 0) {
      console.error('[ImageAnnotation] Image not found:', imageSelector);
      return;
    }

    var overlay = this._createAnnotationOverlay(image, errors);
    if (!overlay) {
      return;
    }

    image.after(overlay);

    overlay.on('mouseenter', '.error-marker', function(e) {
      var data = $(this).data('annotationData');
      if (data) {
        self._showTooltipForOverlay(e, data, tooltip);
      }
    });

    overlay.on('mouseleave', '.error-marker', function() {
      tooltip.hide();
    });

    overlay.on('mousemove', '.error-marker', function(e) {
      var data = $(this).data('annotationData');
      if (data) {
        self._updateTooltipPositionForOverlay(e, tooltip);
      }
    });
  },

  /**
   * Show tooltip for a specific overlay
   */
  _showTooltipForOverlay: function(event, data, tooltip) {
    var title = '';
    var color = '';

    switch (data.errorType) {
      case 'stain':
      case 'stainValue':
        title = '检测到污点';
        color = '#ff6b6b';
        break;
      case 'hole':
        title = '检测到装订孔';
        color = '#ffa500';
        break;
      case 'edge':
      case 'edgeRemove':
        title = '检测到黑边';
        color = '#a855f7';
        break;
      case 'skew':
        title = '检测到倾斜';
        color = '#ffa500';
        break;
      default:
        title = '质量问题';
        color = '#ff6b6b';
    }

    var content = '<div class="tooltip-title" style="color: ' + color + '">' + title + '</div>';
    content += '<div>' + (data.message || '') + '</div>';
    content += '<div class="tooltip-location">';
    content += '位置: x=' + data.x + ', y=' + data.y;
    if (data.width && data.height) {
      content += ', 宽=' + data.width + ', 高=' + data.height;
    }
    content += '</div>';

    tooltip.html(content);
    tooltip.css({
      display: 'block'
    });
    this._updateTooltipPositionForOverlay(event, tooltip);
  },

  /**
   * Update tooltip position for a specific overlay
   */
  _updateTooltipPositionForOverlay: function(event, tooltip) {
    var offset = 15;
    var left = event.pageX + offset;
    var top = event.pageY + offset;

    var tooltipWidth = tooltip.outerWidth();
    var tooltipHeight = tooltip.outerHeight();
    var windowWidth = $(window).width();
    var windowHeight = $(window).height();

    if (left + tooltipWidth > windowWidth - 20) {
      left = event.pageX - tooltipWidth - offset;
    }
    if (top + tooltipHeight > windowHeight - 20) {
      top = event.pageY - tooltipHeight - offset;
    }

    tooltip.css({
      left: left,
      top: top
    });
  },

  /**
   * Clear all annotations
   */
  clearAnnotations: function() {
    if (this._overlayContainer) {
      this._overlayContainer.find('.error-marker').remove();
      this._legend.hide();
    }
    this._hideTooltip();
  },

  /**
   * Destroy annotation instance
   */
  destroy: function() {
    if (this._overlayContainer) {
      this._overlayContainer.remove();
      this._overlayContainer = null;
    }
    this._hideTooltip();
    this._isSetUp = false;
  },

  showImageWithAnnotations: function(imagePath, errors) {
    // console.log('[ImageAnnotation.showImageWithAnnotations] 被调用, errors数量:', errors ? errors.length : 0);
    var self = this;

    var dialogId = 'quality-image-annotation-dialog';
    var existingDialog = $('#' + dialogId);

    if (existingDialog.length > 0) {
      existingDialog.remove();
    }

    var expandedErrors = [];
    var totalLocations = 0;
    
    // console.log('[ImageAnnotation] 原始错误数量:', errors.length);
    
    errors.forEach(function(error, errorIndex) {
      // console.log('[ImageAnnotation] 处理错误', errorIndex, 'errorType:', error.errorType, 'details:', JSON.stringify(error.details, null, 2));
      
      if (error.details && error.details.locations && Array.isArray(error.details.locations) && error.details.locations.length > 0) {
        // console.log('[ImageAnnotation] 展开聚合错误，位置数量:', error.details.locations.length);
        totalLocations += error.details.locations.length;
        
        error.details.locations.forEach(function(loc, locIndex) {
          // console.log('[ImageAnnotation] 位置', locIndex, ':', JSON.stringify(loc));
          
          if (loc && loc.length >= 4) {
            expandedErrors.push({
              rowIndex: error.rowIndex,
              column: error.column,
              hiddenFileName: error.hiddenFileName,
              value: error.value,
              errorType: error.errorType,
              message: error.message,
              locationX: loc[0],
              locationY: loc[1],
              locationWidth: loc[2],
              locationHeight: loc[3]
            });
          }
        });
      } else {
        expandedErrors.push(error);
      }
    });
    
    errors = expandedErrors;
    // console.log('[ImageAnnotation] 展开后的错误数量:', errors.length);

    var dialog = $('<div id="' + dialogId + '" class="quality-image-dialog"></div>');
    dialog.html(`
      <div class="quality-image-container">
        <img class="quality-image-preview" src="${imagePath}" alt="Image preview">
      </div>
      <div class="quality-image-toolbar">
        <span class="quality-image-filename">${imagePath.split('/').pop()}</span>
        <span class="quality-image-error-count">${errors.length} 个问题</span>
      </div>
    `);

    dialog.css({
      position: 'fixed',
      top: '50%',
      left: '50%',
      transform: 'translate(-50%, -50%)',
      background: '#fff',
      padding: '16px',
      borderRadius: '8px',
      boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
      zIndex: 2000,
      maxWidth: '90vw',
      maxHeight: '90vh'
    });

    var imageContainer = dialog.find('.quality-image-container');
    imageContainer.css({
      position: 'relative',
      overflow: 'hidden'
    });

    var imagePreview = dialog.find('.quality-image-preview');
    imagePreview.css({
      maxWidth: '100%',
      maxHeight: '70vh',
      display: 'block'
    });

    var toolbar = dialog.find('.quality-image-toolbar');
    toolbar.css({
      marginTop: '10px',
      padding: '8px',
      background: '#f5f5f5',
      borderRadius: '4px',
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center'
    });

    var filename = dialog.find('.quality-image-filename');
    filename.css({
      fontWeight: 'bold',
      color: '#333'
    });

    var errorCount = dialog.find('.quality-image-error-count');
    errorCount.css({
      color: '#ff6b6b'
    });

    $('body').append(dialog);

    dialog.on('click', '.error-marker', function() {
      var data = $(this).data('annotationData');
      if (data) {
        QualityAlignment._showErrorDetailDialog(data);
      }
    });

    imagePreview.on('load', function() {
      ImageAnnotation.annotateImage('#' + dialogId + ' .quality-image-preview', errors);
    });

    if (imagePreview[0].complete) {
      ImageAnnotation.annotateImage('#' + dialogId + ' .quality-image-preview', errors);
    }

    dialog.dialog({
      title: '图像质量问题标注',
      width: Math.min($(window).width() * 0.9, 1200),
      height: Math.min($(window).height() * 0.9, 800),
      modal: true,
      resizable: true,
      close: function() {
        ImageAnnotation.clearAnnotations();
        dialog.remove();
      }
    });

    dialog.find('.ui-dialog-titlebar-close').on('click', function() {
      ImageAnnotation.clearAnnotations();
    });
  }
};

/**
 * Add image annotation capability to quality results display
 * Integrates with QualityAlignment for showing errors on images
 */
QualityAlignment._initImageAnnotation = function() {
  if (typeof ImageAnnotation !== 'undefined') {
    ImageAnnotation.init();
    // console.log('[QualityAlignment] Image annotation initialized');
  }
};

/**
 * Show image with error annotations in a modal dialog (QualityAlignment wrapper)
 * @param {string} imagePath - Path to the image
 * @param {Array} errors - Array of error objects with location data
 */
QualityAlignment.showImageWithAnnotations = function(imagePath, errors) {
  // console.log('[QualityAlignment.showImageWithAnnotations] 被调用, errors数量:', errors ? errors.length : 0);
  if (typeof ImageAnnotation !== 'undefined' && typeof ImageAnnotation.showImageWithAnnotations === 'function') {
    ImageAnnotation.showImageWithAnnotations(imagePath, errors);
  } else {
    console.error('[ImageAnnotation] ImageAnnotation module not loaded');
    alert('图像标注功能未加载，请刷新页面后重试');
  }
};

/**
 * Show detailed error information in a dialog
 * @param {Object} errorData - Error data object
 */
QualityAlignment._showErrorDetailDialog = function(errorData) {
  var dialogId = 'quality-error-detail-dialog';
  var existingDialog = $('#' + dialogId);

  if (existingDialog.length > 0) {
    existingDialog.remove();
  }

  var title = '';
  switch (errorData.errorType) {
    case 'stain':
    case 'stainValue':
      title = '污点详情';
      break;
    case 'hole':
      title = '装订孔详情';
      break;
    case 'edge':
    case 'edgeRemove':
      title = '黑边详情';
      break;
    case 'skew':
      title = '倾斜详情';
      break;
    default:
      title = '错误详情';
  }

  var dialog = $('<div id="' + dialogId + '"></div>');
  dialog.html(`
    <div class="quality-error-detail-content">
      <h3>${title}</h3>
      <p><strong>错误信息：</strong>${errorData.message || '无'}</p>
      <p><strong>位置坐标：</strong></p>
      <ul>
        <li>X: ${errorData.x || 0}</li>
        <li>Y: ${errorData.y || 0}</li>
        <li>宽度: ${errorData.width || 'N/A'}</li>
        <li>高度: ${errorData.height || 'N/A'}</li>
      </ul>
    </div>
  `);

  dialog.css({
    padding: '20px'
  });

  dialog.dialog({
    title: title,
    width: 400,
    height: 'auto',
    modal: true,
    resizable: false,
    close: function() {
      dialog.remove();
    }
  });
};

/**
 * Add annotation functionality to quality alignment module
 */
$(document).ready(function() {
  QualityAlignment._initImageAnnotation();
});
