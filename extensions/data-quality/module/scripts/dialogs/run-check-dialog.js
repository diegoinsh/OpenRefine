/**
 * Data Quality Extension - Run Check Dialog
 *
 * Modal dialog for running quality checks with progress display
 * Supports async execution with progress polling
 */

function RunCheckDialog() {
  this._createDialog();
}

RunCheckDialog.prototype._createDialog = function() {
  var self = this;

  var frame = $(DOM.loadHTML("data-quality", "scripts/dialogs/run-check-dialog.html"));
  this._elmts = DOM.bind(frame);

  this._elmts.dialogHeader.text($.i18n('data-quality-extension/run-check-dialog-title'));
  this._elmts.startButton.text($.i18n('data-quality-extension/start-check'));
  this._elmts.stopButton.text($.i18n('data-quality-extension/stop-check'));
  this._elmts.cancelButton.text($.i18n('data-quality-extension/cancel'));

  this._elmts.stopButton.hide();

  this._elmts.startButton.on('click', function() {
    self._startCheck();
  });

  this._elmts.stopButton.on('click', function() {
    self._stopCheck();
  });

  this._elmts.cancelButton.on('click', function() {
    DialogSystem.dismissUntil(self._level - 1);
  });

  this._level = DialogSystem.showDialog(frame);

  this._isRunning = false;
  this._checkResult = null;
  this._taskId = null;
  this._pollInterval = null;
};

RunCheckDialog.prototype._startCheck = function() {
  var self = this;

  this._isRunning = true;
  this._elmts.startButton.hide();
  this._elmts.stopButton.show();
  this._elmts.progressBar.show();
  this._elmts.statusText.text($.i18n('data-quality-extension/checking'));
  this._elmts.progressFill.css('width', '0%');
  this._elmts.progressText.text('0%');

  // Get AIMP service URL if configured
  var aimpServiceUrl = '';
  if (QualityAlignment._aimpConfig && QualityAlignment._aimpConfig.serviceUrl) {
    aimpServiceUrl = QualityAlignment._aimpConfig.serviceUrl;
  }

  // Check if content check is enabled (has AIMP URL and content rules)
  var hasContentCheck = aimpServiceUrl && QualityAlignment._rules &&
    QualityAlignment._rules.contentRules && QualityAlignment._rules.contentRules.length > 0;

  // Use async mode if content check is enabled (for progress tracking)
  var useAsync = hasContentCheck;

  // Call backend API to run check
  Refine.postCSRF(
    "command/data-quality/run-quality-check",
    {
      project: theProject.id,
      aimpServiceUrl: aimpServiceUrl,
      async: useAsync ? 'true' : 'false'
    },
    function(response) {
      if (response.code === "ok") {
        if (response.async && response.taskId) {
          // Async mode: start polling for progress
          self._taskId = response.taskId;
          self._startProgressPolling();
        } else {
          // Sync mode: result is ready
          self._isRunning = false;
          self._checkResult = response;
          self._onCheckComplete(response);
        }
      } else {
        self._isRunning = false;
        self._onCheckError(response.message || 'Unknown error');
      }
    },
    "json"
  );
};

RunCheckDialog.prototype._stopCheck = function() {
  this._isRunning = false;
  this._stopProgressPolling();
  DialogSystem.dismissUntil(this._level - 1);
};

RunCheckDialog.prototype._startProgressPolling = function() {
  var self = this;

  // Poll every 500ms
  this._pollInterval = setInterval(function() {
    self._pollProgress();
  }, 500);
};

RunCheckDialog.prototype._stopProgressPolling = function() {
  if (this._pollInterval) {
    clearInterval(this._pollInterval);
    this._pollInterval = null;
  }
};

RunCheckDialog.prototype._pollProgress = function() {
  var self = this;

  if (!this._taskId || !this._isRunning) {
    this._stopProgressPolling();
    return;
  }

  $.ajax({
    url: "command/data-quality/get-check-progress",
    type: "GET",
    data: { taskId: this._taskId },
    dataType: "json",
    success: function(response) {
      if (response.code === "ok") {
        self._updateProgress(response);

        // Check if task is completed
        if (response.status === "COMPLETED") {
          self._stopProgressPolling();
          self._isRunning = false;
          if (response.result) {
            self._checkResult = response.result;
            self._onCheckComplete(response.result);
          }
        } else if (response.status === "FAILED") {
          self._stopProgressPolling();
          self._isRunning = false;
          self._onCheckError(response.errorMessage || 'Task failed');
        }
      }
    },
    error: function(xhr, status, error) {
      console.error("Error polling progress:", error);
    }
  });
};

RunCheckDialog.prototype._updateProgress = function(progress) {
  var percent = progress.progress || 0;
  var phase = progress.currentPhase || '';

  this._elmts.progressFill.css('width', percent + '%');
  this._elmts.progressText.text(Math.round(percent) + '%');

  // Build status text with phase and counts
  var statusParts = [];
  if (phase) {
    statusParts.push(phase);
  }

  // Show content check progress if available
  if (progress.contentCheckTotal > 0) {
    statusParts.push('(' + progress.contentCheckProcessed + '/' + progress.contentCheckTotal + ')');
  }

  // Show error counts
  var errorParts = [];
  if (progress.formatErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/format-check-tab') + ': ' + progress.formatErrors);
  }
  if (progress.resourceErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/resource-check-tab') + ': ' + progress.resourceErrors);
  }
  if (progress.contentErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/content-check-tab') + ': ' + progress.contentErrors);
  }

  if (errorParts.length > 0) {
    statusParts.push($.i18n('data-quality-extension/error-count') + ': ' + errorParts.join(', '));
  }

  this._elmts.statusText.text(statusParts.join(' '));
};

RunCheckDialog.prototype._onCheckComplete = function(response) {
  var self = this;

  this._elmts.progressFill.css('width', '100%');
  this._elmts.progressText.text('100%');

  // Build result for QualityAlignment from new response format
  var result = {
    totalRows: response.summary ? response.summary.totalRows : 0,
    errors: response.errors || [],
    passedRows: 0,
    failedRows: 0,
    formatErrors: response.summary ? response.summary.formatErrors : 0,
    resourceErrors: response.summary ? response.summary.resourceErrors : 0,
    contentErrors: response.summary ? response.summary.contentErrors : 0,
    // Add summary object for export
    summary: {
      totalRows: response.summary ? response.summary.totalRows : 0,
      totalErrors: response.summary ? response.summary.totalErrors : 0,
      formatErrors: response.summary ? response.summary.formatErrors : 0,
      resourceErrors: response.summary ? response.summary.resourceErrors : 0,
      contentErrors: response.summary ? response.summary.contentErrors : 0
    }
  };

  var totalErrors = result.errors.length;
  var statusMsg = $.i18n('data-quality-extension/check-complete') + ' - ' +
    $.i18n('data-quality-extension/error-count') + ': ' + totalErrors;

  if (result.formatErrors > 0) {
    statusMsg += ' (' + $.i18n('data-quality-extension/format-check-tab') + ': ' + result.formatErrors + ')';
  }
  if (result.resourceErrors > 0) {
    statusMsg += ' (' + $.i18n('data-quality-extension/resource-check-tab') + ': ' + result.resourceErrors + ')';
  }
  if (result.contentErrors > 0) {
    statusMsg += ' (' + $.i18n('data-quality-extension/content-check-tab') + ': ' + result.contentErrors + ')';
  }

  this._elmts.statusText.text(statusMsg);
  this._elmts.stopButton.hide();
  this._elmts.cancelButton.text($.i18n('data-quality-extension/view-results'));

  // Store result in QualityAlignment
  QualityAlignment._lastCheckResult = result;
  QualityAlignment._currentResults = result;

  // Build cell error map for cell marking and refresh data table
  QualityAlignment._buildCellErrorMap();
  QualityAlignment._refreshDataTable();

  // Save result to backend for persistence
  Refine.postCSRF(
    "command/data-quality/save-quality-result",
    {
      project: theProject.id,
      result: JSON.stringify(result)
    },
    function(saveResponse) {
      if (saveResponse.code !== "ok") {
        console.warn("Failed to save quality result:", saveResponse);
      }
    },
    "json"
  );

  // Update cancel button to view results
  this._elmts.cancelButton.off('click').on('click', function() {
    DialogSystem.dismissUntil(self._level - 1);
    QualityAlignment.launch(true); // Switch to results tab
  });
};

RunCheckDialog.prototype._onCheckError = function(message) {
  this._elmts.statusText.text($.i18n('data-quality-extension/check-error') + ': ' + message);
  this._elmts.stopButton.hide();
  this._elmts.startButton.show();
  this._elmts.progressBar.hide();
};

