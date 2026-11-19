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

  /* ---------------- 页签级参数（设计稿 FR-L2 口径） ---------------- */

  /** 页签右上角的需求编号；文案与设计稿一致，不再统一显示「四性=完整性」 */
  var CATEGORY_REQUIREMENTS = {
    complete: 'FR-L2-01 ~ 08 · 四性=完整性',
    seal: 'FR-L2-10 ~ 24 · CFG-03',
    order: 'FR-L2-30 ~ 33 · CFG-02',
    reconcile: 'FR-L2-40 ~ 45 · 归校审',
    volume: 'FR-L2-50 ~ 54 · 卷级 scope',
    document_element: '合规移交 C1 ~ C7 · 四性=完整性',
    piece_combo: '原页签保留'
  };

  /** 页签说明：按设计稿只保留一句话，不罗列实现细节 */
  var CATEGORY_DESCS = {
    complete: '按必备件清单模板逐件匹配，输出缺件 / 缺页 / 空件 / 条件缺件 / 附件并入 / 分册 / 证物袋 / 正副卷区分。件清单来源为本次提交目录。',
    order: '件序按模板基线比对，只出偏差清单（warning），不阻断归档。日期链与办理时限同页配置。'
  };

  /** 完备性参数默认值（阈值以平台模板实测 0.85 为准，不用设计稿草案的 0.82） */
  var DEFAULT_PARAMS = {
    matchThreshold: 0.85, mergeAttachments: true, pouchElements: true, missingAction: 'warning'
  };

  /** 签章要求表（FR-L2-10 ~ 24）：覆盖粒度默认取设计稿字面值，可在表格内下拉调整 */
  var SEAL_REQUIREMENTS = [
    { title: '判决书 / 裁定书 / 调解书', kind: '院印', coverage: '末页', roles: '—', enabled: true },
    { title: '通知书 / 公告 / 送达回证', kind: '院章', coverage: '末页', roles: '—', enabled: true },
    { title: '笔录类', kind: '当事人签名', coverage: '逐页', roles: '—', enabled: false },
    { title: '笔录类', kind: '审判人员 / 书记员签名', coverage: '结尾', roles: '2', enabled: false },
    { title: '合议笔录', kind: '合议庭成员签名 + 日期', coverage: '结尾', roles: '3', enabled: false },
    { title: '备考表', kind: '立卷人 / 检查人 / 验收人', coverage: '末页', roles: '3', enabled: false },
    { title: '卷皮 / 卷底', kind: '正副卷章 / 封条 + 书记员印章', coverage: '全卷 · 末页', roles: '1', enabled: false },
    { title: '刑事证据', kind: '提供人签名 + 单位印章（AND）', coverage: '末页', roles: '2', enabled: false }
  ];

  var COVERAGE_OPTIONS = ['逐页', '末页', '结尾', '首尾页', '全卷 · 末页', '件内一处'];

  /** 件序基线下拉（与平台模板 payload.order.baseline 对齐） */
  var ORDER_BASELINES = [
    { code: 's1-1991', label: '1991 办法（24 项）' },
    { code: 's5-2018', label: '2018 电子卷宗（51 项）' },
    { code: 'local', label: '本院裁剪（33 项）' }
  ];

  /** 日期链节点与办理时限提示（只读声明，成文日期由提交目录带入后才判定） */
  var DATE_CHAIN = ['立案', '开庭', '裁判', '送达'];
  var TIME_LIMIT_BADGES = ['扫描挂接 3 个工作日', '开庭公告提前 3 日', '合议庭告知 3 日'];

  function defaultParams() {
    return { matchThreshold: DEFAULT_PARAMS.matchThreshold,
      mergeAttachments: DEFAULT_PARAMS.mergeAttachments,
      pouchElements: DEFAULT_PARAMS.pouchElements,
      missingAction: DEFAULT_PARAMS.missingAction };
  }

  /** 签章页参数 + 每行覆盖粒度（rows 以 SEAL_REQUIREMENTS 下标为键） */
  function defaultSeal(tpl, saved) {
    var p = (tpl && tpl.params) || {};
    var seal = {
      sealConfidence: p.sealConfidence != null ? p.sealConfidence : 0.78,
      signConfidence: p.signConfidence != null ? p.signConfidence : 0.72,
      allowSignatureAsSeal: true,
      rows: {},
      custom: []
    };
    SEAL_REQUIREMENTS.forEach(function(row, i) {
      seal.rows[i] = row.coverage;
    });
    return mergeSeal(seal, saved);
  }

  /** 已保存值覆盖默认值：只认已知键，避免旧数据把脏字段带进来 */
  function mergeSeal(base, saved) {
    if (!saved) {
      return base;
    }
    if (saved.sealConfidence != null) { base.sealConfidence = saved.sealConfidence; }
    if (saved.signConfidence != null) { base.signConfidence = saved.signConfidence; }
    if (saved.allowSignatureAsSeal != null) { base.allowSignatureAsSeal = !!saved.allowSignatureAsSeal; }
    if (saved.rows) {
      SEAL_REQUIREMENTS.forEach(function(row, i) {
        if (typeof saved.rows[i] === 'string' && saved.rows[i]) {
          base.rows[i] = saved.rows[i];
        }
      });
    }
    if (saved.custom && saved.custom.length) {
      saved.custom.forEach(function(row) {
        if (!row) {
          return;
        }
        base.custom.push({
          title: row.title || '',
          kind: row.kind || '',
          coverage: row.coverage || '末页',
          roles: row.roles || ''
        });
      });
    }
    return base;
  }

  function mergeParams(base, saved) {
    if (!saved) {
      return base;
    }
    if (saved.matchThreshold != null) { base.matchThreshold = saved.matchThreshold; }
    if (saved.mergeAttachments != null) { base.mergeAttachments = !!saved.mergeAttachments; }
    if (saved.pouchElements != null) { base.pouchElements = !!saved.pouchElements; }
    if (saved.missingAction) { base.missingAction = saved.missingAction; }
    return base;
  }

  /** 顺序与时限参数默认值：件序基线取模板值，偏差默认只告警 */
  function defaultOrder(tpl, saved) {
    var p = (tpl && tpl.params) || {};
    var order = {
      baseline: (tpl && tpl.baseline) || 's5-2018',
      baselineVersion: 'v3',
      onDeviation: p.onDeviation === 'error' ? 'error' : 'warning',
      dateChain: true,
      custom: []
    };
    return mergeOrder(order, saved);
  }

  function mergeOrder(base, saved) {
    if (!saved) {
      return base;
    }
    if (saved.baseline) { base.baseline = saved.baseline; }
    if (saved.baselineVersion) { base.baselineVersion = saved.baselineVersion; }
    if (saved.onDeviation === 'error' || saved.onDeviation === 'warning') {
      base.onDeviation = saved.onDeviation;
    }
    if (saved.dateChain != null) { base.dateChain = !!saved.dateChain; }
    if (saved.custom && saved.custom.length) {
      saved.custom.forEach(function(row) {
        if (!row) {
          return;
        }
        base.custom.push({
          order: Number(row.order) || 0,
          title: row.title || '',
          required: row.required !== false
        });
      });
    }
    return base;
  }

  /**
   * 新建表单状态。
   * @param {Array} selectedRules 已保存的规则清单（[{key}]）；为空时按「所有已启用规则默认勾选」处理
   */
  function createState(selectedRules) {
    var cats = categories();
    var state = { activeKey: cats.length ? cats[0].key : '', checked: {}, templateId: '',
      catalog: [], catalogMeta: null, comboFilter: null, templateComplete: null,
      params: null, seal: null, order: null, paramsSaved: false };
    var saved = null;
    if (selectedRules && selectedRules.length > 0) {
      saved = {};
      for (var i = 0; i < selectedRules.length; i++) {
        var key = selectedRules[i] && selectedRules[i].key;
        if (key) {
          saved[key] = true;
        }
        // 页签级参数随规则落盘（挂在首条规则的 options 上）：重开页面时回填
        var options = selectedRules[i] && selectedRules[i].options;
        if (!state.params && options) {
          state.params = mergeParams(defaultParams(), options.complete);
          state.seal = defaultSeal(null, options.seal);
          state.order = defaultOrder(null, options.order);
          state.paramsSaved = true;
        }
      }
    }
    if (!state.params) {
      state.params = defaultParams();
    }
    if (!state.seal) {
      state.seal = defaultSeal(null, null);
    }
    if (!state.order) {
      state.order = defaultOrder(null, null);
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
    // 套用模板同时重置页签参数：完备性取模板值（无则默认 0.85），签章置信度取模板值
    var p = tpl.params || {};
    state.params = mergeParams(defaultParams(), {
      matchThreshold: p.matchThreshold,
      mergeAttachments: p.mergeAttachments,
      pouchElements: p.pouchElements,
      missingAction: p.onDeviation === 'error' ? 'error' : 'warning'
    });
    state.paramsSaved = true;
    state.seal = defaultSeal(tpl, null);
    state.order = defaultOrder(tpl, null);
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
    // 未保存过参数时按模板快照回填（与引擎判定同一份口径）；用户改过则以用户值为准
    if (!state.paramsSaved && hasCompleteTemplate(state)) {
      state.params = mergeParams(defaultParams(), {
        matchThreshold: state.templateComplete.matchThreshold,
        mergeAttachments: state.templateComplete.mergeAttachments,
        pouchElements: state.templateComplete.pouchElements,
        missingAction: state.templateComplete.missingAction
      });
    }
    // 页头按设计稿：标题 + 需求编号，说明单独一行（只保留一句话）
    var $head = $('<div class="review-rule-category-head"></div>').appendTo($list);
    $('<span class="review-rule-category-title"></span>').text(active.name).appendTo($head);
    $('<span class="review-cat-chip"></span>')
        .text(CATEGORY_REQUIREMENTS[active.key] || '四性=完整性').appendTo($head);
    var descText = CATEGORY_DESCS[active.key] || active.desc || '';
    if (descText) {
      $('<div class="review-rule-category-desc"></div>').text(descText).appendTo($list);
    }

    renderParamLine($list, active.key, currentTemplate(state), state.templateComplete, state, rerender);

    if (active.key === 'piece_combo') {
      // 内容组合类的表格即「二级目录组合表」，不再重复一行规则表
      renderCatalog($list, state, rerender);
    } else if (active.key === 'complete' && hasCompleteTemplate(state)) {
      // 完备性主表：渲染平台下发的模板件类型清单（与引擎判定同一份快照）
      renderCompleteTable($list, state);
    } else if (active.key === 'seal') {
      // 签章与签署主表：覆盖粒度逐行下拉（默认取设计稿字面值），随校审参数保存
      renderSealTable($list, state, rerender);
    } else if (active.key === 'order') {
      // 顺序与时限主表：件序基线来自模板快照，件清单不全时只展示已有条目、不判缺件
      renderOrderTable($list, state, rerender);
    } else {
      renderRuleTable($list, active, state, $note, onChange);
    }

    updateNote($note, state);
  }

  /** 参数行：完备性/签章按设计稿渲染可编辑控件，其余页签保持只读摘要。 */
  function renderParamLine($list, key, tpl, complete, state, rerender) {
    if (key === 'complete') {
      renderCompleteParams($list, state);
      return;
    }
    if (key === 'seal') {
      renderSealParams($list, state);
      return;
    }
    if (key === 'order') {
      renderOrderParams($list, state);
      return;
    }
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

  /** 参数行里的一个字段（label 包住控件，点文字即可操作） */
  function paramField($line, label) {
    var $f = $('<label class="review-param-field"></label>').appendTo($line);
    $('<span class="review-param-label"></span>').text(label).appendTo($f);
    return $f;
  }

  function paramNumber($line, label, value, onChange) {
    var $inp = $('<input type="number" step="0.01" min="0" max="1" class="review-param-input"/>')
        .val(value).appendTo(paramField($line, label));
    $inp.on('change', function() {
      var v = parseFloat($(this).val());
      v = isNaN(v) ? value : Math.min(1, Math.max(0, v));
      $(this).val(v);
      onChange(v);
    });
  }

  function paramCheckbox($line, label, checked, onChange) {
    // 方框在文字前（与设计稿一致）：☑ 附件并入主件
    var $f = $('<label class="review-param-field"></label>').appendTo($line);
    var $cb = $('<input type="checkbox"/>').prop('checked', !!checked).appendTo($f);
    $('<span class="review-param-label"></span>').text(label).appendTo($f);
    $cb.on('change', function() {
      onChange($(this).is(':checked'));
    });
  }

  /** 完备性参数：四个控件的值写回 state.params，随校审参数一并保存 */
  function renderCompleteParams($list, state) {
    var p = state.params;
    var $line = $('<div class="review-param-line"></div>').appendTo($list);
    paramNumber($line, '缺件匹配相似度', p.matchThreshold, function(v) {
      p.matchThreshold = v;
      state.paramsSaved = true;
    });
    paramCheckbox($line, '附件并入主件（不单独占件）', p.mergeAttachments, function(v) {
      p.mergeAttachments = v;
      state.paramsSaved = true;
    });
    paramCheckbox($line, '证物袋需标注名称 / 数量 / 特征 / 来源', p.pouchElements, function(v) {
      p.pouchElements = v;
      state.paramsSaved = true;
    });
    var $sel = $('<select class="review-param-select"></select>')
        .appendTo(paramField($line, '缺件阈值告警'));
    [['warning', '只告警'], ['error', '阻断']].forEach(function(opt) {
      $('<option></option>').val(opt[0]).text(opt[1]).appendTo($sel);
    });
    $sel.val(p.missingAction === 'error' ? 'error' : 'warning');
    $sel.on('change', function() {
      p.missingAction = String($(this).val());
      state.paramsSaved = true;
    });
  }

  /** 签章参数：置信度读模板值，覆盖粒度在表格内逐行选择 */
  function renderSealParams($list, state) {
    // 设计稿的黄色说明条：先讲清「只声明、不重复检测」这一口径
    $('<div class="review-seal-note"></div>')
        .text('印章 / 指纹 / 签字由 AIMP 统一检测一次；本页只声明「要求什么签章、覆盖到哪」，不重复检测。')
        .appendTo($list);
    var s = state.seal;
    var $line = $('<div class="review-param-line"></div>').appendTo($list);
    paramNumber($line, '印章置信度', s.sealConfidence, function(v) {
      s.sealConfidence = v;
    });
    paramNumber($line, '签名置信度', s.signConfidence, function(v) {
      s.signConfidence = v;
    });
    var $cov = $('<span class="review-param"></span>').appendTo($line);
    $('<b></b>').text('覆盖粒度').appendTo($cov);
    $('<span></span>').text('：逐页 / 结尾 / 首末页 / 件内一处（在表格中逐行选择）').appendTo($cov);
    paramCheckbox($line, '允许「签名或捺印」替代', s.allowSignatureAsSeal, function(v) {
      s.allowSignatureAsSeal = v;
    });
  }

  /** 顺序与时限参数：件序基线 / 偏差处理 / 版本号 / 日期链开关 */
  function renderOrderParams($list, state) {
    var o = state.order;
    var $line = $('<div class="review-param-line"></div>').appendTo($list);
    var $base = $('<select class="review-param-select"></select>')
        .appendTo(paramField($line, '件序基线'));
    ORDER_BASELINES.forEach(function(opt) {
      $('<option></option>').val(opt.code).text(opt.label).appendTo($base);
    });
    $base.val(o.baseline);
    $base.on('change', function() {
      o.baseline = String($(this).val());
    });
    var $dev = $('<select class="review-param-select"></select>')
        .appendTo(paramField($line, '偏差处理'));
    [['warning', 'warning 不阻断'], ['error', 'error 阻断']].forEach(function(opt) {
      $('<option></option>').val(opt[0]).text(opt[1]).appendTo($dev);
    });
    $dev.val(o.onDeviation === 'error' ? 'error' : 'warning');
    $dev.on('change', function() {
      o.onDeviation = String($(this).val());
    });
    var $ver = $('<input type="text" class="review-param-input"/>')
        .val(o.baselineVersion || 'v3')
        .appendTo(paramField($line, '版本号'));
    $ver.on('change', function() {
      o.baselineVersion = String($(this).val());
    });
    paramCheckbox($line, '启用日期链', o.dateChain, function(v) {
      o.dateChain = v;
    });
  }

  function paramRows(key, tpl) {
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

  /** 是否有平台下发的完备性模板快照（有则主表按模板清单渲染）。 */
  function hasCompleteTemplate(state) {
    return !!(state.templateComplete && state.templateComplete.items
        && state.templateComplete.items.length);
  }

  /**
   * 完备性主表：件类型 / 别名集 / 必需可选 / 条件 / 判定状态。
   * 数据只来自平台随校审下发的模板快照（与本页参数行、引擎判定同源），OR 侧不另存一份口径。
   */
  function renderCompleteTable($list, state) {
    var complete = state.templateComplete;
    var items = complete.items || [];
    var $table = $('<table class="review-rule-table review-complete-table"></table>').appendTo($list);
    var $hr = $('<tr></tr>').appendTo($('<thead></thead>').appendTo($table));
    ['件类型', '别名集', '必需/可选', '条件', '判定状态'].forEach(function(h) {
      $('<th></th>').text(h).appendTo($hr);
    });
    var $tbody = $('<tbody></tbody>').appendTo($table);
    items.forEach(function(item) {
      var pending = item.status === 'pending';
      var $tr = $('<tr></tr>').appendTo($tbody);
      if (pending) {
        $tr.addClass('review-rule-row-disabled');
      }
      $('<td></td>').text(item.title || item.level2 || '').appendTo($tr);
      var aliases = item.aliases || [];
      $('<td class="review-rule-desc"></td>')
          .text(aliases.length ? aliases.join('、') : '—')
          .appendTo($tr);
      $('<td></td>').text(item.required === false ? '可选' : '必需').appendTo($tr);
      $('<td class="review-rule-desc"></td>').text(conditionText(item.condition)).appendTo($tr);
      var $status = $('<td></td>').appendTo($tr);
      $('<span class="review-rule-status"></span>')
          .addClass(pending ? 'locked' : 'ok')
          .text(pending ? '待开放' : '已开放')
          .appendTo($status);
    });

    var $foot = $('<div class="review-complete-foot"></div>').appendTo($list);
    $('<span class="review-template-hint"></span>')
        .text('模板内共 ' + items.length + ' 件')
        .appendTo($foot);
    $('<button type="button" class="button review-template-btn" disabled="disabled"></button>')
        .text('+ 添加件类型')
        .attr('title', '件类型由平台模板库维护')
        .appendTo($foot);
  }

  /**
   * 签章与签署主表：件类型 / 签章类型 / 覆盖粒度（下拉）/ 角色位 / 判定状态。
   * 覆盖粒度默认选设计稿字面值，可在行内下拉调整；底部「+ 添加签章要求」可追加人工补充项。
   */
  function renderSealTable($list, state, rerender) {
    if (!state.seal.custom) {
      state.seal.custom = [];
    }
    var $table = $('<table class="review-rule-table review-seal-table"></table>').appendTo($list);
    var $hr = $('<tr></tr>').appendTo($('<thead></thead>').appendTo($table));
    ['件类型', '签章类型', '覆盖粒度', '角色位', '判定状态'].forEach(function(h) {
      $('<th></th>').text(h).appendTo($hr);
    });
    var $tbody = $('<tbody></tbody>').appendTo($table);

    /** 覆盖粒度下拉：默认取传入值（新增行默认「末页」），改动写回调用方 */
    function coverageSelect($cell, value, onPick) {
      var $sel = $('<select class="review-param-select review-seal-coverage"></select>').appendTo($cell);
      COVERAGE_OPTIONS.forEach(function(opt) {
        $('<option></option>').val(opt).text(opt).appendTo($sel);
      });
      $sel.val(value);
      $sel.on('change', function() {
        onPick(String($(this).val()));
      });
    }

    SEAL_REQUIREMENTS.forEach(function(row, i) {
      var $tr = $('<tr></tr>').appendTo($tbody);
      if (!row.enabled) {
        $tr.addClass('review-rule-row-disabled');
      }
      $('<td></td>').text(row.title).appendTo($tr);
      $('<td></td>').text(row.kind).appendTo($tr);
      coverageSelect($('<td></td>').appendTo($tr), state.seal.rows[i] || row.coverage, function(v) {
        state.seal.rows[i] = v;
      });
      $('<td></td>').text(row.roles).appendTo($tr);
      $('<span class="review-rule-status"></span>')
          .addClass(row.enabled ? 'ok' : 'locked')
          .text(row.enabled ? '已开放' : '待开放')
          .appendTo($('<td></td>').appendTo($tr));
    });

    // 人工追加的签章要求：文本字段直接在行内编辑
    state.seal.custom.forEach(function(row) {
      var $tr = $('<tr></tr>').appendTo($tbody);
      function textCell(placeholder, key) {
        var $inp = $('<input type="text" class="review-seal-input"/>')
            .attr('placeholder', placeholder).val(row[key] || '')
            .appendTo($('<td></td>').appendTo($tr));
        $inp.on('change', function() {
          row[key] = String($(this).val());
        });
      }
      textCell('件类型', 'title');
      textCell('签章类型', 'kind');
      coverageSelect($('<td></td>').appendTo($tr), row.coverage || '末页', function(v) {
        row.coverage = v;
      });
      textCell('角色位', 'roles');
      $('<span class="review-rule-status ok"></span>').text('已开放')
          .appendTo($('<td></td>').appendTo($tr));
    });

    var $foot = $('<div class="review-complete-foot"></div>').appendTo($list);
    $('<button type="button" class="button review-template-btn"></button>')
        .text('+ 添加签章要求')
        .on('click', function() {
          state.seal.custom.push({ title: '', kind: '', coverage: '末页', roles: '' });
          rerender();
        })
        .appendTo($foot);
    $('<button type="button" class="button review-template-btn"></button>')
        .text('恢复模板默认')
        .on('click', function() {
          state.seal = defaultSeal(currentTemplate(state), null);
          rerender();
        })
        .appendTo($foot);
  }

  /** 件序基线条目：取模板快照里的件清单（自带 order/required），按序号升序；来源缺失时为空。 */
  function orderBaselineItems(state) {
    var complete = state.templateComplete;
    var items = (complete && complete.items) ? complete.items.slice() : [];
    items = items.filter(function(it) { return !!(it && it.title); });
    items.sort(function(a, b) {
      return (Number(a.order) || 0) - (Number(b.order) || 0);
    });
    return items;
  }

  /** 新增件序的序号：基线最大序号 + 已插入行数 + 1 */
  function nextOrderNo(items, custom) {
    var max = 0;
    items.concat(custom || []).forEach(function(it) {
      max = Math.max(max, Number(it.order) || 0);
    });
    return max + 1;
  }

  /**
   * 顺序与时限主表：序号 / 件题名 / 必需 / 允许缺省 / 判定状态。
   * 基线取自平台随任务下发的模板快照（complete.items 自带 order/required）。
   * 提交目录只有中间一件或几件时照常展示模板基线，不由本页判缺件、不刷件序告警。
   */
  function renderOrderTable($list, state, rerender) {
    if (!state.order.custom) {
      state.order.custom = [];
    }
    var items = orderBaselineItems(state);

    var $table = $('<table class="review-rule-table review-order-table"></table>').appendTo($list);
    var $hr = $('<tr></tr>').appendTo($('<thead></thead>').appendTo($table));
    ['序号', '件题名', '必需', '允许缺省', '判定状态'].forEach(function(h) {
      $('<th></th>').text(h).appendTo($hr);
    });
    var $tbody = $('<tbody></tbody>').appendTo($table);

    items.forEach(function(item) {
      var pending = item.status === 'pending';
      var optional = item.required === false;
      var $tr = $('<tr></tr>').appendTo($tbody);
      if (pending) {
        $tr.addClass('review-rule-row-disabled');
      }
      $('<td class="review-order-no"></td>').text(item.order != null ? item.order : '').appendTo($tr);
      $('<td></td>').text(item.title).appendTo($tr);
      $('<td></td>').text(optional ? '否' : '是').appendTo($tr);
      $('<td></td>').text(optional ? '是' : '否').appendTo($tr);
      $('<span class="review-rule-status"></span>')
          .addClass(pending ? 'locked' : 'ok')
          .text(pending ? '待开放' : '已开放')
          .appendTo($('<td></td>').appendTo($tr));
    });

    // 人工插入的件序：只登记基线声明，文本行内编辑
    state.order.custom.forEach(function(row) {
      var $tr = $('<tr></tr>').appendTo($tbody);
      $('<td class="review-order-no"></td>').text(row.order).appendTo($tr);
      var $inp = $('<input type="text" class="review-seal-input"/>')
          .attr('placeholder', '件题名').val(row.title || '')
          .appendTo($('<td></td>').appendTo($tr));
      $inp.on('change', function() {
        row.title = String($(this).val());
      });
      var $req = $('<select class="review-param-select"></select>')
          .appendTo($('<td></td>').appendTo($tr));
      ['是', '否'].forEach(function(v) {
        $('<option></option>').val(v).text(v).appendTo($req);
      });
      $req.val(row.required === false ? '否' : '是');
      $req.on('change', function() {
        row.required = String($(this).val()) !== '否';
        rerender();
      });
      $('<td></td>').text(row.required === false ? '是' : '否').appendTo($tr);
      $('<span class="review-rule-status ok"></span>').text('已开放')
          .appendTo($('<td></td>').appendTo($tr));
    });

    if (!items.length && !state.order.custom.length) {
      $('<tr></tr>').append($('<td colspan="5" class="review-combo-empty-cell"></td>')
          .text('当前任务未下发件序基线（旧任务）；重新启动校审后按模板展示。'))
          .appendTo($tbody);
    }

    renderOrderChain($list);

    var $foot = $('<div class="review-complete-foot"></div>').appendTo($list);
    $('<span class="review-template-hint"></span>')
        .text(items.length
            ? '模板内共 ' + items.length + ' 项'
                + (state.order.custom.length ? '，另插入 ' + state.order.custom.length + ' 项' : '')
            : '')
        .appendTo($foot);
    var $btns = $('<span class="review-order-btns"></span>').appendTo($foot);
    $('<button type="button" class="button review-template-btn"></button>')
        .text('+ 插入件序')
        .on('click', function() {
          state.order.custom.push({
            order: nextOrderNo(items, state.order.custom),
            title: '',
            required: true
          });
          rerender();
        })
        .appendTo($btns);
    $('<button type="button" class="button review-template-btn" disabled="disabled"></button>')
        .text('导入基线（CSV）')
        .attr('title', '件序基线由平台模板库维护')
        .appendTo($btns);
  }

  /** 日期链与办理时限：只读声明（成文日期由提交目录带入后才开始判定） */
  function renderOrderChain($list) {
    var $chain = $('<div class="review-order-chain"></div>').appendTo($list);
    DATE_CHAIN.forEach(function(node, i) {
      if (i > 0) {
        $('<span class="review-order-arrow"></span>').text('≤').appendTo($chain);
      }
      $('<b></b>').text(node).appendTo($chain);
    });
    var $limits = $('<div class="review-order-chain"></div>').appendTo($list);
    TIME_LIMIT_BADGES.forEach(function(text) {
      $('<span class="review-order-limit"></span>').text(text).appendTo($limits);
    });
  }

  /** 条件列文案：件上内联条件优先，形如「公告送达 → 需下落不明证明」。 */
  function conditionText(condition) {
    if (!condition) {
      return '—';
    }
    if (typeof condition === 'string') {
      return condition;
    }
    var when = condition.whenAny || condition.when || [];
    var req = condition.requires || condition.required || [];
    var w = when.length ? when.join(' / ') : '';
    var r = req.length ? req.join(' / ') : '';
    if (w && r) {
      return w + ' → 需' + r;
    }
    return w || r || '—';
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
    // 页签级参数随规则一并落盘：挂在首条规则的 options 上（后端 parseRules 只取 key/name，
    // 未知字段原样保留在任务登记里），重开页面时由 createState 回填
    if (items.length) {
      items[0].options = { complete: state.params, seal: state.seal, order: state.order };
    }
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
