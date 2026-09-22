/*

Copyright 2024 Open Refine.
All rights reserved.
*/

// This file is added to the /project page

// 点击数据表行的空白处 → 在右侧滑出该行对应的文件资源预览面板
// （交互参考 data-quality 扩展的 FileViewPanel，但只依赖「文件名 / 文件夹路径」两列，
//   不依赖质量检查的资源配置）。

var FilesResourceViewPanel = (function () {
  var PANEL_WIDTH = 700;

  var $panel = null;
  var visible = false;
  var currentRowIndex = null;
  var currentFiles = [];
  var currentFileIndex = 0;

  var COL_FILE_NAME = '文件名';
  var COL_FOLDER_PATH = '文件夹路径';

  function t(key, fallback) {
    var text = $.i18n('files-import/' + key);
    return text && text !== 'files-import/' + key ? text : fallback;
  }

  function projectHasResourceColumns() {
    try {
      return typeof theProject !== 'undefined' && theProject &&
          findCellIndex(COL_FOLDER_PATH) >= 0;
    } catch (e) {
      return false;
    }
  }

  function findCellIndex(columnName) {
    var columns = theProject.columnModel.columns;
    for (var i = 0; i < columns.length; i++) {
      if (columns[i].name === columnName) {
        return columns[i].cellIndex;
      }
    }
    return -1;
  }

  function cellValue(row, cellIndex) {
    if (cellIndex < 0 || !row.cells || cellIndex >= row.cells.length) return '';
    var cell = row.cells[cellIndex];
    return cell && cell.v !== undefined && cell.v !== null ? String(cell.v).trim() : '';
  }

  function findRow(rowIndex) {
    var rows = theProject.rowModel.rows;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].i === rowIndex) return rows[i];
    }
    return null;
  }

  /**
   * 行内前 3 个单元格依次是「星标、旗标、行号」（见 core data-table-view.js 的 renderRow），
   * 行号单元格文本形如 "12."，即 row.i + 1。
   */
  function rowIndexFromTr($tr) {
    var cells = $tr.find('td').slice(0, 3);
    for (var i = 0; i < cells.length; i++) {
      var text = $(cells[i]).text().trim();
      if (/^\d+\.$/.test(text)) {
        return parseInt(text, 10) - 1;
      }
    }
    return null;
  }

  function buildResource(rowIndex) {
    var row = findRow(rowIndex);
    if (!row) return null;
    var dir = cellValue(row, findCellIndex(COL_FOLDER_PATH));
    if (!dir) return null;
    return {
      dir: dir,
      fileName: cellValue(row, findCellIndex(COL_FILE_NAME))
    };
  }

  function createPanel() {
    var panel = $('<div>').attr('id', 'files-resource-view-panel').addClass('files-resource-panel');

    var header = $('<div>').addClass('files-resource-header').appendTo(panel);
    $('<span>').addClass('files-resource-title').appendTo(header);
    $('<button>')
      .addClass('files-resource-close-btn')
      .attr('title', t('resource-view-close', '关闭'))
      .html('&times;')
      .on('click', hide)
      .appendTo(header);

    var body = $('<div>').addClass('files-resource-body').appendTo(panel);
    var mainArea = $('<div>').addClass('files-resource-main').appendTo(body);
    $('<div>').addClass('files-resource-content').appendTo(mainArea);
    $('<div>').addClass('files-resource-footer').appendTo(mainArea);

    var thumbArea = $('<div>').addClass('files-resource-thumbnails').appendTo(body);
    // tabindex 让缩略图列表可以获得焦点，从而接收左右方向键（上翻/下翻）
    $('<div>').addClass('files-resource-thumb-list').attr('tabindex', 0).appendTo(thumbArea);

    var rightPanel = $('#right-panel');
    if (rightPanel.length > 0) {
      panel.appendTo(rightPanel);
    } else {
      panel.appendTo(document.body);
    }

    // 左右方向键在缩略图列表内上翻/下翻（参考 data-quality 的 FileViewPanel）
    panel.find('.files-resource-thumb-list')
      .off('keydown.filesResourceKeys')
      .on('keydown.filesResourceKeys', function (e) {
        if (e.keyCode === 37) {
          e.preventDefault();
          navigatePrev();
        } else if (e.keyCode === 39) {
          e.preventDefault();
          navigateNext();
        }
      });

    panel.hide();
    $panel = panel;
    return panel;
  }

  function show(rowIndex) {
    var resource = buildResource(rowIndex);
    if (!resource) return;

    currentRowIndex = rowIndex;
    currentFileIndex = 0;
    currentFiles = [];

    var panel = $panel || createPanel();
    panel.show();
    visible = true;
    adjustLayout();

    var $content = panel.find('.files-resource-content');
    var $title = panel.find('.files-resource-title');
    var $footer = panel.find('.files-resource-footer');
    $title.text(resource.fileName || resource.dir);
    $footer.empty();
    $content.html('<div class="files-resource-hint">' + t('resource-view-loading', '加载中…') + '</div>');

    listFiles(resource, function (files, error) {
      if (!files || files.length === 0) {
        $content.html('<div class="files-resource-empty">' +
            (error || t('resource-view-empty', '该目录下没有可预览的文件')) +
            '<div class="files-resource-path">' + escapeHtml(resource.dir) + '</div></div>');
        return;
      }
      currentFiles = files;
      // 「文件名」列命中的文件放在第一位，实现"直达该文件"
      if (resource.fileName) {
        for (var i = 0; i < files.length; i++) {
          if (files[i].name === resource.fileName) {
            currentFileIndex = i;
            break;
          }
        }
        if (currentFileIndex > 0) {
          var target = files.splice(currentFileIndex, 1)[0];
          files.unshift(target);
          currentFileIndex = 0;
        }
      }
      renderThumbnails();
      loadFile();
    });
  }

  function hide() {
    if ($panel) $panel.hide();
    visible = false;
    currentRowIndex = null;
    currentFiles = [];
    currentFileIndex = 0;
    restoreLayout();
    $('.data-table tr').removeClass('files-resource-active-row');
  }

  // 面板占用右侧固定宽度，数据表一侧相应收窄，避免遮挡
  function adjustLayout() {
    $('#view-panel, #tool-panel').css('width', 'calc(100% - ' + PANEL_WIDTH + 'px)');
    if ($panel) {
      $panel.css({
        position: 'absolute',
        top: 0,
        right: 0,
        width: PANEL_WIDTH + 'px',
        height: '100%'
      });
    }
  }

  function restoreLayout() {
    $('#view-panel, #tool-panel').css('width', '');
  }

  function toggle(rowIndex) {
    if (visible && currentRowIndex === rowIndex) {
      hide();
    } else {
      show(rowIndex);
    }
  }

  function listFiles(resource, callback) {
    $.ajax({
      url: '/command/records-assets/list',
      type: 'GET',
      data: { root: '', path: resource.dir, page: 1, pageSize: 500 },
      dataType: 'json',
      success: function (data) {
        if (data.status !== 'ok' && data.status !== 'success') {
          callback([], data.message || t('resource-view-error', '读取目录失败'));
          return;
        }
        var fullPath = data.fullPath || resource.dir;
        var files = [];
        (data.items || []).forEach(function (item) {
          if (item.isDirectory) return;
          files.push({
            name: item.name,
            size: item.size || 0,
            rootPath: fullPath
          });
        });
        callback(files, null);
      },
      error: function () {
        callback([], t('resource-view-error', '读取目录失败'));
      }
    });
  }

  var IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp', 'ico', 'tif', 'tiff'];

  // 文件类型 → core 静态图标（/images/extensions），与 data-quality 一致
  var FILE_ICON_MAP = {
    'pdf': 'file-pdf.svg',
    'doc': 'file-word.svg', 'docx': 'file-word.svg',
    'xls': 'file-excel.svg', 'xlsx': 'file-excel.svg', 'csv': 'file-excel.svg',
    'txt': 'file-text.svg', 'md': 'file-text.svg', 'json': 'file-text.svg',
    'xml': 'file-text.svg', 'html': 'file-text.svg', 'htm': 'file-text.svg',
    'zip': 'file-zipper.svg', 'rar': 'file-zipper.svg', '7z': 'file-zipper.svg',
    'mp4': 'file-video.svg', 'avi': 'file-video.svg', 'mov': 'file-video.svg',
    'mp3': 'file-audio.svg', 'wav': 'file-audio.svg', 'flac': 'file-audio.svg',
    'jpg': 'file-image.svg', 'jpeg': 'file-image.svg', 'png': 'file-image.svg',
    'gif': 'file-image.svg', 'bmp': 'file-image.svg', 'svg': 'file-image.svg',
    'webp': 'file-image.svg', 'tif': 'file-image.svg', 'tiff': 'file-image.svg',
    'ico': 'file-image.svg'
  };

  // 缩略文件名只保留「首部 + 尾部」且不含扩展名，完整文件名由 hover(title) 展示
  var NAME_HEAD_CHARS = 4;
  var NAME_TAIL_CHARS = 3;

  // 只有图片渲染成缩略图，PDF 等多页文件展示文件类型图标
  function isImageFile(name) {
    var ext = String(name || '').split('.').pop().toLowerCase();
    return IMAGE_EXTENSIONS.indexOf(ext) !== -1;
  }

  function fileIconHtml(name) {
    var ext = String(name || '').split('.').pop().toLowerCase();
    var iconFile = FILE_ICON_MAP[ext] || 'file-text.svg';
    return $('<img>')
      .addClass('files-resource-thumb-icon')
      .attr('src', '/images/extensions/' + iconFile)
      .attr('alt', '');
  }

  function truncateFileName(name) {
    var base = String(name || '');
    var dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    if (base.length <= NAME_HEAD_CHARS + NAME_TAIL_CHARS + 1) return base;
    return base.substring(0, NAME_HEAD_CHARS) + '…' + base.substring(base.length - NAME_TAIL_CHARS);
  }

  function renderThumbnails() {
    var thumbArea = $panel.find('.files-resource-thumbnails');
    var thumbList = $panel.find('.files-resource-thumb-list');
    thumbList.empty();

    if (currentFiles.length <= 1) {
      thumbArea.hide();
      return;
    }
    thumbArea.show();

    currentFiles.forEach(function (file, index) {
      var thumb = $('<div>')
        .addClass('files-resource-thumb-item' + (index === currentFileIndex ? ' active' : ''))
        .attr('data-index', index)
        // 整项 hover 展示完整文件名
        .attr('title', file.name)
        .on('click', function () {
          selectFile(index);
          focusThumbList();
        })
        .appendTo(thumbList);

      if (isImageFile(file.name)) {
        loadThumbnail(file, $('<img>')
          .addClass('files-resource-thumb-img')
          .attr('alt', file.name)
          .appendTo(thumb));
      } else {
        thumb.append(fileIconHtml(file.name));
      }

      $('<div>').addClass('files-resource-thumb-name').text(truncateFileName(file.name)).appendTo(thumb);
    });

    // 面板打开后即可直接用左右键翻页，无需先点击列表
    focusThumbList();
    scrollToThumbnail();
  }

  // 用原生 focus()，避免 jQuery 的 .focus() 简写弃用告警
  function focusThumbList() {
    var list = $panel.find('.files-resource-thumb-list')[0];
    if (list && typeof list.focus === 'function') list.focus();
  }

  // 缩略图取的是预览接口的缩略图模式（与 data-quality 一致）
  function loadThumbnail(file, $img) {
    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: { root: file.rootPath, path: file.name, thumbnail: 'true' },
      dataType: 'json',
      success: function (data) {
        if ((data.status === 'ok' || data.status === 'success') &&
            data.previewType === 'image' && data.preview) {
          $img.attr('src', data.preview);
        } else {
          // 取不到缩略图时退化为文件类型图标
          $img.replaceWith(fileIconHtml(file.name));
        }
      },
      error: function () {
        $img.replaceWith(fileIconHtml(file.name));
      }
    });
  }

  function selectFile(index) {
    if (index < 0 || index >= currentFiles.length || index === currentFileIndex) return;
    currentFileIndex = index;
    updateThumbnailSelection();
    scrollToThumbnail();
    loadFile();
  }

  // 只切换高亮，不重建列表（重建会重新拉取全部缩略图）
  function updateThumbnailSelection() {
    var items = $panel.find('.files-resource-thumb-item');
    items.removeClass('active');
    items.eq(currentFileIndex).addClass('active');
  }

  function scrollToThumbnail() {
    var thumbList = $panel.find('.files-resource-thumb-list');
    var active = thumbList.find('.files-resource-thumb-item.active');
    if (active.length === 0) return;

    var containerTop = thumbList.scrollTop();
    var containerHeight = thumbList.height();
    var thumbTop = active.position().top + containerTop;
    var thumbHeight = active.outerHeight();

    if (thumbTop < containerTop) {
      thumbList.scrollTop(thumbTop);
    } else if (thumbTop + thumbHeight > containerTop + containerHeight) {
      thumbList.scrollTop(thumbTop + thumbHeight - containerHeight);
    }
  }

  function navigatePrev() {
    selectFile(currentFileIndex - 1);
  }

  function navigateNext() {
    selectFile(currentFileIndex + 1);
  }

  function loadFile() {
    var file = currentFiles[currentFileIndex];
    if (!file) return;
    var $content = $panel.find('.files-resource-content');
    var $title = $panel.find('.files-resource-title');
    var $footer = $panel.find('.files-resource-footer');

    $title.text(file.name);
    $footer.empty();
    $content.html('<div class="files-resource-hint">' + t('resource-view-loading', '加载中…') + '</div>');

    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: { root: file.rootPath, path: file.name },
      dataType: 'json',
      success: function (data) {
        if (data.status !== 'ok' && data.status !== 'success') {
          $content.html('<div class="files-resource-empty">' +
              escapeHtml(data.message || t('resource-view-error', '加载失败')) + '</div>');
          return;
        }
        renderContent($content, data, file);
        renderFooter($footer, data, file);
      },
      error: function () {
        $content.html('<div class="files-resource-empty">' +
            t('resource-view-error', '加载失败') + '</div>');
      }
    });
  }

  function renderContent($content, data, file) {
    $content.empty();
    var previewType = data.previewType || 'unknown';

    if (previewType === 'image' && data.preview) {
      $('<div>').addClass('files-resource-image-container')
        .append($('<img>').attr('src', data.preview).addClass('files-resource-image'))
        .appendTo($content);
    } else if (previewType === 'pdf') {
      var url = fileUrl(file) + '#navpanes=0&toolbar=1&page=1';
      $('<div>').addClass('files-resource-pdf-container')
        .append($('<iframe>').attr('src', url).addClass('files-resource-pdf-iframe'))
        .appendTo($content);
    } else if (previewType === 'text' && data.preview) {
      $('<div>').addClass('files-resource-text-container')
        .append($('<pre>').addClass('files-resource-text').text(data.preview))
        .appendTo($content);
    } else {
      $content.html('<div class="files-resource-empty">' +
          t('resource-view-no-preview', '当前文件不支持预览') + '</div>');
    }
  }

  function renderFooter($footer, data, file) {
    $footer.empty();
    var $info = $('<div>').addClass('files-resource-info').appendTo($footer);
    if (data.size) {
      $('<span>').text(formatSize(data.size)).appendTo($info);
    }
    if (currentFiles.length > 1) {
      $('<span>').text((currentFileIndex + 1) + ' / ' + currentFiles.length).appendTo($info);
    }
    $('<div>').addClass('files-resource-actions')
      .append($('<button>')
          .addClass('button')
          .text(t('resource-view-download', '下载'))
          .on('click', function () {
            window.open(fileUrl(file) + '&download=true', '_blank');
          }))
      .appendTo($footer);
  }

  function fileUrl(file) {
    return '/command/records-assets/file?root=' + encodeURIComponent(file.rootPath) +
        '&path=' + encodeURIComponent(file.name);
  }

  function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  }

  function escapeHtml(text) {
    return $('<div>').text(text == null ? '' : text).html();
  }

  function installRowClick() {
    // 事件委托：行内空白处（非链接/按钮/输入等交互元素）点击即预览该行文件资源
    $(document).on('click.filesResourceView', '.data-table tr', function (e) {
      if ($(e.target).is('a, button, input, select, textarea')) return;
      if ($(this).hasClass('data-table-row-editing')) return;
      if (!projectHasResourceColumns()) return;

      var rowIndex = rowIndexFromTr($(this));
      if (rowIndex === null) return;

      toggle(rowIndex);
      $('.data-table tr').removeClass('files-resource-active-row');
      if (visible) {
        $(this).addClass('files-resource-active-row');
      }
    });

    $(document).on('keydown.filesResourceView', function (e) {
      if (visible && e.keyCode === 27) hide();
    });

    $(window).on('resize.filesResourceView', function () {
      if (visible) adjustLayout();
    });
  }

  $(function () {
    try {
      if (window.I18NUtil && typeof I18NUtil.init === 'function') {
        I18NUtil.init('files');
      }
    } catch (e) {
      // ignore
    }
    installRowClick();
  });

  return {
    show: show,
    hide: hide,
    toggle: toggle,
    isVisible: function () {
      return visible;
    },
    PANEL_WIDTH: PANEL_WIDTH
  };
})();