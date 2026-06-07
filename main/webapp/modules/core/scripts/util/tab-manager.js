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
this software without this prior written permission.

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

var TabManager = {
  _tabGroups: {},
  _activeTab: null,
  _activeTabGroup: null,

  registerTabGroup: function(groupId, containerSelector) {
    this._tabGroups[groupId] = {
      container: $(containerSelector),
      tabs: []
    };
  },

  registerTab: function(groupId, tabElement, panelSelector, onClick, priority) {
    if (!this._tabGroups[groupId]) {
      console.error('[TabManager] Tab group not registered:', groupId);
      return;
    }

    var group = this._tabGroups[groupId];
    var tab = $(tabElement);
    var panel = panelSelector ? $(panelSelector) : null;

    group.tabs.push({
      tab: tab,
      panel: panel,
      onClick: onClick,
      priority: priority || 0
    });

    tab.on('click.tabmanager', function(e) {
      TabManager.switchTab(groupId, tab);
      if (onClick) {
        onClick(e);
      }
      e.preventDefault();
    });

    this._reorderTabs();
  },

  _reorderTabs: function() {
    var allTabs = [];
    for (var gId in this._tabGroups) {
      if (this._tabGroups.hasOwnProperty(gId)) {
        allTabs = allTabs.concat(this._tabGroups[gId].tabs);
      }
    }

    allTabs.sort(function(a, b) {
      return a.priority - b.priority;
    });

    var summaryBar = $('#summary-bar');
    var summaryBarParent = summaryBar.parent();

    allTabs.forEach(function(tabData) {
      if (!tabData.tab.is(summaryBar)) {
        tabData.tab.appendTo(tabData.tab.parent());
      }
    });

    summaryBar.prependTo(summaryBarParent);

    console.log('[TabManager] Reordered tabs');
  },

  switchTab: function(groupId, tabElement) {
    if (!this._tabGroups[groupId]) {
      console.error('[TabManager] Tab group not registered:', groupId);
      return;
    }

    var group = this._tabGroups[groupId];
    var targetTab = $(tabElement);

    var targetTabData = group.tabs.find(function(t) {
      return t.tab.is(targetTab);
    });

    if (!targetTabData) {
      console.error('[TabManager] Tab not found in group');
      return;
    }

    this._activeTab = targetTab;
    this._activeTabGroup = groupId;

    var allTabs = [];
    for (var gId in this._tabGroups) {
      if (this._tabGroups.hasOwnProperty(gId)) {
        allTabs = allTabs.concat(this._tabGroups[gId].tabs);
      }
    }

    allTabs.forEach(function(tabData) {
      tabData.tab.removeClass('active');
      if (tabData.panel) {
        tabData.panel.hide();
      }
    });

    targetTabData.tab.addClass('active');
    if (targetTabData.panel) {
      targetTabData.panel.show();
    }

    console.log('[TabManager] Switched to tab in group:', groupId);
  },

  getActiveTab: function() {
    return this._activeTab;
  },

  getActiveTabGroup: function() {
    return this._activeTabGroup;
  },

  unregisterTabGroup: function(groupId) {
    if (this._tabGroups[groupId]) {
      var group = this._tabGroups[groupId];
      group.tabs.forEach(function(tabData) {
        tabData.tab.off('click.tabmanager');
      });
      delete this._tabGroups[groupId];
    }
  }
};
