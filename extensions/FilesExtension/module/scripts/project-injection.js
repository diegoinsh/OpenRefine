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

  /** 表格「跟随最新」的容差（px）：距底不超过该值即视为用户正在看最新数据 */
  var FOLLOW_BOTTOM_TOLERANCE_PX = 80;

  /**
   * 用户本来就在底部附近时，把表格滚到最新一行；否则不动，尊重用户当前的浏览位置。
   */
  function followTableBottom() {
    var el = document.querySelector('.data-table-container');
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight <= FOLLOW_BOTTOM_TOLERANCE_PX) {
      el.scrollTop = el.scrollHeight;
    }
  }

  /** 已展示条目的上限（与后端页级预览容量一致），超出后从最旧一端丢弃 */
  var PAGE_PREVIEW_MAX = 40;
  /** 待揭示队列的上限：只作兜底，揭示节奏与抽取节奏大体匹配，正常不会触发 */
  var PAGE_PENDING_LIMIT = 40;
  /** 后端还没测出平均耗时时的兜底揭示间隔 */
  var PAGE_REVEAL_FALLBACK_MS = 3000;
  /** 揭示间隔的上下限：太快等于一次性蹦出，太慢会一直追不上抽取进度 */
  var PAGE_REVEAL_MIN_MS = 800;
  var PAGE_REVEAL_MAX_MS = 8000;
  /**
   * 逐条揭示间隔（毫秒）：0 表示「到达即显示」。
   * 后端每批并发抽 4 页、同时返回，一次性弹出 4 条过于突兀，故按实测的平均每页耗时逐条揭示；
   * 逐页串行调用的门类（每页本身就是一条，3 秒左右一页）不需要这个节奏，直接显示即可。
   */
  var previewIntervalMs = 0;
  /** 已展示的页（按页号升序，最新在最下方）与待揭示队列 */
  var previewShown = [];
  var previewPending = [];
  var previewTimer = null;
  /** 预览所属的卷序号：换卷后页号会重新从 1 开始，必须重置队列，否则新旧卷的页会混在一起 */
  var previewVolumeIndex = -1;

  function resetPagePreview() {
    previewShown = [];
    previewPending = [];
    previewIntervalMs = 0;
    previewVolumeIndex = -1;
    if (previewTimer) {
      window.clearInterval(previewTimer);
      previewTimer = null;
    }
  }

  /**
   * 按后端给的口径更新揭示间隔：并发批调用用实测的平均每页耗时，逐页串行用 0（到达即显示）。
   * 间隔一变就停掉正在跑的定时器，避免继续沿用旧节奏。
   */
  function updatePreviewInterval(pageConcurrency, avgPageMillis) {
    var next = 0;
    if (pageConcurrency > 1) {
      next = (typeof avgPageMillis === 'number' && avgPageMillis > 0)
          ? avgPageMillis : PAGE_REVEAL_FALLBACK_MS;
      next = Math.max(PAGE_REVEAL_MIN_MS, Math.min(PAGE_REVEAL_MAX_MS, next));
    }
    if (next === previewIntervalMs) return;
    previewIntervalMs = next;
    if (previewTimer) {
      window.clearInterval(previewTimer);
      previewTimer = null;
    }
  }

  /**
   * 预览列名兜底：key → i18n 键。列名正常由后端随 fields 一并返回；这里只在后端为
   * 旧版本（fields 仍是 {key: value}、没带列名）时把键译成中文，与导入对话框
   * （import-from-local-dir.js 的 FIXED/OPTIONAL/EXTRA_ELEMENTS）保持一致。
   */
  var PREVIEW_FIELD_I18N = {
    title: 'files-import/el-title',
    responsible_party: 'files-import/el-responsible',
    document_number: 'files-import/el-docno',
    date: 'files-import/el-date',
    miji: 'files-import/el-security',
    kaifang_zhuangtai: 'files-import/el-open-status',
    anyou: 'files-import/el-case-cause',
    dangshiren: 'files-import/el-party',
    shenji: 'files-import/el-trial-level',
    jiean_fangshi: 'files-import/el-closing-method',
    baoguan_qixian: 'files-import/el-retention'
  };

  function previewFieldLabel(key) {
    var i18nKey = PREVIEW_FIELD_I18N[key];
    if (i18nKey) {
      var label = $.i18n(i18nKey);
      if (label && label !== i18nKey) return label;
    }
    return key;
  }

  /**
   * 预览要素列表：后端返回 [{name, value}]；旧后端返回 {key: value}，一并兼容，
   * 避免前后端版本错配时整条预览抛异常。旧结构下列名未知的键不展示——AIMP 会随页级
   * 结果返回 party_roles 这类内部辅助数据（供分件用，不是著录项），显示出来只是原始 JSON。
   */
  function previewFieldList(fields) {
    if (!fields) return [];
    if (Array.isArray(fields)) return fields;
    if (typeof fields !== 'object') return [];
    var list = [];
    Object.keys(fields).forEach(function (key) {
      var value = fields[key];
      if (!value || !PREVIEW_FIELD_I18N[key]) return;
      list.push({ name: previewFieldLabel(key), value: value });
    });
    return list;
  }

  /** 一条预览的文本：「第 N 页 · 列名：取值 · …」（要素按列顺序，过长由 CSS 截断） */
  function buildPreviewText(p) {
    var parts = [$.i18n('files-import/batch-preview-page', p.page)];
    previewFieldList(p.fields).forEach(function (f) {
      if (f && f.value) {
        parts.push(f.name ? f.name + '：' + f.value : f.value);
      }
    });
    return parts.join(' · ');
  }

  /**
   * 接收后端页级预览并逐条揭示。
   *
   * 件级行要等整卷抽完、分件矫正后才写入，2-300 页的长卷会让界面长时间空白，故页级每抽完
   * 一页就回传一条。并发批调用（4 页同时返回）时排入队列、按实测的平均每页耗时逐条追加到
   * 末尾（最新在最下，像日志一样往下长），**第一批同样逐条揭示**——不因「算是历史」就一次
   * 性铺开；逐页串行调用时每页到达本身就是一条，间隔为 0，直接显示。
   */
  function feedPagePreview($banner, pages, volumeIndex) {
    if (volumeIndex !== previewVolumeIndex) {
      previewVolumeIndex = volumeIndex;
      previewShown = [];
      previewPending = [];
      $banner.find('.batch-extraction-banner-preview').remove();
    }
    if (pages && pages.length > 0) {
      var known = {};
      previewShown.concat(previewPending).forEach(function (p) {
        known[p.page] = true;
      });
      pages.forEach(function (p) {
        if (!known[p.page]) {
          previewPending.push(p);
        }
      });
      previewPending.sort(function (a, b) {
        return a.page - b.page;
      });
      while (previewPending.length > PAGE_PENDING_LIMIT) {
        previewPending.shift();
      }
    }
    // 逐页串行调用（间隔为 0）：每页到达本身就是一条，直接显示，不必排队等定时器
    if (previewIntervalMs <= 0) {
      if (previewPending.length > 0) {
        previewShown = previewShown.concat(previewPending);
        previewPending = [];
        while (previewShown.length > PAGE_PREVIEW_MAX) {
          previewShown.shift();
        }
        renderPagePreview($banner);
      }
      return;
    }
    // 间隔变化（updatePreviewInterval）会重建定时器；若那一轮恰好没有新页到达，
    // 这里负责把积压的队列重新驱动起来，否则预览会停在原地等下一页
    // （长卷单页可达 20s，观感就是「卡住不动」）
    if (!previewTimer && previewPending.length > 0) {
      previewTimer = window.setInterval(function () {
        if (previewPending.length === 0) {
          window.clearInterval(previewTimer);
          previewTimer = null;
          return;
        }
        // 积压追赶：定时器按「平均每页耗时」逐条揭示，比实际抽取略慢时会越积越多，
        // 队列越长一次揭示越多，避免预览永远追不上顶部的进度文案
        var revealCount = 1;
        if (previewPending.length > 20) {
          revealCount = 4;
        } else if (previewPending.length > 8) {
          revealCount = 2;
        }
        while (revealCount-- > 0 && previewPending.length > 0) {
          previewShown.push(previewPending.shift());
          while (previewShown.length > PAGE_PREVIEW_MAX) {
            previewShown.shift();
          }
        }
        renderPagePreview($banner);
      }, previewIntervalMs);
    }
  }

  /**
   * 渲染已展示的页。**增量追加**，不整块重建——重建会丢失滚动位置、内容跳动（像跑马灯）。
   * 一条为「第 N 页 · 各要素值」；容器固定可视高度约 6 行，超过即出竖向滚动条。
   */
  function renderPagePreview($banner) {
    if (previewShown.length === 0) return;
    var $box = $banner.find('.batch-extraction-banner-preview');
    if ($box.length === 0) {
      // 下行：标签行（右端为折叠箭头）+ 列表。折叠只切 class，不重建本块，
      // 因此进度轮询刷新时用户选定的折叠状态不会被重置
      $box = $('<div class="batch-extraction-banner-preview"></div>')
          .append($('<div class="batch-preview-header">')
              .append($('<span class="batch-preview-label">')
                  .text($.i18n('files-import/batch-preview-title')))
              .append($('<span class="batch-preview-latest">'))
              .append($('<button type="button" class="batch-preview-toggle">&#9662;</button>')
                  .on('click', function () {
                    $box.toggleClass('collapsed');
                  })))
          .append($('<div class="batch-preview-list"></div>'));
      $banner.append($box);
    }
    var $list = $box.find('.batch-preview-list');
    var keep = {};
    previewShown.forEach(function (p) {
      keep[p.page] = true;
    });
    // 丢弃已被上限挤出的条目（最旧的一端）
    $list.children('.batch-preview-item').each(function () {
      if (!keep[$(this).attr('data-page')]) {
        $(this).remove();
      }
    });
    var rendered = {};
    $list.children('.batch-preview-item').each(function () {
      rendered[$(this).attr('data-page')] = true;
    });
    var listEl = $list[0];
    // 用户本来就在底部时继续跟随最新，否则不动、尊重其滚动位置
    var follow = listEl.scrollHeight - listEl.scrollTop - listEl.clientHeight <= 20;
    previewShown.forEach(function (p) {
      if (rendered[p.page]) return;
      $list.append($('<span class="batch-preview-item">')
          .attr('data-page', p.page)
          .text(buildPreviewText(p)));
    });
    if (follow) {
      listEl.scrollTop = listEl.scrollHeight;
    }
    // 收起时列表整体隐藏，把最新一条顶到标签后面，收起状态下也能看到进展
    $box.find('.batch-preview-latest')
        .text(buildPreviewText(previewShown[previewShown.length - 1]));
  }

  /**
   * 刷新数据表。
   *
   * keepPage 为真时保持当前所在页、并在用户本来位于底部时继续跟随新行。抽取期间表格是
   * 随件级行实时增长的，而 DataTableView.update() 默认会把 start 重置为 0——不保持页位置
   * 就会每次刷新都跳回第一页，新行却落在末页，看起来就像「一直没有新数据出来」。
   */
  function refreshDataTable(keepPage) {
    try {
      if (typeof FileViewPanel !== 'undefined' && typeof FileViewPanel.invalidatePageMap === 'function') {
        FileViewPanel.invalidatePageMap();
      }
    } catch (e) {
      // ignore
    }
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
          if (keepPage) {
            followTableBottom();
          }
        }, !!keepPage);
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
      // 上行（100% 不透明）：状态图标 + 进度文案 + 进度条 + 关闭按钮。
      // 下行预览（半透明、可折叠）由 renderPagePreview 按需追加到 $banner
      var $head = $('<div class="batch-extraction-banner-head">')
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
      $banner = $('<div class="batch-extraction-banner">').append($head);
      $('body').append($banner);
    }
    $banner.data('banner-kind', kind);
    if (kind === 'running') {
      $banner.data('processed-pages', d.processedPages || 0);
      $banner.data('rows-appended', d.rowsAppended || 0);
    }
    $banner.removeClass('banner-error');
    $banner.find('.batch-banner-progress-bar').removeClass('banner-bar-error');
    if (kind !== 'running') {
      // 终态：清掉预览与揭示队列，避免残留上一次运行的内容
      $banner.find('.batch-extraction-banner-preview').remove();
      resetPagePreview();
    }
    var $text = $banner.find('.batch-extraction-banner-text');
    var $bar = $banner.find('.batch-banner-progress-bar');
    var text = '';
    if (kind !== 'running') {
      // 终态：清除单调进度记忆，下一次提取从头计算
      $banner.removeData('last-percent');
    }
    if (kind === 'running') {
      var hint = d.message ? d.message + '。' : '';
      if (d.currentUnit) {
        hint += $.i18n('files-import/batch-banner-current', d.currentUnit);
      }
      var unitLabel = $.i18n(d.unitKind === 'volume'
          ? 'files-import/batch-unit-volume' : 'files-import/batch-unit-case');
      text = $.i18n('files-import/batch-banner-running',
          d.processedFiles || 0, d.totalFiles || 0, unitLabel,
          d.processedPages || 0, d.totalPages || 0,
          d.rowsAppended || 0, hint);
      // 进度条按件口径推进（已完成件数 + 当前件内完成度）。
      // 不能直接用 processedPages/totalPages：PDF 实际页数只能在处理中探明，
      // 分母被动态修正后移会让百分比回退，表现为进度条来回跳动。
      var percent;
      if ((d.totalFiles || 0) > 0) {
        percent = ((d.processedFiles || 0) - 1
            + (typeof d.unitFraction === 'number' ? d.unitFraction : 0)) * 100 / d.totalFiles;
      } else if ((d.totalPages || 0) > 0) {
        percent = (d.processedPages || 0) * 100 / d.totalPages;
      } else {
        percent = 0;
      }
      percent = Math.max(0, Math.min(100, Math.round(percent)));
      // 单调保护：同一任务内进度条只前进不后退
      var lastPercent = $banner.data('last-percent');
      if (typeof lastPercent === 'number' && percent < lastPercent) {
        percent = lastPercent;
      }
      $banner.data('last-percent', percent);
      $bar.css('width', percent + '%');
      if ($banner.find('.batch-extraction-banner-hint').length === 0) {
        $banner.find('.batch-extraction-banner-head')
            .append($('<span class="batch-extraction-banner-hint">')
                .text($.i18n('files-import/batch-readonly-hint')));
      }
      updatePreviewInterval(d.pageConcurrency, d.avgPageMillis);
      feedPagePreview($banner, d.recentPages, d.volumeIndex);
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
            // 保持页位置并跟随最新行，否则每次刷新都跳回第一页，看不到新抽出的件
            refreshDataTable(true);
          }
          lastRowsAppended = data.rowsAppended;
        } else {
          stop();
          setReadonlyMode(false);
          // 重新拉取项目模型，让案卷级项目在抽取结束后出现「卷级」数据表标签页
          Refine.reinitializeProjectData(function () {
            refreshDataTable();
          });
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
