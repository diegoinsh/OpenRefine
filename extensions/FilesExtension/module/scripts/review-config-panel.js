/**
 * 数据校审 - 校审参数表单（共享渲染/收集逻辑）
 *
 * 新建项目页「校审模式」的 `review-config-panel` 与项目页「编辑校审参数」tab 共用同一套
 * 分类页签 + 规则勾选 UI，避免两处文案、启用状态与勾选行为漂移。
 *
 * 分类/规则清单来自 `Refine.REVIEW_RULE_CATEGORIES`（定义在 import-from-local-dir.js），
 * 本项目页与新建页都加载了该脚本。
 */
var ReviewConfigPanelUI = (function() {

  /** 分类清单（全量，含置灰未启用的规则） */
  function categories() {
    return Refine.REVIEW_RULE_CATEGORIES || [];
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
    var state = { activeKey: cats.length ? cats[0].key : '', checked: {} };
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
        state.checked[rule.key] = saved ? !!saved[rule.key] : true;
      });
    });
    return state;
  }

  /**
   * 渲染分类页签与当前分类的规则清单；切页签会原地重绘两侧容器。
   * @param {jQuery} $tabs 页签容器
   * @param {jQuery} $list 规则清单容器
   * @param {jQuery} $note 已选规则摘要容器（每次勾选变化后刷新）
   * @param {Object} state createState() 返回的表单状态（activeKey / checked）
   * @param {Function} onChange 勾选变化回调（可选）
   */
  function render($tabs, $list, $note, state, onChange) {
    var cats = categories();
    if (!cats.length || !$tabs || !$list) {
      return;
    }

    var active = categoryOf(state.activeKey);
    state.activeKey = active.key;

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
            render($tabs, $list, $note, state, onChange);
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

    updateNote($note, state);
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
    createState: createState,
    render: render,
    collect: collect,
    updateNote: updateNote
  };
})();
