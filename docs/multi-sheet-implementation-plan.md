# OpenRefine多Sheet导入与分Tab展示技术方案

## 1. 项目概述

### 1.1 需求背景
OpenRefine当前架构支持从Excel文件导入数据，但会将所有选中的sheet合并到单一项目中。用户需要能够：
- 从Excel文件导入多个sheet
- 在同一项目中以tab形式分别展示不同sheet的数据
- 对每个sheet进行独立的数据处理操作
- 独立导出每个sheet的数据
- 各sheet的数据持久化互不影响

### 1.2 技术目标
- 扩展OpenRefine核心架构，支持多数据集（多sheet）管理
- 保持向后兼容性，不影响现有单sheet项目
- 提供直观的UI交互体验
- 确保所有现有功能在多sheet环境下正常工作

## 2. 当前架构分析

### 2.1 核心数据模型

#### 2.1.1 Project类
- **位置**: `modules/core/src/main/java/com/google/refine/model/Project.java`
- **核心属性**:
  - `List<Row> rows`: 所有行数据
  - `ColumnModel columnModel`: 列模型
  - `RecordModel recordModel`: 记录模型
  - `History history`: 操作历史
  - `Map<String, OverlayModel> overlayModels`: 扩展模型
- **设计特点**: 一个Project对应单一数据集

#### 2.1.2 ColumnModel类
- **位置**: `modules/core/src/main/java/com/google/refine/model/ColumnModel.java`
- **核心属性**:
  - `List<Column> columns`: 列列表
  - `List<ColumnGroup> columnGroups`: 列组
- **设计特点**: 管理单一数据集的列结构

#### 2.1.3 Row类
- **位置**: `modules/core/src/main/java/com/google/refine/model/Row.java`
- **核心属性**:
  - `List<Cell> cells`: 单元格列表
  - `boolean flagged`: 标记状态
  - `boolean starred`: 星标状态
- **设计特点**: 表示单行数据

#### 2.1.4 History类
- **位置**: `modules/core/src/main/java/com/google/refine/history/History.java`
- **核心功能**: 跟踪变更历史，支持撤销/重做
- **设计特点**: 基于单一数据集的变更跟踪

### 2.2 Excel导入机制

#### 2.2.1 ExcelImporter类
- **位置**: `main/src/com/google/refine/importers/ExcelImporter.java`
- **当前实现**:
  - 在`createParserUIInitializationData`方法中扫描所有sheet（第55-80行）
  - 创建sheetRecords供用户选择
  - 在`parseOneFile`方法中遍历所有选中的sheet
  - 将所有sheet数据合并到单一Project中

#### 2.2.2 Excel Parser UI
- **位置**: `main/webapp/modules/core/scripts/index/parser-interfaces/excel-parser-ui.js`
- **当前实现**:
  - 显示所有sheet供用户选择
  - 支持全选/全不选操作
  - 但所有选中sheet会被合并导入

### 2.3 数据展示架构

#### 2.3.1 DataTableView类
- **位置**: `main/webapp/modules/core/scripts/views/data-table/data-table-view.js`
- **当前实现**:
  - 展示单一数据集
  - 无tab切换机制
  - 所有操作针对单一数据集

### 2.4 数据持久化机制

#### 2.4.1 FileProjectManager类
- **位置**: `modules/core/src/main/java/com/google/refine/io/FileProjectManager.java`
- **当前实现**:
  - 每个项目独立存储在`{workspace}/{projectID}.project/`目录
  - 项目数据通过`Project.saveToOutputStream`序列化
  - 历史记录通过`HistoryEntryManager`管理

## 3. 方案A技术设计

### 3.1 核心设计理念

**扩展而非替换**: 在保持现有架构不变的基础上，通过扩展支持多数据集，确保向后兼容性。

**最小侵入原则**: 尽量减少对现有代码的修改，通过继承和组合实现新功能。

**渐进式实现**: 分阶段实施，每个阶段都可以独立验证和部署。

### 3.2 数据模型扩展

#### 3.2.1 新增SheetData类

```java
package com.google.refine.model;

import java.util.ArrayList;
import java.util.List;

public class SheetData {
    public String sheetId;
    public String sheetName;
    public String sourceFileName;
    public List<Row> rows;
    public ColumnModel columnModel;
    public RecordModel recordModel;
    public int activeRowIndex;
    
    public SheetData(String sheetId, String sheetName, String sourceFileName) {
        this.sheetId = sheetId;
        this.sheetName = sheetName;
        this.sourceFileName = sourceFileName;
        this.rows = new ArrayList<>();
        this.columnModel = new ColumnModel();
        this.recordModel = new RecordModel();
        this.activeRowIndex = 0;
    }
    
    public int getRowCount() {
        return rows.size();
    }
    
    public Row getRow(int index) {
        if (index >= 0 && index < rows.size()) {
            return rows.get(index);
        }
        return null;
    }
}
```

#### 3.2.2 扩展Project类

```java
package com.google.refine.model;

import java.util.HashMap;
import java.util.Map;

public class Project {
    final public long id;
    
    public List<Row> rows;
    public ColumnModel columnModel;
    public RecordModel recordModel;
    public Map<String, OverlayModel> overlayModels;
    public History history;
    
    transient public ProcessManager processManager = new ProcessManager();
    
    public String activeSheetId;
    public Map<String, SheetData> sheetDataMap;
    public boolean isMultiSheetProject;
    
    public Project() {
        this(generateID());
    }
    
    protected Project(long id) {
        this.id = id;
        this.history = new History(this);
        this.sheetDataMap = new HashMap<>();
        this.isMultiSheetProject = false;
    }
    
    public SheetData getActiveSheetData() {
        if (isMultiSheetProject && activeSheetId != null) {
            return sheetDataMap.get(activeSheetId);
        }
        return null;
    }
    
    public void setActiveSheet(String sheetId) {
        if (sheetDataMap.containsKey(sheetId)) {
            this.activeSheetId = sheetId;
            SheetData sheetData = sheetDataMap.get(sheetId);
            this.rows = sheetData.rows;
            this.columnModel = sheetData.columnModel;
            this.recordModel = sheetData.recordModel;
        }
    }
    
    public void addSheetData(SheetData sheetData) {
        sheetDataMap.put(sheetData.sheetId, sheetData);
        if (sheetDataMap.size() > 1) {
            isMultiSheetProject = true;
        }
    }
    
    public List<SheetData> getAllSheetData() {
        return new ArrayList<>(sheetDataMap.values());
    }
}
```

### 3.3 UI交互设计

#### 3.3.1 扩展DataTableView

**新增Tab导航组件**:
```javascript
class SheetTabView {
    constructor(project, container) {
        this._project = project;
        this._container = container;
        this._activeSheetId = null;
        this._initialize();
    }
    
    _initialize() {
        this._container.html(
            '<div class="sheet-tabs">' +
                '<div class="sheet-tab-list"></div>' +
            '</div>'
        );
        this._renderTabs();
    }
    
    _renderTabs() {
        const sheetList = this._container.find('.sheet-tab-list');
        sheetList.empty();
        
        const sheets = this._project.sheetDataMap;
        for (const [sheetId, sheetData] of Object.entries(sheets)) {
            const tab = $('<div>')
                .addClass('sheet-tab')
                .attr('data-sheet-id', sheetId)
                .text(sheetData.sheetName);
            
            if (sheetId === this._activeSheetId) {
                tab.addClass('active');
            }
            
            tab.on('click', () => {
                this._switchSheet(sheetId);
            });
            
            sheetList.append(tab);
        }
    }
    
    _switchSheet(sheetId) {
        this._activeSheetId = sheetId;
        this._renderTabs();
        
        const event = new CustomEvent('sheetChanged', {
            detail: { sheetId: sheetId }
        });
        window.dispatchEvent(event);
    }
}
```

**修改DataTableView集成Tab**:
```javascript
function DataTableView(div) {
    this._div = div;
    
    this._gridPagesSizes = JSON.parse(Refine.getPreference("ui.browsing.pageSize", null));
    this._gridPagesSizes = this._checkPaginationSize(this._gridPagesSizes, [ 5, 10, 25, 50, 100, 500, 1000 ]);
    this._pageSize = ( this._gridPagesSizes[0] < 10 ) ? 10 : this._gridPagesSizes[0];
    
    this._showRecon = true;
    this._collapsedColumnNames = {};
    this._sorting = { criteria: [] };
    this._columnHeaderUIs = [];
    this._shownulls = false;
    
    this._sheetTabView = null;
    
    this._initialize();
    this._showRows({start: 0});
}

DataTableView.prototype._initialize = function() {
    if (theProject.sheetDataMap && Object.keys(theProject.sheetDataMap).length > 1) {
        this._sheetTabView = new SheetTabView(theProject, this._div.find('.sheet-tabs-container'));
        window.addEventListener('sheetChanged', (e) => {
            this._onSheetChanged(e.detail.sheetId);
        });
    }
    
    this._renderDataTable();
};

DataTableView.prototype._onSheetChanged = function(sheetId) {
    Refine.postCoreProcess(
        "switch-sheet",
        { sheetId: sheetId },
        {},
        { },
        function(data) {
            theProject.activeSheetId = sheetId;
            DataTableView._renderDataTable();
        }
    );
};
```

#### 3.3.2 修改Excel Parser UI

**多Sheet导入UI设计**：

当用户选择多个Sheet时，UI自动切换到**多Sheet配置模式**，支持为每个Sheet独立配置导入选项。

**UI结构**：
```
┌─────────────────────────────────────────────────────────┐
│  数据预览区域 - 多Sheet Tab导航                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │ [Sheet1] [Sheet2] [Sheet3]                    │   │
│  ├─────────────────────────────────────────────────┤   │
│  │  Sheet1 预览数据表格                          │   │
│  └─────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│  导入选项区域 - 多Sheet Tab导航                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │ [Sheet1配置] [Sheet2配置] [Sheet3配置]        │   │
│  ├─────────────────────────────────────────────────┤   │
│  │  Sheet1 专属配置（忽略行数、标题行等）         │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  [更新预览] [创建项目]                                  │
└─────────────────────────────────────────────────────────┘
```

**核心实现**：

##### 1. Sheet选择与模式切换

```javascript
Refine.ExcelParserUI.prototype._initialize = function() {
    var self = this;
    
    this._optionContainer.off().empty().html(
        DOM.loadHTML("core", "scripts/index/parser-interfaces/excel-parser-ui.html"));
    this._optionContainerElmts = DOM.bind(this._optionContainer);
    
    this._renderSheetSelection();
    
    this._optionContainerElmts.sheetRecordContainer.on('change', 'input', function() {
        self._onSheetSelectionChanged();
    });
    
    this._initializePreviewTabs();
    this._initializeOptionTabs();
};

Refine.ExcelParserUI.prototype._renderSheetSelection = function() {
    var sheetTable = this._optionContainerElmts.sheetRecordContainer[0];
    $.each(this._config.sheetRecords, function(i, v) {
        var id = 'core-excel-worksheet-' + Math.round(Math.random() * 1000000);
        var tr = sheetTable.insertRow(sheetTable.rows.length);
        
        $('<input>')
            .attr('id', id)
            .attr('type', 'checkbox')
            .attr('class', 'core-excel-worksheet')
            .attr('index', i)
            .attr('data-sheet-name', v.name)
            .appendTo($(tr.insertCell(0)));
        
        $('<label>')
            .attr('for', id)
            .text(v.name)
            .appendTo(tr.insertCell(1));
        
        $('<label>')
            .attr('for', id)
            .text(v.rows + ' rows')
            .appendTo(tr.insertCell(2));
    });
};

Refine.ExcelParserUI.prototype._onSheetSelectionChanged = function() {
    var selectedSheets = this._getSelectedSheets();
    
    if (selectedSheets.length > 1) {
        this._enableMultiSheetMode(selectedSheets);
    } else if (selectedSheets.length === 1) {
        this._enableSingleSheetMode(selectedSheets[0]);
    } else {
        this._disablePreview();
    }
    
    this._scheduleUpdatePreview();
};

Refine.ExcelParserUI.prototype._getSelectedSheets = function() {
    var selectedSheets = [];
    var self = this;
    
    this._optionContainerElmts.sheetRecordContainer.find('input:checked').each(function() {
        var index = parseInt($(this).attr('index'));
        selectedSheets.push({
            index: index,
            name: $(this).attr('data-sheet-name'),
            record: self._config.sheetRecords[index]
        });
    });
    
    return selectedSheets;
};
```

##### 2. 多Sheet模式切换

```javascript
Refine.ExcelParserUI.prototype._enableMultiSheetMode = function(selectedSheets) {
    this._previewTabContainer.show();
    this._optionTabContainer.show();
    
    this._rebuildPreviewTabs(selectedSheets);
    this._rebuildOptionTabs(selectedSheets);
    
    this._activeSheetIndex = selectedSheets[0].index;
    this._switchPreviewTab(this._activeSheetIndex);
    this._switchOptionTab(this._activeSheetIndex);
};

Refine.ExcelParserUI.prototype._enableSingleSheetMode = function(sheet) {
    this._previewTabContainer.hide();
    this._optionTabContainer.hide();
    
    this._activeSheetIndex = sheet.index;
    this._showSingleSheetOptions(sheet);
};

Refine.ExcelParserUI.prototype._disablePreview = function() {
    this._previewTabContainer.hide();
    this._optionTabContainer.hide();
    this._dataContainer.empty();
};
```

##### 3. 预览Tab管理

```javascript
Refine.ExcelParserUI.prototype._initializePreviewTabs = function() {
    var self = this;
    
    this._previewTabContainer = $('<div>')
        .addClass('preview-tab-container')
        .hide()
        .insertBefore(this._dataContainer);
    
    this._previewTabList = $('<div>')
        .addClass('preview-tab-list')
        .appendTo(this._previewTabContainer);
};

Refine.ExcelParserUI.prototype._rebuildPreviewTabs = function(selectedSheets) {
    var self = this;
    
    this._previewTabList.empty();
    
    $.each(selectedSheets, function(i, sheet) {
        var tab = $('<div>')
            .addClass('preview-tab')
            .attr('data-sheet-index', sheet.index)
            .text(sheet.name)
            .on('click', function() {
                self._switchPreviewTab(sheet.index);
            });
        
        if (sheet.index === self._activeSheetIndex) {
            tab.addClass('active');
        }
        
        self._previewTabList.append(tab);
    });
};

Refine.ExcelParserUI.prototype._switchPreviewTab = function(sheetIndex) {
    this._activeSheetIndex = sheetIndex;
    
    this._previewTabList.find('.preview-tab').removeClass('active');
    this._previewTabList.find('[data-sheet-index="' + sheetIndex + '"]').addClass('active');
    
    this._updatePreview();
};
```

##### 4. 选项Tab管理

```javascript
Refine.ExcelParserUI.prototype._initializeOptionTabs = function() {
    var self = this;
    
    this._optionTabContainer = $('<div>')
        .addClass('option-tab-container')
        .hide()
        .insertBefore(this._optionContainer);
    
    this._optionTabList = $('<div>')
        .addClass('option-tab-list')
        .appendTo(this._optionTabContainer);
    
    this._sheetOptionContainers = {};
};

Refine.ExcelParserUI.prototype._rebuildOptionTabs = function(selectedSheets) {
    var self = this;
    
    this._optionTabList.empty();
    this._sheetOptionContainers = {};
    
    $.each(selectedSheets, function(i, sheet) {
        var tab = $('<div>')
            .addClass('option-tab')
            .attr('data-sheet-index', sheet.index)
            .text(sheet.name + ' 配置')
            .on('click', function() {
                self._switchOptionTab(sheet.index);
            });
        
        if (sheet.index === self._activeSheetIndex) {
            tab.addClass('active');
        }
        
        self._optionTabList.append(tab);
        
        var optionContainer = $('<div>')
            .addClass('sheet-option-container')
            .attr('data-sheet-index', sheet.index)
            .hide()
            .appendTo(this._optionContainer);
        
        self._renderSheetOptions(optionContainer, sheet);
        self._sheetOptionContainers[sheet.index] = optionContainer;
    });
    
    if (this._sheetOptionContainers[this._activeSheetIndex]) {
        this._sheetOptionContainers[this._activeSheetIndex].show();
    }
};

Refine.ExcelParserUI.prototype._renderSheetOptions = function(container, sheet) {
    var self = this;
    
    var html = DOM.loadHTML("core", "scripts/index/parser-interfaces/sheet-options.html");
    container.html(html);
    var elmts = DOM.bind(container);
    
    elmts.previewButton.on('click', function() { self._updatePreview(); });
    elmts.previewButton.html($.i18n('core-buttons/update-preview'));
    
    var onChange = function() {
        if (!elmts.disableAutoPreviewCheckbox[0].checked) {
            self._scheduleUpdatePreview();
        }
    };
    
    container.find("input").on("change", onChange);
    container.find("select").on("change", onChange);
};

Refine.ExcelParserUI.prototype._switchOptionTab = function(sheetIndex) {
    this._activeSheetIndex = sheetIndex;
    
    this._optionTabList.find('.option-tab').removeClass('active');
    this._optionTabList.find('[data-sheet-index="' + sheetIndex + '"]').addClass('active');
    
    this._optionContainer.find('.sheet-option-container').hide();
    if (this._sheetOptionContainers[sheetIndex]) {
        this._sheetOptionContainers[sheetIndex].show();
    }
    
    this._updatePreview();
};
```

##### 5. 获取多Sheet配置

```javascript
Refine.ExcelParserUI.prototype.getOptions = function() {
    var options = {
        sheets: [],
        sheetOptions: {}
    };
    
    var self = this;
    
    this._optionContainerElmts.sheetRecordContainer.find('input:checked').each(function() {
        var index = parseInt($(this).attr('index'));
        options.sheets.push(self._config.sheetRecords[index]);
        options.sheetOptions[index] = self._getSheetOptions(index);
    });
    
    return options;
};

Refine.ExcelParserUI.prototype._getSheetOptions = function(sheetIndex) {
    var container = this._sheetOptionContainers[sheetIndex];
    if (!container) {
        return {};
    }
    
    var elmts = DOM.bind(container);
    var parseIntDefault = function(s, def) {
        try {
            var n = parseInt(s, 10);
            if (!isNaN(n)) {
                return n;
            }
        } catch (e) {
        }
        return def;
    };
    
    var options = {};
    
    if (elmts.ignoreCheckbox[0].checked) {
        options.ignoreLines = parseIntDefault(elmts.ignoreInput[0].value, -1);
    } else {
        options.ignoreLines = -1;
    }
    
    if (elmts.headerLinesCheckbox[0].checked) {
        options.headerLines = parseIntDefault(elmts.headerLinesInput[0].value, 0);
    } else {
        options.headerLines = 0;
    }
    
    if (elmts.skipCheckbox[0].checked) {
        options.skipDataLines = parseIntDefault(elmts.skipInput[0].value, 0);
    } else {
        options.skipDataLines = 0;
    }
    
    if (elmts.limitCheckbox[0].checked) {
        options.limit = parseIntDefault(elmts.limitInput[0].value, -1);
    } else {
        options.limit = -1;
    }
    
    options.storeBlankRows = elmts.storeBlankRowsCheckbox[0].checked;
    options.storeBlankColumns = elmts.storeBlankColumnsCheckbox[0].checked;
    options.storeBlankCellsAsNulls = elmts.storeBlankCellsAsNullsCheckbox[0].checked;
    options.includeFileSources = elmts.includeFileSourcesCheckbox[0].checked;
    options.includeArchiveFileName = elmts.includeArchiveFileCheckbox[0].checked;
    options.forceText = elmts.forceTextCheckbox[0].checked;
    
    return options;
};
```

**设计优势**：
- 每个Sheet有独立的导入选项配置（忽略行数、标题行、跳过行等）
- 预览区域支持Tab切换，查看不同Sheet的数据
- 选项区域支持Tab切换，配置不同Sheet的参数
- 一次性创建包含所有Sheet的项目
- 保持向后兼容，单Sheet模式不变

### 3.4 数据持久化设计（混合存储方案）

#### 3.4.1 存储架构设计

**采用混合存储方案（方案C）**，结合集中管理和分离存储的优势：

**文件结构**：
```
{projectID}.project/
  ├── project.txt              # 项目元数据和sheet索引
  ├── history/                 # 历史记录（共享）
  │   ├── {entryID}.change.zip
  │   └── ...
  └── sheets/                  # Sheet数据（分离）
      ├── {sheetId}.data.txt   # Sheet完整数据
      └── {sheetId}.history.txt # Sheet历史索引
```

**设计优势**：
- 项目元数据集中管理，便于备份和迁移
- Sheet数据独立存储，支持并行操作和延迟加载
- 历史记录共享但增加sheetId字段，支持跨sheet操作

#### 3.4.2 project.txt格式设计

**多sheet项目格式**：
```
{RefineServlet.VERSION}
isMultiSheetProject=true
activeSheetId={sheetId}
sheetCount={sheet数量}
{每个Sheet的索引信息}
history=
{History数据}
/e/
```

**Sheet索引信息格式**：
```
sheet:{sheetId}=
{Sheet元数据JSON}
```

**Sheet元数据JSON**：
```json
{
  "sheetId": "file.xlsx#Sheet1",
  "sheetName": "Sheet1",
  "sourceFileName": "file.xlsx",
  "activeRowIndex": 0,
  "rowCount": 1000,
  "columnCount": 5,
  "dataFile": "sheets/file.xlsx#Sheet1.data.txt",
  "historyFile": "sheets/file.xlsx#Sheet1.history.txt"
}
```

**完整示例**：
```
3.11
isMultiSheetProject=true
activeSheetId=file.xlsx#Sheet1
sheetCount=2
sheet:file.xlsx#Sheet1=
{"sheetId":"file.xlsx#Sheet1","sheetName":"Sheet1","sourceFileName":"file.xlsx","activeRowIndex":0,"rowCount":1000,"columnCount":5,"dataFile":"sheets/file.xlsx#Sheet1.data.txt","historyFile":"sheets/file.xlsx#Sheet1.history.txt"}
sheet:file.xlsx#Sheet2=
{"sheetId":"file.xlsx#Sheet2","sheetName":"Sheet2","sourceFileName":"file.xlsx","activeRowIndex":0,"rowCount":500,"columnCount":3,"dataFile":"sheets/file.xlsx#Sheet2.data.txt","historyFile":"sheets/file.xlsx#Sheet2.history.txt"}
history=
pastEntryCount=2
{"id":1234567890,"sheetId":"file.xlsx#Sheet1","description":"Text transform on cells","time":"2024-01-20T10:30:00Z","operation":{"operationId":"core/text-transform","columnName":"Column1","expression":"value.toUppercase()"}}
{"id":1234567891,"sheetId":"file.xlsx#Sheet2","description":"Text transform on cells","time":"2024-01-20T10:35:00Z","operation":{"operationId":"core/text-transform","columnName":"A","expression":"value.toLowercase()"}}
futureEntryCount=0
/e/
```

#### 3.4.3 Sheet数据文件格式

**sheets/{sheetId}.data.txt格式**：
```
columnModel=
maxCellIndex={最大单元格索引}
keyColumnIndex={关键列索引}
columnCount={列数}
{每列JSON数据}
columnGroupCount={列组数}
{每列组数据}
/e/
rowCount={行数}
{每行JSON数据}
/e/
```

**示例**：
```
columnModel=
maxCellIndex=4
keyColumnIndex=0
columnCount=5
{"name":"Column1","cellIndex":0,"type":"text"}
{"name":"Column2","cellIndex":1,"type":"number"}
{"name":"Column3","cellIndex":2,"type":"text"}
{"name":"Column4","cellIndex":3,"type":"text"}
{"name":"Column5","cellIndex":4,"type":"text"}
columnGroupCount=0
/e/
rowCount=1000
{"cells":[{"v":"value1","r":null},...],"flagged":false,"starred":false}
{"cells":[{"v":"value2","r":null},...],"flagged":false,"starred":false}
...
/e/
```

#### 3.4.4 历史记录管理

**HistoryEntry扩展**：
```java
public class HistoryEntry {
    protected long id;
    protected String sheetId;
    protected String description;
    protected Date time;
    protected AbstractOperation operation;
    
    public HistoryEntry(long id, String sheetId, String description, AbstractOperation operation) {
        this.id = id;
        this.sheetId = sheetId;
        this.description = description;
        this.time = new Date();
        this.operation = operation;
    }
}
```

**History管理**：
```java
public class History {
    protected List<HistoryEntry> _pastEntries;
    protected List<HistoryEntry> _futureEntries;
    
    public List<HistoryEntry> getPastEntries(String sheetId) {
        return _pastEntries.stream()
            .filter(entry -> entry.sheetId.equals(sheetId))
            .collect(Collectors.toList());
    }
    
    public List<HistoryEntry> getFutureEntries(String sheetId) {
        return _futureEntries.stream()
            .filter(entry -> entry.sheetId.equals(sheetId))
            .collect(Collectors.toList());
    }
    
    public void addEntry(HistoryEntry entry) {
        _pastEntries.add(entry);
        _futureEntries.clear();
    }
}
```

#### 3.4.5 并发控制

**Sheet级锁实现**：
```java
public class Project {
    protected Map<String, Object> sheetLocks = new ConcurrentHashMap<>();
    
    public Object getSheetLock(String sheetId) {
        return sheetLocks.computeIfAbsent(sheetId, k -> new Object());
    }
    
    public void saveSheet(String sheetId) {
        synchronized (getSheetLock(sheetId)) {
            saveSheetData(sheetId);
        }
    }
    
    public void applyOperation(String sheetId, Operation operation) {
        synchronized (getSheetLock(sheetId)) {
            SheetData sheet = getSheetData(sheetId);
            operation.apply(sheet);
            saveSheet(sheetId);
        }
    }
}
```

#### 3.4.6 延迟加载

**按需加载Sheet数据**：
```java
public class Project {
    protected Map<String, SheetData> sheetDataMap;
    protected Map<String, Boolean> loadedSheets;
    
    public SheetData getSheetData(String sheetId) {
        if (!loadedSheets.getOrDefault(sheetId, false)) {
            loadSheetData(sheetId);
            loadedSheets.put(sheetId, true);
        }
        return sheetDataMap.get(sheetId);
    }
    
    private void loadSheetData(String sheetId) {
        File projectDir = getProjectDir(id);
        File dataFile = new File(projectDir, "sheets/" + sheetId + ".data.txt");
        
        try (FileReader reader = new FileReader(dataFile)) {
            SheetData sheetData = loadSheetDataFromFile(reader);
            sheetDataMap.put(sheetId, sheetData);
        } catch (IOException e) {
            logger.error("Failed to load sheet data: " + sheetId, e);
        }
    }
}
```

#### 3.4.7 原子写入

**使用临时文件+原子替换**：
```java
public class ProjectSaver {
    public void saveSheet(Project project, String sheetId) throws IOException {
        File projectDir = getProjectDir(project.id);
        File sheetsDir = new File(projectDir, "sheets");
        if (!sheetsDir.exists()) {
            sheetsDir.mkdirs();
        }
        
        File tempFile = new File(sheetsDir, sheetId + ".data.tmp");
        File targetFile = new File(sheetsDir, sheetId + ".data.txt");
        
        try {
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                SheetData sheetData = project.getSheetData(sheetId);
                saveSheetDataToStream(sheetData, out);
            }
            
            Files.move(tempFile.toPath(), targetFile.toPath(), 
                StandardCopyOption.ATOMIC_MOVE, 
                StandardCopyOption.REPLACE_EXISTING);
                
        } catch (IOException e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }
}
```

#### 3.4.8 数据迁移

**自动检测和转换旧项目**：
```java
public class ProjectLoader {
    static public Project load(File projectDir, long id) throws IOException {
        File projectFile = new File(projectDir, "project.txt");
        
        ProjectFormat format = detectProjectFormat(projectFile);
        
        if (format == ProjectFormat.SINGLE_SHEET) {
            return convertToMultiSheet(projectFile, id);
        } else {
            return loadMultiSheetProject(projectFile, id);
        }
    }
    
    private static Project convertToMultiSheet(File projectFile, long id) throws IOException {
        Project project = loadOldFormat(projectFile, id);
        
        String defaultSheetId = "default";
        SheetData defaultSheet = new SheetData(defaultSheetId, "Default Sheet", "");
        defaultSheet.rows = project.rows;
        defaultSheet.columnModel = project.columnModel;
        defaultSheet.recordModel = project.recordModel;
        
        project.isMultiSheetProject = true;
        project.activeSheetId = defaultSheetId;
        project.addSheetData(defaultSheet);
        
        project.save();
        
        return project;
    }
}
```

### 3.5 导入逻辑修改

#### 3.5.1 修改ExcelImporter

```java
@Override
public void parseOneFile(
        Project project,
        ProjectMetadata metadata,
        ImportingJob job,
        String fileSource,
        InputStream inputStream,
        int limit,
        ObjectNode options,
        List<Exception> exceptions) {
    
    Workbook wb;
    if (!inputStream.markSupported()) {
        inputStream = new BufferedInputStream(inputStream);
    }
    
    try {
        wb = FileMagic.valueOf(inputStream) == FileMagic.OOXML ? new XSSFWorkbook(inputStream)
                : new HSSFWorkbook(new POIFSFileSystem(inputStream));
    } catch (IOException e) {
        exceptions.add(new ImportException(
                "Attempted to parse as an Excel file but failed. " +
                        "Try to use Excel to re-save the file as a different Excel version or as TSV and upload again.",
                e));
        return;
    } catch (ArrayIndexOutOfBoundsException e) {
        exceptions.add(new ImportException(
                "Attempted to parse file as an Excel file but failed. " +
                        "This is probably caused by a corrupt excel file, or due to the file having previously been created or saved by a non-Microsoft application. " +
                        "Please try opening the file in Microsoft Excel and resaving it, then try re-uploading the file. " +
                        "See https://issues.apache.org/bugzilla/show_bug.cgi?id=48261 for further details",
                e));
        return;
    } catch (IllegalArgumentException e) {
        exceptions.add(new ImportException(
                "Attempted to parse as an Excel file but failed. " +
                        "Only Excel 97 and later formats are supported.",
                e));
        return;
    } catch (POIXMLException e) {
        exceptions.add(new ImportException(
                "Attempted to parse as an Excel file but failed. " +
                        "Invalid XML.",
                e));
        return;
    }
    
    final boolean forceText;
    if (options.get("forceText") != null) {
        forceText = options.get("forceText").asBoolean(false);
    } else {
        forceText = false;
    }
    
    final boolean createMultiSheetProject;
    if (options.get("createMultiSheetProject") != null) {
        createMultiSheetProject = options.get("createMultiSheetProject").asBoolean(false);
    } else {
        createMultiSheetProject = false;
    }
    
    ArrayNode sheets = (ArrayNode) options.get("sheets");
    ObjectNode sheetOptions = (ObjectNode) options.get("sheetOptions");
    
    if (createMultiSheetProject) {
        parseMultipleSheets(project, metadata, job, fileSource, wb, sheets, sheetOptions, forceText, limit, options, exceptions);
    } else {
        parseSingleSheet(project, metadata, job, fileSource, wb, sheets, forceText, limit, options, exceptions);
    }
}

private void parseMultipleSheets(
        Project project,
        ProjectMetadata metadata,
        ImportingJob job,
        String fileSource,
        Workbook wb,
        ArrayNode sheets,
        ObjectNode sheetOptions,
        boolean forceText,
        int limit,
        ObjectNode options,
        List<Exception> exceptions) {
    
    for (int i = 0; i < sheets.size(); i++) {
        String[] fileNameAndSheetIndex = new String[2];
        ObjectNode sheetObj = (ObjectNode) sheets.get(i);
        fileNameAndSheetIndex = sheetObj.get("fileNameAndSheetIndex").asText().split("#");
        
        if (!fileNameAndSheetIndex[0].equals(fileSource))
            continue;
        
        final Sheet sheet = wb.getSheetAt(Integer.parseInt(fileNameAndSheetIndex[1]));
        final String sheetId = fileSource + "#" + sheet.getSheetName();
        
        SheetData sheetData = new SheetData(sheetId, sheet.getSheetName(), fileSource);
        
        sheetData.ignoreLines = -1;
        sheetData.headerLines = 0;
        sheetData.skipDataLines = 0;
        sheetData.limit = -1;
        sheetData.storeBlankRows = false;
        sheetData.storeBlankColumns = false;
        sheetData.storeBlankCellsAsNulls = false;
        sheetData.includeFileSources = false;
        sheetData.includeArchiveFileName = false;
        sheetData.forceText = forceText;
        
        ObjectNode sheetSpecificOptions = (ObjectNode) sheetOptions.get(String.valueOf(i));
        if (sheetSpecificOptions != null) {
            if (sheetSpecificOptions.has("ignoreLines")) {
                sheetData.ignoreLines = sheetSpecificOptions.get("ignoreLines").asInt(-1);
            }
            if (sheetSpecificOptions.has("headerLines")) {
                sheetData.headerLines = sheetSpecificOptions.get("headerLines").asInt(0);
            }
            if (sheetSpecificOptions.has("skipDataLines")) {
                sheetData.skipDataLines = sheetSpecificOptions.get("skipDataLines").asInt(0);
            }
            if (sheetSpecificOptions.has("limit")) {
                sheetData.limit = sheetSpecificOptions.get("limit").asInt(-1);
            }
            if (sheetSpecificOptions.has("storeBlankRows")) {
                sheetData.storeBlankRows = sheetSpecificOptions.get("storeBlankRows").asBoolean(false);
            }
            if (sheetSpecificOptions.has("storeBlankColumns")) {
                sheetData.storeBlankColumns = sheetSpecificOptions.get("storeBlankColumns").asBoolean(false);
            }
            if (sheetSpecificOptions.has("storeBlankCellsAsNulls")) {
                sheetData.storeBlankCellsAsNulls = sheetSpecificOptions.get("storeBlankCellsAsNulls").asBoolean(false);
            }
            if (sheetSpecificOptions.has("includeFileSources")) {
                sheetData.includeFileSources = sheetSpecificOptions.get("includeFileSources").asBoolean(false);
            }
            if (sheetSpecificOptions.has("includeArchiveFileName")) {
                sheetData.includeArchiveFileName = sheetSpecificOptions.get("includeArchiveFileName").asBoolean(false);
            }
            if (sheetSpecificOptions.has("forceText")) {
                sheetData.forceText = sheetSpecificOptions.get("forceText").asBoolean(forceText);
            }
        }
        
        TableDataReader dataReader = new TableDataReader() {
            int nextRow = 0;
            final int lastRow = sheet.getLastRowNum();
            
            @Override
            public List<Object> getNextRowOfCells() throws IOException {
                if (nextRow > lastRow) {
                    return null;
                }
                
                List<Object> cells = new ArrayList<Object>();
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(nextRow++);
                if (row != null) {
                    short lastCell = row.getLastCellNum();
                    for (short cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                        Cell cell = null;
                        org.apache.poi.ss.usermodel.Cell sourceCell = row.getCell(cellIndex);
                        if (sourceCell != null) {
                            cell = extractCell(sourceCell, sheetData.forceText);
                        }
                        cells.add(cell);
                    }
                }
                return cells;
            }
        };
        
        TabularImportingParserBase.readTable(
                sheetData.rows,
                sheetData.columnModel,
                metadata,
                job,
                dataReader,
                sheetId,
                sheetData.limit,
                options,
                exceptions);
        
        sheetData.recordModel.update(sheetData);
        project.addSheetData(sheetData);
    }
    
    if (!project.sheetDataMap.isEmpty()) {
        String firstSheetId = project.sheetDataMap.keySet().iterator().next();
        project.setActiveSheet(firstSheetId);
    }
}

private void parseSingleSheet(
        Project project,
        ProjectMetadata metadata,
        ImportingJob job,
        String fileSource,
        Workbook wb,
        ArrayNode sheets,
        boolean forceText,
        int limit,
        ObjectNode options,
        List<Exception> exceptions) {
    
    for (int i = 0; i < sheets.size(); i++) {
        String[] fileNameAndSheetIndex = new String[2];
        ObjectNode sheetObj = (ObjectNode) sheets.get(i);
        fileNameAndSheetIndex = sheetObj.get("fileNameAndSheetIndex").asText().split("#");
        
        if (!fileNameAndSheetIndex[0].equals(fileSource))
            continue;
        
        final Sheet sheet = wb.getSheetAt(Integer.parseInt(fileNameAndSheetIndex[1]));
        final int lastRow = sheet.getLastRowNum();
        
        TableDataReader dataReader = new TableDataReader() {
            int nextRow = 0;
            
            @Override
            public List<Object> getNextRowOfCells() throws IOException {
                if (nextRow > lastRow) {
                    return null;
                }
                
                List<Object> cells = new ArrayList<Object>();
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(nextRow++);
                if (row != null) {
                    short lastCell = row.getLastCellNum();
                    for (short cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                        Cell cell = null;
                        org.apache.poi.ss.usermodel.Cell sourceCell = row.getCell(cellIndex);
                        if (sourceCell != null) {
                            cell = extractCell(sourceCell, forceText);
                        }
                        cells.add(cell);
                    }
                }
                return cells;
            }
        };
        
        TabularImportingParserBase.readTable(
                project,
                metadata,
                job,
                dataReader,
                fileSource + "#" + sheet.getSheetName(),
                limit,
                options,
                exceptions);
    }
}
```

### 3.6 操作系统扩展

#### 3.6.1 新增SwitchSheetCommand

```java
package com.google.refine.commands.sheet;

import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.model.Project;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SwitchSheetCommand extends Command {
    
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) {
        try {
            long projectID = getProjectParameter(request, "project");
            String sheetId = request.getParameter("sheetId");
            
            Project project = ProjectManager.singleton.getProject(projectID);
            
            if (project != null && project.sheetDataMap.containsKey(sheetId)) {
                project.setActiveSheet(sheetId);
                respondJSON(response, new JSONObject());
            } else {
                respondError(response, "Sheet not found");
            }
        } catch (Exception e) {
            respondException(response, e);
        }
    }
}
```

#### 3.6.2 修改操作以支持多Sheet

所有Operation类需要添加sheetId参数，确保操作针对正确的sheet执行：

```java
public abstract class AbstractOperation implements Operation {
    
    protected String sheetId;
    
    public void setSheetId(String sheetId) {
        this.sheetId = sheetId;
    }
    
    protected String getSheetId(Project project) {
        return sheetId != null ? sheetId : project.activeSheetId;
    }
}
```

### 3.7 导出功能扩展

#### 3.7.1 新增MultiSheetExporter

```java
package com.google.refine.exporters;

import com.google.refine.ProjectManager;
import com.google.refine.model.Project;
import com.google.refine.model.SheetData;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class MultiSheetExporter implements Exporter {
    
    @Override
    public String getContentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
    
    @Override
    public void export(Project project, Properties options, OutputStream outputStream) throws IOException {
        Workbook wb = new XSSFWorkbook();
        
        List<SheetData> sheets = project.getAllSheetData();
        for (SheetData sheetData : sheets) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(sheetData.sheetName);
            
            for (int rowIndex = 0; rowIndex < sheetData.rows.size(); rowIndex++) {
                Row row = sheetData.rows.get(rowIndex);
                org.apache.poi.ss.usermodel.Row excelRow = sheet.createRow(rowIndex);
                
                for (int cellIndex = 0; cellIndex < row.cells.size(); cellIndex++) {
                    Cell cell = row.cells.get(cellIndex);
                    if (cell != null) {
                        org.apache.poi.ss.usermodel.Cell excelCell = excelRow.createCell(cellIndex);
                        excelCell.setCellValue(cell.value != null ? cell.value.toString() : "");
                    }
                }
            }
        }
        
        wb.write(outputStream);
        wb.close();
    }
}
```

## 4. 实施计划

### 4.1 第一阶段：数据模型扩展（2-3周）

**任务**:
1. 创建SheetData类
2. 扩展Project类支持多sheet
3. 修改Project序列化/反序列化逻辑
4. 编写单元测试

**验收标准**:
- SheetData类能够正确存储和管理sheet数据
- Project类能够正确切换活动sheet
- 项目能够正确保存和加载多sheet数据

### 4.2 第二阶段：导入逻辑修改（2周）

**任务**:
1. 修改ExcelImporter支持多sheet导入
2. 修改Excel Parser UI添加多sheet选项
3. 编写集成测试

**验收标准**:
- 能够正确导入多个sheet到同一项目
- UI能够正确显示和选择多个sheet
- 导入的数据结构正确

### 4.3 第三阶段：UI交互实现（3-4周）

**任务**:
1. 实现SheetTabView组件
2. 修改DataTableView集成tab切换
3. 实现SwitchSheetCommand
4. 编写前端测试

**验收标准**:
- Tab界面正确显示所有sheet
- 点击tab能够正确切换sheet
- 切换sheet后数据正确更新

### 4.4 第四阶段：操作系统适配（3-4周）

**任务**:
1. 修改所有Operation类支持sheetId
2. 确保所有操作针对正确sheet执行
3. 修改历史记录管理
4. 编写回归测试

**验收标准**:
- 所有操作能够针对特定sheet执行
- 历史记录正确跟踪每个sheet的操作
- 撤销/重做功能正常工作

### 4.5 第五阶段：导出功能实现（1-2周）

**任务**:
1. 实现MultiSheetExporter
2. 修改现有Exporter支持多sheet
3. 编写导出测试

**验收标准**:
- 能够正确导出所有sheet到Excel
- 导出的数据格式正确
- 支持单独导出单个sheet

### 4.6 第六阶段：测试和优化（2-3周）

**任务**:
1. 全面测试所有功能
2. 性能优化
3. 用户体验优化
4. 文档编写

**验收标准**:
- 所有功能测试通过
- 性能满足要求
- 用户体验良好
- 文档完整

## 5. 技术风险评估

### 5.1 高风险项

#### 5.1.1 历史记录管理
**风险**: 多sheet环境下的历史记录管理复杂度高
**影响**: 可能导致撤销/重做功能异常
**缓解措施**:
- 为每个sheet维护独立的历史记录
- 切换sheet时切换对应的历史记录
- 充分测试历史记录的保存和恢复

#### 5.1.2 并发操作
**风险**: 多sheet并发操作可能导致数据不一致
**影响**: 可能导致数据损坏
**缓解措施**:
- 实现sheet级别的锁机制
- 确保操作原子性
- 充分测试并发场景

#### 5.1.3 性能问题
**风险**: 多sheet可能增加内存和CPU消耗
**影响**: 可能影响系统性能
**缓解措施**:
- 实现延迟加载机制
- 优化数据结构
- 性能测试和优化

### 5.2 中风险项

#### 5.2.1 向后兼容性
**风险**: 修改可能影响现有单sheet项目
**影响**: 可能导致现有项目无法正常使用
**缓解措施**:
- 保持现有API不变
- 添加版本检查
- 充分测试现有项目

#### 5.2.2 扩展兼容性
**风险**: 扩展可能需要适配多sheet架构
**影响**: 可能导致扩展功能异常
**缓解措施**:
- 提供扩展API
- 文档说明适配方法
- 与扩展开发者协作

### 5.3 低风险项

#### 5.3.1 UI交互
**风险**: Tab交互可能存在用户体验问题
**影响**: 可能影响用户使用体验
**缓解措施**:
- 用户测试
- 迭代优化
- 参考最佳实践

## 6. 测试策略

### 6.1 单元测试

**数据模型测试**:
- SheetData类测试
- Project类多sheet功能测试
- 序列化/反序列化测试

**导入逻辑测试**:
- ExcelImporter多sheet导入测试
- 单sheet和多sheet模式切换测试

**操作系统测试**:
- 各Operation类sheetId支持测试
- 历史记录管理测试

### 6.2 集成测试

**端到端测试**:
- 完整的导入-编辑-导出流程测试
- 多sheet切换测试
- 并发操作测试

**兼容性测试**:
- 现有单sheet项目测试
- 扩展兼容性测试
- 浏览器兼容性测试

### 6.3 性能测试

**负载测试**:
- 大文件多sheet导入测试
- 大数据量操作测试
- 并发用户测试

**压力测试**:
- 极限数据量测试
- 长时间运行测试

## 7. 成功标准

### 7.1 功能标准
- 能够导入2个以上sheet到同一项目
- 能够通过tab切换查看不同sheet
- 能够对每个sheet进行独立的数据处理
- 能够独立导出每个sheet或导出所有sheet
- 各sheet的数据持久化互不影响

### 7.2 性能标准
- 导入1000行×10sheet的Excel文件时间 < 30秒
- 切换sheet响应时间 < 1秒
- 操作响应时间与单sheet模式相当
- 内存消耗增加 < 50%

### 7.3 质量标准
- 所有测试通过率 > 95%
- 代码覆盖率 > 80%
- 无严重bug
- 用户体验评分 > 4.0/5.0

## 8. 后续优化方向

### 8.1 功能增强
- 支持跨sheet数据引用
- 支持跨sheet操作
- 支持sheet重命名和删除
- 支持sheet复制和移动

### 8.2 性能优化
- 实现虚拟滚动
- 优化大数据量渲染
- 实现数据缓存

### 8.3 用户体验优化
- 支持拖拽排序sheet
- 支持sheet分组
- 支持自定义sheet颜色

## 9. 结论

方案A通过扩展OpenRefine核心架构，在保持向后兼容性的前提下，实现了多sheet导入和分tab展示的需求。虽然实施复杂度较高，但通过分阶段实施和充分测试，可以确保功能稳定可靠。

该方案能够满足用户的核心需求，并为未来的功能扩展预留了空间。建议按照实施计划逐步推进，确保每个阶段的质量后再进入下一阶段。
