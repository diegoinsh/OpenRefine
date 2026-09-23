/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
 * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 */

function DataTableCellUI(dataTableView, cell, rowIndex, cellIndex, td) {
  this._dataTableView = dataTableView;
  this._cell = cell;
  this._rowIndex = rowIndex;
  this._cellIndex = cellIndex;
  this._td = td;
  this._focusBeforeEdit;

  // 反向索引：Tab 切换单元格时由 DOM 反查对应的单元格 UI
  td.dataTableCellUI = this;

  this._render();
}

var reconMatchSilimilarCellsByDefault = true;

DataTableCellUI.previewMatchedCells = true;

(function() {
   
   $.ajax({
     url: "command/core/get-preference?" + $.param({
        name: "cell-ui.previewMatchedCells"
     }),
    success: function(data) {
      if (data.value && data.value == "false") {
        DataTableCellUI.previewMatchedCells = false;
     }
   },
   dataType: "json",
  });
})();

DataTableCellUI.prototype._render = function() {
  var self = this;
  var cell = this._cell;

  var divContent = document.createElement('div');
  divContent.className = 'data-table-cell-content';

  var editLink = document.createElement('button');
  editLink.className = 'data-table-cell-edit';
  editLink.setAttribute('title', $.i18n('core-views/edit-cell'));
  editLink.addEventListener('click', function() {
    self._startEdit(this);
    self._focusBeforeEdit = editLink;
  });

  $(this._td).empty()
  .off()
  .on('mouseenter',function() { editLink.style.opacity = "1" })
  .on('mouseleave',function() { if (!$(editLink).is(":focus")) editLink.style.opacity = "0" })
  .on('focusin',function() { editLink.style.opacity = "1" })
  .on('focusout',function() { editLink.style.opacity = "0" });

  var renderedCell = undefined;
  for (let record of CellRendererRegistry.renderers) {
    try {
      renderedCell = record.renderer.render(this._rowIndex, this._cellIndex, cell, this);
    } catch (e) {
      continue;
    }
    if (renderedCell) {
      break;
    }
  }

  if (renderedCell) {
    divContent.appendChild(renderedCell);
  }
  divContent.appendChild(editLink).appendChild(document.createTextNode($.i18n('core-facets/edit')));

  this._td.appendChild(divContent);

  $(this._td).addClass('data-table-cell')
  .off('dblclick.inlineEdit')
  .on('dblclick.inlineEdit', function(e) {
    if ($(e.target).is('a, button, input, select, textarea')) {
      return;
    }
    // 提取任务运行期间项目只读，不允许就地编辑
    if ($(document.body).hasClass('extraction-readonly')) {
      return;
    }
    e.preventDefault();
    self._startInlineEdit();
    // 同步打开资源预览浮窗并默认进入 OCR 模式，可在浮窗内拉框识别后写回单元格
    if (typeof FileViewPanel !== 'undefined' && FileViewPanel.openForOcr) {
      FileViewPanel.openForOcr(self._rowIndex, self._cellIndex);
    }
  });
};

/** 当前处于就地编辑状态的单元格编辑器，供 OCR 等外部功能写入内容 */
DataTableCellUI.activeInlineEditor = null;

/**
 * 单元格提交会写入一条历史操作，提交后自动把左侧面板切到「历史记录」页，
 * 便于立即核对或撤销本次改动。面板未就绪（如扩展独立使用）时静默跳过。
 */
DataTableCellUI._showHistoryTab = function() {
  if (typeof ui === 'undefined' || !ui || !ui.leftPanelTabs || !ui.leftPanelTabs.length) {
    return;
  }
  var index = ui.leftPanelTabs
      .find('a[href="#refine-tabs-history"]')
      .closest('li')
      .index();
  if (index >= 0) {
    ui.leftPanelTabs.tabs('option', 'active', index);
  }
};

DataTableCellUI.prototype._startInlineEdit = function() {
  var self = this;
  var cell = this._cell;

  if (this._inlineEditing) {
    return;
  }

  var originalContent = (!cell || !("v" in cell) || cell.v === null) ? "" : String(cell.v);
  var dataType = (cell !== null && "t" in cell && cell.t != null) ? cell.t
      : (typeof (cell ? cell.v : null) === 'string' ? 'text' : typeof (cell ? cell.v : null));

  // 日期等复杂类型沿用原生编辑弹窗，避免在此重复实现解析逻辑
  if (dataType === 'date' || dataType === 'object' || dataType === 'undefined') {
    this._startEdit(this._td);
    return;
  }

  this._inlineEditing = true;

  var $td = $(this._td);
  var $holder = $td.find('.data-table-cell-content');

  // 编辑前先量出单元格内容区高度：文本框按此精确复刻，避免清空内容后行高塌陷。
  // 宽度不在此处固定，交给 CSS 的 100%，这样折叠左面板等导致列宽变化时仍与单元格边框重合
  var tdStyle = window.getComputedStyle(this._td);
  var editorHeight = this._td.clientHeight
      - parseFloat(tdStyle.paddingTop) - parseFloat(tdStyle.paddingBottom);

  var textarea = document.createElement('textarea');
  textarea.className = 'data-table-cell-inline-editor';
  textarea.value = originalContent;
  if (editorHeight > 0) {
    textarea.style.height = editorHeight + 'px';
  }

  $holder.empty().append(textarea);
  $td.addClass('data-table-cell-editing');

  var finished = false;

  var cleanup = function() {
    finished = true;
    self._inlineEditing = false;
    DataTableCellUI.activeInlineEditor = null;
    $(document).off('mousedown.inlineEditOutside');
  };

  var restore = function() {
    cleanup();
    $td.removeClass('data-table-cell-editing');
    self._render();
  };

  var commit = function(onCommitted) {
    if (finished) {
      return;
    }
    var value = textarea.value;
    if (dataType === 'number') {
      value = parseFloat(value);
      if (isNaN(value)) {
        alert($.i18n('core-views/not-valid-number'));
        textarea.focus();
        return;
      }
    } else if (dataType === 'boolean') {
      value = (value === 'true');
    }

    // 内容未变则不提交，避免在历史记录里留下无实际改动的操作
    var unchanged = (dataType === 'number' || dataType === 'boolean')
        ? value === cell.v
        : String(value) === originalContent;
    if (unchanged) {
      cleanup();
      $td.removeClass('data-table-cell-editing');
      self._render();
      if (onCommitted) {
        onCommitted();
      }
      return;
    }

    cleanup();
    $td.removeClass('data-table-cell-editing');

    Refine.postCoreProcess(
      "edit-one-cell",
      {},
      {
        row: self._rowIndex,
        cell: self._cellIndex,
        value: value,
        type: dataType
      },
      {},
      {
        onDone: function(o) {
          if (o.cell.r) {
            o.cell.r = o.pool.recons[o.cell.r];
          }
          self._cell = o.cell;
          self._dataTableView._updateCell(self._rowIndex, self._cellIndex, self._cell);
          self._render();
          // 写入后按新内容重排该表列宽：长文本列会被撑开（只增不减，用户拖过的列不参与）
          if (typeof self._dataTableView._autoFitColumnWidths === 'function') {
            self._dataTableView._autoFitColumnWidths(
                $(self._td).closest('table.data-table').find('colgroup'));
          }
          // 本次提交已写入历史，切到左侧「历史记录」页便于立即核对或撤销
          DataTableCellUI._showHistoryTab();
          // 提交完成（单元格 DOM 已重绘）后再切换焦点，避免被本次重绘打断
          if (onCommitted) {
            onCommitted();
          }
        },
        onError: function() {
          self._render();
        }
      }
    );
  };

  textarea.addEventListener('keydown', function(evt) {
    if (evt.key === 'Enter' && !evt.shiftKey && !evt.ctrlKey && !evt.altKey) {
      evt.preventDefault();
      commit();
    } else if (evt.key === 'Escape') {
      evt.preventDefault();
      restore();
    } else if (evt.key === 'Tab') {
      // Tab：提交当前单元格，并把就地编辑态推进到本行下一个单元格
      // Shift+Tab：改为沿本列竖向推进，到下一行的同一个单元格
      evt.preventDefault();
      var moveDown = evt.shiftKey;
      commit(function() {
        if (moveDown) {
          self._focusNextRowInlineEdit();
        } else {
          self._focusNextCellInlineEdit();
        }
      });
    }
  });

  // 单击以下区域时不结束就地编辑，也不让文本框失焦：
  //  - file-view-panel：资源预览浮窗，拉框 OCR 的结果要写回正在编辑的单元格
  //  - .menu-system / .visibility-panel-button：菜单与左右面板显隐按钮
  //  - 任意滚动条：编辑中常要横向滚动查看被挡住的列
  var KEEP_FOCUS_SELECTOR = '#file-view-panel, .menu-system, .visibility-panel-button';

  /**
   * 点击是否落在滚动条上。滚动条属于滚动容器自身，因此命中时 event.target 就是该容器，
   * 且坐标落在其内容区之外的那一圈。
   */
  var isOnScrollbar = function(e) {
    var el = e.target;
    if (!el || el.nodeType !== 1) {
      return false;
    }
    var style = window.getComputedStyle(el);
    var borderLeft = parseFloat(style.borderLeftWidth) || 0;
    var borderTop = parseFloat(style.borderTopWidth) || 0;
    var borderX = borderLeft + (parseFloat(style.borderRightWidth) || 0);
    var borderY = borderTop + (parseFloat(style.borderBottomWidth) || 0);
    var scrollbarWidth = el.offsetWidth - el.clientWidth - borderX;
    var scrollbarHeight = el.offsetHeight - el.clientHeight - borderY;
    if (scrollbarWidth <= 0 && scrollbarHeight <= 0) {
      return false;
    }
    var rect = el.getBoundingClientRect();
    return (scrollbarHeight > 0 && e.clientY >= rect.top + borderTop + el.clientHeight)
        || (scrollbarWidth > 0 && e.clientX >= rect.left + borderLeft + el.clientWidth);
  };

  $(document).on('mousedown.inlineEditOutside', function(e) {
    if ($(e.target).closest($td).length) {
      return;
    }
    // 白名单内只阻断原生的焦点转移，不提交；click 仍会正常派发到目标元素，
    // 因此浮窗拉框、面板折叠按钮等功能不受影响
    if ($(e.target).closest(KEEP_FOCUS_SELECTOR).length || isOnScrollbar(e)) {
      e.preventDefault();
      return;
    }
    commit();
  });

  DataTableCellUI.activeInlineEditor = {
    textarea: textarea,
    commit: commit,
    cancel: restore,
    /** 有选区则替换选区，否则插入到光标之后 */
    insertText: function(text) {
      var el = textarea;
      var start = el.selectionStart;
      var end = el.selectionEnd;
      if (start === null || start === undefined) {
        start = end = el.value.length;
      }
      el.value = el.value.substring(0, start) + text + el.value.substring(end);
      var caret = start + text.length;
      el.selectionStart = caret;
      el.selectionEnd = caret;
      el.focus();
    }
  };

  textarea.focus();
  textarea.select();
};

/**
 * 就地编辑按 Tab 时：进入本行下一个单元格的编辑态（默认全选）。
 * 若目标单元格是提取要素格，同步把资源预览浮窗定位到其取值所在页，并保持 OCR 模式。
 */
DataTableCellUI.prototype._focusNextCellInlineEdit = function() {
  var tr = this._td.parentNode;
  if (!tr) {
    return;
  }
  var start = Array.prototype.indexOf.call(tr.cells, this._td);
  for (var i = start + 1; i < tr.cells.length; i++) {
    var nextTd = tr.cells[i];
    // 折叠列与非数据格（星标/标记/行号）没有单元格 UI，跳过
    var nextUI = nextTd.dataTableCellUI;
    if (!nextUI) {
      continue;
    }
    nextUI._startInlineEdit();
    if (typeof FileViewPanel !== 'undefined' && FileViewPanel.followCell) {
      FileViewPanel.followCell(nextUI._rowIndex, nextUI._cellIndex);
    }
    return;
  }
};

/**
 * 就地编辑按 Shift+Tab 时：沿本列竖向推进，进入下一行同一个单元格的编辑态。
 * 表格是整表渲染的，按行序向下找到该列即可；后续行为与横向 Tab 完全一致。
 */
DataTableCellUI.prototype._focusNextRowInlineEdit = function() {
  var tr = this._td.parentNode;
  if (!tr) {
    return;
  }
  for (var nextTr = tr.nextElementSibling; nextTr; nextTr = nextTr.nextElementSibling) {
    for (var i = 0; i < nextTr.cells.length; i++) {
      var nextUI = nextTr.cells[i].dataTableCellUI;
      // 折叠列与非数据格（星标/标记/行号）没有单元格 UI，跳过
      if (!nextUI || nextUI._cellIndex !== this._cellIndex) {
        continue;
      }
      nextUI._startInlineEdit();
      if (typeof FileViewPanel !== 'undefined' && FileViewPanel.followCell) {
        FileViewPanel.followCell(nextUI._rowIndex, nextUI._cellIndex);
      }
      return;
    }
  }
};

DataTableCellUI.prototype._startEdit = function(elmt) {
  self = this;

  var originalContent = !this._cell || ("v" in this._cell && this._cell.v === null) ? "" : this._cell.v;

  var menu = MenuSystem.createMenu().addClass("data-table-cell-editor").width("400px");
  menu.html(DOM.loadHTML("core", "scripts/views/data-table/cell-editor.html"));
  var elmts = DOM.bind(menu);

  elmts.or_views_dataType.html($.i18n('core-views/data-type'));
  elmts.textarea.attr('aria-label',$.i18n('core-views/cell-content'));
  elmts.or_views_text.html($.i18n('core-views/text'));
  elmts.or_views_number.html($.i18n('core-views/number'));
  elmts.or_views_boolean.html($.i18n('core-views/boolean'));
  elmts.or_views_date.html($.i18n('core-views/date'));
  elmts.okButton.html($.i18n('core-buttons/apply'));
  elmts.or_views_enter.html($.i18n('core-buttons/enter'));
  elmts.okallButton.html($.i18n('core-buttons/apply-to-all'));
  elmts.or_views_ctrlEnter.html($.i18n('core-views/ctrl-enter'));
  elmts.cancelButton.html($.i18n('core-buttons/cancel'));
  elmts.or_views_esc.html($.i18n('core-buttons/esc'));

  var cellDataType = typeof originalContent === "string" ? "text" : typeof originalContent;
  cellDataType = (this._cell !== null && "t" in this._cell && this._cell.t !=  null) ? this._cell.t : cellDataType;
  elmts.typeSelect.val(cellDataType);

  elmts.typeSelect.on('change', function() {
    var newType = elmts.typeSelect.val();
    if (newType === "date") {
      elmts.cell_help_text.html($.i18n('core-views/cell-edit-date-help'));
      $(elmts.cell_help_text).show();
    } else {
      $(elmts.cell_help_text).hide();
    }
  });

  if (cellDataType === "date") {
    elmts.cell_help_text.html($.i18n('core-views/cell-edit-date-help'));
    $(elmts.cell_help_text).show();
  }

  MenuSystem.showMenu(menu, function(){});
  MenuSystem.positionMenuLeftRight(menu, $(this._td));

  var commit = function() {
    var type = elmts.typeSelect[0].value;

    var applyOthers = 0;
    if (this === elmts.okallButton[0]) {
      applyOthers = 1;
    }

    var text = elmts.textarea[0].value;
    var value = text;

    if (type == "number") {
      value = parseFloat(text);
      if (isNaN(value)) {
        alert($.i18n('core-views/not-valid-number'));
        return;
      }
    } else if (type == "boolean") {
      value = ("true" == text);
    } else if (type == "date") {
      value = Date.parse(text);
      if (!value) {
        alert($.i18n('core-views/not-valid-date'));
        return;
      }
      value = new Date(value).toISOString();
    }

    self._focusBeforeEdit.focus();
    MenuSystem.dismissAll();

    if (applyOthers) {
      Refine.postCoreProcess(
        "mass-edit",
        {},
        {
          columnName: Refine.cellIndexToColumn(self._cellIndex).name,
          expression: "value",
          edits: JSON.stringify([{
            from: [ originalContent ],
            to: value,
            type: type
          }])
        },
        { cellsChanged: true },
        {
          onDone: function() {
            DataTableCellUI._showHistoryTab();
          }
        }
      );
    } else {
      Refine.postCoreProcess(
        "edit-one-cell", 
        {},
        {
          row: self._rowIndex,
          cell: self._cellIndex,
          value: value,
          type: type
        },
        {},
        {
          onDone: function(o) {
            if (o.cell.r) {
              o.cell.r = o.pool.recons[o.cell.r];
            }

            self._focusBeforeEdit.focus();
            MenuSystem.dismissAll();
            self._cell = o.cell;
            self._dataTableView._updateCell(self._rowIndex, self._cellIndex, self._cell);
            self._render();
            DataTableCellUI._showHistoryTab();
          }
        }
      );
    }
  };

  elmts.okButton.on('click',commit);
  elmts.okallButton.on('click',commit);
  elmts.textarea
  .text(originalContent)
  .on('keydown',function(evt) {
    if (!evt.shiftKey || elmts.textarea.is(':focus')) {
      if (evt.key == "Enter") {
        if (evt.ctrlKey) {
          elmts.okallButton.trigger('click');
        } else {
          elmts.okButton.trigger('click');
        }
      } else if (evt.key == "Escape") {
        MenuSystem.dismissAll();
      }
    }
  })
  .trigger('select')
  .trigger('focus');

  setInitialHeightTextArea(elmts.textarea[0]);

  elmts.cancelButton.on('click',function() {
    MenuSystem.dismissAll();
  });
};
