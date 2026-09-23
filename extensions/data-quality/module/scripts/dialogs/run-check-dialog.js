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
  // 当前件计时：AIMP 按件同步处理，件内无进度信号，用进入本件的时刻估算已耗时
  this._currentContentItemKey = null;
  this._contentItemStartTime = null;
};

RunCheckDialog.prototype._startCheck = function() {
  var self = this;

  // Check if any resource-dependent check is enabled
  var hasResourceCheck = false;
  var hasContentCheck = false;
  var hasImageQualityCheck = false;

  // Check file resource association check
  var resourceConfig = QualityAlignment._resourceConfig || {};
  if (resourceConfig.folderChecks) {
    var folderChecks = resourceConfig.folderChecks;
    if (folderChecks.existence || folderChecks.dataExistence || 
        folderChecks.nameFormat || folderChecks.sequential || folderChecks.emptyFolder) {
      hasResourceCheck = true;
    }
  }
  if (resourceConfig.fileChecks) {
    var fileChecks = resourceConfig.fileChecks;
    if (fileChecks.countMatch || fileChecks.nameFormat || fileChecks.sequential) {
      hasResourceCheck = true;
    }
  }

  // Check content comparison check
  hasContentCheck = QualityAlignment._contentRules && 
    QualityAlignment._contentRules.length > 0;

  // Check image quality check - need to load from backend
  // We'll check this after loading the rule

  console.log('[RunCheckDialog] 调试信息:');
  console.log('[RunCheckDialog] hasResourceCheck:', hasResourceCheck);
  console.log('[RunCheckDialog] hasContentCheck:', hasContentCheck);

  // If any resource-dependent check is enabled, verify resource configuration
  if (hasResourceCheck || hasContentCheck) {
    var basePath = resourceConfig.basePath || '';
    var pathFields = resourceConfig.pathFields || [];
    var isResourceConfigured = pathFields.length > 0 || basePath.length > 0;

    console.log('[RunCheckDialog] basePath:', basePath);
    console.log('[RunCheckDialog] pathFields:', pathFields);
    console.log('[RunCheckDialog] isResourceConfigured:', isResourceConfigured);

    if (!isResourceConfigured) {
      this._isRunning = false;
      this._showResourceConfigWarning();
      return;
    }
  }

  // 内容比对列未匹配时阻断启动：
  // 后端解析不到列会静默跳过该要素，产出「无错误」的假通过，掩盖真正的比对错位
  if (hasContentCheck) {
    var unmatchedColumns = [];
    QualityAlignment._contentRules.forEach(function(rule) {
      if (!rule.column) {
        return;
      }
      var matches = QualityAlignment._findMatchingColumns(rule.column) || [];
      var hasMatch = matches.some(function(m) { return m.matched; });
      if (!hasMatch && unmatchedColumns.indexOf(rule.column) === -1) {
        unmatchedColumns.push(rule.column);
      }
    });
    if (unmatchedColumns.length > 0) {
      this._isRunning = false;
      alert($.i18n('data-quality-extension/unmatched-columns-block').replace('{0}', unmatchedColumns.join('、'))
        + '\n\n' + $.i18n('data-quality-extension/unmatched-columns-hint'));
      return;
    }
  }

  // Load image quality rule to check if it's enabled
  this._loadImageQualityRuleAndStartCheck();
};

RunCheckDialog.prototype._loadImageQualityRuleAndStartCheck = function() {
  var self = this;

  Refine.wrapCSRF(function(csrfToken) {
    var url = 'command/data-quality/get-image-quality-rule?' + $.param({ project: theProject.id, csrf_token: csrfToken });

    $.getJSON(url, function(data) {
      var hasImageQualityCheck = false;
      if (data.rule && data.rule.categories && data.rule.categories.length > 0) {
        hasImageQualityCheck = data.rule.categories.some(function(category) {
          return category.items.some(function(item) {
            return item.enabled;
          });
        });
      }

      console.log('[RunCheckDialog] hasImageQualityCheck:', hasImageQualityCheck);

      // If image quality check is enabled, verify resource configuration
      if (hasImageQualityCheck) {
        var resourceConfig = QualityAlignment._resourceConfig || {};
        var basePath = resourceConfig.basePath || '';
        var pathFields = resourceConfig.pathFields || [];
        var isResourceConfigured = pathFields.length > 0 || basePath.length > 0;

        console.log('[RunCheckDialog] Image quality check enabled, checking resource config...');
        console.log('[RunCheckDialog] basePath:', basePath);
        console.log('[RunCheckDialog] pathFields:', pathFields);
        console.log('[RunCheckDialog] isResourceConfigured:', isResourceConfigured);

        if (!isResourceConfigured) {
          self._isRunning = false;
          self._showResourceConfigWarning();
          return;
        }
      }

      // All checks passed, start the check
      self._doStartCheck();
    }).fail(function() {
      // Failed to load image quality rule, but still start the check
      console.warn('[RunCheckDialog] Failed to load image quality rule, proceeding with check...');
      self._doStartCheck();
    });
  });
};

RunCheckDialog.prototype._doStartCheck = function() {
  var self = this;

  this._isRunning = true;
  this._isPaused = false;
  this._updateButtonStates();
  this._elmts.progressBar.show();
  this._elmts.statusText.text($.i18n('data-quality-extension/checking'));
  this._elmts.statusSubtext.text('');
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

  // 一律使用异步模式：AIMP 按「件」同步处理整件，单件可达数十秒，
  // 同步模式前端只能干等到结束、进度条全程停在起点，没有任何进度可展示。
  // 注意：不能依赖 QualityAlignment._contentRules 判断是否有内容检查——
  // 重启浏览器后若未打开过规则配置页，该变量为空，会静默退化为同步模式，导致进度完全不可见。
  var useAsync = true;

  console.log('[RunCheckDialog] aimpServiceUrl:', aimpServiceUrl);
  console.log('[RunCheckDialog] _contentRules:', QualityAlignment._contentRules);
  console.log('[RunCheckDialog] _contentRules.length:', QualityAlignment._contentRules ? QualityAlignment._contentRules.length : 'N/A');
  console.log('[RunCheckDialog] hasContentCheck:', hasContentCheck);
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

  // 第一行：当前进行中的检查（阶段 + 件进度 + 正在处理的件）
  var line1 = phase || '';
  if (progress.contentCheckTotal > 0) {
    line1 += ' (' + progress.contentCheckProcessed + '/' + progress.contentCheckTotal + ')';
  }

  // 当前件信息：AIMP 一件（含其全部页）处理完才回包，件内没有更细的进度可分，
  // 这里显示"正在处理哪一件、本件几页、已耗时"，让等待过程可见。
  var currentItemKey = progress.contentCheckCurrentItem || null;
  var currentItemPages = progress.contentCheckCurrentItemPages || 0;
  if (currentItemKey && currentItemPages > 0) {
    if (this._currentContentItemKey !== currentItemKey) {
      this._currentContentItemKey = currentItemKey;
      this._contentItemStartTime = Date.now();
    }
    var elapsedSeconds = this._contentItemStartTime
      ? Math.round((Date.now() - this._contentItemStartTime) / 1000) : 0;
    line1 += ' ' + $.i18n('data-quality-extension/content-check-current-item')
      .replace('{0}', currentItemKey).replace('{1}', currentItemPages);
    line1 += ' ' + $.i18n('data-quality-extension/elapsed-seconds').replace('{0}', elapsedSeconds);
  } else {
    this._currentContentItemKey = null;
    this._contentItemStartTime = null;
  }

  if (progress.typoCheckTotal > 0) {
    line1 += ' ' + $.i18n('data-quality-extension/typo-check-tab') + ': '
      + progress.typoCheckProcessed + '/' + progress.typoCheckTotal;
  }

  // 第二行：各检查项的累计错误数与明细
  var formatErrors = progress.formatErrors || 0;
  var resourceErrors = progress.resourceErrors || 0;
  var contentErrors = progress.contentErrors || 0;
  var imageQualityErrors = progress.imageQualityErrors || 0;

  var errorParts = [];
  if (formatErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/format-check-tab') + ': ' + formatErrors);
  }
  if (resourceErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/resource-check-tab') + ': ' + resourceErrors);
  }
  if (contentErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/content-check-tab') + ': ' + contentErrors);
  }
  if (imageQualityErrors > 0) {
    errorParts.push($.i18n('data-quality-extension/image-quality-check-tab') + ': ' + imageQualityErrors);
  }

  var line2 = '';
  if (errorParts.length > 0) {
    var totalErrors = formatErrors + resourceErrors + contentErrors + imageQualityErrors;
    line2 = $.i18n('data-quality-extension/total-errors-line')
      .replace('{0}', totalErrors).replace('{1}', errorParts.join(', '));
  }

  this._elmts.statusText.text(line1);
  this._elmts.statusSubtext.text(line2);
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
    typoErrors: response.summary ? response.summary.typoErrors : 0,
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
  var completeLine1 = $.i18n('data-quality-extension/check-complete-total').replace('{0}', totalErrors);

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

  var completeLine2 = detailParts.length > 0 ? detailParts.join(', ') : '';

  this._elmts.statusText.text(completeLine1);
  this._elmts.statusSubtext.text(completeLine2);
  this._elmts.stopButton.hide();
  this._elmts.cancelButton.text($.i18n('data-quality-extension/view-results'));

  // 在对话框中显示服务不可用提示
  if (response.serviceUnavailable) {
    var suMessage = response.serviceUnavailableMessage || '';
    var alertText;
    if (suMessage.indexOf('UNMATCHED_COLUMNS:') === 0) {
      // 列头未匹配：与 AI 服务不可用区分提示
      alertText = '<strong>' + $.i18n('data-quality-extension/unmatched-columns-block')
        .replace('{0}', suMessage.substring('UNMATCHED_COLUMNS:'.length)) + '</strong>';
    } else {
      alertText = '<strong>' + $.i18n('data-quality-extension/aimp-service-unavailable') + '</strong> ' + $.i18n('data-quality-extension/aimp-service-incomplete');
    }
    var alertHtml = '<div style="background-color: #f8d7da; border: 1px solid #f5c6cb; border-radius: 4px; padding: 15px; margin: 15px 0; color: #b82e3bff;">' +
      '<div style="display: flex; align-items: center;">' +
        '<img src="images/extensions/triangle-exclamation.svg" style="width: 20px; height: 20px; margin-right: 8px; flex-shrink: 0;" />' +
        '<span>' + alertText + '</span>' +
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

RunCheckDialog.prototype._showResourceConfigWarning = function() {
  var self = this;
  var message = $.i18n('data-quality-extension/resource-config-warning-message');

  if (confirm(message + '\n\n' + $.i18n('data-quality-extension/go-to-resource-config') + '?')) {
    DialogSystem.dismissUntil(self._level - 1);
    QualityAlignment.launch(false);
    setTimeout(function() {
      QualityAlignment.switchTab('#quality-rules-panel', false);
      setTimeout(function() {
        QualityAlignment._switchSubTab('resource-check');
        setTimeout(function() {
          QualityAlignment._showPathConfigDialog();
        }, 200);
      }, 100);
    }, 100);
  }
};

