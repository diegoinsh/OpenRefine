/*

Copyright 2025, OpenRefine contributors
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

function SheetTabView(project) {
    this._project = project;
    this._activeSheetId = null;
    this._initialize();
}

SheetTabView.prototype._initialize = function() {
    var self = this;
    
    this._toolPanel = $('#tool-panel');
    this._renderTabs();
};

SheetTabView.prototype._renderTabs = function() {
    var self = this;
    
    this._toolPanel.find('.sheet-tab-header').remove();
    
    var sheets = this._project.sheetDataMap;
    console.log('[SheetTabView] Rendering tabs, sheets:', sheets);
    var sheetCount = Object.keys(sheets).length;
    console.log('[SheetTabView] Sheet count:', sheetCount);
    
    if (sheetCount <= 1) {
        console.log('[SheetTabView] Skipping render, sheet count <= 1');
        return;
    }
    
    for (var sheetId in sheets) {
        if (sheets.hasOwnProperty(sheetId)) {
            var sheetData = sheets[sheetId];
            console.log('[SheetTabView] Creating tab for sheet:', sheetId, 'name:', sheetData.sheetName);
            var tab = $('<div></div>')
                .addClass('main-view-panel-tab-header')
                .addClass('sheet-tab-header')
                .attr('data-sheet-id', sheetId)
                .attr('href', '#sheet-' + sheetId)
                .text(sheetData.sheetName);
            
            if (sheetId === this._activeSheetId) {
                tab.addClass('active');
            }
            
            tab.on('click', function(e) {
                self._switchSheet($(this).attr('data-sheet-id'));
                e.preventDefault();
            });
            
            this._toolPanel.append(tab);
        }
    }
};

SheetTabView.prototype._switchSheet = function(sheetId) {
    console.log('[SheetTabView] _switchSheet called with sheetId:', sheetId);
    this._activeSheetId = sheetId;
    this._renderTabs();
    
    console.log('[SheetTabView] Dispatching sheetChanged event');
    var event = new CustomEvent('sheetChanged', {
        detail: { sheetId: sheetId }
    });
    window.dispatchEvent(event);
    console.log('[SheetTabView] Event dispatched');
};

SheetTabView.prototype.setActiveSheet = function(sheetId) {
    this._activeSheetId = sheetId;
    this._renderTabs();
};

SheetTabView.prototype.dispose = function() {
    this._toolPanel.find('.sheet-tab-header').remove();
};
