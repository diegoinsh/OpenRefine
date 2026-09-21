/*

Copyright 2024 Open Refine.
All rights reserved.
*/

// This file is added to the /project page

// Batch title extraction monitor: while an extraction task is running for the
// currently opened project, show a progress banner (with progress bar) and
// auto-refresh the data table as new rows are appended.

var BatchTitleExtractionMonitor = (function () {
  var POLL_INTERVAL = 2000;
  var FINAL_BANNER_MS = 10000;

  var pollTimer = null;
  var lastRowsAppended = -1;
  var $banner = null;
  var bannerHideTimer = null;
  var runningDismissed = null;
  var i18nReady = false;

  // localStorage记录按项目持久化"用户已关闭的终态横幅"：
  // 终态（完成/取消/失败）横幅一旦被用户关闭，刷新或重进项目页均不再出现；
  // 运行中关闭仅静默到下一次进度更新。
  function dismissedStorageKey(pid) {
    return 'files-batch-banner-dismissed-' + pid;
  }

  function isFinalStatus(status) {
    return status === 'completed' || status === 'cancelled' || status === 'failed';
  }

  function readDismissedFinal(pid) {
    try {
      return window.localStorage.getItem(dismissedStorageKey(pid));
    } catch (e) {
      return null;
    }
  }

  function writeDismissedFinal(pid, status) {
    try {
      window.localStorage.setItem(dismissedStorageKey(pid), status);
    } catch (e) {
      // ignore
    }
  }

  function clearDismissedFinal(pid) {
    try {
      window.localStorage.removeItem(dismissedStorageKey(pid));
    } catch (e) {
      // ignore
    }
  }

  function loadExtensionMessages() {
    try {
      if (window.I18NUtil && typeof I18NUtil.init === 'function') {
        I18NUtil.init('files');
      }
      i18nReady = true;
    } catch (e) {
      i18nReady = true;
    }
  }

  function getProjectId() {
    try {
      if (typeof theProject !== 'undefined' && theProject && typeof theProject.id !== 'undefined') {
        return theProject.id;
      }
      if (typeof project !== 'undefined' && project && typeof project.id !== 'undefined') {
        return project.id;
      }
    } catch (e) {
      // ignore
    }
    return null;
  }

  function refreshDataTable() {
    try {
      if (window.ui && ui.dataTableView && typeof ui.dataTableView.update === 'function') {
        ui.dataTableView.update(function () {
          try {
            // The core render fixes column widths from a cache; with an empty
            // growing table that cache pins narrow widths. Remove the inline
            // widths after render so columns re-fit their content.
            $('.data-table-container col').css('width', '');
          } catch (e) {
            // ignore
          }
        });
      }
    } catch (e) {
      // ignore
    }
  }

  function removeBanner() {
    if (bannerHideTimer) {
      window.clearTimeout(bannerHideTimer);
      bannerHideTimer = null;
    }
    if ($banner) {
      $banner.remove();
      $banner = null;
    }
  }

  function updateBanner(kind, d) {
    if (!$banner) {
      $banner = $('<div class="batch-extraction-banner">')
          .append($('<span class="batch-extraction-banner-icon icon-running">'))
          .append($('<span class="batch-extraction-banner-text">'))
          .append(
              $('<div class="batch-progress-track batch-banner-progress-track">' +
                '<div class="batch-progress-bar batch-banner-progress-bar"></div></div>')
          )
          .append(
              $('<button type="button" class="batch-extraction-banner-close">&times;</button>')
                  .on('click', function () {
                    // 终态横幅：关闭后按项目持久化，刷新/重进不再出现；
                    // 运行中横幅：静默直到下一次进度更新
                    var pid = getProjectId();
                    var currentKind = $banner ? $banner.data('banner-kind') : null;
                    if (isFinalStatus(currentKind) && pid !== null) {
                      writeDismissedFinal(pid, currentKind);
                    } else if (currentKind === 'running') {
                      runningDismissed = {
                        processedPages: $banner.data('processed-pages') || 0,
                        rowsAppended: $banner.data('rows-appended') || 0
                      };
                    }
                    removeBanner();
                  })
          );
      $('body').append($banner);
    }
    $banner.data('banner-kind', kind);
    if (kind === 'running') {
      $banner.data('processed-pages', d.processedPages || 0);
      $banner.data('rows-appended', d.rowsAppended || 0);
    }
    $banner.removeClass('banner-error');
    $banner.find('.batch-banner-progress-bar').removeClass('banner-bar-error');
    var $text = $banner.find('.batch-extraction-banner-text');
    var $bar = $banner.find('.batch-banner-progress-bar');
    var text = '';
    if (kind === 'running') {
      var hint = d.message ? d.message + '。' : '';
      var unitLabel = $.i18n(d.unitKind === 'volume'
          ? 'files-import/batch-unit-volume' : 'files-import/batch-unit-case');
      text = $.i18n('files-import/batch-banner-running',
          d.processedFiles || 0, d.totalFiles || 0, unitLabel,
          d.processedPages || 0, d.totalPages || 0,
          d.rowsAppended || 0, hint);
      var percent = d.totalPages > 0
          ? Math.min(100, Math.round((d.processedPages || 0) * 100 / d.totalPages)) : 0;
      $bar.css('width', percent + '%');
      if ($banner.find('.batch-extraction-banner-hint').length === 0) {
        $banner.append($('<span class="batch-extraction-banner-hint">')
            .text($.i18n('files-import/batch-readonly-hint')));
      }
    } else if (kind === 'completed') {
      text = $.i18n('files-import/batch-banner-completed', d.rowsAppended || 0);
      $bar.css('width', '100%');
    } else if (kind === 'cancelled') {
      text = $.i18n('files-import/batch-banner-cancelled', d.rowsAppended || 0);
      $bar.css('width', '100%');
    } else {
      text = $.i18n('files-import/batch-banner-failed', d.message || '');
      $banner.addClass('banner-error');
      $bar.addClass('banner-bar-error');
      $bar.css('width', '100%');
    }
    $text.text(text);
    // 状态图标：运行中转圈，终态切换为静态图标
    var iconClass = 'icon-running';
    if (kind === 'completed') {
      iconClass = 'icon-completed';
    } else if (kind === 'cancelled') {
      iconClass = 'icon-cancelled';
    } else if (kind !== 'running') {
      iconClass = 'icon-error';
    }
    $banner.find('.batch-extraction-banner-icon')
        .attr('class', 'batch-extraction-banner-icon ' + iconClass);
  }

  function showBanner(kind, d) {
    // 运行中横幅被用户关闭后，进度未变化前保持静默；
    // 一旦 processedPages/rowsAppended 有更新则重新出现
    if (kind === 'running' && runningDismissed &&
        (d.processedPages || 0) === runningDismissed.processedPages &&
        (d.rowsAppended || 0) === runningDismissed.rowsAppended) {
      return;
    }
    if (kind === 'running') {
      runningDismissed = null;
    }
    updateBanner(kind, d);
  }

  function showFinalBanner(kind, d) {
    // 用户已关闭过该终态横幅：不再出现（跨刷新持久化）
    var pid = getProjectId();
    if (pid !== null) {
      var dismissed = readDismissedFinal(pid);
      if (dismissed === kind) {
        return;
      }
    }
    updateBanner(kind, d);
    if (bannerHideTimer) window.clearTimeout(bannerHideTimer);
    bannerHideTimer = window.setTimeout(function () {
      removeBanner();
    }, FINAL_BANNER_MS);
  }

  function pollOnce() {
    var pid = getProjectId();
    if (pid === null) {
      stop();
      return;
    }
    Refine.wrapCSRF(function (token) {
      $.post("command/files/batch-extraction", {
        subCommand: "progress",
        project: pid,
        csrf_token: token
      }, function (data) {
        if (!data || data.code !== 'ok') {
          stop();
          return;
        }
        if (data.status === 'running') {
          // 新任务运行中：清除该项目旧的终态关闭记录，确保新任务的终态横幅可见
          clearDismissedFinal(pid);
          setReadonlyMode(true);
          showBanner('running', data);
          if (lastRowsAppended !== -1 && data.rowsAppended !== lastRowsAppended) {
            refreshDataTable();
          }
          lastRowsAppended = data.rowsAppended;
        } else {
          stop();
          setReadonlyMode(false);
          refreshDataTable();
          showFinalBanner(data.status, data);
        }
      }, "json").fail(function () {
        stop();
        setReadonlyMode(false);
      });
    });
  }

  function start() {
    if (pollTimer) return;
    pollOnce();
    pollTimer = window.setInterval(pollOnce, POLL_INTERVAL);
  }

  function stop() {
    if (pollTimer) {
      window.clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function setReadonlyMode(on) {
    try {
      $(document.body).toggleClass('extraction-readonly', !!on);
    } catch (e) {
      // ignore
    }
  }

  function installEditBlockers() {
    // R-07: during extraction the project is read-only. Block the most common
    // editing gestures up front (backend guard vetoes Change commands anyway).
    $(document).on('dblclick.extraction-readonly', '.data-table-cell', function (e) {
      if ($(document.body).hasClass('extraction-readonly')) {
        e.preventDefault();
        e.stopPropagation();
      }
    });
    $(document).on('click.extraction-readonly', '.data-table-star, .data-table-flag', function (e) {
      if ($(document.body).hasClass('extraction-readonly')) {
        e.preventDefault();
        e.stopPropagation();
      }
    });
  }

  $(function () {
    loadExtensionMessages();
    installEditBlockers();
    window.setTimeout(start, 1000);
  });

  return {};
})();
