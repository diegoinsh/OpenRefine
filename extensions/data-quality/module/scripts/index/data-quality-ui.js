/*
 * Data Quality UI - 数据质量导航Tab
 *
 * 提供数据质量任务管理、全局规则管理和配置设置功能
 */

Refine.DataQualityUI = function(elmt) {
  var self = this;

  elmt.html(DOM.loadHTML("data-quality", "scripts/index/data-quality-ui.html"));

  this._elmt = elmt;
  this._elmts = DOM.bind(elmt);

  // 当前选中的Tab
  this._currentTab = 'tasks';

  // 定时刷新器
  this._refreshInterval = null;
  this._refreshIntervalMs = 5000; // 默认5秒刷新

  // 项目数据缓存
  this._projects = {};
  this._tasks = {};

  // 配置数据
  this._config = {};

  // 分页状态
  this._currentPage = 1;
  this._pageSize = 50; // 默认值，会从配置加载

  this._initTabs();
  this._initTasksPanel();
  this._initSettingsPanel();

  // 先加载配置，再加载项目列表
  this._loadConfigAndProjects();

  // 监听页面切换，只在数据质量页面可见时启动刷新
  this._initVisibilityListener();
};

/**
 * 初始化可见性监听
 */
Refine.DataQualityUI.prototype._initVisibilityListener = function() {
  var self = this;

  // 监听 hashchange 事件
  $(window).on('hashchange.dataQuality', function() {
    if (window.location.hash === '#data-quality') {
      // 切换到数据质量页面，启动刷新
      if (self._currentTab === 'tasks') {
        self._loadProjects();
        self._startAutoRefresh();
      }
    } else {
      // 离开数据质量页面，停止刷新
      self._stopAutoRefresh();
    }
  });
};

/**
 * 加载配置后再加载项目列表（确保分页等配置生效）
 */
Refine.DataQualityUI.prototype._loadConfigAndProjects = function() {
  var self = this;

  $.getJSON(
    "command/data-quality/get-config",
    null,
    function(data) {
      self._config = $.extend({}, Refine.DataQualityUI.DEFAULT_CONFIG, data.config || {});
      self._pageSize = self._config['ui.pageSize'] || 50;
      self._refreshIntervalMs = (self._config['ui.refreshInterval'] || 5) * 1000;
      // 配置加载完成后加载项目
      self._loadProjects();
      // 只在页面可见时启动自动刷新
      if (self._isVisible()) {
        self._startAutoRefresh();
      }
    }
  ).fail(function() {
    // 配置加载失败，使用默认值
    self._config = $.extend({}, Refine.DataQualityUI.DEFAULT_CONFIG);
    self._pageSize = self._config['ui.pageSize'] || 50;
    self._refreshIntervalMs = (self._config['ui.refreshInterval'] || 5) * 1000;
    self._loadProjects();
    // 只在页面可见时启动自动刷新
    if (self._isVisible()) {
      self._startAutoRefresh();
    }
  });
};

/**
 * 检查数据质量页面是否可见
 */
Refine.DataQualityUI.prototype._isVisible = function() {
  // 检查 action-area-tab-body 是否可见
  return this._elmt.css('visibility') === 'visible';
};

/**
 * 初始化二级Tab切换
 */
Refine.DataQualityUI.prototype._initTabs = function() {
  var self = this;

  this._elmt.find('.dq-tab').on('click', function() {
    var tabId = $(this).data('tab');
    self._switchTab(tabId);
  });
};

/**
 * 切换Tab
 */
Refine.DataQualityUI.prototype._switchTab = function(tabId) {
  this._currentTab = tabId;

  // 更新Tab样式
  this._elmt.find('.dq-tab').removeClass('active');
  this._elmt.find('.dq-tab[data-tab="' + tabId + '"]').addClass('active');

  // 更新面板显示
  this._elmt.find('.dq-tab-panel').removeClass('active');
  this._elmt.find('#dq-panel-' + tabId).addClass('active');

  // 根据Tab加载数据
  if (tabId === 'tasks') {
    this._loadProjects();
    this._startAutoRefresh();
  } else {
    this._stopAutoRefresh();
    if (tabId === 'settings') {
      this._loadSettings();
    }
  }
};

/**
 * 初始化任务面板 (参考 open-project-ui.js)
 */
Refine.DataQualityUI.prototype._initTasksPanel = function() {
  var self = this;

  // 搜索图标点击
  this._elmt.find('#dq-search-icon').on('click', function() {
    var input = self._elmt.find('#dq-search-input');
    var icon = $(this);
    if (input.is(':hidden')) {
      input.show();
      icon.addClass('dq-magnifying-glass-open');
      input.focus();
    } else {
      input.hide();
      input.val('');
      icon.removeClass('dq-magnifying-glass-open');
      self._filterProjectsBySearch('');
    }
  });

  // 搜索输入框
  var typingTimer;
  this._elmt.find('#dq-search-input').on('keyup', function(e) {
    clearTimeout(typingTimer);
    if (e.key === 'Enter') {
      e.preventDefault();
      self._filterProjectsBySearch($(this).val());
    } else if (e.key === 'Escape') {
      e.preventDefault();
      self._elmt.find('#dq-search-icon').click();
    } else {
      typingTimer = setTimeout(function() {
        self._filterProjectsBySearch(self._elmt.find('#dq-search-input').val());
      }, 500);
    }
  });

  // 刷新按钮
  this._elmt.find('#dq-refresh-tasks').on('click', function() {
    self._loadProjects();
  });

  // 当前选中的标签
  this._currentTag = '';
};

/**
 * 初始化配置面板
 */
Refine.DataQualityUI.prototype._initSettingsPanel = function() {
  // 配置表单初始化在加载配置时完成
};

/**
 * 加载项目列表和任务状态
 */
Refine.DataQualityUI.prototype._loadProjects = function() {
  var self = this;

  // 获取所有项目元数据
  $.getJSON(
    "command/core/get-all-project-metadata",
    null,
    function(data) {
      self._projects = data.projects || {};
      // 构建标签列表
      self._buildTagsList();
      // 渲染项目表格
      self._renderProjectList();
      // 加载任务状态
      self._loadAllTasksStatus();
    }
  );
};

/**
 * 构建标签列表 (参考 open-project-ui.js)
 */
Refine.DataQualityUI.prototype._buildTagsList = function() {
  var self = this;
  var allTags = {};

  // 收集所有项目的标签
  for (var projectId in this._projects) {
    var project = this._projects[projectId];
    if (project && project.tags && Array.isArray(project.tags)) {
      project.tags.forEach(function(tag) {
        if (tag) {
          allTags[tag] = (allTags[tag] || 0) + 1;
        }
      });
    }
  }

  var tagList = Object.keys(allTags).sort();
  var ul = this._elmt.find('#dq-tags-ul').empty();

  // 添加 "全部" 选项
  var allLi = $('<li/>').appendTo(ul);
  var allA = $('<a/>')
    .attr('href', 'javascript:void(0)')
    .text('全部')
    .appendTo(allLi);
  allA.on('click', function(e) {
    e.preventDefault();
    self._filterByTag('');
  });

  if (!this._currentTag) {
    allLi.addClass('current');
  }

  // 添加各个标签
  tagList.forEach(function(tag) {
    var li = $('<li/>').appendTo(ul);
    var a = $('<a/>')
      .attr('href', 'javascript:void(0)')
      .text(tag + ' (' + allTags[tag] + ')')
      .appendTo(li);
    a.on('click', function(e) {
      e.preventDefault();
      self._filterByTag(tag);
    });

    if (self._currentTag === tag) {
      li.addClass('current');
    }
  });
};

/**
 * 按标签过滤项目
 */
Refine.DataQualityUI.prototype._filterByTag = function(tag) {
  this._currentTag = tag;

  // 更新标签样式
  this._elmt.find('#dq-tags-ul li').removeClass('current');
  if (tag) {
    this._elmt.find('#dq-tags-ul li a').filter(function() {
      return $(this).text().startsWith(tag + ' (');
    }).parent().addClass('current');
  } else {
    this._elmt.find('#dq-tags-ul li:first').addClass('current');
  }

  // 过滤表格行
  this._elmt.find('#dq-table-body tr').each(function() {
    var rowTags = $(this).data('tags') || [];
    if (!tag || rowTags.indexOf(tag) >= 0) {
      $(this).show();
    } else {
      $(this).hide();
    }
  });
};

/**
 * 按搜索关键词过滤项目
 */
Refine.DataQualityUI.prototype._filterProjectsBySearch = function(keyword) {
  keyword = (keyword || '').toLowerCase();

  this._elmt.find('#dq-table-body tr').each(function() {
    var text = $(this).text().toLowerCase();
    if (!keyword || text.indexOf(keyword) >= 0) {
      $(this).show();
    } else {
      $(this).hide();
    }
  });
};

/**
 * 加载所有项目的任务状态 (一次性获取所有任务，避免重复请求)
 */
Refine.DataQualityUI.prototype._loadAllTasksStatus = function() {
  var self = this;

  // 一次性获取所有任务
  $.getJSON(
    "command/data-quality/list-tasks",
    {},
    function(data) {
      // 清空旧的任务状态
      self._tasks = {};

      if (data.tasks && data.tasks.length > 0) {
        // 按项目ID分组，每个项目只保留最新的任务
        data.tasks.forEach(function(task) {
          var pid = task.projectId;
          if (pid) {
            // 如果这个项目还没有任务，或者当前任务更新（使用createdAt比较）
            if (!self._tasks[pid] ||
                (task.createdAt && self._tasks[pid].createdAt &&
                 task.createdAt > self._tasks[pid].createdAt)) {
              self._tasks[pid] = task;
            }
          }
        });
      }

      // 更新所有项目行
      for (var projectId in self._projects) {
        self._updateProjectRow(projectId);
      }
    }
  ).fail(function() {
    // 失败时清空任务状态
    self._tasks = {};
    for (var projectId in self._projects) {
      self._updateProjectRow(projectId);
    }
  });
};

/**
 * 渲染项目列表 (参考 open-project-ui.js 的 tablesorter 风格)
 */
Refine.DataQualityUI.prototype._renderProjectList = function() {
  var self = this;
  var container = this._elmt.find('#dq-project-list');
  container.empty();

  // 使用配置的分页大小
  this._pageSize = this._config['ui.pageSize'] || 50;

  // 按上次修改时间逆序排序（最近修改的在前）
  var projectIds = Object.keys(this._projects);
  projectIds.sort(function(a, b) {
    var ma = self._projects[a].modified ? new Date(self._projects[a].modified).getTime() : 0;
    var mb = self._projects[b].modified ? new Date(self._projects[b].modified).getTime() : 0;
    return mb - ma;
  });

  if (projectIds.length === 0) {
    $('<div class="dq-empty-message">暂无项目</div>').appendTo(container);
    return;
  }

  // 计算分页
  var totalItems = projectIds.length;
  var totalPages = Math.ceil(totalItems / this._pageSize);
  if (this._currentPage > totalPages) {
    this._currentPage = totalPages;
  }
  if (this._currentPage < 1) {
    this._currentPage = 1;
  }
  var startIndex = (this._currentPage - 1) * this._pageSize;
  var endIndex = Math.min(startIndex + this._pageSize, totalItems);
  var pagedProjectIds = projectIds.slice(startIndex, endIndex);

  // 渲染分页控件
  this._renderPagination(container, totalItems, totalPages);

  // 创建表格 (参考 open-project-ui 的 tablesorter-blue 风格)
  var table = $(
    '<table class="tablesorter-blue list-table"><thead><tr>' +
    '<th></th>' +                               // 删除按钮
    '<th>上次修改</th>' +
    '<th>项目名称</th>' +
    '<th>标签</th>' +
    '<th>最后检查</th>' +
    '<th>任务状态</th>' +
    '<th>操作</th>' +
    '</tr></thead><tbody id="dq-table-body"></tbody></table>'
  ).appendTo(container);

  var tbody = table.find('#dq-table-body');

  for (var i = 0; i < pagedProjectIds.length; i++) {
    var projectId = pagedProjectIds[i];
    var project = this._projects[projectId];
    var tags = project.tags || [];

    var tr = $('<tr>')
      .addClass('project')
      .attr('data-project-id', projectId)
      .data('tags', tags)
      .appendTo(tbody);

    // 为标签过滤添加 class
    tags.forEach(function(tag) {
      tr.addClass(tag);
    });

    // 删除按钮列
    var deleteCell = $('<td/>').appendTo(tr);
    $('<a/>')
      .addClass('delete-project')
      .attr('title', '删除项目')
      .attr('href', 'javascript:void(0)')
      .html('<img src="images/close-ry.png" />')
      .on('click', function(e) {
        e.preventDefault();
        var pid = $(this).closest('tr').data('project-id');
        var pname = self._projects[pid] ? self._projects[pid].name : pid;
        if (window.confirm('确定要删除项目 "' + pname + '" 吗？此操作不可撤销！')) {
          Refine.postCSRF(
            'command/core/delete-project',
            { project: pid },
            function(data) {
              if (data && data.code === 'ok') {
                self._loadProjects();
              } else {
                alert('删除失败: ' + (data.message || '未知错误'));
              }
            },
            'json'
          );
        }
      })
      .appendTo(deleteCell);

    // 上次修改时间
    var modifiedDate = project.modified ? new Date(project.modified) : null;
    $('<td/>')
      .addClass('last-modified')
      .html('<span style="display:none">' + (project.modified || '') + '</span>' +
            (modifiedDate ? this._formatDate(modifiedDate) : '-'))
      .appendTo(tr);

    // 项目名称
    var nameCell = $('<td/>').appendTo(tr);
    $('<a/>')
      .addClass('project-name searchable')
      .text(project.name || '未命名项目')
      .attr('href', 'project?project=' + projectId)
      .appendTo(nameCell);

    // 标签
    var tagsCell = $('<td/>').appendTo(tr);
    tags.forEach(function(tag) {
      $('<span/>')
        .addClass('project-tag searchable')
        .attr('data-tag-name', tag)
        .text(tag)
        .appendTo(tagsCell);
    });

    // 最后检查时间 (占位符，后续更新)
    $('<td/>')
      .addClass('dq-check-time last-modified')
      .text('-')
      .appendTo(tr);

    // 任务状态 (占位符，后续更新)
    $('<td/>')
      .addClass('dq-task-status')
      .html('<span class="dq-status-none">-</span>')
      .appendTo(tr);

    // 操作按钮
    var actionsCell = $('<td/>')
      .addClass('dq-task-actions')
      .appendTo(tr);

    this._renderActionButtons(actionsCell, projectId, null);
  }

  // 使用 tablesorter 插件 (参考 open-project-ui.js)
  if ($.fn.tablesorter) {
    table.tablesorter({
      headers: {
        0: { sorter: false },  // 删除列不排序
        1: { sorter: 'text' }, // 修改时间列 - 使用隐藏的 ISO 格式字符串排序
        3: { sorter: false },  // 标签列不排序
        5: { sorter: false },  // 状态列不排序
        6: { sorter: false }   // 操作列不排序
      },
      sortList: [[1, 1]],  // 默认按修改时间倒序
      widthFixed: false
    });
  }
};

/**
 * 渲染分页控件
 */
Refine.DataQualityUI.prototype._renderPagination = function(container, totalItems, totalPages) {
  var self = this;

  if (totalPages <= 1) {
    // 只有一页时不显示分页
    return;
  }

  var paginationDiv = $('<div class="dq-pagination"></div>');

  // 信息显示
  var startItem = (this._currentPage - 1) * this._pageSize + 1;
  var endItem = Math.min(this._currentPage * this._pageSize, totalItems);
  $('<span class="dq-pagination-info">')
    .text('显示 ' + startItem + '-' + endItem + ' / 共 ' + totalItems + ' 项')
    .appendTo(paginationDiv);

  // 分页按钮容器
  var btnContainer = $('<span class="dq-pagination-btns"></span>').appendTo(paginationDiv);

  // 首页
  $('<button>')
    .addClass('dq-btn dq-btn-sm')
    .text('首页')
    .prop('disabled', this._currentPage <= 1)
    .on('click', function() {
      self._currentPage = 1;
      self._renderProjectList();
      self._loadAllTasksStatus();
    })
    .appendTo(btnContainer);

  // 上一页
  $('<button>')
    .addClass('dq-btn dq-btn-sm')
    .text('上一页')
    .prop('disabled', this._currentPage <= 1)
    .on('click', function() {
      self._currentPage--;
      self._renderProjectList();
      self._loadAllTasksStatus();
    })
    .appendTo(btnContainer);

  // 页码显示
  $('<span class="dq-pagination-current">')
    .text(' 第 ' + this._currentPage + ' / ' + totalPages + ' 页 ')
    .appendTo(btnContainer);

  // 下一页
  $('<button>')
    .addClass('dq-btn dq-btn-sm')
    .text('下一页')
    .prop('disabled', this._currentPage >= totalPages)
    .on('click', function() {
      self._currentPage++;
      self._renderProjectList();
      self._loadAllTasksStatus();
    })
    .appendTo(btnContainer);

  // 末页
  $('<button>')
    .addClass('dq-btn dq-btn-sm')
    .text('末页')
    .prop('disabled', this._currentPage >= totalPages)
    .on('click', function() {
      self._currentPage = totalPages;
      self._renderProjectList();
      self._loadAllTasksStatus();
    })
    .appendTo(btnContainer);

  container.append(paginationDiv);
};

/**
 * 更新单个项目行的任务状态
 */
Refine.DataQualityUI.prototype._updateProjectRow = function(projectId) {
  var task = this._tasks[projectId];
  var row = this._elmt.find('tr[data-project-id="' + projectId + '"]');

  if (row.length === 0) return;

  var timeCell = row.find('.dq-check-time');
  var statusCell = row.find('.dq-task-status');
  var actionsCell = row.find('.dq-task-actions');

  if (!task) {
    timeCell.text('-');
    statusCell.html('<span class="dq-status-none">无任务</span>');
    this._renderActionButtons(actionsCell, projectId, null);
    return;
  }

  // 更新检查时间（使用 createdAt 字段）
  if (task.createdAt) {
    var date = new Date(task.createdAt);
    timeCell.text(this._formatDate(date));
  } else {
    timeCell.text('-');
  }

  // 更新状态
  statusCell.html(this._renderTaskStatus(task));

  // 更新操作按钮
  this._renderActionButtons(actionsCell, projectId, task);
};

/**
 * 渲染任务状态
 */
Refine.DataQualityUI.prototype._renderTaskStatus = function(task) {
  if (!task) {
    return '<span class="dq-status-none">无任务</span>';
  }

  var status = task.status || 'PENDING';
  var progress = task.progress || 0;

  switch (status) {
    case 'RUNNING':
      return '<div class="dq-progress-bar">' +
             '<div class="dq-progress-fill" style="width: ' + progress + '%"></div>' +
             '</div>' +
             '<span class="dq-progress-text">' + progress + '%</span>';

    case 'PAUSED':
      var pausedErrors = (task.formatErrors || 0) + (task.resourceErrors || 0) + (task.contentErrors || 0);
      if (pausedErrors > 0) {
        return '<span class="dq-status-paused">⏸ 已暂停 (' + pausedErrors + '错误)</span>';
      }
      return '<span class="dq-status-paused">⏸ 已暂停 (' + progress + '%)</span>';

    case 'COMPLETED':
      var errorCount = (task.formatErrors || 0) + (task.resourceErrors || 0) + (task.contentErrors || 0);
      if (errorCount > 0) {
        return '<span class="dq-status-completed-error">✓ 完成 (' + errorCount + '错误)</span>';
      }
      return '<span class="dq-status-completed">✓ 完成</span>';

    case 'FAILED':
      return '<span class="dq-status-failed">✗ 失败</span>';

    case 'CANCELLED':
      return '<span class="dq-status-cancelled">⊘ 已取消</span>';

    case 'PENDING':
    default:
      return '<span class="dq-status-pending">待开始</span>';
  }
};


/**
 * 渲染操作按钮
 */
Refine.DataQualityUI.prototype._renderActionButtons = function(container, projectId, task) {
  var self = this;
  container.empty();

  var status = task ? task.status : null;

  // 根据任务状态显示不同的按钮
  if (!task || status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') {
    // 启动检查按钮
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', '启动检查')
      .html('<img src="images/extensions/circle-play-regular-full.svg" alt="启动" />')
      .on('click', function() { self._startCheck(projectId); })
      .appendTo(container);
  }

  if (status === 'RUNNING') {
    // 暂停按钮
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', '暂停')
      .html('<img src="images/extensions/pause-solid-full.svg" alt="暂停" />')
      .on('click', function() { self._pauseTask(projectId); })
      .appendTo(container);

    // 停止按钮
    $('<button>')
      .addClass('dq-btn-icon dq-btn-danger')
      .attr('title', '停止')
      .html('<img src="images/extensions/stop-solid-full.svg" alt="停止" />')
      .on('click', function() { self._cancelTask(projectId); })
      .appendTo(container);
  }

  if (status === 'PAUSED') {
    // 恢复按钮
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', '恢复')
      .html('<img src="images/extensions/circle-play-regular-full.svg" alt="恢复" />')
      .on('click', function() { self._resumeTask(projectId); })
      .appendTo(container);

    // 停止按钮
    $('<button>')
      .addClass('dq-btn-icon dq-btn-danger')
      .attr('title', '停止')
      .html('<img src="images/extensions/stop-solid-full.svg" alt="停止" />')
      .on('click', function() { self._cancelTask(projectId); })
      .appendTo(container);
  }

  // 编辑规则按钮 (始终显示)
  $('<button>')
    .addClass('dq-btn-icon')
    .attr('title', '编辑规则')
    .html('<img src="images/extensions/pen-to-square-regular-full.svg" alt="规则" />')
    .on('click', function() { self._openRulesEditor(projectId); })
    .appendTo(container);

  // 查看报告按钮 (完成后或暂停且有结果时显示)
  if (status === 'COMPLETED' || (status === 'PAUSED' &&
      ((task.formatErrors || 0) + (task.resourceErrors || 0) + (task.contentErrors || 0)) > 0)) {
    $('<button>')
      .addClass('dq-btn-icon')
      .attr('title', '查看报告')
      .html('<img src="images/extensions/chart-line-solid-full.svg" alt="报告" />')
      .on('click', function() { self._viewReport(projectId); })
      .appendTo(container);
  }
};

/**
 * 启动质量检查
 */
Refine.DataQualityUI.prototype._startCheck = function(projectId) {
  var self = this;

  Refine.postCSRF(
    "command/data-quality/run-quality-check",
    { project: projectId, async: true },
    function(data) {
      if (data.code === 'ok' || data.status === 'started') {
        self._loadProjects();
      } else {
        alert('启动检查失败: ' + (data.message || '未知错误'));
      }
    },
    "json",
    function(xhr, status, error) {
      console.error('启动检查请求失败:', status, error);
      alert('启动检查请求失败: ' + error);
    }
  );
};

/**
 * 暂停任务
 */
Refine.DataQualityUI.prototype._pauseTask = function(projectId) {
  var self = this;

  Refine.postCSRF(
    "command/data-quality/task-control",
    { project: projectId, action: 'pause' },
    function(data) {
      if (data.code === 'ok') {
        self._loadProjects();
      } else {
        alert('暂停失败: ' + (data.message || '未知错误'));
      }
    },
    "json"
  );
};

/**
 * 恢复任务
 */
Refine.DataQualityUI.prototype._resumeTask = function(projectId) {
  var self = this;

  Refine.postCSRF(
    "command/data-quality/task-control",
    { project: projectId, action: 'resume' },
    function(data) {
      if (data.code === 'ok') {
        self._loadProjects();
      } else {
        alert('恢复失败: ' + (data.message || '未知错误'));
      }
    },
    "json"
  );
};

/**
 * 取消任务
 */
Refine.DataQualityUI.prototype._cancelTask = function(projectId) {
  var self = this;

  if (!confirm('确定要取消当前检查任务吗？')) {
    return;
  }

  Refine.postCSRF(
    "command/data-quality/task-control",
    { project: projectId, action: 'cancel' },
    function(data) {
      if (data.code === 'ok') {
        self._loadProjects();
      } else {
        alert('取消失败: ' + (data.message || '未知错误'));
      }
    },
    "json"
  );
};

/**
 * 打开规则编辑器（跳转到项目页面的质量规则Tab）
 */
Refine.DataQualityUI.prototype._openRulesEditor = function(projectId) {
  // 跳转到项目页面，hash标识需要激活质量规则Tab
  window.location.href = 'project?project=' + projectId + '#quality-rules';
};

/**
 * 查看检查报告（跳转到项目页面）
 */
Refine.DataQualityUI.prototype._viewReport = function(projectId) {
  window.location.href = 'project?project=' + projectId + '#quality-results';
};

/**
 * 启动自动刷新
 */
Refine.DataQualityUI.prototype._startAutoRefresh = function() {
  var self = this;
  this._stopAutoRefresh();

  this._refreshInterval = setInterval(function() {
    if (self._currentTab === 'tasks') {
      self._loadAllTasksStatus();
    }
  }, this._refreshIntervalMs);
};

/**
 * 停止自动刷新
 */
Refine.DataQualityUI.prototype._stopAutoRefresh = function() {
  if (this._refreshInterval) {
    clearInterval(this._refreshInterval);
    this._refreshInterval = null;
  }
};

/**
 * 格式化日期
 */
Refine.DataQualityUI.prototype._formatDate = function(date) {
  if (!date) return '-';

  var year = date.getFullYear();
  var month = String(date.getMonth() + 1).padStart(2, '0');
  var day = String(date.getDate()).padStart(2, '0');
  var hours = String(date.getHours()).padStart(2, '0');
  var minutes = String(date.getMinutes()).padStart(2, '0');

  return year + '-' + month + '-' + day + ' ' + hours + ':' + minutes;
};

// ==================== 配置设置 ====================

/**
 * 默认配置项
 */
Refine.DataQualityUI.DEFAULT_CONFIG = {
  // AIMP服务配置
  'aimp.server': 'http://127.0.0.1:7998',
  'aimp.timeout': 30,

  // 任务执行配置
  'task.threadPoolSize': 4,
  'task.batchSize': 100,
  'task.autosaveInterval': 60,

  // 内容比对配置
  'content.similarityPass': 100,
  'content.similarityWarning': 100,
  'content.batchSize': 2,
  'content.ocrConfidence': 0.8,

  // 界面配置
  'ui.refreshInterval': 5,
  'ui.pageSize': 50
};

/**
 * 加载配置
 */
Refine.DataQualityUI.prototype._loadSettings = function() {
  var self = this;

  $.getJSON(
    "command/data-quality/get-config",
    null,
    function(data) {
      self._config = $.extend({}, Refine.DataQualityUI.DEFAULT_CONFIG, data.config || {});
      self._renderSettingsForm();
    }
  ).fail(function() {
    // 如果API不存在，使用默认配置
    self._config = $.extend({}, Refine.DataQualityUI.DEFAULT_CONFIG);
    self._renderSettingsForm();
  });
};

/**
 * 渲染配置表单
 */
Refine.DataQualityUI.prototype._renderSettingsForm = function() {
  var self = this;
  var container = this._elmt.find('#dq-settings-form');
  container.empty();

  // AIMP服务配置
  var aimpSection = this._createSettingsSection('AIMP服务配置');
  this._createTextInput(aimpSection, 'aimp.server', '服务地址', this._config['aimp.server']);
  this._createNumberInput(aimpSection, 'aimp.timeout', '请求超时(秒)', this._config['aimp.timeout'], 1, 300);
  this._createTestConnectionButton(aimpSection);
  container.append(aimpSection);

  // 任务执行配置
  var taskSection = this._createSettingsSection('任务执行配置');
  this._createNumberInput(taskSection, 'task.threadPoolSize', '线程池大小', this._config['task.threadPoolSize'], 1, 32);
  this._createNumberInput(taskSection, 'task.batchSize', '批处理大小(行)', this._config['task.batchSize'], 10, 1000);
  this._createNumberInput(taskSection, 'task.autosaveInterval', '自动保存间隔(秒)', this._config['task.autosaveInterval'], 10, 600);
  container.append(taskSection);

  // 内容比对配置
  var contentSection = this._createSettingsSection('默认内容比对配置');
  this._createNumberInput(contentSection, 'content.similarityPass', '通过阈值(%)', this._config['content.similarityPass'], 0, 100);
  this._createNumberInput(contentSection, 'content.similarityWarning', '警告阈值(%)', this._config['content.similarityWarning'], 0, 100);
  this._createNumberInput(contentSection, 'content.batchSize', '批量比对大小(条)', this._config['content.batchSize'], 1, 50);
  this._createNumberInput(contentSection, 'content.ocrConfidence', 'OCR置信度阈值', this._config['content.ocrConfidence'], 0, 1, 0.1);
  container.append(contentSection);

  // 界面配置
  var uiSection = this._createSettingsSection('界面配置');
  this._createNumberInput(uiSection, 'ui.refreshInterval', '任务列表刷新间隔(秒)', this._config['ui.refreshInterval'], 1, 60);
  this._createNumberInput(uiSection, 'ui.pageSize', '每页显示条数', this._config['ui.pageSize'], 10, 200);
  container.append(uiSection);

  // 按钮区
  var buttonSection = $('<div>').addClass('dq-settings-buttons');

  $('<button>')
    .addClass('dq-btn')
    .text('恢复默认值')
    .on('click', function() { self._resetSettings(); })
    .appendTo(buttonSection);

  $('<button>')
    .addClass('dq-btn dq-btn-primary')
    .text('保存配置')
    .on('click', function() { self._saveSettings(); })
    .appendTo(buttonSection);

  container.append(buttonSection);

  // 提示信息
  var tip = $('<div>').addClass('dq-settings-tip')
    .html('<img src="images/extensions/lightbulb-regular-full.svg" alt="提示" /> 配置修改后对新启动的任务生效，运行中的任务不受影响');
  container.append(tip);
};


/**
 * 创建配置分组
 */
Refine.DataQualityUI.prototype._createSettingsSection = function(title) {
  var section = $('<div>').addClass('dq-settings-section');
  $('<div>').addClass('dq-settings-section-title').text(title).appendTo(section);
  $('<div>').addClass('dq-settings-section-body').appendTo(section);
  return section;
};

/**
 * 创建文本输入框
 */
Refine.DataQualityUI.prototype._createTextInput = function(section, key, label, value) {
  var row = $('<div>').addClass('dq-settings-row');
  $('<label>').text(label + ':').appendTo(row);
  $('<input>')
    .attr('type', 'text')
    .attr('data-config-key', key)
    .addClass('dq-input')
    .val(value || '')
    .appendTo(row);
  section.find('.dq-settings-section-body').append(row);
};

/**
 * 创建数字输入框
 */
Refine.DataQualityUI.prototype._createNumberInput = function(section, key, label, value, min, max, step) {
  var row = $('<div>').addClass('dq-settings-row');
  $('<label>').text(label + ':').appendTo(row);
  var input = $('<input>')
    .attr('type', 'number')
    .attr('data-config-key', key)
    .attr('min', min)
    .attr('max', max)
    .addClass('dq-input dq-input-number')
    .val(value || 0);
  if (step) {
    input.attr('step', step);
  }
  input.appendTo(row);
  section.find('.dq-settings-section-body').append(row);
};

/**
 * 创建测试连接按钮
 */
Refine.DataQualityUI.prototype._createTestConnectionButton = function(section) {
  var self = this;
  var row = $('<div>').addClass('dq-settings-row');

  $('<label>').text('连接状态:').appendTo(row);

  var statusSpan = $('<span>')
    .addClass('dq-connection-status')
    .text('未测试')
    .appendTo(row);

  $('<button>')
    .addClass('dq-btn dq-btn-small')
    .html('<img src="images/extensions/link-solid-full.svg" alt="提示" /> 测试连接')
    .on('click', function() {
      var serverUrl = self._elmt.find('input[data-config-key="aimp.server"]').val();
      self._testAimpConnection(serverUrl, statusSpan);
    })
    .appendTo(row);

  section.find('.dq-settings-section-body').append(row);
};

/**
 * 测试AIMP连接
 */
Refine.DataQualityUI.prototype._testAimpConnection = function(serverUrl, statusSpan) {
  statusSpan.text('测试中...').removeClass('dq-status-ok dq-status-error');

  var startTime = Date.now();

  $.ajax({
    url: "command/data-quality/test-aimp-connection",
    type: "GET",
    data: { server: serverUrl },
    dataType: "json",
    timeout: 10000,
    success: function(data) {
      var elapsed = Date.now() - startTime;
      if (data.connected) {
        statusSpan.text('● 已连接 (响应时间: ' + elapsed + 'ms)')
          .addClass('dq-status-ok').removeClass('dq-status-error');
      } else {
        statusSpan.text('○ 未连接 - ' + (data.message || '请检查服务'))
          .addClass('dq-status-error').removeClass('dq-status-ok');
      }
    },
    error: function() {
      statusSpan.text('○ 连接失败')
        .addClass('dq-status-error').removeClass('dq-status-ok');
    }
  });
};

/**
 * 保存配置
 */
Refine.DataQualityUI.prototype._saveSettings = function() {
  var self = this;
  var config = {};

  this._elmt.find('input[data-config-key]').each(function() {
    var key = $(this).data('config-key');
    var type = $(this).attr('type');
    var value = $(this).val();

    if (type === 'number') {
      config[key] = parseFloat(value);
    } else {
      config[key] = value;
    }
  });

  Refine.postCSRF(
    "command/data-quality/save-config",
    { config: JSON.stringify(config) },
    function(data) {
      if (data.code === 'ok') {
        alert('配置保存成功');
        self._config = config;
        // 更新刷新间隔
        self._refreshIntervalMs = (config['ui.refreshInterval'] || 5) * 1000;
      } else {
        alert('保存失败: ' + (data.message || '未知错误'));
      }
    },
    "json",
    function() {
      alert('保存失败: 网络错误');
    }
  );
};

/**
 * 恢复默认配置
 */
Refine.DataQualityUI.prototype._resetSettings = function() {
  if (!confirm('确定要恢复所有配置为默认值吗？')) {
    return;
  }
  this._config = $.extend({}, Refine.DataQualityUI.DEFAULT_CONFIG);
  this._renderSettingsForm();
};

/**
 * 窗口大小调整处理
 */
Refine.DataQualityUI.prototype.resize = function() {
  // 可选：处理窗口大小变化
};

// ==================== 注册ActionArea ====================

Refine.actionAreas.push({
  id: "data-quality",
  label: "数据质量",
  uiClass: Refine.DataQualityUI
});