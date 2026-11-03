/**
 * 合规检查结果 - 图像预览嵌入入口（供业务系统 iframe 直接嵌入）
 *
 * 地址：/app/project?project=<项目号>&view=quality-preview[&seq=<序号>]
 *
 * 与校审的 review-preview 同口径：只公布「结果」这一个界面模块。这里公布的是合规检查
 * 结果里点击某条图像错误时弹出的浮动预览（file-preview-dialog-float），包含检出标注。
 *
 * seq 与结果表第一列「序号」一致（从 1 起、跨分页连续）；省略时取第一条图像类错误。
 * 不带 view=quality-preview 时本脚本不做任何事，项目页行为与原来完全一致。
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
  if (view !== 'quality-preview') {
    return;
  }

  // 标记打在 <html> 上（project.vt 的 <head> 脚本已提前加过，这里兜底）
  document.documentElement.classList.add('ruyi-embed-quality-preview');

  var seq = param('seq');
  var settled = false;

  /** 嵌入页专用提示（不依赖对话框，避免关闭后页面全空） */
  function showNotice(text) {
    settled = true;
    if (typeof FilePreviewDialog !== 'undefined' && FilePreviewDialog._currentDialog) {
      FilePreviewDialog.close();
    }
    var el = document.getElementById('quality-preview-notice');
    if (!el) {
      el = document.createElement('div');
      el.id = 'quality-preview-notice';
      el.style.cssText = 'position:fixed;inset:0;display:flex;align-items:center;' +
        'justify-content:center;font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;' +
        'color:#333;background:#fff';
      document.body.appendChild(el);
    }
    el.innerHTML = '<div style="max-width:520px;margin:16px;padding:20px 24px;background:#fff;' +
      'border:1px solid #e5e7eb;border-radius:10px;font-size:14px;line-height:1.8;color:#555"></div>';
    el.firstChild.textContent = text;
  }

  function imageErrors(errors) {
    return (errors || []).filter(function(e) {
      return QualityAlignment._isImageError(e);
    });
  }

  function openPreview() {
    var errors = (QualityAlignment._lastCheckResult && QualityAlignment._lastCheckResult.errors) || [];
    if (errors.length === 0) {
      showNotice('该项目暂无可预览的检查结果，请先在平台启动检查。');
      return;
    }

    var target = null;
    var n = parseInt(seq, 10);
    if (n > 0 && n <= errors.length) {
      target = errors[n - 1];
      if (!QualityAlignment._isImageError(target)) {
        showNotice('第 ' + n + ' 条不是图像类错误（' + (target.errorType || '未知类型') + '），无法查看图像预览。');
        return;
      }
    } else {
      target = imageErrors(errors)[0];
      if (!target) {
        showNotice('检查结果中没有图像类错误，无需查看图像预览。');
        return;
      }
    }

    settled = true;
    QualityAlignment._openImagePreviewWithAnnotations(target, target.rowIndex);
  }

  /**
   * 等项目规则与检查结果都加载完再打开预览：路径由 resourceConfig.basePath 与错误行
   * 的文件列拼出，两者任一缺失都会解析到错误路径。
   */
  function waitAndOpen(attempts) {
    attempts = attempts || 0;
    if (settled) {
      return;
    }
    var ready = typeof QualityAlignment !== 'undefined'
      && typeof FilePreviewDialog !== 'undefined'
      && QualityAlignment._lastCheckResult
      && QualityAlignment._resourceConfig;
    if (!ready && attempts < 150) {
      setTimeout(function() {
        waitAndOpen(attempts + 1);
      }, 200);
      return;
    }
    if (!ready) {
      showNotice('加载检查结果超时（30 秒）。请确认检查已完成，或稍后重试。');
      return;
    }
    openPreview();
  }

  $(function() {
    setTimeout(function() {
      waitAndOpen(0);
    }, 300);
  });
})();
