/**
 * Quality Error Cell Renderer
 * Adds error markers to cells that have quality check errors
 * Similar to Excel's red triangle in the corner
 */
class QualityCellRenderer {
  render(rowIndex, cellIndex, cell, cellUI) {
    // Check if there are quality errors for this cell
    if (typeof QualityAlignment === 'undefined' || !QualityAlignment._cellErrorMap) {
      return undefined; // Let other renderers handle it
    }

    var columnName = Refine.cellIndexToColumn(cellIndex);
    if (!columnName) {
      return undefined;
    }
    columnName = columnName.name;

    var errorKey = rowIndex + '_' + columnName;
    var errors = QualityAlignment._cellErrorMap[errorKey];

    if (!errors || errors.length === 0) {
      return undefined; // No errors, let other renderers handle it
    }

    // We have errors - let another renderer create the base content first
    // Then we'll add the error marker
    // Return undefined to let other renderers process, but mark the cell for post-processing

    // Store error info on the cell for post-processing
    if (cellUI && cellUI._td) {
      // Use setTimeout to ensure other renderers have finished
      setTimeout(function() {
        QualityCellRenderer._addErrorMarker(cellUI._td, errors, rowIndex, columnName);
      }, 0);
    }

    return undefined; // Let other renderers handle the actual content
  }

  /**
   * Add error marker to a cell
   * @param {HTMLElement} td - The table cell element
   * @param {Array} errors - Array of error objects for this cell
   * @param {number} rowIndex - The row index
   * @param {string} columnName - The column name
   */
  static _addErrorMarker(td, errors, rowIndex, columnName) {
    // Check if marker already exists
    if ($(td).find('.quality-error-marker').length > 0) {
      return;
    }

    // Create error marker (red triangle in bottom-right corner)
    var marker = document.createElement('div');
    marker.className = 'quality-error-marker';

    // Add marker to cell
    $(td).css('position', 'relative');
    $(td).append(marker);

    // Add tooltip on hover with delayed hide
    $(marker).on('mouseenter', function(e) {
      QualityCellRenderer._showTooltip(e, errors, rowIndex, columnName);
    }).on('mouseleave', function() {
      QualityCellRenderer._scheduleHideTooltip();
    });
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

