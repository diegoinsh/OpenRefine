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
    html += '<button id="auto-json-flatten" class="button">' + (i18n.t('records.db.wizard.fieldMapping.autoJsonFlatten') || '自动扁平化 JSON 键') + '</button>';
    html += '<span id="json-status" class="status"></span>';
    html += '</div>';

    html += '</div>';
    div.innerHTML = html;

    // Load field mappings from schema profile
    this._loadFieldMappings();

    // Attach event handlers
    var addBtn = document.getElementById('add-field-mapping');
    if (addBtn) {
      addBtn.onclick = function() { self._addFieldMapping(); };
    }
    var autoBtn = document.getElementById('auto-json-flatten');
    if (autoBtn) {
      autoBtn.onclick = function(e) {
        e.preventDefault();
        e.stopPropagation();
        self._autoJsonFlatten();
      };
    }
  };

  FieldMappingStep.prototype._loadFieldMappings = function() {
    var tbody = document.getElementById('field-mapping-body');
    if (!tbody) {
      return;
    }

    // Clear existing rows
    while (tbody.firstChild) {
      tbody.removeChild(tbody.firstChild);
    }

    var profile = this._wizard._schemaProfile || {};
    var mappings = Array.isArray(profile.fieldMappings) ? profile.fieldMappings : [];

    // Render field mappings based on profile.fieldMappings (only selected fields)
    mappings.forEach(function(mapping) {
      if (!mapping || !mapping.columnName) {
        return;
      }
      var tr = document.createElement('tr');
      if (mapping.jsonPath) {
        tr.setAttribute('data-json-path', mapping.jsonPath);
      }

      var tdSource = document.createElement('td');
      tdSource.textContent = mapping.columnName;
      tr.appendChild(tdSource);

      var tdTarget = document.createElement('td');
      var input = document.createElement('input');
      input.type = 'text';
      input.className = 'target-field';
      input.value = mapping.columnLabel || mapping.columnName;
      tdTarget.appendChild(input);
      tr.appendChild(tdTarget);

      var tdType = document.createElement('td');
      var sel = document.createElement('select');
      sel.className = 'field-type';
      var opts = [
        { v: 'string', t: i18n.t('records.db.common.type.string') },
        { v: 'number', t: i18n.t('records.db.common.type.number') },
        { v: 'boolean', t: i18n.t('records.db.common.type.boolean') },
        { v: 'date', t: i18n.t('records.db.common.type.date') },
        { v: 'json', t: i18n.t('records.db.common.type.json') }
      ];
      opts.forEach(function(op) {
        var o = document.createElement('option');
        o.value = op.v;
        o.textContent = op.t;
        sel.appendChild(o);
      });
      var dt = (mapping.dataType || '').toLowerCase();
      if (!dt) {
        dt = 'string';
      }
      sel.value = dt;
      tdType.appendChild(sel);
      tr.appendChild(tdType);

      var tdAct = document.createElement('td');
      var btn = document.createElement('button');
      btn.className = 'remove-field';
      btn.textContent = i18n.t('records.db.wizard.fieldMapping.remove');
      btn.onclick = function() { tr.remove(); };
      tdAct.appendChild(btn);
      tr.appendChild(tdAct);

      tbody.appendChild(tr);
    });
  };

  FieldMappingStep.prototype._addFieldMapping = function() {
    var tbody = document.getElementById('field-mapping-body');
    var row = document.createElement('tr');

    row.innerHTML = '<td><input type="text" class="source-field" placeholder="Field name"></td>';
    row.innerHTML += '<td><input type="text" class="target-field" placeholder="Target name"></td>';
    row.innerHTML += '<td><select class="field-type">';
    row.innerHTML += '<option value="string">' + i18n.t('records.db.common.type.string') + '</option>';
    row.innerHTML += '<option value="number">' + i18n.t('records.db.common.type.number') + '</option>';
    row.innerHTML += '<option value="boolean">' + i18n.t('records.db.common.type.boolean') + '</option>';
    row.innerHTML += '<option value="date">' + i18n.t('records.db.common.type.date') + '</option>';
    row.innerHTML += '<option value="json">' + i18n.t('records.db.common.type.json') + '</option>';
    row.innerHTML += '</select></td>';
    row.innerHTML += '<td><button class="remove-field">' + i18n.t('records.db.wizard.fieldMapping.remove') + '</button></td>';

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

  FieldMappingStep.prototype.applyToProfile = function() {
    var profile = this._wizard._schemaProfile || {};
    var tbody = document.getElementById('field-mapping-body');
    var mappings = [];

    if (tbody) {
      tbody.querySelectorAll('tr').forEach(function(row) {
        var sourceField = null;
        var sourceInput = row.querySelector('.source-field');
        if (sourceInput && sourceInput.value) {
          sourceField = sourceInput.value.trim();
        } else {
          var firstCell = row.querySelector('td:first-child');
          if (firstCell && firstCell.textContent) {
            sourceField = firstCell.textContent.trim();
          }
        }

        var targetInput = row.querySelector('.target-field');
        var targetField = targetInput && targetInput.value ? targetInput.value.trim() : '';

        var typeSelect = row.querySelector('.field-type');
        var fieldType = typeSelect && typeSelect.value ? String(typeSelect.value).toLowerCase() : 'string';

        if (!sourceField || !targetField) {
          return;
        }

        var mapping = {
          columnName: sourceField,
          columnLabel: targetField,
          dataType: fieldType
        };

        var jsonPath = row.getAttribute('data-json-path');
        if (jsonPath) {
          mapping.jsonPath = jsonPath;
          mapping.isJsonField = true;
        }

        mappings.push(mapping);
      });
    }

    profile.fieldMappings = mappings;
    this._wizard._schemaProfile = profile;
  };


  /* Legacy _autoJsonFlatten implementation (commented out due to encoding issues)

  FieldMappingStep.prototype._autoJsonFlatten = function() {
    var self = this;
    var status = document.getElementById('json-status');
    if (status) {
      status.textContent = (i18n.t('records.db.common.loading') || '
4  ') + '...';
    }

    // Persist current UI to profile
    if (typeof this.applyToProfile === 'function') {
      try {
        this.applyToProfile();
      } catch (e) {
        if (console && console.warn) {
          console.warn('[records-db] applyToProfile in _autoJsonFlatten error:', e);
        }
      }
    }

    var profile = this._wizard._schemaProfile || {};
    var mappings = Array.isArray(profile.fieldMappings) ? profile.fieldMappings : [];
    if (!mappings.length) {
      if (status) {
        status.textContent = '';
      }
      return;
    }

    // Find first JSON column without jsonPath
    var jsonCol = null;
    mappings.forEach(function(m) {
      if (jsonCol || !m || !m.columnName) {
        return;
      }
      var dt = (m.dataType || '').toLowerCase();
      var isJson = dt === 'json' || m.isJsonField === true;
      if (isJson && !m.jsonPath) {
        jsonCol = m.columnName;
      }
    });

    if (!jsonCol) {
      if (status) {
        status.textContent = (i18n.t('records.db.wizard.fieldMapping.noJsonColumns') || '  JSON ');
      }
      return;
    }

    var baseProfile = JSON.parse(JSON.stringify(profile || {}));
    var payload = {
      "schemaProfile": JSON.stringify(baseProfile),
      "source": "main",
      "field": jsonCol
    };

    var handleResponse = function(data) {
      if (status) {
        status.textContent = '';
      }
      var keys = [];
      if (data && data.status === 'ok' && Array.isArray(data.values)) {
        for (var i = 0; i < data.values.length; i++) {
          var v = data.values[i];
          if (!v) {
            continue;
          }
          try {
            var obj = typeof v === 'string' ? JSON.parse(v) : v;
            if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
              keys = Object.keys(obj);
              break;
            }
          } catch (e) {
            // ignore parse errors
          }
        }
      }

      if (!keys.length) {
        if (status) {
          status.textContent = (i18n.t('records.db.wizard.fieldMapping.noJsonKeysFound') || '  JSON ');
        }
        return;
      }

      var prof = self._wizard._schemaProfile || {};
      var existingMappings = Array.isArray(prof.fieldMappings) ? prof.fieldMappings : [];
      var existing = {};
      existingMappings.forEach(function(m) {
        if (m && m.columnName && m.jsonPath) {
          existing[m.columnName + '||' + m.jsonPath] = true;
        }
      });

      keys.forEach(function(k) {
        if (!k) {
          return;
        }
        var key = String(k);
        var id = jsonCol + '||' + key;
        if (existing[id]) {
          return;
        }
        var label = jsonCol + '.' + key;
        existingMappings.push({
          columnName: jsonCol,
          columnLabel: label,
          dataType: 'string',
          jsonField: true,
          isJsonField: true,
          jsonPath: key
        });
      });

      prof.fieldMappings = existingMappings;
      self._wizard._schemaProfile = prof;
      self._loadFieldMappings();
    };

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "distinct-values",
            "csrf_token": token
          }),
          payload,
          function(data) { handleResponse(data); },
          "json"
        );
      });
    } else {
      var params = new URLSearchParams();
      Object.keys(payload).forEach(function(k) { params.set(k, payload[k]); });
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=distinct-values', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      .then(function(r) { return r.json(); })
      .then(handleResponse)
      .catch(function() {
        if (status) {
          status.textContent = (i18n.t('records.db.common.loadFailed') || ' ');
        }
      });
    }
  };

  */

  // New _autoJsonFlatten implementation
  FieldMappingStep.prototype._autoJsonFlatten = function() {
    var self = this;
    var status = document.getElementById('json-status');
    if (status) {
      status.textContent = (i18n.t('records.db.common.loading') || '加载中') + '...';
    }

    // Persist current UI to profile
    if (typeof this.applyToProfile === 'function') {
      try {
        this.applyToProfile();
      } catch (e) {
        if (console && console.warn) {
          console.warn('[records-db] applyToProfile in _autoJsonFlatten error:', e);
        }
      }
    }

    // Ensure Step 4 (ConfigureFiltersStep) has applied its filters to the profile
    if (this._wizard && this._wizard._steps && this._wizard._steps[3]) {
      var filterStep = this._wizard._steps[3];
      if (filterStep && typeof filterStep.applyToProfile === 'function') {
        try {
          filterStep.applyToProfile();
        } catch (e) {
          if (console && console.warn) {
            console.warn('[records-db] applyToProfile from ConfigureFiltersStep error:', e);
          }
        }
      }
    }

    var profile = this._wizard._schemaProfile || {};
    var mappings = Array.isArray(profile.fieldMappings) ? profile.fieldMappings : [];
    if (!mappings.length) {
      if (status) {
        status.textContent = '';
      }
      return;
    }

    // Find first JSON column without jsonPath
    var jsonCol = null;
    mappings.forEach(function(m) {
      if (jsonCol || !m || !m.columnName) {
        return;
      }
      var dt = (m.dataType || '').toLowerCase();
      var isJson = dt === 'json' || m.isJsonField === true;
      if (isJson && !m.jsonPath) {
        jsonCol = m.columnName;
      }
    });

    if (!jsonCol) {
      if (status) {
        status.textContent = (i18n.t('records.db.wizard.fieldMapping.noJsonColumns') || '没有可用的 JSON 列');
      }
      return;
    }

    var baseProfile = JSON.parse(JSON.stringify(profile || {}));
    var payload = {
      "schemaProfile": JSON.stringify(baseProfile),
      "source": "main",
      "field": jsonCol
    };

    var handleResponse = function(data) {
      if (status) {
        status.textContent = '';
      }
      var keys = [];
      if (data && data.status === 'ok' && Array.isArray(data.values)) {
        for (var i = 0; i < data.values.length; i++) {
          var v = data.values[i];
          if (!v) {
            continue;
          }
          try {
            var obj = typeof v === 'string' ? JSON.parse(v) : v;
            if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
              keys = Object.keys(obj);
              break;
            }
          } catch (e) {
            // ignore parse errors
          }
        }
      }

      if (!keys.length) {
        if (status) {
          status.textContent = (i18n.t('records.db.wizard.fieldMapping.noJsonKeysFound') || '未找到可用的 JSON 键');
        }
        return;
      }

      var prof = self._wizard._schemaProfile || {};
      var existingMappings = Array.isArray(prof.fieldMappings) ? prof.fieldMappings : [];
      var existing = {};
      existingMappings.forEach(function(m) {
        if (m && m.columnName && m.jsonPath) {
          existing[m.columnName + '||' + m.jsonPath] = true;
        }
      });

      keys.forEach(function(k) {
        if (!k) {
          return;
        }
        var key = String(k);
        var id = jsonCol + '||' + key;
        if (existing[id]) {
          return;
        }
        var label = jsonCol + '.' + key;
        existingMappings.push({
          columnName: jsonCol,
          columnLabel: label,
          dataType: 'string',
          jsonField: true,
          isJsonField: true,
          jsonPath: key
        });
      });

      prof.fieldMappings = existingMappings;
      self._wizard._schemaProfile = prof;
      self._loadFieldMappings();
    };

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "distinct-values",
            "csrf_token": token
          }),
          payload,
          function(data) { handleResponse(data); },
          "json"
        );
      });
    } else {
      var params = new URLSearchParams();
      Object.keys(payload).forEach(function(k) { params.set(k, payload[k]); });
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=distinct-values', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      .then(function(r) { return r.json(); })
      .then(handleResponse)
      .catch(function() {
        if (status) {
          status.textContent = (i18n.t('records.db.common.loadFailed') || '加载失败');
        }
      });
    }
  };


  // Populate dictionary tables select
  FieldMappingStep.prototype._populateDictTables = function() {
    var self = this;
    var sel = document.getElementById('dict-table-select');
    if (!sel) return;
    var profile = this._wizard._schemaProfile || {};
    // ensure Step 2 saved
    if (this._wizard && this._wizard._steps && this._wizard._steps[1] && typeof this._wizard._steps[1].applyToProfile === 'function') {
      try { this._wizard._steps[1].applyToProfile(); profile = this._wizard._schemaProfile || profile; } catch (e) {}
    }
    sel.innerHTML = '';
    var loadingOpt = document.createElement('option');
    loadingOpt.value = '';
    loadingOpt.textContent = (i18n.t('records.db.common.loading') || 'Loading') + '...';
    sel.appendChild(loadingOpt);

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-tables",
            "csrf_token": token
          }),
          { "schemaProfile": JSON.stringify(profile) },
          function(data) {
            sel.innerHTML = '';
            if (data && data.status === 'ok' && Array.isArray(data.tables)) {
              data.tables.forEach(function(t) {
                var name = t.name || t;
                var label = t.label || name;
                var opt = document.createElement('option');
                opt.value = name; opt.textContent = label;
                sel.appendChild(opt);
              });
              // Prefer project_bind_field if exists
              for (var i = 0; i < sel.options.length; i++) {
                if ((sel.options[i].value || '').toLowerCase() === 'project_bind_field') { sel.value = sel.options[i].value; break; }
              }
              sel.dataset.loaded = 'true';
              self._populateDictColumns();
            }
          },
          "json"
        );
      });
    }
  };

  // Populate dictionary columns selects based on selected dict table
  FieldMappingStep.prototype._populateDictColumns = function() {
    var dictTable = document.getElementById('dict-table-select');
    var codeSel = document.getElementById('dict-code-column-select');
    var nameSel = document.getElementById('dict-name-column-select');
    var bindSel = document.getElementById('dict-bind-column-select');
    if (!dictTable || !dictTable.value) return;
    var profile = JSON.parse(JSON.stringify(this._wizard._schemaProfile || {}));
    profile.mainTable = dictTable.value; // temporarily query dict table columns

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-columns",
            "csrf_token": token
          }),
          { "schemaProfile": JSON.stringify(profile) },
          function(data) {
            function fill(selectEl, def) {
              if (!selectEl) return;
              selectEl.innerHTML = '';
              if (data && data.status === 'ok' && Array.isArray(data.columns)) {
                data.columns.forEach(function(c) {
                  var opt = document.createElement('option'); opt.value = c.name; opt.textContent = c.name; selectEl.appendChild(opt);
                });
                var lower = (def || '').toLowerCase();
                for (var i = 0; i < selectEl.options.length; i++) {
                  if ((selectEl.options[i].value || '').toLowerCase() === lower) { selectEl.value = selectEl.options[i].value; break; }
                }
              }
            }
            fill(codeSel, 'code');
            fill(nameSel, 'name');
            fill(bindSel, 'bind_table_id');
            var loadBtn = document.getElementById('load-dict-fields');
            if (loadBtn) loadBtn.disabled = !(codeSel && codeSel.options.length && nameSel && nameSel.options.length);
          },
          "json"
        );
      });
    }
  };

  // Populate JSON column select from main table columns
  FieldMappingStep.prototype._populateJsonColumn = function() {
    var sel = document.getElementById('json-column-select');
    if (!sel) return;
    sel.innerHTML='';
    var cols = (this._wizard._schemaProfile && this._wizard._schemaProfile.columns) || [];
    cols.forEach(function(c){ var opt=document.createElement('option'); opt.value=c.name; opt.textContent=c.name; sel.appendChild(opt); });
    for (var i=0;i<sel.options.length;i++){ if ((sel.options[i].value||'').toLowerCase()==='field_data'){ sel.value = sel.options[i].value; break; } }
  };

  // Load dictionary fields and append to profile.fieldMappings as JSON extractions (Plan B)
  FieldMappingStep.prototype._loadDictionaryFields = function() {
    var self = this;
    var status = document.getElementById('dict-status');
    if (status) status.textContent = (i18n.t('records.db.common.loading') || 'Loading') + '...';
    var dictTable = document.getElementById('dict-table-select')?.value || '';
    var codeCol = document.getElementById('dict-code-column-select')?.value || '';
    var nameCol = document.getElementById('dict-name-column-select')?.value || '';
    var bindCol = document.getElementById('dict-bind-column-select')?.value || '';
    var bindVal = document.getElementById('dict-bind-value')?.value || '';
    var jsonCol = document.getElementById('json-column-select')?.value || 'field_data';
    if (!dictTable || !codeCol || !nameCol || !jsonCol) {
      if (status) status.textContent = (i18n.t('records.db.common.missingRequired') || 'Missing required fields');
      return;
    }

    var profile = this._wizard._schemaProfile || {};

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        var payload = {
          "schemaProfile": JSON.stringify(profile),
          "dictTable": dictTable,
          "codeColumn": codeCol,
          "nameColumn": nameCol
        };
        if (bindCol) payload["bindColumn"] = bindCol;
        if (bindVal) payload["bindValue"] = bindVal;

        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-dictionary",
            "csrf_token": token
          }),
          payload,
          function(data) {
            if (data && data.status === 'ok' && Array.isArray(data.items)) {
              var mappings = (self._wizard._schemaProfile.fieldMappings || []).slice(0);
              data.items.forEach(function(it) {
                mappings.push({
                  columnName: jsonCol,
                  columnLabel: it.name,
                  dataType: 'string',
                  jsonField: true,
                  isJsonField: true,
                  jsonPath: it.code
                });
              });
              self._wizard._schemaProfile.fieldMappings = mappings;
              if (status) status.textContent = (i18n.t('records.db.wizard.fieldMapping.loadedCount') || 'Loaded {0} fields').replace('{0}', String(data.items.length));
            } else {
              if (status) status.textContent = (data && data.message) ? data.message : (i18n.t('records.db.common.loadFailed') || 'Load failed');
            }
          },
          "json"
        );
      });
    }
  };


  /**
   * Step 4: File Mapping (Simplified)
   */
  window.FileMappingStep = function(wizard) {
    this._wizard = wizard;
    this._fileMapping = {};
    this._availableSources = []; // Will be populated from filters
  };

  FileMappingStep.prototype.render = function(div) {
    var self = this;
    var html = '<div class="records-db-step">';
    html += '<h3>' + (i18n.t('records.db.wizard.fileMapping.title') || '文件路径映射') + '</h3>';
    html += '<p>' + (i18n.t('records.db.wizard.fileMapping.description') || '配置文件路径字段和根路径') + '</p>';

    html += '<div class="file-mapping-form">';

    // Root path (optional)
    html += '<div class="form-group">';
    html += '<label>' + (i18n.t('records.db.wizard.fileMapping.rootPath') || '根路径 (可选)') + ':</label>';
    html += '<input type="text" id="file-root-path" placeholder="/home/kubao/scanFiles or D:\\scanFiles">';
    html += '<small>' + (i18n.t('records.db.wizard.fileMapping.rootPathHint') || '留空表示数据库字段包含完整路径') + '</small>';
    html += '</div>';

    // Source selector (main / exportedJoin / join)
    html += '<div class="form-group">';
    html += '<label>' + (i18n.t('records.db.wizard.fileMapping.source') || '路径字段来源') + ' *:</label>';
    html += '<select id="file-path-source">';
    html += '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectSource') || '选择来源') + ' --</option>';
    html += '<option value="main">' + (i18n.t('records.db.wizard.fileMapping.mainTable') || '主表') + '</option>';
    html += '</select>';
    html += '</div>';

    // Join table selector (only visible when source=join)
    html += '<div class="form-group" id="join-table-group" style="display:none;">';
    html += '<label>' + (i18n.t('records.db.wizard.fileMapping.joinTable') || '关联表') + ' *:</label>';
    html += '<select id="file-path-join-table">';
    html += '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectJoinTable') || '选择关联表') + ' --</option>';
    html += '</select>';
    html += '</div>';

    // Field selector
    html += '<div class="form-group">';
    html += '<label>' + (i18n.t('records.db.wizard.fileMapping.field') || '路径字段') + ' *:</label>';
    html += '<select id="file-path-field">';
    html += '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectField') || '选择字段') + ' --</option>';
    html += '</select>';
    html += '</div>';

    // Column label
    html += '<div class="form-group">';
    html += '<label>' + (i18n.t('records.db.wizard.fileMapping.columnLabel') || '列名') + ':</label>';
    html += '<input type="text" id="file-path-column-label" placeholder="file_path" value="file_path">';
    html += '</div>';

    html += '</div>';

    html += '</div>';
    div.innerHTML = html;

    // Load file mapping from schema profile
    this._loadFileMapping();

    // Attach event handlers
    document.getElementById('file-path-source').onchange = function() {
      self._onSourceChange();
    };
    document.getElementById('file-path-join-table').onchange = function() {
      self._onJoinTableChange();
    };
  };

  FileMappingStep.prototype._loadFileMapping = function() {
    var self = this;
    var fileMapping = this._wizard._schemaProfile?.fileMapping || {};

    // Populate available sources from filters
    this._populateAvailableSources();

    // Load values
    document.getElementById('file-root-path').value = fileMapping.rootPath || '';
    document.getElementById('file-path-column-label').value = fileMapping.columnLabel || 'file_path';

    if (fileMapping.source) {
      document.getElementById('file-path-source').value = fileMapping.source;
      this._onSourceChange();

      // Load join table if source is join
      if (fileMapping.source === 'join' && fileMapping.joinTable) {
        setTimeout(function() {
          document.getElementById('file-path-join-table').value = fileMapping.joinTable + '|' + fileMapping.mainKey + '|' + fileMapping.joinKey;
          self._onJoinTableChange();

          // Load field
          if (fileMapping.field) {
            setTimeout(function() {
              document.getElementById('file-path-field').value = fileMapping.field;
            }, 100);
          }
        }, 100);
      } else if (fileMapping.field) {
        // Load field for main or exportedJoin
        setTimeout(function() {
          document.getElementById('file-path-field').value = fileMapping.field;
        }, 100);
      }
    }
  };

  FileMappingStep.prototype._populateAvailableSources = function() {
    var profile = this._wizard._schemaProfile || {};
    var filters = profile.filters || {};
    var sourceSelect = document.getElementById('file-path-source');

    // Check if exportedJoin is configured
    if (filters.exportedJoin && filters.exportedJoin.joinTable) {
      var option = document.createElement('option');
      option.value = 'exportedJoin';
      option.text = (i18n.t('records.db.wizard.fileMapping.exportedJoinTable') || '排除表') + ' (' + filters.exportedJoin.joinTable + ')';
      sourceSelect.appendChild(option);
    }

    // Check if custom joins are configured
    if (filters.conditions && Array.isArray(filters.conditions)) {
      var hasJoins = false;
      for (var i = 0; i < filters.conditions.length; i++) {
        var cond = filters.conditions[i];
        if (cond.source === 'join' && cond.joinTable) {
          if (!hasJoins) {
            var option = document.createElement('option');
            option.value = 'join';
            option.text = i18n.t('records.db.wizard.fileMapping.customJoinTable') || '自定义关联表';
            sourceSelect.appendChild(option);
            hasJoins = true;
          }
        }
      }
    }
  };

  FileMappingStep.prototype._onSourceChange = function() {
    var source = document.getElementById('file-path-source').value;
    var joinTableGroup = document.getElementById('join-table-group');
    var fieldSelect = document.getElementById('file-path-field');

    // Clear field options
    fieldSelect.innerHTML = '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectField') || '选择字段') + ' --</option>';

    if (source === 'join') {
      // Show join table selector and populate it
      joinTableGroup.style.display = 'block';
      this._populateJoinTables();
    } else {
      // Hide join table selector
      joinTableGroup.style.display = 'none';

      // Load fields for main or exportedJoin
      if (source === 'main') {
        this._loadFieldsForTable(this._wizard._schemaProfile?.mainTable);
      } else if (source === 'exportedJoin') {
        var filters = this._wizard._schemaProfile?.filters || {};
        if (filters.exportedJoin && filters.exportedJoin.joinTable) {
          this._loadFieldsForTable(filters.exportedJoin.joinTable);
        }
      }
    }
  };

  FileMappingStep.prototype._populateJoinTables = function() {
    var profile = this._wizard._schemaProfile || {};
    var filters = profile.filters || {};
    var joinTableSelect = document.getElementById('file-path-join-table');

    joinTableSelect.innerHTML = '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectJoinTable') || '选择关联表') + ' --</option>';

    if (filters.conditions && Array.isArray(filters.conditions)) {
      var addedJoins = {};
      for (var i = 0; i < filters.conditions.length; i++) {
        var cond = filters.conditions[i];
        if (cond.source === 'join' && cond.joinTable && cond.mainKey && cond.joinKey) {
          var key = cond.joinTable + '|' + cond.mainKey + '|' + cond.joinKey;
          if (!addedJoins[key]) {
            var option = document.createElement('option');
            option.value = key;
            option.text = cond.joinTable + ' (ON ' + cond.mainKey + '=' + cond.joinKey + ')';
            joinTableSelect.appendChild(option);
            addedJoins[key] = true;
          }
        }
      }
    }
  };

  FileMappingStep.prototype._onJoinTableChange = function() {
    var joinTableValue = document.getElementById('file-path-join-table').value;
    if (joinTableValue) {
      var parts = joinTableValue.split('|');
      var joinTable = parts[0];
      this._loadFieldsForTable(joinTable);
    }
  };

  FileMappingStep.prototype._loadFieldsForTable = function(tableName) {
    var self = this;
    var fieldSelect = document.getElementById('file-path-field');

    if (!tableName) {
      return;
    }

    // Build a temporary profile with just the table we want to query
    var profile = this._wizard._schemaProfile || {};
    var tempProfile = {
      dialect: profile.dialect || 'postgresql',
      host: profile.host || '',
      port: profile.port || 5432,
      database: profile.database || '',
      username: profile.username || '',
      password: profile.password || '',
      mainTable: tableName
    };

    // Call list-columns endpoint via POST with schemaProfile
    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-columns",
            "csrf_token": token
          }),
          {
            "schemaProfile": JSON.stringify(tempProfile)
          },
          function(data) {
            if (data.status === 'ok' && data.columns) {
              fieldSelect.innerHTML = '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectField') || '选择字段') + ' --</option>';
              data.columns.forEach(function(col) {
                var option = document.createElement('option');
                option.value = col.name;
                option.text = col.name + ' (' + col.type + ')';
                fieldSelect.appendChild(option);
              });
            } else {
              fieldSelect.innerHTML = '<option value="">Error loading fields</option>';
            }
          },
          "json"
        ).fail(function(jqXHR, textStatus, errorThrown) {
          console.error('Error loading fields:', textStatus, errorThrown);
          fieldSelect.innerHTML = '<option value="">Error loading fields</option>';
        });
      });
    } else {
      // Fallback for non-jQuery environments
      var params = new URLSearchParams();
      params.set('schemaProfile', JSON.stringify(tempProfile));
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=list-columns', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
        .then(function(response) { return response.json(); })
        .then(function(data) {
          if (data.status === 'ok' && data.columns) {
            fieldSelect.innerHTML = '<option value="">-- ' + (i18n.t('records.db.wizard.fileMapping.selectField') || '选择字段') + ' --</option>';
            data.columns.forEach(function(col) {
              var option = document.createElement('option');
              option.value = col.name;
              option.text = col.name + ' (' + col.type + ')';
              fieldSelect.appendChild(option);
            });
          } else {
            fieldSelect.innerHTML = '<option value="">Error loading fields</option>';
          }
        })
        .catch(function(error) {
          console.error('Error loading fields:', error);
          fieldSelect.innerHTML = '<option value="">Error loading fields</option>';
        });
    }
  };

  // Persist file mapping when moving forward/backward
  FileMappingStep.prototype.applyToProfile = function() {
    var profile = this._wizard._schemaProfile || {};
    profile.fileMapping = this.getFileMapping();
    this._wizard._schemaProfile = profile;
  };

  FileMappingStep.prototype.getFileMapping = function() {
    var source = document.getElementById('file-path-source').value;
    var field = document.getElementById('file-path-field').value;

    if (!source || !field) {
      return {}; // Return empty if required fields not filled
    }

    var mapping = {
      rootPath: document.getElementById('file-root-path').value || '',
      source: source,
      field: field,
      columnLabel: document.getElementById('file-path-column-label').value || 'file_path'
    };

    // Add join info if source is join
    if (source === 'join') {
      var joinTableValue = document.getElementById('file-path-join-table').value;
      if (joinTableValue) {
        var parts = joinTableValue.split('|');
        mapping.joinTable = parts[0];
        mapping.mainKey = parts[1];
        mapping.joinKey = parts[2];
      }
    }

    return mapping;
  };

})();

