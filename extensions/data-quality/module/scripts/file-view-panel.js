/*
 * File View Panel
 * Inline panel within rightPanelDiv for previewing resource files associated with data rows.
 * Positioned as a 700px-wide div to the right of viewPanelDiv/summaryBarDiv/quality-rules-panel.
 */

var FileViewPanel = {};

(function() {
  'use strict';

  FileViewPanel._panel = null;
  FileViewPanel._isVisible = false;
  FileViewPanel._currentRow = null;
  FileViewPanel._currentFiles = [];
  FileViewPanel._currentFileIndex = 0;
  /** 当前定位页：PDF 为文件内部页号，整目录成件的图片为目录内第几个文件（1 起） */
  FileViewPanel._currentPage = 1;
  /** 当前单元格的候选页列表 [{v:取值, c:置信度, p:页码}]，首位为写入单元格的取值所在页 */
  FileViewPanel._pageCandidates = [];
  FileViewPanel._pageMap = null;
  FileViewPanel._pageMapProjectId = null;
  /** 当前预览内容类型（image / pdf / text），用于区分页面定位与文件切换的处理方式 */
  FileViewPanel._currentPreviewType = null;
  FileViewPanel._zoomLevel = 1;
  FileViewPanel._currentOffsetX = 0;
  FileViewPanel._currentOffsetY = 0;
  FileViewPanel._isDraggingImage = false;
  FileViewPanel._dragStartX = 0;
  FileViewPanel._dragStartY = 0;
  FileViewPanel._dragNamespace = '.fileViewDrag_' + Math.random().toString(36).substr(2, 9);
  FileViewPanel._keyNamespace = '.fileViewKey_' + Math.random().toString(36).substr(2, 9);
  FileViewPanel.PANEL_WIDTH = 700;

  /** 条目提取项目的「文件夹路径」列，无资源路径配置时用它兜底定位资源目录 */
  FileViewPanel.FOLDER_PATH_COLUMN = '文件夹路径';

  /** 案卷 PDF 抽取生成的「文件名」列，用于把行定位到卷目录下的具体文件 */
  FileViewPanel.FILE_NAME_COLUMN = '文件名';

  FileViewPanel._findColumnCellIndex = function(columnName) {
    if (typeof theProject === 'undefined' || !theProject || !theProject.columnModel) {
      return -1;
    }
    var columns = theProject.columnModel.columns;
    for (var i = 0; i < columns.length; i++) {
      if (columns[i].name === columnName) {
        return columns[i].cellIndex;
      }
    }
    return -1;
  };

  FileViewPanel._findFolderPathCellIndex = function() {
    return FileViewPanel._findColumnCellIndex(FileViewPanel.FOLDER_PATH_COLUMN);
  };

  FileViewPanel._findRow = function(rowIndex) {
    var rows = theProject.rowModel.rows;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].i === rowIndex) {
        return rows[i];
      }
    }
    return null;
  };

  FileViewPanel._getCellText = function(row, cellIndex) {
    if (!row || cellIndex < 0 || cellIndex >= row.cells.length) {
      return '';
    }
    var cell = row.cells[cellIndex];
    if (!cell || cell.v === undefined || cell.v === null) {
      return '';
    }
    return String(cell.v).trim();
  };

  /** 该行「文件名」列的值；无该列（非 PDF 批次）时为空串 */
  FileViewPanel._getRowFileName = function(rowIndex) {
    var cellIndex = FileViewPanel._findColumnCellIndex(FileViewPanel.FILE_NAME_COLUMN);
    if (cellIndex < 0) {
      return '';
    }
    return FileViewPanel._getCellText(FileViewPanel._findRow(rowIndex), cellIndex);
  };

  /** 文件名比较用归一化：去目录、忽略大小写 */
  FileViewPanel._normalizeFileToken = function(value) {
    var text = String(value || '').trim().replace(/\\/g, '/');
    var slash = text.lastIndexOf('/');
    if (slash >= 0) {
      text = text.substring(slash + 1);
    }
    return text.toLowerCase();
  };

  /** 目录内按文件名（先全名、后主名）找序号，找不到返回 -1 */
  FileViewPanel._indexOfFile = function(files, targetName) {
    var targetToken = FileViewPanel._normalizeFileToken(targetName);
    for (var i = 0; i < files.length; i++) {
      if (FileViewPanel._normalizeFileToken(files[i].name) === targetToken) {
        return i;
      }
    }
    // 列值可能不含扩展名，退一步按主文件名比较
    var targetStem = targetToken.replace(/\.[^.]+$/, '');
    for (var j = 0; j < files.length; j++) {
      var stem = FileViewPanel._normalizeFileToken(files[j].name).replace(/\.[^.]+$/, '');
      if (stem === targetStem) {
        return j;
      }
    }
    return -1;
  };

  /**
   * 当前要定位的文件名：案卷 PDF 抽取取该行「文件名」列；
   * 整目录成件的图片批次没有该列，取候选页自带的文件名（不依赖目录列举顺序）。
   */
  FileViewPanel._getTargetFileName = function() {
    var rowFileName = FileViewPanel._getRowFileName(FileViewPanel._currentRow);
    if (rowFileName) {
      return rowFileName;
    }
    for (var i = 0; i < FileViewPanel._pageCandidates.length; i++) {
      if (FileViewPanel._pageCandidates[i].f) {
        return FileViewPanel._pageCandidates[i].f;
      }
    }
    return '';
  };

  /**
   * 打开面板时定位到该行对应的文件：先按文件名匹配（案卷 PDF、整目录图片均适用），
   * 匹配不到时退化为按候选页序号选中目录内第 N 个文件，最后回退到第一个文件。
   */
  FileViewPanel._resolveInitialFileIndex = function(files) {
    var targetName = FileViewPanel._getTargetFileName();
    if (targetName) {
      var matched = FileViewPanel._indexOfFile(files, targetName);
      if (matched >= 0) {
        return matched;
      }
      console.log('[FileViewPanel] 未匹配到目录内文件:', targetName);
    }
    var pageIndex = FileViewPanel._currentPage - 1;
    if (pageIndex > 0 && pageIndex < files.length) {
      return pageIndex;
    }
    return 0;
  };

  /**
   * 是否可以对当前项目做行级资源预览：
   * 优先按质量检查的资源路径配置定位；条目提取项目没有该配置，回退到「文件夹路径」列。
   */
  FileViewPanel.canPreview = function() {
    if (typeof QualityAlignment === 'undefined' || !QualityAlignment._resourceConfig) {
      return false;
    }
    var resourceConfig = QualityAlignment._resourceConfig;
    if (resourceConfig.pathFields && resourceConfig.pathFields.length > 0) {
      return true;
    }
    return FileViewPanel._findFolderPathCellIndex() >= 0;
  };

  /**
   * 拉取项目级「行 → 列 → 候选页」映射，仅首次请求；非批量提取项目返回空映射。
   */
  FileViewPanel._ensurePageMap = function(callback) {
    var projectId = (typeof theProject !== 'undefined' && theProject) ? theProject.id : null;
    if (FileViewPanel._pageMap !== null && FileViewPanel._pageMapProjectId === projectId) {
      callback();
      return;
    }
    FileViewPanel._pageMapProjectId = projectId;
    FileViewPanel._pageMap = {};
    if (projectId === null || projectId === undefined) {
      callback();
      return;
    }
    Refine.wrapCSRF(function(token) {
      $.post('command/files/batch-extraction', {
        subCommand: 'pageMap',
        project: projectId,
        csrf_token: token
      }, function(data) {
        if (data && data.code === 'ok' && data.pageMap) {
          FileViewPanel._pageMap = data.pageMap;
        }
        callback();
      }, 'json').fail(function() {
        callback();
      });
    });
  };

  /** 取该单元格对应的候选页列表，供 _currentPage 与页签展示使用 */
  FileViewPanel._resolveCellCandidates = function(rowIndex, cellIndex) {
    FileViewPanel._pageCandidates = [];
    FileViewPanel._currentPage = 1;
    if (cellIndex < 0 || !FileViewPanel._pageMap) return;
    var columns = theProject.columnModel.columns;
    var columnName = null;
    for (var i = 0; i < columns.length; i++) {
      if (columns[i].cellIndex === cellIndex) {
        columnName = columns[i].name;
        break;
      }
    }
    if (columnName === null) return;
    var rowNode = FileViewPanel._pageMap[String(rowIndex)];
    if (!rowNode) return;
    var list = rowNode[columnName];
    if (!list || !list.length) return;
    // 历史提取数据里可能残留无取值 / 无页码的空候选，先剔除，避免默认落回第 1 页
    list = list.filter(function(candidate) {
      return candidate && candidate.v && parseInt(candidate.p, 10) > 0;
    });
    if (!list.length) return;
    FileViewPanel._pageCandidates = list;
    var page = parseInt(list[0].p, 10);
    FileViewPanel._currentPage = page > 0 ? page : 1;
  };

  FileViewPanel.show = function(rowIndex, cellIndex) {
    if (!FileViewPanel.canPreview()) {
      return;
    }

    var resourcePath = FileViewPanel._buildResourcePath(rowIndex);
    if (!resourcePath) {
      return;
    }

    FileViewPanel._currentRow = rowIndex;
    FileViewPanel._currentCellIndex = (typeof cellIndex === 'number') ? cellIndex : -1;
    FileViewPanel._currentFileIndex = 0;
    FileViewPanel._currentPage = 1;
    FileViewPanel._pageCandidates = [];
    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;

    if (!FileViewPanel._panel) {
      FileViewPanel._createPanel();
    }

    FileViewPanel._panel.show();
    FileViewPanel._isVisible = true;

    var content = FileViewPanel._panel.find('.file-view-content');
    var title = FileViewPanel._panel.find('.file-view-title');
    title.text($.i18n('data-quality-extension/file-view-loading') || 'Loading...');
    content.html('<div class="file-view-loading">' + ($.i18n('data-quality-extension/file-view-loading') || 'Loading...') + '</div>');

    FileViewPanel._adjustRightPanel();

    FileViewPanel._ensurePageMap(function() {
      FileViewPanel._resolveCellCandidates(rowIndex, FileViewPanel._currentCellIndex);
      FileViewPanel._fetchFilesForResourcePath(resourcePath, function(files, error) {
        if (!files || files.length === 0) {
          var content = FileViewPanel._panel.find('.file-view-content');
          var title = FileViewPanel._panel.find('.file-view-title');
          var footer = FileViewPanel._panel.find('.file-view-footer');
          title.text($.i18n('data-quality-extension/file-view-title') || 'File Preview');
          footer.empty();
          content.html(
            '<div class="file-view-no-preview">' +
            '<div class="file-view-no-preview-icon">📂</div>' +
            '<div>' + (error || $.i18n('data-quality-extension/file-view-no-resource') || 'No resource files') + '</div>' +
            '<div style="font-size:11px;color:#999;margin-top:8px;word-break:break-all;">' + resourcePath + '</div>' +
            '</div>'
          );
          FileViewPanel._panel.focus();
          return;
        }

        FileViewPanel._currentFiles = files;
        // 案卷 PDF 抽取：按该行「文件名」列直接定位到对应 PDF，而不是默认落到卷内第一个文件
        FileViewPanel._currentFileIndex = FileViewPanel._resolveInitialFileIndex(files);
        FileViewPanel._renderThumbnails();
        FileViewPanel._loadCurrentFile();
        FileViewPanel._scrollToThumbnail();
        FileViewPanel._focusThumbList();
      });
    });

    FileViewPanel._panel.off('keydown' + FileViewPanel._keyNamespace).on('keydown' + FileViewPanel._keyNamespace, FileViewPanel._handleKeyDown);
  };

  FileViewPanel.hide = function() {
    if (FileViewPanel._panel) {
      FileViewPanel._panel.hide();
    }
    FileViewPanel._isVisible = false;
    FileViewPanel._currentRow = null;
    FileViewPanel._currentCellIndex = -1;
    FileViewPanel._currentFiles = [];
    FileViewPanel._currentFileIndex = 0;
    FileViewPanel._currentPage = 1;
    FileViewPanel._pageCandidates = [];
    FileViewPanel._currentPreviewType = null;

    FileViewPanel._restoreRightPanel();

    FileViewPanel._panel.off(FileViewPanel._keyNamespace);
  };

  FileViewPanel.toggle = function(rowIndex, cellIndex) {
    if (FileViewPanel._isVisible && FileViewPanel._currentRow === rowIndex
        && FileViewPanel._currentCellIndex === cellIndex) {
      FileViewPanel.hide();
    } else {
      FileViewPanel.show(rowIndex, cellIndex);
    }
  };

  FileViewPanel.isVisible = function() {
    return FileViewPanel._isVisible;
  };

  /** 重跑提取任务后页码映射会变化，由外部在数据刷新时调用以清掉缓存 */
  FileViewPanel.invalidatePageMap = function() {
    FileViewPanel._pageMap = null;
    FileViewPanel._pageMapProjectId = null;
  };

  FileViewPanel._createPanel = function() {
    var panel = $('<div>')
      .attr('id', 'file-view-panel')
      .addClass('file-view-panel')
      .attr('tabindex', '0');

    var header = $('<div>')
      .addClass('file-view-header')
      .appendTo(panel);

    $('<span>')
      .addClass('file-view-title')
      .appendTo(header);

    $('<button>')
      .addClass('file-view-close-btn')
      .html('&times;')
      .on('click', function() {
        FileViewPanel.hide();
      })
      .appendTo(header);

    var body = $('<div>')
      .addClass('file-view-body')
      .appendTo(panel);

    var mainArea = $('<div>')
      .addClass('file-view-main')
      .appendTo(body);

    var content = $('<div>')
      .addClass('file-view-content')
      .appendTo(mainArea);

    var footer = $('<div>')
      .addClass('file-view-footer')
      .appendTo(mainArea);

    var thumbArea = $('<div>')
      .addClass('file-view-thumbnails')
      .appendTo(body);

    // 列表可聚焦：面板打开后焦点默认落在文件列表，滚轮与方向键默认作用于文件浏览
    var thumbList = $('<div>')
      .addClass('file-view-thumb-list')
      .attr('tabindex', '0')
      .appendTo(thumbArea);

    FileViewPanel._bindPreviewWheelForwarding(content);

    var rightPanel = $('#right-panel');
    if (rightPanel.length > 0) {
      panel.appendTo(rightPanel);
    }

    FileViewPanel._panel = panel;
    panel.hide();
  };

  FileViewPanel._buildResourcePath = function(rowIndex) {
    var resourceConfig = QualityAlignment._resourceConfig;
    console.log('[FileViewPanel] _buildResourcePath called, rowIndex:', rowIndex);
    console.log('[FileViewPanel] resourceConfig:', JSON.stringify(resourceConfig));

    var rows = theProject.rowModel.rows;

    var row = null;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].i === rowIndex) {
        row = rows[i];
        break;
      }
    }
    if (!row) {
      console.log('[FileViewPanel] row not found for rowIndex:', rowIndex);
      return null;
    }

    var cells = row.cells;

    // 条目提取项目没有资源路径配置，直接用该行「文件夹路径」列的值作为资源目录
    var configuredFields = resourceConfig.pathFields || [];
    if (configuredFields.length === 0) {
      var folderCellIndex = FileViewPanel._findFolderPathCellIndex();
      var folderCell = (folderCellIndex >= 0 && folderCellIndex < cells.length) ? cells[folderCellIndex] : null;
      var folderValue = (folderCell && folderCell.v !== undefined && folderCell.v !== null) ? String(folderCell.v).trim() : '';
      console.log('[FileViewPanel] no pathFields, fallback to folder path column:', folderValue);
      return folderValue ? folderValue.replace(/\\/g, '/') : null;
    }

    var basePath = resourceConfig.basePath || '';
    var pathFields = resourceConfig.pathFields || [];
    var pathMode = resourceConfig.pathMode || 'separator';
    var separator = resourceConfig.separator || QualityAlignment._getPathSeparator();
    var template = resourceConfig.template || '';

    console.log('[FileViewPanel] basePath:', basePath, 'pathFields:', pathFields, 'pathMode:', pathMode, 'template:', template);

    var fieldValues = [];
    var columns = theProject.columnModel.columns;
    pathFields.forEach(function(fieldName) {
      var cellIndex = -1;
      for (var c = 0; c < columns.length; c++) {
        if (columns[c].name === fieldName) {
          cellIndex = columns[c].cellIndex;
          break;
        }
      }
      console.log('[FileViewPanel] fieldName:', fieldName, 'cellIndex:', cellIndex, 'cells.length:', cells.length);
      var cell = (cellIndex >= 0 && cellIndex < cells.length) ? cells[cellIndex] : null;
      var value = (cell && cell.v !== undefined && cell.v !== null) ? String(cell.v).trim() : '';
      console.log('[FileViewPanel] cell:', cell, 'value:', value);
      if (value) {
        fieldValues.push(value);
      }
    });

    console.log('[FileViewPanel] fieldValues:', fieldValues);

    if (fieldValues.length === 0) {
      return null;
    }

    var resourcePath;
    if (pathMode === 'template' && template) {
      resourcePath = template;
      for (var fi = 0; fi < fieldValues.length; fi++) {
        resourcePath = resourcePath.split('{' + fi + '}').join(fieldValues[fi]);
      }
    } else {
      resourcePath = fieldValues.join(separator);
    }

    if (basePath) {
      if (!basePath.endsWith('/') && !basePath.endsWith('\\') && !resourcePath.startsWith('/') && !resourcePath.startsWith('\\')) {
        resourcePath = basePath + separator + resourcePath;
      } else {
        resourcePath = basePath + resourcePath;
      }
    }

    resourcePath = resourcePath.replace(/\\/g, '/');

    return resourcePath;
  };

  FileViewPanel._fetchFilesForResourcePath = function(resourcePath, callback) {
    $.ajax({
      url: '/command/records-assets/list',
      type: 'GET',
      data: { root: '', path: resourcePath, page: 1, pageSize: 100 },
      dataType: 'json',
      success: function(data) {
        var files = [];
        if ((data.status === 'ok' || data.status === 'success') && data.items) {
          var fullPath = data.fullPath || resourcePath;
          data.items.forEach(function(item) {
            if (!item.isDirectory) {
              files.push({
                name: item.name,
                path: fullPath + '/' + item.name,
                rootPath: fullPath,
                size: item.size || 0,
                mimeType: item.mimeType || ''
              });
            }
          });
          callback(files, null);
        } else {
          callback([], data.message || 'Error');
        }
      },
      error: function() {
        callback([], $.i18n('data-quality-extension/file-view-error') || 'Error');
      }
    });
  };

  FileViewPanel._renderThumbnails = function() {
    var thumbList = FileViewPanel._panel.find('.file-view-thumb-list');
    thumbList.empty();

    var thumbArea = FileViewPanel._panel.find('.file-view-thumbnails');

    if (FileViewPanel._currentFiles.length <= 1) {
      thumbArea.hide();
      return;
    }

    thumbArea.show();

    FileViewPanel._currentFiles.forEach(function(file, index) {
      var thumb = $('<div>')
        .addClass('file-view-thumb-item')
        .attr('data-index', index)
        .appendTo(thumbList);

      if (index === FileViewPanel._currentFileIndex) {
        thumb.addClass('active');
      }

      var ext = file.name.split('.').pop().toLowerCase();
      var isImage = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp', 'ico', 'tiff'].indexOf(ext) !== -1;

      if (isImage) {
        var thumbImg = $('<img>')
          .addClass('file-view-thumb-img')
          .attr('alt', file.name)
          .css({ width: '100%', height: 'auto', display: 'block' })
          .appendTo(thumb);

        $.ajax({
          url: '/command/records-assets/preview',
          type: 'GET',
          data: { root: file.rootPath, path: file.name, thumbnail: 'true' },
          dataType: 'json',
          success: function(data) {
            if ((data.status === 'ok' || data.status === 'success') && data.previewType === 'image' && data.preview) {
              thumbImg.attr('src', data.preview);
            } else {
              thumbImg.replaceWith(FileViewPanel._getFileIconHtml(file.name));
            }
          },
          error: function() {
            thumbImg.replaceWith(FileViewPanel._getFileIconHtml(file.name));
          }
        });
      } else {
        thumb.append(FileViewPanel._getFileIconHtml(file.name));
      }

      $('<div>')
        .addClass('file-view-thumb-name')
        .text(FileViewPanel._truncateFileName(file.name))
        .attr('title', file.name)
        .appendTo(thumb);

      thumb.on('click', function() {
        FileViewPanel._currentFileIndex = index;
        FileViewPanel._zoomLevel = 1;
        FileViewPanel._currentOffsetX = 0;
        FileViewPanel._currentOffsetY = 0;
        FileViewPanel._loadCurrentFile();
        FileViewPanel._updateThumbnailSelection();
      });
    });
  };

  FileViewPanel._getFileIconHtml = function(filename) {
    var ext = filename.split('.').pop().toLowerCase();
    var basePath = '/images/extensions/';
    var iconMap = {
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
      'webp': 'file-image.svg', 'tiff': 'file-image.svg', 'ico': 'file-image.svg'
    };
    var iconFile = iconMap[ext] || 'file-text.svg';
    return '<img src="' + basePath + iconFile + '" class="file-view-thumb-icon" alt="" />';
  };

  // 缩略文件名只保留「首部 + 尾部」且不含扩展名，完整文件名由 hover(title) 展示
  FileViewPanel._truncateFileName = function(filename) {
    var base = String(filename || '');
    var dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    var headChars = 4;
    var tailChars = 3;
    if (base.length <= headChars + tailChars + 1) return base;
    return base.substring(0, headChars) + '…' + base.substring(base.length - tailChars);
  };

  FileViewPanel._updateThumbnailSelection = function() {
    FileViewPanel._panel.find('.file-view-thumb-item').removeClass('active');
    FileViewPanel._panel.find('.file-view-thumb-item[data-index="' + FileViewPanel._currentFileIndex + '"]').addClass('active');
  };

  FileViewPanel._loadCurrentFile = function() {
    if (FileViewPanel._currentFiles.length === 0) return;

    var file = FileViewPanel._currentFiles[FileViewPanel._currentFileIndex];
    var content = FileViewPanel._panel.find('.file-view-content');
    var footer = FileViewPanel._panel.find('.file-view-footer');
    var title = FileViewPanel._panel.find('.file-view-title');

    title.text(file.name);
    content.html('<div class="file-view-loading">' + ($.i18n('data-quality-extension/file-view-loading') || 'Loading...') + '</div>');
    footer.empty();

    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;

    $.ajax({
      url: '/command/records-assets/preview',
      type: 'GET',
      data: { root: file.rootPath, path: file.name },
      dataType: 'json',
      success: function(data) {
        if (data.status === 'ok' || data.status === 'success') {
          FileViewPanel._renderFileContent(content, data, file);
          FileViewPanel._renderFooter(footer, data, file);
        } else {
          content.html('<div class="file-view-error">' + (data.message || 'Error') + '</div>');
        }
      },
      error: function() {
        content.html('<div class="file-view-error">' + ($.i18n('data-quality-extension/file-view-error') || 'Error') + '</div>');
      }
    });
  };

  FileViewPanel._renderFileContent = function(container, data, file) {
    container.empty();

    var previewType = data.previewType || 'unknown';
    var preview = data.preview;
    FileViewPanel._currentPreviewType = previewType;

    if (previewType === 'image' && preview) {
      var imgContainer = $('<div>').addClass('file-view-image-container').appendTo(container);
      var img = $('<img>')
        .attr('src', preview)
        .addClass('file-view-preview-image')
        .appendTo(imgContainer);

      container.data('previewImage', img);
      FileViewPanel._initImageDrag(imgContainer);

    } else if (previewType === 'pdf') {
      var pdfContainer = $('<div>').addClass('file-view-pdf-container').appendTo(container);
      // 点击抽取要素单元格时按候选页直达：page 为该 PDF 文件内部页号
      var pdfPage = FileViewPanel._currentPage > 0 ? FileViewPanel._currentPage : 1;
      var pdfUrl = '/command/records-assets/file?root=' + encodeURIComponent(file.rootPath) +
                   '&path=' + encodeURIComponent(file.name) + '#navpanes=0&toolbar=1&page=' + pdfPage;
      var pdfIframe = $('<iframe>')
        .attr('src', pdfUrl)
        .addClass('file-view-pdf-iframe')
        .appendTo(pdfContainer);
      // 焦点交给内嵌 PDF 查看器，滚轮 / 滚动条 / 方向键等原生翻页操作才可用
      pdfIframe[0].focus();

    } else if (previewType === 'text' && preview) {
      var textContainer = $('<div>').addClass('file-view-text-container').appendTo(container);
      $('<pre>')
        .addClass('file-view-text-preview')
        .text(preview)
        .appendTo(textContainer);

    } else {
      container.html(
        '<div class="file-view-no-preview">' +
        '<div class="file-view-no-preview-icon">📄</div>' +
        '<div>' + ($.i18n('data-quality-extension/file-view-no-preview') || '当前文件无法预览') + '</div>' +
        '</div>'
      );
    }
  };

  FileViewPanel._renderFooter = function(footer, data, file) {
    footer.empty();

    var previewType = data.previewType || 'unknown';

    var info = $('<div>').addClass('file-view-file-info').appendTo(footer);

    if (data.size) {
      $('<span>').text(FileViewPanel._formatFileSize(data.size)).appendTo(info);
    }

    if (FileViewPanel._currentFiles.length > 1) {
      $('<span>').addClass('file-view-file-counter')
        .text((FileViewPanel._currentFileIndex + 1) + ' / ' + FileViewPanel._currentFiles.length)
        .appendTo(info);
    }

    // 抽取要素的候选页：点击页签可直接跳到该取值出现的那一页
    if (FileViewPanel._pageCandidates.length > 0) {
      var pages = $('<div>').addClass('file-view-page-chips').appendTo(footer);
      $('<span>').addClass('file-view-page-label')
        .text($.i18n('data-quality-extension/file-view-page-label') || '取值所在页：')
        .appendTo(pages);
      FileViewPanel._pageCandidates.forEach(function(candidate) {
        var page = parseInt(candidate.p, 10);
        if (!(page > 0)) return;
        var chip = $('<button>').addClass('button file-view-page-chip').text('第 ' + page + ' 页');
        if (page === FileViewPanel._currentPage) chip.addClass('active');
        if (candidate.v) chip.attr('title', candidate.v);
        chip.on('click', function() {
          FileViewPanel._jumpToPage(page);
        });
        pages.append(chip);
      });
    }

    var actions = $('<div>').addClass('file-view-actions').appendTo(footer);

    if (previewType === 'image') {
      var zoomControls = $('<div>').addClass('file-view-zoom-controls').appendTo(actions);

      $('<button>').addClass('button file-view-zoom-btn').text('−').on('click', function() {
        FileViewPanel._zoomLevel = Math.max(0.1, FileViewPanel._zoomLevel - 0.2);
        FileViewPanel._applyZoom();
      }).appendTo(zoomControls);

      $('<button>').addClass('button file-view-zoom-btn').text('100%').on('click', function() {
        FileViewPanel._zoomLevel = 1;
        FileViewPanel._currentOffsetX = 0;
        FileViewPanel._currentOffsetY = 0;
        FileViewPanel._applyZoom();
      }).appendTo(zoomControls);

      $('<button>').addClass('button file-view-zoom-btn').text('+').on('click', function() {
        FileViewPanel._zoomLevel = Math.min(5, FileViewPanel._zoomLevel + 0.2);
        FileViewPanel._applyZoom();
      }).appendTo(zoomControls);
    }

    $('<button>')
      .addClass('button')
      .text($.i18n('data-quality-extension/file-view-download') || 'Download')
      .on('click', function() {
        window.open('/command/records-assets/file?root=' + encodeURIComponent(file.rootPath) +
                    '&path=' + encodeURIComponent(file.name) + '&download=true', '_blank');
      })
      .appendTo(actions);
  };

  /**
   * 跳到候选页：PDF 重新加载 iframe 到该页；整目录成件的图片则选中该页对应的文件。
   */
  FileViewPanel._jumpToPage = function(page) {
    if (!(page > 0)) return;
    FileViewPanel._currentPage = page;
    if (FileViewPanel._currentPreviewType === 'pdf') {
      FileViewPanel._loadCurrentFile();
      return;
    }
    var targetName = null;
    for (var i = 0; i < FileViewPanel._pageCandidates.length; i++) {
      if (FileViewPanel._pageCandidates[i].p === page && FileViewPanel._pageCandidates[i].f) {
        targetName = FileViewPanel._pageCandidates[i].f;
        break;
      }
    }
    var index = targetName ? FileViewPanel._indexOfFile(FileViewPanel._currentFiles, targetName) : -1;
    if (index < 0) {
      index = page - 1;
    }
    if (index >= 0 && index < FileViewPanel._currentFiles.length) {
      FileViewPanel._currentFileIndex = index;
      FileViewPanel._loadCurrentFile();
      FileViewPanel._updateThumbnailSelection();
      FileViewPanel._scrollToThumbnail();
    }
  };

  FileViewPanel._applyZoom = function() {
    if (!FileViewPanel._panel) return;
    var img = FileViewPanel._panel.find('.file-view-preview-image');
    if (img.length) {
      img.css('transform', 'translate(' + FileViewPanel._currentOffsetX + 'px, ' +
              FileViewPanel._currentOffsetY + 'px) scale(' + FileViewPanel._zoomLevel + ')');
      img.css('transform-origin', 'top left');
    }
  };

  FileViewPanel._initImageDrag = function(imgContainer) {
    var img = imgContainer.find('.file-view-preview-image');
    var ns = FileViewPanel._dragNamespace;

    img.on('mousedown' + ns, function(e) {
      FileViewPanel._isDraggingImage = true;
      FileViewPanel._dragStartX = e.clientX;
      FileViewPanel._dragStartY = e.clientY;
      img.addClass('dragging');
      e.preventDefault();
    });

    $(document).on('mousemove' + ns, function(e) {
      if (!FileViewPanel._isDraggingImage) return;

      var deltaX = e.clientX - FileViewPanel._dragStartX;
      var deltaY = e.clientY - FileViewPanel._dragStartY;

      FileViewPanel._currentOffsetX += deltaX;
      FileViewPanel._currentOffsetY += deltaY;

      FileViewPanel._dragStartX = e.clientX;
      FileViewPanel._dragStartY = e.clientY;

      FileViewPanel._applyZoom();
      e.preventDefault();
    });

    $(document).on('mouseup' + ns, function() {
      if (FileViewPanel._isDraggingImage) {
        FileViewPanel._isDraggingImage = false;
        img.removeClass('dragging');
      }
    });

    imgContainer.on('wheel' + ns, function(e) {
      var originalEvent = e.originalEvent;
      // 文件列表可见时普通滚轮不缩放，交由预览区转发去滚动文件列表；
      // 需要缩放图像时按住 Ctrl/Alt + 滚轮
      if (FileViewPanel._isThumbListVisible() && !originalEvent.ctrlKey && !originalEvent.altKey) {
        return;
      }
      e.preventDefault();
      var delta = originalEvent.deltaY;
      var zoomStep = delta > 0 ? -0.1 : 0.1;
      FileViewPanel._zoomLevel = Math.max(0.1, Math.min(5, FileViewPanel._zoomLevel + zoomStep));
      FileViewPanel._applyZoom();
    });

    imgContainer.on('remove', function() {
      $(document).off(ns);
      img.off(ns);
    });
  };

  FileViewPanel._handleKeyDown = function(e) {
    if (!FileViewPanel._isVisible) return;

    if (e.keyCode === 37) {
      e.preventDefault();
      FileViewPanel._navigatePrev();
    } else if (e.keyCode === 39) {
      e.preventDefault();
      FileViewPanel._navigateNext();
    } else if (e.keyCode === 27) {
      e.preventDefault();
      FileViewPanel.hide();
    }
  };

  FileViewPanel._navigatePrev = function() {
    if (FileViewPanel._currentFiles.length <= 1) return;
    if (FileViewPanel._currentFileIndex > 0) {
      FileViewPanel._currentFileIndex--;
    } else {
      FileViewPanel._currentFileIndex = FileViewPanel._currentFiles.length - 1;
    }
    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;
    FileViewPanel._loadCurrentFile();
    FileViewPanel._updateThumbnailSelection();
    FileViewPanel._scrollToThumbnail();
  };

  FileViewPanel._navigateNext = function() {
    if (FileViewPanel._currentFiles.length <= 1) return;
    if (FileViewPanel._currentFileIndex < FileViewPanel._currentFiles.length - 1) {
      FileViewPanel._currentFileIndex++;
    } else {
      FileViewPanel._currentFileIndex = 0;
    }
    FileViewPanel._zoomLevel = 1;
    FileViewPanel._currentOffsetX = 0;
    FileViewPanel._currentOffsetY = 0;
    FileViewPanel._loadCurrentFile();
    FileViewPanel._updateThumbnailSelection();
    FileViewPanel._scrollToThumbnail();
  };

  FileViewPanel._scrollToThumbnail = function() {
    var thumbList = FileViewPanel._panel.find('.file-view-thumb-list');
    var activeThumb = thumbList.find('.file-view-thumb-item.active');
    if (activeThumb.length) {
      var containerTop = thumbList.scrollTop();
      var containerHeight = thumbList.height();
      var thumbTop = activeThumb.position().top + containerTop;
      var thumbHeight = activeThumb.outerHeight();

      if (thumbTop < containerTop) {
        thumbList.scrollTop(thumbTop);
      } else if (thumbTop + thumbHeight > containerTop + containerHeight) {
        thumbList.scrollTop(thumbTop + thumbHeight - containerHeight);
      }
    }
  };

  /** 文件列表是否可见（仅一个文件时该区域隐藏，此时不接管滚轮） */
  FileViewPanel._isThumbListVisible = function() {
    return !!FileViewPanel._panel && FileViewPanel._panel.find('.file-view-thumbnails').is(':visible');
  };

  /** 面板打开后把焦点交给右侧文件列表；无列表（单文件）时退回面板本身 */
  FileViewPanel._focusThumbList = function() {
    if (!FileViewPanel._panel) return;
    if (FileViewPanel._isThumbListVisible()) {
      FileViewPanel._panel.find('.file-view-thumb-list').focus();
    } else {
      FileViewPanel._panel.focus();
    }
  };

  /**
   * 预览区滚轮转发给文件列表：鼠标停在图像/PDF 预览上滚动时也默认浏览文件，
   * 而不是缩放图像或翻动 PDF（图像缩放改为 Ctrl/Alt + 滚轮，见 _initImageDrag）。
   */
  FileViewPanel._bindPreviewWheelForwarding = function(content) {
    content.off('wheel' + FileViewPanel._dragNamespace).on('wheel' + FileViewPanel._dragNamespace, function(e) {
      if (!FileViewPanel._isThumbListVisible()) return;
      // 文本预览自身可滚动，保留其滚动行为
      if ($(e.target).closest('.file-view-text-container').length > 0) return;
      var thumbList = FileViewPanel._panel.find('.file-view-thumb-list');
      thumbList.scrollTop(thumbList.scrollTop() + e.originalEvent.deltaY);
      e.preventDefault();
    });
  };

  FileViewPanel._adjustRightPanel = function() {
    var panelWidth = FileViewPanel.PANEL_WIDTH;

    $('#view-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');
    $('#tool-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');
    $('#quality-rules-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');
    $('#quality-results-panel').css('width', 'calc(100% - ' + panelWidth + 'px)');

    FileViewPanel._panel.css({
      position: 'absolute',
      top: 0,
      right: 0,
      width: panelWidth + 'px',
      height: '100%'
    });
  };

  FileViewPanel._restoreRightPanel = function() {
    $('#view-panel').css('width', '');
    $('#tool-panel').css('width', '');
    $('#quality-rules-panel').css('width', '');
    $('#quality-results-panel').css('width', '');
  };

  FileViewPanel._formatFileSize = function(bytes) {
    if (bytes === 0) return '0 B';
    var k = 1024;
    var sizes = ['B', 'KB', 'MB', 'GB'];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  };

  FileViewPanel.resize = function() {
    if (!FileViewPanel._panel || !FileViewPanel._isVisible) return;
    FileViewPanel._adjustRightPanel();
  };

})();
