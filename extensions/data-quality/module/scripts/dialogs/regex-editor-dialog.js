var RegexEditorDialog = function(options) {
  options = options || {
    pattern: '',
    flags: 'g',
    description: '',
    testInput: ''
  };
  
  this._options = options;
  this._selectedPreset = null;
  this._subitems = [];
  this._separator = '-';
  this._selectedSubitemIndex = -1;
  this._createDialog(options);
};

RegexEditorDialog.PRESETS = {
  archiveNumber: [
    {
      id: 'archive-fonds-number',
      name: 'data-quality-extension/regex-preset-fonds-number',
      description: 'data-quality-extension/regex-preset-fonds-number-desc',
      pattern: '^[0-9]{4}$',
      examples: ['0999', '1234']
    },
    {
      id: 'archive-year',
      name: 'data-quality-extension/regex-preset-year',
      description: 'data-quality-extension/regex-preset-year-desc',
      pattern: '^[0-9]{4}$',
      examples: ['2023', '1991']
    },
    {
      id: 'archive-catalog-number',
      name: 'data-quality-extension/regex-preset-catalog-number',
      description: 'data-quality-extension/regex-preset-catalog-number-desc',
      pattern: '^[0-9]{3,4}$',
      examples: ['001', '0123']
    },
    {
      id: 'archive-volume-number',
      name: 'data-quality-extension/regex-preset-volume-number',
      description: 'data-quality-extension/regex-preset-volume-number-desc',
      pattern: '^[0-9]{4}$',
      examples: ['0001', '0123']
    },
    {
      id: 'archive-file-number',
      name: 'data-quality-extension/regex-preset-file-number',
      description: 'data-quality-extension/regex-preset-file-number-desc',
      pattern: '^[0-9]{4}$',
      examples: ['0001', '0123']
    },
    {
      id: 'archive-item-number',
      name: 'data-quality-extension/regex-preset-item-number',
      description: 'data-quality-extension/regex-preset-item-number-desc',
      pattern: '^[0-9]{3}$',
      examples: ['001', '123']
    },
    {
      id: 'archive-file-archive-number',
      name: 'data-quality-extension/regex-preset-file-archive-number',
      description: 'data-quality-extension/regex-preset-file-archive-number-desc',
      pattern: '^[0-9]{4}-[0-9]{3}-[0-9]{4}$',
      examples: ['0999-001-0001', '1234-0123-4567']
    },
    {
      id: 'archive-complete-archive-number',
      name: 'data-quality-extension/regex-preset-complete-archive-number',
      description: 'data-quality-extension/regex-preset-complete-archive-number-desc',
      pattern: '^[0-9]{4}-[0-9]{4}-[0-9]{3}-[0-9]{4}-[0-9]{3}$',
      examples: ['1111-0010-001-0001-001']
    },
    {
      id: 'archive-title-optional',
      name: 'data-quality-extension/regex-preset-title-optional',
      description: 'data-quality-extension/regex-preset-title-optional-desc',
      pattern: '^\\[.*\\]$|^[^\\[]*$',
      examples: ['[补充题名]', '正常题名']
    }
  ],
  documentNumber: [
    {
      id: 'doc-number-year',
      name: 'data-quality-extension/regex-preset-doc-year',
      description: 'data-quality-extension/regex-preset-doc-year-desc',
      pattern: '^[0-9]{4}$',
      examples: ['2023', '1991']
    },
    {
      id: 'doc-number-order',
      name: 'data-quality-extension/regex-preset-doc-order',
      description: 'data-quality-extension/regex-preset-doc-order-desc',
      pattern: '^[0-9]{1,6}$',
      examples: ['1', '123456']
    },
    {
      id: 'doc-complete-number',
      name: 'data-quality-extension/regex-preset-doc-complete-number',
      description: 'data-quality-extension/regex-preset-doc-complete-number-desc',
      pattern: '^[\\u4e00-\\u9fa5]{1,4}[0-9]{4}-[0-9]+$',
      examples: ['国发2023-1', '粤府2022-123']
    }
  ],
  date: [
    {
      id: 'date-8digit',
      name: 'data-quality-extension/regex-preset-date-8digit',
      description: 'data-quality-extension/regex-preset-date-8digit-desc',
      pattern: '^[0-9]{8}$',
      examples: ['19910101', '20231231']
    },
    {
      id: 'date-ymd',
      name: 'data-quality-extension/regex-preset-date-ymd',
      description: 'data-quality-extension/regex-preset-date-ymd-desc',
      pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$',
      examples: ['1991-01-01', '2023-12-31']
    },
    {
      id: 'date-chinese',
      name: 'data-quality-extension/regex-preset-date-chinese',
      description: 'data-quality-extension/regex-preset-date-chinese-desc',
      pattern: '^[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日$',
      examples: ['1991年1月1日', '2023年12月31日']
    }
  ],
  path: [
    {
      id: 'path-windows',
      name: 'data-quality-extension/regex-preset-path-windows',
      description: 'data-quality-extension/regex-preset-path-windows-desc',
      pattern: '^[a-zA-Z]:\\\\[\\\\S\\s]+$',
      examples: ['D:\\ABC\\test']
    },
    {
      id: 'path-folder',
      name: 'data-quality-extension/regex-preset-path-folder',
      description: 'data-quality-extension/regex-preset-path-folder-desc',
      pattern: '^[^<>:"/\\\\|?*]+$',
      examples: ['0016-WS-2016', 'test_folder']
    },
    {
      id: 'path-filename',
      name: 'data-quality-extension/regex-preset-path-filename',
      description: 'data-quality-extension/regex-preset-path-filename-desc',
      pattern: '^[^<>:"/\\\\|?*\\x00-\\x1f]+$',
      examples: ['document.pdf', '0016-WS-2016-Y-0018']
    },
    {
      id: 'path-extension',
      name: 'data-quality-extension/regex-preset-path-extension',
      description: 'data-quality-extension/regex-preset-path-extension-desc',
      pattern: '\\.[a-zA-Z0-9]+$',
      examples: ['.pdf', '.DOCX']
    }
  ],
  archiveField: [
    {
      id: 'archive-field-archive-number',
      name: 'data-quality-extension/regex-preset-field-archive-number',
      description: 'data-quality-extension/regex-preset-field-archive-number-desc',
      pattern: '^[0-9]{4}-[0-9]{3}-[0-9]{4}$',
      examples: ['0999-001-0001', '1234-001-0001']
    },
    {
      id: 'archive-field-complete-number',
      name: 'data-quality-extension/regex-preset-field-complete-number',
      description: 'data-quality-extension/regex-preset-field-complete-number-desc',
      pattern: '^[0-9]{4}-[0-9]{3}-[0-9]{4}-[0-9]{3}$',
      examples: ['1111-001-0001-001', '1234-001-0001-001']
    },
    {
      id: 'archive-field-title',
      name: 'data-quality-extension/regex-preset-field-title',
      description: 'data-quality-extension/regex-preset-field-title-desc',
      pattern: '^(\\[.*\\]|.{1,3999})$',
      examples: ['[补充题名]', '正常题名123']
    },
    {
      id: 'archive-field-doc-number',
      name: 'data-quality-extension/regex-preset-field-doc-number',
      description: 'data-quality-extension/regex-preset-field-doc-number-desc',
      pattern: '^[\\u4e00-\\u9fa5]{1,4}[0-9]{4}-[0-9]+$',
      examples: ['国发2023-1', '粤府2022-123']
    },
    {
      id: 'archive-field-date',
      name: 'data-quality-extension/regex-preset-field-date',
      description: 'data-quality-extension/regex-preset-field-date-desc',
      pattern: '^[0-9]{8}$',
      examples: ['19910101', '20231231']
    },
    {
      id: 'archive-field-urgency',
      name: 'data-quality-extension/regex-preset-field-urgency',
      description: 'data-quality-extension/regex-preset-field-urgency-desc',
      pattern: '^(普通|紧急|特急|速)$',
      examples: ['普通', '紧急']
    },
    {
      id: 'archive-field-security',
      name: 'data-quality-extension/regex-preset-field-security',
      description: 'data-quality-extension/regex-preset-field-security-desc',
      pattern: '^(机密|秘密|绝密|内部|秘密★[0-9]+年|机密★[0-9]+年|绝密★[0-9]+年)$',
      examples: ['秘密', '机密★10年', '绝密']
    },
    {
      id: 'archive-field-retention',
      name: 'data-quality-extension/regex-preset-field-retention',
      description: 'data-quality-extension/regex-preset-field-retention-desc',
      pattern: '^(永久|长期|短期|定期30年|定期10年)$',
      examples: ['永久', '定期30年', '长期']
    },
    {
      id: 'archive-field-retention-code',
      name: 'data-quality-extension/regex-preset-field-retention-code',
      description: 'data-quality-extension/regex-preset-field-retention-code-desc',
      pattern: '^(Y|C|D|D30|D10)$',
      examples: ['Y', 'D', 'C', 'D10', 'D30']
    },
    {
      id: 'archive-field-open-status',
      name: 'data-quality-extension/regex-preset-field-open-status',
      description: 'data-quality-extension/regex-preset-field-open-status-desc',
      pattern: '^(按期开放|延期开放)$',
      examples: ['按期开放', '延期开放']
    },
    {
      id: 'archive-field-aggregation',
      name: 'data-quality-extension/regex-preset-field-aggregation',
      description: 'data-quality-extension/regex-preset-field-aggregation-desc',
      pattern: '^(卷|件)$',
      examples: ['卷', '件']
    },
    {
      id: 'archive-field-category',
      name: 'data-quality-extension/regex-preset-field-category',
      description: 'data-quality-extension/regex-preset-field-category-desc',
      pattern: '^(WS|ZP|LY|LX|SW|KU|KJ·KY|KJ·JJ|KJ·SB|RS|ZY|YJ|WY|MT|KJ)$',
      examples: ['WS', 'ZP', 'KJ·KY']
    },
    {
      id: 'archive-field-volume-number',
      name: 'data-quality-extension/regex-preset-field-volume-number',
      description: 'data-quality-extension/regex-preset-field-volume-number-desc',
      pattern: '^[0-9]{4}$',
      examples: ['0001', '0123']
    },
    {
      id: 'archive-field-sequence',
      name: 'data-quality-extension/regex-preset-field-sequence',
      description: 'data-quality-extension/regex-preset-field-sequence-desc',
      pattern: '^[0-9]{3,5}$',
      examples: ['001', '00001', '123']
    },
    {
      id: 'archive-field-pages',
      name: 'data-quality-extension/regex-preset-field-pages',
      description: 'data-quality-extension/regex-preset-field-pages-desc',
      pattern: '^[1-9][0-9]*$',
      examples: ['1', '10', '100']
    },
    {
      id: 'archive-field-dept-code',
      name: 'data-quality-extension/regex-preset-field-dept-code',
      description: 'data-quality-extension/regex-preset-field-dept-code-desc',
      pattern: '^[0-9]{6}$',
      examples: ['415025', '123456']
    }
  ],
  archiveFormat: [
    {
      id: 'archive-format-full1',
      name: 'data-quality-extension/regex-preset-format-full1',
      description: 'data-quality-extension/regex-preset-format-full1-desc',
      pattern: '^[0-9]{4}-[A-Za-z]+·[YCD][0-9]*-[A-Z]·[A-Z]·[A-Z]-[0-9]{4}-[0-9]{3}-[0-9]{3}-[0-9]{3}$',
      examples: ['0999-JCDWDM·D-KY·ML·CS-2026-001-002-003']
    },
    {
      id: 'archive-format-full2',
      name: 'data-quality-extension/regex-preset-format-full2',
      description: 'data-quality-extension/regex-preset-format-full2-desc',
      pattern: '^[0-9]{4}·[A-Za-z]+-[YCD][0-9]*-[A-Z]·[A-Z]·[A-Z]-[0-9]{4}-[0-9]{3}-[0-9]{3}-[0-9]{3}$',
      examples: ['0999·Chif-D-KY·ML·CS-2026-001-002-003']
    },
    {
      id: 'archive-format-full3',
      name: 'data-quality-extension/regex-preset-format-full3',
      description: 'data-quality-extension/regex-preset-format-full3-desc',
      pattern: '^[0-9]{4}-[A-Za-z]+-[YCD][0-9]*-[0-9]{4}-[0-9]{3}-[0-9]{3}$',
      examples: ['0999-Dept-D-2026-001-002']
    },
    {
      id: 'archive-format-no-unit',
      name: 'data-quality-extension/regex-preset-format-no-unit',
      description: 'data-quality-extension/regex-preset-format-no-unit-desc',
      pattern: '^[0-9]{4}-[YCD][0-9]*-[0-9]{4}-[0-9]{3}-[0-9]{3}-[0-9]{3}$',
      examples: ['0999-D-2026-001-002-003']
    },
    {
      id: 'archive-format-simple',
      name: 'data-quality-extension/regex-preset-format-simple',
      description: 'data-quality-extension/regex-preset-format-simple-desc',
      pattern: '^[0-9]{4}-[YCD][0-9]*-[0-9]{3}-[0-9]{4}$',
      examples: ['0999-D-001-0001']
    },
    {
      id: 'archive-format-category-year',
      name: 'data-quality-extension/regex-preset-format-category-year',
      description: 'data-quality-extension/regex-preset-format-category-year-desc',
      pattern: '^[0-9]{4}-[A-Z]+·[0-9]{4}-[YCD][0-9]*-[0-9]{3,4}-[0-9]{3,5}$',
      examples: ['0999-WS·2026-D-001-001']
    },
    {
      id: 'archive-format-codetype',
      name: 'data-quality-extension/regex-preset-format-codetype',
      description: 'data-quality-extension/regex-preset-format-codetype-desc',
      pattern: '^[A-Z0-9]+-[YCD][0-9]*-[A-Z]·[A-Z]·[A-Z]-[0-9]{4}-[0-9]{3}-[0-9]{3}-[0-9]{3}$',
      examples: ['QR01-D-KY·ML·CS-2026-001-002-003']
    },
    {
      id: 'archive-format-ws-item',
      name: 'data-quality-extension/regex-preset-format-ws-item',
      description: 'data-quality-extension/regex-preset-format-ws-item-desc',
      pattern: '^[0-9]{4}-[A-Z]+[0-9]{4}-[YCD][0-9]*-[A-Z]+-[0-9]{4}$',
      examples: ['5134-WS2025-Y-BGS-0001']
    },
    {
      id: 'archive-format-kj-volume',
      name: 'data-quality-extension/regex-preset-format-kj-volume',
      description: 'data-quality-extension/regex-preset-format-kj-volume-desc',
      pattern: '^[0-9]{4}-[A-Z]+·[A-Z0-9]+-[0-9]{3,4}$',
      examples: ['8136-KJ·JJ·001-0001']
    },
    {
      id: 'archive-format-zy-volume',
      name: 'data-quality-extension/regex-preset-format-zy-volume',
      description: 'data-quality-extension/regex-preset-format-zy-volume-desc',
      pattern: '^[0-9]{4}-[A-Z]+·[0-9]{4}-[YCD][0-9]*-[0-9]{3,4}$',
      examples: ['8200-ZY·SJ·2025-D30-0001']
    },
    {
      id: 'archive-format-ku-volume',
      name: 'data-quality-extension/regex-preset-format-ku-volume',
      description: 'data-quality-extension/regex-preset-format-ku-volume-desc',
      pattern: '^[0-9]{4}-[A-Z]+·[0-9]+·[0-9]+·[0-9]{4}-[YCD][0-9]*-[0-9]{3,4}$',
      examples: ['3100-KU·01·01·2025-D30-0001']
    },
    {
      id: 'archive-format-sw-item',
      name: 'data-quality-extension/regex-preset-format-sw-item',
      description: 'data-quality-extension/regex-preset-format-sw-item-desc',
      pattern: '^[0-9]{4}-[A-Z]+-[YCD][0-9]*-[0-9]{4}$',
      examples: ['5134-SW-Y-0001']
    },
    {
      id: 'archive-format-zy-level3',
      name: 'data-quality-extension/regex-preset-format-zy-level3',
      description: 'data-quality-extension/regex-preset-format-zy-level3-desc',
      pattern: '^[0-9A-Z]+·[A-Z]+-[A-Z0-9]+-[A-Z]+·[A-Z]+·[A-Z]+-[0-9]+-[0-9]+-[0-9]+$',
      examples: ['WH01·SDDLL-D10-ZY·ML·CS-001-002-003']
    }
  ]
};

RegexEditorDialog.prototype._createDialog = function(options) {
  var self = this;
  
  this._dialog = $(DOM.loadHTML("data-quality", "scripts/dialogs/regex-editor-dialog.html"));
  this._elmts = DOM.bind(this._dialog);
  this._level = DialogSystem.showDialog(this._dialog);
  
  this._elmts.dialogHeader.html($.i18n('data-quality-extension/regex-editor-title'));
  this._elmts.or_dialog_presets.html($.i18n('data-quality-extension/regex-editor-presets'));
  this._elmts.or_dialog_custom.html($.i18n('data-quality-extension/regex-editor-custom'));
  this._elmts.or_dialog_preview.html($.i18n('data-quality-extension/regex-editor-preview'));
  this._elmts.presetSearchInput.attr('placeholder', $.i18n('data-quality-extension/regex-editor-search'));
  this._elmts.or_dialog_categoryArchiveNumber.html($.i18n('data-quality-extension/regex-category-archive-number'));
  this._elmts.or_dialog_categoryDocumentNumber.html($.i18n('data-quality-extension/regex-category-document-number'));
  this._elmts.or_dialog_categoryDate.html($.i18n('data-quality-extension/regex-category-date'));
  this._elmts.or_dialog_categoryPath.html($.i18n('data-quality-extension/regex-category-path'));
  this._elmts.or_dialog_categoryArchiveField.html($.i18n('data-quality-extension/regex-category-archive-field'));
  this._elmts.or_dialog_categoryArchiveFormat.html($.i18n('data-quality-extension/regex-category-archive-format'));
  this._elmts.or_dialog_regexPattern.html($.i18n('data-quality-extension/regex-pattern'));
  this._elmts.or_dialog_flags.html($.i18n('data-quality-extension/regex-flags'));
  this._elmts.or_dialog_flagG.html($.i18n('data-quality-extension/regex-flag-g'));
  this._elmts.or_dialog_flagI.html($.i18n('data-quality-extension/regex-flag-i'));
  this._elmts.or_dialog_flagM.html($.i18n('data-quality-extension/regex-flag-m'));
  this._elmts.or_dialog_regexHelp.html($.i18n('data-quality-extension/regex-help'));
  this._elmts.or_dialog_commonPatterns.html($.i18n('data-quality-extension/regex-common-patterns'));
  this._elmts.or_dialog_testInput.html($.i18n('data-quality-extension/regex-test-input'));
  this._elmts.testButton.html($.i18n('data-quality-extension/regex-test-button'));
  this._elmts.or_dialog_enterTestInput.html($.i18n('data-quality-extension/regex-enter-test-input'));
  this._elmts.cancelButton.html($.i18n('core-buttons/cancel'));
  this._elmts.applyButton.html($.i18n('core-buttons/apply'));
  this._elmts.or_dialog_compositeBuilder.html($.i18n('data-quality-extension/regex-composite-builder'));
  this._elmts.subItemSearchInput.attr('placeholder', $.i18n('data-quality-extension/regex-subitem-search'));
  
  $('#regex-editor-tabs').tabs();
  
  this._populatePresets();
  
  if (options.pattern) {
    this._elmts.regexPatternInput.val(options.pattern);
    this._parseFlags(options.flags || '');
  }
  this._elmts.testInput.val(options.testInput || '');
  
  this._elmts.presetSearchInput.on('input', function() {
    self._filterPresets($(this).val());
  });
  
  this._elmts.subItemSearchInput.on('input', function() {
    self._filterSubitems($(this).val());
  });
  
  this._elmts.testButton.on('click', function() {
    self._runTest();
  });
  
  this._elmts.regexPatternInput.on('input', function() {
    self._onPatternChange();
  });
  
  this._elmts.cancelButton.on('click', function() {
    self._dismiss();
  });
  
  this._elmts.applyButton.on('click', function() {
    self._apply();
  });
  
  this._elmts.clearCompositeButton.on('click', function() {
    self._clearComposite();
  });
  
  this._updateCompositePreview();
};

RegexEditorDialog.prototype._populatePresets = function() {
  var self = this;
  
  var populateCategory = function(container, presets) {
    container.empty();
    $.each(presets, function(index, preset) {
      var presetItem = $('<div>')
        .addClass('regex-preset-item')
        .data('preset', preset)
        .appendTo(container);
      
      $('<div>')
        .addClass('regex-preset-name')
        .text($.i18n(preset.name))
        .appendTo(presetItem);
      
      $('<div>')
        .addClass('regex-preset-desc')
        .text($.i18n(preset.description))
        .appendTo(presetItem);
      
      presetItem.on('click', function() {
        self._selectPreset(preset, presetItem);
      });
    });
  };
  
  populateCategory(this._elmts.archiveNumberPresetList, RegexEditorDialog.PRESETS.archiveNumber);
  populateCategory(this._elmts.documentNumberPresetList, RegexEditorDialog.PRESETS.documentNumber);
  populateCategory(this._elmts.datePresetList, RegexEditorDialog.PRESETS.date);
  populateCategory(this._elmts.pathPresetList, RegexEditorDialog.PRESETS.path);
  populateCategory(this._elmts.archiveFieldPresetList, RegexEditorDialog.PRESETS.archiveField);
  populateCategory(this._elmts.archiveFormatPresetList, RegexEditorDialog.PRESETS.archiveFormat);
};

RegexEditorDialog.prototype._selectPreset = function(preset, element) {
  var isFormatPreset = RegexEditorDialog.PRESETS.archiveFormat.some(function(p) {
    return p.id === preset.id;
  });
  
  if (this._selectedSubitemIndex >= 0 && this._subitems.length > 0) {
    if (isFormatPreset) {
      var subitemsData = this._parseFormatPreset(preset);
      var newSubitems = [];
      var self = this;
      
      $.each(subitemsData, function(index, data) {
        var duplicateId = self._subitems.some(function(item) {
          return item.id === data.id;
        });
        
        if (!duplicateId) {
          newSubitems.push(data);
        }
      });
      
      if (newSubitems.length > 0) {
        this._subitems.splice(this._selectedSubitemIndex, 1, ...newSubitems);
        this._selectedSubitemIndex = this._selectedSubitemIndex + newSubitems.length - 1;
        this._renderSubitems();
        this._updateCompositePreview();
      }
    } else {
      this._replaceSubitem(this._selectedSubitemIndex, preset);
    }
    
    this._elmts.archiveNumberPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.documentNumberPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.datePresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.pathPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.archiveFieldPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.archiveFormatPresetList.find('.regex-preset-item').removeClass('selected');
    
    if (element) {
      element.addClass('selected');
    }
    
    return;
  }
  
  if (isFormatPreset) {
    this._addSubitem(preset);
    
    this._elmts.archiveNumberPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.documentNumberPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.datePresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.pathPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.archiveFieldPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.archiveFormatPresetList.find('.regex-preset-item').removeClass('selected');
    
    element.addClass('selected');
  } else {
    this._elmts.regexPatternInput.val(preset.pattern);
    this._elmts.regexPatternInput.focus();
    this._selectedPreset = preset;
    
    this._elmts.archiveNumberPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.documentNumberPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.datePresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.pathPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.archiveFieldPresetList.find('.regex-preset-item').removeClass('selected');
    this._elmts.archiveFormatPresetList.find('.regex-preset-item').removeClass('selected');
    
    if (element) {
      element.addClass('selected');
    }
    
    $('#regex-editor-tabs').tabs('option', 'active', 1);
    
    this._onPatternChange();
  }
};

RegexEditorDialog.prototype._filterPresets = function(searchText) {
  var searchLower = searchText.toLowerCase();
  
  var filterList = function(list) {
    var visibleCount = 0;
    list.find('.regex-preset-item').each(function() {
      var name = $(this).find('.regex-preset-name').text().toLowerCase();
      var desc = $(this).find('.regex-preset-desc').text().toLowerCase();
      
      if (name.indexOf(searchLower) !== -1 || desc.indexOf(searchLower) !== -1) {
        $(this).show();
        visibleCount++;
      } else {
        $(this).hide();
      }
    });
    return visibleCount;
  };
  
  var count1 = filterList(this._elmts.archiveNumberPresetList);
  var count2 = filterList(this._elmts.documentNumberPresetList);
  var count3 = filterList(this._elmts.datePresetList);
  var count4 = filterList(this._elmts.pathPresetList);
  var count5 = filterList(this._elmts.archiveFieldPresetList);
  var count6 = filterList(this._elmts.archiveFormatPresetList);
  
  this._elmts.archiveNumberPresetList.closest('.regex-preset-category').toggle(count1 > 0);
  this._elmts.documentNumberPresetList.closest('.regex-preset-category').toggle(count2 > 0);
  this._elmts.datePresetList.closest('.regex-preset-category').toggle(count3 > 0);
  this._elmts.pathPresetList.closest('.regex-preset-category').toggle(count4 > 0);
  this._elmts.archiveFieldPresetList.closest('.regex-preset-category').toggle(count5 > 0);
  this._elmts.archiveFormatPresetList.closest('.regex-preset-category').toggle(count6 > 0);
  
  this._generateRegexFromInput(searchText);
};

RegexEditorDialog.prototype._generateRegexFromInput = function(input) {
  var generated = this._parseNaturalLanguage(input);
  this._renderGeneratedRegex(generated);
};

RegexEditorDialog.prototype._parseNaturalLanguage = function(input) {
  if (!input || input.length < 1) {
    console.log('[RegexEditor] Input empty or null, returning empty array');
    return [];
  }
  
  console.log('[RegexEditor] _parseNaturalLanguage called with input:', input);
  
  var results = [];
  var inputTrim = input.trim();
  console.log('[RegexEditor] Trimmed input:', inputTrim);
  
  var parseSegment = function(segment) {
    var seg = segment.trim();
    console.log('[RegexEditor] Parsing segment:', seg);
    
    var patterns = [
      { test: /^([A-Za-z]+)开头的([0-9]+)个([字母]+)$/, getPattern: function(m) {
        var prefix = m[1];
        var num = parseInt(m[2]);
        console.log('[RegexEditor] Matched "X开头的N个字母" pattern:', m);
        return { pattern: prefix + '[A-Za-z]{' + (num - prefix.length) + '}', example: prefix + 'b'.repeat(num - prefix.length), name: prefix + '开头的' + num + '个字母' };
      }},
      { test: /^([A-Za-z]+)开头的([一二三四五六七八九十百千0-9]+)个([字母]+)$/, getPattern: function(m) {
        var prefix = m[1];
        var num = this._parseChineseNumber(m[2]);
        return { pattern: prefix + '[A-Za-z]{' + (num - prefix.length) + '}', example: prefix + 'b'.repeat(num - prefix.length), name: prefix + '开头的' + num + '个字母' };
      }},
      { test: /^([0-9]+)开头的([0-9]+)个([数字字母]+)$/, getPattern: function(m) {
        var prefix = m[1];
        var num = parseInt(m[2]);
        var type = m[3];
        var typeMap = { '数字': '\\d', '字母': '[A-Za-z]' };
        var charClass = typeMap[type] || '.';
        return { pattern: prefix + charClass + '{' + (num - prefix.length) + '}', example: prefix + (type === '数字' ? '1' : 'a').repeat(num - prefix.length), name: prefix + '开头的' + num + '个' + type };
      }},
      { test: /^([0-9]+)开头的([一二三四五六七八九十百千0-9]+)个([数字字母]+)$/, getPattern: function(m) {
        var prefix = m[1];
        var num = this._parseChineseNumber(m[2]);
        var type = m[3];
        var typeMap = { '数字': '\\d', '字母': '[A-Za-z]' };
        var charClass = typeMap[type] || '.';
        return { pattern: prefix + charClass + '{' + (num - prefix.length) + '}', example: prefix + (type === '数字' ? '1' : 'a').repeat(num - prefix.length), name: prefix + '开头的' + num + '个' + type };
      }},
      { test: /^([一二三四五六七八九十百千0-9]+)个([数字字母字符]+)$/, getPattern: function(m) {
        var num = this._parseChineseNumber(m[1]);
        var type = m[2];
        var typeMap = { '数字': '\\d{' + num + '}', '字母': '[A-Za-z]{' + num + '}', '字符': '.{' + num + '}' };
        return { pattern: typeMap[type] || '.{' + num + '}', example: type === '数字' ? '1'.repeat(num) : 'a'.repeat(num), name: m[0] };
      }},
      { test: /^([0-9]+)个([数字字母字符]+)$/, getPattern: function(m) {
        var num = parseInt(m[1]);
        var type = m[2];
        var typeMap = { '数字': '\\d{' + num + '}', '字母': '[A-Za-z]{' + num + '}', '字符': '.{' + num + '}' };
        return { pattern: typeMap[type] || '.{' + num + '}', example: type === '数字' ? '1'.repeat(num) : 'a'.repeat(num), name: m[0] };
      }},
      { test: /^以([A-Za-z]+)开头$/, getPattern: function(m) {
        return { pattern: '^' + m[1], example: m[1] + '123', name: '以' + m[1] + '开头' };
      }},
      { test: /^以([0-9]+)开头$/, getPattern: function(m) {
        return { pattern: '^' + m[1], example: m[1] + 'abc', name: m[1] + '开头' };
      }},
      { test: /^([0-9]+)开头$/, getPattern: function(m) {
        return { pattern: '^' + m[1], example: m[1] + 'abc', name: m[1] + '开头' };
      }},
      { test: /^([A-Za-z]+)结尾$/, getPattern: function(m) {
        return { pattern: m[1] + '$', example: '123' + m[1], name: '以' + m[1] + '结尾' };
      }},
      { test: /^([0-9]+)结尾$/, getPattern: function(m) {
        return { pattern: m[1] + '$', example: 'abc' + m[1], name: m[1] + '结尾' };
      }},
      { test: /^([0-9]+)结尾的([0-9]+)个([数字]+)$/, getPattern: function(m) {
        var suffix = m[1];
        var total = parseInt(m[2]);
        var remaining = total - suffix.length;
        return { pattern: '\\d{' + remaining + '}' + suffix + '$', example: '1'.repeat(remaining) + suffix, name: suffix + '结尾的' + total + '个数字' };
      }},
      { test: /^([0-9]+)结尾的([一二三四五六七八九十百千0-9]+)个([数字]+)$/, getPattern: function(m) {
        var suffix = m[1];
        var total = this._parseChineseNumber(m[2]);
        var remaining = total - suffix.length;
        return { pattern: '\\d{' + remaining + '}' + suffix + '$', example: '1'.repeat(remaining) + suffix, name: suffix + '结尾的' + total + '个数字' };
      }},
      { test: /^([A-Za-z]+)结尾的([0-9]+)个([字母]+)$/, getPattern: function(m) {
        var suffix = m[1];
        var total = parseInt(m[2]);
        var remaining = total - suffix.length;
        return { pattern: '[A-Za-z]{' + remaining + '}' + suffix + '$', example: 'a'.repeat(remaining) + suffix, name: suffix + '结尾的' + total + '个字母' };
      }},
      { test: /^([A-Za-z]+)结尾的([一二三四五六七八九十百千0-9]+)个([字母]+)$/, getPattern: function(m) {
        var suffix = m[1];
        var total = this._parseChineseNumber(m[2]);
        var remaining = total - suffix.length;
        return { pattern: '[A-Za-z]{' + remaining + '}' + suffix + '$', example: 'a'.repeat(remaining) + suffix, name: suffix + '结尾的' + total + '个字母' };
      }},
      { test: /^必须包含(.+)$/, getPattern: function(m) {
        return { pattern: '.*' + m[1] + '.*', example: 'x' + m[1] + 'y', name: '包含"' + m[1] + '"' };
      }},
      { test: /^([一-龥]+)$/, getPattern: function(m) {
        var escaped = m[1].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return { pattern: escaped, example: m[1], name: '匹配"' + m[1] + '"' };
      }},
      { test: /^[0-9]+$/, getPattern: function(m) {
        return { pattern: '^' + m[0] + '$', example: m[0], name: '精确数字"' + m[0] + '"' };
      }}
    ];
    
    for (var i = 0; i < patterns.length; i++) {
      var match = seg.match(patterns[i].test);
      if (match) {
        console.log('[RegexEditor] Matched pattern index', i, ':', patterns[i].test);
        var result = patterns[i].getPattern.call(this, match);
        console.log('[RegexEditor] Parsed result:', result);
        return result;
      }
    }
    
    console.log('[RegexEditor] No pattern matched for segment:', seg);
    return null;
  };
  
  var separators = ['+', '-', '×', '*', '/', '·', '_', ':', ';', ',', '和', '或'];
  var usedSeparators = [];
  for (var i = 0; i < separators.length; i++) {
    if (inputTrim.indexOf(separators[i]) !== -1) {
      usedSeparators.push(separators[i]);
    }
  }
  
  if (usedSeparators.length > 0) {
    var sep = usedSeparators[0];
    console.log('[RegexEditor] Separator found:', sep);
    var segments = inputTrim.split(sep);
    console.log('[RegexEditor] Segments after split:', segments);
    var parsedSegments = [];
    var validSegments = true;
    
    for (var i = 0; i < segments.length; i++) {
      var parsed = parseSegment.call(this, segments[i]);
      console.log('[RegexEditor] Parsed segment', i, ':', segments[i], '->', parsed);
      if (parsed) {
        parsedSegments.push(parsed);
      } else {
        validSegments = false;
        break;
      }
    }
    
    if (validSegments && parsedSegments.length > 0) {
      console.log('[RegexEditor] All segments valid, building pattern');
      var pattern = '^' + parsedSegments.map(function(s) { return s.pattern; }).join('') + '$';
      console.log('[RegexEditor] Final pattern:', pattern);
      var example = parsedSegments.map(function(s) { return s.example; }).join(sep);
      
      var nameParts = segments.map(function(s) { return s.trim(); }).join(' ' + sep + ' ');
      results.push({
        id: 'generated-complex-' + Date.now(),
        name: nameParts,
        pattern: pattern,
        example: example
      });
    }
  } else {
    var singleParsed = parseSegment.call(this, inputTrim);
    if (singleParsed) {
      var finalPattern = singleParsed.pattern;
      if (!singleParsed.pattern.startsWith('^')) {
        finalPattern = '^' + finalPattern;
      }
      if (!singleParsed.pattern.endsWith('$')) {
        finalPattern = finalPattern + '$';
      }
      results.push({
        id: 'generated-single-' + Date.now(),
        name: singleParsed.name,
        pattern: finalPattern,
        example: singleParsed.example
      });
    }
  }
  
  var patterns = [
    { test: /^[0-9]+个数字$/, parse: function(match) {
      var num = parseInt(match[0].match(/^[0-9]+/)[0]);
      return [{
        name: num + '个数字',
        pattern: '^\\d{' + num + '}$',
        example: '0'.repeat(num)
      }];
    }},
    { test: /^[0-9]+个字母$/, parse: function(match) {
      var num = parseInt(match[0].match(/^[0-9]+/)[0]);
      var example = '';
      for (var i = 0; i < num; i++) example += 'a';
      return [{
        name: num + '个字母',
        pattern: '^[A-Za-z]{' + num + '}$',
        example: example
      }];
    }},
    { test: /^[0-9]+个字符$/, parse: function(match) {
      var num = parseInt(match[0].match(/^[0-9]+/)[0]);
      var example = '';
      for (var i = 0; i < num; i++) example += 'a';
      return [{
        name: num + '个字符',
        pattern: '^.{' + num + '}$',
        example: example
      }];
    }},
    { test: /^[一二三四五六七八九十百千0-9]+个数字$/, parse: function(match) {
      var num = this._parseChineseNumber(match[0].match(/[一二三四五六七八九十百千0-9]+/)[0]);
      return [{
        name: num + '个数字',
        pattern: '^\\d{' + num + '}$',
        example: '0'.repeat(num)
      }];
    }},
    { test: /^[一二三四五六七八九十百千0-9]+个字母$/, parse: function(match) {
      var num = this._parseChineseNumber(match[0].match(/[一二三四五六七八九十百千0-9]+/)[0]);
      var example = '';
      for (var i = 0; i < num; i++) example += 'a';
      return [{
        name: num + '个字母',
        pattern: '^[A-Za-z]{' + num + '}$',
        example: example
      }];
    }},
    { test: /^[一二三四五六七八九十百千0-9]+个字符$/, parse: function(match) {
      var num = this._parseChineseNumber(match[0].match(/[一二三四五六七八九十百千0-9]+/)[0]);
      var example = '';
      for (var i = 0; i < num; i++) example += 'a';
      return [{
        name: num + '个字符',
        pattern: '^.{' + num + '}$',
        example: example
      }];
    }},
    { test: /^[0-9]+到[0-9]+个数字$/, parse: function(match) {
      var nums = match[0].match(/[0-9]+/g);
      return [{
        name: nums[0] + '-' + nums[1] + '个数字',
        pattern: '^\\d{' + nums[0] + ',' + nums[1] + '}$',
        example: '0'.repeat(parseInt(nums[0]))
      }];
    }},
    { test: /^[0-9]+到[0-9]+个字母$/, parse: function(match) {
      var nums = match[0].match(/[0-9]+/g);
      var example = '';
      for (var i = 0; i < parseInt(nums[0]); i++) example += 'a';
      return [{
        name: nums[0] + '-' + nums[1] + '个字母',
        pattern: '^[A-Za-z]{' + nums[0] + ',' + nums[1] + '}$',
        example: example
      }];
    }},
    { test: /^[0-9]+到[0-9]+个字符$/, parse: function(match) {
      var nums = match[0].match(/[0-9]+/g);
      var example = '';
      for (var i = 0; i < parseInt(nums[0]); i++) example += 'a';
      return [{
        name: nums[0] + '-' + nums[1] + '个字符',
        pattern: '^.{' + nums[0] + ',' + nums[1] + '}$',
        example: example
      }];
    }},
    { test: /^[A-Za-z]+开头$/, parse: function(match) {
      var letter = match[0].match(/^[A-Za-z]+/)[0];
      return [{
        name: letter + '开头',
        pattern: '^' + letter,
        example: letter + '123'
      }];
    }},
    { test: /^以[A-Za-z]+开头$/, parse: function(match) {
      var letter = match[0].match(/以([A-Za-z]+)开头/)[1];
      return [{
        name: '以' + letter + '开头',
        pattern: '^' + letter,
        example: letter + 'test'
      }];
    }},
    { test: /^[0-9]+开头$/, parse: function(match) {
      var num = match[0].match(/^[0-9]+/)[0];
      return [{
        name: num + '开头',
        pattern: '^' + num,
        example: num + 'abc'
      }];
    }},
    { test: /^必须包含(.+)$/, parse: function(match) {
      var content = match[1];
      return [{
        name: '包含"' + content + '"',
        pattern: '.*' + content + '.*',
        example: 'abc' + content + 'xyz'
      }];
    }},
    { test: /^以(.+)结尾$/, parse: function(match) {
      var content = match[1];
      return [{
        name: '以"' + content + '"结尾',
        pattern: '.*' + content + '$',
        example: 'xyz' + content
      }];
    }},
    { test: /^邮箱$/, parse: function(match) {
      return [{
        name: '邮箱地址',
        pattern: '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$',
        example: 'test@example.com'
      }];
    }},
    { test: /^手机号$/, parse: function(match) {
      return [{
        name: '手机号码',
        pattern: '^1[3-9]\\d{9}$',
        example: '13812345678'
      }];
    }},
    { test: /^身份证$/, parse: function(match) {
      return [{
        name: '身份证号',
        pattern: '^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[1-2]\\d|3[0-1])\\d{3}(\\d|X|x)$',
        example: '110101199001011234'
      }];
    }},
    { test: /^日期$/, parse: function(match) {
      return [{
        name: '日期(YYYY-MM-DD)',
        pattern: '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[1-2]\\d|3[0-1])$',
        example: '2024-01-15'
      }];
    }},
    { test: /^8位日期$/, parse: function(match) {
      return [{
        name: '8位日期',
        pattern: '^[0-9]{8}$',
        example: '20240115'
      }];
    }},
    { test: /^时间$/, parse: function(match) {
      return [{
        name: '时间(HH:mm:ss)',
        pattern: '^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$',
        example: '14:30:00'
      }];
    }},
    { test: /^整数$/, parse: function(match) {
      return [{
        name: '整数',
        pattern: '^-?\\d+$',
        example: '123'
      }];
    }},
    { test: /^正整数$/, parse: function(match) {
      return [{
        name: '正整数',
        pattern: '^\\d+$',
        example: '123'
      }];
    }},
    { test: /^小数$/, parse: function(match) {
      return [{
        name: '小数',
        pattern: '^-?\\d+\\.\\d+$',
        example: '12.34'
      }];
    }},
    { test: /^URL$/, parse: function(match) {
      return [{
        name: '网址URL',
        pattern: '^https?:\\/\\/[\\w\\-.]+(\\.[\\w\\-.]+)+(/[\\w\\-./?%&=]*)?$',
        example: 'https://example.com/path'
      }];
    }},
    { test: /^IP(?:地址)?$/, parse: function(match) {
      return [{
        name: 'IP地址',
        pattern: '^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$',
        example: '192.168.1.1'
      }];
    }}
  ];
  
  for (var i = 0; i < patterns.length; i++) {
    var match = inputTrim.match(patterns[i].test);
    if (match) {
      var parsed = patterns[i].parse.call(this, match);
      for (var j = 0; j < parsed.length; j++) {
        parsed[j].id = 'generated-' + j + '-' + Date.now();
        results.push(parsed[j]);
      }
      break;
    }
  }
  
  if (results.length === 0 && inputTrim.length > 0) {
    var escaped = inputTrim.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    results.push({
      id: 'generated-exact-' + Date.now(),
      name: '精确匹配"' + inputTrim + '"',
      pattern: '^' + escaped + '$',
      example: inputTrim
    });
    
    if (inputTrim.length > 0) {
      results.push({
        id: 'generated-contains-' + Date.now(),
        name: '包含"' + inputTrim + '"',
        pattern: '.*' + escaped + '.*',
        example: 'prefix' + inputTrim + 'suffix'
      });
    }
    
    var lowerInput = inputTrim.toLowerCase();
    results.push({
      id: 'generated-case-insensitive-' + Date.now(),
      name: '不区分大小写"' + inputTrim + '"',
      pattern: '^(?i)' + escaped + '$',
      example: inputTrim.toUpperCase()
    });
  }
  
  console.log('[RegexEditor] Final results:', results);
  return results;
};

RegexEditorDialog.prototype._parseChineseNumber = function(chinese) {
  if (/^[0-9]+$/.test(chinese)) {
    return parseInt(chinese, 10);
  }
  
  var numMap = {
    '一': 1, '二': 2, '三': 3, '四': 4, '五': 5,
    '六': 6, '七': 7, '八': 8, '九': 9, '十': 10,
    '百': 100, '千': 1000
  };
  
  var num = 0;
  var current = 0;
  
  for (var i = 0; i < chinese.length; i++) {
    var digit = numMap[chinese[i]];
    if (digit) {
      if (digit >= 100) {
        current = current * digit;
        if (i === chinese.length - 1 || numMap[chinese[i + 1]] < 100) {
          num += current;
          current = 0;
        }
      } else if (digit >= 10) {
        current = current || 1;
        current = current * digit;
      } else {
        current = current || 0;
        current += digit;
      }
    }
  }
  
  return num + current;
};

RegexEditorDialog.prototype._renderGeneratedRegex = function(generatedList) {
  var container = this._elmts.generatedSection;
  var listContainer = this._elmts.generatedList;
  
  listContainer.empty();
  
  if (generatedList.length === 0) {
    container.removeClass('active');
    return;
  }
  
  container.addClass('active');
  
  var self = this;
  $.each(generatedList, function(index, item) {
    var itemEl = $('<div>')
      .addClass('regex-generated-item')
      .appendTo(listContainer);
    
    $('<div>')
      .addClass('regex-generated-name')
      .text(item.name)
      .appendTo(itemEl);
    
    $('<div>')
      .addClass('regex-generated-pattern')
      .text(item.pattern)
      .appendTo(itemEl);
    
    itemEl.on('click', function() {
      self._applyGeneratedRegex(item);
    });
  });
};

RegexEditorDialog.prototype._applyGeneratedRegex = function(item) {
  this._elmts.regexPatternInput.val(item.pattern);
  this._elmts.regexPatternInput.focus();
  $('#regex-editor-tabs').tabs('option', 'active', 1);
  this._onPatternChange();
};

RegexEditorDialog.prototype._parseFlags = function(flags) {
  this._elmts.flagGCheck.prop('checked', flags.indexOf('g') !== -1);
  this._elmts.flagICheck.prop('checked', flags.indexOf('i') !== -1);
  this._elmts.flagMCheck.prop('checked', flags.indexOf('m') !== -1);
};

RegexEditorDialog.prototype._getFlags = function() {
  var flags = '';
  if (this._elmts.flagGCheck[0].checked) flags += 'g';
  if (this._elmts.flagICheck[0].checked) flags += 'i';
  if (this._elmts.flagMCheck[0].checked) flags += 'm';
  return flags;
};

RegexEditorDialog.prototype._onPatternChange = function() {
  this._selectedPreset = null;
  
  var testInput = this._elmts.testInput.val();
  if (testInput.trim()) {
    this._runTest();
  }
};

RegexEditorDialog.prototype._runTest = function() {
  var pattern = this._elmts.regexPatternInput.val();
  var flags = this._getFlags();
  var testInput = this._elmts.testInput.val();
  
  var resultsContainer = this._elmts.testResults;
  resultsContainer.empty();
  
  if (!pattern.trim()) {
    $('<div>')
      .addClass('regex-result-error')
      .text($.i18n('data-quality-extension/regex-error-empty-pattern'))
      .appendTo(resultsContainer);
    return;
  }
  
  try {
    var regex = new RegExp(pattern, flags);
    var matches = testInput.match(regex);
    
    if (matches === null) {
      $('<div>')
        .addClass('regex-result-no-match')
        .text($.i18n('data-quality-extension/regex-result-no-match'))
        .appendTo(resultsContainer);
    } else {
      var matchCount = matches.length;
      var displayMatches = matches.slice(0, 10);
      
      var resultInfo = $('<div>')
        .addClass('regex-result-info')
        .text($.i18n('data-quality-extension/regex-result-count', matchCount))
        .appendTo(resultsContainer);
      
      var matchList = $('<div>').addClass('regex-match-list').appendTo(resultsContainer);
      
      $.each(displayMatches, function(index, match) {
        var matchItem = $('<div>')
          .addClass('regex-match-item')
          .text(match)
          .appendTo(matchList);
      });
      
      if (matchCount > 10) {
        $('<div>')
          .addClass('regex-match-more')
          .text($.i18n('data-quality-extension/regex-result-more', matchCount - 10))
          .appendTo(matchList);
      }
    }
    
    var highlighted = this._highlightMatches(testInput, regex);
    if (highlighted) {
      $('<div>')
        .addClass('regex-highlighted-result')
        .html(highlighted)
        .appendTo(resultsContainer);
    }
  } catch (e) {
    $('<div>')
      .addClass('regex-result-error')
      .text($.i18n('data-quality-extension/regex-error-invalid', e.message))
      .appendTo(resultsContainer);
  }
};

RegexEditorDialog.prototype._highlightMatches = function(text, regex) {
  try {
    var flags = this._getFlags();
    var globalRegex = new RegExp(regex.source, flags.replace('g', '') + 'g');
    
    var highlighted = text.replace(globalRegex, function(match) {
      return '<span class="regex-match-highlight">' + match + '</span>';
    });
    
    return highlighted.replace(/\n/g, '<br>');
  } catch (e) {
    return null;
  }
};

RegexEditorDialog.prototype._dismiss = function() {
  DialogSystem.dismissUntil(this._level - 1);
};

RegexEditorDialog.prototype._clearComposite = function() {
  this._subitems = [];
  this._selectedSubitemIndex = -1;
  this._renderSubitems();
  this._elmts.compositePatternInput.val('');
  this._elmts.subItemSearchInput.val('');
  this._filterSubitems('');
};

RegexEditorDialog.prototype._apply = function() {
  var pattern = this._elmts.regexPatternInput.val().trim();
  var flags = this._getFlags();
  var testInput = this._elmts.testInput.val();
  
  if (!pattern) {
    alert($.i18n('data-quality-extension/regex-error-empty-pattern'));
    return;
  }
  
  try {
    new RegExp(pattern, flags);
  } catch (e) {
    alert($.i18n('data-quality-extension/regex-error-invalid', e.message));
    return;
  }
  
  if (this._options.onApply && typeof this._options.onApply === 'function') {
    this._options.onApply(pattern);
  }
  
  this._dismiss();
};

RegexEditorDialog.show = function(options) {
  options = options || {};
  var dialog = new RegexEditorDialog(options);
  return dialog;
};

RegexEditorDialog.prototype._addSubitem = function(preset) {
  var isFormatPreset = RegexEditorDialog.PRESETS.archiveFormat.some(function(p) {
    return p.id === preset.id;
  });
  
  if (isFormatPreset) {
    var subitemsData = this._parseFormatPreset(preset);
    var added = false;
    var self = this;
    
    $.each(subitemsData, function(index, data) {
      var duplicateId = self._subitems.some(function(item) {
        return item.id === data.id;
      });
      
      if (!duplicateId) {
        self._subitems.push(data);
        added = true;
      }
    });
    
    if (added) {
      this._renderSubitems();
      this._updateCompositePreview();
    }
  } else {
    var duplicateId = this._subitems.some(function(item) {
      return item.id === preset.id;
    });
    
    if (!duplicateId) {
      var subitem = {
        id: preset.id,
        name: $.i18n(preset.name),
        description: $.i18n(preset.description),
        pattern: preset.pattern,
        examples: preset.examples,
        separator: this._separator
      };
      
      this._subitems.push(subitem);
      this._renderSubitems();
      this._updateCompositePreview();
    }
  }
};

RegexEditorDialog.prototype._addBlankSubitemAfter = function(index) {
  var blankSubitem = {
    id: '',
    name: '',
    pattern: '',
    examples: [],
    separator: '-'
  };
  
  this._subitems.splice(index + 1, 0, blankSubitem);
  this._selectedSubitemIndex = index + 1;
  this._renderSubitems();
  this._updateCompositePreview();
  
  var self = this;
  setTimeout(function() {
    self._elmts.subItemList.find('.regex-subitem').eq(self._selectedSubitemIndex).find('.regex-subitem-name').trigger('click');
  }, 50);
};

RegexEditorDialog.prototype._parseFormatPreset = function(preset) {
  var self = this;
  var subitems = [];
  var example = preset.examples && preset.examples[0] ? preset.examples[0] : '';
  
  var commonPatterns = {
    'fonds-number': {
      id: 'archive-field-fonds-number',
      name: '全宗号',
      pattern: '^[0-9]{4}$',
      example: '0999'
    },
    'dept-code': {
      id: 'archive-field-dept-code',
      name: '单位代码',
      pattern: '^[A-Z0-9]+$',
      example: 'JCDWDM'
    },
    'fonds-with-org': {
      id: 'archive-field-fonds-with-org',
      name: '全宗号·机构代码',
      pattern: '^[0-9A-Z]+·[A-Z]+$',
      example: 'WH01·SDDLL'
    },
    'retention': {
      id: 'archive-field-retention-char',
      name: '保管期限',
      pattern: '^[A-Z]$',
      example: 'D'
    },
    'retention-code': {
      id: 'archive-field-retention-code',
      name: '保管期限代码',
      pattern: '^(Y|C|D|D10|D30)$',
      example: 'Y'
    },
    'year': {
      id: 'archive-field-year',
      name: '年度',
      pattern: '^[0-9]{4}$',
      example: '2026'
    },
    'volume-number': {
      id: 'archive-field-volume-number',
      name: '案卷号',
      pattern: '^[0-9]{3,4}$',
      example: '001'
    },
    'item-number': {
      id: 'archive-field-item-number',
      name: '件号',
      pattern: '^[0-9]{3,4}$',
      example: '002'
    },
    'file-number': {
      id: 'archive-field-file-number',
      name: '顺序号',
      pattern: '^[0-9]{4,5}$',
      example: '0001'
    },
    'category': {
      id: 'archive-field-category-multiple',
      name: '门类代码',
      pattern: '^[A-Z]+(·[A-Z]+)*$',
      example: 'KY·ML·CS'
    },
    'category-year': {
      id: 'archive-field-category-year',
      name: '门类代码·年度',
      pattern: '^[A-Z]+·[0-9]{4}$',
      example: 'WS·2026'
    },
    'extension': {
      id: 'archive-field-extension',
      name: '文件扩展名',
      pattern: '\\.[a-zA-Z0-9]+$',
      example: '.jpg'
    },
    'category-year-merged': {
      id: 'archive-field-category-year-merged',
      name: '门类代码年度',
      pattern: '^[A-Z]+[0-9]{4}$',
      example: 'WS2025'
    },
    'org-code': {
      id: 'archive-field-org-code',
      name: '机构代码',
      pattern: '^[A-Z]+$',
      example: 'BGS'
    },
    'category-year-full': {
      id: 'archive-field-category-year-full',
      name: '门类代码·年度',
      pattern: '^[A-Z]+·[A-Z]+·[0-9]{4}$',
      example: 'ZY·SJ·2025'
    },
    'category-project': {
      id: 'archive-field-category-project',
      name: '档案门类代码·项目号',
      pattern: '^[A-Z]+·[A-Z]+·[A-Z0-9]+$',
      example: 'KJ·JJ·001'
    },
    'category-level3': {
      id: 'archive-field-category-level3',
      name: '三级门类代码',
      pattern: '^[A-Z]+·[A-Z]+·[A-Z]+$',
      example: 'ZY·ML·CS'
    },
    'project-number': {
      id: 'archive-field-project-number',
      name: '项目号',
      pattern: '^[0-9]+$',
      example: '001'
    },
    'ku-unit': {
      id: 'archive-field-ku-unit',
      name: '核算单位代码',
      pattern: '^[0-9]+$',
      example: '01'
    },
    'ku-category': {
      id: 'archive-field-ku-category',
      name: '会计档案类别代码',
      pattern: '^[0-9]+$',
      example: '01'
    }
  };
  
  if (preset.id === 'archive-format-full') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['dept-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['file-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-full1') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['dept-code'], { separator: '·' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['file-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-full2') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '·' }));
    subitems.push($.extend({}, commonPatterns['dept-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['file-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-full3') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['dept-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-no-unit') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['file-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-simple') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-category-year') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category-year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-codetype') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['dept-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['file-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-ws-item') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category-year-merged'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['org-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-kj-volume') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category-project'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-zy-volume') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category-year-full'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-ku-volume') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category'], { separator: '·' }));
    subitems.push($.extend({}, commonPatterns['ku-unit'], { separator: '·' }));
    subitems.push($.extend({}, commonPatterns['ku-category'], { separator: '·' }));
    subitems.push($.extend({}, commonPatterns['year'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-sw-item') {
    subitems.push($.extend({}, commonPatterns['fonds-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '' }));
  } else if (preset.id === 'archive-format-zy-level3') {
    subitems.push($.extend({}, commonPatterns['fonds-with-org'], { separator: '' }));
    subitems.push($.extend({}, commonPatterns['retention-code'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['category-level3'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['project-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['volume-number'], { separator: '-' }));
    subitems.push($.extend({}, commonPatterns['item-number'], { separator: '' }));
  }
  
  $.each(subitems, function(index, item) {
    item.separator = self._separator;
  });
  
  return subitems;
};

RegexEditorDialog.prototype._removeSubitem = function(index) {
  if (index >= 0 && index < this._subitems.length) {
    this._subitems.splice(index, 1);
    this._renderSubitems();
    this._updateCompositePreview();
  }
};

RegexEditorDialog.prototype._selectSubitem = function(index) {
  this._selectedSubitemIndex = index;
  this._renderSubitems();
  
  var currentSubitem = this._subitems[index];
  if (currentSubitem && !currentSubitem.id) {
    this._elmts.archiveFieldPresetList.find('.regex-preset-item:first').trigger('click');
  }
};

RegexEditorDialog.prototype._replaceSubitem = function(index, preset) {
  if (index >= 0 && index < this._subitems.length) {
    this._subitems[index].id = preset.id;
    this._subitems[index].name = $.i18n(preset.name);
    this._subitems[index].description = $.i18n(preset.description);
    this._subitems[index].pattern = preset.pattern;
    this._subitems[index].examples = preset.examples;
    this._renderSubitems();
    this._updateCompositePreview();
  }
};

RegexEditorDialog.prototype._renderSubitems = function() {
  var self = this;
  var container = this._elmts.subItemList;
  
  container.empty();
  
  if (this._subitems.length === 0) {
    $('<div>')
      .addClass('regex-subitem-empty')
      .text($.i18n('data-quality-extension/regex-subitem-empty'))
      .appendTo(container);
    return;
  }
  
  $.each(this._subitems, function(index, subitem) {
    var subitemEl = $('<div>')
      .addClass('regex-subitem')
      .addClass(index === self._selectedSubitemIndex ? 'regex-subitem-selected' : '')
      .data('index', index)
      .appendTo(container);
    
    subitemEl.on('click', function(e) {
      if (e.target.tagName === 'SELECT' || $(e.target).closest('select').length > 0) {
        return;
      }
      self._selectSubitem(index);
    });
    
    var separatorSelect = $('<select>')
      .addClass('regex-subitem-separator')
      .on('mousedown', function(e) {
        e.stopPropagation();
      })
      .appendTo(subitemEl);
    
    var separators = ['-', '·', '_', '/', '\\', '.', ':', ';', '无', '+', '*', '=', '(', ')', '[', ']', '{', '}'];
    $.each(separators, function(i, sep) {
      var option = $('<option>')
        .val(sep === '无' ? '' : sep)
        .text(sep)
        .appendTo(separatorSelect);
      if (sep === '-') {
        option.prop('selected', true);
      }
    });
    
    separatorSelect.val(subitem.separator || '-');
    separatorSelect.on('change', function(e) {
      e.stopPropagation();
      self._subitems[index].separator = $(this).val();
      self._updateCompositePreview();
    });
    
    $('<div>')
      .addClass('regex-subitem-name')
      .text(subitem.name)
      .appendTo(subitemEl);
    
    $('<div>')
      .addClass('regex-subitem-pattern')
      .text(subitem.pattern)
      .appendTo(subitemEl);
    
    var addBtn = $('<button>')
      .addClass('regex-subitem-add')
      .html('+')
      .attr('title', $.i18n('data-quality-extension/regex-add-subitem'))
      .appendTo(subitemEl);
    
    addBtn.on('click', function(e) {
      e.stopPropagation();
      self._addBlankSubitemAfter(index);
    });
    
    var removeBtn = $('<button>')
      .addClass('regex-subitem-remove')
      .html('&times;')
      .attr('title', $.i18n('data-quality-extension/regex-remove-subitem'))
      .appendTo(subitemEl);
    
    removeBtn.on('click', function(e) {
      e.stopPropagation();
      self._removeSubitem(index);
    });
  });
};

RegexEditorDialog.prototype._filterSubitems = function(searchText) {
  var container = this._elmts.subItemList;
  var searchLower = searchText.toLowerCase();
  
  container.find('.regex-subitem').each(function() {
    var name = $(this).find('.regex-subitem-name').text().toLowerCase();
    
    if (name.indexOf(searchLower) !== -1) {
      $(this).show();
    } else {
      $(this).hide();
    }
  });
};

RegexEditorDialog.prototype._updateCompositePreview = function() {
  var compositePattern = '';
  
  $.each(this._subitems, function(index, subitem) {
    var pattern = subitem.pattern;
    var separator = subitem.separator || '';
    
    pattern = pattern.replace(/^\^/, '').replace(/\$$/, '');
    
    if (separator) {
      if (compositePattern) {
        compositePattern += separator;
      }
      compositePattern += '(' + pattern + ')';
    } else {
      if (compositePattern) {
        compositePattern += pattern;
      } else {
        compositePattern += pattern;
      }
    }
  });
  
  if (compositePattern) {
    compositePattern = '^' + compositePattern + '$';
  }
  
  this._elmts.compositePatternInput.val(compositePattern);
  
  if (this._subitems.length > 0) {
    this._elmts.regexPatternInput.val(compositePattern);
    this._onPatternChange();
  }
};
