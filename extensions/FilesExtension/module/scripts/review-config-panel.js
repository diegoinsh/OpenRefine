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
    var state = { activeKey: cats.length ? cats[0].key : '', checked: {}, templateId: '',
      catalog: [], catalogMeta: null, comboFilter: null };
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
    var $head = $('<div class="review-rule-category-head"></div>').appendTo($list);
    if (active.desc) {
      $('<span class="review-rule-category-desc"></span>').text(active.desc).appendTo($head);
    }
    $('<span class="review-cat-chip"></span>').text('四性=完整性').appendTo($head);

    renderParamLine($list, active.key, currentTemplate(state));

    if (active.key === 'piece_combo') {
      // 内容组合类的表格即「二级目录组合表」，不再重复一行规则表
      renderCatalog($list, state, rerender);
    } else {
      renderRuleTable($list, active, state, $note, onChange);
    }

    updateNote($note, state);
  }

  /** 参数行：只读展示当前模板的关键参数；未套用模板时显示「按模板默认」。 */
  function renderParamLine($list, key, tpl) {
    var rows = paramRows(key, tpl);
    if (!rows.length) {
      return;
    }
    var $line = $('<div class="review-param-line"></div>').appendTo($list);
    rows.forEach(function(p) {
      var $p = $('<span class="review-param"></span>').appendTo($line);
      $('<b></b>').text(p[0]).appendTo($p);
      $('<span></span>').text('：' + p[1]).appendTo($p);
    });
  }

  function paramRows(key, tpl) {
    var p = tpl && tpl.params ? tpl.params : null;
    function onoff(v) {
      return v ? '开' : '关';
    }
    function deviation() {
      if (!p) {
        return '按模板默认';
      }
      return p.onDeviation === 'error' ? '阻断' : '告警不阻断';
    }
    if (key === 'complete') {
      return [
        ['缺件匹配相似度', p ? p.matchThreshold : '按模板默认'],
        ['附件并入', p ? onoff(p.mergeAttachments) : '按模板默认'],
        ['证物袋四要素', p ? onoff(p.pouchElements) : '按模板默认'],
        ['缺件处理', deviation()]
      ];
    }
    if (key === 'seal') {
      return [
        ['印章置信度', p ? p.sealConfidence : '按模板默认'],
        ['签名置信度', p ? p.signConfidence : '按模板默认'],
        ['覆盖粒度', '件内任一页'],
        ['签名或捺印替代', '待签字模型']
      ];
    }
    if (key === 'order') {
      return [
        ['件序基线', tpl && tpl.baseline ? tpl.baseline : '按模板默认'],
        ['偏差处理', deviation()],
        ['日期链', '待下游提交成文日期'],
        ['时限天数', '随模板下发']
      ];
    }
    if (key === 'reconcile') {
      return [
        ['勾稽范围', '卷内'],
        ['契约字段', '案号 / 当事人 / 金额（已预埋）'],
        ['判定状态', '待启用']
      ];
    }
    if (key === 'volume') {
      return [
        ['编页豁免清单', '目录 / 备考表 / 证物袋 / 卷底'],
        ['卷级判定', '卷内目录已开放；封面、备考表待开放'],
        ['金属物检查', '人工']
      ];
    }
    if (key === 'document_element') {
      return [
        ['启用', '开'],
        ['判定标准', '本次提交目录契约字段'],
        ['聚合口径', '逐件']
      ];
    }
    return [];
  }

  /** 规则表：检查项（可勾选）/ 覆盖粒度 / 判定状态 / 说明；未开放项整行置灰。 */
  function renderRuleTable($list, active, state, $note, onChange) {
    var $table = $('<table class="review-rule-table"></table>').appendTo($list);
    var $headRow = $('<tr></tr>').appendTo($('<thead></thead>').appendTo($table));
    ['检查项', '覆盖粒度', '判定状态', '说明'].forEach(function(h) {
      $('<th></th>').text(h).appendTo($headRow);
    });
    var $body = $('<tbody></tbody>').appendTo($table);
    active.rules.forEach(function(rule) {
      var $tr = $('<tr></tr>').appendTo($body);
      if (!rule.enabled) {
        $tr.addClass('review-rule-row-disabled');
      }
      var $nameCell = $('<td class="review-rule-name-cell"></td>').appendTo($tr);
      var $item = $('<label class="review-rule-item"></label>').appendTo($nameCell);
      var $cb = $('<input type="checkbox" class="review-rule-checkbox"/>').val(rule.key);
      if (!rule.enabled) {
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
      $item.append($cb).append($('<span class="review-rule-name"></span>').text(rule.name));
      $('<td></td>').text(rule.coverage === 'everyPage' ? '逐页' : '件内任一页').appendTo($tr);
      var $status = $('<td></td>').appendTo($tr);
      $('<span class="review-rule-status"></span>')
          .addClass(rule.enabled ? 'ok' : 'locked')
          .text(rule.enabled ? '已开放' : '待开放')
          .appendTo($status);
      $('<td class="review-rule-desc"></td>').text(rule.desc || '').appendTo($tr);
    });
  }

  /** 当前选中的模板（未选时为 null）。 */
  function currentTemplate(state) {
    var tpl = null;
    templates().forEach(function(t) {
      if (t.id === state.templateId) {
        tpl = t;
      }
    });
    return tpl;
  }

  /**
   * 顶部「规则模板」栏：第一行选择/加载/另存为/导入/导出，第二行模板属性（只读）。
   * 另存为/导入/导出由平台模板库承担（OR 侧无模板写入接口），这里置灰并写明去向。
   */
  function renderTemplateRow($tabs, state, rerender) {
    var $parent = $tabs.parent();
    var $row = $parent.children('.review-template-row');
    if (!$row.length) {
      $row = $('<div class="review-template-row"></div>').prependTo($parent);
      var $line1 = $('<div class="review-template-line"></div>').appendTo($row);
      $('<label class="review-template-label"></label>').text('规则模板').appendTo($line1);
      var $sel = $('<select class="review-template-select"></select>').appendTo($line1);
      $sel.append($('<option value=""></option>').text('（不套用模板，逐项勾选）'));
      templates().forEach(function(t) {
        $sel.append($('<option></option>').val(t.id).text(t.name));
      });
      $('<span class="review-template-hint"></span>')
          .text('选择模板会按模板回填下方勾选，仍可再逐项调整。')
          .appendTo($line1);
      $('<button type="button" class="button review-template-btn"></button>')
          .text('加载到页签')
          .on('click', function() {
            applyTemplate(state, String($sel.val() || ''));
            rerender();
          })
          .appendTo($line1);
      ['另存为', '导入', '导出'].forEach(function(label) {
        $('<button type="button" class="button review-template-btn" disabled="disabled"></button>')
            .text(label)
            .attr('title', '模板的新增/覆盖/导入导出由平台模板库维护')
            .appendTo($line1);
      });
      $('<div class="review-template-meta"></div>').appendTo($row);
      $sel.on('change', function() {
        applyTemplate(state, String($(this).val() || ''));
        rerender();
      });
    }
    $row.find('.review-template-select').val(state.templateId || '');
    var tpl = currentTemplate(state);
    var meta = tpl
        ? ['案件类型：' + caseTypeText(tpl.caseType), '审级：' + instanceText(tpl.instance),
           '卷范围：' + volumeScopeText(tpl.volumeScope), '版本：' + (tpl.version || '—'),
           '来源：' + sourceText(tpl.source)]
        : ['未套用模板（按已开放能力逐项勾选）'];
    meta.push('判定输入：本次提交目录（只读）');
    meta.push('报告口径：四性 + 案件质量评查');
    $row.find('.review-template-meta').text(meta.join(' · '));
  }

  function caseTypeText(v) {
    return v === 'civil' ? '民事' : (v || '—');
  }

  function instanceText(v) {
    return v === 'first' ? '一审' : v === 'second' ? '二审' : (v || '—');
  }

  function volumeScopeText(v) {
    if (!v || !v.length) {
      return '—';
    }
    return v.map(function(s) {
      return s === 'main' ? '正卷' : s === 'deputy' ? '副卷' : s;
    }).join(' / ');
  }

  function sourceText(v) {
    return v === 'builtin' ? '平台内置' : v === 'rcp' ? 'RCP 接口' : v === 'upload' ? '本地导入' : (v || '—');
  }


  /**
   * 内容组合类页签：按设计稿展示「数据来源条 + 参数行 + 二级目录组合表 + 底部收窄」。
   * 判定输入是下游本次提交目录（state.catalog），不反查抽取任务。
   */
  function renderCatalog($list, state, rerender) {
    var items = state.catalog || [];
    var $card = $('<div class="review-combo-card"></div>').appendTo($list);
    var $head = $('<div class="review-combo-head"></div>').appendTo($card);
    $('<span class="review-combo-title"></span>').text('内容组合类').appendTo($head);
    $('<span class="review-combo-chip"></span>').text('原页签保留').appendTo($head);
    $('<span class="review-combo-chip"></span>').text('事实来源：下游提交目录').appendTo($head);
    $('<div class="review-combo-desc"></div>')
        .text('按下游本次提交目录的二级分组逐组核对材料组合；四级题名原样展示、不做纠错，'
            + '需要核对图像时按目录中的页序读取对应图片。')
        .appendTo($card);

    if (!items.length) {
      $('<div class="review-combo-empty"></div>')
          .text('本次未提交目录：本页签不产生结果。')
          .appendTo($card);
      return;
    }

    var groups = groupByLevel2(items);
    var totalPages = 0;
    items.forEach(function(it) {
      totalPages += pagesOfItem(it);
    });
    var meta = state.catalogMeta || {};

    var $src = $('<div class="review-combo-src"></div>').appendTo($card);
    $('<span class="review-combo-src-k"></span>').text('数据来源').appendTo($src);
    $('<span class="review-combo-src-v"></span>').text('下游提交 catalog').appendTo($src);
    $('<code></code>').text('档号 ' + (meta.archiveNo || '未标注')).appendTo($src);
    $('<code></code>').text(items.length + ' 件 / ' + totalPages + ' 页').appendTo($src);
    $('<span class="review-combo-chip"></span>').text('客户人工调整后重新提交').appendTo($src);
    $('<span class="review-combo-chip"></span>').text('只读').appendTo($src);

    var $params = $('<div class="review-combo-params"></div>').appendTo($card);
    [
      ['按二级目录归组', '以二级目录为单位比对，不按件平铺'],
      ['四级题名原样展示', '使用下游最终题名，不做同义改写'],
      ['无目录时不产生结果', '本次未提交目录时本页签为空'],
      ['仅作判定输入', '抽取任务编号不参与判定，仅兼容保留']
    ].forEach(function(p) {
      var $p = $('<span class="review-combo-param"></span>').appendTo($params);
      $('<b></b>').text(p[0]).appendTo($p);
      $('<span></span>').text('：' + p[1]).appendTo($p);
    });

    var $table = $('<table class="review-combo-table"></table>').appendTo($card);
    var $headRow = $('<tr></tr>').appendTo($('<thead></thead>').appendTo($table));
    ['二级目录', '本次提交件（四级题名）', '件 ÷ 页', '组合判定'].forEach(function(h) {
      $('<th></th>').text(h).appendTo($headRow);
    });
    var $body = $('<tbody></tbody>').appendTo($table);
    var onlyIssues = state.comboFilter === 'issues';
    var shown = 0;
    groups.forEach(function(g) {
      if (onlyIssues && !g.pending) {
        return;
      }
      shown += 1;
      var $row = $('<tr></tr>').appendTo($body);
      if (g.pending) {
        $row.addClass('review-combo-row-warn');
      }
      $('<td></td>').text(g.level2).appendTo($row);
      $('<td></td>').text(g.titles.join('、') || '（未命名件）').appendTo($row);
      $('<td class="review-combo-num"></td>').text(g.pieces + ' · ' + g.pages).appendTo($row);
      var $status = $('<td></td>').appendTo($row);
      $('<span class="review-combo-tag"></span>')
          .addClass(g.pending ? 'warn' : 'ok')
          .text(g.pending ? '待人工归类' : '完整')
          .appendTo($status);
    });
    if (!shown) {
      $('<tr></tr>').append($('<td colspan="4" class="review-combo-empty-cell"></td>')
          .text('没有需要人工处理的二级分组。')).appendTo($body);
    }

    var $foot = $('<div class="review-combo-foot"></div>').appendTo($card);
    $('<a href="javascript:void(0)" class="action"></a>')
        .text(onlyIssues ? '显示全部' : '按二级目录收窄')
        .on('click', function() {
          state.comboFilter = onlyIssues ? null : 'issues';
          if (rerender) {
            rerender();
          }
        })
        .appendTo($foot);
    $('<span class="review-combo-basis"></span>')
        .text('判定依据：下游本次提交目录（含一级、二级与最终四级题名）；'
            + '待归类项只登记、不判缺件。')
        .appendTo($foot);
  }

  /** 二级目录归组：剥掉「06 」这类序号前缀，同组件名与件/页合并。 */
  function groupByLevel2(items) {
    var map = {};
    var order = [];
    items.forEach(function(it) {
      var raw = String(it.level2 || '').trim() || '未标注二级目录';
      var key = raw.replace(/^\s*\d+\s*[、.．\-—_]?\s*/, '').trim() || '未标注二级目录';
      if (!map[key]) {
        map[key] = { key: key, level2: raw, titles: [], pieces: 0, pages: 0, pending: false };
        order.push(key);
      }
      var g = map[key];
      g.pieces += 1;
      g.pages += pagesOfItem(it);
      var title = it.level3Title || it.title || it.name || '';
      if (title) {
        g.titles.push(title);
      }
    });
    return order.map(function(key) {
      var g = map[key];
      g.pending = key.indexOf('待归类') >= 0;
      return g;
    });
  }

  /** 件页数：优先用登记好的 pages，其次按起止页算（下游数据两字段都可能缺）。 */
  function pagesOfItem(it) {
    var pages = Number(it.pages);
    if (pages > 0) {
      return pages;
    }
    var start = Number(it.startPage) || 0;
    var end = Number(it.endPage) || 0;
    return Math.max(0, end - start + 1);
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
