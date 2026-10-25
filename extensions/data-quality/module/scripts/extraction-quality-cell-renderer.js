/**
 * 抽取质量单元格角标渲染器（可休眠）。
 *
 * 数据来源：项目 metadata 的 pageMap（批量提取时 Java 写入）。每条候选形如
 * {v: 取值, c: 置信度, p: 页码, f: 文件名, q: 质量分, qf: [原因标记], w: 1}。
 *
 * 关键规则（见 docs/extraction-quality-consumption-design.md §5.3 / §6.5）：
 * - 只认 `w === 1` 的候选，**不做文本比对来寻找候选**。单元格值会被日期归一、
 *   当事人规范化、身份证明清责任者、证件清日期、题名由卷级模型合成等改写，
 *   逐字比对必然失配，会把别的页的质量标到当前值上。
 * - `candidate.v === 当前单元格文本` 只作为「改值失效」校验：用户改了值，角标自然消失。
 * - 无 `w` / 无 `q` / 旧项目 / 未开启质量校验器：静默跳过，不报错、不打日志。
 * - 题名列角标覆盖率天然偏低（件名常由卷级分件模型命名），这是预期行为。
 */
console.log('[ExtractionQuality] extraction-quality-cell-renderer.js 脚本加载');

class ExtractionQualityCellRenderer {

  /** 前端总开关：置 false 即整体回退，不影响任何数据 */
  static ENABLED = true;

  /** 正常/可疑分界：与 AIMP quality.extraction_validator.thresholds.ok 保持一致（待 §10.1 标定） */
  static SUSPECT_THRESHOLD = 0.75;

  /**
   * 按字段展示的白名单（pageMap 的列名，如 '题名'）；null = 不做字段限制；[] = 全部关闭。
   *
   * 当前为空（全部关闭）：§10.1 区分度 gate 实测结论（run 20261025-or-qc10，97 个对齐件对）——
   *   题名   覆盖 41% 达标，但无提升（任何阈值最多标出 4 条且全对）；
   *   责任者 最优阈值 0.90 提升 0.91（< 2），低分档错误率无单调性；
   *   文号   39 条全 1.000，标出 0 条：误标率 0% 但召回 0，实际不提示；
   *   成文日期 最优召回 12%（< 30%）。
   * 依 §10.1.4，不通过字段默认关闭展示；评分与 flags 仍照常落 pageMap，供后续校准训练取用。
   */
  static FIELDS = [];

  /** tooltip 最多展示的 flags 条数，余下显示「等 N 项」 */
  static MAX_FLAGS = 3;

  render(rowIndex, cellIndex, cell, cellUI) {
    if (!ExtractionQualityCellRenderer.ENABLED) return undefined;
    if (typeof FileViewPanel === 'undefined' || !FileViewPanel._pageMap) {
      // 首屏渲染早于 pageMap 拉取：只触发一次请求，加载完成后重绘一次单元格
      ExtractionQualityCellRenderer._requestPageMap();
      ExtractionQualityCellRenderer._clearMarker(cellUI);
      return undefined;
    }

    var column = typeof Refine !== 'undefined' ? Refine.cellIndexToColumn(cellIndex) : null;
    if (!column || !ExtractionQualityCellRenderer._isFieldEnabled(column.name)) return undefined;
    var rowNode = FileViewPanel._pageMap[FileViewPanel._pageMapKey(rowIndex)];
    if (!rowNode) {
      ExtractionQualityCellRenderer._clearMarker(cellUI);
      return undefined;
    }

    var winner = ExtractionQualityCellRenderer._findWinner(rowNode, column.name);
    if (!winner || !ExtractionQualityCellRenderer._matchesCellValue(winner, cell)) {
      ExtractionQualityCellRenderer._clearMarker(cellUI);
      return undefined;
    }
    if (!(ExtractionQualityCellRenderer._qualityOf(winner) < ExtractionQualityCellRenderer.SUSPECT_THRESHOLD)) {
      ExtractionQualityCellRenderer._clearMarker(cellUI);
      return undefined;
    }

    if (cellUI && cellUI._td) {
      var target = cellUI._td;
      setTimeout(function() {
        ExtractionQualityCellRenderer._addMarker(target, rowIndex, cellIndex, winner);
      }, 0);
    }
    return undefined;
  }

  /** 该列是否展示（白名单为 null 时不限制） */
  static _isFieldEnabled(elementKey) {
    var fields = ExtractionQualityCellRenderer.FIELDS;
    if (!fields) return true;
    return fields.indexOf(elementKey) !== -1;
  }

  /** 找到该列唯一带 `w` 的候选；无则返回 null */
  static _findWinner(rowNode, columnName) {
    var list = rowNode[columnName];
    if (!list || !list.length) return null;
    for (var i = 0; i < list.length; i++) {
      var candidate = list[i];
      if (candidate && candidate.w === 1) return candidate;
    }
    return null;
  }

  /** 质量分：只接受数值；缺失视为「无可展示质量」 */
  static _qualityOf(candidate) {
    var q = candidate && candidate.q;
    return (typeof q === 'number' && isFinite(q)) ? q : null;
  }

  /**
   * 改值失效校验：候选值与当前单元格文本必须一致。
   * 注意这只用于「排除已被用户改写的值」，不用于寻找候选（寻找只认 `w`）。
   */
  static _matchesCellValue(candidate, cell) {
    if (!candidate || candidate.v === undefined || candidate.v === null) return false;
    if (ExtractionQualityCellRenderer._qualityOf(candidate) === null) return false;
    // 单元格对象形如 {v: 取值, r: 对账信息}，不是 {value: ...}
    var raw = (cell && cell.v !== undefined && cell.v !== null) ? cell.v
            : (cell && cell.value !== undefined && cell.value !== null ? cell.value : null);
    if (raw === null) return false;
    var text = String(raw);
    return text !== '' && text === String(candidate.v);
  }

  /**
   * pageMap 缺失时补拉；同一项目只保留一个在途请求，拿到数据后重绘一次。
   *
   * 不能用「拉过一次就不再拉」的一次性标志：抽取过程中每次刷新数据都会调用
   * FileViewPanel.invalidatePageMap() 把 pageMap 置空再重绘表格，若只拉一次，
   * 角标会在首次刷新后永久消失。改为「内存里有本项目的 pageMap 就不拉，否则补拉」。
   */
  static _requestPageMap() {
    if (typeof FileViewPanel === 'undefined' || !FileViewPanel._ensurePageMap) return;
    var projectId = (typeof theProject !== 'undefined' && theProject) ? theProject.id : null;
    if (ExtractionQualityCellRenderer._hasPageMap(projectId)) return;
    if (ExtractionQualityCellRenderer._requestingProjectId === projectId) return;

    ExtractionQualityCellRenderer._requestingProjectId = projectId;
    var token = {};
    ExtractionQualityCellRenderer._requestToken = token;
    FileViewPanel._ensurePageMap(function() {
      // 项目已切换或又发起了新请求：丢弃这次回调
      if (ExtractionQualityCellRenderer._requestToken !== token) return;
      ExtractionQualityCellRenderer._requestingProjectId = null;
      // 请求失败（空映射）不记为已加载，下次渲染会再试
      if (!ExtractionQualityCellRenderer._hasPageMap(projectId)) return;
      ExtractionQualityCellRenderer._requestedProjectId = projectId;
      // 就地补挂角标，不再整表重绘：Refine.update({cellsChanged:true}) 会再发一次
      // get-rows 并重建整张表，打开项目与每次实时刷新都会因此多出一轮重绘和列宽重算
      // （表现为表格闪一下、列宽/行高跳动）。表格尚未渲染完时再回退整表重绘。
      if (!ExtractionQualityCellRenderer._refreshMarkers() && typeof Refine !== 'undefined') {
        Refine.update({ cellsChanged: true });
      }
    });
  }

  /**
   * pageMap 到达后就地重渲染已显示的单元格（不重拉行数据、不重建表头与列宽）。
   * 走 DataTableCellUI._render 而不是直接调本渲染器：这样仍按 CellRendererRegistry 的优先级
   * 执行，避免绕开「文件路径链接」等优先级更高的渲染器、在它们专属的列上多挂角标。
   * 返回是否有可刷新的单元格（false 表示首屏还没渲染完，由调用方回退整表重绘）。
   */
  static _refreshMarkers() {
    if (!ExtractionQualityCellRenderer.ENABLED) return false;
    var cells = document.querySelectorAll('.data-table-container tbody td');
    var handled = 0;
    for (var i = 0; i < cells.length; i++) {
      var ui = cells[i].dataTableCellUI;
      if (!ui || typeof ui._render !== 'function') continue;
      // 正在就地编辑的单元格不重建，避免打断输入
      if (cells[i].querySelector('.data-table-cell-inline-editor')) continue;
      handled++;
      ui._render();
    }
    return handled > 0;
  }

  /** 内存里是否已有本项目、非空的 pageMap（空对象不能视为已加载，否则失败后永不重试） */
  static _hasPageMap(projectId) {
    if (typeof FileViewPanel === 'undefined' || !FileViewPanel._pageMap) return false;
    var owner = ExtractionQualityCellRenderer._requestedProjectId;
    // 文件视图面板自己加载的 pageMap 也算数，避免重复拉取与多余重绘
    if (owner !== projectId && FileViewPanel._pageMapProjectId !== projectId) return false;
    return Object.keys(FileViewPanel._pageMap).length > 0;
  }

  /** 单元格重绘时清除旧角标（值被改写的单元格由调用方在此路径上返回） */
  static _clearMarker(cellUI) {
    if (!cellUI || !cellUI._td) return;
    var existing = cellUI._td.querySelector('.extraction-quality-marker');
    if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
  }

  static _addMarker(td, rowIndex, cellIndex, candidate) {
    if (!td || !td.parentNode) return;
    if (td.querySelector('.extraction-quality-marker')) return;

    var marker = document.createElement('span');
    marker.className = 'extraction-quality-marker suspect';
    marker.setAttribute('tabindex', '0');
    marker.setAttribute('role', 'button');
    var label = $.i18n('data-quality-extension/extraction-quality-suspect');
    marker.setAttribute('aria-label', label);
    // 不设 title：原生 tooltip 会与自定义浮层叠在一起盖住浮层文字

    $(td).css('position', 'relative');
    $(marker).on('mouseenter', function(e) {
      ExtractionQualityCellRenderer._showTooltip(e, rowIndex, cellIndex, candidate);
    }).on('mouseleave', function() {
      ExtractionQualityCellRenderer._scheduleHideTooltip();
    }).on('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      ExtractionQualityCellRenderer._openSourcePage(rowIndex, cellIndex);
    }).on('focus', function(e) {
      ExtractionQualityCellRenderer._showTooltip(e, rowIndex, cellIndex, candidate);
    }).on('blur', function() {
      ExtractionQualityCellRenderer._scheduleHideTooltip();
    });

    td.appendChild(marker);
  }

  /** 复用现有字段点击定位：来源页与点击落页一致（由 file-view-panel 优先取 `w` 候选） */
  static _openSourcePage(rowIndex, cellIndex) {
    ExtractionQualityCellRenderer._hideTooltip();
    if (typeof FileViewPanel !== 'undefined' && typeof FileViewPanel.show === 'function') {
      FileViewPanel.show(rowIndex, cellIndex);
    }
  }

  static _showTooltip(event, rowIndex, cellIndex, candidate) {
    if (ExtractionQualityCellRenderer._hideTimeout) {
      clearTimeout(ExtractionQualityCellRenderer._hideTimeout);
      ExtractionQualityCellRenderer._hideTimeout = null;
    }
    var tooltip = $('#extraction-quality-tooltip');
    if (tooltip.length === 0) {
      tooltip = $('<div id="extraction-quality-tooltip" class="extraction-quality-tooltip"></div>');
      $('body').append(tooltip);
    }

    var q = ExtractionQualityCellRenderer._qualityOf(candidate);
    var tooltip$ = tooltip.empty();

    $('<div class="extraction-quality-tooltip-title"></div>')
        .text($.i18n('data-quality-extension/extraction-quality-suspect') + ' · ' + q.toFixed(2))
        .appendTo(tooltip$);

    var flags = candidate.qf;
    var flags$ = $('<div class="extraction-quality-tooltip-flags"></div>');
    var labels = [];
    if (flags && flags.length) {
      for (var i = 0; i < flags.length; i++) {
        var text = ExtractionQualityCellRenderer._flagLabel(flags[i]);
        // 未登记中文文案的 flag（如 ngram_low，当前权重 0、区分度不足）不展示
        if (text) labels.push(text);
      }
    }
    if (labels.length) {
      var shown = labels.slice(0, ExtractionQualityCellRenderer.MAX_FLAGS);
      for (var j = 0; j < shown.length; j++) {
        $('<div></div>').text(shown[j]).appendTo(flags$);
      }
      if (labels.length > shown.length) {
        $('<div></div>')
            .text($.i18n('data-quality-extension/extraction-quality-flags-more', labels.length - shown.length))
            .appendTo(flags$);
      }
    } else {
      $('<div></div>').text($.i18n('data-quality-extension/extraction-quality-low-confidence')).appendTo(flags$);
    }
    flags$.appendTo(tooltip$);

    var page = parseInt(candidate.p, 10);
    if (page > 0) {
      $('<div class="extraction-quality-tooltip-source"></div>')
          .text($.i18n('data-quality-extension/extraction-quality-source-page', page))
          .appendTo(tooltip$);
    }

    $('<a href="javascript:;" class="extraction-quality-tooltip-link"></a>')
        .text($.i18n('data-quality-extension/extraction-quality-view-page'))
        .appendTo(tooltip$)
        .on('click', function(e) {
          e.preventDefault();
          e.stopPropagation();
          ExtractionQualityCellRenderer._openSourcePage(rowIndex, cellIndex);
        });

    tooltip.css({
      left: event.pageX + 10,
      top: event.pageY + 10,
      display: 'block'
    }).off('mouseenter mouseleave').on('mouseenter', function() {
      if (ExtractionQualityCellRenderer._hideTimeout) {
        clearTimeout(ExtractionQualityCellRenderer._hideTimeout);
        ExtractionQualityCellRenderer._hideTimeout = null;
      }
    }).on('mouseleave', function() {
      ExtractionQualityCellRenderer._scheduleHideTooltip();
    });
  }

  /** flag 中文文案；未登记的 flag 不展示（不把英文原文给最终用户） */
  static _flagLabel(flag) {
    if (!flag) return '';
    var key = 'data-quality-extension/extraction-quality-flag.' + flag;
    var text = $.i18n(key);
    return text && text !== key ? text : '';
  }

  static _scheduleHideTooltip() {
    ExtractionQualityCellRenderer._hideTimeout = setTimeout(function() {
      $('#extraction-quality-tooltip').hide();
    }, 200);
  }

  static _hideTooltip() {
    if (ExtractionQualityCellRenderer._hideTimeout) {
      clearTimeout(ExtractionQualityCellRenderer._hideTimeout);
      ExtractionQualityCellRenderer._hideTimeout = null;
    }
    $('#extraction-quality-tooltip').hide();
  }
}

ExtractionQualityCellRenderer._hideTimeout = null;
ExtractionQualityCellRenderer._requestedProjectId = null;   // 最近一次成功加载 pageMap 的项目
ExtractionQualityCellRenderer._requestingProjectId = null;  // 在途请求所属项目
ExtractionQualityCellRenderer._requestToken = null;         // 在途请求令牌，用于丢弃过期回调

// 与数据质量检查的角标一样先于内置渲染器执行，并返回 undefined 让后续渲染器接手
if (typeof CellRendererRegistry !== 'undefined') {
  CellRendererRegistry.addRenderer('extraction-quality', new ExtractionQualityCellRenderer(), 'null');
  console.log('[ExtractionQualityCellRenderer] Renderer registered successfully');
}
