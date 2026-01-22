/*

Copyright 2011, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
 * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 */

Refine.ExcelParserUI = function(controller, jobID, job, format, config,
    dataContainerElmt, progressContainerElmt, optionContainerElmt) {
  this._controller = controller;
  this._jobID = jobID;
  this._job = job;
  this._format = format;
  this._config = config;

  this._dataContainer = dataContainerElmt;
  this._progressContainer = progressContainerElmt;
  this._optionContainer = optionContainerElmt;

  this._timerID = null;
  this._initialize();
  this._updatePreview();
};
Refine.DefaultImportingController.parserUIs.ExcelParserUI = Refine.ExcelParserUI;

Refine.ExcelParserUI.prototype.dispose = function() {
  if (this._timerID !== null) {
    window.clearTimeout(this._timerID);
    this._timerID = null;
  }
};

Refine.ExcelParserUI.prototype.confirmReadyToCreateProject = function() {
  return true; // always ready
};

Refine.ExcelParserUI.prototype.getOptions = function() {
  var options = {
    sheets: [],
    sheetOptions: {}
  };

  var parseIntDefault = function(s, def) {
    try {
      var n = parseInt(s,10);
      if (!isNaN(n)) {
        return n;
      }
    } catch (e) {
    }
    return def;
  };
  
  var self = this;
  
  var selectedSheets = [];
  this._optionContainerElmts.sheetRecordContainer.find('input:checked').each(function() {
    var index = parseInt($(this).attr('index'));
    selectedSheets.push({
      index: index,
      record: self._config.sheetRecords[index]
    });
    options.sheets.push(self._config.sheetRecords[index]);
    options.sheetOptions[index] = self._getSheetOptions(index);
  });
  
  if (options.sheets.length === 1) {
    options.activeSheetIndex = this._activeSheetIndex;
    var activeSheetInfo = selectedSheets.find(function(s) { return s.index === self._activeSheetIndex; });
    if (activeSheetInfo) {
      options.sheets = [activeSheetInfo.record];
      var activeSheetOptions = options.sheetOptions[self._activeSheetIndex];
      options.sheetOptions = {};
      options.sheetOptions[self._activeSheetIndex] = activeSheetOptions;
    }
  } else if (options.sheets.length > 1) {
    options.activeSheetIndex = this._activeSheetIndex;
  }
  
  if (this._optionContainerElmts.ignoreCheckbox[0].checked) {
    options.ignoreLines = parseIntDefault(this._optionContainerElmts.ignoreInput[0].value, -1);
  } else {
    options.ignoreLines = -1;
  }
  if (this._optionContainerElmts.headerLinesCheckbox[0].checked) {
    options.headerLines = parseIntDefault(this._optionContainerElmts.headerLinesInput[0].value, 0);
  } else {
    options.headerLines = parseIntDefault(this._optionContainerElmts.headerLinesInput[0].value, 1);
  }
  if (this._optionContainerElmts.skipCheckbox[0].checked) {
    options.skipDataLines = parseIntDefault(this._optionContainerElmts.skipInput[0].value, 0);
  } else {
    options.skipDataLines = 0;
  }
  if (this._optionContainerElmts.limitCheckbox[0].checked) {
    options.limit = parseIntDefault(this._optionContainerElmts.limitInput[0].value, -1);
  } else {
    options.limit = -1;
  }
  options.storeBlankRows = this._optionContainerElmts.storeBlankRowsCheckbox[0].checked;
  options.storeBlankColumns = this._optionContainerElmts.storeBlankColumnsCheckbox[0].checked;
  options.storeBlankCellsAsNulls = this._optionContainerElmts.storeBlankCellsAsNullsCheckbox[0].checked;
  options.includeFileSources = this._optionContainerElmts.includeFileSourcesCheckbox[0].checked;
  options.includeArchiveFileName = this._optionContainerElmts.includeArchiveFileCheckbox[0].checked;
  options.forceText = this._optionContainerElmts.forceTextCheckbox[0].checked;

  options.disableAutoPreview = this._optionContainerElmts.disableAutoPreviewCheckbox[0].checked;

  return options;
};

Refine.ExcelParserUI.prototype._getSheetOptions = function(sheetIndex) {
  var sheetOptionContainer = this._sheetOptionContainers[sheetIndex];
  if (!sheetOptionContainer) {
    return {};
  }
  
  var parseIntDefault = function(s, def) {
    try {
      var n = parseInt(s,10);
      if (!isNaN(n)) {
        return n;
      }
    } catch (e) {
    }
    return def;
  };
  
  var elmts = DOM.bind(sheetOptionContainer);
  var options = {};
  
  var ignoreCheckbox = sheetOptionContainer.find('#ignore-' + sheetIndex);
  var headerCheckbox = sheetOptionContainer.find('#header-' + sheetIndex);
  var skipCheckbox = sheetOptionContainer.find('#skip-' + sheetIndex);
  var limitCheckbox = sheetOptionContainer.find('#limit-' + sheetIndex);
  
  var ignoreInput = sheetOptionContainer.find('#ignoreInput-' + sheetIndex);
  var headerInput = sheetOptionContainer.find('#headerInput-' + sheetIndex);
  var skipInput = sheetOptionContainer.find('#skipInput-' + sheetIndex);
  var limitInput = sheetOptionContainer.find('#limitInput-' + sheetIndex);
  
  var storeBlankRowsCheckbox = sheetOptionContainer.find('#storeBlankRows-' + sheetIndex);
  var storeBlankColumnsCheckbox = sheetOptionContainer.find('#storeBlankColumns-' + sheetIndex);
  var storeBlankCellsAsNullsCheckbox = sheetOptionContainer.find('#storeBlankCellsAsNulls-' + sheetIndex);
  var includeFileSourcesCheckbox = sheetOptionContainer.find('#includeFileSources-' + sheetIndex);
  var includeArchiveFileNameCheckbox = sheetOptionContainer.find('#includeArchiveFileName-' + sheetIndex);
  var forceTextCheckbox = sheetOptionContainer.find('#forceText-' + sheetIndex);
  
  if (ignoreCheckbox.length > 0 && ignoreCheckbox[0].checked) {
    options.ignoreLines = parseIntDefault(ignoreInput[0].value, -1);
  } else {
    options.ignoreLines = -1;
  }
  if (headerCheckbox.length > 0 && headerCheckbox[0].checked) {
    options.headerLines = parseIntDefault(headerInput[0].value, 0);
  } else {
    options.headerLines = parseIntDefault(headerInput.length > 0 ? headerInput[0].value : 1, 1);
  }
  if (skipCheckbox.length > 0 && skipCheckbox[0].checked) {
    options.skipDataLines = parseIntDefault(skipInput[0].value, 0);
  } else {
    options.skipDataLines = 0;
  }
  if (limitCheckbox.length > 0 && limitCheckbox[0].checked) {
    options.limit = parseIntDefault(limitInput[0].value, -1);
  } else {
    options.limit = -1;
  }
  
  if (storeBlankRowsCheckbox.length > 0) {
    options.storeBlankRows = storeBlankRowsCheckbox[0].checked;
  }
  if (storeBlankColumnsCheckbox.length > 0) {
    options.storeBlankColumns = storeBlankColumnsCheckbox[0].checked;
  }
  if (storeBlankCellsAsNullsCheckbox.length > 0) {
    options.storeBlankCellsAsNulls = storeBlankCellsAsNullsCheckbox[0].checked;
  }
  if (includeFileSourcesCheckbox.length > 0) {
    options.includeFileSources = includeFileSourcesCheckbox[0].checked;
  }
  if (includeArchiveFileNameCheckbox.length > 0) {
    options.includeArchiveFileName = includeArchiveFileNameCheckbox[0].checked;
  }
  if (forceTextCheckbox.length > 0) {
    options.forceText = forceTextCheckbox[0].checked;
  }
  
  return options;
};

Refine.ExcelParserUI.prototype._initialize = function() {
  var self = this;

  this._optionContainer.off().empty().html(
      DOM.loadHTML("core", "scripts/index/parser-interfaces/excel-parser-ui.html"));
  this._optionContainerElmts = DOM.bind(this._optionContainer);
  this._optionContainerElmts.previewButton.on('click',function() { self._updatePreview(); });  
  this._optionContainerElmts.previewButton.html($.i18n('core-buttons/update-preview'));
  this._optionContainerElmts.selectAllButton.on('click',function() { self._selectAll(); }); 
  this._optionContainerElmts.selectAllButton.html($.i18n('core-buttons/select-all'));
  this._optionContainerElmts.deselectAllButton.on('click',function() { self._deselectAll(); }); 
  this._optionContainerElmts.deselectAllButton.html($.i18n('core-buttons/deselect-all'));
  $('#or-disable-auto-preview').text($.i18n('core-index-parser/disable-auto-preview'));
  $('#or-import-worksheet').text($.i18n('core-index-import/import-worksheet'));
  $('#or-import-ignore').text($.i18n('core-index-parser/ignore-first'));
  $('#or-import-lines').text($.i18n('core-index-parser/lines-beg'));
  $('#or-import-parse').text($.i18n('core-index-parser/parse-next'));
  $('#or-import-header').text($.i18n('core-index-parser/lines-header'));
  $('#or-import-discard').text($.i18n('core-index-parser/discard-initial'));
  $('#or-import-rows').text($.i18n('core-index-parser/rows-data'));
  $('#or-import-load').text($.i18n('core-index-parser/load-at-most'));
  $('#or-import-rows2').text($.i18n('core-index-parser/rows-data'));
  $('#or-import-blank').text($.i18n('core-index-parser/store-blank'));
  $('#or-import-blank-columns').text($.i18n('core-index-parser/store-blank-columns'));
  $('#or-import-null').text($.i18n('core-index-parser/store-nulls'));
  $('#or-import-source').html($.i18n('core-index-parser/store-source'));
  $('#or-import-archive').html($.i18n('core-index-parser/store-archive'));
  $('#or-force-text').html($.i18n('core-index-parser/force-text'));

  this._sheetOptionContainers = {};
  this._activeSheetIndex = 0;
  this._previewTabContainer = null;
  this._previewTabList = null;
  this._optionTabContainer = null;
  this._optionTabList = null;

  this._initializeSheetSelector();
  this._renderSheetSelection();
  this._initializePreviewTabs();
  this._initializeOptionTabs();
};

Refine.ExcelParserUI.prototype._initializeSheetSelector = function() {
  var self = this;
  
  $('#open-sheet-selector').on('click', function() {
    self._openSheetSelector();
  });
  
  $('#close-sheet-selector').on('click', function() {
    self._closeSheetSelector();
  });
  
  $('#cancel-sheet-selection').on('click', function() {
    self._closeSheetSelector();
  });
  
  $('#confirm-sheet-selection').on('click', function() {
    self._confirmSheetSelection();
  });
  
  $('#sheet-selector-modal').on('click', function(e) {
    if (e.target === this) {
      self._closeSheetSelector();
    }
  });
};

Refine.ExcelParserUI.prototype._openSheetSelector = function() {
  var self = this;
  
  console.log('Opening sheet selector, sheetRecords:', this._config.sheetRecords);
  
  var sheetList = $('#sheet-selector-list');
  sheetList.empty();
  
  if (!this._config.sheetRecords || this._config.sheetRecords.length === 0) {
    sheetList.html('<div style="padding: 20px; text-align: center; color: #666;">没有找到Sheet数据</div>');
    $('#sheet-selector-modal').show();
    return;
  }
  
  $.each(this._config.sheetRecords, function(i, v) {
    console.log('Processing sheet:', i, v);
    
    var sheetItem = $('<div>')
      .addClass('sheet-item')
      .attr('data-sheet-index', i)
      .attr('data-sheet-name', v.name)
      .attr('data-sheet-rows', v.rows);
    
    var checkbox = $('<input>')
      .attr('type', 'checkbox')
      .attr('data-sheet-index', i)
      .prop('checked', v.selected);
    
    var label = $('<label>')
      .text(v.name + ' (' + v.rows + ' 行)');
    
    sheetItem.append(checkbox);
    sheetItem.append(label);
    sheetList.append(sheetItem);
  });
  
  console.log('Sheet list populated, showing modal');
  $('#sheet-selector-modal').show();
};

Refine.ExcelParserUI.prototype._closeSheetSelector = function() {
  $('#sheet-selector-modal').hide();
};

Refine.ExcelParserUI.prototype._confirmSheetSelection = function() {
  var self = this;
  
  $('#sheet-selector-list .sheet-item input:checked').each(function() {
    var index = parseInt($(this).attr('data-sheet-index'));
    var checkbox = self._optionContainerElmts.sheetRecordContainer.find('input[data-sheet-index="' + index + '"]');
    checkbox.prop('checked', true);
  });
  
  $('#sheet-selector-list .sheet-item input:not(:checked)').each(function() {
    var index = parseInt($(this).attr('data-sheet-index'));
    var checkbox = self._optionContainerElmts.sheetRecordContainer.find('input[data-sheet-index="' + index + '"]');
    checkbox.prop('checked', false);
  });
  
  this._closeSheetSelector();
  this._onSheetSelectionChanged();
};

Refine.ExcelParserUI.prototype._renderSheetSelection = function() {
  var self = this;
  var sheetTable = this._optionContainerElmts.sheetRecordContainer[0];
  sheetTable.innerHTML = '';
  
  $.each(this._config.sheetRecords, function(i, v) {
    var id = 'core-excel-worksheet-' + Math.round(Math.random() * 1000000);
    var tr = sheetTable.insertRow(sheetTable.rows.length);
    var td0 = $(tr.insertCell(0)).attr('width', '1%');
    var checkbox = $('<input>')
    .attr('id', id)
    .attr('type', 'checkbox')
    .attr('class', 'core-excel-worksheet')
    .attr('index', i)
    .attr('data-sheet-name', v.name)
    .appendTo(td0);
    checkbox.prop('checked', v.selected);

    $('<label>')
      .attr('for', id)
      .text(v.name)
      .appendTo(tr.insertCell(1));
    
    $('<label>')
      .attr('for', id)
      .text(v.rows + ' rows')
      .appendTo(tr.insertCell(2));
  });

  this._optionContainerElmts.sheetRecordContainer.on('change', 'input', function() {
    self._onSheetSelectionChanged();
  });
  
  this._checkAndAutoOpenSheetSelector();
};

Refine.ExcelParserUI.prototype._checkAndAutoOpenSheetSelector = function() {
  var selectedCount = this._optionContainerElmts.sheetRecordContainer.find('input:checked').length;
  if (selectedCount > 1) {
    this._openSheetSelector();
  }
};

Refine.ExcelParserUI.prototype._onSheetSelectionChanged = function() {
  var selectedSheets = this._getSelectedSheets();
  
  if (selectedSheets.length > 1) {
    this._enableMultiSheetMode(selectedSheets);
  } else if (selectedSheets.length === 1) {
    this._enableSingleSheetMode(selectedSheets[0]);
  } else {
    this._disablePreview();
  }
  
  this._scheduleUpdatePreview();
};

Refine.ExcelParserUI.prototype._getSelectedSheets = function() {
  var selectedSheets = [];
  var self = this;
  
  this._optionContainerElmts.sheetRecordContainer.find('input:checked').each(function() {
    var index = parseInt($(this).attr('index'));
    selectedSheets.push({
      index: index,
      name: $(this).attr('data-sheet-name'),
      record: self._config.sheetRecords[index]
    });
  });
  
  return selectedSheets;
};

Refine.ExcelParserUI.prototype._enableMultiSheetMode = function(selectedSheets) {
  this._previewTabContainer.show();
  this._optionTabContainer.show();
  
  this._rebuildPreviewTabs(selectedSheets);
  this._rebuildOptionTabs(selectedSheets);
  
  this._activeSheetIndex = selectedSheets[0].index;
  this._switchPreviewTab(this._activeSheetIndex);
  this._switchOptionTab(this._activeSheetIndex);
  
  $('#sheet-selection-row').hide();
  $('#option-tab-row').show();
  this._optionTabContainer.show();
};

Refine.ExcelParserUI.prototype._enableSingleSheetMode = function(sheet) {
  this._previewTabContainer.hide();
  this._optionTabContainer.hide();
  
  this._activeSheetIndex = sheet.index;
  this._showSingleSheetOptions(sheet);
  
  $('#sheet-selection-row').show();
  $('#option-tab-row').hide();
  $('#sheet-options-content').hide();
};

Refine.ExcelParserUI.prototype._disablePreview = function() {
  this._previewTabContainer.hide();
  this._optionTabContainer.hide();
  $('#sheet-options-content').hide();
  this._dataContainer.empty();
};

Refine.ExcelParserUI.prototype._initializePreviewTabs = function() {
  var self = this;
  
  this._previewTabContainer = $('<div>')
    .addClass('preview-tab-container')
    .hide()
    .insertBefore(this._dataContainer);
  
  this._previewTabList = $('<div>')
    .addClass('preview-tab-list')
    .appendTo(this._previewTabContainer);
  
  this._previewTabContainer.hide();
};

Refine.ExcelParserUI.prototype._rebuildPreviewTabs = function(selectedSheets) {
  var self = this;
  
  this._previewTabList.empty();
  
  $.each(selectedSheets, function(i, sheet) {
    var tab = $('<div>')
      .addClass('preview-tab')
      .attr('data-sheet-index', sheet.index)
      .text(sheet.name);
    
    if (sheet.index === self._activeSheetIndex) {
      tab.addClass('active');
    }
    
    tab.on('click', function() {
      self._switchPreviewTab(sheet.index);
    });
    
    self._previewTabList.append(tab);
  });
};

Refine.ExcelParserUI.prototype._switchPreviewTab = function(sheetIndex) {
  this._activeSheetIndex = sheetIndex;
  
  this._previewTabList.find('.preview-tab').removeClass('active');
  this._previewTabList.find('[data-sheet-index="' + sheetIndex + '"]').addClass('active');
  
  this._switchOptionTab(sheetIndex);
};

Refine.ExcelParserUI.prototype._initializeOptionTabs = function() {
  var self = this;
  
  this._optionTabContainer = $('#option-tab-container');
  this._optionTabList = $('#option-tab-list');
  
  this._sheetOptionContainers = {};
  
  this._optionTabContainer.hide();
};

Refine.ExcelParserUI.prototype._rebuildOptionTabs = function(selectedSheets) {
  var self = this;
  
  this._optionTabList.empty();
  this._sheetOptionContainers = {};
  
  var sheetOptionsContent = $('#sheet-options-content');
  sheetOptionsContent.empty();
  
  $.each(selectedSheets, function(i, sheet) {
    var tab = $('<div>')
      .addClass('option-tab')
      .attr('data-sheet-index', sheet.index)
      .text(sheet.name + ' 配置');
    
    if (sheet.index === self._activeSheetIndex) {
      tab.addClass('active');
    }
    
    tab.on('click', function() {
      self._switchOptionTab(sheet.index);
    });
    
    self._optionTabList.append(tab);
    
    var optionContainer = $('<div>')
      .addClass('sheet-option-container')
      .attr('data-sheet-index', sheet.index)
      .hide()
      .appendTo(sheetOptionsContent);
    
    self._renderSheetOptions(optionContainer, sheet);
    self._sheetOptionContainers[sheet.index] = optionContainer;
  });
  
  if (this._sheetOptionContainers[this._activeSheetIndex]) {
    this._sheetOptionContainers[this._activeSheetIndex].show();
  }
  
  sheetOptionsContent.show();
};

Refine.ExcelParserUI.prototype._renderSheetOptions = function(container, sheet) {
  var self = this;
  
  container.html(`
    <div class="grid-layout layout-tightest">
      <table role="presentation">
        <tr>
          <td width="1%"><input type="checkbox" id="ignore-${sheet.index}" /></td>
          <td><label for="ignore-${sheet.index}">忽略前</label></td>
          <td><input id="ignoreInput-${sheet.index}" type="number" class="lightweight" size="7" value="0" />
            <label for="ignore-${sheet.index}">行</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="header-${sheet.index}" /></td>
          <td><label for="header-${sheet.index}">解析下</label></td>
          <td><input id="headerInput-${sheet.index}" type="number" class="lightweight" size="7" value="1" />
            <label for="header-${sheet.index}">行作为列标题</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="skip-${sheet.index}" /></td>
          <td><label for="skip-${sheet.index}">丢弃初始</label></td>
          <td><input id="skipInput-${sheet.index}" type="number" class="lightweight" size="7" value="0" />
            <label for="skip-${sheet.index}">行数据</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="limit-${sheet.index}" /></td>
          <td><label for="limit-${sheet.index}">最多加载</label></td>
          <td><input id="limitInput-${sheet.index}" type="number" class="lightweight" size="7" value="0" />
            <label for="limit-${sheet.index}">行数据</label></td>
        </tr>
      </table>
    </div>
    <div class="grid-layout layout-tightest" style="margin-top: 10px;">
      <table role="presentation">
        <tr>
          <td width="1%"><input type="checkbox" id="storeBlankRows-${sheet.index}" checked /></td>
          <td colspan="2"><label for="storeBlankRows-${sheet.index}">存储空白行</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="storeBlankColumns-${sheet.index}" checked /></td>
          <td colspan="2"><label for="storeBlankColumns-${sheet.index}">存储空白列</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="storeBlankCellsAsNulls-${sheet.index}" checked /></td>
          <td colspan="2"><label for="storeBlankCellsAsNulls-${sheet.index}">存储空白单元格为null</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="includeFileSources-${sheet.index}" /></td>
          <td><label for="includeFileSources-${sheet.index}">存储源文件</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="includeArchiveFileName-${sheet.index}" /></td>
          <td><label for="includeArchiveFileName-${sheet.index}">存储归档文件</label></td>
        </tr>
        <tr>
          <td width="1%"><input type="checkbox" id="forceText-${sheet.index}" /></td>
          <td><label for="forceText-${sheet.index}">强制文本模式</label></td>
        </tr>
      </table>
    </div>
  `);
  
  var elmts = DOM.bind(container);
  
  var onChange = function() {
    if (!self._optionContainerElmts.disableAutoPreviewCheckbox[0].checked) {
      self._scheduleUpdatePreview();
    }
  };
  
  container.find("input").on("change", onChange);
  container.find("select").on("change", onChange);
};

Refine.ExcelParserUI.prototype._switchOptionTab = function(sheetIndex) {
  this._activeSheetIndex = sheetIndex;
  
  this._optionTabList.find('.option-tab').removeClass('active');
  this._optionTabList.find('[data-sheet-index="' + sheetIndex + '"]').addClass('active');
  
  $('#sheet-options-content').find('.sheet-option-container').hide();
  if (this._sheetOptionContainers[sheetIndex]) {
    this._sheetOptionContainers[sheetIndex].show();
  }
  
  this._previewTabList.find('.preview-tab').removeClass('active');
  this._previewTabList.find('[data-sheet-index="' + sheetIndex + '"]').addClass('active');
  
  this._scheduleUpdatePreview();
};

Refine.ExcelParserUI.prototype._showSingleSheetOptions = function(sheet) {
  var self = this;
  
  this._sheetOptionContainers = {};
  this._sheetOptionContainers[sheet.index] = this._optionContainer;
  
  $('#sheet-options-content').empty();
};

Refine.ExcelParserUI.prototype._scheduleUpdatePreview = function() {
  if (this._timerID !== null) {
    window.clearTimeout(this._timerID);
    this._timerID = null;
  }

  var self = this;
  this._timerID = window.setTimeout(function() {
    self._timerID = null;
    self._updatePreview();
  }, 500);
};

Refine.ExcelParserUI.prototype._updatePreview = function() {
  var self = this;

  this._progressContainer.show();

  var previewOptions = this._getPreviewOptions();
  this._controller.updateFormatAndOptions(previewOptions, function(result) {
    if (result.status == "ok") {
      self._controller.getPreviewData(function(projectData) {
        self._progressContainer.hide();

        new Refine.PreviewTable(projectData, self._dataContainer.off().empty());
      });
    }
  }, function() {
	  self._progressContainer.hide();
  });
};

Refine.ExcelParserUI.prototype._getPreviewOptions = function() {
  var self = this;
  var options = {
    sheets: [],
    sheetOptions: {}
  };

  var selectedSheets = [];
  this._optionContainerElmts.sheetRecordContainer.find('input:checked').each(function() {
    var index = parseInt($(this).attr('index'));
    selectedSheets.push({
      index: index,
      record: self._config.sheetRecords[index]
    });
    options.sheets.push(self._config.sheetRecords[index]);
    options.sheetOptions[index] = self._getSheetOptions(index);
  });

  if (selectedSheets.length > 0) {
    var activeSheetInfo = selectedSheets.find(function(s) { return s.index === self._activeSheetIndex; });
    if (activeSheetInfo) {
      options.sheets = [activeSheetInfo.record];
      var activeSheetOptions = options.sheetOptions[self._activeSheetIndex];
      options.sheetOptions = {};
      options.sheetOptions[self._activeSheetIndex] = activeSheetOptions;
    }
  }

  var parseIntDefault = function(s, def) {
    try {
      var n = parseInt(s,10);
      if (!isNaN(n)) {
        return n;
      }
    } catch (e) {
    }
    return def;
  };
  
  if (this._optionContainerElmts.ignoreCheckbox[0].checked) {
    options.ignoreLines = parseIntDefault(this._optionContainerElmts.ignoreInput[0].value, -1);
  } else {
    options.ignoreLines = -1;
  }
  if (this._optionContainerElmts.headerLinesCheckbox[0].checked) {
    options.headerLines = parseIntDefault(this._optionContainerElmts.headerLinesInput[0].value, 0);
  } else {
    options.headerLines = parseIntDefault(this._optionContainerElmts.headerLinesInput[0].value, 1);
  }
  if (this._optionContainerElmts.skipCheckbox[0].checked) {
    options.skipDataLines = parseIntDefault(this._optionContainerElmts.skipInput[0].value, 0);
  } else {
    options.skipDataLines = 0;
  }
  if (this._optionContainerElmts.limitCheckbox[0].checked) {
    options.limit = parseIntDefault(this._optionContainerElmts.limitInput[0].value, -1);
  } else {
    options.limit = -1;
  }
  options.storeBlankRows = this._optionContainerElmts.storeBlankRowsCheckbox[0].checked;
  options.storeBlankColumns = this._optionContainerElmts.storeBlankColumnsCheckbox[0].checked;
  options.storeBlankCellsAsNulls = this._optionContainerElmts.storeBlankCellsAsNullsCheckbox[0].checked;
  options.includeFileSources = this._optionContainerElmts.includeFileSourcesCheckbox[0].checked;
  options.includeArchiveFileName = this._optionContainerElmts.includeArchiveFileCheckbox[0].checked;
  options.forceText = this._optionContainerElmts.forceTextCheckbox[0].checked;

  options.disableAutoPreview = this._optionContainerElmts.disableAutoPreviewCheckbox[0].checked;

  return options;
};

Refine.ExcelParserUI.prototype._selectAll = function() {
  var self = this;

  $(".core-excel-worksheet").each(function(index, value){
    $(value).prop('checked', true);
  });
  
  self._scheduleUpdatePreview();
}

Refine.ExcelParserUI.prototype._deselectAll = function() {
  var self = this;

  $(".core-excel-worksheet").each(function(index, value){
    $(value).prop('checked', false);
  });

  self._scheduleUpdatePreview();
}
