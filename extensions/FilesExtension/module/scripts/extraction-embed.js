/**
 * 抽取扩展 - 嵌入形态入口（供业务系统 iframe 直接嵌入）
 *
 * 与合规审查的规则页、数据校审的参数页同口径，用 URL 参数打开单个 UI 模块：
 *   - 编辑抽取参数：/project?project=<id>&view=extraction-params
 *
 * 该面板即「抽取参数」tab（内含 #batch-config-panel），保存写回抽取任务登记表（save-task）。
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
  if (view !== 'extraction-params') {
    return;
  }

  // 标记打在 <html> 上（project.vt 的 <head> 脚本已提前加过，这里兜底）
  document.documentElement.classList.add('ruyi-embed-extraction');

  $(function() {
    if (typeof ExtractionProjectPanel === 'undefined') {
      console.warn('[extraction] 嵌入入口未找到抽取参数面板');
      return;
    }
    ExtractionProjectPanel.open();
  });
})();
