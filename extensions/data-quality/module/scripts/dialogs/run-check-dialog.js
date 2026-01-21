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
  this._elmts.pauseButton.text('⏸ 暂停');
  this._elmts.resumeButton.text('▶ 继续');

  this._elmts.startButton.on('click', function() {
    self._startCheck();
  });

  this._elmts.pauseButton.on('click', function() {
    self._pauseCheck();
  });

  this._elmts.resumeButton.on('click', function() {
    self._resumeCheck();
  });

  this._elmts.stopButton.on('click', function() {
    self._stopCheck();
  });

  this._elmts.cancelButton.on('click', function() {
    DialogSystem.dismissUntil(self._level - 1);
  });

  this._level = DialogSystem.showDialog(frame);

  this._isRunning = false;
  this._isPaused = false;
  this._checkResult = null;
  this._taskId = null;
  this._pollInterval = null;
};

RunCheckDialog.prototype._startCheck = function() {
  var self = this;

  this._isRunning = true;
  this._isPaused = false;
  this._updateButtonStates();
  this._elmts.progressBar.show();
  this._elmts.statusText.text($.i18n('data-quality-extension/checking'));
  this._elmts.progressFill.css('width', '0%');
  this._elmts.progressText.text('0%');

  // Get AIMP service URL if configured
  var aimpServiceUrl = '';
  if (QualityAlignment._aimpConfig && QualityAlignment._aimpConfig.serviceUrl) {
    aimpServiceUrl = QualityAlignment._aimpConfig.serviceUrl;
  }

  // Check if content check is enabled (has content rules)
  var hasContentCheck = QualityAlignment._contentRules && 
    QualityAlignment._contentRules.length > 0;

  // Check if image quality check is enabled (has image quality rule with enabled checks)
  var hasImageQualityCheck = false;
  if (QualityAlignment._imageQualityRule) {
    var rule = QualityAlignment._imageQualityRule;
    hasImageQualityCheck = (
      rule.enableUsability || rule.enableIntegrity || 
      rule.enableUniqueness || rule.enableSecurity || 
      rule.enableOther
    );
  }

  // Use async mode if any check is enabled (for progress tracking)
  // AI service URL will be passed to backend; if empty, AI checks will be skipped but progress still works
  var useAsync = hasContentCheck || hasImageQualityCheck;

  console.log('[RunCheckDialog] 调试信息:');
  console.log('[RunCheckDialog] aimpServiceUrl:', aimpServiceUrl);
  console.log('[RunCheckDialog] _contentRules:', QualityAlignment._contentRules);
  console.log('[RunCheckDialog] _contentRules.length:', QualityAlignment._contentRules ? QualityAlignment._contentRules.length : 'N/A');
  console.log('[RunCheckDialog] _imageQualityRule:', QualityAlignment._imageQualityRule);
  console.log('[RunCheckDialog] hasContentCheck:', hasContentCheck);
  console.log('[RunCheckDialog] hasImageQualityCheck:', hasImageQualityCheck);
  console.log('[RunCheckDialog] useAsync:', useAsync);

  // Call backend API to run check
  Refine.postCSRF(
    "command/data-quality/run-quality-check",
    {
      project: theProject.id,
      aimpServiceUrl: aimpServiceUrl,
      async: useAsync ? 'true' : 'false'
    },
    function(response) {
      console.log('[RunCheckDialog] 后端响应:', response);
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
        var errorMessage = response.errorKey ? 
          $.i18n('data-quality-extension/' + response.errorKey) : 
          (response.message || 'Unknown error');
        self._onCheckError(errorMessage);
      }
    },
    "json"
  );
};

RunCheckDialog.prototype._stopCheck = function() {
  var self = this;

  if (!this._taskId) {
    this._isRunning = false;
    this._stopProgressPolling();
    DialogSystem.dismissUntil(this._level - 1);
    return;
  }

  // Call task control API to cancel
  Refine.postCSRF(
    "command/data-quality/task-control",
    { taskId: this._taskId, action: 'cancel' },
    function(response) {
      self._isRunning = false;
      self._isPaused = false;
      self._stopProgressPolling();
      DialogSystem.dismissUntil(self._level - 1);
    },
    "json"
  );
};

RunCheckDialog.prototype._pauseCheck = function() {
  var self = this;

  if (!this._taskId) return;

  Refine.postCSRF(
    "command/data-quality/task-control",
    { taskId: this._taskId, action: 'pause' },
    function(response) {
      if (response.code === 'ok') {
        self._isPaused = true;
        self._updateButtonStates();
        self._elmts.statusText.text('已暂停 - 点击"继续"恢复检查');
      }
    },
    "json"
  );
};

RunCheckDialog.prototype._resumeCheck = function() {
  var self = this;

  if (!this._taskId) return;

  Refine.postCSRF(
    "command/data-quality/task-control",
    { taskId: this._taskId, action: 'resume' },
    function(response) {
      if (response.code === 'ok') {
        self._isPaused = false;
        self._updateButtonStates();
        self._elmts.statusText.text($.i18n('data-quality-extension/checking'));
      }
    },
    "json"
  );
};

RunCheckDialog.prototype._updateButtonStates = function() {
  if (this._isRunning) {
    this._elmts.startButton.hide();
    this._elmts.stopButton.show();

    if (this._isPaused) {
      this._elmts.pauseButton.hide();
      this._elmts.resumeButton.show();
    } else {
      this._elmts.pauseButton.show();
      this._elmts.resumeButton.hide();
    }
  } else {
    this._elmts.startButton.show();
    this._elmts.pauseButton.hide();
    this._elmts.resumeButton.hide();
    this._elmts.stopButton.hide();
  }
};

RunCheckDialog.prototype._startProgressPolling = function() {
  var self = this;

  console.log('[RunCheckDialog] 启动进度轮询，taskId:', this._taskId);

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
    console.log('[RunCheckDialog] 轮询终止: taskId=', this._taskId, 'isRunning=', this._isRunning);
    this._stopProgressPolling();
    return;
  }

  console.log('[RunCheckDialog] 轮询中... taskId:', this._taskId);

  $.ajax({
    url: "command/data-quality/get-check-progress",
    type: "GET",
    data: { taskId: this._taskId },
    dataType: "json",
    success: function(response) {
      console.log('[RunCheckDialog] 轮询响应:', response);
      if (response.code === "ok") {
        self._updateProgress(response);

        // Check if task status changed
        if (response.status === "COMPLETED") {
          self._stopProgressPolling();
          self._isRunning = false;
          self._updateButtonStates();
          if (response.result) {
            self._checkResult = response.result;
            self._onCheckComplete(response.result);
          }
        } else if (response.status === "FAILED") {
          self._stopProgressPolling();
          self._isRunning = false;
          self._updateButtonStates();
          var asyncErrorMessage = response.errorKey ? 
            $.i18n('data-quality-extension/' + response.errorKey) : 
            (response.errorMessage || 'Task failed');
          self._onCheckError(asyncErrorMessage);
        } else if (response.status === "PAUSED") {
          self._isPaused = true;
          self._updateButtonStates();
          self._elmts.statusText.text('已暂停 - 点击"继续"恢复检查');
        } else if (response.status === "RUNNING") {
          if (self._isPaused) {
            self._isPaused = false;
            self._updateButtonStates();
          }
        } else if (response.status === "CANCELLED") {
          self._stopProgressPolling();
          self._isRunning = false;
          self._updateButtonStates();
          self._elmts.statusText.text('检查已取消');
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
  if (progress.imageQualityErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/image-quality-check-tab') + ': ' + progress.imageQualityErrors);
  }

  if (errorParts.length > 0) {
    statusParts.push($.i18n('data-quality-extension/error-count') + ': ' + errorParts.join(', '));
  }

  this._elmts.statusText.text(statusParts.join(' '));
};

RunCheckDialog.prototype._onCheckComplete = function(response) {
  var self = this;

  console.log('[RunCheckDialog] 收到后端响应:', response);
  console.log('[RunCheckDialog] serviceUnavailable:', response.serviceUnavailable);
  console.log('[RunCheckDialog] serviceUnavailableMessage:', response.serviceUnavailableMessage);
  console.log('[RunCheckDialog] imageQualityResult:', response.imageQualityResult);

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
    imageQualityErrors: response.summary ? response.summary.imageQualityErrors : 0,
    // 添加服务不可用状态
    serviceUnavailable: response.serviceUnavailable || false,
    serviceUnavailableMessage: response.serviceUnavailableMessage || null,
    // Add imageQualityResult for statistics export
    imageQualityResult: response.imageQualityResult || null,
    // 添加开始和结束时间
    startTime: response.startTime || 0,
    endTime: response.endTime || 0,
    // Add summary object for export
    summary: {
      totalRows: response.summary ? response.summary.totalRows : 0,
      totalErrors: response.summary ? response.summary.totalErrors : 0,
      formatErrors: response.summary ? response.summary.formatErrors : 0,
      resourceErrors: response.summary ? response.summary.resourceErrors : 0,
      contentErrors: response.summary ? response.summary.contentErrors : 0,
      imageQualityErrors: response.summary ? response.summary.imageQualityErrors : 0
    }
  };

  console.log('[RunCheckDialog] 构建的result对象:', result);

  var totalErrors = result.errors.length;
  var statusMsg = $.i18n('data-quality-extension/check-complete') + ' - ' +
    $.i18n('data-quality-extension/error-count') + ': ' + totalErrors;

  var detailParts = [];
  if (result.formatErrors > 0) {
    detailParts.push($.i18n('data-quality-extension/format-check-tab') + ': ' + result.formatErrors);
  }
  if (result.resourceErrors > 0) {
    detailParts.push($.i18n('data-quality-extension/resource-check-tab') + ': ' + result.resourceErrors);
  }
  if (result.contentErrors > 0) {
    detailParts.push($.i18n('data-quality-extension/content-check-tab') + ': ' + result.contentErrors);
  }
  if (result.imageQualityErrors > 0) {
    detailParts.push($.i18n('data-quality-extension/image-quality-check-tab') + ': ' + result.imageQualityErrors);
  }

  if (detailParts.length > 0) {
    statusMsg += ' (' + detailParts.join(', ') + ')';
  }

  this._elmts.statusText.text(statusMsg);
  this._elmts.stopButton.hide();
  this._elmts.cancelButton.text($.i18n('data-quality-extension/view-results'));

  // 在对话框中显示服务不可用提示
  if (response.serviceUnavailable) {
    var alertHtml = '<div style="background-color: #f8d7da; border: 1px solid #f5c6cb; border-radius: 4px; padding: 15px; margin: 15px 0; color: #b82e3bff;">' +
      '<div style="display: flex; align-items: center;">' +
        '<img src="images/extensions/triangle-exclamation.svg" style="width: 20px; height: 20px; margin-right: 8px; flex-shrink: 0;" />' +
        '<span><strong>' + $.i18n('data-quality-extension/aimp-service-unavailable') + '</strong> ' + $.i18n('data-quality-extension/aimp-service-incomplete') + '</span>' +
      '</div>' +
      '</div>';
    
    // 在状态文本后插入警告
    this._elmts.statusText.after(alertHtml);
  }

  // Store result in QualityAlignment
  QualityAlignment._lastCheckResult = result;
  QualityAlignment._currentResults = result;

  console.log('[RunCheckDialog] _currentResults设置完成:', QualityAlignment._currentResults);
  console.log('[RunCheckDialog] _lastCheckResult设置完成:', QualityAlignment._lastCheckResult);

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
    console.log('[RunCheckDialog] 点击了查看结果按钮');
    DialogSystem.dismissUntil(self._level - 1);
    console.log('[RunCheckDialog] 调用QualityAlignment.launch(true)');
    QualityAlignment.launch(true); // Switch to results tab
  });
};

RunCheckDialog.prototype._onCheckError = function(message) {
  this._elmts.statusText.text($.i18n('data-quality-extension/check-error') + ': ' + message);
  this._elmts.stopButton.hide();
  this._elmts.startButton.show();
  this._elmts.progressBar.hide();
};

