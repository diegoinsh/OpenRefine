/*
 * Export Bound Assets Dialog
 * Dialog for exporting files based on project data
 */

// Ensure i18n is loaded for records-db module
(function() {
  if (typeof I18NUtil !== 'undefined' && !ExportBoundAssetsDialog._i18nLoaded) {
    I18NUtil.init("records-db");
    ExportBoundAssetsDialog._i18nLoaded = true;
  }
})();

function ExportBoundAssetsDialog() {
  this._selectedFields = [];
  this._config = {};
  this._init();
}

ExportBoundAssetsDialog._i18nLoaded = false;

ExportBoundAssetsDialog.prototype._init = function() {
  var self = this;

  // Ensure i18n is loaded before using translations
  if (typeof I18NUtil !== 'undefined' && !ExportBoundAssetsDialog._i18nLoaded) {
    I18NUtil.init("records-db");
    ExportBoundAssetsDialog._i18nLoaded = true;
  }
  
  // Load dialog HTML
  this._dialog = $(DOM.loadHTML("records-db", "scripts/dialogs/export-bound-assets-dialog.html"));
  this._elmts = DOM.bind(this._dialog);
  
  // Set up i18n labels
  this._elmts.dialogHeader.text($.i18n("records-db-export/dialog-title"));
  this._elmts.tab_pathConfig.text($.i18n("records-db-export/tab-path-config"));
  this._elmts.tab_exportSettings.text($.i18n("records-db-export/tab-export-settings"));
  this._elmts.tab_optionCode.text($.i18n("records-db-export/tab-option-code"));
  
  this._elmts.label_selectFields.text($.i18n("records-db-export/select-fields"));
  this._elmts.label_pathOptions.text($.i18n("records-db-export/path-options"));
  this._elmts.label_pathJoinMethod.text($.i18n("records-db-export/path-join-method"));
  this._elmts.label_useSeparator.text($.i18n("records-db-export/use-separator"));
  this._elmts.label_useTemplate.text($.i18n("records-db-export/use-template"));
  this._elmts.label_sourceRootPath.text($.i18n("records-db-export/source-root-path"));
  this._elmts.label_pathPreview.text($.i18n("records-db-export/path-preview"));
  
  this._elmts.selectAllButton.text($.i18n("records-db-export/select-all"));
  this._elmts.deselectAllButton.text($.i18n("records-db-export/deselect-all"));
  this._elmts.browseSourceButton.text($.i18n("records-db-export/browse"));
  
  this._elmts.label_targetPath.text($.i18n("records-db-export/target-path"));
  this._elmts.browseTargetButton.text($.i18n("records-db-export/browse"));
  this._elmts.label_operationMode.text($.i18n("records-db-export/operation-mode"));
  this._elmts.label_modeCopy.text($.i18n("records-db-export/mode-copy"));
  this._elmts.label_modeMove.text($.i18n("records-db-export/mode-move"));
  this._elmts.label_modeRename.text($.i18n("records-db-export/mode-rename"));
  
  this._elmts.label_advancedOptions.text($.i18n("records-db-export/advanced-options"));
  this._elmts.label_preserveStructure.text($.i18n("records-db-export/preserve-structure"));
  this._elmts.label_skipExisting.text($.i18n("records-db-export/skip-existing"));
  this._elmts.label_ignoreFacets.text($.i18n("records-db-export/ignore-facets"));
  
  this._elmts.previewButton.text($.i18n("records-db-export/preview"));
  this._elmts.exportButton.text($.i18n("records-db-export/start-export"));
  this._elmts.label_jsonCode.text($.i18n("records-db-export/json-code"));
  this._elmts.applyOptionCodeButton.text($.i18n("records-db-export/apply-code"));
  this._elmts.cancelButton.text($.i18n("core-buttons/cancel"));
  
  // Initialize tabs
  this._dialog.find("#export-bound-assets-tabs").tabs();
  
  // Load columns and config
  this._loadConfig();
  
  // Set up event handlers
  this._setupEventHandlers();
  
  // Show dialog
  this._level = DialogSystem.showDialog(this._dialog);
};

ExportBoundAssetsDialog.prototype._loadConfig = function() {
  var self = this;
  
  $.ajax({
    url: "command/records-db/export-bound-assets",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(data) {
      if (data.status === "ok") {
        self._columns = data.columns || [];
        self._fileMapping = data.fileMapping || {};
        self._renderFieldList();
        
        // Pre-fill source root if available
        if (self._fileMapping.rootPath) {
          self._elmts.sourceRootInput.val(self._fileMapping.rootPath);
        }
      }
    },
    error: function(xhr, status, error) {
      alert($.i18n("records-db-export/error-loading-config") + ": " + error);
    }
  });
};

ExportBoundAssetsDialog.prototype._renderFieldList = function() {
  var self = this;
  var container = this._elmts.fieldList.empty();
  
  // Create sortable list
  var list = $('<ul class="export-bound-assets-field-list"></ul>').appendTo(container);
  
  for (var i = 0; i < this._columns.length; i++) {
    var colName = this._columns[i];
    var isFileColumn = this._fileMapping.columnLabel === colName;
    
    var item = $('<li class="export-bound-assets-field-item"></li>')
      .attr("data-column", colName)
      .appendTo(list);
    
    var checkbox = $('<input type="checkbox" />')
      .attr("id", "export-field-" + i)
      .prop("checked", isFileColumn)
      .appendTo(item);
    
    $('<label></label>')
      .attr("for", "export-field-" + i)
      .text(colName)
      .appendTo(item);
    
    if (isFileColumn) {
      item.addClass("file-column");
      self._selectedFields.push(colName);
    }
  }
  
  // Make list sortable
  list.sortable({
    axis: "y",
    cursor: "move",
    update: function() {
      self._updatePathPreview();
    }
  });
  
  // Checkbox change handler
  list.find("input[type=checkbox]").on("change", function() {
    var colName = $(this).closest("li").attr("data-column");
    if (this.checked) {
      if (self._selectedFields.indexOf(colName) === -1) {
        self._selectedFields.push(colName);
      }
    } else {
      var idx = self._selectedFields.indexOf(colName);
      if (idx > -1) {
        self._selectedFields.splice(idx, 1);
      }
    }
    self._updatePathPreview();
  });
  
  this._updatePathPreview();
};

ExportBoundAssetsDialog.prototype._setupEventHandlers = function() {
  var self = this;
  
  // Select/Deselect all
  this._elmts.selectAllButton.on("click", function() {
    self._elmts.fieldList.find("input[type=checkbox]").prop("checked", true).trigger("change");
  });

  this._elmts.deselectAllButton.on("click", function() {
    self._elmts.fieldList.find("input[type=checkbox]").prop("checked", false).trigger("change");
  });

  // Path mode change
  this._dialog.find("input[name='export-bound-assets-path-mode']").on("change", function() {
    self._updatePathPreview();
  });

  // Separator/template input change
  this._elmts.separatorInput.on("input", function() {
    self._updatePathPreview();
  });
  this._elmts.templateInput.on("input", function() {
    self._updatePathPreview();
  });
  this._elmts.sourceRootInput.on("input", function() {
    self._updatePathPreview();
  });

  // Preview button
  this._elmts.previewButton.on("click", function() {
    self._previewExport();
  });

  // Export button
  this._elmts.exportButton.on("click", function() {
    self._executeExport();
  });

  // Apply option code
  this._elmts.applyOptionCodeButton.on("click", function() {
    self._applyOptionCode();
  });

  // Cancel button
  this._elmts.cancelButton.on("click", function() {
    self._dismiss();
  });

  // Browse source button
  this._elmts.browseSourceButton.on("click", function() {
    self._showDirectoryBrowser(function(selectedPath) {
      self._elmts.sourceRootInput.val(selectedPath);
      self._updatePathPreview();
    });
  });

  // Browse target button
  this._elmts.browseTargetButton.on("click", function() {
    self._showDirectoryBrowser(function(selectedPath) {
      self._elmts.targetPathInput.val(selectedPath);
    });
  });
};

ExportBoundAssetsDialog.prototype._showDirectoryBrowser = function(callback) {
  var self = this;

  // Create a simple directory input dialog since browser cannot access local filesystem directly
  var path = prompt($.i18n("records-db-export/enter-directory-path") || "请输入目录路径：", "");
  if (path && path.trim()) {
    callback(path.trim());
  }
};

ExportBoundAssetsDialog.prototype._updatePathPreview = function() {
  var self = this;
  var previewPane = this._elmts.pathPreviewPane.empty();

  // Get selected fields in order
  var orderedFields = [];
  this._elmts.fieldList.find("li").each(function() {
    var colName = $(this).attr("data-column");
    if ($(this).find("input[type=checkbox]").prop("checked")) {
      orderedFields.push(colName);
    }
  });

  if (orderedFields.length === 0) {
    previewPane.text($.i18n("records-db-export/no-fields-selected"));
    return;
  }

  var pathMode = this._dialog.find("input[name='export-bound-assets-path-mode']:checked").val();
  var separator = this._elmts.separatorInput.val() || "/";
  var template = this._elmts.templateInput.val();
  var sourceRoot = this._elmts.sourceRootInput.val();

  // Build preview path
  var previewPath = "";
  if (sourceRoot) {
    previewPath = sourceRoot;
    if (!previewPath.endsWith("/") && !previewPath.endsWith("\\")) {
      previewPath += "/";
    }
  }

  if (pathMode === "template" && template) {
    previewPath += template.replace(/\{(\d+)\}/g, function(match, index) {
      var idx = parseInt(index);
      return idx < orderedFields.length ? "{" + orderedFields[idx] + "}" : match;
    });
  } else {
    previewPath += orderedFields.map(function(f) { return "{" + f + "}"; }).join(separator);
  }

  previewPane.text(previewPath);
  this._selectedFields = orderedFields;
};

ExportBoundAssetsDialog.prototype._getOptions = function() {
  var orderedFields = [];
  this._elmts.fieldList.find("li").each(function() {
    var colName = $(this).attr("data-column");
    if ($(this).find("input[type=checkbox]").prop("checked")) {
      orderedFields.push(colName);
    }
  });

  var pathMode = this._dialog.find("input[name='export-bound-assets-path-mode']:checked").val();
  var mode = this._dialog.find("input[name='export-bound-assets-mode']:checked").val();

  return {
    pathFields: orderedFields,
    sourceRootPath: this._elmts.sourceRootInput.val(),
    targetPath: this._elmts.targetPathInput.val(),
    mode: mode,
    pathMode: pathMode,
    separator: this._elmts.separatorInput.val() || "/",
    template: this._elmts.templateInput.val(),
    preserveStructure: this._elmts.preserveStructureCheckbox.prop("checked"),
    skipExisting: this._elmts.skipExistingCheckbox.prop("checked"),
    exportAllRows: this._elmts.exportAllRowsCheckbox.prop("checked")
  };
};

ExportBoundAssetsDialog.prototype._previewExport = function() {
  var self = this;
  var options = this._getOptions();
  var previewOptions = $.extend({}, options, { preview: true, limit: 20 });

  console.log("Preview options:", previewOptions);
  console.log("pathFields:", previewOptions.pathFields);

  this._elmts.statusMessage.text($.i18n("records-db-export/loading-preview") || "加载预览中...");

  Refine.postCSRF(
    "command/records-db/export-bound-assets?" + $.param({ project: theProject.id }),
    { options: JSON.stringify(previewOptions) },
    function(data) {
      console.log("Preview response:", data);
      if (data.status === "ok") {
        var msg = $.i18n("records-db-export/preview-result",
            data.totalRows, data.existing, data.missing);
        if (!msg || msg === "records-db-export/preview-result") {
          msg = "共 " + data.totalRows + " 行, 存在: " + data.existing + ", 缺失: " + data.missing;
        }
        self._elmts.statusMessage.html(msg);
      } else {
        self._elmts.statusMessage.text(
          ($.i18n("records-db-export/preview-error") || "预览出错") + ": " + (data.message || "未知错误")
        );
      }
    },
    "json",
    function(xhr, status, error) {
      console.error("Preview error:", status, error);
      self._elmts.statusMessage.text(
        ($.i18n("records-db-export/preview-error") || "加载预览失败") + ": " + error
      );
    }
  );
};

ExportBoundAssetsDialog.prototype._executeExport = function() {
  var self = this;
  var options = this._getOptions();

  if (!options.targetPath) {
    alert($.i18n("records-db-export/target-path-required"));
    return;
  }

  if (options.pathFields.length === 0) {
    alert($.i18n("records-db-export/fields-required"));
    return;
  }

  this._elmts.exportButton.prop("disabled", true);
  this._elmts.statusMessage.text($.i18n("records-db-export/exporting") || "正在导出...");

  Refine.postCSRF(
    "command/records-db/export-bound-assets?" + $.param({ project: theProject.id }),
    { options: JSON.stringify(options) },
    function(data) {
      self._elmts.exportButton.prop("disabled", false);
      if (data.status === "ok" || data.status === "partial") {
        var msg = $.i18n("records-db-export/export-complete",
          data.success, data.failed, data.skipped);
        if (!msg || msg === "records-db-export/export-complete") {
          msg = "导出完成: " + data.success + " 成功, " + data.failed + " 失败, " + data.skipped + " 跳过";
        }
        self._elmts.statusMessage.text(msg);

        if (data.status === "ok") {
          alert(msg);
          self._dismiss();
        } else {
          alert(msg + "\n" + ($.i18n("records-db-export/check-errors") || "请查看控制台了解错误详情"));
        }
      } else {
        self._elmts.statusMessage.text($.i18n("records-db-export/export-error") || "导出失败");
        alert(($.i18n("records-db-export/export-error") || "导出失败") + ": " + (data.message || "未知错误"));
      }
    },
    "json",
    function(xhr, status, error) {
      self._elmts.exportButton.prop("disabled", false);
      self._elmts.statusMessage.text($.i18n("records-db-export/export-error") || "导出失败");
      alert(($.i18n("records-db-export/export-error") || "导出失败") + ": " + error);
    }
  );
};

ExportBoundAssetsDialog.prototype._applyOptionCode = function() {
  try {
    var code = this._elmts.optionCodeInput.val();
    var options = JSON.parse(code);

    // Apply options to UI
    if (options.pathFields) {
      this._elmts.fieldList.find("input[type=checkbox]").prop("checked", false);
      for (var i = 0; i < options.pathFields.length; i++) {
        this._elmts.fieldList.find("li[data-column='" + options.pathFields[i] + "'] input")
          .prop("checked", true);
      }
    }
    if (options.sourceRootPath) {
      this._elmts.sourceRootInput.val(options.sourceRootPath);
    }
    if (options.targetPath) {
      this._elmts.targetPathInput.val(options.targetPath);
    }
    if (options.separator) {
      this._elmts.separatorInput.val(options.separator);
    }

    this._updatePathPreview();
    alert($.i18n("records-db-export/code-applied"));
  } catch (e) {
    alert($.i18n("records-db-export/invalid-json"));
  }
};

ExportBoundAssetsDialog.prototype._dismiss = function() {
  DialogSystem.dismissUntil(this._level - 1);
};

