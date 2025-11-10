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
    this._steps = [
      new SelectProfileStep(this),
      new SelectFieldsStep(this),
      new ConfigureFiltersStep(this),
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
        self._onDone();
      } else {
        self._showStep(self._currentStep + 1);
      }
    };
  };

  /**
   * Step 1: Select Profile
   */
  var SelectProfileStep = function(wizard) {
    this._wizard = wizard;
  };

  SelectProfileStep.prototype.render = function(div) {
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.selectProfile.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.selectProfile.description') + '</p>';
    html += '<div class="profile-options">';
    html += '<label><input type="radio" name="profile" value="kubao"> ' + i18n.t('records.db.wizard.selectProfile.kubao') + '</label>';
    html += '<label><input type="radio" name="profile" value="flat_table"> ' + i18n.t('records.db.wizard.selectProfile.flatTable') + '</label>';
    html += '<label><input type="radio" name="profile" value="generic_json"> ' + i18n.t('records.db.wizard.selectProfile.genericJson') + '</label>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;
  };

  /**
   * Step 2: Select Fields
   */
  var SelectFieldsStep = function(wizard) {
    this._wizard = wizard;
  };

  SelectFieldsStep.prototype.render = function(div) {
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.selectFields.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.selectFields.description') + '</p>';
    html += '<div class="fields-container">';
    html += '<div class="available-fields">';
    html += '<h4>' + i18n.t('records.db.wizard.selectFields.availableFields') + '</h4>';
    html += '<ul id="available-fields-list"></ul>';
    html += '</div>';
    html += '<div class="selected-fields">';
    html += '<h4>' + i18n.t('records.db.wizard.selectFields.selectedFields') + '</h4>';
    html += '<ul id="selected-fields-list"></ul>';
    html += '</div>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;
  };

  /**
   * Step 3: Configure Filters
   */
  var ConfigureFiltersStep = function(wizard) {
    this._wizard = wizard;
  };

  ConfigureFiltersStep.prototype.render = function(div) {
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.configureFilters.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.configureFilters.description') + '</p>';
    html += '<div class="filters-container">';
    html += '<label><input type="checkbox" id="exclude-exported"> ' + i18n.t('records.db.wizard.configureFilters.excludeExported') + '</label>';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;
  };

  /**
   * Step 4: Preview
   */
  var PreviewStep = function(wizard) {
    this._wizard = wizard;
  };

  PreviewStep.prototype.render = function(div) {
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.preview.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.preview.description') + '</p>';
    html += '<div id="preview-table"></div>';
    html += '</div>';
    div.innerHTML = html;
  };

  /**
   * Step 5: Create Project
   */
  var CreateProjectStep = function(wizard) {
    this._wizard = wizard;
  };

  CreateProjectStep.prototype.render = function(div) {
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.createProject.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.createProject.description') + '</p>';
    html += '<div class="project-form">';
    html += '<label>' + i18n.t('records.db.wizard.createProject.projectName') + ':</label>';
    html += '<input type="text" id="project-name" placeholder="My Project">';
    html += '</div>';
    html += '</div>';
    div.innerHTML = html;
  };

})();

