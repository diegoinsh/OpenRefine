/**
 * Data Quality Extension - Quality Alignment Module
 *
 * Main module for quality rules editing and results display.
 * Follows the pattern of wikibase's SchemaAlignment.
 */

var QualityAlignment = {
  _isSetUp: false,
  _hasUnsavedChanges: false,
  _currentRules: null,
  _currentResults: null,

  // Cell error map for cell marking: { "rowIndex_columnName": [error1, error2, ...] }
  _cellErrorMap: {},

  // Format check rules: { columnName: { nonEmpty: bool, unique: bool, regex: string, dateFormat: string, valueList: [] } }
  _formatRules: {},

  // Resource check config
  _resourceConfig: {
    basePath: '',
    pathFields: [],  // Array of column names used to build path
    pathMode: 'separator',  // 'separator' or 'template'
    separator: '',  // Will use system default if empty
    template: '',   // Template string like {0}/{1}/{2}
    folderChecks: {
      existence: true,
      dataExistence: true,
      nameFormat: '',
      sequential: true
    },
    fileChecks: {
      countMatch: true,
      countColumn: '',
      nameFormat: '',
      sequential: true
    }
  },

  // Content comparison rules: [{ column: '', extractLabel: '', threshold: 90 }]
  _contentRules: [],

  // AIMP service configuration
  _aimpConfig: {
    serviceUrl: ''
  }
};

/**
 * Launch the quality alignment panel
 * @param {boolean} showResults - If true, show results tab; otherwise show rules tab
 */
QualityAlignment.launch = function(showResults, saveState) {
  if (!this._isSetUp) {
    this.setUpTabs();
  }

  if (showResults) {
    // Always re-render results tab when showing results to ensure latest data is displayed
    this._renderResultsTab();
    this.switchTab('#quality-results-panel', saveState);
  } else {
    this.switchTab('#quality-rules-panel', saveState);
  }
};

/**
 * Check if any quality rules are configured
 */
QualityAlignment._hasAnyRules = function() {
  // Check format rules
  if (this._formatRules && Object.keys(this._formatRules).length > 0) {
    return true;
  }
  // Check content rules
  if (this._contentRules && this._contentRules.length > 0) {
    return true;
  }
  // Check resource config - if basePath is set
  if (this._resourceConfig && this._resourceConfig.basePath) {
    return true;
  }
  return false;
};

/**
 * Check if there are any quality check results
 */
QualityAlignment._hasResults = function() {
  return this._currentResults && this._currentResults.errors && this._currentResults.errors.length > 0;
};

/**
 * Build cell error map from current results for cell marking
 * Map structure: { "rowIndex_columnName": [error1, error2, ...] }
 */
QualityAlignment._buildCellErrorMap = function() {
  this._cellErrorMap = {};

  if (!this._currentResults || !this._currentResults.errors) {
    return;
  }

  var errors = this._currentResults.errors;
  for (var i = 0; i < errors.length; i++) {
    var error = errors[i];
    // Only map errors that have both rowIndex and column
    if (error.rowIndex !== undefined && error.rowIndex !== null && error.column) {
      var key = error.rowIndex + '_' + error.column;
      if (!this._cellErrorMap[key]) {
        this._cellErrorMap[key] = [];
      }
      this._cellErrorMap[key].push(error);
    }
  }

  console.log('[QualityAlignment] Cell error map built with', Object.keys(this._cellErrorMap).length, 'cells');
};

/**
 * Refresh data table to show error markers
 */
QualityAlignment._refreshDataTable = function() {
  if (typeof ui !== 'undefined' && ui.dataTableView) {
    ui.dataTableView.render();
  }
};

/**
 * Auto-launch tabs if rules or results exist
 * Called on page load
 */
QualityAlignment.autoLaunchIfNeeded = function() {
  var self = this;
  console.log('[QualityAlignment] autoLaunchIfNeeded called for project:', theProject.id);

  // Load rules and results in parallel
  var rulesLoaded = false;
  var resultsLoaded = false;
  var hasRules = false;
  var hasResults = false;

  function checkAndLaunch() {
    if (!rulesLoaded || !resultsLoaded) return;

    // If there are rules or results, initialize the tabs first
    if (hasRules || hasResults) {
      // Check if this is a page refresh (has saved tab state) or new project entry
      var savedTab = sessionStorage.getItem('quality_current_tab_' + theProject.id);
      var isRefresh = savedTab !== null;

      // Initialize tabs without saving state (saveState = false)
      self.launch(hasResults, false);

      if (isRefresh) {
        // Page refresh: restore to saved tab
        if (savedTab === '#view-panel') {
          self.switchTab('#view-panel', false);
        } else if (savedTab === '#quality-results-panel' && hasResults) {
          self.switchTab('#quality-results-panel', false);
        } else if (savedTab === '#quality-rules-panel') {
          self.switchTab('#quality-rules-panel', false);
        }
      } else {
        // New project entry: switch to data view after tabs are initialized
        self.switchTab('#view-panel', false);
      }
    }
  }

  // Load rules
  $.ajax({
    url: "command/data-quality/get-quality-rules",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(response) {
      if (response.code === "ok" && response.rules) {
        self._formatRules = response.rules.formatRules || {};
        self._resourceConfig = response.rules.resourceConfig || self._getDefaultResourceConfig();
        self._contentRules = response.rules.contentRules || [];
        self._aimpConfig = response.rules.aimpConfig || { serviceUrl: '' };

        hasRules = self._hasAnyRules();

        // If AIMP service URL is configured, test connection
        if (self._aimpConfig.serviceUrl) {
          self._testAndUpdateAimpConnection(self._aimpConfig.serviceUrl);
        }
      }
      rulesLoaded = true;
      checkAndLaunch();
    },
    error: function() {
      rulesLoaded = true;
      checkAndLaunch();
    }
  });

  // Load previous results
  console.log('[QualityAlignment] Loading previous results...');
  $.ajax({
    url: "command/data-quality/get-quality-result",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(response) {
      console.log('[QualityAlignment] get-quality-result response:', response);
      if (response.code === "ok" && response.hasResult && response.result) {
        self._currentResults = response.result;
        self._lastCheckResult = response.result;
        hasResults = true;
        console.log('[QualityAlignment] Found saved results with', response.result.errors ? response.result.errors.length : 0, 'errors');
        // Build cell error map for cell marking
        self._buildCellErrorMap();
        self._refreshDataTable();
      } else {
        console.log('[QualityAlignment] No saved results found');
      }
      resultsLoaded = true;
      checkAndLaunch();
    },
    error: function(xhr, status, error) {
      console.error('[QualityAlignment] Error loading results:', status, error);
      resultsLoaded = true;
      checkAndLaunch();
    }
  });
};

/**
 * Auto-launch with a specific target tab (called when URL has hash)
 * @param {string} target - 'rules' or 'results'
 */
QualityAlignment.autoLaunchIfNeededWithTarget = function(target) {
  var self = this;
  console.log('[QualityAlignment] autoLaunchIfNeededWithTarget called, target:', target);

  // Load rules and results in parallel
  var rulesLoaded = false;
  var resultsLoaded = false;

  function launchWithTarget() {
    if (!rulesLoaded || !resultsLoaded) return;

    console.log('[QualityAlignment] Both loaded, launching with target:', target);
    // Always launch tabs
    var showResults = (target === 'results');
    self.launch(showResults, true);

    // Switch to target tab after launch
    if (target === 'results') {
      self.switchTab('#quality-results-panel', false);
    } else {
      self.switchTab('#quality-rules-panel', false);
    }
  }

  // Load rules
  $.ajax({
    url: "command/data-quality/get-quality-rules",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(response) {
      if (response.code === "ok" && response.rules) {
        self._formatRules = response.rules.formatRules || {};
        self._resourceConfig = response.rules.resourceConfig || self._getDefaultResourceConfig();
        self._contentRules = response.rules.contentRules || [];
        self._aimpConfig = response.rules.aimpConfig || { serviceUrl: '' };
      }
      rulesLoaded = true;
      launchWithTarget();
    },
    error: function() {
      rulesLoaded = true;
      launchWithTarget();
    }
  });

  // Load previous results
  $.ajax({
    url: "command/data-quality/get-quality-result",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(response) {
      console.log('[QualityAlignment] get-quality-result response:', response);
      if (response.code === "ok" && response.hasResult && response.result) {
        self._currentResults = response.result;
        self._lastCheckResult = response.result;
        console.log('[QualityAlignment] Found saved results with', response.result.errors ? response.result.errors.length : 0, 'errors');
        self._buildCellErrorMap();
        self._refreshDataTable();
      }
      resultsLoaded = true;
      launchWithTarget();
    },
    error: function() {
      resultsLoaded = true;
      launchWithTarget();
    }
  });
};

/**
 * Check if tabs are set up
 */
QualityAlignment.isSetUp = function() {
  return this._isSetUp;
};

/**
 * Set up the tabs in the UI
 */
QualityAlignment.setUpTabs = function() {
  this._isSetUp = true;
  this._rightPanel = $('#right-panel');
  this._viewPanel = $('#view-panel').addClass('main-view-panel-tab');
  this._toolPanel = $('#tool-panel');
  this._summaryBar = $('#summary-bar')
    .addClass('main-view-panel-tab-header')
    .addClass('active')
    .attr('href', '#view-panel');

  // Create Quality Rules Panel
  this._rulesPanel = $('<div id="quality-rules-panel"></div>')
    .addClass('main-view-panel-tab')
    .appendTo(this._rightPanel);

  // Create Check Results Panel
  this._resultsPanel = $('<div id="quality-results-panel"></div>')
    .addClass('main-view-panel-tab')
    .appendTo(this._rightPanel);

  // Add tab headers to tool panel
  var rulesButton = $('<div></div>')
    .addClass('main-view-panel-tab-header')
    .attr('href', '#quality-rules-panel')
    .text($.i18n('data-quality-extension/rules-tab-header'))
    .appendTo(this._toolPanel);

  this._unsavedIndicator = $('<span></span>')
    .html('&nbsp;*')
    .attr('title', 'Unsaved changes')
    .hide()
    .appendTo(rulesButton);

  var resultsButton = $('<div></div>')
    .addClass('main-view-panel-tab-header')
    .attr('href', '#quality-results-panel')
    .text($.i18n('data-quality-extension/results-tab-header'))
    .appendTo(this._toolPanel);

  this._resultsCount = $('<span></span>')
    .addClass('quality-results-count')
    .appendTo(resultsButton)
    .hide();

  // Bind tab click events
  $('.main-view-panel-tab-header').off('click.quality').on('click.quality', function(e) {
    var targetTab = $(this).attr('href');
    QualityAlignment.switchTab(targetTab);
    e.preventDefault();
  });

  // Load existing rules from project
  this._loadRules();

  // Initialize tab content
  this._renderRulesTab();
  this._renderResultsTab();
};

/**
 * Switch to a specific tab
 * @param {string} targetTab - The target tab selector
 * @param {boolean} saveState - Whether to save tab state (default: true)
 */
QualityAlignment.switchTab = function(targetTab, saveState) {
  $('.main-view-panel-tab').hide();
  $('.main-view-panel-tab-header').removeClass('active');

  $(targetTab).show();
  $('.main-view-panel-tab-header[href="' + targetTab + '"]').addClass('active');

  // Save current tab state for page refresh (unless explicitly disabled)
  if (saveState !== false) {
    try {
      sessionStorage.setItem('quality_current_tab_' + theProject.id, targetTab);
    } catch (e) {
      // Ignore storage errors
    }
  }
};

/**
 * Render the rules tab content
 */
QualityAlignment._renderRulesTab = function() {
  var self = this;
  this._rulesPanel.empty();
  
  var container = $('<div class="quality-rules-container"></div>')
    .appendTo(this._rulesPanel);
  
  // Sub-tabs for different rule types
  var subTabsHeader = $('<div class="quality-sub-tabs-header"></div>')
    .appendTo(container);
  
  var subTabsContent = $('<div class="quality-sub-tabs-content"></div>')
    .appendTo(container);
  
  // Create sub-tabs
  var subTabs = [
    { id: 'format-check', label: $.i18n('data-quality-extension/format-check-tab') },
    { id: 'resource-check', label: $.i18n('data-quality-extension/resource-check-tab') },
    { id: 'content-check', label: $.i18n('data-quality-extension/content-check-tab') }
  ];
  
  subTabs.forEach(function(tab, index) {
    var tabHeader = $('<div class="quality-sub-tab-header"></div>')
      .attr('data-tab', tab.id)
      .text(tab.label)
      .appendTo(subTabsHeader);
    
    if (index === 0) {
      tabHeader.addClass('active');
    }
    
    var tabContent = $('<div class="quality-sub-tab-content"></div>')
      .attr('id', 'quality-' + tab.id + '-content')
      .appendTo(subTabsContent);
    
    if (index !== 0) {
      tabContent.hide();
    }
    
    tabHeader.on('click', function() {
      self._switchSubTab(tab.id);
    });
  });
  
  // Render each sub-tab content
  this._renderFormatCheckTab();
  this._renderResourceCheckTab();
  this._renderContentCheckTab();

  // Restore saved sub-tab state
  try {
    var savedSubTab = sessionStorage.getItem('quality_current_subtab_' + theProject.id);
    if (savedSubTab && subTabs.some(function(tab) { return tab.id === savedSubTab; })) {
      this._switchSubTab(savedSubTab, false);
    }
  } catch (e) {
    // Ignore storage errors
  }

  // Save button
  var buttonBar = $('<div class="quality-button-bar"></div>')
    .appendTo(container);

  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/save-rules'))
    .on('click', function() {
      self._saveRules();
    })
    .appendTo(buttonBar);
};

/**
 * Switch sub-tab
 * @param {string} tabId - The tab ID to switch to
 * @param {boolean} saveState - Whether to save tab state (default: true)
 */
QualityAlignment._switchSubTab = function(tabId, saveState) {
  $('.quality-sub-tab-header').removeClass('active');
  $('.quality-sub-tab-header[data-tab="' + tabId + '"]').addClass('active');

  $('.quality-sub-tab-content').hide();
  $('#quality-' + tabId + '-content').show();

  // Save current sub-tab state for page refresh (unless explicitly disabled)
  if (saveState !== false) {
    try {
      sessionStorage.setItem('quality_current_subtab_' + theProject.id, tabId);
    } catch (e) {
      // Ignore storage errors
    }
  }
};

/**
 * Render format check sub-tab
 */
QualityAlignment._renderFormatCheckTab = function() {
  var self = this;
  var container = $('#quality-format-check-content');
  container.empty();

  // Template selector
  var templateRow = $('<div class="quality-template-row"></div>')
    .appendTo(container);

  $('<label></label>')
    .text($.i18n('data-quality-extension/rule-template-label'))
    .appendTo(templateRow);

  this._formatTemplateSelect = $('<select class="quality-template-select"></select>')
    .appendTo(templateRow);

  $('<option value=""></option>')
    .text('-- ' + $.i18n('data-quality-extension/select-template') + ' --')
    .appendTo(this._formatTemplateSelect);

  $('<option value="document-2022"></option>')
    .text($.i18n('data-quality-extension/template-document-2022'))
    .appendTo(this._formatTemplateSelect);

  $('<option value="document-1999" disabled></option>')
    .text($.i18n('data-quality-extension/template-document-1999') + ' (' + $.i18n('data-quality-extension/not-implemented') + ')')
    .appendTo(this._formatTemplateSelect);

  $('<option value="document-1985" disabled></option>')
    .text($.i18n('data-quality-extension/template-document-1985') + ' (' + $.i18n('data-quality-extension/not-implemented') + ')')
    .appendTo(this._formatTemplateSelect);

  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/apply-template'))
    .on('click', function() {
      self._applyFormatTemplate(self._formatTemplateSelect.val());
    })
    .appendTo(templateRow);

  // Section title
  $('<p class="quality-section-title"></p>')
    .text($.i18n('data-quality-extension/column-rules-list'))
    .appendTo(container);

  // Rules table
  var rulesTable = $('<table class="quality-rules-table"></table>')
    .appendTo(container);

  var thead = $('<thead></thead>').appendTo(rulesTable);
  var headerRow = $('<tr></tr>').appendTo(thead);
  $('<th></th>').text($.i18n('data-quality-extension/column-name')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/check-items')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/actions')).appendTo(headerRow);

  this._formatRulesBody = $('<tbody id="format-rules-body"></tbody>').appendTo(rulesTable);

  // Render existing rules
  this._refreshFormatRulesTable();

  // Add rule button
  var addRuleRow = $('<div class="quality-add-rule-row"></div>')
    .appendTo(container);

  $('<button class="button"></button>')
    .text('+ ' + $.i18n('data-quality-extension/add-column-rule'))
    .on('click', function() {
      self._addFormatRule();
    })
    .appendTo(addRuleRow);
};

/**
 * Refresh the format rules table
 */
QualityAlignment._refreshFormatRulesTable = function() {
  var self = this;
  this._formatRulesBody.empty();

  var ruleKeys = Object.keys(this._formatRules);
  if (ruleKeys.length === 0) {
    var emptyRow = $('<tr></tr>').appendTo(this._formatRulesBody);
    $('<td colspan="3" class="quality-placeholder"></td>')
      .text($.i18n('data-quality-extension/no-rules-configured'))
      .appendTo(emptyRow);
    return;
  }

  // Get project column names for matching check
  var projectColumnNames = theProject.columnModel.columns.map(function(col) { return col.name; });

  ruleKeys.forEach(function(columnName) {
    var rule = self._formatRules[columnName];
    var row = $('<tr></tr>').appendTo(self._formatRulesBody);

    // Column name - add * if not matched
    var displayName = columnName;
    var isMatched = projectColumnNames.indexOf(columnName) !== -1;
    if (!isMatched) {
      displayName = columnName + ' *';
      row.addClass('unmatched-column');
    }
    var nameCell = $('<td></td>').appendTo(row);
    $('<span></span>').text(displayName).appendTo(nameCell);
    if (!isMatched) {
      $('<span class="unmatched-hint"></span>')
        .text(' (' + $.i18n('data-quality-extension/unmatched') + ')')
        .appendTo(nameCell);
    }

    // Check items
    var checkItemsCell = $('<td></td>').appendTo(row);
    var checkItemsContainer = $('<span></span>').appendTo(checkItemsCell);

    var checkParts = [];
    if (rule.nonEmpty) checkParts.push($.i18n('data-quality-extension/check-non-empty'));
    if (rule.unique) checkParts.push($.i18n('data-quality-extension/check-unique'));
    if (rule.regex) checkParts.push($.i18n('data-quality-extension/check-regex') + ': ' + rule.regex);
    if (rule.dateFormat) checkParts.push($.i18n('data-quality-extension/check-date-format') + ': ' + rule.dateFormat);

    if (checkParts.length > 0) {
      checkItemsContainer.text(checkParts.join(', '));
    }

    // Value list - make it clickable to show content
    if (rule.valueList && rule.valueList.length > 0) {
      if (checkParts.length > 0) {
        $('<span></span>').text(', ').appendTo(checkItemsCell);
      }
      var valueListLink = $('<a href="javascript:void(0)" class="value-list-link"></a>')
        .text($.i18n('data-quality-extension/check-value-list') + ' (' + rule.valueList.length + ')')
        .attr('title', $.i18n('data-quality-extension/click-to-view'))
        .on('click', function(e) {
          e.stopPropagation();
          self._showValueListPopup(columnName, rule.valueList, $(this));
        })
        .appendTo(checkItemsCell);
    }

    if (checkParts.length === 0 && (!rule.valueList || rule.valueList.length === 0)) {
      checkItemsCell.text('-');
    }

    // Actions
    var actionsCell = $('<td></td>').appendTo(row);
    $('<button class="button small-button"></button>')
      .text($.i18n('data-quality-extension/edit'))
      .on('click', function() {
        self._editFormatRule(columnName);
      })
      .appendTo(actionsCell);
    $('<button class="button small-button danger-button"></button>')
      .text($.i18n('data-quality-extension/delete'))
      .on('click', function() {
        self._deleteFormatRule(columnName);
      })
      .appendTo(actionsCell);
  });
};

/**
 * Show value list popup
 */
QualityAlignment._showValueListPopup = function(columnName, valueList, anchorElement) {
  // Remove any existing popup
  $('.value-list-popup').remove();

  var popup = $('<div class="value-list-popup"></div>');
  var header = $('<div class="value-list-popup-header"></div>').appendTo(popup);
  $('<span></span>').text($.i18n('data-quality-extension/value-list-for') + ' ' + columnName).appendTo(header);
  $('<button class="value-list-popup-close">&times;</button>')
    .on('click', function() { popup.remove(); })
    .appendTo(header);

  var content = $('<div class="value-list-popup-content"></div>').appendTo(popup);
  var list = $('<ul class="value-list-items"></ul>').appendTo(content);

  valueList.forEach(function(value) {
    $('<li></li>').text(value).appendTo(list);
  });

  // Position popup near anchor element
  popup.appendTo('body');
  var offset = anchorElement.offset();
  popup.css({
    left: offset.left,
    top: offset.top + anchorElement.outerHeight() + 5
  });

  // Close on click outside
  $(document).one('click', function(e) {
    if (!$(e.target).closest('.value-list-popup, .value-list-link').length) {
      popup.remove();
    }
  });
};

/**
 * Render resource check sub-tab
 */
QualityAlignment._renderResourceCheckTab = function() {
  var self = this;
  var container = $('#quality-resource-check-content');
  container.empty();

  var columns = theProject.columnModel.columns;

  // Resource location section
  $('<p class="quality-section-title"></p>')
    .text($.i18n('data-quality-extension/resource-location'))
    .appendTo(container);

  var locationSection = $('<div class="quality-config-section quality-path-config-section"></div>')
    .appendTo(container);

  // Configure path button and preview
  var pathConfigRow = $('<div class="quality-path-config-row"></div>').appendTo(locationSection);

  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/configure-path'))
    .on('click', function() {
      self._showPathConfigDialog();
    })
    .appendTo(pathConfigRow);

  // Path preview display
  this._pathPreviewSpan = $('<span class="quality-path-preview"></span>').appendTo(pathConfigRow);
  this._updatePathPreview();

  // Folder level checks
  $('<p class="quality-section-title"></p>')
    .text($.i18n('data-quality-extension/folder-level-checks'))
    .appendTo(container);

  var folderSection = $('<div class="quality-config-section"></div>').appendTo(container);

  this._renderCheckbox(folderSection, 'folder-existence', $.i18n('data-quality-extension/folder-existence'),
    this._resourceConfig.folderChecks.existence, function(v) { self._resourceConfig.folderChecks.existence = v; });

  this._renderCheckbox(folderSection, 'data-existence', $.i18n('data-quality-extension/data-existence'),
    this._resourceConfig.folderChecks.dataExistence, function(v) { self._resourceConfig.folderChecks.dataExistence = v; });

  var nameFormatRow = this._renderCheckboxWithInput(folderSection, 'folder-name-format',
    $.i18n('data-quality-extension/folder-name-format'), !!this._resourceConfig.folderChecks.nameFormat,
    this._resourceConfig.folderChecks.nameFormat,
    function(checked) { if (!checked) self._resourceConfig.folderChecks.nameFormat = ''; },
    function(val) { self._resourceConfig.folderChecks.nameFormat = val; });

  this._renderCheckbox(folderSection, 'folder-sequential', $.i18n('data-quality-extension/folder-sequential'),
    this._resourceConfig.folderChecks.sequential, function(v) { self._resourceConfig.folderChecks.sequential = v; });

  // File level checks
  $('<p class="quality-section-title"></p>')
    .text($.i18n('data-quality-extension/file-level-checks'))
    .appendTo(container);

  var fileSection = $('<div class="quality-config-section"></div>').appendTo(container);

  var countRow = $('<div class="quality-checkbox-row"></div>').appendTo(fileSection);
  $('<label></label>')
    .html('<input type="checkbox" id="check-file-count" ' + (this._resourceConfig.fileChecks.countMatch ? 'checked' : '') + '> '
      + $.i18n('data-quality-extension/file-count-match') + ': ' + $.i18n('data-quality-extension/count-column') + ' ')
    .appendTo(countRow);

  this._countColumnSelect = $('<select class="quality-inline-select"></select>').appendTo(countRow);
  $('<option value=""></option>').text('-- ' + $.i18n('data-quality-extension/select-column') + ' --').appendTo(this._countColumnSelect);
  columns.forEach(function(col) {
    $('<option></option>').val(col.name).text(col.name).appendTo(self._countColumnSelect);
  });
  this._countColumnSelect.val(this._resourceConfig.fileChecks.countColumn);
  this._countColumnSelect.on('change', function() { self._resourceConfig.fileChecks.countColumn = $(this).val(); });

  countRow.find('#check-file-count').on('change', function() {
    self._resourceConfig.fileChecks.countMatch = $(this).prop('checked');
  });

  this._renderCheckboxWithInput(fileSection, 'file-name-format',
    $.i18n('data-quality-extension/file-name-format'), !!this._resourceConfig.fileChecks.nameFormat,
    this._resourceConfig.fileChecks.nameFormat,
    function(checked) { if (!checked) self._resourceConfig.fileChecks.nameFormat = ''; },
    function(val) { self._resourceConfig.fileChecks.nameFormat = val; });

  this._renderCheckbox(fileSection, 'file-sequential', $.i18n('data-quality-extension/file-sequential'),
    this._resourceConfig.fileChecks.sequential, function(v) { self._resourceConfig.fileChecks.sequential = v; });
};

/**
 * Helper: render a checkbox
 */
QualityAlignment._renderCheckbox = function(container, id, label, checked, onChange) {
  var row = $('<div class="quality-checkbox-row"></div>').appendTo(container);
  var checkbox = $('<input type="checkbox" id="check-' + id + '">')
    .prop('checked', checked)
    .on('change', function() { onChange($(this).prop('checked')); });
  $('<label></label>').append(checkbox).append(' ' + label).appendTo(row);
  return row;
};

/**
 * Helper: render a checkbox with text input
 */
QualityAlignment._renderCheckboxWithInput = function(container, id, label, checked, inputValue, onCheckChange, onInputChange) {
  var row = $('<div class="quality-checkbox-row"></div>').appendTo(container);
  var checkbox = $('<input type="checkbox" id="check-' + id + '">')
    .prop('checked', checked)
    .on('change', function() { onCheckChange($(this).prop('checked')); });
  $('<label></label>').append(checkbox).append(' ' + label + ': ').appendTo(row);
  $('<input type="text" class="quality-text-input">')
    .val(inputValue)
    .on('change', function() { onInputChange($(this).val()); })
    .appendTo(row);
  return row;
};

/**
 * Handle resource config change
 */
QualityAlignment._onResourceConfigChange = function() {
  this._hasUnsavedChanges = true;
  if (this._unsavedIndicator) {
    this._unsavedIndicator.show();
  }
  this._updatePathPreview();
};

/**
 * Get system path separator
 */
QualityAlignment._getPathSeparator = function() {
  // Detect OS from navigator
  var isWindows = navigator.platform.indexOf('Win') !== -1;
  return isWindows ? '\\' : '/';
};

/**
 * Update path preview display
 */
QualityAlignment._updatePathPreview = function() {
  if (!this._pathPreviewSpan) return;

  var config = this._resourceConfig;
  var defaultSep = this._getPathSeparator();
  var sep = config.separator || defaultSep;
  var preview = '';

  if (config.pathFields && config.pathFields.length > 0) {
    // Build source root
    if (config.basePath) {
      preview = config.basePath;
      if (!preview.endsWith('/') && !preview.endsWith('\\')) {
        preview += sep;
      }
    }

    // Build path based on mode
    if (config.pathMode === 'template' && config.template) {
      preview += config.template.replace(/\{(\d+)\}/g, function(match, index) {
        var idx = parseInt(index);
        return idx < config.pathFields.length ? '{' + config.pathFields[idx] + '}' : match;
      });
    } else {
      preview += config.pathFields.map(function(f) { return '{' + f + '}'; }).join(sep);
    }
  } else {
    preview = $.i18n('data-quality-extension/path-not-configured');
  }

  this._pathPreviewSpan.text(preview);
};

/**
 * Show path configuration dialog (same as export-bound-assets)
 */
QualityAlignment._showPathConfigDialog = function() {
  var self = this;
  var columns = theProject.columnModel.columns;
  var defaultSep = this._getPathSeparator();

  var frame = $('<div class="dialog-frame" style="width: 750px;"></div>');
  var header = $('<div class="dialog-header"></div>')
    .text($.i18n('data-quality-extension/configure-path'))
    .appendTo(frame);
  var body = $('<div class="dialog-body"></div>').appendTo(frame);

  // Main layout: left (fields) + right (options)
  var mainTable = $('<div class="grid-layout grid-layout-for-ui layout-normal layout-full"><table role="presentation"></table></div>').appendTo(body);
  var mainTableInner = mainTable.find('table');

  // Header row
  var headerRow = $('<tr></tr>').appendTo(mainTableInner);
  $('<td></td>').text($.i18n('data-quality-extension/select-path-fields')).appendTo(headerRow);
  $('<td></td>').html('<span>' + $.i18n('data-quality-extension/path-options') + '</span>').appendTo(headerRow);

  // Content row
  var contentRow = $('<tr></tr>').appendTo(mainTableInner);

  // Left: Field list
  var leftTd = $('<td width="40%"></td>').appendTo(contentRow);
  var fieldListContainer = $('<div class="path-config-fields"></div>').appendTo(leftTd);
  var fieldList = $('<ul class="path-field-list"></ul>').appendTo(fieldListContainer);

  // Pre-select fields from config and reorder
  var selectedFields = (this._resourceConfig.pathFields || []).slice();
  var columnNames = columns.map(function(c) { return c.name; });

  // First add selected fields in order, then add remaining
  var orderedColumns = [];
  selectedFields.forEach(function(name) {
    if (columnNames.indexOf(name) !== -1) {
      orderedColumns.push(name);
    }
  });
  columnNames.forEach(function(name) {
    if (orderedColumns.indexOf(name) === -1) {
      orderedColumns.push(name);
    }
  });

  orderedColumns.forEach(function(colName, idx) {
    var li = $('<li class="path-field-item"></li>').attr('data-column', colName).appendTo(fieldList);
    var checkbox = $('<input type="checkbox">')
      .attr('id', 'path-field-' + idx)
      .prop('checked', selectedFields.indexOf(colName) !== -1)
      .on('change', function() { updatePreview(); })
      .appendTo(li);
    $('<label></label>').attr('for', 'path-field-' + idx).text(colName).appendTo(li);
  });

  // Make list sortable
  fieldList.sortable({
    axis: 'y',
    cursor: 'move',
    update: function() { updatePreview(); }
  });

  // Right: Path options
  var rightTd = $('<td></td>').appendTo(contentRow);
  var optionsPane = $('<div class="path-config-options"></div>').appendTo(rightTd);
  var optionsTable = $('<div class="grid-layout layout-normal"><table role="presentation"></table></div>').appendTo(optionsPane);
  var optionsTableInner = optionsTable.find('table');

  // Path join method
  var joinRow1 = $('<tr></tr>').appendTo(optionsTableInner);
  $('<td colspan="2"></td>').text($.i18n('data-quality-extension/path-join-method')).appendTo(joinRow1);

  var joinRow2 = $('<tr></tr>').appendTo(optionsTableInner);
  var joinTd = $('<td></td>').appendTo(joinRow2);
  var joinTable = $('<div class="grid-layout layout-tightest"><table role="presentation"></table></div>').appendTo(joinTd);
  var joinTableInner = joinTable.find('table');

  // Separator option
  var sepRow = $('<tr></tr>').appendTo(joinTableInner);
  var pathMode = this._resourceConfig.pathMode || 'separator';
  $('<td width="1%"></td>').html('<input type="radio" name="path-config-mode" value="separator" id="path-mode-sep" ' + (pathMode === 'separator' ? 'checked' : '') + ' />').appendTo(sepRow);
  var sepLabelTd = $('<td></td>').appendTo(sepRow);
  $('<label for="path-mode-sep"></label>').text($.i18n('data-quality-extension/use-separator') + ' ').appendTo(sepLabelTd);
  var separatorInput = $('<input type="text" class="lightweight" size="5">')
    .val(this._resourceConfig.separator || defaultSep)
    .on('input', function() { updatePreview(); })
    .appendTo(sepLabelTd);

  // Template option
  var tplRow = $('<tr></tr>').appendTo(joinTableInner);
  $('<td width="1%"></td>').html('<input type="radio" name="path-config-mode" value="template" id="path-mode-tpl" ' + (pathMode === 'template' ? 'checked' : '') + ' />').appendTo(tplRow);
  var tplLabelTd = $('<td></td>').appendTo(tplRow);
  $('<label for="path-mode-tpl"></label>').text($.i18n('data-quality-extension/use-template') + ' ').appendTo(tplLabelTd);
  var templateInput = $('<input type="text" class="lightweight" size="30">')
    .val(this._resourceConfig.template || '')
    .attr('placeholder', '{0}/{1}/{2}')
    .on('input', function() { updatePreview(); })
    .appendTo(tplLabelTd);

  // Radio change handler
  optionsPane.find('input[name="path-config-mode"]').on('change', function() { updatePreview(); });

  // Source root path
  var rootRow1 = $('<tr></tr>').appendTo(optionsTableInner);
  $('<td colspan="2" style="padding-top: 15px;"></td>').text($.i18n('data-quality-extension/source-root-path')).appendTo(rootRow1);

  var rootRow2 = $('<tr></tr>').appendTo(optionsTableInner);
  var rootTd = $('<td></td>').appendTo(rootRow2);
  var rootTable = $('<div class="grid-layout layout-tightest"><table role="presentation"></table></div>').appendTo(rootTd);
  var rootTableInner = rootTable.find('table');
  var rootInputRow = $('<tr></tr>').appendTo(rootTableInner);
  $('<td width="100%"></td>').html('<input type="text" class="lightweight" style="width: 100%;" id="source-root-input" />').appendTo(rootInputRow);
  var sourceRootInput = rootTd.find('#source-root-input').val(this._resourceConfig.basePath || '').on('input', function() { updatePreview(); });
  $('<td width="1%"></td>').html('<button class="button">' + $.i18n('data-quality-extension/browse') + '</button>').appendTo(rootInputRow);
  rootInputRow.find('button').on('click', function() {
    var path = prompt($.i18n('data-quality-extension/enter-directory-path') || '请输入目录路径：', sourceRootInput.val());
    if (path && path.trim()) {
      sourceRootInput.val(path.trim());
      updatePreview();
    }
  });

  // Path preview
  var previewRow1 = $('<tr></tr>').appendTo(optionsTableInner);
  $('<td colspan="2" style="padding-top: 15px;"></td>').text($.i18n('data-quality-extension/path-preview')).appendTo(previewRow1);

  var previewRow2 = $('<tr></tr>').appendTo(optionsTableInner);
  var previewPane = $('<div class="path-config-preview"></div>');
  $('<td></td>').append(previewPane).appendTo(previewRow2);

  // Button row
  var buttonRow = $('<tr></tr>').appendTo(mainTableInner);
  var buttonTd = $('<td colspan="2"></td>').appendTo(buttonRow);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/select-all'))
    .on('click', function() {
      fieldList.find('input[type=checkbox]').prop('checked', true);
      updatePreview();
    })
    .appendTo(buttonTd);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/deselect-all'))
    .css('margin-left', '5px')
    .on('click', function() {
      fieldList.find('input[type=checkbox]').prop('checked', false);
      updatePreview();
    })
    .appendTo(buttonTd);

  function updatePreview() {
    var orderedFields = [];
    fieldList.find('li').each(function() {
      if ($(this).find('input[type=checkbox]').prop('checked')) {
        orderedFields.push($(this).data('column'));
      }
    });

    if (orderedFields.length === 0) {
      previewPane.text($.i18n('data-quality-extension/no-fields-selected'));
      return;
    }

    var mode = optionsPane.find('input[name="path-config-mode"]:checked').val();
    var sep = separatorInput.val() || defaultSep;
    var tpl = templateInput.val();
    var sourceRoot = sourceRootInput.val();

    var previewPath = '';
    if (sourceRoot) {
      previewPath = sourceRoot;
      if (!previewPath.endsWith('/') && !previewPath.endsWith('\\')) {
        previewPath += sep;
      }
    }

    if (mode === 'template' && tpl) {
      previewPath += tpl.replace(/\{(\d+)\}/g, function(match, index) {
        var idx = parseInt(index);
        return idx < orderedFields.length ? '{' + orderedFields[idx] + '}' : match;
      });
    } else {
      previewPath += orderedFields.map(function(f) { return '{' + f + '}'; }).join(sep);
    }

    previewPane.text(previewPath);
  }

  updatePreview();

  // Footer
  var footer = $('<div class="dialog-footer"></div>').appendTo(frame);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/cancel'))
    .on('click', function() { DialogSystem.dismissUntil(level - 1); })
    .appendTo(footer);
  $('<button class="button button-primary"></button>')
    .text($.i18n('data-quality-extension/confirm'))
    .on('click', function() {
      // Save config
      self._resourceConfig.basePath = sourceRootInput.val().trim();
      self._resourceConfig.pathMode = optionsPane.find('input[name="path-config-mode"]:checked').val();
      self._resourceConfig.separator = separatorInput.val() || defaultSep;
      self._resourceConfig.template = templateInput.val();
      self._resourceConfig.pathFields = [];
      fieldList.find('li').each(function() {
        if ($(this).find('input[type=checkbox]').prop('checked')) {
          self._resourceConfig.pathFields.push($(this).data('column'));
        }
      });
      self._onResourceConfigChange();
      DialogSystem.dismissUntil(level - 1);
    })
    .appendTo(footer);

  var level = DialogSystem.showDialog(frame);
};

/**
 * Render content check sub-tab
 */
QualityAlignment._renderContentCheckTab = function() {
  var self = this;
  var container = $('#quality-content-check-content');
  container.empty();

  // Service status
  var statusSection = $('<div class="quality-service-status"></div>').appendTo(container);
  $('<span class="quality-status-label"></span>')
    .html('<img src="images/extensions/triangle-exclamation.svg"/> ' + $.i18n('data-quality-extension/aimp-required'))
    .appendTo(statusSection);
  this._serviceStatus = $('<span class="quality-status-indicator"></span>')
    .text($.i18n('data-quality-extension/checking-connection'))
    .appendTo(statusSection);

  // Check service connection
  this._checkAimpConnection();

  // Template selector
  var templateRow = $('<div class="quality-template-row"></div>')
    .appendTo(container);

  $('<label></label>')
    .text($.i18n('data-quality-extension/rule-template-label'))
    .appendTo(templateRow);

  this._contentTemplateSelect = $('<select class="quality-template-select"></select>')
    .appendTo(templateRow);

  $('<option value=""></option>')
    .text('-- ' + $.i18n('data-quality-extension/select-template') + ' --')
    .appendTo(this._contentTemplateSelect);

  $('<option value="content-elements"></option>')
    .text($.i18n('data-quality-extension/template-content-elements'))
    .appendTo(this._contentTemplateSelect);

  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/apply-template'))
    .on('click', function() {
      self._applyContentTemplate(self._contentTemplateSelect.val());
    })
    .appendTo(templateRow);

  // Section title
  $('<p class="quality-section-title"></p>')
    .text($.i18n('data-quality-extension/comparison-rules-list'))
    .appendTo(container);

  // Content rules table
  var rulesTable = $('<table class="quality-rules-table"></table>')
    .appendTo(container);

  var thead = $('<thead></thead>').appendTo(rulesTable);
  var headerRow = $('<tr></tr>').appendTo(thead);
  $('<th></th>').text($.i18n('data-quality-extension/data-column')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/extract-label')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/similarity-threshold')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/actions')).appendTo(headerRow);

  this._contentRulesBody = $('<tbody id="content-rules-body"></tbody>').appendTo(rulesTable);

  // Render existing rules
  this._refreshContentRulesTable();

  // Add rule button
  var addRuleRow = $('<div class="quality-add-rule-row"></div>')
    .appendTo(container);

  $('<button class="button"></button>')
    .text('+ ' + $.i18n('data-quality-extension/add-comparison-rule'))
    .on('click', function() {
      self._addContentRule();
    })
    .appendTo(addRuleRow);
};

/**
 * Check AIMP service connection
 */
QualityAlignment._checkAimpConnection = function() {
  var self = this;

  // Call backend to check AIMP service status
  $.ajax({
    url: "command/data-quality/check-aimp-connection",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(response) {
      if (response.connected) {
        self._serviceStatus
          .removeClass('disconnected')
          .addClass('connected')
          .text('● ' + $.i18n('data-quality-extension/connected'));
        self._aimpConnected = true;
      } else {
        self._serviceStatus
          .removeClass('connected')
          .addClass('disconnected')
          .text('○ ' + $.i18n('data-quality-extension/not-connected'))
          .css('cursor', 'pointer')
          .off('click').on('click', function() {
            self._showAimpConfigDialog();
          });
        self._aimpConnected = false;
      }
    },
    error: function() {
      self._serviceStatus
        .removeClass('connected')
        .addClass('disconnected')
        .text('○ ' + $.i18n('data-quality-extension/not-connected'))
        .css('cursor', 'pointer')
        .off('click').on('click', function() {
          self._showAimpConfigDialog();
        });
      self._aimpConnected = false;
    }
  });
};

/**
 * Show AIMP configuration dialog
 */
QualityAlignment._showAimpConfigDialog = function() {
  var self = this;

  var frame = $('<div class="dialog-frame"></div>');
  $('<div class="dialog-header"></div>')
    .text($.i18n('data-quality-extension/aimp-config-title'))
    .appendTo(frame);
  var body = $('<div class="dialog-body"></div>').appendTo(frame);

  // Service URL
  var urlRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/aimp-service-url') + ': ').appendTo(urlRow);
  var urlInput = $('<input type="text" size="40" placeholder="http://localhost:8080">').appendTo(urlRow);

  // Load current config
  if (this._aimpConfig) {
    urlInput.val(this._aimpConfig.serviceUrl || '');
  }

  var footer = $('<div class="dialog-footer"></div>').appendTo(frame);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/cancel'))
    .on('click', function() { DialogSystem.dismissUntil(level - 1); })
    .appendTo(footer);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/test-connection'))
    .on('click', function() {
      var url = urlInput.val();
      if (!url) {
        alert($.i18n('data-quality-extension/please-enter-url'));
        return;
      }
      // Test connection with CSRF token
      Refine.postCSRF(
        "command/data-quality/test-aimp-connection",
        { serviceUrl: url },
        function(response) {
          if (response.connected) {
            alert($.i18n('data-quality-extension/connection-success'));
          } else {
            alert($.i18n('data-quality-extension/connection-failed') + ': ' + (response.message || ''));
          }
        },
        "json"
      );
    })
    .appendTo(footer);
  $('<button class="button button-primary"></button>')
    .text($.i18n('data-quality-extension/confirm'))
    .on('click', function() {
      var url = urlInput.val();
      self._aimpConfig = {
        serviceUrl: url
      };
      // Save the URL and test connection via POST
      Refine.postCSRF(
        "command/data-quality/check-aimp-connection",
        { serviceUrl: url },
        function(response) {
          if (response.connected) {
            self._aimpConnected = true;
            self._serviceStatus
              .removeClass('disconnected')
              .addClass('connected')
              .text('● ' + $.i18n('data-quality-extension/connected'));
            // Save the AIMP configuration to project rules
            self._saveRules(function(success) {
              DialogSystem.dismissUntil(level - 1);
              if (success) {
                alert($.i18n('data-quality-extension/configuration-saved'));
              }
            });
          } else {
            self._aimpConnected = false;
            self._serviceStatus
              .removeClass('connected')
              .addClass('disconnected')
              .text('○ ' + $.i18n('data-quality-extension/not-connected'))
              .css('cursor', 'pointer')
              .off('click').on('click', function() {
                self._showAimpConfigDialog();
              });
            DialogSystem.dismissUntil(level - 1);
            alert($.i18n('data-quality-extension/connection-failed') + ': ' + (response.message || ''));
          }
        },
        "json"
      );
    })
    .appendTo(footer);

  var level = DialogSystem.showDialog(frame);
};

/**
 * Refresh content rules table
 */
QualityAlignment._refreshContentRulesTable = function() {
  var self = this;
  this._contentRulesBody.empty();

  if (this._contentRules.length === 0) {
    var emptyRow = $('<tr></tr>').appendTo(this._contentRulesBody);
    $('<td colspan="4" class="quality-placeholder"></td>')
      .text($.i18n('data-quality-extension/no-rules-configured'))
      .appendTo(emptyRow);
    return;
  }

  var unmatchedCount = 0;
  this._contentRules.forEach(function(rule, index) {
    var row = $('<tr></tr>').appendTo(self._contentRulesBody);

    // Check if column is matched
    var isUnmatched = rule.matched === false;
    if (isUnmatched) {
      row.addClass('unmatched-column');
      unmatchedCount++;
    }

    // Column name with unmatched indicator
    var columnCell = $('<td></td>').appendTo(row);
    var columnText = rule.column;
    if (isUnmatched) {
      columnText += ' *';
      $('<span></span>').text(columnText).appendTo(columnCell);
      $('<span class="unmatched-hint"></span>')
        .text(' (' + $.i18n('data-quality-extension/unmatched') + ')')
        .appendTo(columnCell);
    } else {
      columnCell.text(columnText);
    }

    $('<td></td>').text(rule.extractLabel).appendTo(row);
    $('<td></td>').text(rule.threshold + '%').appendTo(row);

    var actionsCell = $('<td></td>').appendTo(row);
    $('<button class="button small-button"></button>')
      .text($.i18n('data-quality-extension/edit'))
      .on('click', function() {
        self._editContentRule(index);
      })
      .appendTo(actionsCell);
    $('<button class="button small-button danger-button"></button>')
      .text($.i18n('data-quality-extension/delete'))
      .on('click', function() {
        self._deleteContentRule(index);
      })
      .appendTo(actionsCell);
  });

  // Show unmatched warning
  if (unmatchedCount > 0) {
    var warningRow = $('<tr class="unmatched-warning-row"></tr>').prependTo(this._contentRulesBody);
    $('<td colspan="4" class="unmatched-warning"></td>')
      .text($.i18n('data-quality-extension/unmatched-columns').replace('{0}', unmatchedCount))
      .appendTo(warningRow);
  }
};

/**
 * Render results tab
 */
QualityAlignment._renderResultsTab = function() {
  var self = this;
  this._resultsPanel.empty();

  var container = $('<div class="quality-results-container"></div>')
    .appendTo(this._resultsPanel);

  // Get last check result
  var result = this._lastCheckResult;

  // Summary section
  var summarySection = $('<div class="quality-results-summary"></div>')
    .appendTo(container);

  $('<h3></h3>')
    .text($.i18n('data-quality-extension/results-summary'))
    .appendTo(summarySection);

  // Summary content with stats and chart side by side
  var summaryContent = $('<div class="quality-summary-content"></div>')
    .appendTo(summarySection);

  var summaryStats = $('<div class="quality-summary-stats"></div>')
    .appendTo(summaryContent);

  // Render stats from result
  var stats = {
    total: result ? result.totalRows : 0,
    errors: result ? (result.errors ? result.errors.length : 0) : 0,
    warnings: 0,
    passed: result ? result.passedRows : 0
  };
  this._renderSummaryStats(summaryStats, stats);

  // Pie chart section
  var chartSection = $('<div class="quality-chart-section"></div>')
    .appendTo(summaryContent);

  // Get category counts from result
  var formatErrors = 0, resourceErrors = 0, contentErrors = 0;
  if (result && result.summary) {
    formatErrors = result.summary.formatErrors || 0;
    resourceErrors = result.summary.resourceErrors || 0;
    contentErrors = result.summary.contentErrors || 0;
  } else if (result && result.errors) {
    // Calculate from errors if summary not available
    // Error types from backend:
    // Format: non_empty, regex, date_format, value_list, unique
    // Resource: folder_existence, file_count, file_name_format, file_sequential
    // Content: content_mismatch, content_warning
    result.errors.forEach(function(err) {
      var errType = err.errorType || err.category || '';
      // Format errors
      if (errType === 'non_empty' || errType === 'regex' || errType === 'date_format' ||
          errType === 'value_list' || errType === 'unique' || errType.indexOf('format') >= 0) {
        formatErrors++;
      }
      // Resource errors
      else if (errType === 'folder_existence' || errType === 'file_count' ||
               errType === 'file_name_format' || errType === 'file_sequential' ||
               errType.indexOf('folder') >= 0 || errType.indexOf('file') >= 0) {
        resourceErrors++;
      }
      // Content errors
      else if (errType === 'content_mismatch' || errType === 'content_warning' ||
               errType.indexOf('content') >= 0) {
        contentErrors++;
      }
    });
  }

  this._renderPieChart(chartSection, {
    format: formatErrors,
    resource: resourceErrors,
    content: contentErrors
  });

  // Export buttons
  var exportBar = $('<div class="quality-export-bar"></div>')
    .appendTo(summarySection);

  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/export-excel'))
    .on('click', function() {
      self._exportExcel();
    })
    .appendTo(exportBar);

  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/export-pdf'))
    .on('click', function() {
      self._exportPdf();
    })
    .appendTo(exportBar);

  // Results list with pagination
  var resultsSection = $('<div class="quality-results-list-section"></div>')
    .appendTo(container);

  // Filter bar
  var filterBar = $('<div class="quality-filter-bar"></div>')
    .appendTo(resultsSection);

  // Category filter
  $('<label></label>').text($.i18n('data-quality-extension/category') + ': ').appendTo(filterBar);
  var categoryFilter = $('<select id="quality-category-filter"></select>')
    .appendTo(filterBar);
  $('<option value="">' + $.i18n('data-quality-extension/all') + '</option>').appendTo(categoryFilter);
  $('<option value="format">' + $.i18n('data-quality-extension/format-check-tab') + '</option>').appendTo(categoryFilter);
  $('<option value="resource">' + $.i18n('data-quality-extension/resource-check-tab') + '</option>').appendTo(categoryFilter);
  $('<option value="content">' + $.i18n('data-quality-extension/content-check-tab') + '</option>').appendTo(categoryFilter);

  categoryFilter.on('change', function() {
    self._filterCategory = $(this).val();
    self._filterResults();
  });

  // Error type filter
  $('<label style="margin-left: 15px;"></label>').text($.i18n('data-quality-extension/error-type') + ': ').appendTo(filterBar);
  var typeFilter = $('<select id="quality-type-filter"></select>')
    .appendTo(filterBar);
  $('<option value="">' + $.i18n('data-quality-extension/all') + '</option>').appendTo(typeFilter);
  $('<option value="non_empty">' + $.i18n('data-quality-extension/check-non-empty') + '</option>').appendTo(typeFilter);
  $('<option value="unique">' + $.i18n('data-quality-extension/check-unique') + '</option>').appendTo(typeFilter);
  $('<option value="regex">' + $.i18n('data-quality-extension/check-regex') + '</option>').appendTo(typeFilter);
  $('<option value="date_format">' + $.i18n('data-quality-extension/check-date-format') + '</option>').appendTo(typeFilter);
  $('<option value="value_list">' + $.i18n('data-quality-extension/check-value-list') + '</option>').appendTo(typeFilter);
  $('<option value="folder_existence">' + $.i18n('data-quality-extension/folder-existence') + '</option>').appendTo(typeFilter);
  $('<option value="file_count">' + $.i18n('data-quality-extension/file-count-match') + '</option>').appendTo(typeFilter);
  $('<option value="file_name_format">' + $.i18n('data-quality-extension/file-name-format') + '</option>').appendTo(typeFilter);
  $('<option value="file_sequential">' + $.i18n('data-quality-extension/file-sequential') + '</option>').appendTo(typeFilter);

  typeFilter.on('change', function() {
    self._filterErrorType = $(this).val();
    self._filterResults();
  });

  // Page size selector
  $('<label style="margin-left: 20px;"></label>').text($.i18n('data-quality-extension/page-size') + ': ').appendTo(filterBar);
  var pageSizeSelect = $('<select id="quality-page-size"></select>')
    .appendTo(filterBar);
  [20, 50, 100, 200].forEach(function(size) {
    $('<option></option>').val(size).text(size).appendTo(pageSizeSelect);
  });
  pageSizeSelect.val(this._pageSize || 20);
  pageSizeSelect.on('change', function() {
    self._pageSize = parseInt($(this).val());
    self._currentPage = 1;
    self._renderErrorsTable();
  });

  // Results table
  this._resultsTableContainer = $('<div class="quality-results-table" id="quality-results-table"></div>')
    .appendTo(resultsSection);

  // Pagination
  this._paginationContainer = $('<div class="quality-pagination" id="quality-pagination"></div>')
    .appendTo(resultsSection);

  // Initialize pagination state
  this._pageSize = this._pageSize || 20;
  this._currentPage = 1;
  this._filteredErrors = result && result.errors ? result.errors : [];

  // Render errors table
  this._renderErrorsTable();
};

/**
 * Render errors table with pagination
 */
QualityAlignment._renderErrorsTable = function() {
  var self = this;
  var container = this._resultsTableContainer;
  container.empty();

  var errors = this._filteredErrors || [];
  var pageSize = this._pageSize || 20;
  var currentPage = this._currentPage || 1;
  var totalPages = Math.ceil(errors.length / pageSize) || 1;

  if (errors.length === 0) {
    $('<p class="no-results"></p>')
      .text($.i18n('data-quality-extension/no-errors-found'))
      .appendTo(container);
    this._renderPagination(this._paginationContainer, 1, 1);
    return;
  }

  // Create table
  var table = $('<table class="errors-table"></table>').appendTo(container);
  var thead = $('<thead></thead>').appendTo(table);
  var headerRow = $('<tr></tr>').appendTo(thead);
  $('<th></th>').text($.i18n('data-quality-extension/row-number')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/column-name')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/current-value')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/error-type')).appendTo(headerRow);
  $('<th></th>').text($.i18n('data-quality-extension/error-message')).appendTo(headerRow);

  var tbody = $('<tbody></tbody>').appendTo(table);

  // Calculate page range
  var startIndex = (currentPage - 1) * pageSize;
  var endIndex = Math.min(startIndex + pageSize, errors.length);

  for (var i = startIndex; i < endIndex; i++) {
    var error = errors[i];
    var row = $('<tr></tr>').appendTo(tbody);
    $('<td></td>').text(error.rowIndex + 1).appendTo(row);
    // For resource checks like file_sequential, column is not a real column name
    var columnDisplay = error.column;
    if (error.errorType === 'file_sequential' || error.errorType === 'folder_sequential' ||
        error.errorType === 'file_sequence' || error.errorType === 'folder_sequence') {
      columnDisplay = '';
    }
    $('<td></td>').text(columnDisplay || '(空)').appendTo(row);
    $('<td></td>').text(error.value || '(空)').appendTo(row);
    $('<td></td>').text(this._getErrorTypeLabel(error.errorType)).appendTo(row);
    $('<td></td>').text(this._formatErrorMessage(error)).appendTo(row);

    // Click to navigate to row
    row.css('cursor', 'pointer').on('click', function() {
      var rowIndex = parseInt($(this).find('td:first').text(), 10) - 1;
      // Navigate to row in data table using _showRows method
      if (typeof ui !== 'undefined' && ui.dataTableView && typeof ui.dataTableView._showRows === 'function') {
        // Switch to the data view panel first
        QualityAlignment.switchTab('#view-panel');
        // Then navigate to the row
        ui.dataTableView._showRows({start: rowIndex}, function() {
          // Render after navigation
          ui.dataTableView.render();
          // Highlight the target row
          QualityAlignment._highlightRow(rowIndex);
        });
      }
    });
  }

  this._renderPagination(this._paginationContainer, currentPage, totalPages);
};

/**
 * Get error type label
 */
QualityAlignment._getErrorTypeLabel = function(errorType) {
  var labels = {
    // Format checks
    'non_empty': $.i18n('data-quality-extension/check-non-empty'),
    'unique': $.i18n('data-quality-extension/check-unique'),
    'regex': $.i18n('data-quality-extension/check-regex'),
    'date_format': $.i18n('data-quality-extension/check-date-format'),
    'value_list': $.i18n('data-quality-extension/check-value-list'),
    // Resource checks
    'folder_existence': $.i18n('data-quality-extension/folder-existence'),
    'file_count': $.i18n('data-quality-extension/file-count-match'),
    'file_name_format': $.i18n('data-quality-extension/file-name-format'),
    'file_sequential': $.i18n('data-quality-extension/file-sequential'),
    'file_sequence': $.i18n('data-quality-extension/file-sequential'),
    'folder_sequential': $.i18n('data-quality-extension/folder-sequential'),
    'folder_sequence': $.i18n('data-quality-extension/folder-sequential'),
    // Content checks
    'content_mismatch': $.i18n('data-quality-extension/content-check-tab')
  };
  return labels[errorType] || errorType;
};

/**
 * Format error message for display (translate English messages to localized text)
 */
QualityAlignment._formatErrorMessage = function(error) {
  var message = error.message || '';
  var errorType = error.errorType || '';

  // Handle specific error types with parameters
  if (errorType === 'value_list' && message.indexOf('not in allowed list') !== -1) {
    return $.i18n('data-quality-extension/error-msg-value-not-in-list');
  }

  if (errorType === 'file_count') {
    var match = message.match(/expected (\d+), actual (\d+)/);
    if (match) {
      return $.i18n('data-quality-extension/error-msg-file-count-mismatch')
        .replace('{0}', match[1])
        .replace('{1}', match[2]);
    }
  }

  if (errorType === 'file_sequential' || errorType === 'file_sequence' ||
      errorType === 'folder_sequential' || errorType === 'folder_sequence') {
    var seqMatch = message.match(/between (\S+) and (\S+)/);
    if (seqMatch) {
      return $.i18n('data-quality-extension/error-msg-sequence-gap')
        .replace('{0}', seqMatch[1])
        .replace('{1}', seqMatch[2]);
    }
  }

  if (errorType === 'non_empty') {
    return $.i18n('data-quality-extension/error-msg-empty');
  }

  if (errorType === 'unique') {
    return $.i18n('data-quality-extension/error-msg-duplicate');
  }

  if (errorType === 'regex') {
    return $.i18n('data-quality-extension/error-msg-regex-not-match');
  }

  if (errorType === 'date_format') {
    return $.i18n('data-quality-extension/error-msg-date-invalid');
  }

  if (errorType === 'folder_existence') {
    return $.i18n('data-quality-extension/error-msg-folder-not-exist');
  }

  if (errorType === 'file_name_format') {
    return $.i18n('data-quality-extension/error-msg-file-name-invalid');
  }

  if (errorType === 'content_mismatch') {
    var contentMatch = message.match(/similarity (\d+)%.*threshold (\d+)%/i);
    if (contentMatch) {
      return $.i18n('data-quality-extension/error-msg-content-mismatch')
        .replace('{0}', contentMatch[1])
        .replace('{1}', contentMatch[2]);
    }
  }

  // Return original message if no translation found
  return message;
};

/**
 * Filter results by category and error type
 */
QualityAlignment._filterResults = function() {
  var result = this._lastCheckResult;
  var category = this._filterCategory || '';
  var errorType = this._filterErrorType || '';

  if (!result || !result.errors) {
    this._filteredErrors = [];
  } else {
    this._filteredErrors = result.errors.filter(function(e) {
      var matchCategory = !category || e.category === category;
      var matchType = !errorType || e.errorType === errorType;
      return matchCategory && matchType;
    });
  }
  this._currentPage = 1;
  this._renderErrorsTable();
};

/**
 * Render summary statistics
 */
QualityAlignment._renderSummaryStats = function(container, stats) {
  container.empty();

  $('<div class="quality-stat"></div>')
    .html('<span class="stat-label">' + $.i18n('data-quality-extension/total-rows') + ':</span> <span class="stat-value">' + stats.total + '</span>')
    .appendTo(container);

  $('<div class="quality-stat stat-error"></div>')
    .html('<span class="stat-label">' + $.i18n('data-quality-extension/error-count') + ':</span> <span class="stat-value">' + stats.errors + '</span>')
    .appendTo(container);

  $('<div class="quality-stat stat-warning"></div>')
    .html('<span class="stat-label">' + $.i18n('data-quality-extension/warning-count') + ':</span> <span class="stat-value">' + stats.warnings + '</span>')
    .appendTo(container);

  $('<div class="quality-stat stat-pass"></div>')
    .html('<span class="stat-label">' + $.i18n('data-quality-extension/pass-count') + ':</span> <span class="stat-value">' + stats.passed + '</span>')
    .appendTo(container);
};

/**
 * Render pie chart for error distribution
 */
QualityAlignment._renderPieChart = function(container, data) {
  container.empty();

  var total = data.format + data.resource + data.content;
  if (total === 0) {
    $('<div class="chart-no-data"></div>')
      .text($.i18n('data-quality-extension/no-errors'))
      .appendTo(container);
    return;
  }

  // Create canvas for pie chart
  var canvas = $('<canvas class="quality-pie-chart" width="200" height="200"></canvas>')
    .appendTo(container);

  var ctx = canvas[0].getContext('2d');
  var centerX = 100, centerY = 100, radius = 80;

  // Colors for each category
  var colors = {
    format: '#e74c3c',    // Red
    resource: '#f39c12',  // Orange
    content: '#3498db'    // Blue
  };

  // Draw pie slices
  var startAngle = -Math.PI / 2;
  var categories = ['format', 'resource', 'content'];

  categories.forEach(function(cat) {
    if (data[cat] > 0) {
      var sliceAngle = (data[cat] / total) * 2 * Math.PI;
      ctx.beginPath();
      ctx.moveTo(centerX, centerY);
      ctx.arc(centerX, centerY, radius, startAngle, startAngle + sliceAngle);
      ctx.closePath();
      ctx.fillStyle = colors[cat];
      ctx.fill();
      startAngle += sliceAngle;
    }
  });

  // Draw legend
  var legend = $('<div class="chart-legend"></div>').appendTo(container);

  var labels = {
    format: $.i18n('data-quality-extension/format-check-tab'),
    resource: $.i18n('data-quality-extension/resource-check-tab'),
    content: $.i18n('data-quality-extension/content-check-tab')
  };

  categories.forEach(function(cat) {
    if (data[cat] > 0) {
      var percent = Math.round((data[cat] / total) * 100);
      $('<div class="legend-item"></div>')
        .html('<span class="legend-color" style="background:' + colors[cat] + '"></span>' +
              '<span class="legend-label">' + labels[cat] + ': ' + data[cat] + ' (' + percent + '%)</span>')
        .appendTo(legend);
    }
  });
};

/**
 * Render pagination controls
 */
QualityAlignment._renderPagination = function(container, currentPage, totalPages) {
  container.empty();

  if (totalPages <= 1) return;

  var self = this;

  if (currentPage > 1) {
    $('<button class="button pagination-btn"></button>')
      .text('上一页')
      .on('click', function() {
        self._loadResultsPage(currentPage - 1);
      })
      .appendTo(container);
  }

  $('<span class="pagination-info"></span>')
    .text('第 ' + currentPage + ' / ' + totalPages + ' 页')
    .appendTo(container);

  if (currentPage < totalPages) {
    $('<button class="button pagination-btn"></button>')
      .text('下一页')
      .on('click', function() {
        self._loadResultsPage(currentPage + 1);
      })
      .appendTo(container);
  }
};

/**
 * Get column name mapping for templates (Chinese -> English variants)
 */
QualityAlignment._getColumnNameMapping = function() {
  return {
    '档号': ['Dangao', 'dangao', 'FileNo', 'fileno', 'ArchiveNo', 'archiveno', 'RecordNo', 'recordno', 'DH', 'dh'],
    '题名': ['Timing', 'timing', 'Title', 'title', 'Name', 'name', 'TM', 'tm'],
    '日期': ['Riqi', 'riqi', 'Date', 'date', 'DateTime', 'datetime', 'RQ', 'rq'],
    '责任者': ['Zerenzi', 'zerenzi', 'Author', 'author', 'Responsible', 'responsible', 'Creator', 'creator', 'ZRZ', 'zrz'],
    '成文时间': ['Chengwenshijian', 'chengwenshijian', 'CWSJ', 'cwsj', 'DocumentDate', 'documentdate', 'DocumentTime', 'documenttime', 'CreateTime', 'createtime', 'CreationDate', 'creationdate'],
    '成文日期': ['Chengwenriqi', 'chengwenriqi', 'CWRQ', 'cwrq', 'DocumentDate', 'documentdate', 'CreationDate', 'creationdate'],
    '时间': ['Shijian', 'shijian', 'SJ', 'sj', 'Time', 'time'],
    '文号': ['Wenhao', 'wenhao', 'WH', 'wh', 'DocNo', 'docno', 'DocumentNo', 'documentno'],
    '保管期限': ['Baoguanqixian', 'baoguanqixian', 'RetentionPeriod', 'retentionperiod', 'Period', 'period', 'BGQX', 'bgqx'],
    '密级': ['Miji', 'miji', 'SecurityLevel', 'securitylevel', 'Level', 'level', 'Classification', 'classification', 'MJ', 'mj']
  };
};

/**
 * Find matching column name in project columns
 * Returns { matched: true/false, columnName: actualColumnName, displayName: displayName }
 */
QualityAlignment._findMatchingColumn = function(templateColumnName) {
  var columns = theProject.columnModel.columns;
  var columnNames = columns.map(function(col) { return col.name; });
  var mapping = this._getColumnNameMapping();

  // Define fallback mappings: if primary not found, try these alternatives
  var fallbacks = {
    '成文时间': ['成文日期', '时间', '日期']  // If "成文时间" not found, try these alternatives
  };

  // First try exact match with Chinese name
  if (columnNames.indexOf(templateColumnName) !== -1) {
    return { matched: true, columnName: templateColumnName, displayName: templateColumnName };
  }

  // Try matching with English/pinyin variants
  var variants = mapping[templateColumnName] || [];
  for (var i = 0; i < variants.length; i++) {
    var variant = variants[i];
    if (columnNames.indexOf(variant) !== -1) {
      return { matched: true, columnName: variant, displayName: variant };
    }
  }

  // Try fallback Chinese names and their variants
  var fallbackNames = fallbacks[templateColumnName] || [];
  for (var j = 0; j < fallbackNames.length; j++) {
    var fallbackName = fallbackNames[j];
    // Try exact Chinese fallback
    if (columnNames.indexOf(fallbackName) !== -1) {
      return { matched: true, columnName: fallbackName, displayName: fallbackName };
    }
    // Try fallback's English/pinyin variants
    var fallbackVariants = mapping[fallbackName] || [];
    for (var k = 0; k < fallbackVariants.length; k++) {
      var fbVariant = fallbackVariants[k];
      if (columnNames.indexOf(fbVariant) !== -1) {
        return { matched: true, columnName: fbVariant, displayName: fbVariant };
      }
    }
  }

  // No match found - return original template name (not undefined)
  return { matched: false, columnName: templateColumnName, displayName: templateColumnName + ' *' };
};

/**
 * Apply format template
 */
QualityAlignment._applyFormatTemplate = function(templateId) {
  if (!templateId) {
    alert($.i18n('data-quality-extension/please-select-template'));
    return;
  }

  if (templateId === 'document-2022') {
    // 档案著录规则-2022版
    var templateRules = [
      { name: '档号', rule: { nonEmpty: true, unique: true, regex: '', dateFormat: '', valueList: [] } },
      { name: '题名', rule: { nonEmpty: true, unique: false, regex: '', dateFormat: '', valueList: [] } },
      { name: '日期', rule: { nonEmpty: true, unique: false, regex: '', dateFormat: 'yyyyMMdd', valueList: [] } },
      { name: '责任者', rule: { nonEmpty: true, unique: false, regex: '', dateFormat: '', valueList: [] } },
      { name: '保管期限', rule: { nonEmpty: false, unique: false, regex: '', dateFormat: '', valueList: ['永久', '定期30年', '定期10年'] } },
      { name: '密级', rule: { nonEmpty: false, unique: false, regex: '', dateFormat: '', valueList: ['公开', '内部', '秘密', '机密', '绝密'] } }
    ];

    var self = this;
    var unmatchedCount = 0;
    this._formatRules = {};

    templateRules.forEach(function(item) {
      var match = self._findMatchingColumn(item.name);
      // Store rule with additional metadata
      item.rule._templateName = item.name;  // Keep original template name for reference
      item.rule._matched = match.matched;
      self._formatRules[match.columnName] = item.rule;
      if (!match.matched) {
        unmatchedCount++;
      }
    });

    this._hasUnsavedChanges = true;
    if (this._unsavedIndicator) this._unsavedIndicator.show();
    this._refreshFormatRulesTable();

    if (unmatchedCount > 0) {
      alert($.i18n('data-quality-extension/template-applied') + '\n' +
            $.i18n('data-quality-extension/unmatched-columns', unmatchedCount));
    } else {
      alert($.i18n('data-quality-extension/template-applied'));
    }
  }
};

/**
 * Apply content template
 */
QualityAlignment._applyContentTemplate = function(templateId) {
  if (!templateId) {
    alert($.i18n('data-quality-extension/please-select-template'));
    return;
  }

  if (templateId === 'content-elements') {
    // 文书档案要素比对规则 - 4个关键要素
    var templateRules = [
      { templateField: '题名', extractLabel: '题名', threshold: 90 },
      { templateField: '责任者', extractLabel: '责任者', threshold: 90 },
      { templateField: '文号', extractLabel: '文号', threshold: 95 },
      { templateField: '成文时间', extractLabel: '成文时间', threshold: 100 }
    ];

    var self = this;
    this._contentRules = [];

    templateRules.forEach(function(rule) {
      var matchResult = self._findMatchingColumn(rule.templateField);
      self._contentRules.push({
        column: matchResult.columnName,
        extractLabel: rule.extractLabel,
        threshold: rule.threshold,
        templateField: rule.templateField,
        matched: matchResult.matched
      });
    });

    this._hasUnsavedChanges = true;
    if (this._unsavedIndicator) this._unsavedIndicator.show();
    this._refreshContentRulesTable();
    alert($.i18n('data-quality-extension/template-applied'));
  }
};

/**
 * Add format rule - show dialog
 */
QualityAlignment._addFormatRule = function() {
  var self = this;
  var columns = theProject.columnModel.columns;

  // Create dialog
  var frame = $('<div class="dialog-frame"></div>');
  var header = $('<div class="dialog-header"></div>')
    .text($.i18n('data-quality-extension/add-column-rule'))
    .appendTo(frame);
  var body = $('<div class="dialog-body"></div>').appendTo(frame);

  // Column selector
  var columnRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/column-name') + ': ').appendTo(columnRow);
  var columnSelect = $('<select></select>').appendTo(columnRow);
  $('<option value=""></option>').text('-- ' + $.i18n('data-quality-extension/select-column') + ' --').appendTo(columnSelect);
  columns.forEach(function(col) {
    if (!self._formatRules[col.name]) {
      $('<option></option>').val(col.name).text(col.name).appendTo(columnSelect);
    }
  });

  // Check options
  var checksRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/check-items') + ': ').appendTo(checksRow);

  var checksContainer = $('<div class="dialog-checks"></div>').appendTo(body);

  var nonEmptyCheck = $('<label><input type="checkbox" id="rule-non-empty"> ' + $.i18n('data-quality-extension/check-non-empty') + '</label><br>')
    .appendTo(checksContainer);
  var uniqueCheck = $('<label><input type="checkbox" id="rule-unique"> ' + $.i18n('data-quality-extension/check-unique') + '</label><br>')
    .appendTo(checksContainer);

  var regexRow = $('<div></div>').appendTo(checksContainer);
  $('<label><input type="checkbox" id="rule-regex-check"> ' + $.i18n('data-quality-extension/check-regex') + ': </label>').appendTo(regexRow);
  var regexInput = $('<input type="text" id="rule-regex-value" size="30">').appendTo(regexRow);

  var dateRow = $('<div class="dialog-date-section"></div>').appendTo(checksContainer);
  $('<label><input type="checkbox" id="rule-date-check"> ' + $.i18n('data-quality-extension/check-date-format') + ': </label>').appendTo(dateRow);

  // Predefined date formats
  var dateFormats = [
    { value: 'yyyyMMdd', label: 'yyyyMMdd (20231225)' },
    { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd (2023-12-25)' },
    { value: 'yyyy/MM/dd', label: 'yyyy/MM/dd (2023/12/25)' },
    { value: 'yyyy.MM.dd', label: 'yyyy.MM.dd (2023.12.25)' },
    { value: 'yyyy年MM月dd日', label: 'yyyy年MM月dd日 (2023年12月25日)' },
    { value: 'yyyy年M月d日', label: 'yyyy年M月d日 (2023年1月5日)' },
    { value: 'yyyyMM', label: 'yyyyMM (202312)' },
    { value: 'yyyy-MM', label: 'yyyy-MM (2023-12)' },
    { value: 'yyyy', label: 'yyyy (2023)' },
    { value: 'MM/dd/yyyy', label: 'MM/dd/yyyy (12/25/2023)' },
    { value: 'dd/MM/yyyy', label: 'dd/MM/yyyy (25/12/2023)' },
    { value: 'yyyy-MM-dd HH:mm:ss', label: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'yyyyMMddHHmmss', label: 'yyyyMMddHHmmss' },
    { value: 'custom', label: $.i18n('data-quality-extension/date-format-custom') }
  ];

  var dateSelect = $('<select id="rule-date-select"></select>').appendTo(dateRow);
  dateFormats.forEach(function(fmt) {
    $('<option></option>').val(fmt.value).text(fmt.label).appendTo(dateSelect);
  });
  dateSelect.val('yyyyMMdd');  // Default to yyyyMMdd

  // Custom input (hidden by default)
  var dateCustomInput = $('<input type="text" id="rule-date-custom" size="20" class="dialog-date-custom-input">')
    .attr('placeholder', $.i18n('data-quality-extension/enter-custom-format'))
    .appendTo(dateRow);
  dateCustomInput.hide();

  // Toggle custom input visibility
  dateSelect.on('change', function() {
    if ($(this).val() === 'custom') {
      dateCustomInput.show().focus();
    } else {
      dateCustomInput.hide();
    }
  });

  // Footer
  var footer = $('<div class="dialog-footer"></div>').appendTo(frame);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/cancel'))
    .on('click', function() { DialogSystem.dismissUntil(level - 1); })
    .appendTo(footer);
  $('<button class="button button-primary"></button>')
    .text($.i18n('data-quality-extension/confirm'))
    .on('click', function() {
      var colName = columnSelect.val();
      if (!colName) {
        alert($.i18n('data-quality-extension/please-select-column'));
        return;
      }

      // Get date format value
      var dateFormatValue = '';
      if ($('#rule-date-check').prop('checked')) {
        var selectedFormat = dateSelect.val();
        if (selectedFormat === 'custom') {
          dateFormatValue = dateCustomInput.val().trim();
        } else {
          dateFormatValue = selectedFormat;
        }
      }

      self._formatRules[colName] = {
        nonEmpty: $('#rule-non-empty').prop('checked'),
        unique: $('#rule-unique').prop('checked'),
        regex: $('#rule-regex-check').prop('checked') ? regexInput.val() : '',
        dateFormat: dateFormatValue,
        valueList: []
      };
      self._hasUnsavedChanges = true;
      if (self._unsavedIndicator) self._unsavedIndicator.show();
      self._refreshFormatRulesTable();
      DialogSystem.dismissUntil(level - 1);
    })
    .appendTo(footer);

  var level = DialogSystem.showDialog(frame);
};

/**
 * Edit format rule
 */
QualityAlignment._editFormatRule = function(columnName) {
  var self = this;
  var rule = this._formatRules[columnName];
  if (!rule) return;

  var columns = theProject.columnModel.columns;

  var frame = $('<div class="dialog-frame"></div>');
  var header = $('<div class="dialog-header"></div>')
    .text($.i18n('data-quality-extension/edit-column-rule'))
    .appendTo(frame);
  var body = $('<div class="dialog-body"></div>').appendTo(frame);

  // Column selector (allow changing the column)
  var columnRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/column-name') + ': ').appendTo(columnRow);
  var columnSelect = $('<select id="edit-column-select"></select>').appendTo(columnRow);

  // Add current column if not in project columns (unmatched case)
  var projectColumnNames = columns.map(function(col) { return col.name; });
  var isCurrentColumnInProject = projectColumnNames.indexOf(columnName) !== -1;

  if (!isCurrentColumnInProject) {
    $('<option></option>').val(columnName).text(columnName + ' *').appendTo(columnSelect);
  }

  columns.forEach(function(col) {
    $('<option></option>').val(col.name).text(col.name).appendTo(columnSelect);
  });
  columnSelect.val(columnName);

  // Show template info if available
  if (rule._templateName && rule._templateName !== columnName) {
    var templateInfo = $('<div class="dialog-info"></div>').appendTo(body);
    $('<small></small>')
      .text($.i18n('data-quality-extension/template-field') + ': ' + rule._templateName)
      .appendTo(templateInfo);
  }

  var checksContainer = $('<div class="dialog-checks"></div>').appendTo(body);

  $('<label><input type="checkbox" id="rule-non-empty" ' + (rule.nonEmpty ? 'checked' : '') + '> '
    + $.i18n('data-quality-extension/check-non-empty') + '</label><br>').appendTo(checksContainer);
  $('<label><input type="checkbox" id="rule-unique" ' + (rule.unique ? 'checked' : '') + '> '
    + $.i18n('data-quality-extension/check-unique') + '</label><br>').appendTo(checksContainer);

  var regexRow = $('<div></div>').appendTo(checksContainer);
  $('<label><input type="checkbox" id="rule-regex-check" ' + (rule.regex ? 'checked' : '') + '> '
    + $.i18n('data-quality-extension/check-regex') + ': </label>').appendTo(regexRow);
  var regexInput = $('<input type="text" id="rule-regex-value" size="30">').val(rule.regex).appendTo(regexRow);

  var dateRow = $('<div class="dialog-date-section"></div>').appendTo(checksContainer);
  $('<label><input type="checkbox" id="rule-date-check" ' + (rule.dateFormat ? 'checked' : '') + '> '
    + $.i18n('data-quality-extension/check-date-format') + ': </label>').appendTo(dateRow);

  // Predefined date formats
  var dateFormats = [
    { value: 'yyyyMMdd', label: 'yyyyMMdd (20231225)' },
    { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd (2023-12-25)' },
    { value: 'yyyy/MM/dd', label: 'yyyy/MM/dd (2023/12/25)' },
    { value: 'yyyy.MM.dd', label: 'yyyy.MM.dd (2023.12.25)' },
    { value: 'yyyy年MM月dd日', label: 'yyyy年MM月dd日 (2023年12月25日)' },
    { value: 'yyyy年M月d日', label: 'yyyy年M月d日 (2023年1月5日)' },
    { value: 'yyyyMM', label: 'yyyyMM (202312)' },
    { value: 'yyyy-MM', label: 'yyyy-MM (2023-12)' },
    { value: 'yyyy', label: 'yyyy (2023)' },
    { value: 'MM/dd/yyyy', label: 'MM/dd/yyyy (12/25/2023)' },
    { value: 'dd/MM/yyyy', label: 'dd/MM/yyyy (25/12/2023)' },
    { value: 'yyyy-MM-dd HH:mm:ss', label: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'yyyyMMddHHmmss', label: 'yyyyMMddHHmmss' },
    { value: 'custom', label: $.i18n('data-quality-extension/date-format-custom') }
  ];

  var dateSelect = $('<select id="rule-date-select"></select>').appendTo(dateRow);
  dateFormats.forEach(function(fmt) {
    $('<option></option>').val(fmt.value).text(fmt.label).appendTo(dateSelect);
  });

  // Custom input (hidden by default)
  var dateCustomInput = $('<input type="text" id="rule-date-custom" size="20" class="dialog-date-custom-input">')
    .attr('placeholder', $.i18n('data-quality-extension/enter-custom-format'))
    .appendTo(dateRow);
  dateCustomInput.hide();

  // Determine current value
  var currentDateFormat = rule.dateFormat || 'yyyyMMdd';
  var isCustomFormat = true;
  for (var i = 0; i < dateFormats.length - 1; i++) {  // Exclude 'custom' option
    if (dateFormats[i].value === currentDateFormat) {
      isCustomFormat = false;
      break;
    }
  }

  if (isCustomFormat && currentDateFormat) {
    dateSelect.val('custom');
    dateCustomInput.val(currentDateFormat).show();
  } else {
    dateSelect.val(currentDateFormat || 'yyyyMMdd');
  }

  // Toggle custom input visibility
  dateSelect.on('change', function() {
    if ($(this).val() === 'custom') {
      dateCustomInput.show().focus();
    } else {
      dateCustomInput.hide();
    }
  });

  // Value list section
  var valueListRow = $('<div class="dialog-value-list-section"></div>').appendTo(checksContainer);
  var valueListHeader = $('<div class="dialog-value-list-header"></div>').appendTo(valueListRow);
  var hasValueList = rule.valueList && rule.valueList.length > 0;
  $('<label><input type="checkbox" id="rule-value-list-check" ' + (hasValueList ? 'checked' : '') + '> '
    + $.i18n('data-quality-extension/check-value-list') + '</label>').appendTo(valueListHeader);

  var valueListContainer = $('<div class="dialog-value-list-container"></div>').appendTo(valueListRow);
  if (!hasValueList) valueListContainer.hide();

  $('<div class="dialog-value-list-hint"></div>')
    .text($.i18n('data-quality-extension/value-list-hint'))
    .appendTo(valueListContainer);

  var valueListTextarea = $('<textarea id="rule-value-list-values" rows="4" cols="40"></textarea>')
    .val((rule.valueList || []).join('\n'))
    .appendTo(valueListContainer);

  // Toggle value list visibility
  $('#rule-value-list-check', checksContainer).on('change', function() {
    if ($(this).prop('checked')) {
      valueListContainer.show();
    } else {
      valueListContainer.hide();
    }
  });

  var footer = $('<div class="dialog-footer"></div>').appendTo(frame);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/cancel'))
    .on('click', function() { DialogSystem.dismissUntil(level - 1); })
    .appendTo(footer);
  $('<button class="button button-primary"></button>')
    .text($.i18n('data-quality-extension/confirm'))
    .on('click', function() {
      var newColumnName = columnSelect.val();

      // If column name changed, delete old rule and create new
      if (newColumnName !== columnName) {
        delete self._formatRules[columnName];
      }

      // Parse value list from textarea
      var valueListEnabled = $('#rule-value-list-check').prop('checked');
      var valueList = [];
      if (valueListEnabled) {
        var text = valueListTextarea.val().trim();
        if (text) {
          valueList = text.split('\n').map(function(v) { return v.trim(); }).filter(function(v) { return v.length > 0; });
        }
      }

      // Get date format value
      var dateFormatValue = '';
      if ($('#rule-date-check').prop('checked')) {
        var selectedFormat = dateSelect.val();
        if (selectedFormat === 'custom') {
          dateFormatValue = dateCustomInput.val().trim();
        } else {
          dateFormatValue = selectedFormat;
        }
      }

      self._formatRules[newColumnName] = {
        nonEmpty: $('#rule-non-empty').prop('checked'),
        unique: $('#rule-unique').prop('checked'),
        regex: $('#rule-regex-check').prop('checked') ? regexInput.val() : '',
        dateFormat: dateFormatValue,
        valueList: valueList,
        _templateName: rule._templateName  // Preserve template info
      };
      self._hasUnsavedChanges = true;
      if (self._unsavedIndicator) self._unsavedIndicator.show();
      self._refreshFormatRulesTable();
      DialogSystem.dismissUntil(level - 1);
    })
    .appendTo(footer);

  var level = DialogSystem.showDialog(frame);
};

/**
 * Delete format rule
 */
QualityAlignment._deleteFormatRule = function(columnName) {
  if (confirm($.i18n('data-quality-extension/confirm-delete-rule') + ': ' + columnName + '?')) {
    delete this._formatRules[columnName];
    this._hasUnsavedChanges = true;
    if (this._unsavedIndicator) this._unsavedIndicator.show();
    this._refreshFormatRulesTable();
  }
};

/**
 * Add content rule
 */
QualityAlignment._addContentRule = function() {
  var self = this;
  var columns = theProject.columnModel.columns;

  var frame = $('<div class="dialog-frame"></div>');
  $('<div class="dialog-header"></div>')
    .text($.i18n('data-quality-extension/add-comparison-rule'))
    .appendTo(frame);
  var body = $('<div class="dialog-body"></div>').appendTo(frame);

  // Column selector
  var columnRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/data-column') + ': ').appendTo(columnRow);
  var columnSelect = $('<select></select>').appendTo(columnRow);
  $('<option value=""></option>').text('-- ' + $.i18n('data-quality-extension/select-column') + ' --').appendTo(columnSelect);
  columns.forEach(function(col) {
    $('<option></option>').val(col.name).text(col.name).appendTo(columnSelect);
  });

  // Extract label
  var labelRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/extract-label') + ': ').appendTo(labelRow);
  var labelInput = $('<input type="text" size="20">').appendTo(labelRow);

  // Threshold
  var thresholdRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/similarity-threshold') + ' (%): ').appendTo(thresholdRow);
  var thresholdInput = $('<input type="number" min="0" max="100" value="90">').appendTo(thresholdRow);

  var footer = $('<div class="dialog-footer"></div>').appendTo(frame);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/cancel'))
    .on('click', function() { DialogSystem.dismissUntil(level - 1); })
    .appendTo(footer);
  $('<button class="button button-primary"></button>')
    .text($.i18n('data-quality-extension/confirm'))
    .on('click', function() {
      var colName = columnSelect.val();
      var label = labelInput.val();
      if (!colName || !label) {
        alert($.i18n('data-quality-extension/please-fill-all-fields'));
        return;
      }
      self._contentRules.push({
        column: colName,
        extractLabel: label,
        threshold: parseInt(thresholdInput.val()) || 90,
        matched: true
      });
      self._hasUnsavedChanges = true;
      if (self._unsavedIndicator) self._unsavedIndicator.show();
      self._refreshContentRulesTable();
      DialogSystem.dismissUntil(level - 1);
    })
    .appendTo(footer);

  var level = DialogSystem.showDialog(frame);
};

/**
 * Edit content rule
 */
QualityAlignment._editContentRule = function(index) {
  var self = this;
  var rule = this._contentRules[index];
  if (!rule) return;

  var columns = theProject.columnModel.columns;

  var frame = $('<div class="dialog-frame"></div>');
  $('<div class="dialog-header"></div>')
    .text($.i18n('data-quality-extension/edit-comparison-rule'))
    .appendTo(frame);
  var body = $('<div class="dialog-body"></div>').appendTo(frame);

  // Show template field info if from template
  if (rule.templateField) {
    $('<div class="dialog-info"></div>')
      .text($.i18n('data-quality-extension/template-field') + ': ' + rule.templateField)
      .appendTo(body);
  }

  var columnRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/data-column') + ': ').appendTo(columnRow);
  var columnSelect = $('<select></select>').appendTo(columnRow);

  // Add current value if unmatched
  var currentInList = false;
  columns.forEach(function(col) {
    if (col.name === rule.column) currentInList = true;
    $('<option></option>').val(col.name).text(col.name).appendTo(columnSelect);
  });
  if (!currentInList && rule.column) {
    $('<option></option>').val(rule.column).text(rule.column + ' *').prependTo(columnSelect);
  }
  columnSelect.val(rule.column);

  var labelRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/extract-label') + ': ').appendTo(labelRow);
  var labelInput = $('<input type="text" size="20">').val(rule.extractLabel).appendTo(labelRow);

  var thresholdRow = $('<div class="dialog-row"></div>').appendTo(body);
  $('<label></label>').text($.i18n('data-quality-extension/similarity-threshold') + ' (%): ').appendTo(thresholdRow);
  var thresholdInput = $('<input type="number" min="0" max="100">').val(rule.threshold).appendTo(thresholdRow);

  var footer = $('<div class="dialog-footer"></div>').appendTo(frame);
  $('<button class="button"></button>')
    .text($.i18n('data-quality-extension/cancel'))
    .on('click', function() { DialogSystem.dismissUntil(level - 1); })
    .appendTo(footer);
  $('<button class="button button-primary"></button>')
    .text($.i18n('data-quality-extension/confirm'))
    .on('click', function() {
      var selectedColumn = columnSelect.val();
      // Check if column exists in project
      var columnExists = columns.some(function(c) { return c.name === selectedColumn; });
      self._contentRules[index] = {
        column: selectedColumn,
        extractLabel: labelInput.val(),
        threshold: parseInt(thresholdInput.val()) || 90,
        templateField: rule.templateField,
        matched: columnExists
      };
      self._hasUnsavedChanges = true;
      if (self._unsavedIndicator) self._unsavedIndicator.show();
      self._refreshContentRulesTable();
      DialogSystem.dismissUntil(level - 1);
    })
    .appendTo(footer);

  var level = DialogSystem.showDialog(frame);
};

/**
 * Delete content rule
 */
QualityAlignment._deleteContentRule = function(index) {
  if (confirm($.i18n('data-quality-extension/confirm-delete-rule') + '?')) {
    this._contentRules.splice(index, 1);
    this._hasUnsavedChanges = true;
    if (this._unsavedIndicator) this._unsavedIndicator.show();
    this._refreshContentRulesTable();
  }
};

/**
 * Save rules
 * @param {Function} callback - Optional callback after save completes
 * @param {boolean} silent - If true, don't show alert on success
 */
QualityAlignment._saveRules = function(callback, silent) {
  var self = this;
  var rules = {
    formatRules: this._formatRules,
    resourceConfig: this._resourceConfig,
    contentRules: this._contentRules,
    aimpConfig: this._aimpConfig
  };

  console.log('Saving rules:', rules);

  Refine.postCSRF(
    "command/data-quality/save-quality-rules",
    {
      project: theProject.id,
      rules: JSON.stringify(rules)
    },
    function(response) {
      if (response.code === "ok") {
        self._hasUnsavedChanges = false;
        if (self._unsavedIndicator) self._unsavedIndicator.hide();
        if (!silent) {
          alert($.i18n('data-quality-extension/rules-saved'));
        }
        if (typeof callback === 'function') {
          callback(true);
        }
      } else {
        alert($.i18n('data-quality-extension/save-error') + ': ' + (response.message || 'Unknown error'));
        if (typeof callback === 'function') {
          callback(false);
        }
      }
    },
    "json"
  );
};

/**
 * Load rules from project
 */
QualityAlignment._loadRules = function() {
  var self = this;

  $.ajax({
    url: "command/data-quality/get-quality-rules",
    type: "GET",
    data: { project: theProject.id },
    dataType: "json",
    success: function(response) {
      if (response.code === "ok" && response.rules) {
        self._formatRules = response.rules.formatRules || {};
        self._resourceConfig = response.rules.resourceConfig || self._getDefaultResourceConfig();
        self._contentRules = response.rules.contentRules || [];
        self._aimpConfig = response.rules.aimpConfig || { serviceUrl: '' };

        // Re-render if already set up
        if (self._isSetUp) {
          self._refreshFormatRulesTable();
          self._renderResourceCheckTab();
          self._refreshContentRulesTable();
        }

        // If AIMP service URL is configured, test connection to update status
        if (self._aimpConfig.serviceUrl) {
          self._testAndUpdateAimpConnection(self._aimpConfig.serviceUrl);
        }

        self._hasUnsavedChanges = false;
        if (self._unsavedIndicator) self._unsavedIndicator.hide();
      }
    },
    error: function(xhr, status, error) {
      console.error("Error loading quality rules:", error);
    }
  });
};

/**
 * Test AIMP connection and update status
 */
QualityAlignment._testAndUpdateAimpConnection = function(serviceUrl) {
  var self = this;

  Refine.postCSRF(
    "command/data-quality/test-aimp-connection",
    { serviceUrl: serviceUrl },
    function(response) {
      if (response.connected) {
        self._aimpConnected = true;
        // Update UI if service status element exists
        if (self._serviceStatus) {
          self._serviceStatus
            .removeClass('disconnected')
            .addClass('connected')
            .text('● ' + $.i18n('data-quality-extension/connected'));
        }
      } else {
        self._aimpConnected = false;
        if (self._serviceStatus) {
          self._serviceStatus
            .removeClass('connected')
            .addClass('disconnected')
            .text('○ ' + $.i18n('data-quality-extension/not-connected'))
            .css('cursor', 'pointer')
            .off('click').on('click', function() {
              self._showAimpConfigDialog();
            });
        }
      }
    },
    "json"
  );
};

/**
 * Get default resource config
 */
QualityAlignment._getDefaultResourceConfig = function() {
  return {
    basePath: '',
    pathFields: [],
    pathMode: 'separator',
    separator: '',
    template: '',
    folderChecks: { existence: true, dataExistence: true, nameFormat: '', sequential: true },
    fileChecks: { countMatch: true, countColumn: '', nameFormat: '', sequential: true }
  };
};

/**
 * Build export data with summary from results
 * Ensures summary is always present even when loading from overlay
 */
QualityAlignment._buildExportData = function(result) {
  if (!result) return null;

  // If summary already exists, return as is
  if (result.summary) {
    return result;
  }

  // Build summary from errors
  var errors = result.errors || [];
  var formatErrors = 0;
  var resourceErrors = 0;
  var contentErrors = 0;

  errors.forEach(function(err) {
    if (err.category === 'format') {
      formatErrors++;
    } else if (err.category === 'resource') {
      resourceErrors++;
    } else if (err.category === 'content') {
      contentErrors++;
    }
  });

  return {
    summary: {
      totalRows: result.totalRows || theProject.rowModel.total || 0,
      totalErrors: errors.length,
      formatErrors: formatErrors,
      resourceErrors: resourceErrors,
      contentErrors: contentErrors
    },
    errors: errors
  };
};

/**
 * Export to Excel
 */
QualityAlignment._exportExcel = function() {
  if (!this._lastCheckResult) {
    alert($.i18n('data-quality-extension/no-results'));
    return;
  }

  var exportData = this._buildExportData(this._lastCheckResult);

  // Use wrapCSRF to get a fresh token
  Refine.wrapCSRF(function(csrfToken) {
    // Create a form to submit the export request
    var form = $('<form method="POST" target="_blank"></form>')
      .attr('action', 'command/data-quality/export-quality-report?' + $.param({project: theProject.id, csrf_token: csrfToken}))
      .appendTo('body');

    $('<input type="hidden" name="format">').val('excel').appendTo(form);
    $('<input type="hidden" name="results">').val(JSON.stringify(exportData)).appendTo(form);

    form.submit();
    form.remove();
  });
};

/**
 * Export to PDF
 */
QualityAlignment._exportPdf = function() {
  if (!this._lastCheckResult) {
    alert($.i18n('data-quality-extension/no-results'));
    return;
  }

  var exportData = this._buildExportData(this._lastCheckResult);

  // Use wrapCSRF to get a fresh token
  Refine.wrapCSRF(function(csrfToken) {
    // Create a form to submit the export request
    var form = $('<form method="POST" target="_blank"></form>')
      .attr('action', 'command/data-quality/export-quality-report?' + $.param({project: theProject.id, csrf_token: csrfToken}))
      .appendTo('body');

    $('<input type="hidden" name="format">').val('pdf').appendTo(form);
    $('<input type="hidden" name="results">').val(JSON.stringify(exportData)).appendTo(form);

    form.submit();
    form.remove();
  });
};

/**
 * Load results page
 */
QualityAlignment._loadResultsPage = function(page) {
  this._currentPage = page;
  this._renderErrorsTable();
};

/**
 * Highlight a specific row in the data table
 * @param {number} rowIndex - The 0-based row index to highlight
 */
QualityAlignment._highlightRow = function(rowIndex) {
  console.log('[QualityAlignment] _highlightRow called with rowIndex:', rowIndex);

  // Remove any existing highlights
  $('.quality-highlight-row').removeClass('quality-highlight-row');

  // Use theProject.rowModel.rows to find the row position in current view
  var rows = theProject.rowModel.rows;
  console.log('[QualityAlignment] rows in view:', rows.length);

  for (var r = 0; r < rows.length; r++) {
    console.log('[QualityAlignment] checking row', r, 'with i=', rows[r].i);
    if (rows[r].i === rowIndex) {
      // Found the row in current view, highlight it
      var dataTable = $('.data-table');
      console.log('[QualityAlignment] dataTable found:', dataTable.length);

      var tableRows = dataTable.find('tr');
      console.log('[QualityAlignment] tableRows found:', tableRows.length);

      // Skip header row, so use r+1
      if (tableRows.length > r + 1) {
        var targetRow = $(tableRows[r + 1]);
        console.log('[QualityAlignment] targetRow:', targetRow);
        targetRow.addClass('quality-highlight-row');

        // Apply styles to all cells in the row for stronger effect
        targetRow.find('td').each(function() {
          $(this).attr('style', 'background-color: #ffc107 !important; transition: background-color 0.3s;');
        });

        // Scroll into view if needed
        tableRows[r + 1].scrollIntoView({ behavior: 'smooth', block: 'center' });

        // Remove highlight after 5 seconds
        setTimeout(function() {
          $('.quality-highlight-row').find('td').each(function() {
            $(this).attr('style', '');
          });
          $('.quality-highlight-row').removeClass('quality-highlight-row');
        }, 5000);
      }
      break;
    }
  }
};

