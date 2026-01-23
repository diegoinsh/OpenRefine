function MultiSheetExporterDialog(format, ext) {
  this._format = format;
  this._ext = ext;
  this._selectedSheetIds = [];
  this._outputColumnHeaders = true;
  this._outputEmptyRows = true;
  this._createDialog();
}

MultiSheetExporterDialog.prototype._createDialog = function() {
  var self = this;
  
  this._dialog = $(DOM.loadHTML("core", "scripts/dialogs/multi-sheet-exporter-dialog.html"));
  this._elmts = DOM.bind(this._dialog);
  this._level = DialogSystem.showDialog(this._dialog);
  
  this._elmts.dialogHeader.html($.i18n('core-dialogs/select-sheets'));
  this._elmts.selectSheetsTabLabel.html($.i18n('core-buttons/select-sheets'));
  this._elmts.exportOptionsTabLabel.html($.i18n('core-dialogs/export-options'));
  this._elmts.selectSheetsLabel.html($.i18n('core-dialogs/select-sheets-to-export'));
  this._elmts.selectAllButton.html($.i18n('core-buttons/select-all'));
  this._elmts.deselectAllButton.html($.i18n('core-buttons/deselect-all'));
  this._elmts.exportButton.html($.i18n('core-buttons/export'));
  this._elmts.cancelButton.html($.i18n('core-buttons/cancel'));
  this._elmts.outputColumnHeadersLabel.html($.i18n('core-dialogs/out-col-header'));
  this._elmts.outputEmptyRowsLabel.html($.i18n('core-dialogs/out-empty-row'));
  this._elmts.exportOptionsLabel.html($.i18n('core-dialogs/export-options'));
  
  $("#multi-sheet-exporter-tabs-sheets").css("display", "");
  $("#multi-sheet-exporter-tabs-options").css("display", "");
  $("#multi-sheet-exporter-tabs").tabs();
  
  this._renderSheetList();
  
  this._elmts.selectAllButton.on('click', function() {
    self._selectAllSheets();
  });
  
  this._elmts.deselectAllButton.on('click', function() {
    self._deselectAllSheets();
  });
  
  this._elmts.exportButton.on('click', function() {
    self._export();
  });
  
  this._elmts.cancelButton.on('click', function() {
    DialogSystem.dismissUntil(self._level - 1);
  });
  
  this._elmts.outputColumnHeadersCheckbox.prop('checked', this._outputColumnHeaders);
  this._elmts.outputEmptyRowsCheckbox.prop('checked', this._outputEmptyRows);
  
  this._elmts.outputColumnHeadersCheckbox.on('change', function() {
    self._outputColumnHeaders = $(this).prop('checked');
  });
  
  this._elmts.outputEmptyRowsCheckbox.on('change', function() {
    self._outputEmptyRows = $(this).prop('checked');
  });
};

MultiSheetExporterDialog.prototype._renderSheetList = function() {
  var self = this;
  var sheetList = this._elmts.sheetList;
  sheetList.empty();
  
  if (theProject.sheetDataMap && Object.keys(theProject.sheetDataMap).length > 0) {
    var sheets = theProject.sheetDataMap;
    for (var sheetId in sheets) {
      if (sheets.hasOwnProperty(sheetId)) {
        var sheetData = sheets[sheetId];
        var sheetItem = $('<div>')
          .addClass('multi-sheet-exporter-sheet-item')
          .attr('data-sheet-id', sheetId);
        
        var checkbox = $('<input>')
          .attr('type', 'checkbox')
          .attr('value', sheetId)
          .prop('checked', true);
        
        var label = $('<span>')
          .addClass('multi-sheet-exporter-sheet-name')
          .text(sheetData.sheetName);
        
        sheetItem.append(checkbox).append(label);
        sheetList.append(sheetItem);
        
        this._selectedSheetIds.push(sheetId);
      }
    }
  } else {
    sheetList.html('<div class="multi-sheet-exporter-no-sheets">' + $.i18n('core-dialogs/no-sheets-available') + '</div>');
  }
};

MultiSheetExporterDialog.prototype._selectAllSheets = function() {
  this._elmts.sheetList.find('input[type="checkbox"]').prop('checked', true);
  this._selectedSheetIds = [];
  var self = this;
  this._elmts.sheetList.find('input[type="checkbox"]').each(function() {
    self._selectedSheetIds.push($(this).val());
  });
};

MultiSheetExporterDialog.prototype._deselectAllSheets = function() {
  this._elmts.sheetList.find('input[type="checkbox"]').prop('checked', false);
  this._selectedSheetIds = [];
};

MultiSheetExporterDialog.prototype._export = function() {
  var self = this;
  
  this._selectedSheetIds = [];
  this._elmts.sheetList.find('input[type="checkbox"]:checked').each(function() {
    self._selectedSheetIds.push($(this).val());
  });
  
  if (this._selectedSheetIds.length === 0) {
    alert($.i18n('core-dialogs/please-select-at-least-one-sheet'));
    return;
  }
  
  DialogSystem.dismissUntil(this._level - 1);
  
  Refine.wrapCSRF(function(csrfToken) {
    let form = ExporterManager.prepareExportRowsForm(self._format, true, self._ext, csrfToken);
    
    $('<input />')
      .attr("name", "sheetIds")
      .val(JSON.stringify(self._selectedSheetIds))
      .appendTo(form);
    
    $('<input />')
      .attr("name", "outputColumnHeaders")
      .val(self._outputColumnHeaders ? "true" : "false")
      .appendTo(form);
    
    $('<input />')
      .attr("name", "outputEmptyRows")
      .val(self._outputEmptyRows ? "true" : "false")
      .appendTo(form);
    
    document.body.appendChild(form);
    form.submit();
    document.body.removeChild(form);
  });
};
