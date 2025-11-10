/*
 * Records Database Wizard Steps
 * 
 * Implements Wizard Step 3 (Field Mapping) and Step 4 (File Mapping)
 */

(function() {
  'use strict';

  /**
   * Step 3: Field Mapping
   */
  window.FieldMappingStep = function(wizard) {
    this._wizard = wizard;
    this._fieldMappings = {};
  };

  FieldMappingStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.fieldMapping.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.fieldMapping.description') + '</p>';
    
    html += '<div class="field-mapping-container">';
    html += '<table class="field-mapping-table">';
    html += '<thead>';
    html += '<tr>';
    html += '<th>' + i18n.t('records.db.wizard.fieldMapping.sourceField') + '</th>';
    html += '<th>' + i18n.t('records.db.wizard.fieldMapping.targetField') + '</th>';
    html += '<th>' + i18n.t('records.db.wizard.fieldMapping.fieldType') + '</th>';
    html += '<th>' + i18n.t('records.db.wizard.fieldMapping.action') + '</th>';
    html += '</tr>';
    html += '</thead>';
    html += '<tbody id="field-mapping-body">';
    html += '</tbody>';
    html += '</table>';
    html += '</div>';
    
    html += '<div class="field-mapping-actions">';
    html += '<button id="add-field-mapping" class="button">' + i18n.t('records.db.wizard.fieldMapping.addField') + '</button>';
    html += '</div>';
    
    html += '</div>';
    div.innerHTML = html;
    
    // Load field mappings from schema profile
    this._loadFieldMappings();
    
    // Attach event handlers
    document.getElementById('add-field-mapping').onclick = function() {
      self._addFieldMapping();
    };
  };

  FieldMappingStep.prototype._loadFieldMappings = function() {
    var self = this;
    var tbody = document.getElementById('field-mapping-body');
    
    // Get available fields from database
    var availableFields = this._wizard._schemaProfile?.columns || [];
    
    // Render field mappings
    availableFields.forEach(function(field, index) {
      var row = document.createElement('tr');
      row.innerHTML = '<td>' + field.name + '</td>';
      row.innerHTML += '<td><input type="text" class="target-field" value="' + field.name + '"></td>';
      row.innerHTML += '<td><select class="field-type">';
      row.innerHTML += '<option value="string">String</option>';
      row.innerHTML += '<option value="number">Number</option>';
      row.innerHTML += '<option value="boolean">Boolean</option>';
      row.innerHTML += '<option value="date">Date</option>';
      row.innerHTML += '<option value="json">JSON</option>';
      row.innerHTML += '</select></td>';
      row.innerHTML += '<td><button class="remove-field" data-index="' + index + '">Remove</button></td>';
      
      tbody.appendChild(row);
      
      // Attach remove handler
      row.querySelector('.remove-field').onclick = function() {
        row.remove();
      };
    });
  };

  FieldMappingStep.prototype._addFieldMapping = function() {
    var tbody = document.getElementById('field-mapping-body');
    var row = document.createElement('tr');
    
    row.innerHTML = '<td><input type="text" class="source-field" placeholder="Field name"></td>';
    row.innerHTML += '<td><input type="text" class="target-field" placeholder="Target name"></td>';
    row.innerHTML += '<td><select class="field-type">';
    row.innerHTML += '<option value="string">String</option>';
    row.innerHTML += '<option value="number">Number</option>';
    row.innerHTML += '<option value="boolean">Boolean</option>';
    row.innerHTML += '<option value="date">Date</option>';
    row.innerHTML += '<option value="json">JSON</option>';
    row.innerHTML += '</select></td>';
    row.innerHTML += '<td><button class="remove-field">Remove</button></td>';
    
    tbody.appendChild(row);
    
    row.querySelector('.remove-field').onclick = function() {
      row.remove();
    };
  };

  FieldMappingStep.prototype.getFieldMappings = function() {
    var mappings = {};
    var tbody = document.getElementById('field-mapping-body');
    
    tbody.querySelectorAll('tr').forEach(function(row) {
      var sourceField = row.querySelector('.source-field')?.value || 
                       row.querySelector('td:first-child').textContent;
      var targetField = row.querySelector('.target-field').value;
      var fieldType = row.querySelector('.field-type').value;
      
      if (sourceField && targetField) {
        mappings[sourceField] = {
          targetField: targetField,
          fieldType: fieldType
        };
      }
    });
    
    return mappings;
  };

  /**
   * Step 4: File Mapping
   */
  window.FileMappingStep = function(wizard) {
    this._wizard = wizard;
    this._fileMapping = {};
  };

  FileMappingStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + i18n.t('records.db.wizard.fileMapping.title') + '</h3>';
    html += '<p>' + i18n.t('records.db.wizard.fileMapping.description') + '</p>';
    
    html += '<div class="file-mapping-form">';
    
    // File root column
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.fileMapping.fileRootColumn') + ':</label>';
    html += '<input type="text" id="file-root-column" placeholder="e.g., file_root">';
    html += '</div>';
    
    // File root raw column
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.fileMapping.fileRootRawColumn') + ':</label>';
    html += '<input type="text" id="file-root-raw-column" placeholder="e.g., file_root_raw">';
    html += '</div>';
    
    // Allowed roots
    html += '<div class="form-group">';
    html += '<label>' + i18n.t('records.db.wizard.fileMapping.allowedRoots') + ':</label>';
    html += '<textarea id="allowed-roots" placeholder="One root per line" rows="4"></textarea>';
    html += '</div>';
    
    // File preview
    html += '<div class="form-group">';
    html += '<label><input type="checkbox" id="enable-file-preview"> ' + i18n.t('records.db.wizard.fileMapping.enableFilePreview') + '</label>';
    html += '</div>';
    
    html += '</div>';
    
    // File browser preview
    html += '<div class="file-browser-preview">';
    html += '<h4>' + i18n.t('records.db.wizard.fileMapping.preview') + '</h4>';
    html += '<div id="file-browser" class="file-browser"></div>';
    html += '</div>';
    
    html += '</div>';
    div.innerHTML = html;
    
    // Load file mapping from schema profile
    this._loadFileMapping();
    
    // Attach event handlers
    document.getElementById('enable-file-preview').onchange = function() {
      self._toggleFilePreview();
    };
  };

  FileMappingStep.prototype._loadFileMapping = function() {
    var fileMapping = this._wizard._schemaProfile?.fileMapping || {};
    
    document.getElementById('file-root-column').value = fileMapping.fileRootColumn || '';
    document.getElementById('file-root-raw-column').value = fileMapping.fileRootRawColumn || '';
    
    if (fileMapping.allowedRoots && fileMapping.allowedRoots.length > 0) {
      document.getElementById('allowed-roots').value = fileMapping.allowedRoots.join('\n');
    }
  };

  FileMappingStep.prototype._toggleFilePreview = function() {
    var checkbox = document.getElementById('enable-file-preview');
    var fileBrowser = document.getElementById('file-browser');
    
    if (checkbox.checked) {
      this._loadFilePreview();
    } else {
      fileBrowser.innerHTML = '';
    }
  };

  FileMappingStep.prototype._loadFilePreview = function() {
    var self = this;
    var fileRootColumn = document.getElementById('file-root-column').value;
    var fileBrowser = document.getElementById('file-browser');
    
    if (!fileRootColumn) {
      fileBrowser.innerHTML = '<p>' + i18n.t('records.db.wizard.fileMapping.selectFileRootColumn') + '</p>';
      return;
    }
    
    // Load file list from records-assets extension
    var root = this._wizard._schemaProfile?.fileMapping?.allowedRoots?.[0] || '';
    
    if (!root) {
      fileBrowser.innerHTML = '<p>' + i18n.t('records.db.wizard.fileMapping.noRootConfigured') + '</p>';
      return;
    }
    
    // Fetch file list
    fetch('/command/records-assets/list?root=' + encodeURIComponent(root) + '&limit=20')
      .then(function(response) { return response.json(); })
      .then(function(data) {
        if (data.status === 'ok') {
          self._renderFileList(data.items);
        } else {
          fileBrowser.innerHTML = '<p>Error: ' + data.message + '</p>';
        }
      })
      .catch(function(error) {
        fileBrowser.innerHTML = '<p>Error loading files: ' + error.message + '</p>';
      });
  };

  FileMappingStep.prototype._renderFileList = function(items) {
    var fileBrowser = document.getElementById('file-browser');
    var html = '<ul class="file-list">';
    
    items.forEach(function(item) {
      var icon = item.type === 'directory' ? '📁' : '📄';
      html += '<li class="file-item" data-type="' + item.type + '">';
      html += icon + ' ' + item.name;
      if (item.type === 'file') {
        html += ' (' + self._formatFileSize(item.size) + ')';
      }
      html += '</li>';
    });
    
    html += '</ul>';
    fileBrowser.innerHTML = html;
  };

  FileMappingStep.prototype._formatFileSize = function(bytes) {
    if (bytes === 0) return '0 B';
    var k = 1024;
    var sizes = ['B', 'KB', 'MB', 'GB'];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
  };

  FileMappingStep.prototype.getFileMapping = function() {
    return {
      fileRootColumn: document.getElementById('file-root-column').value,
      fileRootRawColumn: document.getElementById('file-root-raw-column').value,
      allowedRoots: document.getElementById('allowed-roots').value.split('\n').filter(function(r) { return r.trim(); }),
      enableFilePreview: document.getElementById('enable-file-preview').checked
    };
  };

})();

