/**
 * 抽取扩展 - 管理抽取规则
 *
 * 展示当前项目的抽取规则（模式 / 门类 / 要素 / 自定义要素），并支持导出/导入模板。
 * 与「管理质量规则」一致：只做展示与导入导出，不提供删除。
 * 系统内置提示词不展示；自定义要素的名称 / key / 操作 / 提示词全部展示。
 */
function ExtractionRulesDialog() {
  this._createDialog();
}

ExtractionRulesDialog.LABELS = {
  title: '题名',
  responsible_party: '责任者',
  document_number: '文号',
  date: '成文日期',
  miji: '密级',
  kaifang_zhuangtai: '开放状态',
  anyou: '案由',
  dangshiren: '当事人',
  shenji: '审级',
  jiean_fangshi: '结案方式',
  baoguan_qixian: '保管期限'
};

ExtractionRulesDialog.prototype._createDialog = function() {
  var self = this;
  var frame = $(DOM.loadHTML('files', 'scripts/dialogs/extraction-rules-dialog.html'));
  this._elmts = DOM.bind(frame);

  this._elmts.dialogHeader.text('管理抽取规则');
  this._elmts.closeButton.text('关闭');
  this._elmts.exportButton.text('导出规则');
  this._elmts.importButton.text('导入规则');

  this._elmts.closeButton.on('click', function() {
    DialogSystem.dismissUntil(self._level - 1);
  });
  this._elmts.exportButton.on('click', function() {
    self._exportRules();
  });
  this._elmts.importButton.on('click', function() {
    self._importRules();
  });

  this._level = DialogSystem.showDialog(frame);
  this._load();
};

ExtractionRulesDialog.prototype._load = function() {
  var self = this;
  Refine.wrapCSRF(function(token) {
    $.post('command/files/batch-extraction',
      { subCommand: 'list', csrf_token: token },
      function(data) {
        var task = null;
        if (data && data.code === 'ok' && data.tasks) {
          for (var i = 0; i < data.tasks.length; i++) {
            if (String(data.tasks[i].projectId) === String(theProject.id)) {
              task = data.tasks[i];
              break;
            }
          }
        }
        self._task = task || {};
        self._render();
      }, 'json').fail(function(xhr) {
        self._elmts.errorText.text('读取抽取规则失败（HTTP ' + xhr.status + '）').show();
      });
  });
};

ExtractionRulesDialog.prototype._render = function() {
  var task = this._task || {};
  var summary = this._elmts.summary.empty();

  if (!task.template && !task.rootPath) {
    summary.html('<p class="no-rules">该项目尚未配置抽取规则。</p>');
    return;
  }

  var isCase = task.template === 'batch-title-case';
  var modeText = isCase ? '按件（单表）' : '案卷（卷内 + 卷级）';
  var categoryText = task.archiveCategory || '（未指定）';
  var subCategoryText = task.archiveSubCategory || '（无）';

  var basic = $('<div class="rules-section"></div>').appendTo(summary);
  $('<h4></h4>').text('基本配置').appendTo(basic);
  var dl = $('<dl></dl>').appendTo(basic);
  addPair(dl, '抽取模式', modeText);
  addPair(dl, '档案门类', categoryText);
  addPair(dl, '二级门类', subCategoryText);
  addPair(dl, '源目录', task.rootPath || '（未登记）');

  // 固定要素：只显示要素名，不显示系统内置提示词
  var fixed = parseArray(task.fixedElements);
  var fixedSection = $('<div class="rules-section"></div>').appendTo(summary);
  $('<h4></h4>').text('抽取要素').appendTo(fixedSection);
  if (fixed.length === 0) {
    $('<p></p>').text('（未选择固定要素）').appendTo(fixedSection);
  } else {
    var names = fixed.map(function(key) {
      return ExtractionRulesDialog.LABELS[key] || key;
    });
    $('<p></p>').text(names.join('、')).appendTo(fixedSection);
  }

  // 自定义要素：名称 / key / 操作 / 提示词 全部展示
  var custom = parseArray(task.customElements);
  var customSection = $('<div class="rules-section"></div>').appendTo(summary);
  $('<h4></h4>').text('自定义要素').appendTo(customSection);
  if (custom.length === 0) {
    $('<p></p>').text('（无）').appendTo(customSection);
  } else {
    var table = $('<table></table>').appendTo(customSection);
    var thead = $('<thead></thead>').appendTo(table);
    var hr = $('<tr></tr>').appendTo(thead);
    ['名称', 'key', '操作', '提示词'].forEach(function(h) {
      $('<th></th>').text(h).appendTo(hr);
    });
    var tbody = $('<tbody></tbody>').appendTo(table);
    custom.forEach(function(item) {
      var tr = $('<tr></tr>').appendTo(tbody);
      $('<td></td>').text(item.name || '').appendTo(tr);
      $('<td></td>').text(item.key || '').appendTo(tr);
      $('<td></td>').text(item.action === 'adjust' ? '微调现有要素' : '新增要素').appendTo(tr);
      $('<td class="extraction-rule-prompt"></td>').text(item.description || '').appendTo(tr);
    });
  }

  function addPair(dl, label, value) {
    $('<dt></dt>').text(label).appendTo(dl);
    $('<dd></dd>').text(value).appendTo(dl);
  }
};

ExtractionRulesDialog.prototype._exportRules = function() {
  var task = this._task || {};
  if (!task.rootPath) {
    this._elmts.errorText.text('该项目尚未建立抽取任务，无法导出规则。').show();
    return;
  }
  var isCase = task.template === 'batch-title-case';
  // 件级列 / 卷级列由引擎按抽取模式与门类确定，模板不再导出这部分
  var payload = {
    engineTemplate: isCase ? 'batch-title-case' : 'batch-title-volume',
    archiveCategory: task.archiveCategory || '',
    archiveSubCategory: task.archiveSubCategory || '',
    fixedElements: parseArray(task.fixedElements),
    customElements: parseArray(task.customElements)
  };

  var projectId = theProject.id;
  var name = (theProject.metadata && theProject.metadata.name)
      ? theProject.metadata.name : ('#' + projectId);
  var doc = {
    schema: 'rcp.extraction-template/v1',
    templateId: 'extraction-rules-' + projectId,
    type: 'extraction',
    name: name + ' 抽取规则',
    description: '从 JinRefine 导出（项目 ' + projectId + '）',
    version: 1,
    payload: payload
  };

  var blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = 'extraction-template-' + projectId + '.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};

ExtractionRulesDialog.prototype._importRules = function() {
  var self = this;
  var input = document.createElement('input');
  input.type = 'file';
  input.accept = '.json';

  input.onchange = function(e) {
    var file = e.target.files[0];
    if (!file) {
      return;
    }
    var reader = new FileReader();
    reader.onload = function(evt) {
      try {
        var doc = JSON.parse(evt.target.result);
        // 兼容 RCP 模板文件（{payload:{...}}）与裸配置
        var rules = doc.payload ? doc.payload : doc;
        var template = rules.engineTemplate || rules.template;
        if (!template) {
          throw new Error('缺少 engineTemplate');
        }
        if (!self._task.rootPath) {
          throw new Error('请先在抽取任务控制台登记源目录');
        }
        var payload = {
          subCommand: 'save-task',
          project: theProject.id,
          rootPath: self._task.rootPath,
          projectName: self._task.projectName || '',
          template: template,
          archiveCategory: rules.archiveCategory || '',
          archiveSubCategory: rules.archiveSubCategory || '',
          fixedElements: JSON.stringify(rules.fixedElements || []),
          customElements: JSON.stringify(rules.customElements || []),
          disableCache: 'false'
        };
        Refine.wrapCSRF(function(token) {
          payload.csrf_token = token;
          $.post('command/files/batch-extraction', payload, function(data) {
            if (data && data.code === 'ok') {
              self._elmts.errorText.hide();
              self._load();
              if (typeof ExtractionProjectPanel !== 'undefined') {
                // 仅重渲染已打开的 tab（不主动切换）
                if ($('#extraction-params-panel').is(':visible')) {
                  ExtractionProjectPanel.refresh();
                }
              }
            } else {
              self._elmts.errorText.text((data && data.message) ? data.message : '导入失败').show();
            }
          }, 'json').fail(function(xhr) {
            self._elmts.errorText.text('导入失败（HTTP ' + xhr.status + '）').show();
          });
        });
      } catch (err) {
        self._elmts.errorText.text('导入失败：' + err.message).show();
      }
    };
    reader.readAsText(file);
  };

  input.click();
};

function parseArray(text) {
  if (!text) {
    return [];
  }
  try {
    var v = JSON.parse(text);
    return $.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}