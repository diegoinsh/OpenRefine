/*
 * Records Database Import Controller
 * 
 * Registers the Records Database importer with OpenRefine
 */

(function() {
  'use strict';

  // Register the import controller
  Refine.registerImportingController(new ImportingController({
    id: 'records-db',
    label: i18n.t('records.db.label'),
    uiClass: RecordsDBSourceUI
  }));

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
    html += '<button id="records-db-prev" class="button">' + i18n.t('common.button.previous') + '</button>';
    html += '<button id="records-db-next" class="button">' + i18n.t('common.button.next') + '</button>';
    html += '</div>';
    html += '</div>';
    
    bodyDiv.innerHTML = html;
    
    // Initialize the wizard
    this._wizard = new RecordsDBWizard(this._controller, onDone);
    this._wizard.init(bodyDiv);
  };

  /**
   * Records Database Wizard
   */
  var RecordsDBWizard = function(controller, onDone) {
    this._controller = controller;
    this._onDone = onDone;
    this._currentStep = 0;
    this._schemaProfile = {};
    this._steps = [
      new SelectProfileStep(this),
      new SelectFieldsStep(this),
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

    // Update step indicator
    var stepsDiv = document.getElementById('records-db-steps');
    stepsDiv.innerHTML = '<p>Step ' + (stepIndex + 1) + ' of ' + this._steps.length + '</p>';

    // Show step content
    var contentDiv = document.getElementById('records-db-content');
    contentDiv.innerHTML = '';
    step.render(contentDiv);

    // Update buttons
    var prevBtn = document.getElementById('records-db-prev');
    var nextBtn = document.getElementById('records-db-next');

    prevBtn.disabled = (stepIndex === 0);
    nextBtn.disabled = false;

    if (stepIndex === this._steps.length - 1) {
      nextBtn.textContent = i18n.t('common.button.finish');
    } else {
      nextBtn.textContent = i18n.t('common.button.next');
    }

    // Attach event handlers
    var self = this;
    prevBtn.onclick = function() {
      self._showStep(self._currentStep - 1);
    };
    nextBtn.onclick = function() {
      if (self._currentStep === self._steps.length - 1) {
        self._createProject();
      } else {
        // Validate current step before moving to next
        if (self._currentStep === 2) { // ConfigureFiltersStep
          var validation = self._steps[2].validateFilters();
          if (!validation.valid) {
            alert(validation.message);
            return;
          }
          // Store filters in wizard
          self._filters = self._steps[2].getFilters();
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

    var statusDiv = document.getElementById('project-status');
    statusDiv.innerHTML = '<p>' + i18n.t('records.db.wizard.createProject.creating') + '...</p>';

    fetch('/command/records-db/create-project', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectName: projectName,
        schemaProfile: this._schemaProfile,
        maxRows: maxRows
      })
    })
    .then(function(response) { return response.json(); })
    .then(function(data) {
      if (data.status === 'ok') {
        statusDiv.innerHTML = '<p style="color: green;">' + i18n.t('records.db.wizard.createProject.success') + '</p>';
        setTimeout(function() {
          self._onDone();
        }, 1000);
      } else {
        statusDiv.innerHTML = '<p style="color: red;">Error: ' + data.message + '</p>';
      }
    })
    .catch(function(error) {
      statusDiv.innerHTML = '<p style="color: red;">Error: ' + error.message + '</p>';
    });
  };

  /**
   * Step 1: Select Profile
   */
  var SelectProfileStep = function(wizard) {
    this._wizard = wizard;
  };

  SelectProfileStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.selectProfile.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.selectProfile.description') + '</p>';

    html += '<div class="profile-options">';
    html += '<label><input type="radio" name="profile" value="kubao"> ' + i18n.t('records.db.wizard.selectProfile.kubao') + '</label>';
    html += '<label><input type="radio" name="profile" value="flat_table"> ' + i18n.t('records.db.wizard.selectProfile.flatTable') + '</label>';
    html += '<label><input type="radio" name="profile" value="generic_json"> ' + i18n.t('records.db.wizard.selectProfile.genericJson') + '</label>';
    html += '</div>';

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
    html += '<button id="test-connection" class="button">' + i18n.t('records.db.wizard.selectProfile.testConnection') + '</button>';
    html += '<div id="connection-status"></div>';
    html += '</div>';

    html += '</div>';
    div.innerHTML = html;

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

    // Test connection by calling parse-preview
    fetch('/command/records-db/parse-preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ schemaProfile: profile, offset: 0, limit: 1 })
    })
    .then(function(response) { return response.json(); })
    .then(function(data) {
      if (data.status === 'ok') {
        statusDiv.innerHTML = '<p style="color: green;">' + i18n.t('records.db.wizard.selectProfile.connectionSuccess') + '</p>';
      } else {
        statusDiv.innerHTML = '<p style="color: red;">Error: ' + data.message + '</p>';
      }
    })
    .catch(function(error) {
      statusDiv.innerHTML = '<p style="color: red;">Error: ' + error.message + '</p>';
    });
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
    html += '<div class="fields-container">';
    html += '<div class="available-fields">';
    html += '<h4>' + i18n.t('records.db.wizard.selectFields.availableFields') + '</h4>';
    html += '<ul id="available-fields-list"></ul>';
    html += '</div>';
    html += '<div class="fields-buttons">';
    html += '<button id="select-all-fields" class="button">' + i18n.t('records.db.wizard.selectFields.selectAll') + '</button>';
    html += '<button id="deselect-all-fields" class="button">' + i18n.t('records.db.wizard.selectFields.deselectAll') + '</button>';
    html += '</div>';
    html += '<div class="selected-fields">';
    html += '<h4>' + i18n.t('records.db.wizard.selectFields.selectedFields') + '</h4>';
    html += '<ul id="selected-fields-list"></ul>';
    html += '</div>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;

    // Load fields from database
    this._loadFields();

    // Attach event handlers
    document.getElementById('select-all-fields').onclick = function() {
      self._selectAllFields();
    };
    document.getElementById('deselect-all-fields').onclick = function() {
      self._deselectAllFields();
    };
  };

  SelectFieldsStep.prototype._loadFields = function() {
    var self = this;
    var profile = this._wizard._schemaProfile;

    if (!profile || !profile.columns) {
      // Fetch fields from database
      fetch('/command/records-db/parse-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ schemaProfile: profile, offset: 0, limit: 1 })
      })
      .then(function(response) { return response.json(); })
      .then(function(data) {
        if (data.status === 'ok') {
          self._wizard._schemaProfile.columns = data.columns;
          self._renderFields(data.columns);
        }
      });
    } else {
      this._renderFields(profile.columns);
    }
  };

  SelectFieldsStep.prototype._renderFields = function(columns) {
    var self = this;
    var availableList = document.getElementById('available-fields-list');

    columns.forEach(function(column) {
      var li = document.createElement('li');
      li.className = 'field-item';
      li.textContent = column.name + ' (' + column.type + ')';
      li.onclick = function() {
        self._toggleField(column, li);
      };
      availableList.appendChild(li);
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
  };

  ConfigureFiltersStep.prototype.getFilters = function() {
    return this._filterBuilder.getFilters();
  };

  ConfigureFiltersStep.prototype.validateFilters = function() {
    return this._filterBuilder.validateFilters();
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

    fetch('/command/records-db/parse-preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ schemaProfile: profile, offset: 0, limit: 100 })
    })
    .then(function(response) { return response.json(); })
    .then(function(data) {
      document.getElementById('preview-loading').style.display = 'none';
      if (data.status === 'ok') {
        self._renderPreviewTable(data.columns, data.rows);
      } else {
        document.getElementById('preview-table').innerHTML = '<p>Error: ' + data.message + '</p>';
      }
    })
    .catch(function(error) {
      document.getElementById('preview-loading').style.display = 'none';
      document.getElementById('preview-table').innerHTML = '<p>Error: ' + error.message + '</p>';
    });
  };

  PreviewStep.prototype._renderPreviewTable = function(columns, rows) {
    var html = '<table class="preview-table">';
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
    html += '</table>';

    document.getElementById('preview-table').innerHTML = html;
  };

  /**
   * Step 6: Create Project
   */
  var CreateProjectStep = function(wizard) {
    this._wizard = wizard;
  };

  CreateProjectStep.prototype.render = function(div) {
    var self = this;
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
    html += '<input type="number" id="max-rows" value="10000" min="1">';
    html += '</div>';
    html += '<div id="project-status"></div>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;
  };

  CreateProjectStep.prototype.getProjectName = function() {
    return document.getElementById('project-name').value || 'Imported Project';
  };

  CreateProjectStep.prototype.getMaxRows = function() {
    return parseInt(document.getElementById('max-rows').value) || 10000;
  };

})();

