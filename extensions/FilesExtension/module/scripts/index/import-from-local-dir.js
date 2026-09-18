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

  function showBatchConfig(mode, rootPath) {
    batchMode = mode;
    batchRootPath = rootPath;
    $("#directoryTreePanel").hide();
    self._elmts.batchConfigPanel.show();
    self._elmts.batchProgressPanel.hide();
    self._elmts.batchStartButton.prop('disabled', false);
    self._elmts.batchBackButton.prop('disabled', false);
    self._elmts.openProjectButton.hide();
    self._elmts.batchCancelButton.show();
    self._elmts.progressBar.css("width", "0%");
    self._elmts.progressDetail.empty();
    self._elmts.progressMessage.empty();
    if (!self._elmts.batchProjectName.val()) {
      self._elmts.batchProjectName.val($.i18n('files-import/batch-project-default-name'));
    }
    if (self._elmts.customElementsBody.children().length === 0) {
      addCustomElementRow();
    }
  }

  function addCustomElementRow(data) {
    var d = data || {};
    var $tr = $("<tr></tr>");
    $tr.append($("<td></td>").append(
        $("<input type='text' class='batch-el-name'/>").val(d.name || "")));
    $tr.append($("<td></td>").append(
        $("<input type='text' class='batch-el-key'/>").val(d.key || "")));
    var $action = $("<select class='batch-el-action'></select>");
    $action.append($("<option value='include'></option>").text($.i18n('files-import/batch-el-add')));
    $action.append($("<option value='exclude'></option>").text($.i18n('files-import/batch-el-exclude')));
    if (d.action === 'exclude') $action.val('exclude');
    $tr.append($("<td></td>").append($action));
    $tr.append($("<td></td>").append(
        $("<input type='text' class='batch-el-desc'/>").val(d.description || "")));
    var $del = $("<button type='button' class='button button-light batch-el-remove'>×</button>");
    $del.on('click', function () { $tr.remove(); });
    $tr.append($("<td></td>").append($del));
    self._elmts.customElementsBody.append($tr);
  }

  this._elmts.addRowButton.on('click', function () {
    addCustomElementRow();
  });

  function generateKey(name) {
    var sb = "";
    for (var i = 0; i < name.length; i++) {
      var c = name.charAt(i);
      if (/[a-zA-Z0-9]/.test(c)) {
        sb += c.toLowerCase();
      } else {
        sb += "_";
      }
    }
    if (sb.length === 0) sb = "custom_";
    if (/^[0-9]/.test(sb)) sb = "x" + sb;
    return sb;
  }

  function collectCustomElements() {
    var items = [];
    self._elmts.customElementsBody.find("tr").each(function () {
      var name = $(this).find(".batch-el-name").val().trim();
      var key = $(this).find(".batch-el-key").val().trim();
      var action = $(this).find(".batch-el-action").val();
      var description = $(this).find(".batch-el-desc").val().trim();
      if (!name) return;
      if (!key) key = generateKey(name);
      items.push({ name: name, key: key, action: action, description: description });
    });
    return items;
  }

  this._elmts.batchBackButton.on('click', function () {
    self._elmts.batchConfigPanel.hide();
    $("#directoryTreePanel").show();
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
      customElements: JSON.stringify(collectCustomElements())
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
    self._elmts.batchProgressPanel.show();
    if (batchPollTimer) window.clearInterval(batchPollTimer);
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
          var percent = data.totalPages > 0
              ? Math.min(100, Math.round(data.processedPages * 100 / data.totalPages)) : 0;
          self._elmts.progressBar.css("width", percent + "%");
          self._elmts.progressDetail.text(
              data.processedPages + " / " + data.totalPages
              + " · " + (data.currentUnit || "")
              + " · " + data.rowsAppended + " rows");
          self._elmts.progressMessage.text(data.message || "");
          if (data.status !== 'running') {
            window.clearInterval(batchPollTimer);
            batchPollTimer = null;
            self._elmts.batchCancelButton.hide();
            self._elmts.openProjectButton.show();
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
