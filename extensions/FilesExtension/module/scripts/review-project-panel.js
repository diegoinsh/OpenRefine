/**
 * 数据校审 - 项目页「校审参数」tab
 *
 * 与新建项目页「校审模式」的 review-config-panel 同构（复用 ReviewConfigPanelUI 的
 * 页签/勾选渲染与收集逻辑），保存写回校审任务登记（subCommand=save-rules），不启动校审。
 */
var ReviewProjectPanel = (function() {

  var panel = null;
  var tab = null;
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

    initialized = true;
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

    var running = (task.status === 'running');
    if (running) {
      elmts.hint.text('校审任务进行中，暂时不能修改参数；请等待完成或停止后再保存。');
    } else if (!task.rootPath) {
      elmts.hint.text('该项目尚未建立校审任务，请先在校审控制台创建校审项目。');
    } else {
      elmts.hint.text('保存后的规则对之后启动的校审任务生效，已完成的校审结果不会回写。');
    }

    ReviewConfigPanelUI.render(elmts.tabs, elmts.list, elmts.note, state);

    var actions = $('<div class="review-params-actions"></div>').appendTo(panel);
    elmts.saveButton = $('<button class="button"></button>')
      .text('保存')
      .prop('disabled', running || !task.rootPath)
      .on('click', save)
      .appendTo(actions);
    elmts.error = $('<span class="review-params-error"></span>').appendTo(actions);
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
    fetchTask(render);
  }

  return {
    open: open,
    refresh: open
  };
})();
