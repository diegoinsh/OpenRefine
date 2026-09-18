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
  var i18nReady = false;

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
          .append($('<span class="batch-extraction-banner-text">'))
          .append(
              $('<div class="batch-progress-track batch-banner-progress-track">' +
                '<div class="batch-progress-bar batch-banner-progress-bar"></div></div>')
          )
          .append(
              $('<button type="button" class="batch-extraction-banner-close">&times;</button>')
                  .on('click', function () { removeBanner(); })
          );
      $('body').append($banner);
    }
    $banner.removeClass('banner-error');
    $banner.find('.batch-banner-progress-bar').removeClass('banner-bar-error');
    var $text = $banner.find('.batch-extraction-banner-text');
    var $bar = $banner.find('.batch-banner-progress-bar');
    var text = '';
    if (kind === 'running') {
      text = $.i18n('files-import/batch-banner-running',
          d.processedPages || 0, d.totalPages || 0, d.rowsAppended || 0);
      var percent = d.totalPages > 0
          ? Math.min(100, Math.round((d.processedPages || 0) * 100 / d.totalPages)) : 0;
      $bar.css('width', percent + '%');
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
  }

  function showBanner(kind, d) {
    updateBanner(kind, d);
  }

  function showFinalBanner(kind, d) {
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
          showBanner('running', data);
          if (lastRowsAppended !== -1 && data.rowsAppended !== lastRowsAppended) {
            refreshDataTable();
          }
          lastRowsAppended = data.rowsAppended;
        } else {
          stop();
          refreshDataTable();
          showFinalBanner(data.status, data);
        }
      }, "json").fail(function () {
        stop();
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

  $(function () {
    loadExtensionMessages();
    window.setTimeout(start, 1000);
  });

  return {};
})();
