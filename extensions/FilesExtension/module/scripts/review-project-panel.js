/**
 * 数据校审 - 项目页「校审参数」tab
 *
 * 与新建项目页「校审模式」的 review-config-panel 同构（复用 ReviewConfigPanelUI 的
 * 页签/勾选渲染与收集逻辑），保存写回校审任务登记（subCommand=save-rules），不启动校审。
 */
var ReviewProjectPanel = (function() {

  var panel = null;
  var tab = null;
  var resultsPanel = null;
  var resultsTab = null;
  var dataTab = null;
  var state = null;
  var initialized = false;
  var currentTask = null;
  var elmts = null;

  function projectId() {
    return theProject.id;
  }

  function initialize() {
    if (initialized || typeof TabManager === 'undefined') {
      return;
    }
    panel = $('<div id="review-params-panel"></div>')
      .addClass('main-view-panel-tab')
      .appendTo('#right-panel');

    // 校审结果面板：与数据表/校审参数并列，形态复用合规的检查结果 tab
    resultsPanel = $('<div id="review-results-panel"></div>')
      .addClass('main-view-panel-tab')
      .appendTo('#right-panel');

    tab = $('<div></div>')
      .addClass('main-view-panel-tab-header')
      .attr('href', '#review-params-panel')
      .text('校审参数')
      .appendTo('#tool-panel');

    resultsTab = $('<div></div>')
      .addClass('main-view-panel-tab-header')
      .attr('href', '#review-results-panel')
      .text($.i18n('files-import/review-results-tab-header'))
      .appendTo('#tool-panel');

    TabManager.registerTabGroup('review-tabs', '#tool-panel');
    TabManager.registerTab('review-tabs', tab, '#review-params-panel', function() {
      // 切回 tab 时不强制重载，避免覆盖未保存的编辑
    }, 6);
    TabManager.registerTab('review-tabs', resultsTab, '#review-results-panel', function() {
      // 每次切到结果 tab 都重取：结果覆盖写、只保留最新一轮
      loadResults();
    }, 7);

    // 与数据质量扩展同构：数据网格需要一个独立的「数据」页签（指向 #view-panel）。
    // 质量扩展初始化有延迟时它可能还没建这个页签，切到校审参数后就回不到数据界面，
    // 因此本组先补一个；质量扩展稍后建好同名页签时，由下面的观察器移除我们补的那个。
    ensureDataTab();

    positionPanel();
    $(window).on('resize.reviewParams', positionPanel);
    // 质量扩展可能在本面板之后才注册页签；页签增删会改变工具条实际高度，
    // 监听变化重新对齐，避免面板盖住新增的那一行页签。
    //
    // 只监听 childList：绝不能监听 attributes。positionPanel 自身会写面板样式，
    // 而工具条的 style/class 又会被预览面板等模块反复改写（预览打开时压缩宽度、
    // 关闭时清空），两者互相触发会形成无限反馈环——实测 8 秒内触发 2000+ 次，
    // 主线程被占满，页面白屏且完全无响应。
    if (window.MutationObserver) {
      var positionScheduled = false;
      var schedulePosition = function() {
        if (positionScheduled) {
          return;
        }
        positionScheduled = true;
        var run = function() {
          positionScheduled = false;
          positionPanel();
        };
        if (window.requestAnimationFrame) {
          window.requestAnimationFrame(run);
        } else {
          setTimeout(run, 16);
        }
      };
      new MutationObserver(function(mutations) {
        // 只在子节点增删时重排；属性变化一律忽略
        var structural = false;
        for (var i = 0; i < mutations.length; i++) {
          if (mutations[i].type === 'childList') {
            structural = true;
            break;
          }
        }
        if (!structural) {
          return;
        }
        // 质量扩展晚于本面板初始化时会再建一个「数据」页签，去掉我们临时补的那个
        if (dataTab && $('#tool-panel .quality-data-tab').length > 1) {
          dropDataTab();
        }
        schedulePosition();
      }).observe($('#tool-panel')[0], {
        childList: true,
        subtree: true
      });
    }
    initialized = true;
  }

  /**
   * 补一个「数据」页签（与数据质量扩展 quality-alignment 的 _dataTab 同构）。
   * 质量扩展已初始化时它已建好并注册在 quality-tabs，直接复用，不重复建。
   */
  function ensureDataTab() {
    if (dataTab || $('#tool-panel .quality-data-tab').length > 0) {
      return;
    }
    dataTab = $('<div></div>')
      .addClass('main-view-panel-tab-header')
      .addClass('quality-data-tab')
      .attr('href', '#view-panel')
      .text('数据')
      .insertBefore('#summary-bar');
    TabManager.registerTab('review-tabs', dataTab, '#view-panel', function() {
      // 只是回到数据网格，不需要额外动作
    }, 0);
  }

  /** 质量扩展已接管「数据」页签：撤掉本组临时补的那个，避免出现两个同名页签 */
  function dropDataTab() {
    if (!dataTab) {
      return;
    }
    dataTab.off('click.tabmanager');
    var group = TabManager._tabGroups['review-tabs'];
    if (group) {
      group.tabs = group.tabs.filter(function(tabData) {
        return !tabData.tab.is(dataTab);
      });
    }
    dataTab.remove();
    dataTab = null;
  }

  /**
   * 让面板贴住数据网格区域：top 取工具条各页签的实际底边（#tool-panel 高度固定为 34px，
   * 页签多到换行时会溢出到下方，只看 outerHeight 会盖住第二行页签，导致点不回数据界面）。
   * 面板是绝对定位的（见 review-project-panel.css），不依赖 #view-panel 是否已被
   * TabManager 隐藏——质量扩展初始化有延迟时，view-panel 仍占满右侧面板，
   * 正常流里的面板会被挤到可视区下方（表现为「tab 出现了但没有任何内容」）。
   */
  function positionPanel() {
    if (!panel) {
      return;
    }
    // 参数面板与结果面板上下对齐，取同一 top
    var applyTop = function(target, top) {
      if (target && target[0].style.top !== top) {
        target.css('top', top);
      }
    };
    // 嵌入形态把整个工具条隐藏了（display:none），此时面板应从顶部铺满整块区域，
    // 不能再去取工具条高度——隐藏元素的 outerHeight 不可靠，会让面板整体下移一条。
    var toolPanel = $('#tool-panel');
    if (toolPanel.length === 0 || toolPanel.css('display') === 'none') {
      applyTop(panel, '0px');
      applyTop(resultsPanel, '0px');
      return;
    }
    var bottom = 0;
    toolPanel.children().each(function() {
      if (!$(this).is(':visible')) {
        return;
      }
      var childBottom = this.offsetTop + $(this).outerHeight();
      if (childBottom > bottom) {
        bottom = childBottom;
      }
    });
    if (bottom <= 0) {
      bottom = $('#tool-panel').outerHeight() || 0;
    }
    // 值没变就不写：写样式会触发订阅工具条变化的观察者，无谓的样式写入会放大反馈环
    var top = bottom + 'px';
    applyTop(panel, top);
    applyTop(resultsPanel, top);
  }

  /** 从校审任务登记表取当前项目的参数（没有则空对象）。 */
  function fetchTask(callback) {
    Refine.wrapCSRF(function(token) {
      $.post('command/files/data-review',
        { subCommand: 'list', csrf_token: token },
        function(data) {
          var task = null;
          if (data && data.code === 'ok' && data.tasks) {
            for (var i = 0; i < data.tasks.length; i++) {
              if (String(data.tasks[i].projectId) === String(projectId())) {
                task = data.tasks[i];
                break;
              }
            }
          }
          callback(task || {});
        }, 'json').fail(function() {
          callback({});
        });
    });
  }

  /** 已保存的规则清单；格式非法或为空时返回 null（由 UI 按默认全勾处理） */
  function parseSavedRules(task) {
    if (!task || !task.rules) {
      return null;
    }
    try {
      var rules = (typeof task.rules === 'string') ? JSON.parse(task.rules) : task.rules;
      return (rules && rules.length > 0) ? rules : null;
    } catch (e) {
      return null;
    }
  }

  function render(task) {
    currentTask = task;
    state = ReviewConfigPanelUI.createState(parseSavedRules(task));
    // 模板标识随任务落盘（平台下发 templateId）：项目页把模板栏还原成选中状态，
    // 各页签参数按模板回显，不再显示「按模板默认」。
    if (task.templateId) {
      state.templateId = task.templateId;
    }
    // 完备性页签的件类型清单：用平台随任务下发的模板快照（与引擎判定同源），
    // 旧任务没有该字段时回退到 4 个规则开关的表单。
    state.templateComplete = task.templateComplete || null;
    // 内容组合类页签的判定输入：优先用平台随任务下发的「下游本次提交目录」，
    // 未下发时回退到件清单摘要（旧任务）；两者都为只读展示。
    var catalog = (task.catalog && task.catalog.items) ? task.catalog : null;
    state.catalog = catalog ? catalog.items : (task.pieceList || []);
    state.catalogMeta = {
      archiveNo: catalog ? (catalog.archiveNo || catalog.caseNo || '') : '',
      source: task.catalogSource || (catalog ? 'downstream' : ''),
      volumeNo: catalog && catalog.volumes && catalog.volumes.length
        ? (catalog.volumes[0].volumeNo || '') : (catalog ? (catalog.volumeNo || '') : '')
    };

    panel.empty();
    elmts = {};

    var form = $('<div class="batch-config-panel review-params-form"></div>').appendTo(panel);
    $('<h2></h2>').text('校审参数').appendTo(form);

    var rootRow = $('<div class="batch-field-row"></div>').appendTo(form);
    $('<label></label>').text('根目录（不可修改）').appendTo(rootRow);
    $('<span class="review-params-root"></span>')
      .text(task.rootPath || '未登记校审根目录')
      .appendTo(rootRow);

    // 本次提交范围：目录里声明了 submission 才显示（旧任务无该字段，不显示）
    var submission = catalog && catalog.submission;
    if (submission) {
      var subMode = submission.mode === 'full' ? '全卷归档'
        : submission.mode === 'supplement' ? '单件补交' : '随案在建';
      var scopeText = '本次提交 ' + state.catalog.length + ' 件 · ' + subMode;
      if (submission.mode !== 'full') {
        scopeText += '（未提交的必备件只统计，不判缺件）';
      }
      var scopeRow = $('<div class="batch-field-row"></div>').appendTo(form);
      $('<label></label>').text('提交范围').appendTo(scopeRow);
      $('<span class="review-params-root"></span>').text(scopeText).appendTo(scopeRow);
    }

    var ruleRow = $('<div class="batch-field-row"></div>').appendTo(form);
    $('<label></label>').text('校审规则').appendTo(ruleRow);
    elmts.tabs = $('<div class="review-category-tabs"></div>').appendTo(ruleRow);
    elmts.list = $('<div class="review-rule-list"></div>').appendTo(ruleRow);
    elmts.note = $('<div class="review-selected-note"></div>').appendTo(ruleRow);

    elmts.hint = $('<div class="review-params-hint"></div>').appendTo(form);

    refreshHint();

    ReviewConfigPanelUI.render(elmts.tabs, elmts.list, elmts.note, state, refreshHint);

    var actions = $('<div class="review-params-actions"></div>').appendTo(panel);
    var running = (task.status === 'running');
    // 立即校审：与合规「扩展 → 数据质量 → 立即检查...」同口径，按本任务的登记参数
    // （目录 + 规则 + 提交目录）重新跑一轮校审，结果覆盖写并可在「校审结果」页签查看。
    elmts.runButton = $('<button class="button"></button>')
      .text('立即校审')
      .prop('disabled', running || !task.projectId)
      .on('click', openRunDialog)
      .appendTo(actions);
    elmts.saveButton = $('<button class="button"></button>')
      .text('保存')
      .prop('disabled', running || !task.rootPath)
      .on('click', save)
      .appendTo(actions);
    elmts.error = $('<span class="review-params-error"></span>').appendTo(actions);
  }

  /**
   * 参数提示：随勾选变化刷新。
   * 内容组合类依赖件清单——任务是文件级（mode=review-file）时即使勾选也不会有件级结果，
   * 需要平台按抽取项目号（pieceMode）重新启动校审。
   */
  function refreshHint() {
    if (!elmts || !elmts.hint) {
      return;
    }
    var task = currentTask || {};
    if (task.status === 'running') {
      elmts.hint.text('校审任务进行中，暂时不能修改参数；请等待完成或停止后再保存。');
    } else if (!task.rootPath) {
      elmts.hint.text('该项目尚未建立校审任务，请先在校审控制台创建校审项目。');
    } else if (state && state.checked['piece_combo'] && task.mode !== 'review-piece') {
      elmts.hint.text('已选「内容组合类」：当前任务是文件级校审（无件清单，pieceMode=false），'
          + '该项不会产生件级结果；请由平台按抽取项目号启动校审。');
    } else {
      elmts.hint.text('保存后的规则对之后启动的校审任务生效，已完成的校审结果不会回写。');
    }
  }

  // ---- 校审结果 tab ------------------------------------------------------
  // 结果覆盖写、只保留最新一轮，因此每次切到该 tab 都重新取（subCommand=get-result）。

  function loadResults() {
    if (!resultsPanel) {
      return;
    }
    Refine.wrapCSRF(function(token) {
      $.post('command/files/data-review',
        { subCommand: 'get-result', project: projectId(), csrf_token: token },
        function(data) {
          if (data && data.code === 'ok' && data.hasResult && data.result) {
            renderResults(data.result);
          } else if (data && data.code === 'ok') {
            renderResultsMessage('files-import/review-results-empty', false);
          } else {
            renderResultsMessage('files-import/review-results-load-failed', true);
          }
        }, 'json').fail(function() {
          renderResultsMessage('files-import/review-results-load-failed', true);
        });
    });
  }

  function renderResultsMessage(key, isError) {
    resultsPanel.empty();
    $('<div></div>')
      .addClass(isError ? 'review-results-error' : 'review-piece-list-empty')
      .text($.i18n(key))
      .appendTo(resultsPanel);
  }

  /** 本轮结果：摘要 + 规则 + 明细（件级优先，件级缺失时退回文件级） */
  function renderResults(result) {
    resultsPanel.empty();
    var form = $('<div class="batch-config-panel review-results-form"></div>').appendTo(resultsPanel);
    $('<h2></h2>').text($.i18n('files-import/review-results-title')).appendTo(form);

    var summary = result.summary || {};
    $('<div class="review-results-summary"></div>')
      .text($.i18n('files-import/review-results-summary',
        summary.processedPieces || 0, summary.totalPieces || 0,
        summary.passedPieces || 0, summary.failedPieces || 0, summary.unknownPieces || 0))
      .appendTo(form);

    renderCompletenessScope(form, result);

    var ruleNames = (result.rules || []).map(function(rule) {
      return rule.name || rule.key;
    }).filter(function(name) {
      return !!name;
    });
    if (ruleNames.length) {
      $('<div class="review-results-rules"></div>')
        .text($.i18n('files-import/review-results-rules') + ruleNames.join('、'))
        .appendTo(form);
    }

    var pieces = result.pieces || [];
    var files = result.files || [];
    if (pieces.length) {
      renderResultPieces(form, pieces, files, result);
    } else if (files.length) {
      renderResultFiles(form, files);
    } else {
      $('<div class="review-piece-list-empty"></div>')
        .text($.i18n('files-import/review-results-empty')).appendTo(form);
    }
  }

  /**
   * 完备性口径块：本次提交范围、模板来源/版本、必备件三数（已核查/未纳入/待开放）。
   * 老任务结果没有这些字段时整块不显示（结果契约只做附加式扩展）。
   */
  function renderCompletenessScope(form, result) {
    var checklist = result.checklist || {};
    var summary = checklist.summary || {};
    if (!summary.completenessScope && !summary.baselineSource && !result.templateId) {
      return;
    }
    var mode = summary.completenessScope;
    var modeText = mode === 'full' ? '全卷归档'
      : mode === 'supplement' ? '单件补交' : '随案在建';
    var parts = ['完备性口径：' + modeText];
    if (result.templateId) {
      parts.push('模板 ' + result.templateId
        + (result.templateVersion ? '@' + result.templateVersion : ''));
    }
    if (summary.baselineSource === 'template') {
      parts.push('按模板条目判定');
    } else if (summary.baselineSource === 'builtin') {
      parts.push('按内置基线判定');
    }
    if (summary.matchThreshold != null) {
      parts.push('匹配阈值 ' + summary.matchThreshold);
    }
    if (summary.requiredChecked != null) {
      parts.push('必备件已核查 ' + (summary.requiredChecked || 0)
        + ' / 未纳入本次 ' + (summary.outOfScope || 0)
        + (summary.requiredSkipped ? ' / 待开放 ' + summary.requiredSkipped : ''));
    }
    $('<div class="review-results-scope"></div>')
      .text(parts.join(' · '))
      .appendTo(form);

    var skipped = [];
    (checklist.items || []).forEach(function(row) {
      if (row && row.status === 'out-of-scope') {
        skipped.push(row.title || row.level2 || '');
      }
    });
    if (skipped.length) {
      $('<div class="review-results-scope review-results-scope-muted"></div>')
        .text('本次未纳入范围的模板必备件（' + skipped.length + ' 件，只统计不判缺）：'
          + skipped.join('、'))
        .appendTo(form);
    }

    // 相似度落在阈值内但未满 1：列出疑似匹配，提示人工确认；附件并入只给件数，不占独立件
    var suspect = [];
    var merged = 0;
    (checklist.items || []).forEach(function(row) {
      if (!row) {
        return;
      }
      if (row.status === 'suspect') {
        var hit = (row.matchedTitle && row.matchedTitle.length) ? row.matchedTitle[0] : '';
        suspect.push((hit ? hit + ' → ' : '') + (row.title || '')
          + (row.similarity != null ? '（' + row.similarity + '）' : ''));
      } else if (row.status === 'merged') {
        merged++;
      }
    });
    if (suspect.length) {
      $('<div class="review-results-scope review-results-scope-muted"></div>')
        .text('疑似匹配需确认（' + suspect.length + ' 件）：' + suspect.join('、'))
        .appendTo(form);
    }
    if (merged) {
      $('<div class="review-results-scope review-results-scope-muted"></div>')
        .text('附件并入主件（' + merged + ' 件，不单独占件）')
        .appendTo(form);
    }
  }

  /**
   * 件级明细：命中项/缺失项/说明；点行打开该件首页的原件预览。
   *
   * 两种结果形态要一起兼容：
   * - 件级检测（mode=review-piece）：引擎逐件给 present/missing/detail，pages/files 齐备；
   * - 纯数据校审（mode=review-data，平台只下发提交目录）：overlay 里给的是
   *   matched/missing/notes + pageCount，没有 files[]，命中的规则名需要按
   *   checklist.issues 的 rule 归并出来，数据行按件序与目录逐件对应。
   */
  function renderResultPieces(form, pieces, files, result) {
    var box = $('<div class="review-piece-list"></div>').appendTo(form);
    $('<h3></h3>').text($.i18n('files-import/review-results-pieces-title')).appendTo(box);
    var table = $('<table class="review-piece-table review-results-table"></table>').appendTo(box);
    var head = $('<tr></tr>').appendTo($('<thead></thead>').appendTo(table));
    ['piece-no', 'title', 'pages', 'result', 'present', 'missing', 'note'].forEach(function(key) {
      $('<th></th>').text($.i18n('files-import/review-results-col-' + key)).appendTo(head);
    });
    var body = $('<tbody></tbody>').appendTo(table);
    var catalogRows = (result.mode === 'review-data');
    pieces.forEach(function(piece, index) {
      var issues = pieceIssues(result, piece);
      var notes = (piece.notes || []).slice();
      if (!notes.length && piece.detail) {
        notes.push(piece.detail);
      }
      issues.forEach(function(issue) {
        if (issue.message && notes.indexOf(issue.message) === -1) {
          notes.push(issue.message);
        }
      });
      var missing = piece.missing || [];
      if (!missing.length && issues.length) {
        missing = issueLabels(issues);
      }
      var pageCount = (piece.pages && piece.pages.length)
        ? piece.pages.length : (piece.pageCount == null ? null : Number(piece.pageCount));

      var row = $('<tr></tr>').appendTo(body);
      $('<td></td>').text(piece.pieceNo || '').appendTo(row);
      $('<td></td>').text(piece.title || '').appendTo(row);
      $('<td></td>').text(pageCount == null ? '—' : String(pageCount)).appendTo(row);
      $('<td></td>').addClass('review-result-' + (piece.status || 'unknown'))
        .text(statusLabel(piece.status)).appendTo(row);
      $('<td></td>').text(joinNames(piece.present || piece.matched)).appendTo(row);
      $('<td></td>').text(joinNames(missing)).appendTo(row);
      $('<td></td>').text(notes.length ? notes.join('；') : '—').appendTo(row);
      // 纯数据校审没有逐页文件清单，数据行是「一件一行、按目录顺序写入」的，
      // 因此退回到件序号定位；件级检测仍按件首页文件名反查行号。
      var rowIndex = rowIndexOfPiece(piece, files);
      if (rowIndex == null && catalogRows) {
        rowIndex = index;
      }
      bindPreview(row, rowIndex);
    });
  }

  /** 结果里的 checklist.issues 按件归并（scope=volume/level2 的不挂到具体件） */
  function pieceIssues(result, piece) {
    var checklist = result.checklist || {};
    var issues = checklist.issues || [];
    var volumeNo = piece.volumeNo == null ? '' : String(piece.volumeNo);
    var pieceNo = piece.pieceNo == null ? '' : String(piece.pieceNo);
    var out = [];
    for (var i = 0; i < issues.length; i++) {
      var issue = issues[i] || {};
      if (!issue.pieceNo) {
        continue;
      }
      if (String(issue.pieceNo) !== pieceNo) {
        continue;
      }
      if (issue.volumeNo != null && String(issue.volumeNo) !== volumeNo) {
        continue;
      }
      out.push(issue);
    }
    return out;
  }

  var ISSUE_LABELS = {
    missing_piece: '缺件',
    missing_page: '缺页',
    empty_piece: '空件',
    missing_attachment: '条件缺件',
    piece_order: '件序',
    volume_toc: '卷内目录',
    document_element: '文书要素',
    piece_combo: '内容组合'
  };

  /** 问题类型 → 缺失项标签（去重，保持出现顺序） */
  function issueLabels(issues) {
    var labels = [];
    issues.forEach(function(issue) {
      var label = ISSUE_LABELS[issue.rule] || issue.rule;
      if (label && labels.indexOf(label) === -1) {
        labels.push(label);
      }
    });
    return labels;
  }

  /** 文件级明细：一行一个文件；点行打开原件预览 */
  function renderResultFiles(form, files) {
    var box = $('<div class="review-piece-list"></div>').appendTo(form);
    $('<h3></h3>').text($.i18n('files-import/review-results-files-title')).appendTo(box);
    var table = $('<table class="review-piece-table review-results-table"></table>').appendTo(box);
    var head = $('<tr></tr>').appendTo($('<thead></thead>').appendTo(table));
    ['seq', 'file', 'pages', 'rules', 'result', 'note'].forEach(function(key) {
      $('<th></th>').text($.i18n('files-import/review-results-col-' + key)).appendTo(head);
    });
    var body = $('<tbody></tbody>').appendTo(table);
    files.forEach(function(file) {
      var row = $('<tr></tr>').appendTo(body);
      var rowIndex = (file.rowIndex == null) ? null : Number(file.rowIndex);
      $('<td></td>').text(rowIndex == null ? '' : String(rowIndex + 1)).appendTo(row);
      $('<td></td>').text(file.fileName || '').appendTo(row);
      $('<td></td>').text(file.pages == null ? '—' : String(file.pages)).appendTo(row);
      $('<td></td>').text(joinNames(file.rules)).appendTo(row);
      $('<td></td>').text(file.result || '').appendTo(row);
      $('<td></td>').text(file.note || '—').appendTo(row);
      bindPreview(row, rowIndex);
    });
  }

  function bindPreview(row, rowIndex) {
    if (rowIndex == null || rowIndex < 0) {
      return;
    }
    row.addClass('review-results-row').on('click', function() {
      if (typeof FileViewPanel === 'undefined') {
        return;
      }
      FileViewPanel.show(rowIndex, -1);
    });
  }

  /** 件首页的文件名 → 数据行号（件级结果不带行号，用 files[] 反查） */
  function rowIndexOfPiece(piece, files) {
    var first = (piece.pages && piece.pages.length) ? piece.pages[0].f : null;
    if (!first) {
      return null;
    }
    for (var i = 0; i < files.length; i++) {
      if (files[i].fileName === first) {
        return files[i].rowIndex;
      }
    }
    return null;
  }

  function statusLabel(status) {
    if (status === 'passed') {
      return $.i18n('files-import/review-results-passed');
    }
    if (status === 'failed') {
      return $.i18n('files-import/review-results-failed');
    }
    return $.i18n('files-import/review-results-unknown');
  }

  /** 名字清单：引擎给件级的是数组（present/missing），给文件级 rules 的是顿号串 */
  function joinNames(value) {
    if (value == null || value === '') {
      return '—';
    }
    if (typeof value === 'string') {
      return value;
    }
    if (!value.length) {
      return '—';
    }
    var texts = value.map(function(item) {
      return (typeof item === 'string') ? item : (item.name || item.key || '');
    }).filter(function(text) {
      return !!text;
    });
    return texts.length ? texts.join('、') : '—';
  }

  function save() {
    var rules = ReviewConfigPanelUI.collect(state);
    if (rules.length === 0) {
      elmts.error.text('请至少选择一项校审规则').show();
      return;
    }
    var payload = {
      subCommand: 'save-rules',
      project: projectId(),
      rules: JSON.stringify(rules)
    };
    Refine.wrapCSRF(function(token) {
      payload.csrf_token = token;
      $.post('command/files/data-review', payload, function(data) {
        if (data && data.code === 'ok') {
          elmts.error.hide();
          elmts.hint.text('校审参数已保存：对之后启动的校审任务生效，已完成的校审结果不会回写。');
        } else {
          elmts.error.text((data && data.message) ? data.message : '保存失败').show();
        }
      }, 'json').fail(function(xhr) {
        elmts.error.text('请求失败（HTTP ' + xhr.status + '）').show();
      });
    });
  }

  // ---- 立即校审 ----------------------------------------------------------
  // 与合规「扩展 → 数据质量 → 立即检查...」同口径：弹窗说明本次要跑什么，
  // 点「开始校审」用任务登记的目录/规则/提交目录重新发起一轮，结果覆盖写。

  function openRunDialog() {
    if (typeof theProject === 'undefined' || !theProject) {
      window.alert('请先在项目页打开需要校审的项目。');
      return;
    }
    fetchTask(function(task) {
      createRunDialog(task || {});
    });
  }

  function createRunDialog(task) {
    if (!task.projectId) {
      window.alert('当前项目尚未登记校审任务：请先在平台创建校审项目后再运行校审。');
      return;
    }
    // 纯数据校审（平台只下发提交目录）与文件级校审可以在引擎侧重跑；
    // 件级校审的件清单由平台启动时逐件下发，任务记录里只有摘要，无法还原 pieceMap。
    var canRunData = !!task.catalogOnly && !!task.catalog;
    var canRunFile = !canRunData && task.mode === 'review-file' && !!task.rootPath;
    var runnable = canRunData || canRunFile;
    var running = (task.status === 'running');

    var frame = $(
      '<div class="dialog-frame" style="width: 480px;">'
      + '  <div class="dialog-header">运行校审</div>'
      + '  <div class="dialog-body">'
      + '    <p class="review-run-status">点击"开始校审"运行校审任务</p>'
      + '    <p class="review-run-detail"></p>'
      + '    <div class="review-run-progress" style="display: none; height: 8px;'
      + '         background: #e6e6e6; border-radius: 4px; overflow: hidden; margin-top: 10px;">'
      + '      <div class="review-run-progress-fill" style="width: 0; height: 100%;'
      + '           background: #4a90d9; transition: width .2s;"></div>'
      + '    </div>'
      + '  </div>'
      + '  <div class="dialog-footer">'
      + '    <button class="button button-primary" name="start">开始校审</button>'
      + '    <button class="button button-secondary" name="results" style="display: none;">查看结果</button>'
      + '    <button class="button button-secondary" name="cancel">取消</button>'
      + '  </div>'
      + '</div>');

    var level = DialogSystem.showDialog(frame);
    var $status = frame.find('.review-run-status');
    var $detail = frame.find('.review-run-detail');
    var $progress = frame.find('.review-run-progress');
    var $fill = frame.find('.review-run-progress-fill');
    var $start = frame.find('button[name="start"]');
    var $results = frame.find('button[name="results"]');
    var timer = null;

    function dismiss() {
      if (timer) {
        window.clearTimeout(timer);
        timer = null;
      }
      DialogSystem.dismissUntil(level - 1);
    }

    function describe() {
      var pieces = (task.pieceList || []).length;
      if (canRunData) {
        return '按已保存的校审规则，对平台下发的提交目录重新执行校审（'
          + pieces + ' 件）；结果覆盖上一轮，可在「校审结果」页签查看。';
      }
      if (canRunFile) {
        return '按已保存的校审规则重新扫描根目录并检测；结果覆盖上一轮，'
          + '可在「校审结果」页签查看。';
      }
      return '该任务为件级校审（按平台下发的件清单逐件检测），件清单只在平台启动校审时下发，'
        + '无法在引擎侧单独重跑；请在平台重新启动校审。';
    }

    $detail.text(describe());
    if (!runnable) {
      $start.prop('disabled', true);
    }
    if (running) {
      $start.prop('disabled', true);
      $status.text('校审任务进行中，请等待完成…');
      poll();
    }

    function rulesText() {
      var rules = task.rules;
      if (typeof rules === 'string') {
        return rules;
      }
      return JSON.stringify(rules || []);
    }

    function start() {
      $start.prop('disabled', true);
      $status.text('正在启动校审任务…');
      $detail.text('');
      $progress.show();
      $fill.css('width', '0%');

      var payload = {
        subCommand: 'start',
        project: task.projectId,
        projectName: unicodeEscape(task.projectName || ''),
        rootPath: task.rootPath || '',
        rules: unicodeEscape(rulesText()),
        templateId: task.templateId || '',
        templateVersion: task.templateVersion || ''
      };
      if (canRunData) {
        // 服务端按 ISO-8859-1 解码表单参数，中文目录必须先 u 转义
        payload.catalogOnly = 'true';
        payload.catalog = unicodeEscape(JSON.stringify(task.catalog));
      }
      Refine.wrapCSRF(function(token) {
        payload.csrf_token = token;
        $.post('command/files/data-review', payload, function(data) {
          if (!data || data.code === 'error') {
            $status.text('启动失败：' + ((data && data.message) ? data.message : '未知错误'));
            $start.prop('disabled', false);
            $progress.hide();
            return;
          }
          $status.text('校审任务已启动，正在处理…');
          poll();
        }, 'json').fail(function(xhr) {
          $status.text('启动失败（HTTP ' + xhr.status + '）');
          $start.prop('disabled', false);
          $progress.hide();
        });
      });
    }

    function poll() {
      var tick = function() {
        Refine.wrapCSRF(function(token) {
          $.post('command/files/data-review',
            { subCommand: 'progress', project: task.projectId, csrf_token: token },
            function(p) {
              if (!p || p.code === 'error') {
                $status.text('读取进度失败：' + ((p && p.message) ? p.message : '未知错误'));
                return;
              }
              updateProgress(p);
              if (p.status === 'running') {
                timer = window.setTimeout(tick, 1000);
              } else {
                timer = null;
                finish(p);
              }
            }, 'json').fail(function() {
              timer = window.setTimeout(tick, 1500);
            });
        });
      };
      tick();
    }

    function updateProgress(p) {
      var total = Number(p.totalFiles || 0);
      var done = Number(p.processedFiles || 0);
      var pct = total > 0 ? Math.max(0, Math.min(100, Math.round(done * 100 / total))) : 0;
      $progress.show();
      $fill.css('width', pct + '%');
      $status.text('校审中：' + done + ' / ' + total + ' 页'
        + (p.currentFile ? ' · ' + p.currentFile : ''));
    }

    function finish(p) {
      if (p.status === 'completed') {
        $status.text('校审完成，可在「校审结果」页签查看本轮结果。');
        $results.show();
        $start.hide();
        loadResults();
        // 任务重跑会重写项目数据行（纯数据校审重写件目录），必须重新取行，
        // 否则预览仍读内存里的旧「文件夹路径」，点行打不开原件。
        if (typeof ui !== 'undefined' && ui && ui.dataTableView && ui.dataTableView._showRows) {
          ui.dataTableView._showRows({ start: 0 });
        }
      } else if (p.status === 'cancelled') {
        $status.text('校审已取消。');
        $start.prop('disabled', false).show();
      } else if (p.status === 'failed') {
        $status.text('校审失败：' + (p.message || '未知错误'));
        $start.prop('disabled', false).show();
      } else {
        $status.text('校审已结束（' + (p.status || '未知状态') + '）。');
        $start.prop('disabled', false).show();
      }
      $progress.hide();
    }

    $start.on('click', start);
    $results.on('click', function() {
      dismiss();
      if (resultsTab) {
        TabManager.switchTab('review-tabs', resultsTab);
      }
      loadResults();
    });
    frame.find('button[name="cancel"]').on('click', dismiss);
  }

  /** 表单参数里的中文统一 u 转义（服务端用 UnicodeText.decode 还原） */
  function unicodeEscape(text) {
    return String(text).replace(/[\u0080-\uffff]/g, function(ch) {
      return '\\u' + ('0000' + ch.charCodeAt(0).toString(16)).slice(-4);
    });
  }

  function open() {
    initialize();
    if (!initialized) {
      return;
    }
    TabManager.switchTab('review-tabs', tab);
    // 新 tab 加入后 #tool-panel 高度可能变化，切到本 tab 时重新对齐面板顶部
    positionPanel();
    fetchTask(render);
  }

  /**
   * 项目页加载时自动挂载「校审参数 / 校审结果」页签（与合规 quality-alignment 的
   * autoLaunchIfNeeded 同口径）：本项目登记过校审任务就注册页签并预取内容，
   * 停在与合规一致的「数据」视图，用户点页签即可看到规则与结果。
   *
   * 嵌入形态（URL 带 view=review-params / review-preview）由 review-embed.js 负责，
   * 这里不重复处理，避免两个入口争抢页签。
   */
  function autoAttach(attempts) {
    attempts = attempts || 0;
    // 只在项目页执行（导入页等也加载本脚本，那里没有 theProject）
    if (typeof theProject === 'undefined' || !theProject) {
      return;
    }
    var view = null;
    try {
      view = new URLSearchParams(window.location.search).get('view');
    } catch (e) {
      view = null;
    }
    if (view === 'review-params' || view === 'review-preview') {
      return;
    }
    // 等项目页界面初始化完成再挂：抢在前面注册会与随后的 initializeUI 争抢容器
    var ready = (typeof ui !== 'undefined' && ui && ui.dataTableView);
    if (!ready && attempts < 150) {
      window.setTimeout(function() {
        autoAttach(attempts + 1);
      }, 100);
      return;
    }
    fetchTask(function(task) {
      if (!task || !task.projectId) {
        return; // 非校审项目：保持普通项目页原样
      }
      initialize();
      if (!initialized) {
        return;
      }
      render(task);
      // 与合规一致：进入项目停在数据视图，避免看起来「项目是空的」
      if (dataTab) {
        TabManager.switchTab('review-tabs', dataTab);
      }
    });
  }

  return {
    open: open,
    refresh: open,
    autoAttach: autoAttach,
    runDialog: openRunDialog
  };
})();

$(function() {
  if (typeof ReviewProjectPanel !== 'undefined') {
    window.setTimeout(function() {
      ReviewProjectPanel.autoAttach(0);
    }, 800);
  }
});
