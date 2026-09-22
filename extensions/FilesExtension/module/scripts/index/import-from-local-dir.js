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
    var payload = {
      subCommand: "start",
      rootPath: batchRootPath,
      template: batchMode === 'batch-case' ? 'batch-title-case' : 'batch-title-volume',
      projectName: projectName,
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
