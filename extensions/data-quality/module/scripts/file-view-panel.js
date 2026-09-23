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
  FileViewPanel._docNamespace = '.fileViewDoc_' + Math.random().toString(36).substr(2, 9);
  /**
   * 浮窗内的当前操作作用域：'preview'（预览区）| 'list'（右侧文件列表）| null。
   * 单元格就地编辑时焦点始终留在编辑框内（点击浮窗不转移焦点），所以上下键 / 方向键
   * 只能靠这个状态而非真实焦点判断作用目标；点到浮窗之外（回到编辑框等）即清空。
   */
  FileViewPanel._activeScope = null;
  /** 多页 PDF 的总页数，由渲染结果回填；未知（0）时不限制翻页 */
  FileViewPanel._pageCount = 0;

  /** OCR 拉框识别模式：开启后预览区改为位图，可在图上拉框送后台 OCR */
  FileViewPanel._ocrMode = false;
  FileViewPanel._ocrBusy = false;
  FileViewPanel._ocrNamespace = '.fileViewOcr_' + Math.random().toString(36).substr(2, 9);

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

  /** 取该单元格在 pageMap 中的候选页列表；历史提取数据里可能残留无取值 / 无页码的空候选，先剔除 */
  FileViewPanel._getCellCandidates = function(rowIndex, cellIndex) {
    if (cellIndex < 0 || !FileViewPanel._pageMap) {
      return [];
    }
    var columns = theProject.columnModel.columns;
    var columnName = null;
    for (var i = 0; i < columns.length; i++) {
      if (columns[i].cellIndex === cellIndex) {
        columnName = columns[i].name;
        break;
      }
    }
    if (columnName === null) {
      return [];
    }
    var rowNode = FileViewPanel._pageMap[String(rowIndex)];
    if (!rowNode) {
      return [];
    }
    var list = rowNode[columnName];
    if (!list || !list.length) {
      return [];
    }
    return list.filter(function(candidate) {
      return candidate && candidate.v && parseInt(candidate.p, 10) > 0;
    });
  };

  /** 取该单元格对应的候选页列表，供 _currentPage 与页签展示使用 */
  FileViewPanel._resolveCellCandidates = function(rowIndex, cellIndex) {
    FileViewPanel._pageCandidates = [];
    FileViewPanel._currentPage = 1;
    var list = FileViewPanel._getCellCandidates(rowIndex, cellIndex);
    if (!list.length) return;
    FileViewPanel._pageCandidates = list;
    var page = parseInt(list[0].p, 10);
    FileViewPanel._currentPage = page > 0 ? page : 1;
  };

  FileViewPanel.show = function(rowIndex, cellIndex, options) {
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
    FileViewPanel._pageCount = 0;
    FileViewPanel._setActiveScope(null);
    FileViewPanel._ocrMode = !!(options && options.ocr);
    FileViewPanel._ocrBusy = false;
    FileViewPanel._clearOcrSelection();

    if (!FileViewPanel._panel) {
      FileViewPanel._createPanel();
    }

    FileViewPanel._syncOcrModeUI();

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
          FileViewPanel._focusThumbList();
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
    FileViewPanel._pageCount = 0;
    FileViewPanel._setActiveScope(null);
    FileViewPanel._ocrMode = false;
    FileViewPanel._ocrBusy = false;
    FileViewPanel._clearOcrSelection();
    FileViewPanel._syncOcrModeUI();

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

  /**
   * 双击单元格时调用：打开（或复用）资源预览浮窗并默认进入 OCR 模式。
   */
  FileViewPanel.openForOcr = function(rowIndex, cellIndex) {
    if (!FileViewPanel.canPreview()) {
      return;
    }
    cellIndex = (typeof cellIndex === 'number') ? cellIndex : -1;
    if (FileViewPanel._isVisible && FileViewPanel._currentRow === rowIndex
        && FileViewPanel._currentCellIndex === cellIndex) {
      FileViewPanel._setOcrMode(true);
      return;
    }
    FileViewPanel.show(rowIndex, cellIndex, { ocr: true });
  };

  /**
   * 就地编辑按 Tab 切到本行下一个单元格时调用：仅当目标单元格是提取要素格
   * （在 pageMap 中有取值页映射）时，才把浮窗定位到该取值所在页，并保持 OCR 模式。
   */
  FileViewPanel.followCell = function(rowIndex, cellIndex) {
    if (!FileViewPanel._isVisible) {
      return;
    }
    if (FileViewPanel._currentRow === rowIndex && FileViewPanel._currentCellIndex === cellIndex) {
      return;
    }
    if (FileViewPanel._getCellCandidates(rowIndex, cellIndex).length === 0) {
      return;
    }
    FileViewPanel.show(rowIndex, cellIndex, { ocr: true });
  };

  /** 切换 OCR 模式；开启时把预览换成位图以便拉框，关闭时恢复常规预览 */
  FileViewPanel._setOcrMode = function(on) {
    on = !!on;
    if (on === FileViewPanel._ocrMode) {
      return;
    }
    FileViewPanel._ocrMode = on;
    FileViewPanel._ocrBusy = false;
    FileViewPanel._syncOcrModeUI();
    if (FileViewPanel._isVisible) {
      FileViewPanel._loadCurrentFile();
    }
  };

  FileViewPanel._syncOcrModeUI = function() {
    if (!FileViewPanel._panel) {
      return;
    }
    FileViewPanel._panel.toggleClass('ocr-mode', !!FileViewPanel._ocrMode);
    FileViewPanel._panel.find('.file-view-ocr-btn').toggleClass('active', !!FileViewPanel._ocrMode);
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
      .addClass('button file-view-ocr-btn')
      .text($.i18n('data-quality-extension/file-view-ocr-btn') || 'OCR识别')
      .attr('title', $.i18n('data-quality-extension/file-view-ocr-btn-title') || 'OCR识别')
      .on('click', function() {
        FileViewPanel._setOcrMode(!FileViewPanel._ocrMode);
      })
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

    FileViewPanel._bindPreviewWheel(content);

    // 面板只创建一次，document 级绑定随之只做一次（用独立命名空间，避免与面板内的 _keyNamespace 相互清理）
    // 点预览区 / 点右侧文件列表：把方向键与滚轮的作用目标切到对应区域。焦点始终留在
    // 单元格编辑框内（点击浮窗不转移焦点由 data-table 侧白名单保证），故只能用作用域状态判断意图
    content.on('mousedown' + FileViewPanel._docNamespace, function() {
      FileViewPanel._setActiveScope('preview');
    });
    thumbArea.on('mousedown' + FileViewPanel._docNamespace, function() {
      FileViewPanel._setActiveScope('list');
    });

    // 按作用域接管方向键：事件从单元格编辑框冒泡上来，preventDefault 可阻止光标移动
    //  - preview：上下键翻页
    //  - list：左右键切换文件，上下键滚动文件列表
    $(document).on('keydown' + FileViewPanel._docNamespace, function(e) {
      var scope = FileViewPanel._activeScope;
      if (!FileViewPanel._isVisible || !scope) return;
      // 焦点已在浮窗内（非就地编辑场景）时由面板自身的 _handleKeyDown 处理，避免重复触发
      if ($(e.target).closest('#file-view-panel').length > 0) return;
      if (scope === 'preview') {
        if (e.keyCode === 38) {
          e.preventDefault();
          FileViewPanel._goToPage(-1);
        } else if (e.keyCode === 40) {
          e.preventDefault();
          FileViewPanel._goToPage(1);
        }
      } else if (scope === 'list') {
        if (e.keyCode === 37) {
          e.preventDefault();
          FileViewPanel._navigatePrev();
        } else if (e.keyCode === 39) {
          e.preventDefault();
          FileViewPanel._navigateNext();
        } else if (e.keyCode === 38 || e.keyCode === 40) {
          e.preventDefault();
          FileViewPanel._scrollThumbListBy(e.keyCode === 38 ? -80 : 80);
        }
      }
    });

    // 点到浮窗之外（例如回到单元格编辑框）即清空作用域，方向键还给文本光标
    $(document).on('mousedown' + FileViewPanel._docNamespace, function(e) {
      if (!FileViewPanel._isVisible || !FileViewPanel._activeScope) return;
      if ($(e.target).closest('#file-view-panel').length > 0) return;
      FileViewPanel._setActiveScope(null);
    });

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
          if (data.previewType) {
            FileViewPanel._currentPreviewType = data.previewType;
          }
          if (FileViewPanel._ocrMode) {
            // OCR 模式：位图由后端按页渲染（PDF 也能框选），用于拉框裁剪
            FileViewPanel._renderFooter(footer, { previewType: 'ocr' }, file);
            FileViewPanel._loadOcrBitmap();
          } else {
            FileViewPanel._renderFileContent(content, data, file);
            FileViewPanel._renderFooter(footer, data, file);
          }
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
    // 预览接口若给出总页数（PDF），一并记录，供翻页判断上界；不返回则保持原值
    if (data.pageCount) {
      FileViewPanel._pageCount = parseInt(data.pageCount, 10) || 0;
    }

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

  FileViewPanel._clearOcrSelection = function() {
    $(document).off(FileViewPanel._ocrNamespace);
  };

  /** 取当前文件/当前页的位图，作为拉框裁剪的底图（PDF 由后端 PDFBox 渲染） */
  FileViewPanel._loadOcrBitmap = function() {
    if (!FileViewPanel._panel || FileViewPanel._currentFiles.length === 0) {
      return;
    }
    var file = FileViewPanel._currentFiles[FileViewPanel._currentFileIndex];
    var content = FileViewPanel._panel.find('.file-view-content');
    var page = FileViewPanel._currentPage > 0 ? FileViewPanel._currentPage : 1;

    FileViewPanel._clearOcrSelection();
    content.html('<div class="file-view-loading">' + ($.i18n('data-quality-extension/file-view-loading') || 'Loading...') + '</div>');

    $.ajax({
      url: '/command/data-quality/render-file-page',
      type: 'GET',
      data: { root: file.rootPath, path: file.name, page: page },
      dataType: 'json',
      success: function(data) {
        if (data && data.status === 'ok' && data.preview) {
          FileViewPanel._renderOcrStage(content, data);
        } else {
          content.html('<div class="file-view-error">' +
            ((data && data.message) || $.i18n('data-quality-extension/file-view-error') || 'Error') + '</div>');
        }
      },
      error: function() {
        content.html('<div class="file-view-error">' + ($.i18n('data-quality-extension/file-view-error') || 'Error') + '</div>');
      }
    });
  };

  FileViewPanel._renderOcrStage = function(container, data) {
    container.empty();

    // 记录总页数，供上下键 / 滚轮翻页判断上界
    FileViewPanel._pageCount = parseInt(data.pageCount, 10) || 0;

    var stage = $('<div>').addClass('file-view-ocr-stage').appendTo(container);
    var img = $('<img>')
      .addClass('file-view-ocr-bitmap')
      .attr('src', data.preview)
      .appendTo(stage);
    var sel = $('<div>').addClass('file-view-ocr-selection').hide().appendTo(stage);
    stage.data('naturalWidth', parseInt(data.width, 10) || 0);

    $('<div>').addClass('file-view-ocr-hint')
      .text($.i18n('data-quality-extension/file-view-ocr-hint') || '在图上按住鼠标左键拉框，松开后自动识别')
      .appendTo(stage);

    FileViewPanel._bindOcrSelection(stage, img, sel);
  };

  FileViewPanel._bindOcrSelection = function(stage, img, sel) {
    var ns = FileViewPanel._ocrNamespace;
    var imgEl = img[0];
    var dragging = false;
    var startX = 0;
    var startY = 0;
    var startDrawX = 0;
    var startDrawY = 0;

    // 鼠标位置换算为「原图像素坐标」与「相对 stage 的绘制坐标」
    var locate = function(e) {
      var imgRect = imgEl.getBoundingClientRect();
      var stageRect = stage[0].getBoundingClientRect();
      var x = Math.max(0, Math.min(e.clientX - imgRect.left, imgRect.width));
      var y = Math.max(0, Math.min(e.clientY - imgRect.top, imgRect.height));
      return {
        x: x,
        y: y,
        drawX: imgRect.left - stageRect.left + x,
        drawY: imgRect.top - stageRect.top + y
      };
    };

    $(document).off(ns);

    img.on('mousedown' + ns, function(e) {
      if (e.which !== 1 || FileViewPanel._ocrBusy) {
        return;
      }
      e.preventDefault();
      var p = locate(e);
      dragging = true;
      startX = p.x;
      startY = p.y;
      startDrawX = p.drawX;
      startDrawY = p.drawY;
      sel.show().css({ left: startDrawX + 'px', top: startDrawY + 'px', width: '0px', height: '0px' });
    });

    $(document).on('mousemove' + ns, function(e) {
      if (!dragging) {
        return;
      }
      e.preventDefault();
      var p = locate(e);
      sel.css({
        left: Math.min(startDrawX, p.drawX) + 'px',
        top: Math.min(startDrawY, p.drawY) + 'px',
        width: Math.abs(p.drawX - startDrawX) + 'px',
        height: Math.abs(p.drawY - startDrawY) + 'px'
      });
    });

    $(document).on('mouseup' + ns, function(e) {
      if (!dragging) {
        return;
      }
      dragging = false;
      var p = locate(e);
      var imgWidth = imgEl.getBoundingClientRect().width;
      var naturalWidth = stage.data('naturalWidth') || imgEl.naturalWidth || imgWidth;
      var scale = imgWidth > 0 ? (naturalWidth / imgWidth) : 1;

      var x = Math.round(Math.min(startX, p.x) * scale);
      var y = Math.round(Math.min(startY, p.y) * scale);
      var width = Math.round(Math.abs(p.x - startX) * scale);
      var height = Math.round(Math.abs(p.y - startY) * scale);

      if (width < 4 || height < 4) {
        sel.hide();
        return;
      }
      FileViewPanel._submitOcrCrop(x, y, width, height, sel);
    });

    stage.on('remove', function() {
      $(document).off(ns);
    });
  };

  FileViewPanel._submitOcrCrop = function(x, y, width, height, sel) {
    if (FileViewPanel._currentFiles.length === 0) {
      return;
    }
    var file = FileViewPanel._currentFiles[FileViewPanel._currentFileIndex];
    var page = FileViewPanel._currentPage > 0 ? FileViewPanel._currentPage : 1;
    var stage = FileViewPanel._panel.find('.file-view-ocr-stage');

    FileViewPanel._ocrBusy = true;
    stage.addClass('busy');

    Refine.wrapCSRF(function(token) {
      $.ajax({
        url: '/command/data-quality/ocr-crop',
        type: 'POST',
        data: {
          root: file.rootPath,
          path: file.name,
          page: page,
          x: x,
          y: y,
          width: width,
          height: height,
          mode: 'auto',
          csrf_token: token
        },
        dataType: 'json',
        success: function(data) {
          FileViewPanel._ocrBusy = false;
          stage.removeClass('busy');
          if (data && data.status === 'ok') {
            sel.hide();
            var text = (data.text === null || data.text === undefined) ? '' : String(data.text);
            if (!text) {
              // 空结果不能走写回：就地编辑框默认全选，插入空串会把单元格清空
              var emptyMsg = $.i18n('data-quality-extension/file-view-ocr-empty');
              if (!emptyMsg || emptyMsg.indexOf('data-quality-extension/') === 0) {
                emptyMsg = '未识别到文字，请调整框选范围或改选纯文字模式后重试。';
              }
              alert(emptyMsg);
              return;
            }
            FileViewPanel._applyOcrResult(text);
          } else {
            alert((data && data.message) || ($.i18n('data-quality-extension/file-view-ocr-failed') || 'OCR 识别失败'));
          }
        },
        error: function() {
          FileViewPanel._ocrBusy = false;
          stage.removeClass('busy');
          alert($.i18n('data-quality-extension/file-view-ocr-failed') || 'OCR 识别失败');
        }
      });
    });
  };

  /**
   * 写回识别结果：有就地编辑框则替换选区 / 插入光标后；
   * 否则弹提示并把结果放到剪贴板，供用户自行粘贴。
   */
  FileViewPanel._applyOcrResult = function(text) {
    text = (text === null || text === undefined) ? '' : String(text);

    var editor = (typeof DataTableCellUI !== 'undefined') ? DataTableCellUI.activeInlineEditor : null;
    if (editor && typeof editor.insertText === 'function') {
      editor.insertText(text);
      return;
    }

    FileViewPanel._copyTextToClipboard(text);
    var message = $.i18n('data-quality-extension/file-view-ocr-result-copied');
    if (!message || message.indexOf('data-quality-extension/') === 0) {
      message = 'OCR结果：$1。结果已拷贝供粘贴。';
    }
    alert(message.replace('$1', text));
  };

  FileViewPanel._copyTextToClipboard = function(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
        return true;
      }
    } catch (e) {
      // 非安全上下文不支持 Clipboard API，回退到 execCommand
    }
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      return true;
    } catch (e) {
      return false;
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

  /** 切换浮窗内的操作作用域（'preview' / 'list' / null），并给对应区域描边反馈 */
  FileViewPanel._setActiveScope = function(scope) {
    FileViewPanel._activeScope = scope || null;
    if (FileViewPanel._panel) {
      FileViewPanel._panel
        .toggleClass('preview-active', FileViewPanel._activeScope === 'preview')
        .toggleClass('list-active', FileViewPanel._activeScope === 'list');
    }
  };

  /** 滚动右侧文件列表：预览区滚轮转发与「列表作用域」下的上下键共用同一入口 */
  FileViewPanel._scrollThumbListBy = function(delta) {
    var thumbList = FileViewPanel._panel.find('.file-view-thumb-list');
    thumbList.scrollTop(thumbList.scrollTop() + delta);
  };

  /**
   * 预览区翻页：delta 为 -1（上一页）或 +1（下一页）。
   * PDF 按文件内页号，整目录成件的图片按目录内文件序号，两者都复用 _jumpToPage。
   */
  FileViewPanel._goToPage = function(delta) {
    var page = (FileViewPanel._currentPage > 0 ? FileViewPanel._currentPage : 1) + delta;
    if (page < 1) {
      return;
    }
    if (FileViewPanel._currentPreviewType === 'pdf') {
      if (FileViewPanel._pageCount > 0 && page > FileViewPanel._pageCount) {
        return;
      }
    } else if (FileViewPanel._currentFiles.length > 0 && page > FileViewPanel._currentFiles.length) {
      return;
    }
    FileViewPanel._jumpToPage(page);
  };

  FileViewPanel._applyZoom = function() {
    if (!FileViewPanel._panel) return;
    // 普通图片预览与 OCR 位图共用同一套缩放 / 平移参数
    var img = FileViewPanel._panel.find('.file-view-preview-image, .file-view-ocr-bitmap');
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

    // 滚轮缩放统一由预览区 _bindPreviewWheel 处理（Ctrl/Alt + 滚轮），此处只管拖拽平移
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
    // 单元格就地编辑进行中时不抢焦点，否则 Tab 连跳与 OCR 写回会被打断
    if (typeof DataTableCellUI !== 'undefined' && DataTableCellUI.activeInlineEditor) return;
    if (FileViewPanel._isThumbListVisible()) {
      FileViewPanel._panel.find('.file-view-thumb-list').focus();
    } else {
      FileViewPanel._panel.focus();
    }
  };

  /**
   * 预览区滚轮：
   *  - Ctrl/Alt + 滚轮：缩放当前预览图片（必须 preventDefault，否则浏览器会整页缩放）
   *  - 非预览作用域：维持原有行为，鼠标停在预览上滚动即浏览右侧文件列表
   *  - 预览作用域：优先在页面内滚动（放大态平移图像），滚到边界或无法滚动时翻页
   */
  FileViewPanel._bindPreviewWheel = function(content) {
    content.off('wheel' + FileViewPanel._dragNamespace).on('wheel' + FileViewPanel._dragNamespace, function(e) {
      var originalEvent = e.originalEvent;
      // 文本预览自身可滚动，保留其滚动行为
      if ($(e.target).closest('.file-view-text-container').length > 0) return;

      e.preventDefault();
      if (originalEvent.ctrlKey || originalEvent.altKey) {
        FileViewPanel._zoomPreview(originalEvent.deltaY > 0 ? -0.1 : 0.1);
        return;
      }

      if (FileViewPanel._activeScope !== 'preview') {
        if (FileViewPanel._isThumbListVisible()) {
          FileViewPanel._scrollThumbListBy(originalEvent.deltaY);
        }
        return;
      }

      if (FileViewPanel._scrollPreviewBy(originalEvent.deltaY)) {
        return;
      }
      FileViewPanel._goToPage(originalEvent.deltaY > 0 ? 1 : -1);
    });
  };

  /** Ctrl/Alt + 滚轮：按步进缩放当前预览图片（0.1 ~ 5 倍），OCR 位图同样适用 */
  FileViewPanel._zoomPreview = function(step) {
    FileViewPanel._zoomLevel = Math.max(0.1, Math.min(5, FileViewPanel._zoomLevel + step));
    FileViewPanel._applyZoom();
  };

  /**
   * 在预览区内滚动内容（放大后的图像上下平移）。
   * 返回 true 表示本次滚轮已被内容滚动消费；false 表示已到边界或无法滚动，应转交翻页。
   */
  FileViewPanel._scrollPreviewBy = function(deltaY) {
    if (FileViewPanel._zoomLevel <= 1) {
      return false;
    }
    // 普通图片预览与 OCR 舞台都可滚动，两者都是 overflow:hidden 的居中容器
    var container = FileViewPanel._panel.find('.file-view-image-container, .file-view-ocr-stage').first();
    var img = container.find('.file-view-preview-image, .file-view-ocr-bitmap').first();
    if (container.length === 0 || img.length === 0) {
      return false;
    }
    var viewHeight = container.height();
    // transform 不参与布局，所以放大后的实际高度要拿布局高度乘缩放比
    var scaledHeight = img.outerHeight() * FileViewPanel._zoomLevel;
    if (scaledHeight <= viewHeight) {
      return false;
    }
    // 图片在容器内未必贴顶（flex 居中），偏移边界以图片自身位置为基准
    var imgTop = img[0].offsetTop;
    // 向上滚看更靠上的内容（offsetY 增大），向下滚看更靠下的内容（offsetY 减小）
    var minOffsetY = viewHeight - imgTop - scaledHeight;
    var maxOffsetY = -imgTop;
    var clamped = Math.max(minOffsetY, Math.min(maxOffsetY, FileViewPanel._currentOffsetY - deltaY));
    if (Math.abs(clamped - FileViewPanel._currentOffsetY) < 0.5) {
      return false;   // 已在边界，交给翻页
    }
    FileViewPanel._currentOffsetY = clamped;
    FileViewPanel._applyZoom();
    return true;
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
