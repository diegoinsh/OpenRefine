/**
 * 数据校审 - 项目页「校审参数」tab
 *
 * 与新建项目页「校审模式」的 review-config-panel 同构（复用 ReviewConfigPanelUI 的
 * 页签/勾选渲染与收集逻辑），保存写回校审任务登记（subCommand=save-rules），不启动校审。
 */
var ReviewProjectPanel = (function() {

  var panel = null;
  var tab = null;
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

    tab = $('<div></div>')
      .addClass('main-view-panel-tab-header')
      .attr('href', '#review-params-panel')
      .text('校审参数')
      .appendTo('#tool-panel');

    TabManager.registerTabGroup('review-tabs', '#tool-panel');
    TabManager.registerTab('review-tabs', tab, '#review-params-panel', function() {
      // 切回 tab 时不强制重载，避免覆盖未保存的编辑
    }, 6);

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
    // 嵌入形态把整个工具条隐藏了（display:none），此时面板应从顶部铺满整块区域，
    // 不能再去取工具条高度——隐藏元素的 outerHeight 不可靠，会让面板整体下移一条。
    var toolPanel = $('#tool-panel');
    if (toolPanel.length === 0 || toolPanel.css('display') === 'none') {
      if (panel[0].style.top !== '0px') {
        panel.css('top', '0px');
      }
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
    if (panel[0].style.top !== top) {
      panel.css('top', top);
    }
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

    panel.empty();
    elmts = {};

    var form = $('<div class="batch-config-panel review-params-form"></div>').appendTo(panel);
    $('<h2></h2>').text('校审参数').appendTo(form);

    var rootRow = $('<div class="batch-field-row"></div>').appendTo(form);
    $('<label></label>').text('根目录（不可修改）').appendTo(rootRow);
    $('<span class="review-params-root"></span>')
      .text(task.rootPath || '未登记校审根目录')
      .appendTo(rootRow);

    var ruleRow = $('<div class="batch-field-row"></div>').appendTo(form);
    $('<label></label>').text('校审规则').appendTo(ruleRow);
    elmts.tabs = $('<div class="review-category-tabs"></div>').appendTo(ruleRow);
    elmts.list = $('<div class="review-rule-list"></div>').appendTo(ruleRow);
    elmts.note = $('<div class="review-selected-note"></div>').appendTo(ruleRow);

    elmts.hint = $('<div class="review-params-hint"></div>').appendTo(form);

    // 件级清单（只读）：件数据由平台按抽取项目号/模板下发，随启动任务登记到 record，
    // 参数页直接展示，避免下游还要去平台模板页才能确认「本件有哪些件、每件要求什么」。
    if (task.pieceList && task.pieceList.length) {
      renderPieceList(form, task.pieceList);
    } else if (task.mode === 'review-piece') {
      $('<div class="review-piece-list-empty"></div>')
        .text('件级任务（pieceMode=true），但该任务记录没有件清单（旧任务）；'
            + '由平台按件重新启动校审后，这里会显示本件清单。')
        .appendTo(form);
    }

    refreshHint();

    ReviewConfigPanelUI.render(elmts.tabs, elmts.list, elmts.note, state, refreshHint);

    var actions = $('<div class="review-params-actions"></div>').appendTo(panel);
    var running = (task.status === 'running');
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

  /**
   * 件级清单（只读）：一行一件，展示件号/题名/页数/页文件/要求项（含覆盖方式与必填性）。
   * 数据来自任务记录 pieceList（平台启动校审时随 pieceMap 下发，由引擎摘要登记）。
   */
  function renderPieceList(form, list) {
    var box = $('<div class="review-piece-list"></div>').appendTo(form);
    $('<h3></h3>').text('件级清单（只读，按平台下发的件模板）').appendTo(box);

    var table = $('<table class="review-piece-table"></table>').appendTo(box);
    var head = $('<tr></tr>').appendTo($('<thead></thead>').appendTo(table));
    ['件号', '件题名', '页数', '页文件', '要求项'].forEach(function(text) {
      $('<th></th>').text(text).appendTo(head);
    });

    var body = $('<tbody></tbody>').appendTo(table);
    list.forEach(function(piece) {
      var row = $('<tr></tr>').appendTo(body);
      $('<td></td>').text(piece.pieceNo || '').appendTo(row);
      $('<td></td>').text(piece.title || '').appendTo(row);
      $('<td></td>').text(piece.pages == null ? '' : String(piece.pages)).appendTo(row);
      var files = (piece.pageFiles || []).join('、');
      $('<td class="review-piece-files"></td>').text(files || '—')
        .attr('title', files).appendTo(row);
      var reqs = (piece.rules || []).map(function(rule) {
        var text = rule.name || rule.key;
        if (rule.coverage === 'everyPage') {
          text += '（逐页）';
        }
        if (rule.required === false) {
          text += '（选填）';
        }
        return text;
      });
      $('<td></td>').text(reqs.join('、') || '—').appendTo(row);
    });
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

  return {
    open: open,
    refresh: open
  };
})();
