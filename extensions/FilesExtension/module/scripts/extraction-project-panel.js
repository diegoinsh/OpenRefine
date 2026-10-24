/**
 * 抽取扩展 - 项目页「抽取参数」tab
 *
 * 表单与抽取任务列表的「编辑抽取参数」弹窗同构（复用 ExtractionConsoleUI 的渲染/收集逻辑），
 * 保存写回抽取任务登记（subCommand=save-task），不启动任务。
 */
var ExtractionProjectPanel = (function() {

  var panel = null;
  var tab = null;
  var elmts = null;
  var host = null;
  var initialized = false;

  function projectId() {
    return theProject.id;
  }

  function projectName() {
    return (theProject.metadata && theProject.metadata.name)
        ? theProject.metadata.name : ('#' + theProject.id);
  }

  function initialize() {
    if (initialized || typeof TabManager === 'undefined') {
      return;
    }
    panel = $('<div id="extraction-params-panel"></div>')
      .addClass('main-view-panel-tab')
      .appendTo('#right-panel');

    tab = $('<div></div>')
      .addClass('main-view-panel-tab-header')
      .attr('href', '#extraction-params-panel')
      .text('抽取参数')
      .appendTo('#tool-panel');

    TabManager.registerTabGroup('extraction-tabs', '#tool-panel');
    TabManager.registerTab('extraction-tabs', tab, '#extraction-params-panel', function() {
      // 切回 tab 时不强制重载，避免覆盖未保存的编辑
    }, 5);

    host = Object.create(Refine.ExtractionConsoleUI.prototype);
    host._config = {};
    initialized = true;
  }

  /** 从抽取任务登记表取当前项目的参数（没有则空对象）。 */
  function fetchTask(callback) {
    Refine.wrapCSRF(function(token) {
      $.post('command/files/batch-extraction',
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

  function render(task) {
    panel.empty();

    var frame = $(DOM.loadHTML('files', 'scripts/index/extraction-task-dialog.html'));
    frame.find('.dialog-header, .dialog-footer').hide();
    // 根节点本身就是 .dialog-frame（find 不包含自身），直接在根上去掉弹窗外框样式
    frame.removeClass('dialog-frame').addClass('extraction-params-embed');
    frame.appendTo(panel);
    elmts = DOM.bind(frame);

    elmts.dialogHeader.text('抽取参数');
    elmts.rootPathLabel.text('根目录（不可修改）');
    elmts.batchProjectNameLabel.text($.i18n('files-import/batch-project-name'));
    elmts.batchModeRowLabel.text('抽取模式');
    elmts.batchArchiveSectionLabel.text($.i18n('files-import/batch-archive-section'));
    elmts.archiveCategoryLabel.text($.i18n('files-import/batch-archive-category'));
    elmts.archiveSubCategoryLabel.text($.i18n('files-import/batch-archive-sub-category'));
    elmts.extractionElementsLabel.text($.i18n('files-import/batch-extraction-elements'));
    elmts.batchCustomElementsLabel.text($.i18n('files-import/batch-custom-elements'));
    elmts.elNameHeader.text($.i18n('files-import/batch-el-name'));
    elmts.elKeyHeader.text($.i18n('files-import/batch-el-key'));
    elmts.elActionHeader.text($.i18n('files-import/batch-el-action'));
    elmts.elDescHeader.text($.i18n('files-import/batch-el-desc'));
    elmts.addRowButton.text($.i18n('files-import/batch-add-row'));
    elmts.batchClearCacheLabel.text($.i18n('files-import/batch-clear-cache-resume'));

    host._renderTaskForm(elmts, task, { name: projectName() });

    var actions = $('<div class="extraction-params-actions"></div>').appendTo(panel);
    if (!task.rootPath) {
      $('<span class="extraction-params-hint"></span>')
        .text('该项目尚未建立抽取任务，请先在抽取任务控制台登记源目录。')
        .appendTo(actions);
    }
    // 与数据质量「质量规则」页的保存按钮一致：普通 button，左对齐
    $('<button class="button"></button>')
      .text('保存')
      .on('click', save)
      .appendTo(actions);
  }

  function save() {
    var params = host._collectTaskForm(elmts);
    if (!params.rootPath) {
      elmts.errorText.text('根目录为空：请先在抽取任务控制台创建任务并登记源目录。').show();
      return;
    }
    var payload = $.extend({ project: projectId() }, params);
    payload.subCommand = 'save-task';
    payload.fixedElements = JSON.stringify(params.fixedElements);
    payload.customElements = JSON.stringify(params.customElements);

    Refine.wrapCSRF(function(token) {
      payload.csrf_token = token;
      $.post('command/files/batch-extraction', payload, function(data) {
        if (data && data.code === 'ok') {
          elmts.errorText.hide();
          open();
        } else {
          elmts.errorText.text((data && data.message) ? data.message : '保存失败').show();
        }
      }, 'json').fail(function(xhr) {
        elmts.errorText.text('请求失败（HTTP ' + xhr.status + '）').show();
      });
    });
  }

  function open() {
    initialize();
    if (!initialized) {
      return;
    }
    TabManager.switchTab('extraction-tabs', tab);
    fetchTask(render);
  }

  return {
    open: open,
    refresh: open
  };
})();