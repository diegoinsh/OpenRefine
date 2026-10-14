/*
 * Files extension - 数据抽取控制台
 *
 * 与「数据质量」并列的独立任务控制台，界面与交互对齐 data-quality-ui.js：
 *   抽取任务：项目列表（标签/搜索/分页）+ 每行「启动 / 编辑」
 *   高级设置：系统级配置（AIMP 地址、抽取默认值、界面）
 *
 * 抽取任务是「先跑任务、产出项目」，与合规的「先有项目、再跑检查」语义相反，
 * 因此不复用「项目质量任务」的检查语义：这里的一行 = 一个项目的抽取任务。
 */

Refine.ExtractionConsoleUI = function(elmt) {
  this._elmt = elmt;
  this._elmts = null;
  this._config = null;
  this._projects = {};
  this._tasks = {};        // projectId -> 抽取任务记录（登记表 + 内存实时）
  this._currentTag = '';
  this._currentPage = 1;
  this._pageSize = 50;
  this._timer = null;
  this._currentTab = 'tasks';
  this._settingInputs = {};
  this._loading = false;

  elmt.html(DOM.loadHTML('files', 'scripts/index/extraction-console.html'));
  this._elmts = DOM.bind(elmt);

  this._initTabs();
  this._initTasksPanel();
  this._loadConfigAndProjects();
};

Refine.ExtractionConsoleUI.DEFAULT_CONFIG = {
  'aimp.server': 'http://127.0.0.1:7998',
  'aimp.timeout': 30,
  'extraction.pageConcurrency': 4,
  'extraction.defaultCategory': '',
  'extraction.defaultSubCategory': '',
  'extraction.disableCache': false,
  'ui.refreshInterval': 5,
  'ui.pageSize': 50
};

/** 高级设置表单项（与后端 ExtractionConfigStore.defaults 对齐） */
Refine.ExtractionConsoleUI.FIELDS = [
  { group: 'AIMP 服务', items: [
    { key: 'aimp.server', label: '服务地址', type: 'text' },
    { key: 'aimp.timeout', label: '请求超时（秒）', type: 'number', min: 1, max: 300 }
  ]},
  { group: '抽取执行', items: [
    { key: 'extraction.pageConcurrency', label: '诉讼档案页级并发度', type: 'number', min: 1, max: 16 }
  ]},
  { group: '抽取默认值', items: [
    { key: 'extraction.defaultCategory', label: '默认门类', type: 'text' },
    { key: 'extraction.defaultSubCategory', label: '默认子类', type: 'text' },
    { key: 'extraction.disableCache', label: '默认禁用缓存', type: 'checkbox' }
  ]},
  { group: '界面', items: [
    { key: 'ui.refreshInterval', label: '任务刷新间隔（秒）', type: 'number', min: 1, max: 60 },
    { key: 'ui.pageSize', label: '每页任务数', type: 'number', min: 10, max: 200 }
  ]}
];

Refine.ExtractionConsoleUI.prototype.resize = function() {
};

/* ---------------- Tab ---------------- */

Refine.ExtractionConsoleUI.prototype._initTabs = function() {
  var self = this;
  this._elmt.find('.dq-tab').on('click', function() {
    self._switchTab($(this).data('tab'));
  });
};

Refine.ExtractionConsoleUI.prototype._switchTab = function(tabId) {
  this._currentTab = tabId;
  this._elmt.find('.dq-tab').removeClass('active');
  this._elmt.find('.dq-tab[data-tab="' + tabId + '"]').addClass('active');
  this._elmt.find('.dq-tab-panel').removeClass('active');
  this._elmt.find('#ec-panel-' + tabId).addClass('active');

  if (tabId === 'tasks') {
    this._loadProjects();
    this._startAutoRefresh();
  } else {
    this._stopAutoRefresh();
    this._renderSettingsForm();
  }
};

/* ---------------- 任务面板 ---------------- */

Refine.ExtractionConsoleUI.prototype._initTasksPanel = function() {
  var self = this;

  this._elmt.find('#ec-search-icon').on('click', function() {
    var input = self._elmt.find('#ec-search-input');
    var icon = $(this);
    if (input.is(':hidden')) {
      input.show();
      icon.addClass('dq-magnifying-glass-open');
      input.focus();
    } else {
      input.hide();
      input.val('');
      icon.removeClass('dq-magnifying-glass-open');
      self._searchKeyword = '';
      self._currentPage = 1;
      self._renderProjectList();
    }
  });

  var typingTimer;
  this._elmt.find('#ec-search-input').on('keyup', function(e) {
    clearTimeout(typingTimer);
    if (e.key === 'Enter') {
      e.preventDefault();
      self._searchKeyword = $(this).val();
      self._currentPage = 1;
      self._renderProjectList();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      self._elmt.find('#ec-search-icon').click();
    } else {
      typingTimer = setTimeout(function() {
        self._searchKeyword = self._elmt.find('#ec-search-input').val();
        self._currentPage = 1;
        self._renderProjectList();
      }, 500);
    }
  });

  this._elmt.find('#ec-refresh-tasks').on('click', function() {
    self._loadProjects();
  });

  this._currentTag = '';
  this._searchKeyword = '';
};

Refine.ExtractionConsoleUI.prototype._loadConfigAndProjects = function() {
  var self = this;
  $.getJSON('command/files/extraction-config', null, function(data) {
    self._config = (data && data.code === 'ok' && data.config)
        ? data.config : $.extend({}, Refine.ExtractionConsoleUI.DEFAULT_CONFIG);
  }).fail(function() {
    self._config = $.extend({}, Refine.ExtractionConsoleUI.DEFAULT_CONFIG);
  }).always(function() {
    self._pageSize = parseInt(self._config['ui.pageSize'], 10) || 50;
    self._renderSettingsForm();
    self._loadProjects();
    self._startAutoRefresh();
  });
};

Refine.ExtractionConsoleUI.prototype._loadProjects = function() {
  var self = this;
  $.getJSON('command/core/get-all-project-metadata', null, function(data) {
    self._projects = data.projects || {};
    self._currentPage = 1;
    // 标签与列表都在任务状态就绪后构建：计数与行都只统计本域（抽取）任务的项目
    self._loadAllTasksStatus();
  });
};

/** 任务状态：以登记表为准（含历史），在跑的任务由后端并入实时页数 */
Refine.ExtractionConsoleUI.prototype._loadAllTasksStatus = function() {
  var self = this;
  if (this._loading) return;
  this._loading = true;
  Refine.wrapCSRF(function(token) {
    $.post('command/files/batch-extraction',
      { subCommand: 'list', csrf_token: token },
      function(data) {
        self._loading = false;
        self._tasks = {};
        if (data && data.code === 'ok' && data.tasks) {
          data.tasks.forEach(function(task) {
            if (task.projectId != null) {
              self._tasks[String(task.projectId)] = task;
            }
          });
        }
        self._buildTagsList();
        self._renderProjectList();
      }, 'json').fail(function() {
        self._loading = false;
        self._buildTagsList();
        self._renderProjectList();
      });
  });
};

/* ---------------- 标签 / 过滤 ---------------- */

Refine.ExtractionConsoleUI.prototype._buildTagsList = function() {
  var self = this;
  var counts = {};
  var total = 0;
  for (var id in this._projects) {
    if (!this._tasks[String(id)]) continue;
    total++;
    var tags = this._projects[id].tags || [];
    tags.forEach(function(tag) {
      counts[tag] = (counts[tag] || 0) + 1;
    });
  }
  this._tagCounts = counts;

  var ul = this._elmt.find('#ec-tags-ul').empty();
  this._appendTagItem(ul, '', '全部', total);
  Object.keys(counts).sort().forEach(function(tag) {
    self._appendTagItem(ul, tag, tag, counts[tag]);
  });
};

Refine.ExtractionConsoleUI.prototype._appendTagItem = function(ul, tag, label, count) {
  var self = this;
  var li = $('<li/>').appendTo(ul);
  $('<a href="javascript:void(0)"></a>')
    .text(label + ' (' + count + ')')
    .toggleClass('current', tag === this._currentTag)
    .on('click', function() {
      self._currentTag = tag;
      self._currentPage = 1;
      ul.find('a').removeClass('current');
      $(this).addClass('current');
      self._renderProjectList();
    })
    .appendTo(li);
};

Refine.ExtractionConsoleUI.prototype._filteredProjectIds = function() {
  var self = this;
  var keyword = (this._searchKeyword || '').toLowerCase();
  var ids = [];
  for (var id in this._projects) {
    var project = this._projects[id];
    // 任务分域：本列表只显示「有抽取任务」的项目；质量检查等其他任务不在此出现
    // （想看到全部项目请用「打开项目」）
    if (!this._tasks[String(id)]) continue;
    if (this._currentTag) {
      var tags = project.tags || [];
      if (tags.indexOf(this._currentTag) === -1) continue;
    }
    if (keyword && String(project.name || '').toLowerCase().indexOf(keyword) === -1) continue;
    ids.push(id);
  }
  ids.sort(function(a, b) {
    var ma = self._projects[a].modified ? new Date(self._projects[a].modified).getTime() : 0;
    var mb = self._projects[b].modified ? new Date(self._projects[b].modified).getTime() : 0;
    return mb - ma;
  });
  return ids;
};

/* ---------------- 渲染 ---------------- */

Refine.ExtractionConsoleUI.prototype._renderProjectList = function() {
  var self = this;
  var container = this._elmt.find('#ec-project-list');
  container.empty();

  var ids = this._filteredProjectIds();
  if (ids.length === 0) {
    $('<div class="dq-empty-message">暂无项目</div>').appendTo(container);
    return;
  }

  var totalItems = ids.length;
  var totalPages = Math.max(1, Math.ceil(totalItems / this._pageSize));
  if (this._currentPage > totalPages) this._currentPage = totalPages;
  if (this._currentPage < 1) this._currentPage = 1;
  var startIndex = (this._currentPage - 1) * this._pageSize;
  var pageIds = ids.slice(startIndex, startIndex + this._pageSize);

  this._renderPagination(container, totalItems, totalPages);

  var table = $(
    '<table class="tablesorter-blue list-table"><thead><tr>' +
    '<th></th>' +
    '<th>上次修改</th>' +
    '<th>项目名称</th>' +
    '<th>标签</th>' +
    '<th>最后抽取</th>' +
    '<th>任务状态</th>' +
    '<th>操作</th>' +
    '</tr></thead><tbody id="ec-table-body"></tbody></table>'
  ).appendTo(container);

  var tbody = table.find('#ec-table-body');

  pageIds.forEach(function(projectId) {
    var project = self._projects[projectId];
    var tags = project.tags || [];

    var tr = $('<tr>')
      .addClass('project')
      .attr('data-project-id', projectId)
      .appendTo(tbody);

    // 删除项目
    var deleteCell = $('<td/>').appendTo(tr);
    $('<a/>')
      .addClass('delete-project')
      .attr('title', '删除项目')
      .attr('href', 'javascript:void(0)')
      .html('<img src="images/close-ry.png" />')
      .on('click', function(e) {
        e.preventDefault();
        if (window.confirm('确定要删除项目 "' + project.name + '" 吗？此操作不可撤销！')) {
          Refine.postCSRF('command/core/delete-project', { project: projectId }, function(data) {
            if (data && data.code === 'ok') {
              self._loadProjects();
            } else {
              alert('删除失败：' + ((data && data.message) ? data.message : '未知错误'));
            }
          }, 'json');
        }
      })
      .appendTo(deleteCell);

    // 上次修改
    $('<td/>').addClass('last-modified').text(self._formatDate(project.modified)).appendTo(tr);

    // 项目名称：与数据质量任务列表一致，在本页打开
    var nameCell = $('<td/>').appendTo(tr);
    $('<a/>')
      .addClass('project-name')
      .attr('href', 'project?project=' + projectId)
      .text(project.name || ('#' + projectId))
      .appendTo(nameCell);

    // 标签
    var tagsCell = $('<td/>').appendTo(tr);
    tags.forEach(function(tag) {
      $('<span/>').addClass('project-tag').text(tag).appendTo(tagsCell);
    });

    // 最后抽取 / 任务状态 / 操作（随任务状态刷新）
    $('<td/>').addClass('ec-last-run').appendTo(tr);
    $('<td/>').addClass('ec-task-status').appendTo(tr);
    var opsCell = $('<td/>').addClass('ec-task-ops').appendTo(tr);

    self._updateProjectRow(projectId, tr, opsCell);
  });
};

Refine.ExtractionConsoleUI.prototype._updateProjectRow = function(projectId, tr, opsCell) {
  var task = this._tasks[String(projectId)];
  tr.find('.ec-last-run').text(task ? this._formatDate(task.finishedAt || task.startedAt || task.configuredAt) : '—');
  tr.find('.ec-task-status').html(this._renderTaskStatus(task));
  this._renderActionButtons(opsCell, projectId, task);
};

Refine.ExtractionConsoleUI.prototype._renderTaskStatus = function(task) {
  if (!task) {
    return '<span class="dq-status-none">无任务</span>';
  }
  var status = task.status || 'pending';
  var total = Number(task.totalPages || 0);
  var done = Number(task.processedPages || 0);

  if (status === 'running') {
    var pct = total > 0 ? Math.max(0, Math.min(100, Math.round(done * 100 / total))) : 0;
    return '<div class="dq-progress-bar">'
      + '<div class="dq-progress-fill" style="width: ' + pct + '%"></div>'
      + '</div><span class="dq-progress-text">'
      + (total > 0 ? (done + ' / ' + total + ' 页') : (task.message || '进行中'))
      + '</span>';
  }
  switch (status) {
    case 'interrupted':
      // 进程被直接关闭（断电/杀进程）留下的任务：已识别页在页级缓存里，再次启动即续跑
      return '<span class="dq-status-failed">⊘ 已中断'
        + (total > 0 ? ' (' + done + ' / ' + total + ' 页)' : '') + ' · 可续跑</span>';
    case 'paused':
      return '<span class="dq-status-paused">⏸ 已暂停'
        + (total > 0 ? ' (' + done + ' / ' + total + ' 页)' : '') + '</span>';
    case 'completed':
      return '<span class="dq-status-completed">✓ 完成'
        + (total > 0 ? ' (' + total + ' 页)' : '') + '</span>';
    case 'failed':
      return '<span class="dq-status-failed">✗ 失败</span>';
    case 'cancelled':
      return '<span class="dq-status-cancelled">⊘ 已取消</span>';
    case 'pending':
    default:
      return '<span class="dq-status-pending">待开始</span>';
  }
};

Refine.ExtractionConsoleUI.prototype._renderActionButtons = function(container, projectId, task) {
  var self = this;
  container.empty();
  var status = task ? task.status : null;

  // 启动：无任务或已结束（完成/失败/取消/待开始/已中断）时可启动；运行中/已暂停不显示
  if (status !== 'running' && status !== 'paused') {
    var resumable = status === 'interrupted';
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', resumable ? '继续抽取（断点续跑）' : '启动抽取')
      .html('<img src="images/extensions/circle-play-regular-full.svg" alt="启动" />')
      .on('click', function() { self._openTaskDialog(projectId, true); })
      .appendTo(container);
  }

  // 暂停：运行中可暂停（断点保留，恢复后接着跑）
  if (status === 'running') {
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', '暂停')
      .html('<img src="images/extensions/pause-solid-full.svg" alt="暂停" />')
      .on('click', function() { self._controlTask(projectId, 'pause'); })
      .appendTo(container);
  }

  // 恢复：已暂停可恢复
  if (status === 'paused') {
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', '恢复')
      .html('<img src="images/extensions/circle-play-regular-full.svg" alt="恢复" />')
      .on('click', function() { self._controlTask(projectId, 'resume'); })
      .appendTo(container);
  }

  // 停止：运行中/已暂停可停止
  if (status === 'running' || status === 'paused') {
    $('<button>')
      .addClass('dq-btn-icon dq-btn-danger')
      .attr('title', '停止')
      .html('<img src="images/extensions/stop-solid-full.svg" alt="停止" />')
      .on('click', function() { self._cancelTask(projectId); })
      .appendTo(container);
  }

  // 编辑：始终显示（对齐数据质量：规则编辑常驻）
  $('<button>')
    .addClass('dq-btn-icon')
    .attr('title', '编辑抽取参数')
    .html('<img src="images/extensions/pen-to-square-regular-full.svg" alt="编辑" />')
    .on('click', function() { self._openTaskDialog(projectId, false); })
    .appendTo(container);
};

/** 暂停 / 恢复（对齐数据质量 task-control 的 pause / resume） */
Refine.ExtractionConsoleUI.prototype._controlTask = function(projectId, action) {
  var self = this;
  Refine.wrapCSRF(function(token) {
    $.post('command/files/batch-extraction',
      { subCommand: action, project: projectId, csrf_token: token },
      function(data) {
        if (data && data.code === 'ok') {
          self._loadAllTasksStatus();
        } else {
          alert((action === 'pause' ? '暂停' : '恢复') + '失败：'
            + ((data && data.message) ? data.message : '未知错误'));
        }
      }, 'json').fail(function(xhr) {
        alert('请求失败（HTTP ' + xhr.status + '）');
      });
  });
};

Refine.ExtractionConsoleUI.prototype._cancelTask = function(projectId) {
  var self = this;
  if (!window.confirm('确定要停止当前抽取任务吗？已识别的部分会保留，之后可再次启动继续。')) {
    return;
  }
  Refine.wrapCSRF(function(token) {
    $.post('command/files/batch-extraction',
      { subCommand: 'cancel', project: projectId, csrf_token: token },
      function() { self._loadAllTasksStatus(); }, 'json');
  });
};

Refine.ExtractionConsoleUI.prototype._renderPagination = function(container, totalItems, totalPages) {
  var self = this;
  var bar = $('<div class="dq-pagination"></div>');
  var startIndex = (this._currentPage - 1) * this._pageSize;
  var endIndex = Math.min(startIndex + this._pageSize, totalItems);
  $('<span class="dq-pagination-info">显示 ' + (startIndex + 1) + '-' + endIndex + ' / 共 ' + totalItems + ' 项</span>').appendTo(bar);

  var btns = $('<span class="dq-pagination-btns"></span>').appendTo(bar);
  var addBtn = function(label, page, disabled) {
    $('<button class="dq-btn dq-btn-sm"></button>')
      .text(label)
      .prop('disabled', disabled)
      .on('click', function() {
        self._currentPage = page;
        self._renderProjectList();
      })
      .appendTo(btns);
  };
  addBtn('首页', 1, this._currentPage <= 1);
  addBtn('上一页', this._currentPage - 1, this._currentPage <= 1);
  $('<span class="dq-pagination-current">第 ' + this._currentPage + ' / ' + totalPages + ' 页</span>').appendTo(btns);
  addBtn('下一页', this._currentPage + 1, this._currentPage >= totalPages);
  addBtn('末页', totalPages, this._currentPage >= totalPages);

  bar.prependTo(container);
};

/* ---------------- 启动 / 编辑（抽取参数对话框） ---------------- */

Refine.ExtractionConsoleUI.prototype._openTaskDialog = function(projectId, thenStart) {
  var self = this;
  var task = this._tasks[String(projectId)] || {};
  var project = this._projects[String(projectId)] || {};
  var frame = $(DOM.loadHTML('files', 'scripts/index/extraction-task-dialog.html'));
  var elmts = DOM.bind(frame);

  // 标签与「新建项目 → 本地目录」的批量配置面板用同一批 i18n 键，保证文字一致
  elmts.dialogHeader.text(thenStart ? '启动抽取任务' : '编辑抽取参数');
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
  elmts.confirmButton.text(thenStart ? '启动抽取' : '保存');

  this._renderTaskForm(elmts, task, project);

  var level = DialogSystem.showDialog(frame);

  elmts.cancelButton.on('click', function() {
    DialogSystem.dismissUntil(level - 1);
  });

  elmts.confirmButton.on('click', function() {
    var params = self._collectTaskForm(elmts);
    var errors = [];
    if (!params.projectName) errors.push($.i18n('files-import/batch-project-name-required'));
    if (!params.rootPath) errors.push('根目录不能为空');
    if (params.fixedElements.length === 0) errors.push($.i18n('files-import/batch-elements-required'));
    if (errors.length > 0) {
      elmts.errorText.text(errors.join('；')).show();
      return;
    }
    elmts.errorText.hide();
    elmts.confirmButton.prop('disabled', true);

    var payload = $.extend({ project: projectId }, params);
    payload.subCommand = thenStart ? 'start' : 'save-task';
    payload.fixedElements = JSON.stringify(params.fixedElements);
    payload.customElements = JSON.stringify(params.customElements);

    Refine.wrapCSRF(function(token) {
      payload.csrf_token = token;
      $.post('command/files/batch-extraction', payload, function(data) {
        elmts.confirmButton.prop('disabled', false);
        if (data && data.code === 'ok') {
          DialogSystem.dismissUntil(level - 1);
          self._loadProjects();
        } else {
          elmts.errorText.text((data && data.message) ? data.message : '操作失败').show();
        }
      }, 'json').fail(function(xhr) {
        elmts.confirmButton.prop('disabled', false);
        elmts.errorText.text('请求失败（HTTP ' + xhr.status + '）').show();
      });
    });
  });
};

/* ---------------- 抽取参数表单（与 batch-config-panel 同构） ---------------- */

/** 要素键：与「新建项目 → 本地目录」的表单同一套算法（拼音或原文 → 下划线小写） */
Refine.ExtractionConsoleUI.prototype._keyFromName = function(name) {
  var pinyin = null;
  if (typeof TinyPinyin !== 'undefined') {
    try {
      if (TinyPinyin.isSupported()) pinyin = TinyPinyin.convertToPinyin(name, '', true);
    } catch (e) {
      pinyin = null;
    }
  }
  var raw = pinyin === null ? String(name) : pinyin;
  var sb = '';
  for (var i = 0; i < raw.length; i++) {
    var c = raw.charAt(i);
    sb += /[a-zA-Z0-9]/.test(c) ? c.toLowerCase() : '_';
  }
  sb = sb.replace(/_+/g, '_').replace(/^_+|_+$/g, '');
  if (/^[0-9]/.test(sb)) sb = 'x' + sb;
  return sb;
};

Refine.ExtractionConsoleUI.prototype._renderTaskForm = function(elmts, task, project) {
  var self = this;
  var config = this._config || {};

  // 根目录：只读，来自任务记录
  elmts.rootPathInput.val(task.rootPath || '');
  elmts.batchProjectName.val(task.projectName || project.name || '');

  elmts.templateSelect.empty();
  $('<option value="batch-title-volume"></option>').text($.i18n('files-import/batch-mode-volume')).appendTo(elmts.templateSelect);
  $('<option value="batch-title-case"></option>').text($.i18n('files-import/batch-mode-case')).appendTo(elmts.templateSelect);
  elmts.templateSelect.val(task.template === 'batch-title-case' ? 'batch-title-case' : 'batch-title-volume');

  Refine.ARCHIVE_CATEGORIES.forEach(function(cat) {
    $('<option></option>').val(cat.value).text($.i18n('files-import/' + cat.i18n)).appendTo(elmts.archiveCategorySelect);
  });

  var desiredCategory = task.archiveCategory || config['extraction.defaultCategory'] || '';
  var desiredSubCategory = task.archiveSubCategory || config['extraction.defaultSubCategory'] || '';

  function refreshSubCategory() {
    var subs = Refine.ARCHIVE_SUB_CATEGORIES[elmts.archiveCategorySelect.val()] || [];
    elmts.archiveSubCategorySelect.empty();
    elmts.archiveSubCategoryRow.toggle(subs.length > 0);
    subs.forEach(function(sub) {
      $('<option></option>').val(sub.value).text($.i18n('files-import/' + sub.i18n)).appendTo(elmts.archiveSubCategorySelect);
    });
    if (desiredSubCategory && subs.length > 0) {
      elmts.archiveSubCategorySelect.val(desiredSubCategory);
      desiredSubCategory = '';
    }
    refreshExtraElements();
  }

  function buildElementOption(key, label, checked, meta) {
    var option = $("<label class='batch-element-option'></label>");
    var checkbox = $("<input type='checkbox' class='batch-element-checkbox'/>").val(key);
    if (checked) checkbox.prop('checked', true);
    checkbox.on('change', function() {
      if (meta) syncOptionalElement(meta, $(this).is(':checked'));
    });
    option.append(checkbox).append($('<span></span>').text(label));
    return option;
  }

  function findCustomRowByKey(key) {
    return elmts.customElementsBody.find('tr').filter(function() {
      return $(this).find('.batch-el-key').val().trim() === key;
    });
  }

  function syncOptionalElement(meta, checked) {
    var existing = findCustomRowByKey(meta.key);
    if (!checked) {
      existing.remove();
      return;
    }
    if (existing.length > 0) return;
    addCustomRow({
      name: $.i18n('files-import/' + meta.nameKey),
      key: meta.key,
      action: 'include',
      description: $.i18n('files-import/' + meta.promptKey)
    });
  }

  function extraElementByKey(key) {
    for (var i = 0; i < Refine.EXTRA_ELEMENTS.length; i++) {
      if (Refine.EXTRA_ELEMENTS[i].key === key) return Refine.EXTRA_ELEMENTS[i];
    }
    return null;
  }

  function refreshExtraElements() {
    elmts.extractionElementsExtraOptions.find('.batch-element-checkbox:checked').each(function() {
      var meta = extraElementByKey($(this).val());
      if (meta && findCustomRowByKey(meta.key).length > 0) syncOptionalElement(meta, false);
    });
    var sub = elmts.archiveSubCategoryRow.is(':visible')
        ? String(elmts.archiveSubCategorySelect.val() || '') : '';
    var extras = Refine.SUB_CATEGORY_EXTRA_ELEMENTS[sub] || [];
    elmts.extractionElementsExtraRow.toggle(extras.length > 0);
    elmts.extractionElementsExtraOptions.empty();
    extras.forEach(function(el) {
      elmts.extractionElementsExtraOptions.append(
          buildElementOption(el.key, $.i18n('files-import/' + el.nameKey), el.checked, el));
    });
  }

  function addCustomRow(data) {
    var d = data || {};
    var tr = $('<tr></tr>');
    var nameInput = $("<input type='text' class='batch-el-name'/>").val(d.name || '');
    var keyInput = $("<input type='text' class='batch-el-key'/>").val(d.key || '');
    if (d.key) keyInput.data('manual', true);
    nameInput.on('input', function() {
      if (tr.find('.batch-el-action').val() === 'adjust') return;
      if (keyInput.data('manual')) return;
      var n = nameInput.val().trim();
      keyInput.val(n ? self._keyFromName(n) : '');
    });
    keyInput.on('input', function() { keyInput.data('manual', true); });

    var actionSelect = $("<select class='batch-el-action'></select>");
    $("<option value='include'></option>").text($.i18n('files-import/batch-el-add')).appendTo(actionSelect);
    $("<option value='adjust'></option>").text($.i18n('files-import/batch-el-adjust')).appendTo(actionSelect);
    if (d.action === 'adjust') actionSelect.val('adjust');
    actionSelect.on('change', function() {
      if (actionSelect.val() === 'adjust') {
        keyInput.val('').prop('disabled', true);
      } else {
        keyInput.prop('disabled', false);
      }
    });

    var removeButton = $("<button type='button' class='button button-light batch-el-remove'>×</button>");
    removeButton.on('click', function() { tr.remove(); });

    tr.append($('<td></td>').append(nameInput));
    tr.append($('<td></td>').append(keyInput));
    tr.append($('<td></td>').append(actionSelect));
    tr.append($('<td></td>').append($("<input type='text' class='batch-el-desc'/>").val(d.description || '')));
    tr.append($('<td></td>').append(removeButton));
    elmts.customElementsBody.append(tr);
    if (actionSelect.val() === 'adjust') keyInput.prop('disabled', true);
    return tr;
  }

  // 固定要素 + 可选要素（可选要素勾选后同步追加自定义要素行）
  Refine.FIXED_ELEMENTS.forEach(function(el) {
    elmts.extractionElementsOptions.append(
        buildElementOption(el.key, $.i18n('files-import/' + el.i18n), el.checked, null));
  });
  Refine.OPTIONAL_ELEMENTS.forEach(function(el) {
    elmts.extractionElementsOptions.append(
        buildElementOption(el.key, $.i18n('files-import/' + el.nameKey), el.checked, el));
    if (el.checked) syncOptionalElement(el, true);
  });

  elmts.archiveCategorySelect.val(desiredCategory || elmts.archiveCategorySelect.val());
  elmts.archiveCategorySelect.on('change', refreshSubCategory);
  refreshSubCategory();

  // 已保存的自定义要素（下次编辑时回填）
  var savedCustom = [];
  if (task.customElements) {
    try { savedCustom = JSON.parse(task.customElements) || []; } catch (e) { savedCustom = []; }
  }
  savedCustom.forEach(function(item) { addCustomRow(item); });
  if (elmts.customElementsBody.children().length === 0) addCustomRow();
  elmts.customElementsSection.toggle(savedCustom.length > 0);

  elmts.addRowButton.on('click', function() { addCustomRow(); });
  elmts.batchCustomElementsLabel.on('click', function() { elmts.customElementsSection.toggle(); });

  // 清除缓存：默认不勾选 —— 不勾选即就地重跑并复用已识别的页（断点续跑）；
  // 只有该任务上次显式勾选过才回填勾选状态。
  elmts.batchClearCache.prop('checked', task.disableCache === true || task.disableCache === 'true');
};

Refine.ExtractionConsoleUI.prototype._collectTaskForm = function(elmts) {
  var fixedKeys = Refine.FIXED_ELEMENTS.map(function(el) { return el.key; });
  var fixedElements = [];
  elmts.extractionElementsOptions.find('.batch-element-checkbox:checked').each(function() {
    var key = $(this).val();
    if (fixedKeys.indexOf(key) >= 0) fixedElements.push(key);
  });

  var customElements = [];
  elmts.customElementsBody.find('tr').each(function() {
    var name = $(this).find('.batch-el-name').val().trim();
    var key = $(this).find('.batch-el-key').val().trim();
    var action = $(this).find('.batch-el-action').val();
    var description = $(this).find('.batch-el-desc').val().trim();
    if (action === 'adjust') {
      if (description) customElements.push({ name: name, key: '', action: action, description: description });
      return;
    }
    if (!name) return;
    if (!key) key = keyFromNameFallback(name);
    customElements.push({ name: name, key: key, action: action, description: description });
  });

  return {
    rootPath: $.trim(elmts.rootPathInput.val()),
    projectName: $.trim(elmts.batchProjectName.val()),
    template: elmts.templateSelect.val(),
    archiveCategory: String(elmts.archiveCategorySelect.val() || ''),
    archiveSubCategory: elmts.archiveSubCategoryRow.is(':visible')
        ? String(elmts.archiveSubCategorySelect.val() || '') : '',
    fixedElements: fixedElements,
    customElements: customElements,
    disableCache: elmts.batchClearCache.is(':checked') ? 'true' : 'false'
  };

  function keyFromNameFallback(name) {
    var sb = '';
    for (var i = 0; i < name.length; i++) {
      var c = name.charAt(i);
      sb += /[a-zA-Z0-9]/.test(c) ? c.toLowerCase() : '_';
    }
    sb = sb.replace(/_+/g, '_').replace(/^_+|_+$/g, '');
    return sb || 'custom';
  }
};

/* ---------------- 高级设置 ---------------- */

Refine.ExtractionConsoleUI.prototype._renderSettingsForm = function() {
  var self = this;
  var config = this._config || {};
  var holder = this._elmt.find('#ec-settings-form');
  if (holder.length === 0) return;
  holder.empty();
  this._settingInputs = {};

  Refine.ExtractionConsoleUI.FIELDS.forEach(function(section) {
    var sectionEl = $('<div class="dq-settings-section"></div>').appendTo(holder);
    $('<div class="dq-settings-section-title"></div>').text(section.group).appendTo(sectionEl);
    var body = $('<div class="dq-settings-section-body"></div>').appendTo(sectionEl);

    section.items.forEach(function(item) {
      var row = $('<div class="dq-settings-row"></div>').appendTo(body);
      $('<label></label>').text(item.label).appendTo(row);
      var input;
      if (item.type === 'checkbox') {
        input = $('<input type="checkbox"/>');
        input.prop('checked', config[item.key] === true || config[item.key] === 'true');
      } else if (item.type === 'number') {
        input = $('<input type="number" class="dq-input dq-input-number"/>');
        if (item.min != null) input.attr('min', item.min);
        if (item.max != null) input.attr('max', item.max);
        input.val(config[item.key] != null ? config[item.key] : '');
      } else {
        input = $('<input type="text" class="dq-input"/>');
        input.val(config[item.key] != null ? config[item.key] : '');
      }
      row.append(input);
      self._settingInputs[item.key] = input;
    });
  });

  var buttons = $('<div class="dq-settings-buttons"></div>').appendTo(holder);
  $('<button class="dq-btn"></button>')
    .text('恢复默认值')
    .on('click', function() {
      self._config = $.extend({}, Refine.ExtractionConsoleUI.DEFAULT_CONFIG);
      self._renderSettingsForm();
    })
    .appendTo(buttons);
  $('<button class="dq-btn dq-btn-primary"></button>')
    .text('保存配置')
    .on('click', function() { self._saveSettings(); })
    .appendTo(buttons);

  $('<div class="dq-settings-tip"></div>')
    .text('配置修改后对新启动的抽取任务生效；AIMP 服务地址会被「本地目录」批量抽取一并使用。'
      + '按页识别的结果会本地缓存，暂停/停止/重启后再次启动即从中断处继续。')
    .appendTo(holder);
};

Refine.ExtractionConsoleUI.prototype._saveSettings = function() {
  var self = this;
  var config = {};
  Refine.ExtractionConsoleUI.FIELDS.forEach(function(section) {
    section.items.forEach(function(item) {
      var input = self._settingInputs[item.key];
      if (!input) return;
      if (item.type === 'checkbox') {
        config[item.key] = input.prop('checked');
      } else if (item.type === 'number') {
        var n = parseInt(input.val(), 10);
        config[item.key] = isNaN(n) ? 0 : n;
      } else {
        config[item.key] = input.val();
      }
    });
  });

  Refine.wrapCSRF(function(token) {
    $.post('command/files/extraction-config',
      { config: JSON.stringify(config), csrf_token: token },
      function(data) {
        if (data && data.code === 'ok') {
          self._config = config;
          self._pageSize = parseInt(config['ui.pageSize'], 10) || 50;
          self._startAutoRefresh();
          alert('配置已保存');
        } else {
          alert('保存失败：' + ((data && data.message) ? data.message : '未知错误'));
        }
      }, 'json').fail(function(xhr) {
        alert('保存失败：HTTP ' + xhr.status);
      });
  });
};

/* ---------------- 自动刷新 ---------------- */

Refine.ExtractionConsoleUI.prototype._startAutoRefresh = function() {
  var self = this;
  this._stopAutoRefresh();
  var seconds = parseInt((this._config || {})['ui.refreshInterval'], 10) || 5;
  this._timer = window.setInterval(function() {
    if (self._currentTab === 'tasks') {
      self._loadAllTasksStatus();
    }
  }, Math.max(1, seconds) * 1000);
};

Refine.ExtractionConsoleUI.prototype._stopAutoRefresh = function() {
  if (this._timer) {
    window.clearInterval(this._timer);
    this._timer = null;
  }
};

/* ---------------- 工具 ---------------- */

Refine.ExtractionConsoleUI.prototype._formatDate = function(value) {
  if (!value) return '';
  var d = new Date(value);
  if (isNaN(d.getTime())) return String(value);
  var pad = function(n) { return (n < 10 ? '0' : '') + n; };
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
};

Refine.actionAreas.push({
  id: 'data-extract',
  label: '数据抽取',
  uiClass: Refine.ExtractionConsoleUI
});
