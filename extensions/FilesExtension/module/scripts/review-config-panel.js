/**
 * 数据校审 - 校审参数表单（共享渲染/收集逻辑）
 *
 * 新建项目页「校审模式」的 `review-config-panel` 与项目页「编辑校审参数」tab 共用同一套
 * 业务页签 + 规则勾选 UI，避免两处文案、启用状态与勾选行为漂移。
 *
 * 页签按**业务需求**分为 7 组（完备性 / 签章与签署 / 顺序与时限 / 跨材料勾稽 /
 * 卷级规范 / 文书必备要素 / 内容组合类），定义在 `Refine.REVIEW_RULE_CATEGORIES`
 * （import-from-local-dir.js）。顶部「规则模板」下拉可一键回填各页签勾选
 * （`Refine.REVIEW_RULE_TEMPLATES`）；内容组合类页签展示本次提交目录摘要（state.catalog）。
 */
var ReviewConfigPanelUI = (function() {

  /** 业务页签清单（全量，含置灰未启用的规则） */
  function categories() {
    return Refine.REVIEW_RULE_CATEGORIES || [];
  }

  function templates() {
    return Refine.REVIEW_RULE_TEMPLATES || [];
  }

  function categoryOf(key) {
    var cats = categories();
    for (var i = 0; i < cats.length; i++) {
      if (cats[i].key === key) {
        return cats[i];
      }
    }
    return cats[0];
  }

  /**
   * 新建表单状态。
   * @param {Array} selectedRules 已保存的规则清单（[{key}]）；为空时按「所有已启用规则默认勾选」处理
   */
  function createState(selectedRules) {
    var cats = categories();
    var state = { activeKey: cats.length ? cats[0].key : '', checked: {}, templateId: '', catalog: [] };
    var saved = null;
    if (selectedRules && selectedRules.length > 0) {
      saved = {};
      for (var i = 0; i < selectedRules.length; i++) {
        var key = selectedRules[i] && selectedRules[i].key;
        if (key) {
          saved[key] = true;
        }
      }
    }
    cats.forEach(function(cat) {
      cat.rules.forEach(function(rule) {
        if (!rule.enabled) {
          return;
        }
        // 未保存过规则时：默认勾选，但规则可声明 defaultChecked:false 表示「空项默认不勾」
        // （如内容组合类依赖件清单，没有件清单时不会产生结果，避免误勾）。
        var fallback = rule.defaultChecked === false ? false : true;
        state.checked[rule.key] = saved ? !!saved[rule.key] : fallback;
      });
    });
    return state;
  }

  /** 套用规则模板：按模板列出的规则回填（只影响已开放能力，未开放项保持置灰）。 */
  function applyTemplate(state, id) {
    var tpl = null;
    templates().forEach(function(t) {
      if (t.id === id) {
        tpl = t;
      }
    });
    state.templateId = tpl ? tpl.id : '';
    if (!tpl) {
      return;
    }
    var wanted = {};
    (tpl.rules || []).forEach(function(key) { wanted[key] = true; });
    categories().forEach(function(cat) {
      cat.rules.forEach(function(rule) {
        if (rule.enabled) {
          state.checked[rule.key] = !!wanted[rule.key];
        }
      });
    });
  }

  /**
   * 渲染业务页签与当前页签的规则清单；切页签会原地重绘两侧容器。
   * @param {jQuery} $tabs 页签容器
   * @param {jQuery} $list 规则清单容器
   * @param {jQuery} $note 已选规则摘要容器（每次勾选变化后刷新）
   * @param {Object} state createState() 返回的表单状态（activeKey / checked / templateId / catalog）
   * @param {Function} onChange 勾选变化回调（可选）
   */
  function render($tabs, $list, $note, state, onChange) {
    var cats = categories();
    if (!cats.length || !$tabs || !$list) {
      return;
    }

    var active = categoryOf(state.activeKey);
    state.activeKey = active.key;

    var rerender = function() {
      render($tabs, $list, $note, state, onChange);
    };
    renderTemplateRow($tabs, state, rerender);

    $tabs.empty();
    cats.forEach(function(cat) {
      var enabled = cat.rules.some(function(rule) { return rule.enabled; });
      $('<a href="javascript:void(0)" class="review-category-tab"></a>')
          .text(cat.name)
          .attr('title', cat.desc || '')
          .toggleClass('active', cat.key === active.key)
          .toggleClass('review-category-tab-disabled', !enabled)
          .on('click', function() {
            state.activeKey = cat.key;
            rerender();
          })
          .appendTo($tabs);
    });

    $list.empty();
    if (active.desc) {
      $('<div class="review-rule-category-desc"></div>').text(active.desc).appendTo($list);
    }
    active.rules.forEach(function(rule) {
      var $item = $('<label class="review-rule-item"></label>');
      var $cb = $('<input type="checkbox" class="review-rule-checkbox"/>').val(rule.key);
      if (!rule.enabled) {
        $item.addClass('review-rule-disabled');
        $cb.prop('disabled', true);
      } else {
        $cb.prop('checked', !!state.checked[rule.key]);
      }
      $cb.on('change', function() {
        state.checked[rule.key] = $(this).is(':checked');
        updateNote($note, state);
        if (onChange) {
          onChange();
        }
      });
      $item.append($cb)
          .append($('<span class="review-rule-name"></span>').text(rule.name))
          .append($('<span class="review-rule-desc"></span>').text(rule.desc || ''))
          .appendTo($list);
    });

    if (active.key === 'piece_combo') {
      renderCatalog($list, state);
    }

    updateNote($note, state);
  }

  /** 顶部「规则模板」下拉：选中即回填该模板的勾选（不改变未开放项的置灰状态）。 */
  function renderTemplateRow($tabs, state, rerender) {
    var $parent = $tabs.parent();
    var $row = $parent.children('.review-template-row');
    if (!$row.length) {
      $row = $('<div class="review-template-row"></div>').prependTo($parent);
      $('<label class="review-template-label"></label>').text('规则模板').appendTo($row);
      var $sel = $('<select class="review-template-select"></select>').appendTo($row);
      $sel.append($('<option value=""></option>').text('（不套用模板，逐项勾选）'));
      templates().forEach(function(t) {
        $sel.append($('<option></option>').val(t.id).text(t.name));
      });
      $('<span class="review-template-hint"></span>')
          .text('选择模板会按模板回填下方勾选，仍可再逐项调整。')
          .appendTo($row);
      $sel.on('change', function() {
        applyTemplate(state, String($(this).val() || ''));
        rerender();
      });
    }
    $row.find('.review-template-select').val(state.templateId || '');
  }

  /** 内容组合类页签：展示本次提交目录摘要（只读）。 */
  function renderCatalog($list, state) {
    var items = state.catalog || [];
    var $box = $('<div class="review-catalog-summary"></div>').appendTo($list);
    if (!items.length) {
      $('<div class="review-catalog-empty"></div>')
          .text('本次未提交目录：本页签暂不产生结果。')
          .appendTo($box);
      return;
    }
    var titles = {};
    var pieces = 0;
    items.forEach(function(it) {
      pieces += 1;
      var g = it.level2 || it.category || '未分组';
      titles[g] = (titles[g] || 0) + 1;
    });
    $('<div class="review-catalog-title"></div>')
        .text('本次提交目录 · ' + pieces + ' 件 / ' + Object.keys(titles).length + ' 组')
        .appendTo($box);
    var $table = $('<table class="review-catalog-table"></table>').appendTo($box);
    $('<tr></tr>')
        .append($('<th></th>').text('二级目录'))
        .append($('<th></th>').text('件数'))
        .appendTo($('<thead></thead>').appendTo($table));
    var $body = $('<tbody></tbody>').appendTo($table);
    Object.keys(titles).forEach(function(g) {
      $('<tr></tr>')
          .append($('<td></td>').text(g))
          .append($('<td></td>').text(String(titles[g])))
          .appendTo($body);
    });
  }

  /** 已勾选规则（仅已启用规则可能被勾选），后端 start / save-rules 的 rules 参数结构 */
  function collect(state) {
    var items = [];
    categories().forEach(function(cat) {
      cat.rules.forEach(function(rule) {
        if (rule.enabled && state.checked[rule.key]) {
          items.push({ category: cat.name, key: rule.key, name: rule.name });
        }
      });
    });
    return items;
  }

  /** 已勾选规则摘要；为空时提示至少选择一项 */
  function updateNote($note, state) {
    if (!$note || $note.length === 0) {
      return;
    }
    var names = collect(state).map(function(rule) { return rule.name; });
    $note.toggleClass('review-selected-note-empty', names.length === 0)
        .text(names.length > 0
            ? $.i18n('files-import/review-selected') + names.join('、')
            : $.i18n('files-import/review-rules-required'));
  }

  return {
    categories: categories,
    templates: templates,
    createState: createState,
    applyTemplate: applyTemplate,
    render: render,
    collect: collect,
    updateNote: updateNote
  };
})();
