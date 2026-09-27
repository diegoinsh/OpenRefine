Refine.LocalDirectorySourceUI = function (controller) {
  this._controller = controller;
};

Refine.LocalDirectorySourceUI.prototype.attachUI = function (bodyDiv) {
  var self = this;
  var fileSystemDetails = [];

  bodyDiv.html(DOM.loadHTML("files", "scripts/index/import-from-local-dir-form.html"));

  this._elmts = DOM.bind(bodyDiv);

  $("#directoryTreePanel").hide();
  $('#fileExtensionsLabel').text($.i18n('files-import/fileExtensionsLabel'));
  $('#fileExtensionsDetails').text($.i18n('files-import/fileExtensionsDetails'));
  $('#driveSelectorLabel').text($.i18n('files-import/selectDrive'));
  $('#directorySelectLabel').text($.i18n('files-import/selectDirectory'));
  this._elmts.nextButton.html($.i18n('files-import/next'));
  this._elmts.modeLabel.text($.i18n('files-import/batch-mode-label'));
  this._elmts.modeStandardLabel.text($.i18n('files-import/batch-mode-standard'));
  this._elmts.modeVolumeLabel.text($.i18n('files-import/batch-mode-volume'));
  this._elmts.modeCaseLabel.text($.i18n('files-import/batch-mode-case'));
  this._elmts.batchConfigTitle.text($.i18n('files-import/batch-config-title'));
  this._elmts.batchProjectNameLabel.text($.i18n('files-import/batch-project-name'));
  this._elmts.batchCustomElementsLabel.text($.i18n('files-import/batch-custom-elements'));
  this._elmts.elNameHeader.text($.i18n('files-import/batch-el-name'));
  this._elmts.elKeyHeader.text($.i18n('files-import/batch-el-key'));
  this._elmts.elActionHeader.text($.i18n('files-import/batch-el-action'));
  this._elmts.elDescHeader.text($.i18n('files-import/batch-el-desc'));
  this._elmts.addRowButton.text($.i18n('files-import/batch-add-row'));
  this._elmts.batchStartButton.text($.i18n('files-import/batch-start'));
  this._elmts.batchBackButton.text($.i18n('files-import/batch-back'));
  this._elmts.batchCancelButton.text($.i18n('files-import/batch-cancel'));
  this._elmts.batchRestartButton.text("⟳ " + $.i18n('files-import/batch-restart'));
  this._elmts.batchClearCacheLabel.text($.i18n('files-import/batch-clear-cache'));
  this._elmts.openProjectButton.text($.i18n('files-import/batch-open-project'));
  this._elmts.progressLabel.text($.i18n('files-import/batch-progress-label'));
  this._elmts.batchArchiveSectionLabel.text($.i18n('files-import/batch-archive-section'));
  this._elmts.archiveCategoryLabel.text($.i18n('files-import/batch-archive-category'));
  this._elmts.archiveSubCategoryLabel.text($.i18n('files-import/batch-archive-sub-category'));
  this._elmts.extractionElementsLabel.text($.i18n('files-import/batch-extraction-elements'));

  // 档案门类（依据《机关档案管理规定》国家档案局令第13号第二十三条）：
  // value 即提交给 AIMP 的门类名，AIMP 据此选用该门类的提取规则提示词
  var ARCHIVE_CATEGORIES = [
    { value: '文书档案', i18n: 'archive-cat-wenshu' },
    { value: '科技档案', i18n: 'archive-cat-keji' },
    { value: '科研档案', i18n: 'archive-cat-keyan' },
    { value: '基建档案', i18n: 'archive-cat-jijian' },
    { value: '设备档案', i18n: 'archive-cat-shebei' },
    { value: '人事档案', i18n: 'archive-cat-renshi' },
    { value: '会计档案', i18n: 'archive-cat-kuaiji' },
    { value: '专业档案', i18n: 'archive-cat-zhuanye' },
    { value: '照片档案', i18n: 'archive-cat-zhaopian' },
    { value: '录音档案', i18n: 'archive-cat-luyin' },
    { value: '录像档案', i18n: 'archive-cat-luxiang' },
    { value: '业务数据档案', i18n: 'archive-cat-yewushuju' },
    { value: '公务电子邮件档案', i18n: 'archive-cat-gongwu-email' },
    { value: '网页信息档案', i18n: 'archive-cat-wangye' },
    { value: '社交媒体档案', i18n: 'archive-cat-shejiao' },
    { value: '实物档案', i18n: 'archive-cat-shiwu' },
    { value: '其他档案', i18n: 'archive-cat-qita' }
  ];

  // 专业档案的二级细分类别（依据《国家基本专业档案目录（第一批）》档函〔2011〕261号）：
  // 仅「专业档案」有二级细分；value 即提交给 AIMP 的二级类别名，AIMP 优先按它匹配提取规则。
  // 未列入二级细分的门类不提交该参数，AIMP 按门类规则处理。
  var ARCHIVE_SUB_CATEGORIES = {
    '专业档案': [
      { value: '人民法院诉讼档案', i18n: 'sub-cat-court' },
      { value: '人民检察院诉讼档案', i18n: 'sub-cat-procuratorate' },
      { value: '公安业务档案', i18n: 'sub-cat-police' },
      { value: '公证档案', i18n: 'sub-cat-notary' },
      { value: '纪检监察机关案件档案', i18n: 'sub-cat-discipline' },
      { value: '信访档案', i18n: 'sub-cat-petition' },
      { value: '婚姻登记档案', i18n: 'sub-cat-marriage' },
      { value: '社会保险业务档案', i18n: 'sub-cat-social-insurance' },
      { value: '病历档案', i18n: 'sub-cat-medical' },
      { value: '学籍档案', i18n: 'sub-cat-student' },
      { value: '会计档案', i18n: 'sub-cat-accounting' },
      { value: '土地管理档案', i18n: 'sub-cat-land' },
      { value: '城建档案', i18n: 'sub-cat-urban' },
      { value: '房屋产权登记档案', i18n: 'sub-cat-property' },
      { value: '审计档案', i18n: 'sub-cat-audit' },
      { value: '税收征管档案', i18n: 'sub-cat-tax' },
      { value: '海关业务档案', i18n: 'sub-cat-customs' },
      { value: '商标档案', i18n: 'sub-cat-trademark' },
      { value: '专利档案', i18n: 'sub-cat-patent' },
      { value: '地名档案', i18n: 'sub-cat-toponym' },
      { value: '气象档案', i18n: 'sub-cat-meteorology' },
      { value: '测绘档案', i18n: 'sub-cat-surveying' },
      { value: '艺术档案', i18n: 'sub-cat-art' },
      { value: '非物质文化遗产档案', i18n: 'sub-cat-heritage' },
      { value: '其他专业档案', i18n: 'sub-cat-other' }
    ]
  };

  // 固定四要素默认勾选；密级与开放状态在 DA/T 18—2022《档案著录规则》中分别为
  // 「有则必著」「必著」，故一并默认勾选；取消勾选则不向 AIMP 请求该要素
  var FIXED_ELEMENTS = [
    { key: 'title', i18n: 'el-title', checked: true },
    { key: 'responsible_party', i18n: 'el-responsible', checked: true },
    { key: 'document_number', i18n: 'el-docno', checked: true },
    { key: 'date', i18n: 'el-date', checked: true }
  ];
  // 密级、开放状态：勾选后按「自定义提取类型」的方式追加到 custom_elements
  var OPTIONAL_ELEMENTS = [
    { key: 'miji', nameKey: 'el-security', promptKey: 'el-security-prompt', checked: true },
    { key: 'kaifang_zhuangtai', nameKey: 'el-open-status', promptKey: 'el-open-status-prompt', checked: true }
  ];
  // 专门档案的扩展要素池（诉讼类档案用）：默认不勾选，勾选后同样按自定义要素方式追加
  var EXTRA_ELEMENTS = [
    { key: 'anyou', nameKey: 'el-case-cause', promptKey: 'el-case-cause-prompt', checked: false },
    { key: 'dangshiren', nameKey: 'el-party', promptKey: 'el-party-prompt', checked: false },
    { key: 'shenji', nameKey: 'el-trial-level', promptKey: 'el-trial-level-prompt', checked: false },
    { key: 'jiean_fangshi', nameKey: 'el-closing-method', promptKey: 'el-closing-method-prompt', checked: false },
    { key: 'baoguan_qixian', nameKey: 'el-retention', promptKey: 'el-retention-prompt', checked: false }
  ];

  // 只有法院/检察院诉讼档案才展示上述扩展要素；其余门类与二级类别不显示该行，也不提交这些要素
  var SUB_CATEGORY_EXTRA_ELEMENTS = {
    '人民法院诉讼档案': EXTRA_ELEMENTS,
    '人民检察院诉讼档案': EXTRA_ELEMENTS
  };

  ARCHIVE_CATEGORIES.forEach(function (cat) {
    self._elmts.archiveCategorySelect.append(
        $("<option></option>").val(cat.value).text($.i18n('files-import/' + cat.i18n)));
  });
  self._elmts.archiveCategorySelect.on('change', refreshSubCategory);
  refreshSubCategory();

  /** 切换文档类别时刷新二级细分下拉；无二级细分的门类整行隐藏，也不提交该参数 */
  function refreshSubCategory() {
    var subs = ARCHIVE_SUB_CATEGORIES[self._elmts.archiveCategorySelect.val()] || [];
    var $select = self._elmts.archiveSubCategorySelect;
    $select.empty();
    self._elmts.archiveSubCategoryRow.toggle(subs.length > 0);
    subs.forEach(function (sub) {
      $select.append($("<option></option>").val(sub.value).text($.i18n('files-import/' + sub.i18n)));
    });
    refreshExtraElements();
  }

  /** 扩展要素行仅在法院/检察院诉讼档案下展示；切走时清掉已追加到自定义要素区的行，避免残留 */
  function refreshExtraElements() {
    self._elmts.extractionElementsExtraOptions.find('.batch-element-checkbox:checked').each(function () {
      var key = $(this).val();
      var meta = extraElementByKey(key);
      if (meta && findCustomRowByKey(key).length > 0) syncOptionalElement(meta, false);
    });
    var sub = self._elmts.archiveSubCategoryRow.is(':visible')
        ? String(self._elmts.archiveSubCategorySelect.val() || '') : '';
    var extras = SUB_CATEGORY_EXTRA_ELEMENTS[sub] || [];
    self._elmts.extractionElementsExtraRow.toggle(extras.length > 0);
    self._elmts.extractionElementsExtraOptions.empty();
    extras.forEach(function (el) {
      self._elmts.extractionElementsExtraOptions.append(
          buildElementOption(el.key, $.i18n('files-import/' + el.nameKey), el.checked, el));
    });
  }

  function extraElementByKey(key) {
    for (var i = 0; i < EXTRA_ELEMENTS.length; i++) {
      if (EXTRA_ELEMENTS[i].key === key) return EXTRA_ELEMENTS[i];
    }
    return null;
  }

  // 路径分段名 → 标准类别名。三类来源：
  //  1) 标准门类代码：《机关档案管理规定》（国家档案局令第13号）规定的一级门类代码，
  //     以及科技档案的二级门类代码；
  //  2) 检察院档号代码 SS：《人民检察院诉讼档案管理办法》第十一条规定的"诉讼档案代码"；
  //  3) 标准未规定代码的门类（如法院诉讼档案）不在表内登记自造代码，路径里直接用中文名
  //     ——写全称「人民法院诉讼档案」可命中，写简称（如「法院诉讼」）则登记在第 4 类里。
  //  注：门类/二级类别的中文全称不必登记，直接命中；这里只登记代码与中文简称。
  var CATEGORY_PATH_ALIASES = {
    // ── 一级门类代码：《机关档案管理规定》国家档案局令第13号 ──
    'WS': '文书档案', 'KJ': '科技档案', 'RS': '人事档案', 'KU': '会计档案',
    'ZY': '专业档案', 'ZP': '照片档案', 'LY': '录音档案', 'LX': '录像档案',
    'SJ': '业务数据档案', 'YJ': '公务电子邮件档案', 'WY': '网页信息档案',
    'MT': '社交媒体档案', 'SW': '实物档案',
    // ── 科技档案的二级门类代码（同上，兼容三种分隔写法）──
    'KJ·KY': '科研档案', 'KJ-KY': '科研档案', 'KJKY': '科研档案',
    'KJ·JJ': '基建档案', 'KJ-JJ': '基建档案', 'KJJJ': '基建档案',
    'KJ·SB': '设备档案', 'KJ-SB': '设备档案', 'KJSB': '设备档案',
    // ── 检察院诉讼档案的档号代码：《人民检察院诉讼档案管理办法》第十一条 ──
    //    档号结构：诉讼档案代码 SS — 结案年度 — 保管期限代码 — 顺序号，如 SS-2016-1-00001
    'SS': '人民检察院诉讼档案',
    // ── 中文简称：标准未规定代码的门类，路径里按实际写法登记 ──
    '法院诉讼': '人民法院诉讼档案',
    '检察院诉讼': '人民检察院诉讼档案'
  };

  /** 按类别名（或别名解析后的名字）定位门类，二级细分类别优先于父门类 */
  function matchCategoryByName(name) {
    if (!name) return null;
    for (var cat in ARCHIVE_SUB_CATEGORIES) {
      var subs = ARCHIVE_SUB_CATEGORIES[cat] || [];
      for (var i = 0; i < subs.length; i++) {
        if (subs[i].value === name) return { category: cat, subCategory: name };
      }
    }
    for (var j = 0; j < ARCHIVE_CATEGORIES.length; j++) {
      if (ARCHIVE_CATEGORIES[j].value === name) return { category: name, subCategory: null };
    }
    return null;
  }

  /**
   * 把一个路径分段名解析为门类，解析不出返回 null。
   * 先整体匹配（中文类别名或门类代码），再尝试把分段当作「门类代码 + 分隔符/数字」的形式
   * （如档号分段「WS-2024」「ZY·2023」「WS2024」），这样带年度/序号的分段也能识别。
   */
  function lookupCategoryName(segment) {
    var seg = String(segment || '').trim();
    if (!seg) return null;
    var hit = matchCategoryByName(CATEGORY_PATH_ALIASES[seg] || seg);
    if (hit) return hit;
    for (var code in CATEGORY_PATH_ALIASES) {
      if (seg.length <= code.length || seg.indexOf(code) !== 0) continue;
      var next = seg.charAt(code.length);
      if (next === '-' || next === '·' || next === '.' || next === '_' || next === ' ' || /[0-9]/.test(next)) {
        hit = matchCategoryByName(CATEGORY_PATH_ALIASES[code]);
        if (hit) return hit;
      }
    }
    return null;
  }

  /**
   * 依所选根路径自动选择档案门类：路径各级文件夹名与门类/二级类别名做精确匹配，
   * 从末级往前扫描，因此「专业档案\人民法院诉讼档案\JZ07-2024-M2-0158」会命中二级类别。
   * 匹配不到时保持界面默认值，不改变用户原有操作。
   * @return 是否命中
   */
  function applyCategoryFromPath(rootPath) {
    var segments = String(rootPath || '').split(/[\\/]+/).filter(function (s) {
      return String(s).trim().length > 0;
    });
    for (var i = segments.length - 1; i >= 0; i--) {
      var hit = lookupCategoryName(String(segments[i]).trim());
      if (!hit) continue;
      self._elmts.archiveCategorySelect.val(hit.category);
      refreshSubCategory();
      if (hit.subCategory) {
        self._elmts.archiveSubCategorySelect.val(hit.subCategory);
        refreshExtraElements();
      }
      console.log('[批量提取] 依路径自动选择档案门类:', hit.category,
          hit.subCategory ? '> ' + hit.subCategory : '');
      return true;
    }
    return false;
  }

  function buildElementOption(key, label, checked, meta) {
    var $option = $("<label class='batch-element-option'></label>");
    var $checkbox = $("<input type='checkbox' class='batch-element-checkbox'/>").val(key);
    if (checked) $checkbox.prop('checked', true);
    $checkbox.on('change', function () {
      if (meta) syncOptionalElement(meta, $(this).is(':checked'));
    });
    $option.append($checkbox).append($("<span></span>").text(label));
    return $option;
  }

  FIXED_ELEMENTS.forEach(function (el) {
    self._elmts.extractionElementsOptions.append(
        buildElementOption(el.key, $.i18n('files-import/' + el.i18n), el.checked, null));
  });
  OPTIONAL_ELEMENTS.forEach(function (el) {
    self._elmts.extractionElementsOptions.append(
        buildElementOption(el.key, $.i18n('files-import/' + el.nameKey), el.checked, el));
    if (el.checked) syncOptionalElement(el, true);
  });

  function findCustomRowByKey(key) {
    return self._elmts.customElementsBody.find("tr").filter(function () {
      return $(this).find(".batch-el-key").val().trim() === key;
    });
  }

  /** 勾选密级/开放状态时追加对应自定义要素行，取消勾选时移除该行 */
  function syncOptionalElement(meta, checked) {
    var $existing = findCustomRowByKey(meta.key);
    if (!checked) {
      $existing.remove();
      return;
    }
    if ($existing.length > 0) return;
    addCustomElementRow({
      name: $.i18n('files-import/' + meta.nameKey),
      key: meta.key,
      action: 'include',
      description: $.i18n('files-import/' + meta.promptKey)
    });
  }

  /** 本次勾选的固定要素键，作为 keyList 提交给 AIMP，未勾选的要素不请求 */
  function collectFixedElements() {
    var fixedKeys = FIXED_ELEMENTS.map(function (el) { return el.key; });
    var keys = [];
    self._elmts.extractionElementsOptions.find(".batch-element-checkbox:checked").each(function () {
      var key = $(this).val();
      if (fixedKeys.indexOf(key) >= 0) keys.push(key);
    });
    return keys;
  }

  var self2 = this;
  $('input[name="extractionMode"]').on('change', function () {
    var mode = $("input[name='extractionMode']:checked").val();
    self2._elmts.batchModeNote.toggle(mode !== 'standard');
  });

  getFileSystemDetails();

  function getFileSystemDetails() {

    Refine.wrapCSRF(function (token) {
      $.post(
        "command/core/importing-controller?" + $.param({
          "controller": "files/files-importing-controller",
          "subCommand": "filesystem-details",
          "csrf_token": token
        }),
        null,
        function (data) {
          if (!(data)) {
            window.alert($.i18n('files-import/fetch-drive-details-failed'));
          }
          else {
            fileSystemDetails = data;
            const selectOneLabel = $.i18n('files-import/select-one');
            const driveSelector = $("#drive-selector");
            driveSelector.append('<option value="" disabled selected>' + selectOneLabel + '</option>');
            fileSystemDetails.forEach((item) => {
              const option = document.createElement("option");
              option.value = item;
              option.textContent = item;
              driveSelector.append(option);
            });
          }

        },
        "json"
      );
    });
  }

  function getDirectoryHierarchy(rootPath) {
    var dismiss = DialogSystem.showBusy(($.i18n('files-import/fetchingDirectoryDetails')));

    Refine.wrapCSRF(function (token) {
      $.post(
        "command/core/importing-controller?" + $.param({
          "controller": "files/files-importing-controller",
          "subCommand": "directory-hierarchy",
          "dirPath": rootPath,
          "csrf_token": token
        }),
        null,
        function (data) {
          dismiss();
          if (!(data)) {
            window.alert($.i18n('files-import/fetch-directory-details-failed'));
          } else {
            var treeData = [JSON.parse(data)];
            renderTreeView(treeData);
            $("#directoryTreePanel").show();
          }
        },
        "json"
      );
    });
  }

  this._elmts.driveselector.on('change', function () {
    const selectedValue = $(this).val();
    if (selectedValue) {
      $("#directoryTreePanel").hide();
      getDirectoryHierarchy(selectedValue);
    } else {
      window.alert($.i18n('files-import/drive-not-selected'));
    }
  });

  this._elmts.form.on('submit', function (evt) {
    evt.preventDefault();
    var doc = {};
    let errorString = '';

    const selectedItems = $(".directory-checkbox:checked")
      .map(function () {
        return { directory: $(this).val() };
      })
      .get();

    if (selectedItems.length === 0) {
      errorString += $.i18n('files-import/no-directory-selected') + '\n';
      window.alert($.i18n('files-import/warning-directory-selection') + "\n" + errorString);
      return;
    }

    var mode = $("input[name='extractionMode']:checked").val();
    if (mode !== 'standard' && selectedItems.length > 1) {
      window.alert($.i18n('files-import/batch-single-dir-alert'));
      return;
    }

    if (mode === 'standard') {
      doc.directoryJsonObj = selectedItems;
      self._controller.startImportingDocument(doc);
    } else {
      showBatchConfig(mode, selectedItems[0].directory);
    }
  });

  var batchMode = null;
  var batchRootPath = null;
  var batchProjectId = null;
  var batchPollTimer = null;

  function pad2(n) {
    return (n < 10 ? '0' : '') + n;
  }

  /** 默认项目名：模式名-所选根目录名-年月日时（如 文件级条目提取-0033-2026080314） */
  function defaultBatchProjectName() {
    var path = (batchRootPath || '').replace(/[\\/]+$/, '');
    var idx = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
    var dirName = idx >= 0 ? path.substring(idx + 1) : path;
    if (!dirName) {
      dirName = $.i18n('files-import/batch-project-default-name');
    }
    var now = new Date();
    var stamp = '' + now.getFullYear() + pad2(now.getMonth() + 1)
        + pad2(now.getDate()) + pad2(now.getHours());
    var modeKey = batchMode === 'batch-case'
        ? 'files-import/batch-mode-case' : 'files-import/batch-mode-volume';
    return $.i18n(modeKey) + '-' + dirName + '-' + stamp;
  }

  function showBatchConfig(mode, rootPath) {
    batchMode = mode;
    batchRootPath = rootPath;
    $("#directoryTreePanel").hide();
    self._elmts.batchConfigPanel.show();
    self._elmts.batchActionRow.show();
    self._elmts.batchProgressPanel.hide();
    self._elmts.batchStartButton.prop('disabled', false);
    self._elmts.batchBackButton.prop('disabled', false);
    self._elmts.openProjectButton.hide();
    self._elmts.batchCancelButton.show();
    self._elmts.progressBar.css("width", "0%");
    self._elmts.progressDetail.empty();
    self._elmts.progressMessage.empty();
    // 未手工改过名称时，随所选根目录与提取模式刷新默认名
    if (!self._elmts.batchProjectName.data('bound')) {
      self._elmts.batchProjectName.data('bound', true).on('input', function () {
        $(this).data('edited', true);
      });
    }
    if (!self._elmts.batchProjectName.val() || !self._elmts.batchProjectName.data('edited')) {
      self._elmts.batchProjectName.val(defaultBatchProjectName());
    }
    if (self._elmts.customElementsBody.children().length === 0) {
      addCustomElementRow();
    }
    // 依所选路径自动选择档案门类（匹配不到则保持默认）
    applyCategoryFromPath(rootPath);
  }

  function addCustomElementRow(data) {
    var d = data || {};
    var $tr = $("<tr></tr>");
    var $name = $("<input type='text' class='batch-el-name'/>").val(d.name || "");
    var $key = $("<input type='text' class='batch-el-key'/>").val(d.key || "");
    if (d.key) $key.data('manual', true);
    $name.on('input', function () {
      if ($tr.find(".batch-el-action").val() === 'adjust') return;
      if ($key.data('manual')) return;
      var name = $name.val().trim();
      if (!name) {
        $key.val("");
        return;
      }
      $key.val(generateKey(name, usedKeysExcept($tr)));
    });
    $key.on('input', function () {
      $key.data('manual', true);
    });
    $tr.append($("<td></td>").append($name));
    $tr.append($("<td></td>").append($key));
    var $action = $("<select class='batch-el-action'></select>");
    $action.append($("<option value='include'></option>").text($.i18n('files-import/batch-el-add')));
    $action.append($("<option value='adjust'></option>").text($.i18n('files-import/batch-el-adjust')));
    if (d.action === 'adjust') $action.val('adjust');
    $action.on('change', function () {
      applyActionState($tr, $name, $key);
    });
    $tr.append($("<td></td>").append($action));
    $tr.append($("<td></td>").append(
        $("<input type='text' class='batch-el-desc'/>").val(d.description || "")));
    var $del = $("<button type='button' class='button button-light batch-el-remove'>×</button>");
    $del.on('click', function () { $tr.remove(); });
    $tr.append($("<td></td>").append($del));
    self._elmts.customElementsBody.append($tr);
    applyActionState($tr, $name, $key);
    return $tr;
  }

  function applyActionState($tr, $name, $key) {
    if ($tr.find(".batch-el-action").val() === 'adjust') {
      $key.val("").prop('disabled', true);
      $key.removeData('manual');
      return;
    }
    $key.prop('disabled', false);
    if (!$key.val()) {
      var name = $name.val().trim();
      if (name) $key.val(generateKey(name, usedKeysExcept($tr)));
    }
  }

  this._elmts.addRowButton.on('click', function () {
    addCustomElementRow();
  });

  function sanitizeKey(name) {
    var sb = "";
    for (var i = 0; i < name.length; i++) {
      var c = name.charAt(i);
      if (/[a-zA-Z0-9]/.test(c)) {
        sb += c.toLowerCase();
      } else {
        sb += "_";
      }
    }
    sb = sb.replace(/_+/g, "_").replace(/^_+|_+$/g, "");
    if (/^[0-9]/.test(sb)) sb = "x" + sb;
    return sb;
  }

  function toPinyin(name) {
    if (typeof TinyPinyin === 'undefined') return null;
    try {
      if (!TinyPinyin.isSupported()) return null;
      return TinyPinyin.convertToPinyin(name, '', true);
    } catch (e) {
      return null;
    }
  }

  function keyBase(name) {
    var pinyin = toPinyin(name);
    return sanitizeKey(pinyin === null ? name : pinyin);
  }

  function usedKeysExcept($row) {
    var used = {};
    self._elmts.customElementsBody.find("tr").each(function () {
      if (this === $row[0]) return;
      var key = $(this).find(".batch-el-key").val().trim();
      if (key) used[key] = true;
    });
    return used;
  }

  function generateKey(name, usedKeys) {
    var base = keyBase(name);
    var key = (base && !usedKeys[base]) ? base : null;
    if (!key) {
      var n = 1;
      while (usedKeys["custom_" + n]) n++;
      key = "custom_" + n;
    }
    usedKeys[key] = true;
    return key;
  }

  function collectCustomElements() {
    var rows = self._elmts.customElementsBody.find("tr");
    var usedKeys = {};
    rows.each(function () {
      if ($(this).find(".batch-el-action").val() === 'adjust') return;
      var key = $(this).find(".batch-el-key").val().trim();
      if (key) usedKeys[key] = true;
    });
    var items = [];
    rows.each(function () {
      var name = $(this).find(".batch-el-name").val().trim();
      var key = $(this).find(".batch-el-key").val().trim();
      var action = $(this).find(".batch-el-action").val();
      var description = $(this).find(".batch-el-desc").val().trim();
      if (action === 'adjust') {
        if (!description) return;
        items.push({ name: name, key: "", action: action, description: description });
        return;
      }
      if (!name) return;
      if (!key) key = generateKey(name, usedKeys);
      items.push({ name: name, key: key, action: action, description: description });
    });
    return items;
  }

  this._elmts.batchBackButton.on('click', function () {
    self._elmts.batchConfigPanel.hide();
    self._elmts.batchActionRow.hide();
    $("#directoryTreePanel").show();
  });

  this._elmts.batchCustomElementsLabel.on('click', function () {
    self._elmts.customElementsSection.toggle();
  });

  this._elmts.batchStartButton.on('click', function () {
    var projectName = self._elmts.batchProjectName.val().trim();
    if (!projectName) {
      window.alert($.i18n('files-import/batch-project-name-required'));
      return;
    }
    var fixedElements = collectFixedElements();
    if (fixedElements.length === 0) {
      window.alert($.i18n('files-import/batch-elements-required'));
      return;
    }
    var payload = {
      subCommand: "start",
      rootPath: batchRootPath,
      template: batchMode === 'batch-case' ? 'batch-title-case' : 'batch-title-volume',
      projectName: projectName,
      archiveCategory: self._elmts.archiveCategorySelect.val(),
      // 仅专业档案等有二级细分的门类提交该参数，其余留空由 AIMP 按门类规则处理
      archiveSubCategory: self._elmts.archiveSubCategoryRow.is(':visible')
          ? String(self._elmts.archiveSubCategorySelect.val() || "") : "",
      fixedElements: JSON.stringify(fixedElements),
      customElements: JSON.stringify(collectCustomElements()),
      disableCache: self._elmts.batchClearCache.is(':checked') ? "true" : "false"
    };
    Refine.wrapCSRF(function (token) {
      payload.csrf_token = token;
      $.post("command/files/batch-extraction", payload, function (data) {
        if (!data || data.code === 'error') {
          window.alert(data && data.message ? data.message : $.i18n('files-import/batch-start-failed'));
          return;
        }
        batchProjectId = data.projectId;
        self._elmts.openProjectButton.show();
        startProgressPolling(data.projectId);
      }, "json");
    });
  });

  function startProgressPolling(projectId) {
    self._elmts.batchStartButton.prop('disabled', true);
    self._elmts.batchBackButton.prop('disabled', true);
    self._elmts.batchRestartButton.hide();
    self._elmts.batchCancelButton.show();
    self._elmts.batchProgressPanel.show();
    if (batchPollTimer) window.clearInterval(batchPollTimer);
    self._elmts.progressBar.removeData("last-percent");
    batchPollTimer = window.setInterval(function () {
      Refine.wrapCSRF(function (token) {
        $.post("command/files/batch-extraction", {
          subCommand: "progress",
          project: projectId,
          csrf_token: token
        }, function (data) {
          if (!data || data.code === 'error') {
            window.clearInterval(batchPollTimer);
            batchPollTimer = null;
            window.alert(data && data.message ? data.message : $.i18n('files-import/batch-progress-error'));
            return;
          }
          var percent;
          // 与项目页顶部横幅同口径：按件推进（已完成件数 + 当前件内完成度），
          // 页口径会因 PDF 实际页数动态修正分母而回退
          if ((data.totalFiles || 0) > 0) {
            percent = ((data.processedFiles || 0) - 1
                + (typeof data.unitFraction === 'number' ? data.unitFraction : 0))
                * 100 / data.totalFiles;
          } else if ((data.totalPages || 0) > 0) {
            percent = (data.processedPages || 0) * 100 / data.totalPages;
          } else {
            percent = 0;
          }
          percent = Math.max(0, Math.min(100, Math.round(percent)));
          var lastPercent = self._elmts.progressBar.data("last-percent");
          if (typeof lastPercent === "number" && percent < lastPercent) {
            percent = lastPercent;
          }
          self._elmts.progressBar.data("last-percent", percent);
          self._elmts.progressBar.css("width", percent + "%");
          var unitLabel = $.i18n(data.unitKind === 'volume'
              ? 'files-import/batch-unit-volume' : 'files-import/batch-unit-case');
          var detail = data.totalFiles > 0
              ? data.processedFiles + " / " + data.totalFiles + " " + unitLabel + " · " : "";
          detail += data.processedPages + " / " + data.totalPages + " 页"
              + " · " + (data.currentUnit || "")
              + " · " + data.rowsAppended + " 行";
          self._elmts.progressDetail.text(detail);
          self._elmts.progressMessage.text(data.message || "");
          if (data.status !== 'running') {
            window.clearInterval(batchPollTimer);
            batchPollTimer = null;
            self._elmts.batchCancelButton.hide();
            self._elmts.openProjectButton.show();
            self._elmts.batchRestartButton.show();
          }
        }, "json");
      });
    }, 2000);
  }

  this._elmts.batchCancelButton.on('click', function () {
    if (!batchProjectId) return;
    Refine.wrapCSRF(function (token) {
      $.post("command/files/batch-extraction", {
        subCommand: "cancel",
        project: batchProjectId,
        csrf_token: token
      }, function () {}, "json");
    });
  });

  this._elmts.batchRestartButton.on('click', function () {
    self._elmts.batchRestartButton.hide();
    self._elmts.batchStartButton.prop('disabled', false);
    self._elmts.batchBackButton.prop('disabled', false);
    self._elmts.batchProgressPanel.hide();
    self._elmts.progressBar.css("width", "0%");
    self._elmts.progressDetail.empty();
    self._elmts.progressMessage.empty();
  });

  this._elmts.openProjectButton.on('click', function () {
    if (batchProjectId) {
      document.location = "project?project=" + batchProjectId;
    }
  });

  function renderTreeView(tree) {
    const $treeContainer = $("#directory-tree");
    $treeContainer.empty();

    function buildTree(nodes, isRoot = false) {
      const $ul = $("<ul></ul>");
      if (!isRoot) {
        $ul.hide();
      }

      nodes.forEach((node) => {
        const $li = $("<li></li>");
        const hasChildren = node.children && node.children.length > 0 ? true : false;

        const $label = $(`
                <label>
                    <input type="checkbox" class="directory-checkbox" value="${node.path}">
                    ${node.name}
                </label>
            `);

        if (hasChildren) {
          const $toggle = $("<span class='toggle'>></span>");
          $toggle.on("click", function () {
            const $childUl = $li.children("ul");
            if ($childUl.is(":visible")) {
              $childUl.slideUp();
              $toggle.text(">");
            } else {
              $childUl.slideDown();
              $toggle.text("v");
            }
          });

          $li.append($toggle);
        } else {
          const $toggle = $("<span class='notoggle'>-</span>");
          $li.append($toggle);
        }

        $li.append($label);

        if (hasChildren) {
          const $subTree = buildTree(node.children);
          $li.append($subTree);
        }

        $ul.append($li);
      });

      return $ul;
    }

    $treeContainer.append(buildTree(tree, true));
  }

};

Refine.LocalDirectorySourceUI.prototype.focus = function () {

};
