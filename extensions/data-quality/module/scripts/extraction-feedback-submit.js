/**
 * 抽取反馈 · 导出时触发最终态快照比对（可休眠、失败不影响导出）。
 *
 * 口径：导出是「这批复核结果定稿」的天然时点，比对结果 POST 给 RCP
 * （/api/extraction-feedback/samples）。日常改格子已经由 extraction-edit-log.js 记录，
 * 这里只看最终留下的值，因此撤销后不会留下错误标签。
 *
 * 关闭方式：把 ExtractionFeedbackSubmit.ENABLED 置 false（或移除本脚本的注入）。
 */
ExtractionFeedbackSubmit = {

  /** 总开关（部署态可关；关掉后导出照常，只是不上报）。 */
  ENABLED: true,

  /** 同一项目两次上报的最小间隔（毫秒）：导出常连点，平台侧还有 checksum 幂等兜底。 */
  MIN_INTERVAL_MS: 60 * 1000,

  _lastAt: 0,
  _lastProject: null,
  _inflight: false,

  /** 上报当前项目（source: ui_export / idle_scan）。 */
  submit: function(source) {
    if (!ExtractionFeedbackSubmit.ENABLED) return;
    if (typeof theProject === 'undefined' || !theProject || !theProject.id) return;

    var now = new Date().getTime();
    if (ExtractionFeedbackSubmit._inflight
        || (ExtractionFeedbackSubmit._lastProject === theProject.id
            && now - ExtractionFeedbackSubmit._lastAt < ExtractionFeedbackSubmit.MIN_INTERVAL_MS)) {
      return;
    }
    ExtractionFeedbackSubmit._lastAt = now;
    ExtractionFeedbackSubmit._lastProject = theProject.id;
    ExtractionFeedbackSubmit._inflight = true;

    try {
      Refine.postProcess(
        'data-quality',
        'submit-extraction-feedback',
        {},
        { project: theProject.id, source: source || 'ui_export', push: '1' },
        { includeEngine: false },
        {
          onDone: function() { ExtractionFeedbackSubmit._inflight = false; },
          onError: function() { ExtractionFeedbackSubmit._inflight = false; }
        }
      );
    } catch (e) {
      ExtractionFeedbackSubmit._inflight = false;
    }
  },

  /** 包住导出入口：导出前先上报（异步，不阻塞下载）。 */
  _install: function() {
    if (typeof ExporterManager === 'undefined' || !ExporterManager.handlers
        || !ExporterManager.handlers.exportRows) {
      setTimeout(ExtractionFeedbackSubmit._install, 200);
      return;
    }
    if (ExporterManager.handlers.exportRows.__feedbackWrapped) return;

    var original = ExporterManager.handlers.exportRows;
    var wrapped = function() {
      ExtractionFeedbackSubmit.submit('ui_export');
      return original.apply(this, arguments);
    };
    wrapped.__feedbackWrapped = true;
    ExporterManager.handlers.exportRows = wrapped;
  }
};

ExtractionFeedbackSubmit._install();
