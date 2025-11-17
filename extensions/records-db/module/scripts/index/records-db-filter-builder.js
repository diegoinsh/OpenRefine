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
    this._joinColumns = []; // cached join table columns for custom conditions
  };

  FilterConditionBuilder.prototype.render = function(div) {
    var self = this;
    var html = '<div class="filter-builder">';

    // Exclude exported checkbox
    html += '<div class="filter-option">';
    html += '<label><input type="checkbox" id="exclude-exported" class="filter-checkbox"> ' + i18n.t('records.db.wizard.configureFilters.excludeExported') + '</label>';
    html += '<div id="exported-filter-config" class="filter-config" style="display:none;">';
    html +=   '<div class="form-group">'
            + '  <label>' + i18n.t('records.db.wizard.configureFilters.exportJoinTable') + '</label>'
            + '  <select id="join-table-select" class="filter-input"></select>'
            + '</div>'
            + '<div class="form-group">'
            + '  <label>' + i18n.t('records.db.wizard.configureFilters.exportMainKey') + '</label>'
            + '  <select id="main-join-column" class="filter-input"></select>'
            + '</div>'
            + '<div class="form-group">'
            + '  <label>' + i18n.t('records.db.wizard.configureFilters.exportJoinKey') + '</label>'
            + '  <select id="join-join-column" class="filter-input"></select>'
            + '</div>'
            + '<div class="form-group">'
            + '  <label>' + i18n.t('records.db.wizard.configureFilters.exportFlagField') + '</label>'
            + '  <select id="export-flag-field" class="filter-input"></select>'
            + '</div>'
            + '<div class="form-group">'
            + '  <label>' + i18n.t('records.db.wizard.configureFilters.notExportedValue') + '</label>'
            + '  <input type="text" id="export-flag-value" class="filter-input" value="0" placeholder="0">'
            + '</div>'
            + '<div class="form-group">'
            + '  <label><input type="checkbox" id="export-flag-null" checked> ' + i18n.t('records.db.wizard.configureFilters.treatNullAsNotExported') + '</label>'
            + '</div>';
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


  // Initialize exported join config (load tables and columns)
  FilterConditionBuilder.prototype._initExportedConfig = function(existingEj) {
    var self = this;
    existingEj = existingEj || {};
    // Populate main table columns from cached profile columns
    var mainCols = (this._wizard._schemaProfile && this._wizard._schemaProfile.columns) || [];
    var mainSelect = document.getElementById('main-join-column');
    if (mainSelect) {
      mainSelect.innerHTML = '';
      mainCols.forEach(function(c){
        var opt = document.createElement('option');
        opt.value = c.name; opt.textContent = c.name;
        if (existingEj.mainColumn && c.name === existingEj.mainColumn) {
          opt.selected = true;
        } else if (!existingEj.mainColumn && (c.name || '').toLowerCase() === 'book_id') {
          opt.selected = true;
        }
        mainSelect.appendChild(opt);
      });
    }
    // Load join tables and populate
    this._loadJoinTables(function(){
      // Try set defaults / restored value
      var jt = document.getElementById('join-table-select');
      if (!jt) {
        self._updateFilterPreview();
        return;
      }
      if (existingEj.joinTable) {
        jt.value = existingEj.joinTable;
      } else if (!jt.value) {
        // Prefer file_book if exists
        for (var i=0;i<jt.options.length;i++){
          if ((jt.options[i].value||'').toLowerCase()==='file_book'){
            jt.value = jt.options[i].value;
            break;
          }
        }
      }
      // Load join columns for selected table
      var chosen = jt.value || '';
      if (chosen) {
        self._loadJoinColumns(chosen, existingEj);
      } else {
        self._updateFilterPreview();
      }
    });

    // Initialize flag value / includeNull from existingEj (if any)
    var flagValInit = document.getElementById('export-flag-value');
    if (flagValInit && typeof existingEj.value !== 'undefined') {
      flagValInit.value = String(existingEj.value);
    }
    var flagNullInit = document.getElementById('export-flag-null');
    if (flagNullInit) {
      flagNullInit.checked = !!existingEj.includeNull;
    }

    // Attach change handlers
    var jtSel = document.getElementById('join-table-select');
    if (jtSel) {
      jtSel.onchange = function(){ self._loadJoinColumns(this.value); };
    }
    var mjSel = document.getElementById('main-join-column');
    if (mjSel) { mjSel.onchange = function(){ self._updateFilterPreview(); }; }
    var jjSel = document.getElementById('join-join-column');
    if (jjSel) { jjSel.onchange = function(){ self._updateFilterPreview(); }; }
    var flagSel = document.getElementById('export-flag-field');
    if (flagSel) { flagSel.onchange = function(){ self._updateFilterPreview(); }; }
    var flagVal = document.getElementById('export-flag-value');
    if (flagVal) { flagVal.oninput = function(){ self._updateFilterPreview(); }; }
    var flagNull = document.getElementById('export-flag-null');
    if (flagNull) { flagNull.onchange = function(){ self._updateFilterPreview(); }; }
  };

  // Load tables from server
  FilterConditionBuilder.prototype._loadJoinTables = function(done) {
    var self = this;
    var profile = this._wizard._schemaProfile || {};
    var fill = function(data){
      try {
        var sel = document.getElementById('join-table-select');
        if (!sel) return;
        sel.innerHTML = '';
        if (data && data.status === 'ok' && Array.isArray(data.tables)) {
          data.tables.forEach(function(t){
            var name = t.name || t;
            var label = t.label || name;
            var opt = document.createElement('option');
            opt.value = name; opt.textContent = label; sel.appendChild(opt);
          });
        }
      } finally { if (typeof done === 'function') done(); }
    };

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-tables",
            "csrf_token": token
          }),
          { "schemaProfile": JSON.stringify(profile) },
          function(data){ fill(data); },
          'json'
        );
      });
    } else {
      var params = new URLSearchParams();
      params.set('schemaProfile', JSON.stringify(profile));
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=list-tables', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
      }).then(function(r){ return r.json(); }).then(fill).catch(function(){ fill(null); });
    }
  };

  // Load columns for a chosen join table
  FilterConditionBuilder.prototype._loadJoinColumns = function(joinTable, existingEj) {
    var self = this;
    if (!joinTable) {
      self._joinColumns = [];
      self._updateFilterPreview();
      return;
    }
    existingEj = existingEj || {};
    var profile = JSON.parse(JSON.stringify(this._wizard._schemaProfile || {}));
    // Do not mutate mainTable in wizard; use a cloned profile
    profile.mainTable = joinTable;

    var fill = function(data){
      var joinKeySel = document.getElementById('join-join-column');
      var flagSel = document.getElementById('export-flag-field');
      if (data && data.status === 'ok' && Array.isArray(data.columns)) {
        self._joinColumns = data.columns.map(function(c){ return c.name; });
      } else {
        self._joinColumns = [];
      }
      if (joinKeySel && flagSel) {
        joinKeySel.innerHTML = ''; flagSel.innerHTML = '';
        if (data && data.status === 'ok' && Array.isArray(data.columns)) {
          data.columns.forEach(function(c){
            var o1 = document.createElement('option'); o1.value = c.name; o1.textContent = c.name; joinKeySel.appendChild(o1);
            var o2 = document.createElement('option'); o2.value = c.name; o2.textContent = c.name; flagSel.appendChild(o2);
          });
          // Defaults: prefer restored values; otherwise id for join key and exported for flag field
          if (existingEj.joinColumn) {
            joinKeySel.value = existingEj.joinColumn;
          } else {
            for (var i=0;i<joinKeySel.options.length;i++){
              if ((joinKeySel.options[i].value||'').toLowerCase()==='id'){
                joinKeySel.value = joinKeySel.options[i].value;
                break;
              }
            }
          }
          if (existingEj.flagField) {
            flagSel.value = existingEj.flagField;
          } else {
            for (var j=0;j<flagSel.options.length;j++){
              if ((flagSel.options[j].value||'').toLowerCase()==='exported'){
                flagSel.value = flagSel.options[j].value;
                break;
              }
            }
          }
        }
      }
      self._updateFilterPreview();
    };

    if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
      Refine.wrapCSRF(function(token) {
        $.post(
          "command/core/importing-controller?" + $.param({
            "controller": "records-db/records-db-import-controller",
            "subCommand": "list-columns",
            "csrf_token": token
          }),
          { "schemaProfile": JSON.stringify(profile) },
          function(data){ fill(data); },
          'json'
        );
      });
    } else {
      var params = new URLSearchParams();
      params.set('schemaProfile', JSON.stringify(profile));
      fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=list-columns', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
      }).then(function(r){ return r.json(); }).then(fill).catch(function(){ fill(null); });
    }
  };

    document.getElementById('add-condition').onclick = function() {
      self._addCondition();
    };
  };

  FilterConditionBuilder.prototype._toggleExportedFilter = function(existingEj) {
    var checkbox = document.getElementById('exclude-exported');
    var config = document.getElementById('exported-filter-config');

    if (checkbox && checkbox.checked) {
      if (config) {
        config.style.display = 'block';
      }
      // Lazy-init join configuration when enabled, honoring any existing exportedJoin config
      this._initExportedConfig(existingEj);
    } else {
      if (config) {
        config.style.display = 'none';
      }
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

    var row = document.createElement('div');
    row.className = 'condition-row';

    // Source select: main or join
    var sourceSel = document.createElement('select');
    sourceSel.className = 'condition-source';
    sourceSel.setAttribute('data-id', String(conditionId));
    var optMain = document.createElement('option'); optMain.value = 'main'; optMain.textContent = (i18n.t('records.db.wizard.configureFilters.sourceMain') || '主表');
    var optJoin = document.createElement('option'); optJoin.value = 'join'; optJoin.textContent = (i18n.t('records.db.wizard.configureFilters.sourceJoin') || '关联表');
    sourceSel.appendChild(optMain); sourceSel.appendChild(optJoin);
    row.appendChild(sourceSel);

    // Join table select (visible only when source = join)
    var joinTableSel = document.createElement('select');
    joinTableSel.className = 'condition-join-table';
    joinTableSel.style.display = 'none';
    row.appendChild(joinTableSel);

    // Per-condition join columns cache
    var condJoinColumns = [];

    // Join key selects (visible only when source = join)
    var mainKeySel = document.createElement('select');
    mainKeySel.className = 'condition-main-key';
    mainKeySel.style.display = 'none';
    row.appendChild(mainKeySel);

    var joinKeySel = document.createElement('select');
    joinKeySel.className = 'condition-join-key';
    joinKeySel.style.display = 'none';
    row.appendChild(joinKeySel);

    // Helper: fill join table options by copying from global join selector
    var fillJoinTableOptions = function(){
      // ensure exported join config is initialized so global selector exists and is populated
      var jtGlobal = document.getElementById('join-table-select');
      if (!jtGlobal || !jtGlobal.options.length) {
        self._initExportedConfig();
        jtGlobal = document.getElementById('join-table-select');
      }
      joinTableSel.innerHTML = '';
      var ph = document.createElement('option'); ph.value = ''; ph.textContent = (i18n.t('records.db.wizard.configureFilters.selectJoinTable') || '选择关联表');
      joinTableSel.appendChild(ph);
      if (jtGlobal) {
        for (var i=0; i<jtGlobal.options.length; i++) {
          var srcOp = jtGlobal.options[i];
          var op = document.createElement('option');
          op.value = srcOp.value; op.textContent = srcOp.textContent;
          joinTableSel.appendChild(op);
        }
        // Default to the global join-table selection if present
        if (jtGlobal.value) {
          joinTableSel.value = jtGlobal.value;
          // Preload columns for default join table and refresh fields shortly after
          try { self._loadJoinColumns(joinTableSel.value); } catch(e){}
          setTimeout(function(){ if (sourceSel.value === 'join') { try { condJoinColumns = (self._joinColumns||[]).slice(); populateFields(); fillJoinKeyOptions(); } catch(e){} } }, 120);
        }
      }
    };

    var fillMainKeyOptions = function(){
      mainKeySel.innerHTML = '';
      var ph = document.createElement('option'); ph.value = ''; ph.textContent = (i18n.t('records.db.wizard.configureFilters.exportMainKey') || '主表关联字段'); mainKeySel.appendChild(ph);
      var fields = (self._wizard._schemaProfile && self._wizard._schemaProfile.columns) || [];
      fields.forEach(function(col){ var o=document.createElement('option'); o.value=col.name; o.textContent=col.name; mainKeySel.appendChild(o); });
      // default
      for (var i=0;i<mainKeySel.options.length;i++){
        var v=(mainKeySel.options[i].value||'').toLowerCase(); if (v==='book_id'||v==='bookid') { mainKeySel.value = mainKeySel.options[i].value; break; }
      }
    };

    var fillJoinKeyOptions = function(){
      joinKeySel.innerHTML = '';
      var ph = document.createElement('option'); ph.value = ''; ph.textContent = (i18n.t('records.db.wizard.configureFilters.exportJoinKey') || '关联表关联字段'); joinKeySel.appendChild(ph);
      (condJoinColumns || []).forEach(function(n){ var o=document.createElement('option'); o.value=n; o.textContent=n; joinKeySel.appendChild(o); });
      for (var j=0;j<joinKeySel.options.length;j++){ if ((joinKeySel.options[j].value||'').toLowerCase()==='id'){ joinKeySel.value = joinKeySel.options[j].value; break; } }
    };

    // Field select (populated based on source)
    var fieldSel = document.createElement('select');
    fieldSel.className = 'condition-field'; fieldSel.setAttribute('data-id', String(conditionId));
    row.appendChild(fieldSel);

    // Operator select
    var opSel = document.createElement('select'); opSel.className = 'condition-operator'; opSel.setAttribute('data-id', String(conditionId));
    ['=','!=','>','<','>=','<=','LIKE','IN'].forEach(function(v){ var o=document.createElement('option'); o.value=v; o.textContent=v; opSel.appendChild(o); });
    row.appendChild(opSel);

    // Value select (distinct values)
    var valSel = document.createElement('select');
    valSel.className='condition-value'; valSel.setAttribute('data-id', String(conditionId));
    valSel.style.minWidth = '160px';
    row.appendChild(valSel);

    var setValPlaceholder = function(text){
      valSel.innerHTML = '';
      var op = document.createElement('option'); op.value=''; op.textContent = text || (i18n.t('records.db.wizard.configureFilters.selectValue') || '选择值'); valSel.appendChild(op);
    };

    var loadDistinct = function(){
      var field = fieldSel.value;
      if (!field) { setValPlaceholder(i18n.t('records.db.wizard.configureFilters.selectValue') || '选择值'); return; }
      setValPlaceholder(i18n.t('records.db.common.loading') || '加载中...');
      var profile = JSON.parse(JSON.stringify(self._wizard._schemaProfile || {}));
      var src = sourceSel.value === 'join' ? 'join' : 'main';
      var jt = src==='join' ? (joinTableSel.value || '') : '';
      var fill = function(data){
        valSel.innerHTML='';
        var ph = document.createElement('option'); ph.value=''; ph.textContent=(i18n.t('records.db.wizard.configureFilters.selectValue')||'选择值'); valSel.appendChild(ph);
        if (data && data.status==='ok' && Array.isArray(data.values)){
          data.values.forEach(function(v){ var o=document.createElement('option'); o.value=String(v); o.textContent=String(v); valSel.appendChild(o); });
        }
        self._updateFilterPreview();
      };
      var payload = { "schemaProfile": JSON.stringify(profile), "source": src, "field": field };
      if (jt) payload.joinTable = jt;
      if (typeof Refine !== 'undefined' && typeof Refine.wrapCSRF === 'function' && typeof $ !== 'undefined') {
        Refine.wrapCSRF(function(token){
          $.post(
            "command/core/importing-controller?" + $.param({
              "controller": "records-db/records-db-import-controller",
              "subCommand": "distinct-values",
              "csrf_token": token
            }),
            payload,
            function(data){ fill(data); },
            'json'
          );
        });
      } else {
        var params = new URLSearchParams();
        Object.keys(payload).forEach(function(k){ params.set(k, payload[k]); });
        fetch('command/core/importing-controller?controller=records-db/records-db-import-controller&subCommand=distinct-values', {
          method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
        }).then(function(r){ return r.json(); }).then(fill).catch(function(){ fill(null); });
      }
    };

    // Logic select
    var logicSel = document.createElement('select'); logicSel.className = 'condition-logic'; logicSel.setAttribute('data-id', String(conditionId));
    ['AND','OR'].forEach(function(v){ var o=document.createElement('option'); o.value=v; o.textContent=v; logicSel.appendChild(o); });
    row.appendChild(logicSel);

    // Remove button
    var rmBtn = document.createElement('button'); rmBtn.className='button button-danger remove-condition'; rmBtn.setAttribute('data-id', String(conditionId));
    rmBtn.textContent = i18n.t('records.db.wizard.configureFilters.removeCondition');
    row.appendChild(rmBtn);

    conditionDiv.appendChild(row);
    conditionsList.appendChild(conditionDiv);

    // Helper to populate fields based on source
    var populateFields = function(){
      var fields = [];
      if (sourceSel.value === 'join') {
        // ensure join config is initialized (hidden UI is okay)
        var jt = document.getElementById('join-table-select');
        if (!jt || !jt.options.length) { self._initExportedConfig(); }
        fields = (condJoinColumns || []).map(function(n){ return {name:n, type:''}; });
      } else {
        fields = (self._wizard._schemaProfile && self._wizard._schemaProfile.columns) || [];
      }
      fieldSel.innerHTML = '';
      var placeholder = document.createElement('option'); placeholder.value=''; placeholder.textContent = i18n.t('records.db.wizard.configureFilters.selectField'); fieldSel.appendChild(placeholder);
      fields.forEach(function(col){ var o=document.createElement('option'); o.value=col.name; o.textContent = col.name + (col.type?(' ('+col.type+')'):''); fieldSel.appendChild(o); });
    };

    populateFields();

    // Attach event handlers
    sourceSel.onchange = function(){
      if (sourceSel.value === 'join') {
        fillJoinTableOptions();
        joinTableSel.style.display = '';
        mainKeySel.style.display = '';
        joinKeySel.style.display = '';
        fillMainKeyOptions();
        condJoinColumns = (self._joinColumns || []).slice();
        fillJoinKeyOptions();
      } else {
        joinTableSel.style.display = 'none';
        mainKeySel.style.display = 'none';
        joinKeySel.style.display = 'none';
      }
      populateFields();
      loadDistinct();
      self._updateFilterPreview();
    };
    // Join table change handlers
    joinTableSel.onchange = function(){
      var jt = joinTableSel.value;
      if (jt) {
        self._loadJoinColumns(jt);
        setTimeout(function(){ condJoinColumns = (self._joinColumns||[]).slice(); fillJoinKeyOptions(); populateFields(); loadDistinct(); self._updateFilterPreview(); }, 100);
      } else {
        populateFields();
        loadDistinct();
        self._updateFilterPreview();
      }
    };
    joinTableSel.onfocus = function(){ if (joinTableSel.options.length <= 1) { fillJoinTableOptions(); } };
    joinTableSel.onmousedown = function(){ if (joinTableSel.options.length <= 1) { fillJoinTableOptions(); } };

    fieldSel.onchange = function(){ loadDistinct(); self._updateFilterPreview(); };
    opSel.onchange = function(){ valSel.multiple = (opSel.value === 'IN'); self._updateFilterPreview(); };
    valSel.onchange = function(){ self._updateFilterPreview(); };
    rmBtn.onclick = function(){ conditionDiv.remove(); self._updateFilterPreview(); };

    this._conditions.push({ id: conditionId, source: 'main', field: '', operator: '=', value: '', logic: 'AND' });

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

    if (filters.excludeExported || (filters.exportedJoin && filters.exportedJoin.joinTable)) {
      html += '<div class="filter-item">';
      html += '<strong>' + i18n.t('records.db.wizard.configureFilters.joinSummary') + '</strong>';
      if (filters.exportedJoin && filters.exportedJoin.joinTable) {
        html += ' [' + (filters.exportedJoin.joinTable || '') + '] '
          + (filters.exportedJoin.mainColumn || '') + ' = '
          + (filters.exportedJoin.joinColumn || '');
        if (filters.exportedJoin.flagField) {
          html += ', ' + (filters.exportedJoin.flagField || '') + ' = ' + (filters.exportedJoin.value || '');
          if (filters.exportedJoin.includeNull) {
            html += ' OR NULL';
          }
        }
      }
      html += '</div>';
    }

    filters.conditions.forEach(function(cond, index) {
      if (cond.field) {
        html += '<div class="filter-item">';
        if (index > 0) {
          html += '<span class="logic-operator">' + cond.logic + '</span> ';
        }
        var srcLabel = (cond.source === 'join' ? (i18n.t('records.db.wizard.configureFilters.sourceJoin')||'关联表') : (i18n.t('records.db.wizard.configureFilters.sourceMain')||'主表'));
        if (cond.source === 'join' && cond.joinTable) { srcLabel += ':' + cond.joinTable; }
        html += '<span class="source">[' + srcLabel + ']</span> ';
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
    var filters = { excludeExported: false, exportedJoin: null, conditions: [] };
    // Exclude exported join config (used only when the checkbox is enabled)
    var excludeCheckbox = document.getElementById('exclude-exported');
    var exportedJoinFromUI = {
      joinTable: (document.getElementById('join-table-select') || {}).value || '',
      mainColumn: (document.getElementById('main-join-column') || {}).value || '',
      joinColumn: (document.getElementById('join-join-column') || {}).value || '',
      flagField: (document.getElementById('export-flag-field') || {}).value || '',
      value: (document.getElementById('export-flag-value') || {}).value || '',
      includeNull: !!(document.getElementById('export-flag-null') || {}).checked
    };
    if (excludeCheckbox && excludeCheckbox.checked) {
      filters.excludeExported = true;
      filters.exportedJoin = exportedJoinFromUI;
    }
    // Custom conditions
    var hasJoinCond = false;
    var condJoinTable = '';
    var condMainKey = '';
    var condJoinKey = '';
    var conditionItems = document.querySelectorAll('.condition-item');
    conditionItems.forEach(function(item) {
      var source = (item.querySelector('.condition-source') || {}).value || 'main';
      var field = (item.querySelector('.condition-field') || {}).value || '';
      var operator = (item.querySelector('.condition-operator') || {}).value || '=';
      var value = '';
      var valueSel = item.querySelector('.condition-value');
      if (valueSel) {
        if (operator === 'IN' && valueSel.multiple) {
          var vals = []; for (var i=0;i<valueSel.options.length;i++){ var opt=valueSel.options[i]; if (opt.selected && opt.value) vals.push(opt.value); }
          value = vals.join(',');
        } else {
          value = valueSel.value || '';
        }
      }
      var logic = (item.querySelector('.condition-logic') || {}).value || 'AND';
      var jtVal = '';
      var mk = '';
      var jk = '';
      if (source === 'join') {
        var jtSel = item.querySelector('.condition-join-table');
        jtVal = jtSel ? (jtSel.value || '') : '';
        mk = (item.querySelector('.condition-main-key') || {}).value || '';
        jk = (item.querySelector('.condition-join-key') || {}).value || '';
        if (!condJoinTable && jtVal) condJoinTable = jtVal;
        if (!condMainKey && mk) condMainKey = mk;
        if (!condJoinKey && jk) condJoinKey = jk;
      }
      if (field && value) {
        var condObj = { source: source, field: field, operator: operator, value: value, logic: logic };
        if (source === 'join') {
          condObj.joinTable = jtVal;
          condObj.mainKey = mk;
          condObj.joinKey = jk;
          hasJoinCond = true;
        }
        filters.conditions.push(condObj);
      }
    });
    // If exclude-exported is not used, but there are join-conditions and a join table and keys were chosen in conditions,
    // derive the join configuration purely from the join-condition settings (ignore hidden UI defaults).
    if (!filters.excludeExported && hasJoinCond && condJoinTable && condMainKey && condJoinKey) {
      filters.exportedJoin = {
        joinTable: condJoinTable,
        mainColumn: condMainKey,
        joinColumn: condJoinKey,
        // flag-related fields are only used when exclude-exported is enabled,
        // but we keep them here in case they are useful for preview or future logic.
        flagField: exportedJoinFromUI.flagField,
        value: exportedJoinFromUI.value,
        includeNull: exportedJoinFromUI.includeNull
      };
    }
    return filters;
  };

  FilterConditionBuilder.prototype.validateFilters = function() {
    var filters = this.getFilters();
    // Validate exclude exported join config
    if (filters.excludeExported) {
      var ej = filters.exportedJoin || {};
      if (!ej.joinTable || !ej.mainColumn || !ej.joinColumn || !ej.flagField) {
        return { valid: false, message: i18n.t('records.db.wizard.configureFilters.exportJoinConfigRequired') };
      }
    }
    // Validate custom conditions
    for (var i = 0; i < filters.conditions.length; i++) {
      var cond = filters.conditions[i];
      if (!cond.field || !cond.value) {
        return { valid: false, message: i18n.t('records.db.wizard.configureFilters.incompleteCondition') };
      }
    }
    return { valid: true };
  };

  // Restore UI from saved filters in wizard._schemaProfile
  FilterConditionBuilder.prototype._loadFromProfile = function() {
    var self = this;
    var profile = this._wizard && this._wizard._schemaProfile ? this._wizard._schemaProfile : {};
    var filters = profile.filters || {};

    // Restore exported join / exclude exported
    var ej = filters.exportedJoin || {};
    var hasEJ = ej && (ej.joinTable || ej.mainColumn || ej.joinColumn || ej.flagField);
    if (filters.excludeExported) {
      var cb = document.getElementById('exclude-exported');
      if (cb) {
        cb.checked = true;
        // Show UI and initialize using the saved configuration
        this._toggleExportedFilter(ej);
      }
    } else if (hasEJ) {
      // Initialize hidden selects so conditions can reuse the saved defaults,
      // but keep the checkbox unchecked (UI collapsed).
      this._initExportedConfig(ej);
    }

    // Restore custom conditions
    var conds = Array.isArray(filters.conditions) ? filters.conditions : [];
    if (!conds.length) { return; }

    var restoreOne = function(cond, idx) {
      self._addCondition();
      setTimeout(function(){
        var items = document.querySelectorAll('.condition-item');
        var item = items[items.length - 1]; if (!item) return;
        var sourceSel = item.querySelector('.condition-source');
        var joinTableSel = item.querySelector('.condition-join-table');
        var mainKeySel = item.querySelector('.condition-main-key');
        var joinKeySel = item.querySelector('.condition-join-key');
        var fieldSel = item.querySelector('.condition-field');
        var opSel = item.querySelector('.condition-operator');
        var valSel = item.querySelector('.condition-value');
        var logicSel = item.querySelector('.condition-logic');

        if (sourceSel && cond.source) {
          sourceSel.value = cond.source;
          if (sourceSel.onchange) sourceSel.onchange();
        }
        setTimeout(function(){
          if (cond.source === 'join' && cond.joinTable && joinTableSel) {
            joinTableSel.value = cond.joinTable;
            if (joinTableSel.onchange) joinTableSel.onchange();
          }
          setTimeout(function(){
            if (cond.source === 'join') {
              if (mainKeySel && cond.mainKey) { mainKeySel.value = cond.mainKey; }
              if (joinKeySel && cond.joinKey) { joinKeySel.value = cond.joinKey; }
            }
            if (fieldSel && cond.field) {
              fieldSel.value = cond.field;
              if (fieldSel.onchange) fieldSel.onchange();
            }
            if (opSel && cond.operator) {
              opSel.value = cond.operator;
              if (opSel.onchange) opSel.onchange();
            }
            setTimeout(function(){
              if (valSel && typeof cond.value !== 'undefined' && cond.value !== null) {
                var valueStr = String(cond.value);
                if (opSel && opSel.value === 'IN') {
                  valSel.multiple = true;
                  var parts = valueStr.split(',');
                  for (var i=0;i<valSel.options.length;i++) {
                    var o = valSel.options[i];
                    if (parts.indexOf(o.value) >= 0) { o.selected = true; }
                  }
                } else {
                  valSel.value = valueStr;
                }
                if (valSel.onchange) valSel.onchange();
              }
              if (logicSel && cond.logic) { logicSel.value = cond.logic; }
              self._updateFilterPreview();
            }, 180);
          }, 180);
        }, 150);
      }, 80);
    };

    for (var i=0;i<conds.length;i++) { restoreOne(conds[i], i); }
  };

})();
