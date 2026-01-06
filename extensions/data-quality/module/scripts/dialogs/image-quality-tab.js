/**
 * Data Quality Extension - Image Quality Tab
 *
 * Provides UI for configuring and managing image quality check rules
 * Based on 档案四性检测标准 (authenticity, integrity, usability, security)
 */

var ImageQualityTab = {
  _isSetUp: false,
  _currentRule: null,
  _checkResult: null,

  _resourceConfig: null,

  _categoryTypes: [
    { code: 'all', name: 'all-categories' },
    { code: 'authenticity', name: 'authenticity' },
    { code: 'integrity', name: 'integrity' },
    { code: 'usability', name: 'usability' },
    { code: 'security', name: 'security' }
  ],

  _errorTypes: [
    { code: 'all', name: 'all-error-types' },
    { code: 'format', name: 'format-check' },
    { code: 'dpi', name: 'dpi-check' },
    { code: 'bit_depth', name: 'bit-depth-check' },
    { code: 'kb', name: 'kb-check' },
    { code: 'quality', name: 'quality-check' },
    { code: 'blank', name: 'blank-check' },
    { code: 'stain', name: 'stain-check' },
    { code: 'hole', name: 'hole-check' },
    { code: 'skew', name: 'skew-check' },
    { code: 'edge', name: 'edge-check' },
    { code: 'page_size', name: 'page-size-check' }
  ],

  _defaultParams: {
    format: {
      allowedFormats: ['jpeg', 'jpg', 'tiff', 'tif', 'pdf']
    },
    dpi: {
      dpi: 300
    },
    kb: {
      minKb: 10,
      maxKb: 10000
    },
    bit_depth: {
      minBitDepth: 8
    },
    quality: {
      minQuality: 80
    },
    blank: {
    },
    stain: {
      stainValue: 10
    },
    hole: {
      holeValue: 1
    },
    skew: {
      skewTolerance: 0.5
    },
    edge: {
      checkMode: 'standard'
    },
    duplicate: {
      enabled: false
    },
    sequence: {
      enabled: false
    },
    pageSequence: {
      enabled: false
    },
    textDirection: {
      enabled: false
    },
    illegalFiles: {
      enabled: false
    },
    countStats: {
      enabled: false
    },
    emptyFolders: {
      enabled: false
    },
    page_size: {
      enabled: false
    }
  },

  _categoryNames: {
    'authenticity': 'authenticity',
    'integrity': 'integrity',
    'usability': 'usability',
    'security': 'security',
    'technical': 'technical-parameters',
    'storage': 'storage-format',
    'others': 'others'
  },

  _itemNames: {
    'format': 'format-check',
    'dpi': 'dpi-check',
    'kb': 'kb-check',
    'bit_depth': 'bit-depth-check',
    'quality': 'quality-check',
    'blank': 'blank-check',
    'stain': 'stain-check',
    'hole': 'hole-check',
    'skew': 'skew-check',
    'edge': 'edge-check',
    'duplicate': 'duplicate-check',
    'sequence': 'sequence-check',
    'pageSequence': 'page-sequence-check',
    'textDirection': 'text-direction-check',
    'illegalFiles': 'illegal-files-check',
    'countStats': 'count-stats-check',
    'emptyFolders': 'empty-folders-check',
    'page_size': 'page-stats-check'
  },

  _paramNames: {
    allowedFormats: 'allowed-formats',
    dpi: 'dpi-value',
    minKb: 'min-kb',
    maxKb: 'max-kb',
    minBitDepth: 'min-bit-depth',
    minQuality: 'min-quality',
    threshold: 'blank-threshold',
    stainValue: 'stain-value',
    holeValue: 'hole-value',
    skewTolerance: 'skew-tolerance',
    checkMode: 'check-mode'
  }
};

ImageQualityTab.launch = function() {
  if (!this._isSetUp) {
    this.setUpUI();
  }
  this.showPanel();
};

ImageQualityTab.setUpUI = function() {
  var self = this;

  var container = $('#quality-image-quality-content');
  if (container.length === 0) {
    console.error('[ImageQualityTab] Container not found: #quality-image-quality-content');
    return;
  }

  this._container = container;
  this._elmts = DOM.bind(container);

  // 移除筛选功能，不再初始化筛选下拉框
  this._initResourceConfig();
  this._initCheckStandard();
  this._initCategoryPanels();
  this._initActionButtons();
  this._loadRule();
  
  this._isSetUp = true;
};

ImageQualityTab._initFilterDropdowns = function() {
  var self = this;

  var categorySelect = this._elmts.categorySelect;
  var errorTypeSelect = this._elmts.errorTypeSelect;

  categorySelect.empty();
  this._categoryTypes.forEach(function(item) {
    var option = $('<option></option>').val(item.code).text($.i18n(item.name));
    categorySelect.append(option);
  });

  errorTypeSelect.empty();
  this._errorTypes.forEach(function(item) {
    var option = $('<option></option>').val(item.code).text($.i18n(item.name));
    errorTypeSelect.append(option);
  });

  categorySelect.on('change', function() {
    self._filterCategories();
  });

  errorTypeSelect.on('change', function() {
    self._filterItems();
  });
};

ImageQualityTab._initResourceConfig = function() {
  var self = this;

  var resourceConfigElmt = this._elmts.resourceConfig;
  var resourceWarning = this._elmts.resourceWarning;
  var resourceLink = this._elmts.resourceLink;

  this._resourceConfig = QualityAlignment._resourceConfig || {};

  if (this._resourceConfig.basePath && this._resourceConfig.pathFields && this._resourceConfig.pathFields.length > 0) {
    // Format resource path according to file resource association check style
    var pathFields = this._resourceConfig.pathFields;
    var formattedPath = this._resourceConfig.basePath;
    
    if (pathFields.length >= 2) {
      formattedPath += '\\{'+pathFields[0]+'}-{'+pathFields[1]+'}·{'+pathFields[2]+'}\\';
      
      if (pathFields.length >= 3) {
        formattedPath += '{'+pathFields[0]+'}-{'+pathFields[1]+'}·{'+pathFields[2]+'}-{'+pathFields[3]+'}\\';
        
        if (pathFields.length >= 4) {
          formattedPath += '{'+pathFields[0]+'}-{'+pathFields[1]+'}·{'+pathFields[2]+'}-{'+pathFields[3]+'}-{'+pathFields[4]+'}\\';
        }
      }
    } else {
      formattedPath += ' (' + pathFields.join(', ') + ')';
    }
    
    resourceConfigElmt.text(formattedPath);
    resourceWarning.hide();
  } else {
    resourceConfigElmt.text($.i18n('data-quality-extension/resource-not-configured'));
    resourceWarning.show();

    resourceLink.on('click', function() {
      QualityAlignment.switchTab('#quality-resource-panel');
    });
  }
};

ImageQualityTab._initCheckStandard = function() {
    var self = this;
    
    var standardOptions = this._container.find('input[type="radio"][name="check-standard"]');
    standardOptions.on('change', function() {
      var standard = $(this).val();
      self._applyStandard(standard);
      self._updateStandardCount(standard);
      self._markUnsavedChanges();
    });
    
    var currentStandard = this._container.find('input[type="radio"][name="check-standard"]:checked').val() || 'standard';
    this._updateStandardCount(currentStandard);
  };
  
  ImageQualityTab._updateStandardCount = function(standard) {
    var standardConfig = this._getStandardConfig(standard);
    if (!standardConfig) return;
    
    var categoryCount = 0;
    var itemCount = 0;
    
    standardConfig.categories.forEach(function(category) {
      if (!category.enabled) return;
      categoryCount++;
      category.items.forEach(function(item) {
        if (item.enabled) {
          itemCount++;
        }
      });
    });
    
    var standardLabel = $.i18n('data-quality-extension/standard-' + standard);
    var countSuffix = $.i18n('data-quality-extension/check-count-suffix');
    countSuffix = countSuffix.replace('%1$d', categoryCount).replace('%2$d', itemCount);
    var countText = standardLabel + countSuffix;
    
    this._container.find('#iq-standard-count').html('<img src="images/extensions/lightbulb-regular-full.svg" alt="提示" />'  + countText);
  };
  
  ImageQualityTab._initCategoryPanels = function() {
    var self = this;

    var categories = [
      { code: 'authenticity', nameKey: 'authenticity' },
      { code: 'integrity', nameKey: 'integrity' },
      { code: 'usability', nameKey: 'usability' },
      { code: 'security', nameKey: 'security' },
      { code: 'technical', nameKey: 'technical-parameters' },
      { code: 'storage', nameKey: 'storage-format' },
      { code: 'others', nameKey: 'others' }
    ];
    
    var container = this._elmts.categoriesContainer;
    container.empty();

    categories.forEach(function(category) {
      var categoryItems = self._getItemsByCategory(category.code);
      if (categoryItems.length === 0) return;
      
      var sectionTitle = $('<p class="quality-section-title"></p>')
        .text($.i18n('data-quality-extension/' + category.nameKey));
      container.append(sectionTitle);
      
      var section = $('<div class="quality-config-section"></div>');
      container.append(section);
      
      categoryItems.forEach(function(item) {
        var itemElmt = self._createItemElement(item);
        section.append(itemElmt);
      });
    });
  };
  
  ImageQualityTab._applyStandard = function(standard) {
    var self = this;
    
    var standardConfig = this._getStandardConfig(standard);
    if (!standardConfig) return;
    
    this._elmts.categoriesContainer.find('input[type="checkbox"]').prop('checked', false);
    
    standardConfig.categories.forEach(function(category) {
      if (!category.enabled) return;
      
      category.items.forEach(function(item) {
        if (!item.enabled) return;
        
        var checkbox = self._elmts.categoriesContainer.find('input[type="checkbox"][data-item-code="' + item.itemCode + '"]');
        if (checkbox.length === 0) return;
        
        checkbox.prop('checked', true);
        
        if (item.parameters) {
          var itemRow = checkbox.closest('.quality-checkbox-row');
          Object.keys(item.parameters).forEach(function(paramKey) {
            var input = itemRow.find('[data-param-key="' + paramKey + '"]');
            if (input.length > 0) {
              input.val(item.parameters[paramKey]);
            }
          });
        }
      });
    });
  };
  
  ImageQualityTab._getStandardConfig = function(standard) {
    var configs = {
      'simple': {
        categories: [
          {
            type: 'USABILITY',
            enabled: true,
            items: [
              { itemCode: 'format', enabled: true, parameters: { allowedFormats: ['jpeg', 'jpg', 'tiff', 'tif'] } },
              { itemCode: 'blank', enabled: true, parameters: {} },
              { itemCode: 'textDirection', enabled: true, parameters: {} },
              { itemCode: 'skew', enabled: true, parameters: { skewTolerance: 0.5 } }
            ]
          },
          {
            type: 'INTEGRITY',
            enabled: true,
            items: [
              { itemCode: 'sequence', enabled: true, parameters: {} },
              { itemCode: 'pageSequence', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'UNIQUENESS',
            enabled: true,
            items: [
              { itemCode: 'duplicate', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'OTHERS',
            enabled: true,
            items: [
              { itemCode: 'countStats', enabled: true, parameters: {} },
              { itemCode: 'emptyFolders', enabled: true, parameters: {} },
              { itemCode: 'page_size', enabled: true, parameters: {} }
            ]
          }
        ]
      },
      'standard': {
        categories: [
          {
            type: 'USABILITY',
            enabled: true,
            items: [
              { itemCode: 'format', enabled: true, parameters: { allowedFormats: ['jpeg', 'jpg', 'tiff', 'tif'] } },
              { itemCode: 'quality', enabled: true, parameters: { minQuality: 80 } },
              { itemCode: 'blank', enabled: true, parameters: {} },
              { itemCode: 'textDirection', enabled: true, parameters: {} },
              { itemCode: 'skew', enabled: true, parameters: { skewTolerance: 0.5 } },
              { itemCode: 'edge', enabled: true, parameters: { edgeThreshold: 10, checkMode: 'standard' } }
            ]
          },
          {
            type: 'INTEGRITY',
            enabled: true,
            items: [
              { itemCode: 'sequence', enabled: true, parameters: {} },
              { itemCode: 'pageSequence', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'UNIQUENESS',
            enabled: true,
            items: [
              { itemCode: 'duplicate', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'SECURITY',
            enabled: true,
            items: [
              { itemCode: 'illegalFiles', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'TECHNICAL',
            enabled: true,
            items: [
              { itemCode: 'dpi', enabled: true, parameters: { dpi: 300 } },
              { itemCode: 'bit_depth', enabled: true, parameters: { minBitDepth: 8 } }
            ]
          },
          {
            type: 'OTHERS',
            enabled: true,
            items: [
              { itemCode: 'countStats', enabled: true, parameters: {} },
              { itemCode: 'emptyFolders', enabled: true, parameters: {} },
              { itemCode: 'page_size', enabled: true, parameters: {} }
            ]
          }
        ]
      },
      'complex': {
        categories: [
          {
            type: 'USABILITY',
            enabled: true,
            items: [
              { itemCode: 'format', enabled: true, parameters: { allowedFormats: ['jpeg', 'jpg', 'tiff', 'tif', 'pdf'] } },
              { itemCode: 'quality', enabled: true, parameters: { minQuality: 80 } },
              { itemCode: 'blank', enabled: true, parameters: {} },
              { itemCode: 'textDirection', enabled: true, parameters: {} },
              { itemCode: 'skew', enabled: true, parameters: { skewTolerance: 0.5 } },
              { itemCode: 'edge', enabled: true, parameters: { edgeThreshold: 10, checkMode: 'strict' } },
              { itemCode: 'stain', enabled: true, parameters: { stainValue: 10 } },
              { itemCode: 'hole', enabled: true, parameters: { holeValue: 1 } }
            ]
          },
          {
            type: 'INTEGRITY',
            enabled: true,
            items: [
              { itemCode: 'sequence', enabled: true, parameters: {} },
              { itemCode: 'pageSequence', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'UNIQUENESS',
            enabled: true,
            items: [
              { itemCode: 'duplicate', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'SECURITY',
            enabled: true,
            items: [
              { itemCode: 'illegalFiles', enabled: true, parameters: {} }
            ]
          },
          {
            type: 'TECHNICAL',
            enabled: true,
            items: [
              { itemCode: 'dpi', enabled: true, parameters: { dpi: 300 } },
              { itemCode: 'kb', enabled: true, parameters: { minKb: 10, maxKb: 10000 } },
              { itemCode: 'bit_depth', enabled: true, parameters: { minBitDepth: 8 } }
            ]
          },
          {
            type: 'OTHERS',
            enabled: true,
            items: [
              { itemCode: 'countStats', enabled: true, parameters: {} },
              { itemCode: 'emptyFolders', enabled: true, parameters: {} },
              { itemCode: 'page_size', enabled: true, parameters: {} }
            ]
          }
        ]
      }
    };
    
    return configs[standard] || configs['standard'];
  };

ImageQualityTab._createCategoryPanel = function(categoryCode, categoryNameKey) {
  var self = this;
  var panel = $('<div class="iq-category-panel" data-category="' + categoryCode + '"></div>');

  var header = $('<div class="iq-category-header"></div>');
  var title = $('<span class="iq-category-title"></span>').text($.i18n('data-quality-extension/' + categoryNameKey));
  var toggle = $('<span class="iq-category-toggle"></span>');

  header.append(title, toggle);
  panel.append(header);

  var content = $('<div class="iq-category-content"></div>');

  var items = this._getItemsByCategory(categoryCode);
  items.forEach(function(item) {
    var itemElmt = self._createItemElement(item);
    content.append(itemElmt);
  });

  panel.append(content);

  // 默认展开所有分类
  content.show();
  toggle.addClass('expanded');

  // 保留点击展开/折叠功能，但移除复选框相关逻辑
  header.on('click', function(e) {
    content.toggle();
    toggle.toggleClass('expanded');
  });

  return panel;
};

ImageQualityTab._getItemsByCategory = function(categoryCode) {
    var items = [];
    var mapping = {
      'authenticity': ['duplicate'],
      'integrity': ['sequence', 'pageSequence'],
      'usability': ['quality', 'textDirection', 'blank', 'stain', 'hole', 'skew', 'edge'],
      'security': ['illegalFiles'],
      'technical': ['dpi', 'kb', 'bit_depth'],
      'storage': ['format'],
      'others': ['countStats', 'emptyFolders', 'page_size']
    };

    var categoryItems = mapping[categoryCode] || [];
    categoryItems.forEach(function(code) {
      items.push({
        code: code,
        nameKey: 'data-quality-extension/' + this._itemNames[code] || code,
        params: this._defaultParams[code] || {}
      });
    }, this);

    return items;
  };

ImageQualityTab._createItemElement = function(item) {
  var self = this;
  var container = $('<div></div>');

  // Create main checkbox row
  var checkboxRow = $('<div class="quality-checkbox-row"></div>');
  var checkboxId = 'check-image-' + item.code;
  var checkbox = $('<input type="checkbox" id="' + checkboxId + '" data-item-code="' + item.code + '">').data('itemCode', item.code);
  var label = $('<label for="' + checkboxId + '"></label>').append(checkbox).append(' ' + $.i18n(item.nameKey));
  checkboxRow.append(label);
  
  // Add parameters to the same row if any, ignore enabled property
  Object.keys(item.params).forEach(function(paramKey) {
    // Skip enabled property, it's a boolean flag that doesn't need user input
    if (paramKey === 'enabled') {
      return;
    }
    var paramParts = self._createParamParts(item.code, paramKey, item.params[paramKey]);
    checkboxRow.append(paramParts);
  });
  
  container.append(checkboxRow);

  checkbox.on('change', function() {
    self._markUnsavedChanges();
  });

  container.find('input, select').on('change', function() {
    self._markUnsavedChanges();
  });

  return container;
};

ImageQualityTab._createParamParts = function(itemCode, paramKey, paramValue) {
  var paramContainer = $('<span></span>');
  
  // Special handling for format check allowed formats - use checkboxes
  if (itemCode === 'format' && paramKey === 'allowedFormats') {
    var labelText = $('<span></span>').text(' ' + $.i18n('data-quality-extension/' + this._paramNames[paramKey] || paramKey) + ': ');
    paramContainer.append(labelText);
    
    // Required formats: jpg, tif, pdf, ofd and their variants
    var formats = ['jpeg', 'jpg', 'tiff', 'tif', 'pdf', 'ofd'];
    
    // Create a container for horizontal layout
    var formatContainer = $('<span class="format-options-container"></span>');
    paramContainer.append(formatContainer);
    
    formats.forEach(function(format) {
      var isChecked = Array.isArray(paramValue) && paramValue.includes(format);
      var checkboxId = 'format-' + format;
      var checkbox = $('<input type="checkbox" id="' + checkboxId + '" value="' + format + '" data-param-key="' + paramKey + '" ' + (isChecked ? 'checked' : '') + '>');
      var label = $('<label for="' + checkboxId + '" class="format-option"></label>').append(checkbox).append(' ' + format.toUpperCase());
      formatContainer.append(label);
    });
    
    return paramContainer;
  }
  
  // Regular parameter handling for other cases
  var labelText = $('<span></span>').text(' ' + $.i18n('data-quality-extension/' + this._paramNames[paramKey] || paramKey) + ': ');
  paramContainer.append(labelText);
  
  var input;
  var paramType = this._getParamType(paramKey);
  
  switch (paramType) {
    case 'list':
      input = $('<select class="quality-text-input"></select>').attr('data-param-key', paramKey);
      
      if (paramKey === 'checkMode') {
        var options = [
          { value: 'standard', label: '标准' },
          { value: 'strict', label: '严格' }
        ];
        options.forEach(function(opt) {
          var option = $('<option></option>').val(opt.value).text(opt.label);
          if (paramValue === opt.value) {
            option.prop('selected', true);
          }
          input.append(option);
        });
      } else if (paramKey === 'dimension') {
        var options = [
          { value: 'short', label: '短边' },
          { value: 'long', label: '长边' }
        ];
        options.forEach(function(opt) {
          var option = $('<option></option>').val(opt.value).text(opt.label);
          if (paramValue === opt.value) {
            option.prop('selected', true);
          }
          input.append(option);
        });
      } else if (Array.isArray(paramValue)) {
        paramValue.forEach(function(val) {
          input.append($('<option></option>').val(val).text(val));
        });
      }
      break;
    case 'number':
      input = $('<input type="number" class="quality-text-input" data-param-key="' + paramKey + '">').val(paramValue);
      break;
    default:
      input = $('<input type="number" class="quality-text-input" data-param-key="' + paramKey + '">').val(paramValue);
  }

  input.data('itemCode', itemCode);
  paramContainer.append(input);
  
  return paramContainer;
};

ImageQualityTab._getParamType = function(paramKey) {
  if (paramKey === 'allowedFormats' || paramKey === 'checkMode') {
    return 'list';
  }
  if (paramKey === 'minDpi' || paramKey === 'maxDpi' ||
      paramKey === 'minWidth' || paramKey === 'maxWidth' ||
      paramKey === 'minHeight' || paramKey === 'maxHeight' ||
      paramKey === 'minKb' || paramKey === 'maxKb' ||
      paramKey === 'minBitDepth' || paramKey === 'minResolution' ||
      paramKey === 'minQuality' || paramKey === 'threshold' ||
      paramKey === 'stainValue' || paramKey === 'holeValue' ||
      paramKey === 'skewTolerance') {
    return 'number';
  }
  return 'text';
};

ImageQualityTab._initActionButtons = function() {
  // 图片质量标签页使用全局保存按钮，无需本地保存按钮
};

ImageQualityTab._loadRule = function() {
  var self = this;

  Refine.wrapCSRF(function(csrfToken) {
    var url = 'command/data-quality/get-image-quality-rule?' + $.param({ project: theProject.id, csrf_token: csrfToken });

    $.getJSON(url, function(data) {
      if (data.rule && data.rule.categories && data.rule.categories.length > 0) {
        self._currentRule = data.rule;
        self._applyRuleToUI(data.rule);
      } else {
        self._currentRule = self._createDefaultRule();
        self._applyStandard('standard');
      }
    }).fail(function() {
      self._currentRule = self._createDefaultRule();
      self._applyStandard('standard');
    });
  });
};

ImageQualityTab._createDefaultRule = function() {
  return {
    id: 'image-quality-default',
    name: $.i18n('data-quality-extension/image-quality-tab'),
    enabled: true,
    categories: []
  };
};

ImageQualityTab._applyRuleToUI = function(rule) {
  if (!rule) return;

  var self = this;

  // Restore standard selection if available
  if (rule.standard) {
    this._container.find('input[type="radio"][name="check-standard"][value="' + rule.standard + '"]').prop('checked', true);
  }

  // Reset all checkboxes to unchecked
  this._elmts.categoriesContainer.find('input[type="checkbox"]').prop('checked', false);
  
  // Clear all parameter inputs
  this._elmts.categoriesContainer.find('.quality-text-input, select.quality-text-input').val('');
  this._elmts.categoriesContainer.find('input[type="checkbox"][data-param-key]').prop('checked', false);
  
  if (!rule.categories || rule.categories.length === 0) return;
  
  rule.categories.forEach(function(category) {
    category.items.forEach(function(item) {
      var checkbox = self._elmts.categoriesContainer.find('input[type="checkbox"][data-item-code="' + item.itemCode + '"]');
      if (checkbox.length === 0) return;
      
      checkbox.prop('checked', item.enabled);

      var params = item.parameters || {};
      var itemRow = checkbox.closest('.quality-checkbox-row');
      
      Object.keys(params).forEach(function(paramKey) {
        if (paramKey === 'enabled') {
          return;
        }

        var paramValue = params[paramKey];

        if (item.itemCode === 'format' && paramKey === 'allowedFormats' && Array.isArray(paramValue)) {
          itemRow.find('input[type="checkbox"][data-param-key="' + paramKey + '"]').each(function() {
            var formatCheckbox = $(this);
            var isChecked = paramValue.includes(formatCheckbox.val());
            formatCheckbox.prop('checked', isChecked);
          });
        } else {
          var input = itemRow.find('[data-param-key="' + paramKey + '"]');
          if (input.length > 0) {
            if (input.attr('type') === 'checkbox') {
              var isChecked = Array.isArray(paramValue) ? paramValue.includes(input.val()) : paramValue === input.val();
              input.prop('checked', isChecked);
            } else {
              input.val(paramValue);
            }
          }
        }
      });
    });
  }, this);

  // Restore default values for any parameters that were not saved in the rule
  Object.keys(this._defaultParams).forEach(function(itemCode) {
    var defaultItemParams = this._defaultParams[itemCode];
    var checkbox = this._elmts.categoriesContainer.find('input[type="checkbox"][data-item-code="' + itemCode + '"]');
    if (checkbox.length === 0) return;

    var itemRow = checkbox.closest('.quality-checkbox-row');

    Object.keys(defaultItemParams).forEach(function(paramKey) {
      var input = itemRow.find('[data-param-key="' + paramKey + '"]');
      if (input.length > 0 && input.val() === '') {
        input.val(defaultItemParams[paramKey]);
      }
    });
  }, this);
};

ImageQualityTab._collectRuleFromUI = function() {
  var rule = {
    id: this._currentRule ? this._currentRule.id : 'image-quality-default',
    name: $.i18n('data-quality-extension/image-quality-tab'),
    enabled: true,
    standard: this._container.find('input[type="radio"][name="check-standard"]:checked').val() || 'standard',
    categories: []
  };

  var self = this;

  // Collect all categories
  var categories = ['authenticity', 'integrity', 'usability', 'security', 'technical', 'storage', 'others'];
  
  categories.forEach(function(categoryCode) {
    var items = [];
    
    self._elmts.categoriesContainer.find('.quality-checkbox-row input[type="checkbox"]').each(function() {
      var checkbox = $(this);
      var itemCode = checkbox.data('itemCode');
      if (!itemCode) return;
      
      var itemCategory = self._getItemCategory(itemCode);
      if (itemCategory !== categoryCode) return;
      
      var itemElmt = checkbox.closest('.quality-checkbox-row');
      var item = {
        itemCode: itemCode,
        enabled: checkbox.prop('checked'),
        parameters: {}
      };

      var params = {};
      
      if (itemCode === 'format') {
        var allowedFormats = [];
        itemElmt.find('input[type="checkbox"][data-param-key="allowedFormats"]:checked').each(function() {
          allowedFormats.push($(this).val());
        });
        params.allowedFormats = allowedFormats;
      } else {
        itemElmt.find('.quality-text-input, select.quality-text-input').each(function() {
          var input = $(this);
          var paramKey = input.attr('data-param-key');
          if (paramKey) {
            params[paramKey] = input.val();
          }
        });
      }
      
      if (Object.keys(params).length === 0) {
        params.enabled = item.enabled;
      }
      
      item.parameters = params;
      items.push(item);
    });
    
    if (items.length > 0) {
      rule.categories.push({
        categoryCode: categoryCode,
        enabled: true,
        items: items
      });
    }
  });

  return rule;
};

// Helper method to get category for an item
ImageQualityTab._getItemCategory = function(itemCode) {
  var categoryMap = {
    // Authenticity
    'duplicate': 'authenticity',
    
    // Integrity
    'sequence': 'integrity',
    'pageSequence': 'integrity',
    
    // Usability
    'quality': 'usability',
    'textDirection': 'usability',
    'blank': 'usability',
    'stain': 'usability',
    'hole': 'usability',
    'skew': 'usability',
    'edge': 'usability',
    
    // Security
    'illegalFiles': 'security',
    
    // Technical Parameters
    'dpi': 'technical',
    'kb': 'technical',
    
    // Storage Format
    'format': 'storage',
    
    // Others
    'countStats': 'others',
    'emptyFolders': 'others',
    'page_size': 'others'
  };
  return categoryMap[itemCode] || 'others';
};

ImageQualityTab._markUnsavedChanges = function() {
  QualityAlignment._hasUnsavedChanges = true;
  if (QualityAlignment._unsavedIndicator) {
    QualityAlignment._unsavedIndicator.show();
  }
};

ImageQualityTab._saveRule = function() {
  var self = this;
  var rule = this._collectRuleFromUI();

  Refine.postCSRF(
    "command/data-quality/save-image-quality-rule",
    {
      project: theProject.id,
      rule: JSON.stringify(rule)
    },
    function(data) {
      if (data.code === 'ok') {
        self._currentRule = rule;
        QualityAlignment._hasUnsavedChanges = false;
        self._elmts.saveRuleButton.removeClass('unsaved');
        alert($.i18n('data-quality-extension/rule-saved'));
      } else {
        alert($.i18n('data-quality-extension/save-failed') + ': ' + (data.message || $.i18n('data-quality-extension/unknown-error')));
      }
    },
    "json"
  );
};

ImageQualityTab._runCheck = function() {
  var self = this;
  var rule = this._collectRuleFromUI();

  $.ajax({
    url: 'command/data-quality/run-image-quality-check',
    type: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({
      projectId: theProject.id,
      rule: rule
    }),
    success: function(data) {
      if (data.code === 'ok') {
        self._checkResult = data.result;
        alert($.i18n('data-quality-extension/check-completed') + ' ' + (data.result.errorCount || 0) + ' ' + $.i18n('data-quality-extension/issues-found'));
        self._elmts.viewResultsButton.show();
      } else {
        alert($.i18n('data-quality-extension/check-failed') + ': ' + (data.message || $.i18n('data-quality-extension/unknown-error')));
      }
    },
    error: function() {
      alert($.i18n('data-quality-extension/check-failed') + ': ' + $.i18n('data-quality-extension/network-error'));
    }
  });
};

ImageQualityTab._viewResults = function() {
  if (this._checkResult) {
    QualityAlignment._currentResults = this._checkResult;
    QualityAlignment._refreshDataTable();
    QualityAlignment.launch(true);
  }
};

ImageQualityTab._filterCategories = function() {
  var selectedCategory = this._elmts.categorySelect.val();

  this._elmts.categoriesContainer.find('.iq-category-panel').each(function() {
    var panel = $(this);
    var categoryCode = panel.data('category');

    if (selectedCategory === 'all' || categoryCode === selectedCategory) {
      panel.show();
    } else {
      panel.hide();
    }
  });
};

ImageQualityTab._filterItems = function() {
  var selectedType = this._elmts.errorTypeSelect.val();

  this._elmts.categoriesContainer.find('.iq-item').each(function() {
    var item = $(this);
    var itemCode = item.data('item');

    if (selectedType === 'all' || itemCode === selectedType) {
      item.show();
    } else {
      item.hide();
    }
  });
};

ImageQualityTab.showPanel = function() {
  if (this._container) {
    this._container.show();
  }
};

ImageQualityTab.hidePanel = function() {
  if (this._container) {
    this._container.hide();
  }
};
