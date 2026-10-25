/**
 * 人工修改事件记录（可休眠，默认开启；只落盘、不改任何数据）。
 *
 * 目的：为质量分校准采集标签。规则档位分与 LLM 自评都不可校准（§10.1 gate 实测无区分度），
 * 而「用户把某格从什么改成了什么」是真实分布 + 真实口径的天然标签。
 *
 * 记录内容（JSON Lines，落 workspace/edit-events/<projectId>.jsonl）：
 *   ts / projectId / sheet / row / col / old / new / type / mode
 *   page / q / qf —— 取自 pageMap 中该列 `w === 1` 的候选，便于回溯"改自哪一页、当时打多少分"
 *
 * 回退：把 ENABLED 置 false，或在 DataQualityExtensionSpi 里摘掉本脚本，行为即与改造前一致。
 * 服务端未注册 log-edit-event 时静默失败，不影响编辑本身。
 */
console.log('[ExtractionEditLog] extraction-edit-log.js 脚本加载');

var ExtractionEditLog = {

  /** 前端总开关：置 false 即整体回退（不发起任何请求） */
  ENABLED: true,

  /** 单条记录（cell-ui 提交回调调用） */
  record: function(opts) {
    if (!ExtractionEditLog.ENABLED) return;
    if (typeof theProject === 'undefined' || !theProject) return;
    if (!opts || opts.old === opts.new) return;

    var ev = {
      sheet: theProject.activeSheetId || null,
      row: opts.row,
      col: opts.col,
      old: opts.old === undefined ? null : opts.old,
      new: opts.new === undefined ? null : opts.new,
      type: opts.type || 'text',
      mode: 'single'
    };

    ExtractionEditLog._withCandidate(ev, function() {
      ExtractionEditLog._send(ev);
    });
  },

  /** 批量记录（mass-edit：同一列多处替换，无具体行号） */
  recordMass: function(opts) {
    if (!ExtractionEditLog.ENABLED) return;
    if (typeof theProject === 'undefined' || !theProject) return;
    if (!opts || opts.old === opts.new) return;

    ExtractionEditLog._send({
      sheet: theProject.activeSheetId || null,
      col: opts.col,
      old: opts.old === undefined ? null : opts.old,
      new: opts.new === undefined ? null : opts.new,
      type: opts.type || 'text',
      mode: 'mass'
    });
  },

  /** 补齐该行该列的 `w` 候选（页号/质量分/原因），pageMap 未加载时先拉一次 */
  _withCandidate: function(ev, done) {
    var fill = function() {
      try {
        if (typeof FileViewPanel !== 'undefined' && FileViewPanel._pageMap && ev.row !== undefined) {
          var node = FileViewPanel._pageMap[FileViewPanel._pageMapKey(ev.row)];
          var list = node && ev.col ? node[ev.col] : null;
          if (list && list.length) {
            var winner = null;
            for (var i = 0; i < list.length; i++) {
              if (list[i] && list[i].w === 1) { winner = list[i]; break; }
            }
            if (winner) {
              if (winner.p !== undefined) ev.page = winner.p;
              if (winner.q !== undefined) ev.q = winner.q;
              if (winner.qf !== undefined) ev.qf = winner.qf;
            }
          }
        }
      } catch (e) {
        // 候选补齐失败不影响事件本身
      }
      done();
    };

    if (typeof FileViewPanel !== 'undefined' && FileViewPanel._ensurePageMap
        && ev.row !== undefined) {
      try {
        FileViewPanel._ensurePageMap(fill, ev.row);
        return;
      } catch (e) {
        // 落到直接发送
      }
    }
    fill();
  },

  _send: function(ev) {
    try {
      Refine.postProcess(
        'data-quality',
        'log-edit-event',
        {},
        { event: JSON.stringify(ev) },
        { includeEngine: false },
        {}
      );
    } catch (e) {
      // 埋点失败不影响编辑
    }
  }
};
