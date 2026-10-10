/*

Copyright 2025, OpenRefine.
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

// “拆分标记行”：先向后端查询当前视图内被标记的行数，
// 0 行 → 提示；1 行 → 直接执行；多行 → 弹确认框后再执行。
function SplitMarkedRowsDialog() {
    this._prepare();
}

SplitMarkedRowsDialog.prototype._prepare = function() {
    var self = this;

    Refine.wrapCSRF(function(csrfToken) {
        $.ajax({
            type: "POST",
            url: "command/core/split-marked-rows",
            dataType: "json",
            data: {
                project: theProject.id,
                csrf_token: csrfToken,
                dryRun: "true",
                engine: JSON.stringify(ui.browsingEngine.getJSON())
            },
            success: function(data) {
                var count = (data && data.count) ? data.count : 0;
                if (count <= 0) {
                    window.alert($.i18n('core-views/split-marked-rows-none'));
                } else if (count === 1) {
                    self._commit();
                } else {
                    self._showConfirm(count);
                }
            },
            error: function(xhr) {
                window.alert("Error: " + (xhr.responseText || xhr.statusText));
            }
        });
    });
};

SplitMarkedRowsDialog.prototype._showConfirm = function(count) {
    var self = this;
    var elmt = $(
        '<div class="dialog-frame" style="width: 420px;">' +
          '<div class="dialog-header"></div>' +
          '<div class="dialog-body"></div>' +
          '<div class="dialog-footer">' +
            '<button class="button" id="split-marked-rows-cancel"></button>' +
            '<button class="button button-primary" id="split-marked-rows-ok"></button>' +
          '</div>' +
        '</div>'
    );

    elmt.find('.dialog-header').text($.i18n('core-views/split-marked-rows'));
    elmt.find('.dialog-body').text($.i18n('core-views/split-marked-rows-confirm', count));
    elmt.find('#split-marked-rows-cancel')
            .text($.i18n('core-buttons/cancel'))
            .on('click', function() { self._dismiss(); });
    elmt.find('#split-marked-rows-ok')
            // 该 i18n 文案含 &nbsp; 实体，需用 html() 渲染（text() 会原样显示实体字符）
            .html($.i18n('core-buttons/ok'))
            .on('click', function() {
                self._dismiss();
                self._commit();
            });

    this._level = DialogSystem.showDialog(elmt);
};

SplitMarkedRowsDialog.prototype._dismiss = function() {
    if (this._level !== undefined) {
        DialogSystem.dismissUntil(this._level - 1);
    }
};

SplitMarkedRowsDialog.prototype._commit = function() {
    Refine.postCoreProcess(
        "split-marked-rows",
        null,
        null,
        {
            includeEngine: true,
            modelsChanged: true
        },
        {
            "onError": function(o) { window.alert("Error: " + o.message); }
        }
    );
};