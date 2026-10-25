# 批量档案要素数据质量检查——比对错位修复与分件方式统一设计

> 状态：已实现，待回归测试（v1.3）
> 日期：2026-09-22
> 修订记录：
> - v1.0（2026-09-22）：初稿。解决「案卷级提取后直接启动数据质量检查」的比对错位问题；补充案卷内分件编制方式调研；新增列头语义归一化、按件资源切片、对齐键改造、页号全局化四层机制。
> - v1.1（2026-09-22）：修复相似度阈值链路断点。新增 §3.7：内容比对最终判定改由规则级阈值 `ContentComparisonRule.threshold` 决定，`aimpConfig.similarityThreshold`（无界面入口，恒为默认 0.8）不再参与判定；修正错误分级的小数/百分数单位错误。对应回归项见 §7.3。
> - v1.2（2026-09-22）：新增 §3.8 内容检查进度上报。新增 `contentCheckCurrentItem` / `contentCheckCurrentItemPages` 两个进度字段，界面显示"正在处理第 i/N 件、本件几页、已耗时"并给进度条加进行中动效，解决内容检查阶段进度条长时间静止的观感问题。
> - v1.3（2026-09-22）：修订 §3.8。状态文本改为**两行**（第一行进行中检查、第二行错误累积明细），完成态同样两行；`get-check-progress` 补出 `imageQualityErrors`；**回退进度条条纹流动动效**（与其他检查项状态一致）；修复 `$.i18n` 不替换 `{0}` 占位符导致的占位符直出问题。
> 关联文档：[files-extension-batch-title-extraction-design.md](files-extension-batch-title-extraction-design.md)（批量题名提取，含案卷模式自动分件）、[../ruyi/dev/data_quality_feature_design.md](../ruyi/dev/data_quality_feature_design.md)（数据质量功能设计）、[four-character-check-plugin-design.md](four-character-check-plugin-design.md)

---

## 1. 背景与问题定位

### 1.1 两条业务链路的差异

数据质量检查（内容比对 / 资源关联）需要把「条目表的一行」与「磁盘上的一个件（若干页图像或一个 PDF）」关联起来。系统内存在两条来源完全不同的链路：

| 维度 | 链路 A：人工录入 → 批量导入 → 关联文件夹 | 链路 B：案卷级提取 → 直接启动检查 |
|------|------------------------------------------|-----------------------------------|
| 条目来源 | 人工著录的 Excel/多 sheet 项目 | FilesExtension 案卷模式自动分件后写入的行 |
| 列头命名 | 人工拟定的列名（如「起止页号」「文件夹路径」） | 提取模板产出的列名（如「起讫页号」「源路径」） |
| 资源粒度 | 一行 ⇔ 一个文件夹，文件夹内文件即该件全部页 | 同一卷 N 件**共用同一个文件夹**，只在「起止页号」上区分 |
| 行序 | 与文件夹序一致 | 与目录自然序可能不同 |

> 约定：链路 A 中「一行对应一个文件夹」是既有前提，不做改动；链路 B 的差异必须由本次改造吸收。

### 1.2 三层错位根因

比对错位不是单点 Bug，而是三层机制同时失配：

| 层级 | 失配点 | 表现 |
|------|--------|------|
| **列头语义层** | 规则列名与项目实际列名精确匹配，且匹配不到时**静默跳过** | 该要素根本不参与比对，产出「无错误」的**假通过**，掩盖真实错位 |
| **资源粒度层** | 取 `resourcePath` 下的**整目录全部图片**，无视本件页区间 | 同卷多件互相覆盖；每件页数都等于目录总页数，页数校验必错 |
| **对齐键层** | 用 **rowIndex（行号）** 隐式对齐 AIMP 返回的比对结果 | 两条链路行序不一致 → 结果张冠李戴 |

### 1.3 放大因素

- **PDF 分件页号恒为 1**：`BatchExtractionManager` 的 pdfMode 分支原写 `p.startPage = 1`，同卷多个 PDF 件页区间完全重叠，按区间切页必然切错。
- **AIMP 结果以路径为键互相覆盖**：`extraction_results[img_path]`，而同卷 N 件的 `path` 相同 → 后一件覆盖前一件。

---

## 2. 行业调研：案卷内分件编制方式

为把「分件方式」做成通用能力而非硬编码，对档案行业卷内分件的页号编制方式做了梳理（含联网查证）：

### 2.1 五类编制方式

| 类 | 编制方式 | 字段组合 | 典型来源 |
|----|----------|----------|----------|
| A | 件号 + 页数 | `件号` + `页数` | DA/T 22—2015（主流，卷内目录标准形态） |
| B | 起始页号 + 卷尾号 | `起始页号` + `卷尾号`（卷尾号 = 下一件起始页 − 1） | 宁夏地方实践 |
| C | 起始页号 + 页数 | `起始页号` + `页数` | 福建 DB35/T 1856—2019 |
| D | 起止 / 起讫 / 首尾页号 | 单列区间串（`0001-0005`、`0001～0005`、`1至5`） | 多地通用写法 |
| E | 件号即页号 | `件号` = 卷内页序号 | 内蒙古、西藏 |

同时存在混合写法（如「起始页码 + 终止页码」两列并存、全角数字与全角连字符、数字前后颠倒等）。

### 2.2 调研结论：不硬编码方式，统一为语义槽位

**设计决策**：不为每种编制方式写分支，而是把上述字段全部归入**语义槽位（Slot）**，由统一的解析器按固定优先级推导「本件页区间」：

```
起止区间串（PAGE_RANGE）
  → 起始页号 + 终止页号（START_PAGE + END_PAGE）
    → 起始页号 + 页数（START_PAGE + PAGE_COUNT）
      → 均无页号字段 ⇒ 整目录成件（链路 A 天然兼容）
```

这样一份规则可以同时适配链路 A 与链路 B，且新增分件方式时只需往别名词典补别名，无需改流程代码。
---

## 3. 设计改动（六层）

### 3.1 列头语义归一化层

新增 `com.google.refine.extension.quality.util.ColumnSemantics`。

- 把「规则里配置的列名」解析到项目实际列名，匹配顺序：
  **精确 → 归一化精确 → 同槽位别名 → 前后缀包含兜底（取最短候选）**
- 归一化算法：`Normalizer.NFKC`（全角→半角）→ 去掉所有空白 → 转小写。
- **未匹配改为阻断**：内容比对启动前逐条校验规则列能否解析，解析不到则置 `serviceUnavailable`，消息以 `UNMATCHED_COLUMNS:` 前缀携带未匹配列名清单，直接返回，不再静默跳过。

### 3.2 资源切分层

新增 `com.google.refine.extension.quality.util.ResourceSelector`，由内容比对（`ContentChecker`）与资源关联检查（`ResourceChecker`）**共用**，避免两处对「一件包含哪些页」的理解分叉。

- `resolvePageColumns(columnNames)`：一次性按槽位解析出区间/起始/终止/页数/文件名 5 个列名。
- `resolvePageRange(...)`：按 2.2 的优先级推导 `{start, end}`（1-based，含端点）；无页号字段返回 `null`（整目录）。
- `resolvePieceFiles(resourcePath, fileName, pageRange)`：
  - 若路径本身是文件，或「目录 + 文件名」命中 → 返回该单个文件（PDF 分件场景）；
  - 否则列目录内资源文件，按 `NATURAL_ORDER` 自然序排序后按页区间切片；
  - 区间起点 `< 1` 或 `> 文件数` → 返回 `null`（记 warn 并跳过该行）。
- `NATURAL_ORDER`：与 FilesExtension `UnitScanner.NATURAL_ORDER` **等价**的数字感知排序。**这是硬约束**——排序若与提取阶段不一致就会切错页。

### 3.3 对齐键层（dataKey）

`ContentChecker.buildDataKey(...)` 生成规则：

| 优先级 | 条件 | dataKey |
|--------|------|---------|
| 1 | 「件号」非空 | `案卷号|件号`（案卷号为空时退化为 `件号`） |
| 2 | 有资源路径 | `资源路径|起始页`（起始页来源：起始页号 → 区间串首值 → 终止页号） |
| 3 | 都不满足 | `null`（结果按行号兜底匹配） |

AIMP 返回结果按该键索引，`processComparisonResults` 先按键直查，再回退旧的行号格式，保证向后兼容。

### 3.4 页号语义全局化

`BatchExtractionManager` 的 pdfMode 分支引入 `pageOffset` 累加器，使 PDF 件的 `startPage/endPage` 表示**卷内全局页序号**（跨件累加前序件页数），不再恒为 1：

- 成功：`startPage = pageOffset + 1`，`endPage = pageOffset + pageCount`，`pageOffset += pageCount`
- 提交失败 / 提取失败：按 1 页占位，`pageOffset += 1`

**统一口径**：无论图片件还是 PDF 件，「页」= 卷内全局页序号，与「起止页号」列语义一致。

### 3.5 AIMP 侧契约（`/extract/archive/batch_compare`）

- 图像条目新增可选字段 **`imageFiles`**（本件切分后的绝对路径清单）。AIMP 侧**优先**使用它，缺省时才回退为扫描目录；单文件 PDF 走单文件分支。
- 提取结果改以 **`dataKey`** 为键（此前以 `img_path` 为键，同卷多件会互相覆盖）；比对阶段优先按 `dataKey` 直查，再回退遍历兼容。

### 3.6 前端阻断与提示

- 启动前校验：遍历内容比对规则，任一 `rule.column` 无匹配列即 `alert` 并中断（附提示文案），不再放行。
- 结果页/完成回调：按 `UNMATCHED_COLUMNS:` 前缀分流——列头未匹配走专用提示（不显示「配置 AIMP」按钮），其余仍按 AI 服务不可用处理。
- 新增 i18n 键：`data-quality-extension/unmatched-columns-block`、`data-quality-extension/unmatched-columns-hint`（中英文均已补齐）。

### 3.7 相似度阈值链路（规则级阈值生效）

内容比对的**最终判定权在 Java 侧**，以「内容比对规则界面按要素填写的相似度阈值」为准。

- **唯一判定依据**：`ContentComparisonRule.threshold`（整数，0~100，界面默认 100），与会话内相似度比较：

  ```
  similarityPercent = elemResult.getSimilarity() * 100        // AIMP 返回相似度是 0~1 小数
  similarityPercent >= rule.getThreshold() ⇒ 通过，否则记错误
  ```

- **`aimpConfig.similarityThreshold` 的定位降级**：该字段没有规则界面入口，前端 `_aimpConfig` 只落盘 `{serviceUrl, interfaceMode}`，因此反序列化后恒为 Java 默认值 `0.8`。它仅作为下发 AIMP 的参考参数，**不再参与判定**，避免"界面设 100%、实际按 0.8 判过"的静默失效。
- **错误分级修正**：`similarityPercent < 50` → `content_mismatch`（内容不匹配），否则 → `content_warning`（相似度偏低，仍计入内容问题并在单元格渲染）。此前误用小数与百分数比较（`similarity < 50` 恒真），导致分级失效。
- **AIMP 侧 `has_error` 不再作为判据**：其由 AIMP 自身全局阈值算出，会与规则级阈值冲突（阈值放宽时反而多报）。AIMP 只需保证逐要素返回 `similarity`。

### 3.8 内容检查进度上报（件内等待可见）

AIMP 以「件」为原子单位**同步**处理：一次 `/extract/archive/batch_compare` 必须把该件**全部页**的 OCR + LLM 提取 + 比对做完才返回，件内没有任何中间信号。实测单件耗时随页数线性增长（4 页 9s、10 页 26s），期间进度条只能静止。

- **批大小固定为 1 件**：`aimpConfig.batchSize` 默认 1，规则界面无该入口；件不可再拆（件内页序构成提取的增量上下文，拆到页会破坏依赖）。
- **新增两个进度字段**（`QualityCheckTask` → `get-check-progress` 输出）：
  - `contentCheckCurrentItem`：正在处理件的标识（`dataKey`；无件号时回退「第 N 行」），**批次发起前写入、回包后清空**；
  - `contentCheckCurrentItemPages`：本件页数。
- **进度接口错误口径补齐**：`get-check-progress` 除已有的 `formatErrors` / `resourceErrors` / `contentErrors` 外，补出 `imageQualityErrors`，使进行中与完成态的错误明细口径一致（四项检查）。
- **状态文本两行化**（`run-check-dialog.html` 新增 `statusText` + `statusSubtext` 两个 `<p>`）：
  - 第一行＝**当前进行中的检查**，形如
    `内容检查 (1/3) 正在处理: 035（本件 10 页） 已耗时 12 秒`；
  - 第二行＝**错误累积明细**（弱化样式 `.status-subtext`），形如
    `总错误数 6: （文件资源关联检查: 6）`，无错误时为空串不占视觉；
  - 完成态同样两行：`检查完成，总错误数: 68 个` / `(数据格式检查: 1, 文件资源关联检查: 6, 内容比对检查: 4, 图像质量检查: 57)`。
- **占位符替换**：OpenRefine 的 `$.i18n(key, arg)` **不会**替换语言文件里的 `{0}` / `{1}`，必须由调用方 `.replace('{0}', v).replace('{1}', v)` 手动填充，否则界面直出 `{0}`。
- **动效已回退**：进度条**不加**条纹流动/indeterminate 动效，与其他检查项的运行状态保持一致；仅保留 `width` 的 0.3s 过渡。
- **口径说明**：进度百分比仍按「件」跳变（阶段权重 20%→49%），本次只补足"正在处理哪一件、本件多大"的可感知性；件内真实百分比需 AIMP 侧暴露页级进度（见 §8）。

---

## 4. 数据结构

### 4.1 语义槽位 `ColumnSemantics.Slot`

```java
public enum Slot {
    VOLUME_NO,        // 案卷号 / 卷号
    PIECE_NO,         // 件号 / 序号 / 流水号
    START_PAGE,       // 起始页号 / 起始页码 / 首页号 / 自页号
    END_PAGE,         // 终止页号 / 止页号 / 尾页号 / 卷尾号
    PAGE_RANGE,       // 起止页号 / 起讫页号 / 首尾页号 / 页号区间
    PAGE_COUNT,       // 页数 / 张数 / 总页数
    RESOURCE_PATH,    // 文件夹路径 / 源路径 / 资源路径
    RESOURCE_FILE,    // 文件名 / 文档名（指向具体文件）
    TITLE,            // 题名 / 卷内题名 / 文件题名
    RESPONSIBLE_PARTY,// 责任者 / 作者 / 发文单位
    DOCUMENT_NUMBER,  // 文号 / 发文号 / 文件编号
    ISSUE_DATE,       // 成文日期 / 发文时间 / 形成日期
    UNKNOWN
}
```

别名词典要点（完整表见源码 `ColumnSemantics` 静态块）：

| 槽位 | 代表性别名（中文 / 拼音缩写 / 英文） |
|------|--------------------------------------|
| VOLUME_NO | 案卷号、卷号、案卷、目录号 / ajh、jh / volumeno、volume_no |
| PIECE_NO | 件号、顺序号、流水号 / jjh / itemno、pieceno |
| START_PAGE | 起始页号、首页号、自页号、开始页码 / qsph / startpage、frompage |
| END_PAGE | 终止页号、止页号、尾页号、卷尾号 / zzph / endpage、topage |
| PAGE_RANGE | 起止页号、起讫页号、首尾页号、页码区间、页号 / qzph / pagerange |
| PAGE_COUNT | 页数、张数、总页数、件内页数 / ys / pagecount |
| RESOURCE_PATH | 文件夹路径、源路径、资源路径、归档路径 / lj / folderpath、path |
| RESOURCE_FILE | 文件名、文档名、文件名称 / wjm / filename |

> 注意：「文件夹名」**不**归入 `RESOURCE_FILE`（它是案卷/件标识，不是具体文件），保持 `UNKNOWN`。

关键方法：

| 方法 | 作用 |
|------|------|
| `normalize(String)` | NFKC + 去空白 + 小写 |
| `slotOf(String)` | 列名 → 槽位 |
| `findColumnNameBySlot(Slot, List<String>)` | 在列清单中按槽位找第一列 |
| `resolveColumnName(String, List<String>)` | 配置列名 → 实际列名（四级匹配，失败返回 `null`） |
| `parsePageRange(String)` | 区间串 → `{start, end}`（前两个数字，自动 min/max） |

### 4.2 `ResourceSelector` 关键签名

```java
int[]  resolvePageRange(Row, Map<String,Integer>, rangeCol, startCol, endCol, countCol)
PageColumns resolvePageColumns(List<String> columnNames)   // {range,start,end,count,file}
int[]  PageColumns.resolveRange(Row, Map<String,Integer>)
List<File> resolvePieceFiles(String resourcePath, String fileName, int[] pageRange)
List<File> listResourceFiles(File folder)                  // 自然序
String resolveExplicitFile(String resourcePath, String fileName)
String cellValue(Row, Map<String,Integer>, String columnName)
Integer parseInt(String text)                              // 区间串取首值
public static final List<String> RESOURCE_EXTENSIONS = [pdf,jpg,jpeg,png,tif,tiff,bmp,gif,webp]
public static final Comparator<String> NATURAL_ORDER
```

### 4.3 `imageData` 条目（Java → AIMP）

```json
{
  "path":       "D:\\...\\卷001",
  "dataKey":    "卷001|003",
  "imageNames": "0001.jpg,0002.jpg",
  "imageCount": 2,
  "imageFiles": ["D:\\...\\0001.jpg", "D:\\...\\0002.jpg"]
}
```

### 4.4 `excelData` 条目（Java → AIMP）

```json
{
  "dataKey": "卷001|003",
  "rowNum":  7,
  "题名":    "…",
  "责任者":  "…",
  "文号":    "…",
  "成文日期":"…"
}
```

### 4.5 错误/阻断信道

| 位置 | 字段 | 说明 |
|------|------|------|
| `CheckResult` | `serviceUnavailable` / `serviceUnavailableMessage` | JSON 序列化字段名同名 |
| `ContentChecker` | `UNMATCHED_COLUMNS_PREFIX = "UNMATCHED_COLUMNS:"` | 列头未匹配时写入 message 前缀 |
| `RunQualityCheckCommand` | 响应聚合 | 按 content → typo → imageQuality → format → resource 顺序取首个非空 message 上抛 |
| 前端 | `run-check-dialog.js` / `quality-alignment.js` | 按前缀分流文案 |
---

## 5. 程序结构（调用链）

### 5.1 Java 后端（内容比对主链）

```
RunQualityCheckCommand
  └─ ContentChecker.runCheck()
       ├─ 构建 columnNames（保序）
       ├─ [阻断校验] ColumnSemantics.resolveColumnName × 每条规则
       │      └─ 未匹配 ⇒ setServiceUnavailable(UNMATCHED_COLUMNS:…) ⇒ return
       ├─ aimpClient.testConnection()
       ├─ isMultiSheetProject() ? checkVolumeTitleComparison()      // 链路 A 多 sheet（未改动）
       ├─ collectValidRows(columnNames, columnIndexMap, resourceConfig)
       │      ├─ buildResourcePath()   ← pathFields 经 ColumnSemantics 解析
       │      ├─ ResourceSelector.resolvePageColumns() / resolveRange() / resolvePieceFiles()
       │      └─ buildDataKey()        ⇒ 案卷号|件号
       ├─ prepareBatchExcelData()  → rowMap{dataKey,rowNum,要素…}
       ├─ prepareBatchImageData()  → imageMap{path,dataKey,imageNames,imageCount,imageFiles}
       ├─ AimpClient.batchCompare(serviceUrl, excelData, imageData)
       │      └─ POST {serviceUrl}/extract/archive/batch_compare（multipart: excel_data / image_data）
       └─ processComparisonResults()
              ├─ 按 dataKey 直查（回退 rowIndex / rowIndex_rowNum）
              └─ 错误列名用 ColumnSemantics 解析后的实际列名

ResourceChecker.runCheck()
  ├─ ResourceSelector.resolvePageColumns(columnNames)
  ├─ buildResourcePath()            ← 字段名经 ColumnSemantics 解析
  ├─ ResourceSelector.resolvePieceFiles()   // 页数校验对象改为「本件文件数」
  └─ 文件数校验列 = ColumnSemantics.resolveColumnName(fileChecks.countColumn)
```

### 5.2 前端

```
run-check-dialog.js
  _startCheck()
    ├─ 资源未配置 ⇒ 警告并退出
    ├─ [阻断] QualityAlignment._contentRules × _findMatchingColumns()
    │      └─ 存在未匹配列 ⇒ alert(unmatched-columns-block + hint) ⇒ return
    └─ _loadImageQualityRuleAndStartCheck() ⇒ 发 command 请求
  _onCheckComplete(response)
    └─ response.serviceUnavailable ⇒ 按 'UNMATCHED_COLUMNS:' 前缀分流提示

进度呈现（两行状态）
  _doStartCheck()      ⇒ statusText='检查中…'、statusSubtext=''
  _pollProgress()      ⇒ 每 500ms GET command/data-quality/get-check-progress
  _updateProgress()    ⇒ line1 = 阶段 + (已/总件) + 「正在处理: 件（本件 N 页）」+「已耗时 X 秒」
                         line2 = 总错误数 + 四项检查错误明细（无错误为空）
                         ⚠ 文案占位符须手动 .replace('{0}',..).replace('{1}',..)
  _onCheckComplete()   ⇒ line1 = 「检查完成，总错误数: N 个」
                         line2 = (四项检查明细)

quality-alignment.js
  _renderResultsTab()
    └─ result.serviceUnavailable ⇒ 渲染提示块
           ├─ 前缀命中 ⇒ 列头未匹配文案（不显示「配置 AIMP」）
           └─ 否则     ⇒ AI 服务不可用文案
```

### 5.3 AIMP 服务

```
POST /extract/archive/batch_compare     (web_service.py L227 → batch_compare_archive_elements)
  ├─ 解析 image_data：
  │    explicit_files = imageFiles ∪ image_files（过滤存在）
  │    result_key     = dataKey ?: img_path
  │    is_single_file = (len(explicit_files)==1 且 .pdf) 或（无显式清单时非目录）
  │    ├─ 多页分支：image_files = explicit_files ?: _find_image_files_in_directory(path)
  │    └─ 单文件分支：single_path = explicit_files[0] ?: img_path
  ├─ pipeline_manager.submit_task(...) → _wait_for_task_sync()
  │    extraction_results[result_key] = {elements, dataKey, partNumber, …}
  └─ 比对阶段：
       unique_key = partNumber ? f"{dataKey}_{partNumber}" : dataKey
       matched    = extraction_results.get(unique_key)   // 直查
                     ?: 遍历兼容旧键（dataKey/partNumber 组合）
```

> 说明：Java 侧当前**不传** `partNumber`，故两侧键一致（`dataKey`），直查即命中。

### 5.4 涉及文件清单

| 文件 | 类型 |
|------|------|
| `extensions/data-quality/src/.../util/ColumnSemantics.java` | 新增 |
| `extensions/data-quality/src/.../util/ResourceSelector.java` | 新增 |
| `extensions/data-quality/src/.../checker/ContentChecker.java` | 修改 |
| `extensions/data-quality/src/.../checker/ResourceChecker.java` | 修改 |
| `extensions/FilesExtension/src/.../importer/BatchExtractionManager.java` | 修改 |
| `ruyi-aimp/src/api/web_service.py` | 修改 |
| `extensions/data-quality/src/.../commands/GetCheckProgressCommand.java` | 修改（补出 `imageQualityErrors`） |
| `extensions/data-quality/src/.../task/QualityCheckTask.java` | 修改（进度字段） |
| `extensions/data-quality/module/scripts/dialogs/run-check-dialog.js` | 修改（两行状态文案） |
| `extensions/data-quality/module/scripts/dialogs/run-check-dialog.html` | 修改（`statusSubtext` 行） |
| `extensions/data-quality/module/styles/dialogs/run-check-dialog.css` | 修改（`.status-subtext`） |
| `extensions/data-quality/module/scripts/quality-alignment.js` | 修改 |
| `extensions/data-quality/module/langs/translation-zh.json` / `translation-en.json` | 修改 |

---

## 6. 兼容性与迁移

### 6.1 向后兼容

| 场景 | 行为 |
|------|------|
| 链路 A（人工录入，一行一目录） | 无页号字段 → `resolvePageRange` 返回 `null` → 整目录成件，行为与改造前一致 |
| 规则列名与项目列名完全一致 | 走「精确匹配」分支，零影响 |
| 旧项目无「件号」列 | `dataKey` 回退「路径\|起始页」或不生成，`processComparisonResults` 回退行号匹配 |
| AIMP 未升级（无 `imageFiles`） | AIMP 侧回退扫描目录（旧行为） |
| 多 sheet 项目 | `checkVolumeTitleComparison` 路径未改动 |

### 6.2 迁移须知

- **Java 改动需重启 OpenRefine**；前端改动浏览器 `Ctrl+F5` 即可；Python 改动需重启 AIMP 服务。
- **页号语义已变更**（统一为卷内全局页序号）。**此前已提取的历史项目需重新提取**，否则旧数据页号仍是「文件内页号」，比对仍会错位。

---

## 7. 验证与回归测试要点

### 7.1 编译与产物

| 产物 | 路径 |
|------|------|
| data-quality jar | `extensions/data-quality/module/MOD-INF/lib/jinrefine-data-quality.jar` |
| FilesExtension jar | `extensions/FilesExtension/module/MOD-INF/lib/openrefine-files-extension-0.1.0.jar` |

已验证：两 jar 修改时间晚于各自源码；jar 内含 `ColumnSemantics` / `ResourceSelector` / `ContentChecker` / `ResourceChecker` / `BatchExtractionManager` 字节码；`web_service.py` 通过 AST 解析 + `py_compile` 校验。

### 7.2 单元级探针（已通过，临时脚本已删）

| 探针 | 覆盖 | 结果 |
|------|------|------|
| SemanticsProbe | 槽位识别、别名归一化、区间串解析、四级匹配、未匹配返回 null | 35/35 |
| SliceProbe | 自然序切片、边界（起点越界）、显式文件、整目录回退 | 10/10 |

### 7.3 回归测试清单（建议）

- [ ] 链路 A 回归：人工录入项目（一行一目录）内容比对结果与改造前一致
- [ ] 链路 B 基本：单卷多件（图片件），每件**只比对本件页区间**，不串件
- [ ] 链路 B PDF：同卷多 PDF 件，页区间按卷内全局页序号累加、互不重叠
- [ ] 分件方式：分别以「起止页号」「起始+终止页号」「起始页号+页数」「件号+页数」构造项目，均能正确切片
- [ ] 列头变体：把列名改为「起讫页号」「首页号」「源路径」等别名，仍能匹配
- [ ] 阻断路径：故意让规则列名匹配不到 → 前端 alert 阻断 + 结果页显示列头未匹配提示（无「配置 AIMP」按钮）
- [ ] 资源关联：页数校验对象为本件文件数，同卷多件不再假报错
- [ ] 边界：页区间起点 > 目录文件数 → 记 warn 跳过，不中断整体检查
- [ ] 多 sheet 项目：案卷标题比对路径不受影响
- [ ] 阈值生效：规则界面把某要素阈值设为 100%，将抽取值**改动 1 个字**后重跑 → 该要素必须报错（`content_warning`）；完全未改动时不得报错
- [ ] 阈值放宽：把某要素阈值降到 50%，同一处轻微改动应不再报错（验证判定确实跟随界面配置，而非固定 0.8）
- [ ] 进度可见性：内容检查阶段第一行应出现「内容检查 (i/N) 正在处理: <件标识>（本件 N 页） 已耗时 X 秒」，且已耗时在件内递增；件回包后短暂回到「已完成 i/N 件」
- [ ] 文案两行与占位符：进行中第一行不得出现裸 `{0}` / `{1}`；第二行形如「总错误数 N: （<检查项>: n, ...）」，无错误时第二行为空
- [ ] 完成态文案：第一行「检查完成，总错误数: N 个」，第二行括号内为四项检查的错误明细
- [ ] 动效一致性：进度条无条纹流动/indeterminate 动效，与其他检查项运行状态一致

---

## 8. 已知边界与后续可选项

- **件号缺失的强约束场景**：当前 `dataKey` 在无件号时回退路径+起始页，若同一目录多件且都无页号，则无法区分（属配置缺陷，建议规则中要求页号列）。
- **分件方式配置 UI 未做**：本次不引入「分件方式」显式选择项，靠语义槽位自动推导。若后续需支持非标准写法（如自定义分隔符、页码偏移），可在规则配置中增加「分件方式」下拉，映射到 `resolvePageRange` 的不同策略。
- **多页 TIF 口径**：`file_count`（文件数）与「页数」在单文件多页场景仍可能不等价，与既有设计一致，本次未扩大范围。
- **件内真实进度（未做，见 §3.8）**：当前内容检查进度按「件」跳变，件内等待只靠件标识 + 已耗时提示。若需真实百分比，须由 AIMP 侧在批处理循环内逐页更新共享进度并随响应/查询接口回传，OR 侧按「件口径 + 件内完成度」插值（`percent = (已完成件数 + 当前件内完成度) / 总件数`，该口径已在 FilesExtension 批量提取中验证）；涉及两端改动与 AIMP 重启，本次未纳入。