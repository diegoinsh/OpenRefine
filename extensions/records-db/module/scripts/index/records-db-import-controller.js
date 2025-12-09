/*
 * Records Database Import Controller
 *
 * Registers the Records Database importer with OpenRefine
 */

(function() {
  'use strict';

  // Initialize i18n for this module
  if (typeof I18NUtil !== 'undefined') {
    I18NUtil.init("records-db");
  }

  // Safe translation helper and fallback i18n shim
  function tr(key, fallback) {
    try {
      if (typeof $ !== 'undefined' && $.i18n) {
        var v = $.i18n(key);
        if (v && typeof v === 'string' && v.length) return v;
      }
      if (typeof window !== 'undefined' && window.i18n && typeof window.i18n.t === 'function') {
        var v2 = window.i18n.t(key);
        if (v2 && typeof v2 === 'string' && v2.length) return v2;
      }
    } catch (e) {}
    return (typeof fallback === 'string' && fallback.length) ? fallback : key;
  }
  var i18n = { t: function(k){ return tr(k, k); } };


  // Ensure a global i18n shim for other module files (wizard steps rely on global `i18n`)
  if (typeof window !== 'undefined' && !window.i18n) {
    window.i18n = {
      t: function(key) {
        try {
          if (typeof $ !== 'undefined' && $.i18n) {
            var v = $.i18n(key);
            if (v && typeof v === 'string' && v.length) return v;
          }
        } catch (e) {}
        return key;
      }
    };
  }

  // ========== History Configuration Management ==========
  var HISTORY_STORAGE_KEY = 'records-db-history-configs';

  /**
   * Get all saved history configurations
   * @returns {Array} Array of saved configurations
   */
  function getHistoryConfigs() {
    try {
      var stored = localStorage.getItem(HISTORY_STORAGE_KEY);
      if (stored) {
        return JSON.parse(stored);
      }
    } catch (e) {
      console && console.warn && console.warn('[records-db] Failed to load history configs:', e);
    }
    return [];
  }

  /**
   * Save a configuration to history
   * @param {string} name - Project name as the key
   * @param {object} profile - Schema profile to save
   * @param {string} mode - Wizard mode (catalog/sql)
   */
  function saveHistoryConfig(name, profile, mode) {
    if (!name || !name.trim()) return;
    try {
      var configs = getHistoryConfigs();
      // Remove existing config with same name
      configs = configs.filter(function(c) { return c.name !== name; });
      // Add new config at the beginning
      configs.unshift({
        name: name,
        mode: mode || 'catalog',
        profile: profile,
        savedAt: new Date().toISOString()
      });
      // Keep only last 20 configurations
      if (configs.length > 20) {
        configs = configs.slice(0, 20);
      }
      localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(configs));
    } catch (e) {
      console && console.warn && console.warn('[records-db] Failed to save history config:', e);
    }
  }

  /**
   * Remove a configuration from history by name
   * @param {string} name - Project name to remove
   */
  function removeHistoryConfig(name) {
    if (!name) return;
    try {
      var configs = getHistoryConfigs();
      configs = configs.filter(function(c) { return c.name !== name; });
      localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(configs));
    } catch (e) {
      console && console.warn && console.warn('[records-db] Failed to remove history config:', e);
    }
  }

  /**
   * Get a specific configuration by name
   * @param {string} name - Project name
   * @returns {object|null} Configuration object or null
   */
  function getHistoryConfigByName(name) {
    var configs = getHistoryConfigs();
    for (var i = 0; i < configs.length; i++) {
      if (configs[i].name === name) {
        return configs[i];
      }
    }
    return null;
  }


  // Register controller into Create Project UI (like the built-in database importer)
  Refine.RecordsDBImportController = function(createProjectUI) {
    this._createProjectUI = createProjectUI;
    this._parsingPanel = createProjectUI.addCustomPanel();

    createProjectUI.addSourceSelectionUI({
      label: tr('records.db.label', 'Records Database'),
      id: 'records-db-source',
      ui: new RecordsDBSourceUI(this),
      // JINSHU: place Records Database right after "This Computer" in the
      // Create Project source list (0-based index: 0 = first, 1 = second).
      position: 1
    });
  };

  Refine.CreateProjectUI.controllers.push(Refine.RecordsDBImportController);

  // Optional: stub to satisfy CreateProjectUI expectations; wizard handles flow
  Refine.RecordsDBImportController.prototype.startImportingDocument = function(queryInfo) {
    // The wizard triggers importing commands itself; this is a no-op fall-back
  };

  /**
   * Records Database Source UI
   */
  var RecordsDBSourceUI = function(controller) {
    this._controller = controller;
  };

  RecordsDBSourceUI.prototype.attachUI = function(bodyDiv, onDone) {
    var self = this;

    // Create the main container
    var html = '<div id="records-db-wizard" class="records-db-wizard">';
    html += '<div id="records-db-steps"></div>';
    html += '<div id="records-db-content"></div>';
    html += '<div id="records-db-buttons">';
    var prevLabel = tr('core-buttons/startover', 'Previous');
    var nextLabel = tr('core-buttons/next', 'Next');
    var finishLabel = tr('core-buttons/create-project', 'Finish');
    html += '<button id="records-db-prev" class="button">' + prevLabel + '</button>';
    html += '<button id="records-db-next" class="button">' + nextLabel + '</button>';
    html += '</div>';
    html += '</div>';

    // bodyDiv is a jQuery element in OpenRefine; support both jQuery and raw DOM
    var containerEl = null;
    if (typeof $ !== 'undefined') {
      var $body = $(bodyDiv);
      if ($body && $body.length) {
        $body.html(html);
        containerEl = $body[0];
      }
    }
    if (!containerEl && bodyDiv && bodyDiv.nodeType === 1) {
      bodyDiv.innerHTML = html;
      containerEl = bodyDiv;
    }

    // Initialize the wizard
    this._wizard = new RecordsDBWizard(this._controller, onDone);
    this._wizard.init(containerEl || bodyDiv);
  };

  // Required by OpenRefine's CreateProjectUI - called when this source is selected
  RecordsDBSourceUI.prototype.focus = function() {
    // No-op: wizard handles its own focus
  };

  /**
   * Records Database Wizard
   */
  var RecordsDBWizard = function(controller, onDone) {
    this._controller = controller;
    this._onDone = onDone;
    this._currentStep = 0;
    this._schemaProfile = {};
    this._mode = 'catalog'; // Default mode
    this._steps = [
      new SelectModeStep(this),
      new SelectProfileStep(this),
      new SelectFieldsStep(this),
      new ConfigureFiltersStep(this),
      new FieldMappingStep(this),
      new FileMappingStep(this),
      new PreviewStep(this),
      new CreateProjectStep(this)
    ];
  };

  RecordsDBWizard.prototype.init = function(bodyDiv) {
    this._bodyDiv = bodyDiv;
    this._showStep(0);
  };

  RecordsDBWizard.prototype._showStep = function(stepIndex) {
    if (stepIndex < 0 || stepIndex >= this._steps.length) {
      return;
    }

    this._currentStep = stepIndex;
    var step = this._steps[stepIndex];

    // Resolve scoped DOM nodes within the wizard container
    var root = this._bodyDiv || document;
    var stepsDiv = (root.querySelector ? root.querySelector('#records-db-steps') : document.getElementById('records-db-steps'));
    var contentDiv = (root.querySelector ? root.querySelector('#records-db-content') : document.getElementById('records-db-content'));
    var prevBtn = (root.querySelector ? root.querySelector('#records-db-prev') : document.getElementById('records-db-prev'));
    var nextBtn = (root.querySelector ? root.querySelector('#records-db-next') : document.getElementById('records-db-next'));

    if (!stepsDiv || !contentDiv || !prevBtn || !nextBtn) {
      if (typeof console !== 'undefined') {
        console.warn('[records-db] Wizard container elements not found');
      }
      return;
    }

    // Update step indicator
    stepsDiv.innerHTML = '<p>Step ' + (stepIndex + 1) + ' of ' + this._steps.length + '</p>';

    // Show step content
    contentDiv.innerHTML = '';
    step.render(contentDiv);

    // Update buttons
    prevBtn.disabled = (stepIndex === 0);
    nextBtn.disabled = false;

    var nextText = tr('core-buttons/next', 'Next');
    var finishText = tr('core-buttons/create-project', 'Finish');
    nextBtn.innerHTML = (stepIndex === this._steps.length - 1) ? finishText : nextText;

    // Attach event handlers
    var self = this;
    prevBtn.onclick = function() {
      var curr = self._steps[self._currentStep];
      if (curr && typeof curr.applyToProfile === 'function') {
        try { curr.applyToProfile(); } catch (e) { console && console.warn && console.warn('[records-db] applyToProfile error:', e); }
      }
      self._showStep(self._currentStep - 1);
    };
    nextBtn.onclick = function() {
      if (self._currentStep === self._steps.length - 1) {
        self._createProject();
      } else {
        // Optional: validate current step if it exposes a validate method
        var curr = self._steps[self._currentStep];
        if (curr && typeof curr.validateFilters === 'function') {
          var validation = curr.validateFilters();
          if (!validation.valid) {
            alert(validation.message);
            return;
          }
          if (typeof curr.getFilters === 'function') {
            self._filters = curr.getFilters();
          }
        }
        if (curr && typeof curr.applyToProfile === 'function') {
          try { curr.applyToProfile(); } catch (e) { console && console.warn && console.warn('[records-db] applyToProfile error:', e); }
        }
        self._showStep(self._currentStep + 1);
      }
    };
  };

  RecordsDBWizard.prototype._createProject = function() {
    var self = this;
    var createProjectStep = this._steps[this._steps.length - 1];
    var projectName = createProjectStep.getProjectName();
    var maxRows = createProjectStep.getMaxRows();
    var shouldRedirect = (typeof createProjectStep.shouldRedirect === 'function')
      ? createProjectStep.shouldRedirect()
      : true;

    // Save configuration to history if enabled
    if (typeof createProjectStep.saveConfigToHistory === 'function') {
      createProjectStep.saveConfigToHistory();
    }

    var statusDiv = document.getElementById('project-status');
    if (!statusDiv) {
      return;
    }
    statusDiv.innerHTML = '<p>' + i18n.t('records.db.wizard.createProject.creating') + '...</p>';

    function handleSuccess(data) {
      if (data && data.status === 'ok') {
        statusDiv.innerHTML = '<p style="color: green;">' + i18n.t('records.db.wizard.createProject.success') + '</p>';
        var projectId = data.projectId || data.projectID;
        if (shouldRedirect && projectId) {
          setTimeout(function() {
            window.location.href = 'project?project=' + projectId;
          }, 1000);
        } else if (typeof self._onDone === 'function') {
          setTimeout(function() { self._onDone(); }, 1000);
        }
      } else {
        var msg = (data && data.message) ? data.message : 'Unknown error';
        statusDiv.innerHTML = '<p style="color: red;">Error: ' + msg + '</p>';
      }
    }

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "create-project",
            "csrf_token": token
          }),
          {
            "projectName": projectName,
            "schemaProfile": JSON.stringify(self._schemaProfile),
            "maxRows": maxRows
          },
          function(data) {
            handleSuccess(data);
          },
          "json"
        ).fail(function(xhr) {
          statusDiv.innerHTML = '<p style="color: red;">Error: HTTP ' + xhr.status + '</p>';
        });
      });
    } else {
      var params = new URLSearchParams();
      params.set('projectName', projectName);
      params.set('schemaProfile', JSON.stringify(self._schemaProfile));
      params.set('maxRows', String(maxRows));
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=create-project', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      .then(function(response) { return response.json(); })
      .then(function(data) {
        handleSuccess(data);
      })
      .catch(function(error) {
        statusDiv.innerHTML = '<p style="color: red;">Error: ' + error.message + '</p>';
      });
    }
  };

  /**
   * Step 1: Select Mode (Catalog or SQL)
   */
  var SelectModeStep = function(wizard) {
    this._wizard = wizard;
  };

  SelectModeStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.selectMode.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.selectMode.description') + '</p>';

    html += '<div class="mode-options">';
    html += '<div class="mode-option">';
    html += '<label><input type="radio" name="mode" value="catalog" checked> ';
    html += '<strong>' + i18n.t('records.db.wizard.selectMode.catalogMode') + '</strong></label>';
    html += '<p>' + i18n.t('records.db.wizard.selectMode.catalogModeDesc') + '</p>';
    html += '</div>';

    html += '<div class="mode-option">';
    html += '<label><input type="radio" name="mode" value="sql"> ';
    html += '<strong>' + i18n.t('records.db.wizard.selectMode.sqlMode') + '</strong></label>';
    html += '<p>' + i18n.t('records.db.wizard.selectMode.sqlModeDesc') + '</p>';
    html += '</div>';
    html += '</div>';

    // History configuration - custom dropdown with delete on hover
    var historyConfigs = getHistoryConfigs();
    if (historyConfigs.length > 0) {
      html += '<style>';
      html += '.history-dropdown { position: relative; display: inline-block; width: 50%; min-width: 280px; }';
      html += '.history-dropdown-btn { width: 100%; padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px; background: #fff; text-align: left; cursor: pointer; }';
      html += '.history-dropdown-btn:after { content: "▼"; float: right; font-size: 10px; color: #666; }';
      html += '.history-dropdown-list { display: none; position: absolute; top: 100%; left: 0; right: 0; background: #fff; border: 1px solid #ccc; border-top: none; border-radius: 0 0 4px 4px; max-height: 200px; overflow-y: auto; z-index: 100; }';
      html += '.history-dropdown.open .history-dropdown-list { display: block; }';
      html += '.history-dropdown-item { padding: 8px 10px; cursor: pointer; display: flex; justify-content: space-between; align-items: center; }';
      html += '.history-dropdown-item:hover { background: #f0f0f0; }';
      html += '.history-dropdown-item .delete-link { color: #c00; text-decoration: underline; display: none; font-size: 12px; }';
      html += '.history-dropdown-item:hover .delete-link { display: inline; }';
      html += '</style>';
      html += '<div class="form-group history-config-group" style="margin-top: 20px; padding: 10px; background: #f5f5f5; border-radius: 4px;">';
      html += '<label style="font-weight: bold;">' + i18n.t('records.db.wizard.selectMode.historyConfig') + '</label> ';
      html += '<div class="history-dropdown" id="history-dropdown">';
      html += '<button type="button" class="history-dropdown-btn" id="history-dropdown-btn">' + i18n.t('records.db.wizard.selectMode.selectHistory') + '</button>';
      html += '<div class="history-dropdown-list" id="history-dropdown-list">';
      historyConfigs.forEach(function(config) {
        var savedDate = config.savedAt ? new Date(config.savedAt).toLocaleString() : '';
        var escapedName = config.name.replace(/"/g, '&quot;');
        html += '<div class="history-dropdown-item" data-name="' + escapedName + '">';
        html += '<span class="item-text">' + config.name + ' (' + savedDate + ')</span>';
        html += '<span class="delete-link" data-name="' + escapedName + '">' + i18n.t('records.db.wizard.selectMode.deleteHistory') + '</span>';
        html += '</div>';
      });
      html += '</div>';
      html += '</div>';
      html += '</div>';
    }

    html += '</div>';
    div.innerHTML = html;

    // Attach event handlers
    var modeRadios = document.querySelectorAll('input[name="mode"]');
    modeRadios.forEach(function(radio) {
      radio.onchange = function() {
        self._wizard._mode = this.value;
      };
    });

    // Custom dropdown handlers
    var dropdown = document.getElementById('history-dropdown');
    var dropdownBtn = document.getElementById('history-dropdown-btn');
    var dropdownList = document.getElementById('history-dropdown-list');
    if (dropdown && dropdownBtn && dropdownList) {
      // Toggle dropdown
      dropdownBtn.onclick = function(e) {
        e.stopPropagation();
        dropdown.classList.toggle('open');
      };
      // Close dropdown when clicking outside
      document.addEventListener('click', function(e) {
        if (!dropdown.contains(e.target)) {
          dropdown.classList.remove('open');
        }
      });
      // Item click handler
      var items = dropdownList.querySelectorAll('.history-dropdown-item');
      items.forEach(function(item) {
        item.onclick = function(e) {
          // Check if delete link was clicked
          if (e.target.classList.contains('delete-link')) {
            e.stopPropagation();
            var nameToDelete = e.target.getAttribute('data-name');
            if (nameToDelete && confirm(i18n.t('records.db.wizard.selectMode.confirmDelete') + ' "' + nameToDelete + '"?')) {
              removeHistoryConfig(nameToDelete);
              self.render(div);
            }
            return;
          }
          // Select this config
          var configName = item.getAttribute('data-name');
          var config = getHistoryConfigByName(configName);
          if (config) {
            // Update button text (arrow is added by CSS :after)
            dropdownBtn.textContent = configName;
            dropdown.classList.remove('open');
            // Restore mode
            self._wizard._mode = config.mode || 'catalog';
            var modeRadio = document.querySelector('input[name="mode"][value="' + self._wizard._mode + '"]');
            if (modeRadio) modeRadio.checked = true;
            // Restore profile
            self._wizard._schemaProfile = JSON.parse(JSON.stringify(config.profile || {}));
            // Store the loaded project name for Step 8
            self._wizard._loadedProjectName = configName;
          }
        };
      });
    }
  };

  /**
   * Step 2: Select Profile
   */
  var SelectProfileStep = function(wizard) {
    this._wizard = wizard;
  };

  SelectProfileStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.selectProfile.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.selectProfile.description') + '</p>';

    html += '<div class="database-config">';
    html += '<h4>' + i18n.t('records.db.wizard.selectProfile.databaseConfig') + '</h4>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.selectProfile.dialect') + ':</label>';
    html += '<select id="dialect">';
    html += '<option value="mysql">MySQL</option>';
    html += '<option value="postgresql">PostgreSQL</option>';
    html += '<option value="mariadb">MariaDB</option>';
    html += '<option value="sqlite">SQLite</option>';
    html += '</select>';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.selectProfile.host') + ':</label>';
    html += '<input type="text" id="host" placeholder="localhost">';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.selectProfile.port') + ':</label>';
    html += '<input type="number" id="port" placeholder="3306">';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.selectProfile.database') + ':</label>';
    html += '<input type="text" id="database" placeholder="database_name">';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.selectProfile.username') + ':</label>';
    html += '<input type="text" id="username" placeholder="username">';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.selectProfile.password') + ':</label>';
    html += '<input type="password" id="password" placeholder="password">';
    html += '</div>';
    html += '<button id="test-connection">' + i18n.t('records.db.wizard.selectProfile.testConnection') + '</button>';
    html += '<div id="connection-status"></div>';
    html += '</div>';

    html += '</div>';
    div.innerHTML = html;

    // Prefill from saved profile
    var p = this._wizard._schemaProfile || {};
    var setVal = function(id, val) { var el = document.getElementById(id); if (el && val != null && val !== '') el.value = String(val); };
    setVal('dialect', p.dialect);
    setVal('host', p.host);
    if (typeof p.port !== 'undefined') setVal('port', p.port);
    setVal('database', p.database);
    setVal('username', p.username);
    setVal('password', p.password);

    // Attach event handlers
    document.getElementById('test-connection').onclick = function() {
      self._testConnection();
    };
  };

  SelectProfileStep.prototype._testConnection = function() {
    var statusDiv = document.getElementById('connection-status');
    statusDiv.innerHTML = '<p>' + i18n.t('records.db.wizard.selectProfile.testing') + '...</p>';

    var profile = {
      dialect: document.getElementById('dialect').value,
      host: document.getElementById('host').value,
      port: parseInt(document.getElementById('port').value),
      database: document.getElementById('database').value,
      username: document.getElementById('username').value,
      password: document.getElementById('password').value
    };

    // Store profile in wizard
    this._wizard._schemaProfile = profile;

    // Test connection by calling parse-preview via ImportingController
    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "test-connection",
            "csrf_token": token
          }),
          {
            "schemaProfile": JSON.stringify(profile)
          },
          function(data) {
            if (data && data.status === 'ok') {
              statusDiv.innerHTML = '<p style="color: green;">' + i18n.t('records.db.wizard.selectProfile.connectionSuccess') + '</p>';
            } else {
              var msg = (data && data.message) ? data.message : 'Unknown error';
              statusDiv.innerHTML = '<p style="color: red;">Error: ' + msg + '</p>';
            }
          },
          "json"
        ).fail(function(xhr) {
          var msg = 'HTTP ' + xhr.status;
          statusDiv.innerHTML = '<p style="color: red;">Error: ' + msg + '</p>';
        });
      });
    } else {
      var params = new URLSearchParams();
      params.set('schemaProfile', JSON.stringify(profile));
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=test-connection', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      .then(function(response) { return response.json(); })
      .then(function(data) {
        if (data && data.status === 'ok') {
          statusDiv.innerHTML = '<p style="color: green;">' + i18n.t('records.db.wizard.selectProfile.connectionSuccess') + '</p>';
        } else {
          var msg = (data && data.message) ? data.message : 'Unknown error';
          statusDiv.innerHTML = '<p style="color: red;">Error: ' + msg + '</p>';
        }
      })
      .catch(function(error) {
        statusDiv.innerHTML = '<p style="color: red;">Error: ' + error.message + '</p>';
      });
    }
  };

  // Persist Step 2 inputs when moving to next step
  SelectProfileStep.prototype.applyToProfile = function() {
    var profile = this._wizard._schemaProfile || {};
    var get = function(id) { var el = document.getElementById(id); return el ? el.value : ''; };
    var v;
    v = get('dialect'); if (v) profile.dialect = v;
    v = get('host'); if (v) profile.host = v;
    v = get('port'); if (v) { var p = parseInt(v, 10); if (!isNaN(p)) profile.port = p; }
    v = get('database'); if (v) profile.database = v;
    v = get('username'); if (v) profile.username = v;
    var pwdEl = document.getElementById('password'); if (pwdEl) profile.password = pwdEl.value;
    // Set default preset to specific for catalog mode
    profile.preset = 'specific';
    this._wizard._schemaProfile = profile;
  };

  /**
   * Step 2: Select Fields
   */
  var SelectFieldsStep = function(wizard) {
    this._wizard = wizard;
    this._selectedFields = [];
  };

  SelectFieldsStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.selectFields.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.selectFields.description') + '</p>';

    // Main table and Record ID controls
    html += '<div class="form-inline">';
    html += '  <div class="form-group">'
         + '    <label>' + i18n.t('records.db.wizard.selectFields.mainTable') + ':</label>'
         + '    <select id="main-table-select"><option value="">-- ' + i18n.t('records.db.wizard.selectFields.selectTable') + ' --</option></select>'
         + '  </div>';
    html += '  <div class="form-group">'
         + '    <label>' + i18n.t('records.db.wizard.selectFields.recordIdColumn') + ':</label>'
         + '    <select id="record-id-select"><option value="">--</option></select>'
         + '  </div>';
    html += '  <div id="fields-status" class="status"></div>';
    html += '</div>';

    html += '<div class="fields-container">';
    html += '<div class="available-fields">';
    html += '<h4>' + i18n.t('records.db.wizard.selectFields.availableFields') + '</h4>';
    html += '<ul id="available-fields-list"></ul>';
    html += '</div>';
    html += '<div class="fields-buttons">';
    html += '<button id="select-all-fields">' + i18n.t('records.db.wizard.selectFields.selectAll') + '</button>';
    html += '<button id="deselect-all-fields">' + i18n.t('records.db.wizard.selectFields.deselectAll') + '</button>';
    html += '</div>';
    html += '<div class="selected-fields">';
    html += '<h4>' + i18n.t('records.db.wizard.selectFields.selectedFields') + '</h4>';
    html += '<ul id="selected-fields-list"></ul>';
    html += '</div>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;

    // Attach event handlers
    var mainTableSelect = document.getElementById('main-table-select');
    if (mainTableSelect) {
      // Load tables when dropdown is focused
      mainTableSelect.onfocus = function() {
        if (!mainTableSelect.dataset.loaded) {
          self._loadTables();
        }
      };
      // Load fields when table is selected
      mainTableSelect.onchange = function() {
        if (mainTableSelect.value) {
          self._loadFields();
        }
      };
    }

    // Prefill main table if already set
    var existingMainTable = (this._wizard._schemaProfile && this._wizard._schemaProfile.mainTable) || '';
    if (existingMainTable) {
      // Load tables first, then set the value
      this._loadTables(function() {
        if (mainTableSelect) {
          mainTableSelect.value = existingMainTable;
          // Try loading fields if we already know the table
          self._loadFields();
        }
      });
    }

    document.getElementById('select-all-fields').onclick = function() {
      self._selectAllFields();
    };
    document.getElementById('deselect-all-fields').onclick = function() {
      self._deselectAllFields();
    };
  };

  // Load tables list
  SelectFieldsStep.prototype._loadTables = function(callback) {
    var self = this;
    var status = document.getElementById('fields-status');
    var mainTableSelect = document.getElementById('main-table-select');

    if (status) status.textContent = i18n.t('records.db.wizard.selectFields.loadingTables') + '...';

    var profile = this._wizard._schemaProfile || {};
    // Ensure Step 2 inputs are persisted
    if (this._wizard && this._wizard._steps && this._wizard._steps[1] && typeof this._wizard._steps[1].applyToProfile === 'function') {
      try { this._wizard._steps[1].applyToProfile(); profile = this._wizard._schemaProfile || profile; } catch (e) {}
    }

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-tables",
            "csrf_token": token
          }),
          {
            "schemaProfile": JSON.stringify(profile)
          },
          function(data) {
            if (mainTableSelect) {
              // Keep the first placeholder option
              while (mainTableSelect.options.length > 1) {
                mainTableSelect.remove(1);
              }
            }
            if (data && data.status === 'ok' && Array.isArray(data.tables)) {
              data.tables.forEach(function(t) {
                var opt = document.createElement('option');
                opt.value = t.name || t;
                opt.textContent = t.label || t.name || t;
                if (mainTableSelect) mainTableSelect.appendChild(opt);
              });
              if (mainTableSelect) mainTableSelect.dataset.loaded = 'true';
              if (status) status.textContent = '';
              if (callback) callback();
            } else {
              if (status) status.textContent = (data && data.message) ? data.message : 'list-tables error';
            }
          },
          "json"
        );
      });
    }
  };

  SelectFieldsStep.prototype._loadFields = function() {
    var self = this;
    var status = document.getElementById('fields-status');
    var profile = this._wizard._schemaProfile || {};

    // Determine main table
    var mtSelect = document.getElementById('main-table-select');
    var mainTable = mtSelect ? (mtSelect.value || '').trim() : (profile.mainTable || '');
    if (!mainTable) {
      if (status) status.textContent = i18n.t('records.db.wizard.selectFields.validation.mainTableRequired');
      return;
    }

    // Ensure Step 2 inputs have been persisted
    if (this._wizard && this._wizard._steps && this._wizard._steps[1] && typeof this._wizard._steps[1].applyToProfile === 'function') {
      try { this._wizard._steps[1].applyToProfile(); profile = this._wizard._schemaProfile || profile; } catch (e) {}
    }

    profile.mainTable = mainTable;
    this._wizard._schemaProfile = profile;
    if (status) status.textContent = i18n.t('records.db.wizard.selectFields.loadingFields') + '...';

    // Clear previous columns if table changed
    if (profile.lastLoadedTable !== mainTable) {
      profile.columns = null;
      profile.lastLoadedTable = mainTable;
    }

    // If columns already loaded for this table, just render
    if (Array.isArray(profile.columns) && profile.columns.length) {
      this._renderFields(profile.columns);
      this._populateRecordIdSelect(profile.columns);
      if (status) status.textContent = '';
      return;
    }

    // Fetch columns using lightweight list-columns endpoint
    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-columns",
            "csrf_token": token
          }),
          {
            "schemaProfile": JSON.stringify(profile)
          },
          function(data) {
            if (data && data.status === 'ok' && Array.isArray(data.columns)) {
              self._wizard._schemaProfile.columns = data.columns;
              self._renderFields(data.columns);
              self._populateRecordIdSelect(data.columns);
              if (status) status.textContent = '';
            } else {
              if (status) status.textContent = (data && data.message) ? data.message : i18n.t('records.db.wizard.selectFields.errorLoadingFields');
              if (console && console.warn) console.warn('[records-db] list-columns failed:', data);
            }
          },
          "json"
        );
      });
    } else {
      var params = new URLSearchParams();
      params.set('schemaProfile', JSON.stringify(profile));
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=list-columns', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      .then(function(response) { return response.json(); })
      .then(function(data) {
        if (data && data.status === 'ok' && Array.isArray(data.columns)) {
          self._wizard._schemaProfile.columns = data.columns;
          self._renderFields(data.columns);
          self._populateRecordIdSelect(data.columns);
          if (status) status.textContent = '';
        } else {
          if (status) status.textContent = (data && data.message) ? data.message : i18n.t('records.db.wizard.selectFields.errorLoadingFields');
        }
      })
      .catch(function(err) {
        if (status) status.textContent = err && err.message ? err.message : i18n.t('records.db.wizard.selectFields.errorLoadingFields');
      });
    }
  };

  SelectFieldsStep.prototype._renderFields = function(columns) {
    var self = this;
    var availableList = document.getElementById('available-fields-list');
    var selectedList = document.getElementById('selected-fields-list');
    if (availableList) availableList.innerHTML = '';
    if (selectedList) selectedList.innerHTML = '';
    this._selectedFields = [];

    // Restore previously selected fields from profile.fieldMappings
    var profile = this._wizard._schemaProfile || {};
    var selectedSet = new Set(((profile.fieldMappings || []).map(function(m){ return m.columnName; })));

    (columns || []).forEach(function(column) {
      var li = document.createElement('li');
      li.className = 'field-item';
      var typeLabel = (column.type || '');
      li.textContent = column.name + (typeLabel ? (' (' + typeLabel + ')') : '');
      li.onclick = function() {
        self._toggleField(column, li);
      };
      if (availableList) {
        availableList.appendChild(li);
        // Preselect if it was previously chosen
        if (selectedSet.has(column.name)) {
          li.click();
        }
      }
    });
  };

  SelectFieldsStep.prototype._toggleField = function(column, li) {
    var selectedList = document.getElementById('selected-fields-list');

    if (li.classList.contains('selected')) {
      li.classList.remove('selected');
      this._selectedFields = this._selectedFields.filter(function(f) { return f.name !== column.name; });

      // Remove from selected list
      var selectedItems = selectedList.querySelectorAll('li');
      selectedItems.forEach(function(item) {
        if (item.textContent.indexOf(column.name) === 0) {
          item.remove();
        }
      });
    } else {
      li.classList.add('selected');
      this._selectedFields.push(column);

      // Add to selected list
      var selectedLi = document.createElement('li');
      selectedLi.className = 'field-item selected';
      selectedLi.textContent = column.name + ' (' + column.type + ')';
      selectedList.appendChild(selectedLi);
    }
  };

  SelectFieldsStep.prototype._selectAllFields = function() {
    var items = document.querySelectorAll('#available-fields-list .field-item');
    items.forEach(function(item) {
      if (!item.classList.contains('selected')) {
        item.click();
      }
    });
  };

  SelectFieldsStep.prototype._deselectAllFields = function() {
    var items = document.querySelectorAll('#available-fields-list .field-item.selected');
    items.forEach(function(item) {
      item.click();
    });
  };

  SelectFieldsStep.prototype.getSelectedFields = function() {
    return this._selectedFields;
  };

  // Validate Step 3 before proceeding
  SelectFieldsStep.prototype.validateFilters = function() {
    var mtSelect = document.getElementById('main-table-select');
    var mainTable = mtSelect ? (mtSelect.value || '').trim() : '';
    if (!mainTable) {
      return { valid: false, message: i18n.t('records.db.wizard.selectFields.validation.mainTableRequired') };
    }
    var rid = document.getElementById('record-id-select');
    if (!rid || !rid.value) {
      return { valid: false, message: i18n.t('records.db.wizard.selectFields.validation.recordIdRequired') };
    }
    var selected = Array.isArray(this._selectedFields) ? this._selectedFields : [];
    if (!selected.length) {
      return { valid: false, message: i18n.t('records.db.wizard.selectFields.validation.noFieldsSelected') };
    }
    return { valid: true };
  };

  // Populate Record ID select options from columns
  SelectFieldsStep.prototype._populateRecordIdSelect = function(columns) {
    var select = document.getElementById('record-id-select');
    if (!select) return;
    // Clear existing options
    while (select.firstChild) { select.removeChild(select.firstChild); }
    var placeholder = document.createElement('option');
    placeholder.value = ''; placeholder.textContent = '--';
    select.appendChild(placeholder);
    var names = [];
    columns.forEach(function(c){
      var opt = document.createElement('option');
      opt.value = c.name; opt.textContent = c.name;
      select.appendChild(opt);
      names.push((c.name || '').toLowerCase());
    });
    // Preselect existing or common defaults
    var profile = this._wizard._schemaProfile || {};
    var toSelect = profile.recordIdColumn || (names.indexOf('id') >= 0 ? 'id' : (names.indexOf('record_id') >= 0 ? 'record_id' : ''));
    if (toSelect) { select.value = toSelect; }
  };

  // Apply selected fields and record id to schema profile
  SelectFieldsStep.prototype.applyToProfile = function() {
    var profile = this._wizard._schemaProfile || {};
    // Record ID column
    var rid = document.getElementById('record-id-select');
    if (rid && rid.value) {
      profile.recordIdColumn = rid.value;
    }
    // Field mappings from selected fields
    var selected = Array.isArray(this._selectedFields) ? this._selectedFields : [];
    if (selected.length) {
      // Build a lookup map from existing fieldMappings to preserve columnLabel and other properties
      var existingMappings = {};
      var jsonFieldMappings = [];
      if (Array.isArray(profile.fieldMappings)) {
        profile.fieldMappings.forEach(function(m) {
          if (m && m.columnName) {
            // Separate JSON sub-fields from regular fields
            if (m.jsonPath) {
              // This is a JSON sub-field, preserve it completely
              jsonFieldMappings.push(m);
            } else {
              // Regular field - use columnName as key
              existingMappings[m.columnName] = m;
            }
          }
        });
      }
      var mappings = selected.map(function(col){
        var dt = (col.type || '').toLowerCase();
        var norm;
        if (dt.indexOf('int') >= 0 || dt.indexOf('dec') >= 0 || dt.indexOf('num') >= 0 || dt.indexOf('double') >= 0 || dt.indexOf('float') >= 0) norm = 'number';
        else if (dt.indexOf('date') >= 0 || dt.indexOf('time') >= 0) norm = 'date';
        else if (dt.indexOf('bool') >= 0) norm = 'boolean';
        else if (dt.indexOf('json') >= 0) norm = 'json';
        else if (dt.indexOf('text') >= 0) norm = 'text';
        else norm = 'string';
        // Preserve existing columnLabel and dataType if available
        var existing = existingMappings[col.name];
        var label = (existing && existing.columnLabel) ? existing.columnLabel : col.name;
        var dataType = (existing && existing.dataType) ? existing.dataType : norm;
        return { columnName: col.name, columnLabel: label, dataType: dataType };
      });
      // Also preserve JSON sub-field mappings if parent column is still selected
      jsonFieldMappings.forEach(function(m) {
        var parentSelected = selected.some(function(col) { return col.name === m.columnName; });
        if (parentSelected) {
          // Add JSON field mapping - preserve all properties including columnLabel
          mappings.push(m);
        }
      });
      profile.fieldMappings = mappings;
    }
    // Persist back
    this._wizard._schemaProfile = profile;
  };


  /**
   * Step 3: Configure Filters
   */
  var ConfigureFiltersStep = function(wizard) {
    this._wizard = wizard;
    this._filterBuilder = new FilterConditionBuilder(wizard);
  };

  ConfigureFiltersStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.configureFilters.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.configureFilters.description') + '</p>';
    html += '<div class="filters-container" id="filter-builder-container"></div>';
    html += '</div>';
    div.innerHTML = html;

    // Render filter builder
    var container = document.getElementById('filter-builder-container');
    this._filterBuilder.render(container);
    if (this._filterBuilder && typeof this._filterBuilder._loadFromProfile === 'function') {
      this._filterBuilder._loadFromProfile();
    }
  };

  ConfigureFiltersStep.prototype.getFilters = function() {
    return this._filterBuilder.getFilters();
  };

  ConfigureFiltersStep.prototype.validateFilters = function() {
    return this._filterBuilder.validateFilters();
  };

  // Persist filters into schema profile
  ConfigureFiltersStep.prototype.applyToProfile = function() {
    var profile = this._wizard._schemaProfile || {};
    if (this._filterBuilder && typeof this._filterBuilder.getFilters === 'function') {
      profile.filters = this._filterBuilder.getFilters();
    }
    this._wizard._schemaProfile = profile;
  };

  /**
   * Step 5: Preview
   */
  var PreviewStep = function(wizard) {
    this._wizard = wizard;
  };

  PreviewStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.preview.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.preview.description') + '</p>';
    html += '<div id="preview-loading">' + i18n.t('records.db.wizard.preview.loading') + '...</div>';
    html += '<div id="preview-table"></div>';
    html += '</div>';
    div.innerHTML = html;

    // Load preview data
    this._loadPreview();
  };

  PreviewStep.prototype._loadPreview = function() {
    var self = this;
    var profile = this._wizard._schemaProfile;

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "parse-preview",
            "csrf_token": token
          }),
          {
            "schemaProfile": JSON.stringify(profile),
            "offset": 0,
            "limit": 100
          },
          function(data) {
            document.getElementById('preview-loading').style.display = 'none';
            if (data && data.status === 'ok') {
              // Store total rows for CreateProjectStep (prefer totalRows, fallback to rowCount)
              var total = (typeof data.totalRows === 'number') ? data.totalRows : data.rowCount;
              if (typeof total === 'number') {
                self._wizard._totalRows = total;
              }
              self._renderPreviewTable(data.columns, data.rows, total);
            } else {
              var msg = (data && data.message) ? data.message : 'Unknown error';
              document.getElementById('preview-table').innerHTML = '<p>Error: ' + msg + '</p>';
            }
          },
          "json"
        ).fail(function(xhr) {
          document.getElementById('preview-loading').style.display = 'none';
          document.getElementById('preview-table').innerHTML = '<p>Error: HTTP ' + xhr.status + '</p>';
        });
      });
    } else {
      var params = new URLSearchParams();
      params.set('schemaProfile', JSON.stringify(profile));
      params.set('offset', '0');
      params.set('limit', '100');
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=parse-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      .then(function(response) { return response.json(); })
      .then(function(data) {
        document.getElementById('preview-loading').style.display = 'none';
        if (data && data.status === 'ok') {
          // Store total rows for CreateProjectStep (prefer totalRows, fallback to rowCount)
          var total = (typeof data.totalRows === 'number') ? data.totalRows : data.rowCount;
          if (typeof total === 'number') {
            self._wizard._totalRows = total;
          }
          self._renderPreviewTable(data.columns, data.rows, total);
        } else {
          var msg = (data && data.message) ? data.message : 'Unknown error';
          document.getElementById('preview-table').innerHTML = '<p>Error: ' + msg + '</p>';
        }
      })
      .catch(function(error) {
        document.getElementById('preview-loading').style.display = 'none';
        document.getElementById('preview-table').innerHTML = '<p>Error: ' + error.message + '</p>';
      });
    }
  };

  PreviewStep.prototype._renderPreviewTable = function(columns, rows, totalRows) {
    var html = '<div class="preview-table-wrapper">';
    html += '<table class="preview-table">';
    html += '<thead><tr>';

    columns.forEach(function(col) {
      html += '<th>' + col.name + '</th>';
    });

    html += '</tr></thead>';
    html += '<tbody>';

    rows.forEach(function(row) {
      html += '<tr>';
      columns.forEach(function(col) {
        var value = row[col.name] || '';
        html += '<td>' + (typeof value === 'object' ? JSON.stringify(value) : value) + '</td>';
      });
      html += '</tr>';
    });

    html += '</tbody>';
    // Fixed footer row for total count
    html += '<tfoot><tr class="preview-table-footer">';
    html += '<td colspan="' + columns.length + '">';
    html += i18n.t('records.db.wizard.preview.totalRows', '总行数') + '：共 <strong>' + (typeof totalRows === 'number' ? totalRows : '-') + '</strong> 行';
    html += '</td></tr></tfoot>';
    html += '</table>';
    html += '</div>';

    document.getElementById('preview-table').innerHTML = html;
  };

  /**
   * Step 8: Create Project
   */
  var CreateProjectStep = function(wizard) {
    this._wizard = wizard;
  };

  CreateProjectStep.prototype.render = function(div) {
    // Use total rows from preview, or default to 10000
    var defaultMaxRows = (typeof this._wizard._totalRows === 'number' && this._wizard._totalRows > 0)
      ? this._wizard._totalRows
      : 10000;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.createProject.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.createProject.description') + '</p>';
    html += '<div class="project-form">';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.createProject.projectName') + ':</label>';
    html += '<input type="text" id="project-name" placeholder="My Project">';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.createProject.maxRows') + ':</label>';
    html += '<input type="number" id="max-rows" value="' + defaultMaxRows + '" min="1">';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label><input type="checkbox" id="records-db-no-redirect"> ';
    html += i18n.t('records.db.wizard.createProject.noRedirect') + '</label>';
    html += '</div>';
    html += '<div class="form-group">';
    html += '<label><input type="checkbox" id="records-db-save-config" checked> ';
    html += i18n.t('records.db.wizard.createProject.saveConfig') + '</label>';
    html += '</div>';
    html += '<div id="project-status"></div>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;

    // Prefill project name if loaded from history
    var projectNameInput = document.getElementById('project-name');
    if (projectNameInput && this._wizard._loadedProjectName) {
      projectNameInput.value = this._wizard._loadedProjectName;
    }

    // Handle save config checkbox change
    var saveConfigCb = document.getElementById('records-db-save-config');
    if (saveConfigCb) {
      saveConfigCb.onchange = function() {
        var projectName = projectNameInput ? projectNameInput.value.trim() : '';
        if (!saveConfigCb.checked && projectName) {
          // Remove from history when unchecked
          removeHistoryConfig(projectName);
        }
      };
    }
  };

  CreateProjectStep.prototype.getProjectName = function() {
    return document.getElementById('project-name').value || 'Imported Project';
  };

  CreateProjectStep.prototype.shouldRedirect = function() {
    var cb = document.getElementById('records-db-no-redirect');
    return !cb || !cb.checked;
  };

  CreateProjectStep.prototype.getMaxRows = function() {
    return parseInt(document.getElementById('max-rows').value) || 10000;
  };

  CreateProjectStep.prototype.shouldSaveConfig = function() {
    var cb = document.getElementById('records-db-save-config');
    return cb && cb.checked;
  };

  CreateProjectStep.prototype.saveConfigToHistory = function() {
    if (!this.shouldSaveConfig()) return;
    var projectName = this.getProjectName();
    if (!projectName || projectName === 'Imported Project') return; // Don't save default name
    var profile = this._wizard._schemaProfile || {};
    var mode = this._wizard._mode || 'catalog';
    saveHistoryConfig(projectName, profile, mode);
  };

})();

