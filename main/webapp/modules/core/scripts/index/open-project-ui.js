/*

Copyright 2011, Google Inc.
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

Refine.OpenProjectUI = function(elmt) {
  var self = this;

  elmt.html(DOM.loadHTML("core", "scripts/index/open-project-ui.html"));

  this._elmt = elmt;
  this._elmts = DOM.bind(elmt);

  // 分页状态
  this._currentPage = 1;
  this._pageSize = parseInt(localStorage.getItem('openProject.pageSize'), 10) || 50; // 从 localStorage 读取，默认50
  this._allProjects = []; // 缓存所有项目数据

  if (Host.isLocalhost()) {
    $('#projects-workspace-open').text($.i18n('core-index-open/browse'));
    $('#projects-workspace-open').on('click',function() {
      Refine.postCSRF(
        "command/core/open-workspace-dir",
        {},
        function (data) {
          if (data.code != "ok" && "message" in data) {
            alert(data.message);
          }
        },
        "json"
      );
    });
  } else {
    $('#projects-workspace-open').hide();
  }

  // 添加每页数量控制
  this._buildPageSizeControl();

  Refine.TagsManager.allProjectTags = [];
  this._buildTagsAndFetchProjects();
};

/**
 * 系统内置标签（与能力平台「数据保留 → 按标签保留」一致）：
 * 短期 = 7 天后可回收；保留 / 重要 = 永久保留。
 * name 是实际写入项目元数据的标签名，hint 仅用于展示。
 */
Refine.OpenProjectUI.BUILTIN_TAGS = [
  { name: '短期', hint: '7天' },
  { name: '保留', hint: '永久' },
  { name: '重要', hint: '永久' }
];

/**
 * 构建每页数量控制
 */
Refine.OpenProjectUI.prototype._buildPageSizeControl = function() {
  var self = this;
  var controlsDiv = this._elmts.workspaceControls;

  var pageSizeControl = $('<span class="page-size-control"></span>');
  $('<label>').text($.i18n('core-index-open/page-size') + ': ').appendTo(pageSizeControl);

  var select = $('<select id="page-size-select">')
    .append('<option value="20">20</option>')
    .append('<option value="50">50</option>')
    .append('<option value="100">100</option>')
    .append('<option value="200">200</option>')
    .val(this._pageSize) // 设置为当前保存的值
    .on('change', function() {
      self._pageSize = parseInt($(this).val(), 10);
      localStorage.setItem('openProject.pageSize', self._pageSize); // 保存到 localStorage
      self._currentPage = 1;
      self._renderProjectsWithPagination();
    })
    .appendTo(pageSizeControl);

  controlsDiv.append(pageSizeControl);
};

Refine.OpenProjectUI.prototype._fetchProjects = function() {
  var self = this;
  $.getJSON(
      "command/core/get-all-project-metadata",
      null,
      function(data) {
        self._renderProjects(data);
        self.resize(); // other version of this function excludes this resize
      },
      "json"
  );
};

Refine.OpenProjectUI.prototype._buildTagsAndFetchProjects = function() {
    this._buildProjectSearchPanel();
    Refine.OpenProjectUI.refreshTagsListPanel();
    this._fetchProjects();
    var tag = new URLSearchParams(window.location.search).get('tag');
    if (!tag) tag = '';
    Refine.OpenProjectUI._filterTags(tag);
};

Refine.OpenProjectUI.prototype._buildProjectSearchPanel = function(){
  const self = this;
  self._openSearchInput();
  self._searchInput();
}

Refine.OpenProjectUI.refreshTagsListPanel = function() {
    var allTags = Refine.TagsManager._getAllProjectTags();

    var ul = $("#tagsUl").empty();

    // Add 'all' menu item
    var li = $('<li/>').appendTo(ul);
    var a = $('<a/>').attr('href', '?tag=#open-project').text($.i18n("core-views/all")).appendTo(li);
    a.on('click', function(e) {
      e.preventDefault();
      Refine.OpenProjectUI._filterTags('');
    });

    $.each(allTags, function(i) {
      var li = $('<li/>').appendTo(ul);
      var a = $('<a/>').attr('href', '?tag=' + allTags[i] + '#open-project').text(allTags[i])
                      .appendTo(li);
      a.on('click', function(elm) {
        elm.preventDefault();
        Refine.OpenProjectUI._filterTags(allTags[i]);
      });
    });
};

Refine.OpenProjectUI._filterTags = function(tag) {
  // this function can run even if the open project UI is not visible to the user, so only update the URL if it is
  if (window.hash === "#open-project") window.history.pushState("", "", "?tag=" + tag + "#open-project");

  $('#tagsUl').children().each(function() {
    $(this).removeClass('current');
  });
  $('#tagsUl').find('a[href="?tag=' + tag + '#open-project"]').parent().addClass('current');

  $("#tableBody").children().each(function() {
    if ($(this).hasClass(tag) || tag === "") {
      $(this).show();
    } else {
      $(this).hide();
    }
  });
};

Refine.OpenProjectUI.prototype._openSearchInput = function() {
  const icon = $('#search-icon');
  const input = $('#search-input');
  input.attr("placeholder", $.i18n("core-index-open/search-placeholder"));
  icon.click(function () {
    if (input.is(':hidden')) {
      // $("#tagsUl").hide();
      $("#search-input").show();
      icon.addClass("magnifying-glass-open");
      input.show();
      input.focus();
    } else {
      input.hide();
      input.val('');
      icon.removeClass("magnifying-glass-open");
      $("#tableBody").filterListSearch("");
      $("#search-input").hide();
      // $("#tagsUl").show();
    }
  });
};

Refine.OpenProjectUI.prototype._searchInput = function() {
  const input = $('#search-input');
  //setup before functions
  let typingTimer;                //timer identifier
  const doneTypingInterval = 500;  //time in ms
  // search when done typing interval is over when not typing anymore

  //on keyup, start the countdown
  input.on('keyup', function (e) {
    clearTimeout(typingTimer);
    if (e.key === 'Enter') {
      e.preventDefault();
      doneTyping();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      $('#search-icon').click(); // close search box on ESC
    } else {
      typingTimer = setTimeout(doneTyping, doneTypingInterval);
    }
  });

  input.on('paste', function (e) {
    clearTimeout(typingTimer);
    doneTyping();
  });


    //on keydown, clear the countdown
  input.on('keydown', function () {
    clearTimeout(typingTimer);
  });

  //user is "finished typing," do something
  function doneTyping () {
    const text = input.val();
    // get the text, get back the projects that contains the text in the metadata
    $("#tableBody").filterListSearch(text);
  }
};

// FIXME: This is overwriting an earlier function definition
Refine.OpenProjectUI.prototype._fetchProjects = function() {
    var self = this;
    $.ajax({
            type : 'GET',
            url : "command/core/get-all-project-metadata",
            dataType : 'json',
            success : function(data) {
                    self._renderProjects(data);
            },
            data : {},
            async : false
    });
};

Refine.OpenProjectUI.prototype._renderProjects = function(data) {
  const options  = { dateStyle: 'medium', timeStyle: 'medium' };
  const dateFormatter = new Intl.DateTimeFormat(Refine.userLang || navigator.language, options);
  var self = this;
  var projects = [];
  for (var n in data.projects) {
    if (data.projects.hasOwnProperty(n)) {
      var project = data.projects[n];
      if (project == null) {
          console.log('Project '+n+' is null. skipping...');
          continue;
      }
      project.id = n;
      if (!project.name) {
         console.log('Project '+project.id+' name is not set. skipping...');
         continue;
      }

      // project.modified is ISO 8601 format string
      const date = new Date(project.modified);
      project.date = dateFormatter.format(date);

      if (typeof project.userMetadata !== "undefined")  {
          for (var m in data.customMetadataColumns) {
              var found = false;
              for(var i = 0; i < project.userMetadata.length; i++) {
                  if (project.userMetadata[i].name === data.customMetadataColumns[m].name) {
                      found = true;
                      break;
                  }
                  if (!found) {
                      project.userMetadata.push({
                          name: data.customMetadataColumns[m].name,
                          display: data.customMetadataColumns[m].display,
                          value: ""
                      });
                  }
              }
          }
      }
    }
    projects.push(project);
  }

  // 按修改时间倒序排序
  projects.sort(function(a, b) {
    var ma = a.modified ? new Date(a.modified).getTime() : 0;
    var mb = b.modified ? new Date(b.modified).getTime() : 0;
    return mb - ma;
  });

  // 缓存数据和元信息用于分页
  this._allProjects = projects;
  this._customMetadataColumns = data.customMetadataColumns;
  this._currentPage = 1;

  this._renderProjectsWithPagination();
};

/**
 * 根据分页参数渲染项目列表
 */
Refine.OpenProjectUI.prototype._renderProjectsWithPagination = function() {
  var self = this;
  var projects = this._allProjects || [];
  var data = { customMetadataColumns: this._customMetadataColumns || {} };

  var container = self._elmts.projectList.empty();
  if (!projects || !projects.length) {
    $("#no-project-message").clone().show().appendTo(container);
  } else {
    // 计算分页
    var totalItems = projects.length;
    var totalPages = Math.ceil(totalItems / this._pageSize);
    if (this._currentPage > totalPages) this._currentPage = totalPages;
    if (this._currentPage < 1) this._currentPage = 1;
    var startIndex = (this._currentPage - 1) * this._pageSize;
    var endIndex = Math.min(startIndex + this._pageSize, totalItems);
    var pagedProjects = projects.slice(startIndex, endIndex);

    // 渲染分页控件
    this._renderPagination(container, totalItems, totalPages);

    var projectsUl = $("<ul/>").attr('id', 'projectsUl').appendTo(container);

    var table = $(
      '<table class="tablesorter-blue list-table"><thead><tr>' +
      '<th></th>' +
      '<th>'+$.i18n('core-index-open/last-mod')+'</th>' +
      '<th>'+$.i18n('core-index-open/name')+'</th>' +
      '<th>'+$.i18n('core-index-open/tags')+'</th>' + 
      '<th>'+$.i18n('core-index-open/creator')+'</th>' +
      '<th>'+$.i18n('core-index-open/subject')+'</th>' +
      '<th>'+$.i18n('core-index-open/description')+'</th>' +
      '<th>'+$.i18n('core-index-open/row-count')+'</th>' + 
      (function() {
          var htmlDisplay = "";
          for (var n in data.customMetadataColumns) {
            if (data.customMetadataColumns[n].display) {
              htmlDisplay += '<th>'+ data.customMetadataColumns[n].name + '</th>';
            }
          }
          
          return htmlDisplay;
      })() +     
      '</tr></thead><tbody id="tableBody"></tbody></table>  <div id="no-results-message">'
      +$.i18n('core-index-open/no-results-message')+'</div>'
    ).appendTo(projectsUl)[0];

    var renderProject = function(project) {
      var tr = table.getElementsByTagName('tbody')[0].insertRow(table.rows.length - 1);
      tr.className = "project";

      var deleteLink = $('<a></a>')
      .addClass("delete-project")
      .attr("title",$.i18n('core-index-open/del-title'))
      .attr("href","")
      .html("<img src='images/close-ry.png' />")
      .on('click',function() {
        if (window.confirm($.i18n('core-index-open/del-body', project.name))) {
          Refine.postCSRF(
            "command/core/delete-project",
            { "project" : project.id },
            function (data) {
              if (data && typeof data.code != 'undefined' && data.code == "ok") {
                Refine.TagsManager.allProjectTags = [];
                self._buildTagsAndFetchProjects();
              }
            },
            "json"
          );
        }
        return false;
      }).appendTo(
        $(tr.insertCell(tr.cells.length))
      );
      
      $('<div></div>')
      .html('<span style="display:none">' + project.modified + '</span>' + project.date)
      .addClass("last-modified")
      .appendTo($(tr.insertCell(tr.cells.length)));
      
      var nameLink = $('<a></a>')
      .addClass("project-name")
      .addClass("searchable")
      .text(project.name)
      .attr("href", "project?project=" + project.id)
      .appendTo($(tr.insertCell(tr.cells.length)));
      
    var tagsCell = $(tr.insertCell(tr.cells.length));
    tagsCell.addClass("project-tags-cell");
    var tags = (project.tags || []).slice();
    var appliedTagClasses = [];

    var renderTags = function() {
      tagsCell.empty();
      appliedTagClasses.map(function(tag){ $(tr).removeClass(tag); });
      appliedTagClasses = [];
      tags.map(function(tag){
          $("<span/>")
          .addClass("project-tag")
          .attr("data-tag-name", tag)
          .addClass("searchable")
          .text(tag)
          .appendTo(tagsCell);
          $(tr).addClass(tag);
          appliedTagClasses.push(tag);
      });
      $("<a/>")
      .addClass("tag-edit")
      .attr("href", "javascript:{}")
      .attr("title", "编辑标签")
      .text("＋")
      .on('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        openTagEditor();
        return false;
      })
      .appendTo(tagsCell);
    };

    /**
     * 标签下拉：按「系统内置标签 / 已有标签」两组横向展开，
     * 可勾选已有标签，也可输入自定义标签。
     */
    var openTagEditor = function() {
      $('.tag-editor-pop').remove();
      var builtin = Refine.OpenProjectUI.BUILTIN_TAGS || [];
      var builtinNames = builtin.map(function(t){ return t.name; });
      var all = (Refine.TagsManager && Refine.TagsManager._getAllProjectTags)
        ? Refine.TagsManager._getAllProjectTags() : [];
      var candidates = {};
      // 内置标签单独成组展示，不再混入「已有标签」
      all.concat(tags).map(function(t){
        if (builtinNames.indexOf(t) < 0) { candidates[t] = true; }
      });

      var pop = $('<div class="tag-editor-pop"></div>');

      var builtinGroup = $('<div class="tag-editor-group"></div>').appendTo(pop);
      $('<div class="tag-editor-group-title"></div>').text('系统内置标签').appendTo(builtinGroup);
      var builtinOpts = $('<div class="tag-editor-opts"></div>').appendTo(builtinGroup);

      var existingGroup = $('<div class="tag-editor-group"></div>').appendTo(pop);
      $('<div class="tag-editor-group-title"></div>').text('已有标签').appendTo(existingGroup);
      var existingOpts = $('<div class="tag-editor-opts"></div>').appendTo(existingGroup);

      var addRow = $('<div class="tag-editor-add"></div>').appendTo(pop);
      var input = $('<input type="text" placeholder="新增标签">').appendTo(addRow);
      var addBtn = $('<button type="button" class="button">添加</button>').appendTo(addRow);
      var actions = $('<div class="tag-editor-actions"></div>').appendTo(pop);

      /** checked 缺省按项目现有标签判断；hint 显示在标签名后的括号里。 */
      var addChip = function(container, name, hint, checked) {
        if (checked === undefined) { checked = tags.indexOf(name) >= 0; }
        var lab = $('<label class="tag-opt"></label>').attr('data-tag', name);
        $('<input type="checkbox">').attr('value', name).prop('checked', !!checked).prependTo(lab);
        $('<span></span>').text(name).appendTo(lab);
        if (hint) { $('<em class="tag-opt-hint"></em>').text('(' + hint + ')').appendTo(lab); }
        container.append(lab);
      };

      builtin.map(function(t){ addChip(builtinOpts, t.name, t.hint); });

      var existingNames = Object.keys(candidates).sort(function(a, b) {
        return a.toLowerCase().localeCompare(b.toLowerCase());
      });
      if (!existingNames.length) {
        $('<span class="tag-editor-empty"></span>').text('暂无其它标签').appendTo(existingOpts);
      }
      existingNames.map(function(t){ addChip(existingOpts, t); });

      addBtn.on('click', function(e) {
        e.preventDefault();
        var v = $.trim(input.val());
        if (!v) { return false; }
        var exists = pop.find('label').filter(function(){ return $(this).attr('data-tag') === v; });
        if (exists.length) {
          exists.find('input').prop('checked', true);
        } else {
          existingOpts.find('.tag-editor-empty').remove();
          addChip(existingOpts, v, '', true);
        }
        input.val('');
        return false;
      });

      $('<button type="button" class="button button-primary">保存</button>')
        .appendTo(actions)
        .on('click', function(e) {
          e.preventDefault();
          var newTags = [];
          pop.find('input:checked').each(function(){ newTags.push($(this).val()); });
          var oldStr = tags.join(',');
          var newStr = newTags.join(',');
          pop.remove();
          if (oldStr === newStr) { return false; }
          Refine.postCSRF(
            "command/core/set-project-tags",
            { "project": project.id, "old": oldStr, "new": newStr },
            function(data) {
              if (data && data.code === "ok") {
                tags = newTags;
                project.tags = newTags;
                renderTags();
                Refine.TagsManager.allProjectTags = [];
                if (Refine.OpenProjectUI.refreshTagsListPanel) {
                  Refine.OpenProjectUI.refreshTagsListPanel();
                }
              } else {
                window.alert((data && data.message) || "保存标签失败");
              }
            },
            "json"
          );
          return false;
        });

      $('<button type="button" class="button">取消</button>')
        .appendTo(actions)
        .on('click', function(e) {
          e.preventDefault();
          e.stopPropagation();
          pop.remove();
          return false;
        });

      pop.on('click', function(e){ e.stopPropagation(); });
      input.on('keydown', function(e) {
        if (e.key === 'Enter') { e.preventDefault(); addBtn.click(); }
      });
      tagsCell.append(pop);
      input.focus();
    };

    renderTags();
    
    
    var appendMetaField = function(data) {
        $('<div></div>')
        .addClass("searchable")
        .html(data)
        .appendTo($(tr.insertCell(tr.cells.length)));
    };
    
    appendMetaField(project.creator);
    appendMetaField(project.subject);
    appendMetaField(project.description, '20%');
    appendMetaField(project.rowCount);
    
    var data = project.userMetadata;
    for(var i in data)
    {
         if (data[i].display === true) {
             appendMetaField(data[i].value);
         }
    }
        

      };

    for (var i = 0; i < pagedProjects.length; i++) {
      renderProject(pagedProjects[i]);
    }

    $(table).tablesorter({
        headers : {
            0: { sorter: false },
            1: { sorter: "text" }
        },
        sortList: [[1,1]],
        widthFixed: false
    });
    self._addTagFilter();
  }
};

/**
 * 渲染分页控件
 */
Refine.OpenProjectUI.prototype._renderPagination = function(container, totalItems, totalPages) {
  var self = this;

  if (totalPages <= 1) {
    return; // 只有一页时不显示分页
  }

  var paginationDiv = $('<div class="open-project-pagination"></div>');

  // 信息显示
  var startItem = (this._currentPage - 1) * this._pageSize + 1;
  var endItem = Math.min(this._currentPage * this._pageSize, totalItems);
  $('<span class="pagination-info">')
    .text($.i18n('core-index-open/pagination-info', startItem, endItem, totalItems))
    .appendTo(paginationDiv);

  // 分页按钮容器
  var btnContainer = $('<span class="pagination-btns"></span>').appendTo(paginationDiv);

  // 首页
  $('<button>')
    .addClass('prjPagebutton')
    .text($.i18n('core-index-open/first-page'))
    .prop('disabled', this._currentPage <= 1)
    .on('click', function() {
      self._currentPage = 1;
      self._renderProjectsWithPagination();
    })
    .appendTo(btnContainer);

  // 上一页
  $('<button>')
    .addClass('prjPagebutton')
    .text($.i18n('core-index-open/prev-page'))
    .prop('disabled', this._currentPage <= 1)
    .on('click', function() {
      self._currentPage--;
      self._renderProjectsWithPagination();
    })
    .appendTo(btnContainer);

  // 页码显示
  $('<span class="pagination-current">')
    .text(' ' + $.i18n('core-index-open/page-of', this._currentPage, totalPages) + ' ')
    .appendTo(btnContainer);

  // 下一页
  $('<button>')
    .addClass('prjPagebutton')
    .text($.i18n('core-index-open/next-page'))
    .prop('disabled', this._currentPage >= totalPages)
    .on('click', function() {
      self._currentPage++;
      self._renderProjectsWithPagination();
    })
    .appendTo(btnContainer);

  // 末页
  $('<button>')
    .addClass('prjPagebutton')
    .text($.i18n('core-index-open/last-page'))
    .prop('disabled', this._currentPage >= totalPages)
    .on('click', function() {
      self._currentPage = totalPages;
      self._renderProjectsWithPagination();
    })
    .appendTo(btnContainer);

  container.append(paginationDiv);
};

Refine.OpenProjectUI.prototype._addTagFilter = function() {
  if (window.location.hash === 'open-project') {
    var tag = new URLSearchParams(window.location.search).get("tag", "");
    Refine.OpenProjectUI._filterTags(tag);
  }
};

Refine.OpenProjectUI.refreshProject = function(tr, metaData, project) {
    
    var refreshMetaField = function(data, index) {
        if (index === 3) {
            $('a', $('td', tr).eq(index))
            .text(data);
        } else {
            $('td', tr).eq(index).find('div')
            .text(data);
        }
    };
    
    var refreshMetaTags = function(data, index) {
        var tagCol = $('td', tr).eq(index).empty();
        if(data.constructor === Array){
            data.map(function(tag){
                var tagsCell = $("<span/>")
                .addClass("project-tag")
                .attr("data-tag-name", tag)
                .text(tag)
                .appendTo(tagCol);
                tagCol.parent().addClass(tag);
            });
   
        } else{
            data.split(",").map(function(tag){
                var tagsCell = $("<span/>")
                .addClass("project-tag")
                .attr("data-tag-name", tag)
                .text(tag)
                .appendTo(tagCol);
                tagCol.parent().addClass(tag);
            });
            
    }
   
    };
    
    var index = 3;
    refreshMetaField(metaData.name, index); index++;
    refreshMetaTags(metaData.tags, index); index++;
    refreshMetaField(metaData.creator, index); index++;
    refreshMetaField(metaData.subject,index); index++;
    refreshMetaField(metaData.description,index); index++;
    refreshMetaField(metaData.rowCount,index); index++;
    
    var updateUserMetadata = function(ob) {
        var userMetadata = ob.userMetadata;
        
        for ( var n in ob) {
            for ( var i in userMetadata) {
                if (n === userMetadata[i].name) {
                    userMetadata[i].value = ob[n];
                    break;
                }
            }
        }
        
        ob.userMetadata = userMetadata;
    };
    updateUserMetadata(metaData);
    var data = metaData.userMetadata;
    for(var i in data)
    {
         if (data[i].display === true) {
             refreshMetaField(data[i].value,index); index++; 
         }
    }

    Refine.OpenProjectUI.refreshTagsListPanel();
};

Refine.actionAreas.push({
  id: "open-project",
  label: $.i18n('core-index-open/open-proj'),
  uiClass: Refine.OpenProjectUI
});
