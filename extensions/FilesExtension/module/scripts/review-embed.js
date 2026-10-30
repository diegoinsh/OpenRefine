/**
 * 数据校审 - 嵌入形态入口（供业务系统 iframe 直接嵌入）
 *
 * 与合规审查的规则页同口径，用 URL 参数打开单个 UI 模块：
 *   - 编辑校审参数：/project?project=<id>&view=review-params
 *   - 校审结果预览：/project?project=<id>&view=review-preview&seq=<序号>
 *
 * 不带 view 参数时本脚本不做任何事，项目页行为与原来完全一致。
 */
(function() {

  function param(name) {
    try {
      return new URLSearchParams(window.location.search).get(name);
    } catch (e) {
      return null;
    }
  }

  var view = param('view');
  if (view !== 'review-params' && view !== 'review-preview') {
    return;
  }

  // 标记打在 <html> 上（project.vt 的 <head> 脚本已提前加过，这里兜底）
  document.documentElement.classList.add('ruyi-embed-review');

  function projectRows() {
    return (typeof theProject !== 'undefined' && theProject.rowModel && theProject.rowModel.rows) || null;
  }

  function findRowIndexBySeq(seq) {
    var target = String(seq);
    var rows = projectRows() || [];
    var seqColumnIndex = -1;
    var columns = (theProject.columnModel && theProject.columnModel.columns) || [];
    for (var i = 0; i < columns.length; i++) {
      if (columns[i].name === '序号') {
        seqColumnIndex = i;
        break;
      }
    }
    if (seqColumnIndex >= 0) {
      for (var r = 0; r < rows.length; r++) {
        var cell = rows[r].cells[seqColumnIndex];
        if (cell && String(cell.v) === target) {
          return rows[r].i;
        }
      }
    }
    // 序号列缺失时退回「序号从 1 起」的行号口径
    var n = parseInt(target, 10);
    return (n > 0) ? n - 1 : 0;
  }

  /** 校审结果预览：项目 + 序号定位到该行的原件，并渲染检出/缺失标签 */
  function openPreview(seq, attempts) {
    attempts = attempts || 0;
    if (attempts > 150) {
      console.warn('[data-review] 预览入口等待项目数据超时');
      return;
    }
    var rows = projectRows();
    var rowsReady = !!(rows && rows.length > 0);
    var panelReady = (typeof FileViewPanel !== 'undefined' && typeof QualityAlignment !== 'undefined');
    if (!rowsReady || !panelReady) {
      setTimeout(function() {
        openPreview(seq, attempts + 1);
      }, 200);
      return;
    }
    var rowIndex = findRowIndexBySeq(seq);
    if (rowIndex < 0 || rowIndex >= rows.length) {
      rowIndex = rows[0].i;
    }
    FileViewPanel.show(rowIndex, -1);
  }

  $(function() {
    if (view === 'review-params') {
      if (typeof ReviewProjectPanel !== 'undefined') {
        ReviewProjectPanel.open();
      }
    } else {
      openPreview(param('seq'));
    }
  });
})();
