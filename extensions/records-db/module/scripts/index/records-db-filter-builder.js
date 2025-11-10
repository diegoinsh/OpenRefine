/*
 * Records Database Filter Builder
 * 
 * Implements filter condition builder UI for data filtering
 */

(function() {
  'use strict';

  /**
   * Filter Condition Builder
   */
  window.FilterConditionBuilder = function(wizard) {
    this._wizard = wizard;
    this._conditions = [];
    this._conditionId = 0;
  };

  FilterConditionBuilder.prototype.render = function(div) {
    var self = this;
    var html = '<div class="filter-builder">';
    
    // Exclude exported checkbox
    html += '<div class="filter-option">';
    html += '<label><input type="checkbox" id="exclude-exported" class="filter-checkbox"> ';
    html += i18n.t('records.db.wizard.configureFilters.excludeExported');
    html += '</label>';
    html += '<div id="exported-filter-config" class="filter-config" style="display:none;">';
    html += '<p>' + i18n.t('records.db.wizard.configureFilters.exportedFieldLabel') + ':</p>';
    html += '<input type="text" id="exported-field" placeholder="e.g., exported_flag" class="filter-input">';
    html += '<p>' + i18n.t('records.db.wizard.configureFilters.exportedValueLabel') + ':</p>';
    html += '<input type="text" id="exported-value" placeholder="e.g., true, 1, yes" class="filter-input">';
    html += '</div>';
    html += '</div>';
    
    // Custom conditions
    html += '<div class="filter-option">';
    html += '<h4>' + i18n.t('records.db.wizard.configureFilters.customConditions') + '</h4>';
    html += '<div id="conditions-list" class="conditions-list"></div>';
    html += '<button id="add-condition" class="button button-secondary">';
    html += i18n.t('records.db.wizard.configureFilters.addCondition');
    html += '</button>';
    html += '</div>';
    
    // Filter preview
    html += '<div class="filter-option">';
    html += '<h4>' + i18n.t('records.db.wizard.configureFilters.filterPreview') + '</h4>';
    html += '<div id="filter-preview" class="filter-preview">';
    html += '<p>' + i18n.t('records.db.wizard.configureFilters.noFilters') + '</p>';
    html += '</div>';
    html += '</div>';
    
    html += '</div>';
    div.innerHTML = html;
    
    // Attach event handlers
    document.getElementById('exclude-exported').onchange = function() {
      self._toggleExportedFilter();
    };
    
    document.getElementById('add-condition').onclick = function() {
      self._addCondition();
    };
  };

  FilterConditionBuilder.prototype._toggleExportedFilter = function() {
    var checkbox = document.getElementById('exclude-exported');
    var config = document.getElementById('exported-filter-config');
    
    if (checkbox.checked) {
      config.style.display = 'block';
    } else {
      config.style.display = 'none';
    }
    
    this._updateFilterPreview();
  };

  FilterConditionBuilder.prototype._addCondition = function() {
    var self = this;
    var conditionId = this._conditionId++;
    var conditionsList = document.getElementById('conditions-list');
    
    var conditionDiv = document.createElement('div');
    conditionDiv.className = 'condition-item';
    conditionDiv.id = 'condition-' + conditionId;
    
    var availableFields = this._wizard._schemaProfile?.columns || [];
    var fieldOptions = availableFields.map(function(col) {
      return '<option value="' + col.name + '">' + col.name + ' (' + col.type + ')</option>';
    }).join('');
    
    conditionDiv.innerHTML = '<div class="condition-row">';
    conditionDiv.innerHTML += '<select class="condition-field" data-id="' + conditionId + '">';
    conditionDiv.innerHTML += '<option value="">' + i18n.t('records.db.wizard.configureFilters.selectField') + '</option>';
    conditionDiv.innerHTML += fieldOptions;
    conditionDiv.innerHTML += '</select>';
    
    conditionDiv.innerHTML += '<select class="condition-operator" data-id="' + conditionId + '">';
    conditionDiv.innerHTML += '<option value="=">=</option>';
    conditionDiv.innerHTML += '<option value="!=">!=</option>';
    conditionDiv.innerHTML += '<option value=">">></option>';
    conditionDiv.innerHTML += '<option value="<"><</option>';
    conditionDiv.innerHTML += '<option value=">=">>= </option>';
    conditionDiv.innerHTML += '<option value="<=">&lt;=</option>';
    conditionDiv.innerHTML += '<option value="LIKE">LIKE</option>';
    conditionDiv.innerHTML += '<option value="IN">IN</option>';
    conditionDiv.innerHTML += '</select>';
    
    conditionDiv.innerHTML += '<input type="text" class="condition-value" data-id="' + conditionId + '" placeholder="' + i18n.t('records.db.wizard.configureFilters.enterValue') + '">';
    
    conditionDiv.innerHTML += '<select class="condition-logic" data-id="' + conditionId + '">';
    conditionDiv.innerHTML += '<option value="AND">AND</option>';
    conditionDiv.innerHTML += '<option value="OR">OR</option>';
    conditionDiv.innerHTML += '</select>';
    
    conditionDiv.innerHTML += '<button class="button button-danger remove-condition" data-id="' + conditionId + '">';
    conditionDiv.innerHTML += i18n.t('records.db.wizard.configureFilters.removeCondition');
    conditionDiv.innerHTML += '</button>';
    conditionDiv.innerHTML += '</div>';
    
    conditionsList.appendChild(conditionDiv);
    
    // Attach event handlers
    conditionDiv.querySelector('.condition-field').onchange = function() {
      self._updateFilterPreview();
    };
    conditionDiv.querySelector('.condition-operator').onchange = function() {
      self._updateFilterPreview();
    };
    conditionDiv.querySelector('.condition-value').onchange = function() {
      self._updateFilterPreview();
    };
    conditionDiv.querySelector('.remove-condition').onclick = function() {
      conditionDiv.remove();
      self._updateFilterPreview();
    };
    
    this._conditions.push({
      id: conditionId,
      field: '',
      operator: '=',
      value: '',
      logic: 'AND'
    });
    
    this._updateFilterPreview();
  };

  FilterConditionBuilder.prototype._updateFilterPreview = function() {
    var preview = document.getElementById('filter-preview');
    var filters = this.getFilters();
    
    if (!filters || (filters.conditions.length === 0 && !filters.excludeExported)) {
      preview.innerHTML = '<p>' + i18n.t('records.db.wizard.configureFilters.noFilters') + '</p>';
      return;
    }
    
    var html = '<div class="filter-summary">';
    
    if (filters.excludeExported) {
      html += '<div class="filter-item">';
      html += '<strong>' + i18n.t('records.db.wizard.configureFilters.excludeExported') + '</strong>';
      if (filters.exportedField) {
        html += ' (' + filters.exportedField + ' = ' + filters.exportedValue + ')';
      }
      html += '</div>';
    }
    
    filters.conditions.forEach(function(cond, index) {
      if (cond.field) {
        html += '<div class="filter-item">';
        if (index > 0) {
          html += '<span class="logic-operator">' + cond.logic + '</span> ';
        }
        html += '<strong>' + cond.field + '</strong> ';
        html += '<span class="operator">' + cond.operator + '</span> ';
        html += '<span class="value">' + cond.value + '</span>';
        html += '</div>';
      }
    });
    
    html += '</div>';
    preview.innerHTML = html;
  };

  FilterConditionBuilder.prototype.getFilters = function() {
    var filters = {
      excludeExported: false,
      exportedField: '',
      exportedValue: '',
      conditions: []
    };
    
    // Get exclude exported filter
    var excludeCheckbox = document.getElementById('exclude-exported');
    if (excludeCheckbox && excludeCheckbox.checked) {
      filters.excludeExported = true;
      filters.exportedField = document.getElementById('exported-field').value;
      filters.exportedValue = document.getElementById('exported-value').value;
    }
    
    // Get custom conditions
    var conditionItems = document.querySelectorAll('.condition-item');
    conditionItems.forEach(function(item) {
      var field = item.querySelector('.condition-field').value;
      var operator = item.querySelector('.condition-operator').value;
      var value = item.querySelector('.condition-value').value;
      var logic = item.querySelector('.condition-logic').value;
      
      if (field && value) {
        filters.conditions.push({
          field: field,
          operator: operator,
          value: value,
          logic: logic
        });
      }
    });
    
    return filters;
  };

  FilterConditionBuilder.prototype.validateFilters = function() {
    var filters = this.getFilters();
    
    // Validate exclude exported filter
    if (filters.excludeExported) {
      if (!filters.exportedField) {
        return {
          valid: false,
          message: i18n.t('records.db.wizard.configureFilters.exportedFieldRequired')
        };
      }
      if (!filters.exportedValue) {
        return {
          valid: false,
          message: i18n.t('records.db.wizard.configureFilters.exportedValueRequired')
        };
      }
    }
    
    // Validate custom conditions
    for (var i = 0; i < filters.conditions.length; i++) {
      var cond = filters.conditions[i];
      if (!cond.field || !cond.value) {
        return {
          valid: false,
          message: i18n.t('records.db.wizard.configureFilters.incompleteCondition')
        };
      }
    }
    
    return { valid: true };
  };

})();

