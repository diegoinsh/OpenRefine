# FilesExtension 文件信息提取功能改造技术方案

## 1. 需求概述

### 1.1 需求背景
当前 FilesExtension 插件仅支持从本地目录导入文件元数据（文件名、大小、扩展名、修改时间等），形成一个"文件详情"数据表。在档案数字化场景下，用户需要对导入的文件（主要为扫描件图像）进行结构化要素提取（如题名、责任者、文号、成文日期、档号等），并将提取结果作为独立数据 Sheet 展示，以便后续的数据治理和校验。

### 1.2 功能目标
1. **文件元数据可选导入**：文件元数据导入（"文件详情"Sheet）改为可选项，用户可选择是否同时导出文件元数据
2. **新增信息提取模式**：在导入配置界面增加"信息提取"选项，可选择结构化要素模板
3. **结构化要素模板**：
   - **档案要素案件模板**（默认）：提取字段包括 `题名`、`责任者`、`文号`、`成文日期`，以`档号`（根据文件路径和文件名拼接生成）作为行标识
   - **档案要素案卷模板**：包含案件模板字段，在案件条目提取完成后需额外生成一个"案卷"汇总条目
4. **档号生成**：档号不从文档内容提取，而是根据文件路径和文件名按用户配置的拼接规则自动生成，需提供档号拼接规则配置UI（参考质量规则"文件资源关联检查"的"配置路径"弹出窗口实现）
5. **提取粒度**：信息提取的最小单位为**文件夹/PDF文件**（非单个图像文件），每个文件夹或PDF文件对应提取结果的一行
6. **提取方式**：调用已有 AIMP LLM 分析接口（`/api/llm/analyze`）
7. **数据展示**：提取结果作为新的 Sheet 存储，利用已有多Sheet架构进行展示

### 1.3 业务流程
```
用户选择目录 → 配置提取选项（模板选择 + 档号规则配置） → 预览 → 创建项目
    ↓                                                          ↓
  扫描目录结构                               Sheet1: 文件详情（可选，元数据）
  (以文件夹/PDF为单位)                       Sheet2: 信息提取结果（按模板）
                                             （如选案卷模板，含案卷汇总行）
```

## 2. 现有架构分析

### 2.1 FilesExtension 现有结构
```
extensions/FilesExtension/
├── module/
│   ├── MOD-INF/controller.js          # 扩展初始化，注册ImportingController
│   ├── scripts/index/
│   │   ├── files-importing-controller.js  # 前端导入控制器
│   │   └── import-from-local-dir.js       # 目录选择UI
│   └── styles/
├── src/main/java/.../importer/
│   ├── FilesImportingController.java  # 后端导入控制器
│   └── FilesImporter.java            # 文件扫描与数据加载
```

**关键流程**：
1. `FilesImporter.generateFileList()` 扫描目录生成CSV文件元数据
2. `FilesImporter.loadData()` 使用 `SeparatorBasedImporter` 解析CSV为Project数据
3. `FilesImportingController.doCreateProject()` 注册项目

### 2.2 多Sheet架构（已实现）
- `Project` 类已扩展 `sheetDataMap`、`activeSheetId`、`isMultiSheetProject` 属性
- `SheetData` 类独立持有 `rows`、`columnModel`、`recordModel`
- 持久化采用混合存储方案：project.txt索引 + sheets/独立数据文件
- 前端支持Tab切换展示

### 2.3 AIMP LLM 分析接口（已实现）

#### 接口信息
- **路径**: `POST /api/llm/analyze`
- **参数**: `prompt`(提示词), `context`(上下文), `response_format`("json"/"text")
- **响应**: `{ "success": bool, "result": object/string, "error": string }`

#### Java调用方式（已有实现）
`AimpClient.llmAnalyze(prompt, context, responseFormat)` → `LlmAnalyzeResult`

#### 服务配置
- 默认地址: `http://127.0.0.1:7998`
- 通过 `data-quality-config.json` 中的 `aimp.server` 配置
- `CheckAimpConnectionCommand.getConfiguredServiceUrl()` 获取配置的URL

### 2.4 档案要素提取接口（待实现）

> **重要说明**：当前 AIMP 服务端尚未实现信息提取接口，需要根据 [aimp-extraction-api-design.md](aimp-extraction-api-design.md) 进行实现。

#### 接口规划

| 接口 | 路径 | 状态 | 说明 |
|------|------|------|------|
| 单文件提取 | `POST /extract/upload` | 待实现 | 对单个文件进行OCR和信息提取 |
| 批量提取 | `POST /extract/archive/batch_extract` | 待实现 | 批量处理多个文件夹/PDF |
| 任务查询 | `GET /extract/archive/task/{task_id}` | 待实现 | 查询异步任务状态 |

#### 请求参数

**单文件提取接口**：
- `file`: 待提取的文件（PDF或图像）
- `key_list`: 提取要素列表，如 `title,responsible_party,document_number,date`
- `sync`: 是否同步处理

#### 支持的提取要素

| 要素键名 | 中文名称 | 说明 |
|----------|----------|------|
| title | 题名 | 文件标题 |
| responsible_party | 责任者 | 文件责任单位/人 |
| document_number | 文号 | 公文编号 |
| date | 成文日期 | 文件成文日期 |

#### 响应格式
```json
{
  "success": true,
  "results": {
    "title": "关于XXX的通知",
    "responsible_party": "XX单位",
    "document_number": "XX字〔2024〕1号",
    "date": "2024年1月1日"
  }
}
```

详细接口设计请参考 [aimp-extraction-api-design.md](aimp-extraction-api-design.md)。

## 3. 技术方案设计

### 3.1 整体架构

```
┌────────────────── FilesExtension 改造架构 ──────────────────┐
│                                                              │
│  [前端 UI 层]                                                │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  import-from-local-dir.js (改造)                     │    │
│  │  ├─ 目录选择（现有）                                  │    │
│  │  └─ 提取选项配置（新增）                              │    │
│  │     ├─ □ 启用信息提取                                 │    │
│  │     ├─ □ 同时导出文件元数据（可选）                    │    │
│  │     ├─ 模板选择: [档案要素案件模板 / 档案要素案卷模板] │    │
│  │     ├─ 档号拼接规则配置（配置路径弹窗）                │    │
│  │     └─ AIMP服务地址（自动读取已配置地址）              │    │
│  └──────────────────────────────────────────────────────┘    │
│                          │                                    │
│  [后端控制层]             ▼                                    │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  FilesImportingController.java (改造)                │    │
│  │  ├─ doLocalDirectoryPreview (改造: 支持提取预览)      │    │
│  │  └─ doCreateProject (改造: 创建多Sheet项目)           │    │
│  └──────────────────────────────────────────────────────┘    │
│                          │                                    │
│  [数据处理层]             ▼                                    │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  FilesImporter.java (改造)                           │    │
│  │  ├─ generateFileList() （现有逻辑，可选Sheet）        │    │
│  │  ├─ loadData() （现有逻辑，可选Sheet）                │    │
│  │  ├─ scanDirectoryUnits() （新增，扫描文件夹/PDF单元） │    │
│  │  └─ extractFileElements() （新增，Sheet2）            │    │
│  │      ├─ 遍历文件夹/PDF单元                            │    │
│  │      ├─ 按路径规则生成档号                            │    │
│  │      ├─ 调用 AimpClient 提取要素                      │    │
│  │      └─ 组装结构化数据到 SheetData                    │    │
│  └──────────────────────────────────────────────────────┘    │
│                          │                                    │
│  [外部服务层]             ▼                                    │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  AIMP LLM Service (已有)                             │    │
│  │  POST /api/llm/analyze                               │    │
│  │  ├─ OCR识别文档文本                                   │    │
│  │  └─ LLM提取结构化要素                                │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  [数据存储层]                                                 │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  Project (多Sheet模式)                               │    │
│  │  ├─ Sheet "文件详情" (可选): fileName, fileSize, ... │    │
│  │  └─ Sheet "信息提取": 档号, 题名, 责任者, 文号, ... │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 结构化要素模板设计

#### 3.2.0 提取粒度说明

信息提取的最小处理单位为**文件夹**或**PDF文件**（非单个图像文件）：
- 一个文件夹内的所有图像文件属于同一份文件，整体进行 OCR + 要素提取，结果为一行数据
- 一个独立的 PDF 文件整体进行提取，结果为一行数据
- "源路径"列记录的是文件夹路径或PDF文件路径

#### 3.2.1 档案要素案件模板（默认）
| 列名 | 说明 | 生成方式 |
|------|------|----------|
| 档号 | 行标识/主键 | **根据文件路径和文件名按配置的拼接规则自动生成**（见3.2.3） |
| 题名 | 文件标题 | LLM从文档内容提取 |
| 责任者 | 文件责任单位/人 | LLM从文档内容提取 |
| 文号 | 公文编号 | LLM从文档内容提取 |
| 成文日期 | 文件成文日期 | LLM从文档内容提取 |
| 源路径 | 对应的文件夹路径或PDF文件路径 | 系统自动填充 |
| 提取状态 | 成功/失败/部分提取 | 系统自动填充 |

#### 3.2.2 档案要素案卷模板
继承案件模板的字段，增加"条目类型"列标识（案件/案卷），在所有案件条目提取完成后自动生成一行"案卷"汇总条目。

| 列名 | 说明 | 生成方式 |
|------|------|----------|
| 条目类型 | "案件" 或 "案卷" | 系统生成 |
| 档号 | 行标识/案卷档号 | 案件:路径拼接规则生成; 案卷:规则生成(后续补充) |
| 题名 | 标题 | 案件:LLM提取; **案卷:对该案卷下（文件夹中）所有案件的题名进行概括** |
| 责任者 | 责任者 | 案件:LLM提取; **案卷:对该案卷下（文件夹中）所有案件的所有责任者进行汇总** |
| 成文日期 | 日期 | 案件:LLM提取; 案卷:规则生成(后续补充) |
| 源路径 | 案卷文件夹路径 | 系统填充 |

> **注1**: 案卷汇总行的档号、成文日期生成规则待后续业务补充，当前设计预留生成入口和数据结构。
> **注2**: 案卷模板的"题名"汇总将在所有案件提取完成后，调用LLM对所有案件题名进行语义概括生成。
> **注3**: 案卷模板的"责任者"汇总将自动去重合并所有案件条目中的责任者。

#### 3.2.3 档号拼接规则配置

档号**不从文档内容中提取**，而是根据文件系统路径（文件夹名/文件名）按用户配置的拼接规则自动生成。

**配置UI参考**：复用质量规则"文件资源关联检查"中"配置路径"弹出窗口的设计模式，包含：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| 路径层级选择 | 选择目录层级中哪些部分参与档号拼接 | 上级目录名、当前目录名、文件名前缀 |
| 拼接模式 | 分隔符模式 或 模板模式 | 分隔符: `-`，模板: `{0}-{1}-{2}` |
| 分隔符 | 各层级间的连接符 | `-`、`.`、`/` |
| 模板表达式 | 自定义拼接格式 | `{全宗号}-{目录号}-{案卷号}-{件号}` |
| 预览 | 根据当前配置实时预览档号示例 | `0016-WS-2024-001` |

**拼接示例**：
```
目录结构:  D:\archives\0016\WS\2024\001\image001.jpg
路径层级:  [0016, WS, 2024, 001]
分隔符:    -
生成档号:  0016-WS-2024-001
```

**实现方式**：基于 `ResourceCheckConfig` 中已有的 `pathMode`/`separator`/`template` 机制，新增 `ArchiveNumberConfig` 配置类，实现档号生成逻辑。

### 3.3 LLM 提示词设计

针对每个文件夹/PDF单元，构造如下提示词调用 `/api/llm/analyze` 接口：

> 注：档号由路径规则自动生成，不纳入LLM提取范围。

```
请从以下文档图像/内容中提取结构化档案要素信息。

需要提取的要素包括：题名、责任者、文号、成文日期

请按照以下JSON格式返回提取结果：
{
  "题名": "提取到的题名，如无则为空字符串",
  "责任者": "提取到的责任者，如无则为空字符串",
  "文号": "提取到的文号，如无则为空字符串",
  "成文日期": "提取到的成文日期，如无则为空字符串"
}

如果某个要素无法从文档中识别，请将对应字段值设为空字符串。
```

**案卷题名概括提示词**（仅案卷模板使用，在所有案件提取完成后调用）：
```
请对以下多个档案文件的题名进行概括，生成一个简洁的案卷级题名。

各案件题名列表：
{案件1题名}
{案件2题名}
...

请直接返回概括后的题名文本，不需要其他格式。
```

### 3.4 后端改造详细设计

#### 3.4.1 新增 ExtractionTemplate 枚举类

**文件**: `extensions/FilesExtension/src/main/java/org/openrefine/extensions/files/importer/ExtractionTemplate.java`

```java
package org.openrefine.extensions.files.importer;

public enum ExtractionTemplate {
    ARCHIVE_CASE("档案要素案件模板",
        new String[]{"档号", "题名", "责任者", "文号", "成文日期", "源路径", "提取状态"},
        "档号", false),
    ARCHIVE_VOLUME("档案要素案卷模板",
        new String[]{"条目类型", "档号", "题名", "责任者", "成文日期", "源路径"},
        "档号", true);

    private final String displayName;
    private final String[] columns;
    private final String keyColumn;
    private final boolean generateVolumeSummary;

    ExtractionTemplate(String displayName, String[] columns,
                       String keyColumn, boolean generateVolumeSummary) {
        this.displayName = displayName;
        this.columns = columns;
        this.keyColumn = keyColumn;
        this.generateVolumeSummary = generateVolumeSummary;
    }

    public String getDisplayName() { return displayName; }
    public String[] getColumns() { return columns; }
    public String getKeyColumn() { return keyColumn; }
    public boolean isGenerateVolumeSummary() { return generateVolumeSummary; }

    // LLM提取的核心要素（不含系统自动填充列，档号由路径规则生成）
    public String[] getExtractionKeys() {
        return new String[]{"题名", "责任者", "文号", "成文日期"};
    }

    public static ExtractionTemplate fromName(String name) {
        for (ExtractionTemplate t : values()) {
            if (t.displayName.equals(name)) return t;
        }
        return ARCHIVE_CASE; // 默认
    }
}
```

#### 3.4.2 新增 FileElementExtractor 类

**文件**: `extensions/FilesExtension/src/main/java/org/openrefine/extensions/files/importer/FileElementExtractor.java`

核心职责：对文件夹/PDF单元列表逐个调用 AIMP LLM API 提取要素，根据路径规则生成档号，组装为 SheetData。

```java
package org.openrefine.extensions.files.importer;

import com.google.refine.model.*;
import com.fasterxml.jackson.databind.JsonNode;

public class FileElementExtractor {

    private final AimpLlmClient aimpClient;       // 简化版AIMP客户端
    private final ExtractionTemplate template;
    private final ArchiveNumberConfig archiveNumberConfig; // 档号拼接配置

    public FileElementExtractor(String aimpServiceUrl,
                                 ExtractionTemplate template,
                                 ArchiveNumberConfig archiveNumberConfig) {
        this.aimpClient = new AimpLlmClient(aimpServiceUrl);
        this.template = template;
        this.archiveNumberConfig = archiveNumberConfig;
    }

    /**
     * 对文件夹/PDF单元列表执行要素提取，返回SheetData
     * @param extractionUnits 提取单元列表（文件夹路径或PDF路径）
     * @return 填充了提取结果的SheetData
     */
    public SheetData extractToSheetData(List<ExtractionUnit> extractionUnits) {
        SheetData sheetData = new SheetData("extraction-result", "信息提取", "");

        // 1. 构建列模型
        String[] columns = template.getColumns();
        for (int i = 0; i < columns.length; i++) {
            Column col = new Column(i, columns[i]);
            sheetData.columnModel.columns.add(col);
            sheetData.columnModel.setMaxCellIndex(i);
        }

        // 2. 逐单元提取
        for (ExtractionUnit unit : extractionUnits) {
            Row row = extractSingleUnit(unit, columns);
            sheetData.rows.add(row);
        }

        // 3. 如果是案卷模板，生成案卷汇总行
        if (template.isGenerateVolumeSummary()) {
            Row volumeRow = generateVolumeSummaryRow(sheetData, columns);
            sheetData.rows.add(0, volumeRow);
        }

        return sheetData;
    }

    private Row extractSingleUnit(ExtractionUnit unit, String[] columns) {
        Row row = new Row(columns.length);

        // 1. 根据路径规则生成档号（不通过LLM）
        String archiveNumber = archiveNumberConfig.generateArchiveNumber(unit.getPath());

        // 2. 构建提示词（仅提取题名、责任者、文号、成文日期）
        String prompt = buildExtractionPrompt(template.getExtractionKeys());

        // 3. 调用AIMP服务提取（OCR + LLM）
        JsonNode result = aimpClient.extractContent(unit.getPath(),
            Arrays.asList(template.getExtractionKeys()));

        // 4. 填充行数据
        if (result != null) {
            fillRowFromResult(row, archiveNumber, result,
                unit.getPath(), columns);
        } else {
            fillRowWithError(row, archiveNumber, unit.getPath(),
                columns, "提取失败");
        }

        return row;
    }

    /**
     * 生成案卷汇总行
     * - 题名：调用LLM对所有案件题名进行概括
     * - 责任者：去重合并所有案件的责任者
     * - 成文日期：规则生成（后续补充）
     * - 档号：规则生成（后续补充）
     */
    private Row generateVolumeSummaryRow(SheetData sheetData, String[] columns) {
        Row volumeRow = new Row(columns.length);

        // 收集所有案件题名用于概括
        List<String> allTitles = collectColumnValues(sheetData, "题名");
        String summarizedTitle = aimpClient.llmAnalyze(
            buildTitleSummaryPrompt(allTitles), "", "text").getResult();

        // 收集并去重合并所有责任者
        List<String> allAuthors = collectColumnValues(sheetData, "责任者");
        String mergedAuthors = mergeAndDedup(allAuthors);

        // 填充案卷行
        setColumnValue(volumeRow, columns, "条目类型", "案卷");
        setColumnValue(volumeRow, columns, "题名", summarizedTitle);
        setColumnValue(volumeRow, columns, "责任者", mergedAuthors);
        // 档号、成文日期：后续补充规则

        return volumeRow;
    }

    // ... (辅助方法)
}
```

#### 3.4.2.1 新增 ExtractionUnit 数据类

表示一个提取单元（文件夹或PDF文件）：

```java
public class ExtractionUnit {
    private String path;           // 文件夹路径或PDF文件路径
    private String type;           // "folder" 或 "pdf"
    private List<String> files;    // 文件夹内的文件列表（仅folder类型）

    // getter/setter...
}
```

#### 3.4.2.2 新增 ArchiveNumberConfig 配置类

参考 `ResourceCheckConfig` 的路径构建逻辑，实现档号生成：

```java
public class ArchiveNumberConfig {
    private List<Integer> pathLevels;  // 参与档号生成的路径层级索引
    private String mode;                // "separator" 或 "template"
    private String separator;           // 分隔符，如 "-"
    private String template;            // 模板，如 "{0}-{1}-{2}"

    /**
     * 根据文件夹/PDF路径生成档号
     * @param unitPath 文件夹或PDF文件的路径
     * @return 生成的档号字符串
     */
    public String generateArchiveNumber(String unitPath) {
        // 解析路径为层级数组
        String[] pathParts = unitPath.replace("\\", "/").split("/");

        // 根据配置的层级索引提取对应部分
        List<String> selectedParts = new ArrayList<>();
        for (int level : pathLevels) {
            if (level >= 0 && level < pathParts.length) {
                selectedParts.add(pathParts[level]);
            }
        }

        // 按模式拼接
        if ("template".equals(mode) && template != null) {
            String result = template;
            for (int i = 0; i < selectedParts.size(); i++) {
                result = result.replace("{" + i + "}", selectedParts.get(i));
            }
            return result;
        } else {
            return String.join(separator != null ? separator : "-", selectedParts);
        }
    }
}
```

#### 3.4.3 改造 FilesImportingController

**核心改动**：`doLocalDirectoryPreview` 和 `doCreateProject` 支持提取模式。

**doLocalDirectoryPreview 改造要点**：
```java
// 新增参数处理
boolean enableExtraction = JSONUtilities.getBoolean(optionObj, "enableExtraction", false);
boolean includeFileDetails = JSONUtilities.getBoolean(optionObj, "includeFileDetails", false);
String templateName = JSONUtilities.getString(optionObj, "extractionTemplate", "档案要素案件模板");
String aimpServiceUrl = JSONUtilities.getString(optionObj, "aimpServiceUrl", "");
// 档号拼接规则配置
JSONObject archiveNumberConfigObj = optionObj.optJSONObject("archiveNumberConfig");

// 可选：文件元数据导入（仅当用户勾选时）
if (includeFileDetails) {
    FilesImporter.generateFileList(file, optionObj);
    FilesImporter.loadData(job.project, job.metadata, job, fileRecords);

    SheetData fileDetailSheet = new SheetData("file-details", "文件详情", "");
    fileDetailSheet.rows = job.project.rows;
    fileDetailSheet.columnModel = job.project.columnModel;
    fileDetailSheet.recordModel = job.project.recordModel;
    job.project.addSheetData(fileDetailSheet);
}

// 如果启用提取，执行要素提取
if (enableExtraction) {
    ExtractionTemplate template = ExtractionTemplate.fromName(templateName);
    ArchiveNumberConfig archiveNumberConfig =
        ArchiveNumberConfig.fromJSON(archiveNumberConfigObj);

    // 获取AIMP服务地址（优先级：参数传入 > 全局配置）
    if (aimpServiceUrl == null || aimpServiceUrl.isEmpty()) {
        aimpServiceUrl = CheckAimpConnectionCommand.getConfiguredServiceUrl();
    }

    // 扫描目录，以文件夹/PDF为单位构建提取单元
    List<ExtractionUnit> units = FilesImporter.scanExtractionUnits(file);

    // 执行要素提取
    FileElementExtractor extractor = new FileElementExtractor(
        aimpServiceUrl, template, archiveNumberConfig);
    SheetData extractionSheet = extractor.extractToSheetData(units);

    // 添加提取结果Sheet
    job.project.addSheetData(extractionSheet);
}

// 设置多Sheet模式
if (includeFileDetails && enableExtraction) {
    job.project.isMultiSheetProject = true;
    job.project.setActiveSheet("extraction-result");
}
```

**doCreateProject 改造**：基本不变，项目注册逻辑已支持多Sheet持久化。

#### 3.4.4 改造 FilesImporter

新增方法 `scanExtractionUnits`，扫描目录结构，以文件夹/PDF为单位构建提取单元列表：

```java
/**
 * 扫描目录，以文件夹或PDF文件为最小单位生成提取单元列表。
 * - 包含图像文件的子文件夹 → 一个ExtractionUnit(type=folder)
 * - 独立的PDF文件 → 一个ExtractionUnit(type=pdf)
 */
public static List<ExtractionUnit> scanExtractionUnits(File rootDir) {
    List<ExtractionUnit> units = new ArrayList<>();

    File[] children = rootDir.listFiles();
    if (children == null) return units;

    for (File child : children) {
        if (child.isDirectory()) {
            // 如果子目录包含图像文件，作为一个提取单元
            List<String> imageFiles = listImageFiles(child);
            if (!imageFiles.isEmpty()) {
                ExtractionUnit unit = new ExtractionUnit();
                unit.setPath(child.getAbsolutePath());
                unit.setType("folder");
                unit.setFiles(imageFiles);
                units.add(unit);
            }
            // 递归子目录（根据需要可配置递归深度）
        } else if (child.getName().toLowerCase().endsWith(".pdf")) {
            ExtractionUnit unit = new ExtractionUnit();
            unit.setPath(child.getAbsolutePath());
            unit.setType("pdf");
            units.add(unit);
        }
    }
    return units;
}
```

#### 3.4.5 提取调用方式选择

对于图像文件（jpg/png/tif等），需要先进行 OCR 识别再提取要素。有两种调用策略：

**策略A（推荐）：直接使用 extractContent 一步调用**
1. 调用 `AimpClient.extractContent(folderPath, keyList)` 直接获取 OCR + 要素提取结果
2. 服务端自动处理文件夹内多图像的拼合与多页识别

**策略B：先 extractContent 获取OCR，再 llmAnalyze 提取要素**
1. 调用 `extractContent` 获取OCR文本
2. 将OCR文本作为 context 调用 `llmAnalyze` 提取结构化要素

当前方案推荐**策略A**，因为 `extractContent` 已有成熟的 OCR + 要素提取 Pipeline 实现，可以直接复用。

### 3.5 前端改造详细设计

#### 3.5.1 改造 import-from-local-dir.js

在目录选择表单中，新增提取选项配置区域：

```javascript
// 新增HTML结构（插入到form中nextButton之前）
var extractionPanel = $('<div id="extractionOptionsPanel">')
    .addClass('extraction-options-panel');

// 启用信息提取复选框
var enableCheck = $('<label>')
    .append($('<input type="checkbox" id="enableExtraction">'))
    .append(' 启用文件信息提取');
extractionPanel.append(enableCheck);

// 可选：同时导出文件元数据
var fileDetailsCheck = $('<label>')
    .append($('<input type="checkbox" id="includeFileDetails">'))
    .append(' 同时导出文件元数据');
extractionPanel.append(fileDetailsCheck);

// 模板选择下拉框
var templateSelect = $('<div id="templateSelectDiv">')
    .hide()
    .append($('<label>').text('提取模板：'))
    .append($('<select id="extractionTemplate">')
        .append($('<option value="档案要素案件模板" selected>').text('档案要素案件模板'))
        .append($('<option value="档案要素案卷模板">').text('档案要素案卷模板'))
    );
extractionPanel.append(templateSelect);

// 档号拼接规则配置按钮（参考质量规则"配置路径"弹窗实现）
var archiveNumberRow = $('<div id="archiveNumberConfigDiv">')
    .hide();
$('<button class="button"></button>')
    .text('配置档号拼接规则')
    .on('click', function() {
        self._showArchiveNumberConfigDialog();
    })
    .appendTo(archiveNumberRow);
// 档号预览
$('<span class="archive-number-preview"></span>').appendTo(archiveNumberRow);
extractionPanel.append(archiveNumberRow);

// 联动显示
$('#enableExtraction').on('change', function() {
    if (this.checked) {
        $('#templateSelectDiv').show();
        $('#archiveNumberConfigDiv').show();
    } else {
        $('#templateSelectDiv').hide();
        $('#archiveNumberConfigDiv').hide();
    }
});
```

#### 3.5.1.1 档号拼接规则配置弹窗

参考 `QualityAlignment._showPathConfigDialog()` 实现，新增档号配置弹窗：

```javascript
/**
 * 显示档号拼接规则配置弹窗
 * 复用质量规则"配置路径"的UI模式：
 * - 路径层级选择（从目录层级中拖选）
 * - 分隔符模式 / 模板模式 切换
 * - 实时预览档号结果
 */
self._showArchiveNumberConfigDialog = function() {
    var frame = $('<div class="dialog-frame" style="width: 750px;"></div>');
    var header = $('<div class="dialog-header"></div>')
        .text('配置档号拼接规则')
        .appendTo(frame);
    var body = $('<div class="dialog-body"></div>').appendTo(frame);

    // 路径层级选择区域
    // （展示扫描到的目录层级，用户勾选/拖拽参与档号生成的层级）

    // 拼接模式选择（分隔符 / 模板）
    // 分隔符输入框
    // 模板输入框
    // 实时预览

    // 确认/取消按钮
    // ... (参考 quality-alignment.js 中 _showPathConfigDialog 实现)
};
```

#### 3.5.2 改造 files-importing-controller.js

在 `getOptions()` 方法中新增提取相关选项：

```javascript
Refine.FilesImportingController.prototype.getOptions = function() {
    var options = {
        // 现有选项
        directoryJsonValue: JSON.stringify(this._doc.directoryJsonObj),
        skipDataLines: this._parsingPanelElmts.skipDataLinesInput[0].value,

        // 新增选项
        enableExtraction: $('#enableExtraction').is(':checked'),
        includeFileDetails: $('#includeFileDetails').is(':checked'),
        extractionTemplate: $('#extractionTemplate').val(),
        archiveNumberConfig: self._archiveNumberConfig || {}
    };
    return options;
};
```

### 3.6 数据持久化方案

利用已有的多Sheet持久化机制，无需额外开发。项目存储格式：

```
{projectID}.project/
├── project.txt                    # 项目元数据
│   ├── isMultiSheetProject=true/false  (取决于是否同时导出文件详情)
│   ├── activeSheetId=extraction-result
│   ├── sheetCount=1或2
│   ├── sheet:file-details=        # (可选) 文件详情Sheet
│   │   ├── columnModel=...
│   │   └── rowCount=...
│   └── sheet:extraction-result=   # 信息提取结果Sheet
│       ├── columnModel=...
│       └── rowCount=...
└── history/
    └── ...
```

## 4. 依赖关系管理

### 4.1 模块依赖
FilesExtension 需要引用 data-quality 扩展中的 `AimpClient` 类。两种处理方式：

**方案A（推荐）：将 AimpClient 提取到公共模块**
将 `AimpClient`、`LlmAnalyzeResult` 等通用类提取到 `modules/core` 或新建 `common-aimp` 公共模块，供多个扩展共用。

**方案B：FilesExtension 中内置简化版 AimpClient**
在 FilesExtension 中新增一个简化版的 `AimpLlmClient`，仅实现 `llmAnalyze` 和 `extractContent` 方法，避免模块间耦合。

当前建议先采用**方案B**快速实现，后续统一重构为方案A。

### 4.2 Maven依赖
FilesExtension 的 `pom.xml` 需要添加对 Jackson JSON 处理库的依赖（如尚未包含）。

### 4.3 前端依赖
无新增前端依赖，复用现有 jQuery 和 OpenRefine 前端框架。

## 5. 提取流程时序图

```
用户            前端UI           FilesImportingController    FilesImporter    FileElementExtractor    AIMP Service
 │                │                      │                      │                    │                    │
 │─选择目录───────>│                      │                      │                    │                    │
 │  配置提取选项──>│                      │                      │                    │                    │
 │  配置档号规则──>│                      │                      │                    │                    │
 │─点击预览───────>│                      │                      │                    │                    │
 │                │──POST preview──────>│                      │                    │                    │
 │                │                      │                      │                    │                    │
 │                │                      │──(if 导出元数据)────>│                    │                    │
 │                │                      │  generateFileList──>│                    │                    │
 │                │                      │<──Sheet1(文件详情)──│                    │                    │
 │                │                      │                      │                    │                    │
 │                │                      │──scanExtractionUnits>│                    │                    │
 │                │                      │<──文件夹/PDF单元列表│                    │                    │
 │                │                      │                      │                    │                    │
 │                │                      │──(if extraction)────────────────────────>│                    │
 │                │                      │                      │                    │─生成档号(路径规则)│
 │                │                      │                      │                    │──extractContent──>│
 │                │                      │                      │                    │<──OCR+要素结果────│
 │                │                      │                      │                    │  (逐单元循环)      │
 │                │                      │                      │                    │                    │
 │                │                      │                      │                    │─(案卷模板额外步骤)│
 │                │                      │                      │                    │──概括题名(LLM)───>│
 │                │                      │                      │                    │<──汇总题名────────│
 │                │                      │                      │                    │─合并去重责任者─────│
 │                │                      │                      │                    │                    │
 │                │                      │<──Sheet2(信息提取)──────────────────────│                    │
 │                │                      │──组装项目────────────>│                    │                    │
 │                │<──预览数据───────────│                      │                    │                    │
 │<──展示Tab视图──│                      │                      │                    │                    │
 │                │                      │                      │                    │                    │
 │─确认创建───────>│                      │                      │                    │                    │
 │                │──POST create-project>│                      │                    │                    │
 │                │                      │──注册项目+持久化───>│                    │                    │
 │                │<──项目创建成功───────│                      │                    │                    │
 │<──跳转项目页───│                      │                      │                    │                    │
```

## 6. 实施计划

### 6.1 第一阶段：后端核心功能（预计4-5天）✅ 已完成 (2026-02-26)
1. ✅ 新增 `ExtractionTemplate` 枚举类 — 定义案件/案卷模板列结构、提取键、汇总标志
2. ✅ 新增 `ExtractionUnit` 数据类 — 表示文件夹/PDF提取单元，含路径、类型、文件列表
3. ✅ 新增 `ArchiveNumberConfig` 配置类 — 档号拼接规则，支持分隔符/模板两种模式，含fromJSON解析
4. ✅ 新增 `AimpLlmClient` 简化版客户端 — 内置extractContent(OCR+要素提取)和llmAnalyze(LLM分析)
5. ✅ 新增 `FileElementExtractor` 核心提取类 — 逐单元提取要素、生成档号、组装SheetData；案卷模板含题名概括+责任者汇总
6. ✅ 改造 `FilesImporter` — 新增scanExtractionUnits/scanDirForUnits/isImageFile三个方法
7. ✅ 改造 `FilesImportingController` — 支持enableExtraction/includeFileDetails/archiveNumberConfig/extractionTemplate参数
8. ✅ 修复 `pom.xml` — 添加core模块provided scope依赖以引用SheetData
9. ⬜ 单元测试 — 待补充

### 6.2 第二阶段：前端交互（预计3-4天）
1. ✅ 改造 `import-from-local-dir.js` 增加提取选项UI + 文件详情可选
2. ✅ 实现档号拼接规则配置弹窗（参考质量规则路径配置弹窗）
3. ✅ 改造 `files-importing-controller.js` 传递提取参数和档号配置
4. ✅ 多Sheet Tab预览支持
5. ✅ CSS样式适配
6. ⬜ 前端交互测试

### 6.3 第三阶段：集成测试与优化（预计2-3天）
1. ⬜ 端到端测试：目录选择→配置提取+档号规则→预览→创建项目→多Sheet展示
2. ⬜ 错误处理测试：AIMP服务不可用、文件无法识别等场景
3. ⬜ 性能优化：大量文件夹时的提取效率
4. ⬜ 案卷模板汇总行完善（题名概括、责任者汇总验证）
5. ⬜ 档号生成准确性验证

## 7. 风险分析

### 7.1 性能风险
- **风险**：大量文件夹/PDF逐个调用 LLM 提取，可能导致长时间等待
- **缓解**：
  1. 提取过程增加进度条显示
  2. 支持异步提取模式，先创建项目，后台异步填充提取结果
  3. 设置单次导入提取单元数上限（建议≤200）
  4. 考虑批量提取优化

### 7.2 AIMP服务依赖
- **风险**：AIMP LLM服务不可用时功能降级
- **缓解**：
  1. 导入时检测AIMP服务连接状态
  2. 服务不可用时给出清晰提示，允许仅导入文件详情
  3. 支持后续手动触发重新提取

### 7.3 提取准确度
- **风险**：LLM提取结果可能不准确
- **缓解**：
  1. 提取结果中包含提取状态字段，标识成功/失败/部分提取
  2. 提取结果作为数据Sheet展示，用户可直接编辑修正
  3. 预留重新提取单行/批量提取的操作入口

### 7.4 模块耦合
- **风险**：FilesExtension 依赖 data-quality 扩展的 AimpClient
- **缓解**：采用方案B内置简化版客户端，后续统一提取公共模块

### 7.5 档号生成准确性
- **风险**：用户配置的路径拼接规则可能不正确，导致档号错误
- **缓解**：
  1. 配置弹窗中提供实时预览，用户可立即看到生成结果
  2. 档号列支持编辑修正
  3. 提供目录层级可视化选择，降低配置难度

## 8. 未来扩展方向

1. **自定义模板**：支持用户自定义提取字段模板
2. **批量重新提取**：在项目视图中支持选中行重新提取
3. **提取规则编辑**：可视化编辑提取提示词模板
4. **案卷汇总规则引擎**：案卷模板汇总行的生成规则可配置化
5. **多格式文件支持**：扩展支持PDF、Word等文档格式的直接提取
6. **提取结果比对**：与数据库中已有档案数据进行比对校验

## 9. 相关文档引用

| 文档 | 路径 | 说明 |
|------|------|------|
| 多Sheet技术方案 | `docs/multi-sheet-implementation-plan.md` | 多Sheet架构设计与实现 |
| AIMP LLM API | `docs/AIMP_LLM_API.md` | LLM分析接口文档 |
| AimpClient实现 | `extensions/data-quality/src/.../aimp/AimpClient.java` | Java端AIMP调用实现 |
| FilesImporter | `extensions/FilesExtension/src/.../FilesImporter.java` | 文件导入核心类 |
| FilesImportingController | `extensions/FilesExtension/src/.../FilesImportingController.java` | 文件导入控制器 |
| SheetData模型 | `modules/core/src/.../model/SheetData.java` | 多Sheet数据模型 |
| ruyi-aimp提取API | `src/api/web_service.py` | Python端档案要素提取实现 |


