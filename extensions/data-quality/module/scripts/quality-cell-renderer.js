/**
 * Quality Error Cell Renderer
 * Adds error markers to cells that have quality check errors
 * Similar to Excel's red triangle in the corner
 */
console.log('[Quality] quality-cell-renderer.js 脚本加载');
class QualityCellRenderer {
  render(rowIndex, cellIndex, cell, cellUI) {
    // console.log('[QualityCellRenderer.render] 被调用, rowIndex:', rowIndex, 'cellIndex:', cellIndex);
    
    // 快速检查：是否有错误需要显示
    if (typeof QualityAlignment === 'undefined' || !QualityAlignment._cellErrorMap) {
      console.log('[QualityCellRenderer.render] QualityAlignment 或 _cellErrorMap 未定义');
      return undefined;
    }

    var columnObj = Refine.cellIndexToColumn(cellIndex);
    if (!columnObj) {
      console.log('[QualityCellRenderer.render] columnObj 未找到');
      return undefined;
    }
    var columnName = columnObj.name;
    var errorKey = rowIndex + '_' + columnName;
    var errors = QualityAlignment._cellErrorMap[errorKey];

    // console.log('[QualityCellRenderer.render] errorKey:', errorKey, 'errors:', errors ? errors.length : 0);

    if (!errors || errors.length === 0) {
      return;
    }

    // 发现错误，打印详细信息
    console.log('[Quality-2] 找到错误, key:', errorKey, '数量:', errors.length);
    console.log('[Quality-2] 第一个错误:', JSON.stringify(errors[0], function(key, val) {
      if (key === 'details' && val && val.locations) {
        return { locationsCount: val.locations.length, locations: val.locations.slice(0, 2) };
      }
      return val;
    }));

    if (cellUI && cellUI._td) {
      setTimeout(function() {
        QualityCellRenderer._addErrorMarker(cellUI._td, errors, rowIndex, columnName);
      }, 0);
    }

    return undefined;
  }

  /**
   * Add error marker to a cell
   * @param {HTMLElement} td - The table cell element
   * @param {Array} errors - Array of error objects for this cell
   * @param {number} rowIndex - The row index
   * @param {string} columnName - The column name
   */
  static _addErrorMarker(td, errors, rowIndex, columnName) {
    console.log('[Quality-Marker] _addErrorMarker called, errors:', errors ? errors.length : 0);
    
    // Check if marker already exists
    if ($(td).find('.quality-error-marker').length > 0) {
      console.log('[Quality-Marker] marker already exists, returning');
      return;
    }

    // Create error marker (red triangle in bottom-right corner)
    var marker = document.createElement('div');
    marker.className = 'quality-error-marker';
    console.log('[Quality-Marker] marker created');

    // Add marker to cell
    $(td).css('position', 'relative');
    $(td).append(marker);
    console.log('[Quality-Marker] marker appended');

    // Check if this cell contains an image path or if errors have image path info
    var cellValue = $(td).text().trim();
    var hasImagePath = false;
    var imagePath = null;

    // First check if cell value is an image path
    if (cellValue) {
      var isImagePath = cellValue.match(/\.(jpg|jpeg|png|gif|bmp|tiff|webp)$/i);
      console.log('[Quality-Marker] cellValue:', cellValue, 'isImagePath:', !!isImagePath);
      if (isImagePath) {
        hasImagePath = true;
        imagePath = cellValue;
      }
    }

    // If cell value is not an image path, check error objects for hiddenFileName (image file)
    if (!hasImagePath && errors && errors.length > 0) {
      for (var i = 0; i < errors.length; i++) {
        var error = errors[i];
        console.log('[Quality-Marker] 检查错误', i, 'hiddenFileName:', error.hiddenFileName, 'locationX:', error.locationX);
        
        // Check if error has hiddenFileName (indicates image file)
        if (error.hiddenFileName && typeof error.hiddenFileName === 'string') {
          var isImageFile = error.hiddenFileName.match(/\.(jpg|jpeg|png|gif|bmp|tiff|webp)$/i);
          if (isImageFile) {
            hasImagePath = true;
            var folderPath = error.value || '';
            imagePath = folderPath ? folderPath + '/' + error.hiddenFileName : error.hiddenFileName;
            console.log('[Quality-Marker] 发现图像文件 hiddenFileName:', error.hiddenFileName, 'imagePath:', imagePath);
            break;
          }
        }
        
        // Check if error has location info (also indicates image annotation)
        if (error.locationX !== undefined || error.locationY !== undefined) {
          hasImagePath = true;
          imagePath = error.errorValue || null;
          console.log('[Quality-Marker] 发现位置信息, imagePath:', imagePath);
          break;
        }
      }
    }

    console.log('[Quality-Marker] hasImagePath:', hasImagePath, 'imagePath:', imagePath);

    // Add tooltip on hover with delayed hide
    $(marker).on('mouseenter', function(e) {
      QualityCellRenderer._showTooltip(e, errors, rowIndex, columnName);
    }).on('mouseleave', function() {
      QualityCellRenderer._scheduleHideTooltip();
    });
  }

  static _addImageViewLink(td, errors, rowIndex, columnName, imagePath) {
    console.log('[QualityCellRenderer._addImageViewLink] 被调用, errors数量:', errors ? errors.length : 0, 'imagePath:', imagePath);
    
    var self = this;
    var cellValue = $(td).text().trim();

    console.log('[QualityCellRenderer._addImageViewLink] cellValue:', cellValue);

    // Use the provided imagePath if available, otherwise check cellValue
    var actualImagePath = imagePath || cellValue;
    
    if (!actualImagePath || typeof actualImagePath !== 'string') {
      console.log('[QualityCellRenderer._addImageViewLink] actualImagePath 无效');
      return;
    }

    // Check if it's an image path (optional, since we already checked before calling)
    var isImagePath = actualImagePath.match(/\.(jpg|jpeg|png|gif|bmp|tiff|webp)$/i);
    console.log('[QualityCellRenderer._addImageViewLink] isImagePath:', !!isImagePath);

    // Check if there are location-based errors
    var locationErrors = errors.filter(function(error) {
      return error.locationX !== undefined || error.locationY !== undefined || (error.details && error.details.locations);
    });
    
    console.log('[Quality] 原始错误数量:', errors.length);
    console.log('[Quality] 包含位置信息的错误:', locationErrors.length);
    if (locationErrors.length > 0) {
      console.log('[Quality] 第一个错误的details:', JSON.stringify(locationErrors[0].details));
    }
    
    // Expand aggregated errors with details.locations into individual annotations
    var expandedAnnotations = [];
    locationErrors.forEach(function(err, index) {
      console.log('[Quality] 处理错误', index, 'errorType:', err.errorType, 'details:', err.details);
      if (err.details && err.details.locations && Array.isArray(err.details.locations) && err.details.locations.length > 0) {
        console.log('[Quality] 展开聚合错误，位置数量:', err.details.locations.length);
        err.details.locations.forEach(function(loc, locIndex) {
          console.log('[Quality] 位置', locIndex, ':', loc);
          if (loc && loc.length >= 4) {
            expandedAnnotations.push({
              rowIndex: err.rowIndex,
              column: err.column,
              hiddenFileName: err.hiddenFileName,
              value: err.value,
              errorType: err.errorType,
              message: err.message,
              locationX: loc[0],
              locationY: loc[1],
              locationWidth: loc[2],
              locationHeight: loc[3]
            });
          }
        });
      } else {
        expandedAnnotations.push(err);
      }
    });
    locationErrors = expandedAnnotations;
    
    console.log('[Quality] 展开后的位置错误数量:', locationErrors.length);
    var hasAnnotations = locationErrors.length > 0;

    // Create image view link
    var link = document.createElement('a');
    link.className = 'quality-image-view-link';
    link.href = 'javascript:;';
    link.innerHTML = hasAnnotations ? '⚠️' : '👁️';
    link.title = hasAnnotations
      ? '查看图像 (有 ' + locationErrors.length + ' 个问题标注)'
      : '查看图像';

    $(link).css({
      'margin-left': '8px',
      'cursor': 'pointer',
      'color': hasAnnotations ? '#ff6b6b' : '#007bff',
      'text-decoration': 'none',
      'font-size': '14px'
    });

    $(link).on('click', function(e) {
      console.log('[QualityCellRenderer.link.click] 图像查看链接被点击');
      e.preventDefault();
      e.stopPropagation();

      var resourceConfig = QualityAlignment._resourceConfig || {};
      var basePath = resourceConfig.path || '';
      
      console.log('[QualityCellRenderer.link.click] resourceConfig:', resourceConfig);
      
      var fullPath;
      var locationErrors = [];
      
      // Check if there are errors with hiddenFileName
      var errorWithHiddenFile = null;
      for (var i = 0; i < errors.length; i++) {
        if (errors[i].hiddenFileName) {
          errorWithHiddenFile = errors[i];
          break;
        }
      }
      
      if (errorWithHiddenFile && errorWithHiddenFile.hiddenFileName) {
        // Use hiddenFileName: folder path (value) + filename
        var folderPath = errorWithHiddenFile.value || '';
        var fileName = errorWithHiddenFile.hiddenFileName;
        
        console.log('[Quality-Path] 构建完整路径:');
        console.log('  folderPath (value):', folderPath);
        console.log('  fileName (hiddenFileName):', fileName);
        
        if (folderPath) {
          fullPath = folderPath + '/' + fileName;
        } else {
          fullPath = fileName;
        }
        
        console.log('  fullPath:', fullPath);
        
        // Collect location errors from all errors with same hiddenFileName
        locationErrors = errors.filter(function(err) {
          return err.hiddenFileName === errorWithHiddenFile.hiddenFileName && 
                 (err.locationX !== undefined || err.locationY !== undefined || (err.details && err.details.locations));
        });
        
        console.log('[Quality-2] 原始错误数量:', errors.length);
        console.log('[Quality-2] 包含位置信息的错误:', locationErrors.length);
        
        // Expand aggregated errors with details.locations into individual annotations
        var expandedAnnotations = [];
        locationErrors.forEach(function(err, index) {
          console.log('[Quality-2] 处理错误', index, 'errorType:', err.errorType, 'details:', err.details);
          if (err.details && err.details.locations && Array.isArray(err.details.locations) && err.details.locations.length > 0) {
            console.log('[Quality-2] 展开聚合错误，位置数量:', err.details.locations.length);
            err.details.locations.forEach(function(loc, locIndex) {
              console.log('[Quality-2] 位置', locIndex, ':', loc);
              if (loc && loc.length >= 4) {
                expandedAnnotations.push({
                  rowIndex: err.rowIndex,
                  column: err.column,
                  hiddenFileName: err.hiddenFileName,
                  value: err.value,
                  errorType: err.errorType,
                  message: err.message,
                  locationX: loc[0],
                  locationY: loc[1],
                  locationWidth: loc[2],
                  locationHeight: loc[3]
                });
              }
            });
          } else {
            // Regular error with direct location
            expandedAnnotations.push(err);
          }
        });
        locationErrors = expandedAnnotations;
        console.log('[Quality-2] 展开后的位置错误数量:', locationErrors.length);
      } else {
        // Fallback: use cellValue as before
        fullPath = cellValue;
        if (!fullPath.startsWith('/') && !fullPath.match(/^[a-zA-Z]:/)) {
          fullPath = basePath + '/' + fullPath;
        }
        
        locationErrors = errors.filter(function(error) {
          return error.locationX !== undefined || error.locationY !== undefined || (error.details && error.details.locations);
        });
        
        console.log('[Quality-3] 原始错误数量:', errors.length);
        console.log('[Quality-3] 包含位置信息的错误:', locationErrors.length);
        
        // Expand aggregated errors with details.locations into individual annotations
        var expandedAnnotations = [];
        locationErrors.forEach(function(err, index) {
          console.log('[Quality-3] 处理错误', index, 'errorType:', err.errorType, 'details:', err.details);
          if (err.details && err.details.locations && Array.isArray(err.details.locations) && err.details.locations.length > 0) {
            console.log('[Quality-3] 展开聚合错误，位置数量:', err.details.locations.length);
            err.details.locations.forEach(function(loc, locIndex) {
              console.log('[Quality-3] 位置', locIndex, ':', loc);
              if (loc && loc.length >= 4) {
                expandedAnnotations.push({
                  rowIndex: err.rowIndex,
                  column: err.column,
                  hiddenFileName: err.hiddenFileName,
                  value: err.value,
                  errorType: err.errorType,
                  message: err.message,
                  locationX: loc[0],
                  locationY: loc[1],
                  locationWidth: loc[2],
                  locationHeight: loc[3]
                });
              }
            });
          } else {
            expandedAnnotations.push(err);
          }
        });
        locationErrors = expandedAnnotations;
        console.log('[Quality-3] 展开后的位置错误数量:', locationErrors.length);
      }
      
      var resourcePath = fullPath.replace(/\\/g, '/');
      var hasAnnotations = locationErrors.length > 0;

      if (hasAnnotations && typeof QualityAlignment.showImageWithAnnotations === 'function') {
        QualityAlignment.showImageWithAnnotations(resourcePath, locationErrors);
      } else if (typeof FilePreviewDialog !== 'undefined') {
        // Use FilePreviewDialog for consistent preview experience
        var lastSlash = resourcePath.lastIndexOf('/');
        var rootPath = lastSlash > 0 ? resourcePath.substring(0, lastSlash) : '';
        var filePathOnly = lastSlash > 0 ? resourcePath.substring(lastSlash + 1) : resourcePath;
        
        console.log('[FilePreviewDialog] 调用参数:');
        console.log('  resourcePath:', resourcePath);
        console.log('  rootPath:', rootPath);
        console.log('  filePathOnly:', filePathOnly);
        console.log('  hiddenFileName:', errorWithHiddenFile ? errorWithHiddenFile.hiddenFileName : 'null');
        console.log('  value (folderPath):', errorWithHiddenFile ? errorWithHiddenFile.value : 'null');
        
        FilePreviewDialog.show(rootPath, filePathOnly, null);
      } else {
        window.open(resourcePath, '_blank');
      }
    });

    // Insert the link after the cell content
    var cellContent = $(td).contents().first();
    if (cellContent.length) {
      cellContent.after(link);
    } else {
      $(td).append(link);
    }
  }

  /**
   * Show tooltip near mouse position
   * @param {Event} event - Mouse event
   * @param {Array} errors - Array of error objects
   * @param {number} rowIndex - The row index
   * @param {string} columnName - The column name
   */
  static _showTooltip(event, errors, rowIndex, columnName) {
    // Clear any pending hide timeout
    if (QualityCellRenderer._hideTimeout) {
      clearTimeout(QualityCellRenderer._hideTimeout);
      QualityCellRenderer._hideTimeout = null;
    }

    var tooltip = $('#quality-error-tooltip');
    if (tooltip.length === 0) {
      tooltip = $('<div id="quality-error-tooltip" class="quality-error-tooltip"></div>');
      $('body').append(tooltip);
    }

    // Build tooltip content
    var content = '<div class="quality-tooltip-content">';
    errors.forEach(function(err, index) {
      var typeLabel = QualityAlignment._getErrorTypeLabel(err.errorType);
      var msg = QualityAlignment._formatErrorMessage(err);
      content += '<div class="quality-tooltip-item">';
      content += '<div class="quality-tooltip-header"><strong>' + typeLabel + ':</strong></div>';
      content += '<div class="quality-tooltip-message">' + msg + '</div>';

      // Add "更正" button for content errors with extractedValue
      if ((err.errorType === 'content_mismatch' || err.errorType === 'content_warning') && err.extractedValue) {
        content += '<button class="quality-correct-btn" data-row="' + rowIndex +
                   '" data-column="' + columnName +
                   '" data-value="' + QualityCellRenderer._escapeHtml(err.extractedValue) +
                   '" data-index="' + index + '">' +
                   $.i18n('data-quality-extension/correct-value') + '</button>';
      }
      content += '</div>';
    });
    content += '</div>';

    tooltip.html(content);
    tooltip.css({
      left: event.pageX + 10,
      top: event.pageY + 10,
      display: 'block'
    });

    // Allow mouse to enter tooltip
    tooltip.off('mouseenter mouseleave').on('mouseenter', function() {
      if (QualityCellRenderer._hideTimeout) {
        clearTimeout(QualityCellRenderer._hideTimeout);
        QualityCellRenderer._hideTimeout = null;
      }
    }).on('mouseleave', function() {
      QualityCellRenderer._scheduleHideTooltip();
    });

    // Bind click event for correct buttons
    tooltip.find('.quality-correct-btn').off('click').on('click', function(e) {
      e.stopPropagation();
      var $btn = $(this);
      var row = parseInt($btn.data('row'), 10);
      var column = $btn.data('column');
      var value = $btn.data('value');
      QualityCellRenderer._correctCellValue(row, column, value);
    });
  }

  /**
   * Schedule tooltip hide with delay
   */
  static _scheduleHideTooltip() {
    QualityCellRenderer._hideTimeout = setTimeout(function() {
      $('#quality-error-tooltip').hide();
    }, 200);
  }

  /**
   * Hide tooltip immediately
   */
  static _hideTooltip() {
    if (QualityCellRenderer._hideTimeout) {
      clearTimeout(QualityCellRenderer._hideTimeout);
      QualityCellRenderer._hideTimeout = null;
    }
    $('#quality-error-tooltip').hide();
  }

  /**
   * Escape HTML special characters
   */
  static _escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  /**
   * Correct cell value using OpenRefine's edit API
   * @param {number} rowIndex - The row index
   * @param {string} columnName - The column name
   * @param {string} newValue - The new value to set
   */
  static _correctCellValue(rowIndex, columnName, newValue) {
    // Hide tooltip
    QualityCellRenderer._hideTooltip();

    // Use OpenRefine's cell edit command
    Refine.postCSRF(
      "command/core/edit-one-cell",
      {
        project: theProject.id,
        row: rowIndex,
        cell: Refine.columnNameToColumnIndex(columnName),
        value: newValue,
        type: "string"
      },
      function(response) {
        if (response.code === "ok") {
          // Refresh the data table to show updated value
          Refine.update({ cellsChanged: true });

          // Remove error from the cell error map
          var errorKey = rowIndex + '_' + columnName;
          if (QualityAlignment._cellErrorMap && QualityAlignment._cellErrorMap[errorKey]) {
            // Filter out content errors for this cell
            QualityAlignment._cellErrorMap[errorKey] = QualityAlignment._cellErrorMap[errorKey].filter(function(err) {
              return err.errorType !== 'content_mismatch' && err.errorType !== 'content_warning';
            });
            // If no more errors, delete the key
            if (QualityAlignment._cellErrorMap[errorKey].length === 0) {
              delete QualityAlignment._cellErrorMap[errorKey];
            }
          }
        } else {
          alert($.i18n('data-quality-extension/correct-failed') + ': ' + (response.message || ''));
        }
      },
      "json"
    );
  }
}

// Static property for hide timeout
QualityCellRenderer._hideTimeout = null;

// Register the renderer to run first (before other renderers)
// It will return undefined to let other renderers process, but mark cells for post-processing
if (typeof CellRendererRegistry !== 'undefined') {
  CellRendererRegistry.addRenderer('quality-error', new QualityCellRenderer(), 'null');
  console.log('[QualityCellRenderer] Renderer registered successfully');
}