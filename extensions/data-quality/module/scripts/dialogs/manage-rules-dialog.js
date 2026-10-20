/**
 * Data Quality Extension - Manage Rules Dialog
 *
 * Modal dialog for managing quality rules (view current project rules summary)
 */

function ManageRulesDialog() {
  this._createDialog();
}

ManageRulesDialog.prototype._createDialog = function() {
  var self = this;

  var frame = $(DOM.loadHTML("data-quality", "scripts/dialogs/manage-rules-dialog.html"));
  this._elmts = DOM.bind(frame);

  this._elmts.dialogHeader.text($.i18n('data-quality-extension/manage-dialog-title'));
  this._elmts.closeButton.text($.i18n('data-quality-extension/close'));
  this._elmts.clearButton.text($.i18n('data-quality-extension/clear-rules'));
  this._elmts.exportButton.text($.i18n('data-quality-extension/export-rules'));
  this._elmts.importButton.text($.i18n('data-quality-extension/import-rules'));

  this._elmts.closeButton.on('click', function() {
    DialogSystem.dismissUntil(self._level - 1);
  });

  this._elmts.clearButton.on('click', function() {
    self._clearRules();
  });

  this._elmts.exportButton.on('click', function() {
    self._exportRules();
  });

  this._elmts.importButton.on('click', function() {
    self._importRules();
  });

  this._level = DialogSystem.showDialog(frame);

  this._loadRulesSummary();
};

ManageRulesDialog.prototype._loadRulesSummary = function() {
  var self = this;
  var rulesList = this._elmts.rulesList;
  rulesList.empty();

  // Load from QualityAlignment's current rules
  var formatRules = QualityAlignment._formatRules || {};
  var resourceConfig = QualityAlignment._resourceConfig || {};
  var contentRules = QualityAlignment._contentRules || [];

  var formatCount = Object.keys(formatRules).length;
  var contentCount = contentRules.length;
  // Check if resource config has basePath or pathFields configured
  var hasResourceConfig = (resourceConfig.pathFields && resourceConfig.pathFields.length > 0) || !!resourceConfig.basePath;

  if (formatCount === 0 && contentCount === 0 && !hasResourceConfig) {
    rulesList.html('<p class="no-rules">' + $.i18n('data-quality-extension/no-rules-configured') + '</p>');
    return;
  }

  // Format rules summary
  if (formatCount > 0) {
    var formatSection = $('<div class="rules-section"></div>').appendTo(rulesList);
    $('<h4></h4>').text($.i18n('data-quality-extension/format-check-tab') + ' (' + formatCount + ' ' + $.i18n('data-quality-extension/columns') + ')').appendTo(formatSection);

    var formatList = $('<ul></ul>').appendTo(formatSection);
    Object.keys(formatRules).forEach(function(colName) {
      var rule = formatRules[colName];
      var checks = [];
      if (rule.nonEmpty) checks.push($.i18n('data-quality-extension/check-non-empty'));
      if (rule.unique) checks.push($.i18n('data-quality-extension/check-unique'));
      if (rule.regex) checks.push($.i18n('data-quality-extension/check-regex'));
      if (rule.dateFormat) checks.push($.i18n('data-quality-extension/check-date-format'));
      if (rule.valueList && rule.valueList.length > 0) checks.push($.i18n('data-quality-extension/check-value-list'));

      $('<li></li>').text(colName + ': ' + (checks.join(', ') || '-')).appendTo(formatList);
    });
  }

  // Resource config summary
  if (hasResourceConfig) {
    var resourceSection = $('<div class="rules-section"></div>').appendTo(rulesList);
    $('<h4></h4>').text($.i18n('data-quality-extension/resource-check-tab')).appendTo(resourceSection);

    var resourceInfo = $('<div></div>').appendTo(resourceSection);
    // Show base path
    if (resourceConfig.basePath) {
      $('<p></p>').text($.i18n('data-quality-extension/base-path') + ': ' + resourceConfig.basePath).appendTo(resourceInfo);
    }
    // Show path fields
    $('<p></p>').text($.i18n('data-quality-extension/path-fields') + ': ' + resourceConfig.pathFields.join(', ')).appendTo(resourceInfo);
    // Show folder checks
    var folderChecks = [];
    if (resourceConfig.folderChecks) {
      if (resourceConfig.folderChecks.existence) folderChecks.push($.i18n('data-quality-extension/check-folder-existence'));
      if (resourceConfig.folderChecks.nameFormat) folderChecks.push($.i18n('data-quality-extension/folder-name-format'));
    }
    if (folderChecks.length > 0) {
      $('<p></p>').text($.i18n('data-quality-extension/folder-checks') + ': ' + folderChecks.join(', ')).appendTo(resourceInfo);
    }
    // Show file checks
    var fileChecks = [];
    if (resourceConfig.fileChecks) {
      if (resourceConfig.fileChecks.countMatch) fileChecks.push($.i18n('data-quality-extension/check-file-count'));
      if (resourceConfig.fileChecks.nameFormat) fileChecks.push($.i18n('data-quality-extension/file-name-format'));
    }
    if (fileChecks.length > 0) {
      $('<p></p>').text($.i18n('data-quality-extension/file-checks') + ': ' + fileChecks.join(', ')).appendTo(resourceInfo);
    }
  }

  // Content rules summary
  if (contentCount > 0) {
    var contentSection = $('<div class="rules-section"></div>').appendTo(rulesList);
    $('<h4></h4>').text($.i18n('data-quality-extension/content-check-tab') + ' (' + contentCount + ' ' + $.i18n('data-quality-extension/rules') + ')').appendTo(contentSection);

    var contentList = $('<ul></ul>').appendTo(contentSection);
    contentRules.forEach(function(rule) {
      $('<li></li>').text(rule.column + ' → ' + rule.extractLabel + ' (' + rule.threshold + '%)').appendTo(contentList);
    });
  }
};

ManageRulesDialog.prototype._clearRules = function() {
  var self = this;

  if (!confirm($.i18n('data-quality-extension/confirm-clear-rules'))) {
    return;
  }

  // Clear rules in QualityAlignment
  QualityAlignment._formatRules = {};
  QualityAlignment._resourceConfig = QualityAlignment._getDefaultResourceConfig();
  QualityAlignment._contentRules = [];
  QualityAlignment._aimpConfig = { serviceUrl: '' };

  // Auto save to backend (silent=true)
  QualityAlignment._saveRules(function(success) {
    if (success) {
      // Re-render if tabs are set up
      if (QualityAlignment._isSetUp) {
        QualityAlignment._refreshFormatRulesTable();
        QualityAlignment._renderResourceCheckTab();
        QualityAlignment._refreshContentRulesTable();
      }
      // Refresh dialog
      self._loadRulesSummary();
      alert($.i18n('data-quality-extension/rules-cleared'));
    }
  }, true);
};

ManageRulesDialog.prototype._exportRules = function() {
  var buildTemplate = function(imageRule) {
    // 资源根路径属于项目现场配置，模板不携带；否则跨项目引用会串路径
    var resourceConfig = $.extend(true, {}, QualityAlignment._resourceConfig || {});
    delete resourceConfig.basePath;

    var payload = {
      formatRules: QualityAlignment._formatRules || {},
      resourceConfig: resourceConfig,
      contentRules: QualityAlignment._contentRules || [],
      aimpConfig: QualityAlignment._aimpConfig || {}
    };
    if (imageRule) {
      payload.imageQualityRule = imageRule;
    }

    var projectName = (theProject.metadata && theProject.metadata.name)
        ? theProject.metadata.name : ('project-' + theProject.id);

    // 结构与 RCP 的 saveQualityTemplate 请求体一致：文件可直接作为模板导入
    var template = {
      schema: 'rcp.quality-template/v1',
      templateId: 'quality-rules-' + theProject.id,
      type: 'compliance',
      name: projectName + ' 合规规则',
      description: '从 JinRefine 导出（项目 ' + theProject.id + '）',
      version: 1,
      payload: payload
    };

    var jsonStr = JSON.stringify(template, null, 2);
    var blob = new Blob([jsonStr], { type: 'application/json' });
    var url = URL.createObjectURL(blob);

    var a = document.createElement('a');
    a.href = url;
    a.download = 'quality-template-' + theProject.id + '.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // 图像规则优先取当前界面；未打开图像标签时向引擎读取，保证导出完整
  if (typeof ImageQualityTab !== 'undefined' && ImageQualityTab._currentRule) {
    var currentRule = ImageQualityTab._isSetUp
        ? ImageQualityTab._collectRuleFromUI() : ImageQualityTab._currentRule;
    buildTemplate(currentRule);
    return;
  }
  Refine.wrapCSRF(function(csrfToken) {
    var url = 'command/data-quality/get-image-quality-rule?' +
        $.param({ project: theProject.id, csrf_token: csrfToken });
    $.getJSON(url, function(data) {
      buildTemplate(data && data.rule ? data.rule : null);
    }).fail(function() {
      buildTemplate(null);
    });
  });
};

ManageRulesDialog.prototype._importRules = function() {
  var self = this;

  var input = document.createElement('input');
  input.type = 'file';
  input.accept = '.json';

  input.onchange = function(e) {
    var file = e.target.files[0];
    if (!file) return;

    var reader = new FileReader();
    reader.onload = function(e) {
      try {
        var doc = JSON.parse(e.target.result);
        // 兼容 RCP 模板文件（{payload:{...}}）与历史裸规则文件
        var rules = doc.payload ? doc.payload : doc;

        if (rules.formatRules) QualityAlignment._formatRules = rules.formatRules;
        if (rules.resourceConfig) {
          // 导出的模板不含现场路径，导入时保留当前项目已登记的资源根路径
          var currentBasePath = QualityAlignment._resourceConfig && QualityAlignment._resourceConfig.basePath;
          QualityAlignment._resourceConfig = rules.resourceConfig;
          if (currentBasePath) {
            QualityAlignment._resourceConfig.basePath = currentBasePath;
          }
        }
        if (rules.contentRules) QualityAlignment._contentRules = rules.contentRules;
        if (rules.aimpConfig) QualityAlignment._aimpConfig = rules.aimpConfig;

        var imageRule = rules.imageQualityRule || null;
        var uiReady = typeof ImageQualityTab !== 'undefined' && ImageQualityTab._isSetUp;
        if (imageRule && uiReady) {
          ImageQualityTab._currentRule = imageRule;
          ImageQualityTab._applyRuleToUI(imageRule);
        }

        DialogSystem.dismissUntil(self._level - 1);

        QualityAlignment._saveRules(function(success) {
          if (success) {
            if (imageRule && !uiReady) {
              // 图像标签未初始化时 _saveRules 不会保存图像规则，这里单独补写
              Refine.postCSRF(
                "command/data-quality/save-image-quality-rule",
                { project: theProject.id, rule: JSON.stringify(imageRule) },
                function() {},
                "json"
              );
            }

            if (QualityAlignment._isSetUp) {
              QualityAlignment._refreshFormatRulesTable();
              QualityAlignment._renderResourceCheckTab();
              QualityAlignment._refreshContentRulesTable();
            } else {
              QualityAlignment.launch();
            }

            if (QualityAlignment._aimpConfig && QualityAlignment._aimpConfig.serviceUrl) {
              QualityAlignment._testAndUpdateAimpConnection(QualityAlignment._aimpConfig.serviceUrl);
            }

            alert($.i18n('data-quality-extension/rules-imported'));
          }
        }, true);
      } catch (err) {
        alert($.i18n('data-quality-extension/import-error') + ': ' + err.message);
      }
    };
    reader.readAsText(file);
  };

  input.click();
};