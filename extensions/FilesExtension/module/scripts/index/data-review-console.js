/*
 * Files extension - 数据校审控制台
 *
 * 列出全部数据校审任务（新建项目页「校审模式」下发起）。一行 = 一个校审项目：
 * 名称 / 校审目录 / 规则 / 进度 / 通过 / 不通过 / 无法判定 / 状态 / 启动校审（图标按钮，
 * 与「数据抽取」列表的启动/停止交互一致；项目名本身链接可进入项目）。
 *
 * 数据源：DataReviewTaskRegistry（workspace/data-review-tasks.json）经
 * `command/files/data-review?subCommand=list` 输出，在跑任务由后端并入实时计数。
 * 「格式数据校审」页签为占位，本期不实现。
 */

// 项目页也会加载本文件；项目页没有 actionAreas 注册表，这里兜底。
Refine.actionAreas = Refine.actionAreas || [];

Refine.DataReviewConsoleUI = function(elmt) {
  this._elmt = elmt;
  this._elmts = null;
  this._projects = {};
  this._tasks = [];
  this._currentTab = 'review';
  this._timer = null;
  this._loading = false;

  elmt.html(DOM.loadHTML('files', 'scripts/index/data-review-console.html'));
  this._elmts = DOM.bind(elmt);

  this._initTabs();
  var self = this;
  this._elmt.find('#dr-refresh-tasks').on('click', function() { self._load(); });
  this._load();
  this._startAutoRefresh();
};

Refine.DataReviewConsoleUI.prototype.resize = function() {
};

/* ---------------- Tab ---------------- */

Refine.DataReviewConsoleUI.prototype._initTabs = function() {
  var self = this;
  this._elmt.find('.dq-tab').on('click', function() {
    self._switchTab($(this).data('tab'));
  });
};

Refine.DataReviewConsoleUI.prototype._switchTab = function(tabId) {
  this._currentTab = tabId;
  this._elmt.find('.dq-tab').removeClass('active');
  this._elmt.find('.dq-tab[data-tab="' + tabId + '"]').addClass('active');
  this._elmt.find('.dq-tab-panel').removeClass('active');
  this._elmt.find('#dr-panel-' + tabId).addClass('active');
  if (tabId === 'review') {
    this._startAutoRefresh();
  } else {
    this._stopAutoRefresh();
  }
};

/* ---------------- 数据加载 ---------------- */

Refine.DataReviewConsoleUI.prototype._load = function() {
  var self = this;
  if (this._loading) return;
  this._loading = true;
  $.getJSON('command/core/get-all-project-metadata', null, function(data) {
    self._projects = (data && data.projects) || {};
    self._loadTasks();
  }).fail(function() {
    self._projects = {};
    self._loadTasks();
  });
};

Refine.DataReviewConsoleUI.prototype._loadTasks = function() {
  var self = this;
  Refine.wrapCSRF(function(token) {
    $.post('command/files/data-review',
      { subCommand: 'list', csrf_token: token },
      function(data) {
        self._loading = false;
        self._tasks = (data && data.code === 'ok' && data.tasks) ? data.tasks : [];
        self._render();
      }, 'json').fail(function() {
        self._loading = false;
        self._tasks = [];
        self._render();
      });
  });
};

Refine.DataReviewConsoleUI.prototype._startAutoRefresh = function() {
  var self = this;
  this._stopAutoRefresh();
  this._timer = window.setInterval(function() {
    if (self._currentTab === 'review') self._load();
  }, 5000);
};

Refine.DataReviewConsoleUI.prototype._stopAutoRefresh = function() {
  if (this._timer) {
    window.clearInterval(this._timer);
    this._timer = null;
  }
};

/* ---------------- 渲染 ---------------- */

Refine.DataReviewConsoleUI.prototype._render = function() {
  var container = this._elmt.find('#dr-task-list');
  container.empty();

  var running = 0, finished = 0, passed = 0, failed = 0, unknown = 0;
  var self = this;
  this._tasks.forEach(function(task) {
    if (task.status === 'running') running++;
    else finished++;
    passed += Number(task.passed || 0);
    failed += Number(task.failed || 0);
    unknown += Number(task.unknown || 0);
  });
  this._elmt.find('#dr-summary').text('共 ' + this._tasks.length + ' 个校审任务 · '
      + running + ' 个进行中 · 通过 ' + passed + ' · 不通过 ' + failed + ' · 无法判定 ' + unknown);

  if (this._tasks.length === 0) {
    $('<div class="dq-empty-message">暂无数据校审任务。请在「新建项目 → 本电脑的原始文件」中选择「文件校审」发起。</div>')
      .appendTo(container);
    return;
  }

  var table = $(
    '<table class="tablesorter-blue list-table"><thead><tr>' +
    '<th></th>' +
    '<th>开始时间</th>' +
    '<th>项目名称</th>' +
    '<th>校审目录</th>' +
    '<th>校审规则</th>' +
    '<th>状态 / 进度</th>' +
    '<th>通过</th>' +
    '<th>不通过</th>' +
    '<th>无法判定</th>' +
    '<th>操作</th>' +
    '</tr></thead><tbody id="dr-table-body"></tbody></table>'
  ).appendTo(container);

  var tbody = table.find('#dr-table-body');
  this._tasks.forEach(function(task) {
    var projectId = String(task.projectId);
    var project = self._projects[projectId] || {};
    var tr = $('<tr>').addClass('project').attr('data-project-id', projectId).appendTo(tbody);

    // 删除任务：X 按钮与「数据抽取」列表一致；运行中禁止删除
    var deleteCell = $('<td/>').appendTo(tr);
    if (task.status !== 'running') {
      $('<a/>')
        .addClass('delete-project')
        .attr('title', '删除任务')
        .attr('href', 'javascript:void(0)')
        .html('<img src="images/close-ry.png" />')
        .on('click', function(e) {
          e.preventDefault();
          var name = project.name || task.projectName || ('#' + projectId);
          if (window.confirm('确定要删除校审任务 "' + name + '" 吗？项目与校审结果将一并删除，此操作不可撤销！')) {
            Refine.wrapCSRF(function(token) {
              $.post('command/files/data-review',
                { subCommand: 'delete', project: projectId, csrf_token: token },
                function(data) {
                  if (data && data.code === 'ok') {
                    self._load();
                  } else {
                    window.alert('删除失败：' + ((data && data.message) ? data.message : '未知错误'));
                  }
                }, 'json');
            });
          }
        })
        .appendTo(deleteCell);
    }

    $('<td/>').text(self._formatDate(task.startedAt)).appendTo(tr);

    var nameCell = $('<td/>').appendTo(tr);
    if (project.name || task.projectName) {
      $('<a/>').addClass('project-name')
        .attr('href', 'project?project=' + projectId)
        .text(project.name || task.projectName)
        .appendTo(nameCell);
    } else {
      nameCell.text('#' + projectId + '（项目已删除）');
    }

    $('<td/>').text(task.rootPath || '—').appendTo(tr);
    $('<td/>').text(self._formatRules(task.rules)).appendTo(tr);
    $('<td/>').html(self._renderStatus(task)).appendTo(tr);
    $('<td/>').text(Number(task.passed || 0)).appendTo(tr);
    $('<td/>').text(Number(task.failed || 0)).appendTo(tr);
    $('<td/>').text(Number(task.unknown || 0)).appendTo(tr);

    var opsCell = $('<td/>').addClass('dr-task-ops').appendTo(tr);
    // 启动校审：图标按钮与「数据抽取」列表的启动抽取一致；运行中不显示
    if (task.status !== 'running') {
      $('<button>')
        .addClass('dq-btn-icon')
        .attr('title', '启动校审')
        .html('<img src="images/extensions/circle-play-regular-full.svg" alt="启动" width="18" height="18" />')
        .on('click', function() { self._start(task); })
        .appendTo(opsCell);
    }
    if (task.status === 'running' && task.live) {
      $('<button>')
        .addClass('dq-btn-icon dq-btn-danger')
        .attr('title', '停止')
        .html('<img src="images/extensions/stop-solid-full.svg" alt="停止" width="18" height="18" />')
        .on('click', function() { self._cancel(projectId); })
        .appendTo(opsCell);
    }
  });
};

Refine.DataReviewConsoleUI.prototype._formatRules = function(rules) {
  if (!rules) return '—';
  try {
    var arr = typeof rules === 'string' ? JSON.parse(rules) : rules;
    var names = (arr || []).map(function(r) { return r.name || r.key; });
    return names.length > 0 ? names.join('、') : '—';
  } catch (e) {
    return String(rules);
  }
};

Refine.DataReviewConsoleUI.prototype._renderStatus = function(task) {
  var status = task.status || 'pending';
  var total = Number(task.totalFiles || 0);
  var done = Number(task.processedFiles || 0);
  if (status === 'running') {
    var pct = total > 0 ? Math.max(0, Math.min(100, Math.round(done * 100 / total))) : 0;
    return '<div class="dq-progress-bar"><div class="dq-progress-fill" style="width: ' + pct + '%"></div></div>'
      + '<span class="dq-progress-text">' + done + ' / ' + total + ' 个文件'
      + (task.currentFile ? ' · ' + task.currentFile : '') + '</span>';
  }
  switch (status) {
    case 'completed':
      return '<span class="dq-status-completed">✓ 完成 (' + total + ' 个文件)</span>';
    case 'cancelled':
      return '<span class="dq-status-cancelled">⊘ 已取消</span>';
    case 'failed':
      return '<span class="dq-status-failed">✗ 失败' + (task.message ? '：' + task.message : '') + '</span>';
    case 'interrupted':
      return '<span class="dq-status-failed">⊘ 已中断 (' + done + ' / ' + total + ' 个文件)</span>';
    default:
      return '<span class="dq-status-pending">待开始</span>';
  }
};

Refine.DataReviewConsoleUI.prototype._cancel = function(projectId) {
  var self = this;
  Refine.wrapCSRF(function(token) {
    $.post('command/files/data-review',
      { subCommand: 'cancel', project: projectId, csrf_token: token },
      function() { self._load(); }, 'json');
  });
};

/** 启动校审：沿用该任务原有的目录与规则重新发起（与「数据抽取」的启动按钮同一交互） */
Refine.DataReviewConsoleUI.prototype._start = function(task) {
  var self = this;
  if (!task || !task.rootPath) {
    window.alert('该任务缺少校审目录，无法启动');
    return;
  }
  var rules = task.rules;
  if (typeof rules !== 'string') rules = JSON.stringify(rules || []);
  Refine.wrapCSRF(function(token) {
    $.post('command/files/data-review', {
      subCommand: 'start',
      rootPath: task.rootPath,
      projectName: task.projectName || '',
      rules: rules,
      csrf_token: token
    }, function(data) {
      if (!data || data.code === 'error') {
        window.alert(data && data.message ? data.message : '启动校审失败');
        return;
      }
      self._load();
    }, 'json');
  });
};

Refine.DataReviewConsoleUI.prototype._formatDate = function(value) {
  if (!value) return '';
  var d = new Date(value);
  if (isNaN(d.getTime())) return String(value);
  var pad = function(n) { return (n < 10 ? '0' : '') + n; };
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
};

Refine.actionAreas.push({
  id: 'data-review',
  label: '数据校审',
  uiClass: Refine.DataReviewConsoleUI
});
