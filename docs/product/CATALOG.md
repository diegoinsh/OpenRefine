# OpenRefine 功能目录

## 一、产品概述

OpenRefine 是一款基于 Java 开发的开源数据处理工具，提供强大的数据加载、清洗、匹配和增强功能。通过 Web 浏览器界面运行，所有数据处理均在本地计算机完成，确保数据隐私和安全。

**官方网站**: https://openrefine.org

**开源协议**: BSD License

---

## 二、核心功能模块

### 2.1 数据导入功能

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 多格式数据导入 | 支持导入 CSV、TSV、Excel（.xls/.xlsx）、JSON、XML、HTML 等格式文件 | FileImporter, ExcelImporter, JsonImporter, XmlImporter |
| 数据库数据导入 | 通过 JDBC 连接 MySQL、PostgreSQL、MariaDB、SQLite 等主流数据库 | DatabaseExtension, DatabaseService |
| 档案数据库导入 | 支持从档案数据库系统导入数据，支持 Schema Profile 配置 | RecordsDBExtension |
| 粘贴数据导入 | 支持直接粘贴剪贴板数据 | ClipboardImporter |
| URL 数据导入 | 支持从 Web URL 动态获取数据 | UrlImporter |
| 字符编码识别 | 自动检测文件编码（UTF-8、GBK、ISO-8859-1 等） | EncodingDetector |
| 数据预览 | 导入前预览数据结构、前 100 行数据 | parse-preview API |
| 项目创建 | 将导入数据创建为 OpenRefine 项目 | create-project API |

### 2.2 数据导出功能

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 表格数据导出 | 导出为 CSV、TSV、Excel、HTML、JSON、XML 等格式 | CsvExporter, OdsExporter, XlsExporter |
| SQL 数据导出 | 将数据导出为 SQL INSERT 语句 | SqlExporter, SqlData |
| 自定义导出 | 通过模板自定义导出格式和结构 | CustomTabularExporter |
| 资源文件导出 | 导出数据绑定的资源文件 | BoundAssetsExporter |
| 批量导出 | 批量导出多个项目数据 | ExportMenu |

### 2.3 数据清洗功能

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 列操作 | 添加、删除、重命名、移动、拆分、合并列 | ColumnApi, EditCells |
| 行操作 | 基于条件过滤、删除、标记行 | FilterRows, EditRows |
| 单元格编辑 | 单个或批量编辑单元格内容 | EditCells |
| 填充功能 | 向下/向上填充空值单元格 | FillDown/FillUp |
| 排序功能 | 按单列或多列排序，支持升序/降序 | SortRows |
| 去重功能 | 基于列值去除重复行 | DedupRows |
| 空白清理 | 去除单元格首尾空白、统一空白字符 | Trim, NormalizeWhitespace |
| 大小写转换 | 转换文本大小写（首字母大写、全大写、全小写） | CaseConversion |
| 去除 HTML 标签 | 从文本中移除 HTML 标签 | RemoveHtmlTags |
| 正则表达式替换 | 使用正则表达式进行高级文本替换 | Replace |

### 2.4 表达式语言（GREL）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 数学运算 | 加减乘除、幂运算、取模、round、floor、ceil | MathFunctions |
| 字符串操作 | length、substring、startsWith、endsWith、indexOf、lastIndexOf | StringFunctions |
| 数组操作 | length、invert、slice、sort、join、uniques | ArrayFunctions |
| 日期处理 | toDate、toNumber、diff、format、now | DateFunctions |
| 对象操作 | hasField、get、keys、values | ObjectFunctions |
| 条件判断 | if、isNotNull、isEmpty、isError | ConditionalFunctions |
| 链式调用 | 支持多函数链式调用 | ExpressionParser |

### 2.5 分面与过滤功能

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 文本分面 | 按文本值创建分面，统计出现频率 | TextFacet |
| 数值分面 | 按数值范围创建直方图分面 | NumericFacet |
| 时间分面 | 按时间线展示数据分布 | TimelineFacet |
| 自定义分面 | 使用表达式自定义分面逻辑 | CustomFacet |
| 交叉分面 | 多个分面组合过滤 | CrossFacet |
| 过滤面板 | 保存和加载过滤条件 | FilterPanel |
| 快速过滤 | 基于选中单元格快速过滤 | QuickFilter |

### 2.6 数据匹配（Reconciliation）功能

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 实体匹配服务 | 连接到外部数据源匹配实体（人名、地名、组织等） | ReconciliationService |
| 批量匹配 | 批量匹配单元格值到已知实体 | ReconManager |
| 匹配预览 | 预览匹配结果和置信度 | ReconCandidates |
| 匹配策略 | 支持多种匹配策略（自动、手动、批量） | ReconConfig |
| Wikibase 集成 | 集成 Wikidata/Wikibase 数据源 | WikibaseExtension |

### 2.7 数据增强功能

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 从网络获取数据 | 通过 URL 获取外部数据并添加到列 | AddColumnFromUrl |
| 数据联动 | 基于匹配结果获取关联数据 | FetchReconciledData |
| 维基数据增强 | 从 Wikidata 获取实体属性信息 | WikidataExtension |
| 用户自定义列 | 基于表达式创建新列 | AddColumn |

---

## 三、扩展功能模块

### 3.1 档案数据库扩展（Records-DB）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 数据库连接管理 | 管理多种数据库连接（MySQL、PostgreSQL、SQLite 等） | DatabaseConnectionManager |
| Schema Profile | 定义数据模型，支持预置模板（库宝、平表、通用 JSON） | SchemaProfile, PresetManager |
| 数据查询 | 支持 P0 客户端查询和 P1 SQL 推送 | QueryBuilder, FilterApplier |
| 字段映射 | 可视化字段选择和映射 | FieldMapping |
| 文件映射 | 配置数据列到文件路径的映射 | FileMapping |
| 过滤构建器 | 可视化构建数据过滤条件 | FilterBuilder |
| 项目绑定 | 将数据库查询配置保存到项目 | RecordsDBOverlayModel |

### 3.2 档案资源扩展（Records-Assets）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 目录浏览 | 以树形结构浏览资源目录 | DirectoryLister |
| 分页支持 | 大目录分页显示 | PathValidator |
| 文件预览 | 支持图片、PDF、Word、文本等多种格式预览 | FilePreviewHandler |
| 安全验证 | 防止目录遍历攻击 | PathValidator, SecurityValidator |
| 路径配置 | 配置允许访问的根目录 | allowedRoots |

### 3.3 数据质量检查扩展（Data-Quality）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 质量规则管理 | 创建、编辑、保存、加载质量检查规则 | QualityRule, QualityController |
| 格式检查 | 唯一性检查、非空检查、值域检查、日期格式检查、正则匹配 | RuleValidator |
| 资源关联检查 | 文件存在性、数量一致性、命名规范、序号连续性检查 | ResourceChecker |
| 内容比对检查 | 调用 OCR 服务抽取信息并与数据比对 | ContentChecker, AimpClient |
| 规则模板 | 预置档案著录规则模板 | FormatTemplate, ContentTemplate |
| 断点续跑 | 支持任务暂停、恢复和断点续跑 | QualityTask |
| 结果展示 | 可视化展示检查结果（错误、警告、通过） | QualityResults |
| 结果导出 | 导出带标注 Excel、PDF 报告、统计报表 | ExportService |

### 3.4 Wikibase 扩展（Wikibase）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| Wikibase 连接 | 连接到 Wikidata 或私有 Wikibase 实例 | WikibaseManager |
| 模式对齐 | 定义数据到 Wikibase 属性的映射 | SchemaAlignment |
| 批量编辑 | 批量创建和更新 Wikibase 实体 | EntityEdit |
| 预览功能 | 预览编辑效果和冲突检测 | PreviewRenderer |
| 上传管理 | 管理待上传数据 | UploadManager |
| 模板管理 | 定义数据转换模板 | TemplateManager |
| 警告处理 | 处理数据冲突和警告 | WarningsRenderer |

### 3.5 数据库扩展（Database）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| JDBC 数据库连接 | 通过 JDBC 连接多种数据库 | DatabaseService |
| 表选择 | 选择要导入的数据库表 | DatabaseSourceUI |
| SQL 查询 | 自定义 SQL 查询导入数据 | CustomQueryBuilder |
| 连接管理 | 管理数据库连接参数 | DatabaseConnection |

### 3.6 Jython 扩展（Jython）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| Python 脚本 | 支持 Jython 脚本进行高级数据处理 | JythonEvaluators |
| 自定义函数 | 编写自定义表达式函数 | JythonFunctions |

### 3.7 PC-Axis 扩展（PC-Axis）

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| PC-Axis 导入 | 支持导入 PC-Axis 格式统计数据 | PCAxisParser |

---

## 四、用户界面功能

### 4.1 项目管理

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 项目列表 | 查看和管理所有项目 | ProjectManager |
| 项目导入/导出 | 导入导出 OpenRefine 项目文件 | ProjectIO |
| 项目重命名 | 修改项目名称 | RenameProject |
| 项目删除 | 删除项目 | DeleteProject |
| 项目复制 | 复制项目 | CloneProject |

### 4.2 工作区操作

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 撤销/重做 | 支持多步撤销和重做操作 | History, UndoRedo |
| 批量操作 | 对多列/多行执行批量操作 | BulkEdit |
| 导出历史 | 查看操作历史 | HistoryPanel |
| 标签管理 | 为行添加、删除、过滤标签 | Star, Flag |

### 4.3 视图切换

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 记录视图 | 按记录模式查看数据（多行一条记录） | RecordMode |
| 行视图 | 按行模式查看数据（传统表格） | RowMode |
| 跨记录编辑 | 在记录视图下跨行编辑 | CrossRecordEdit |

### 4.4 数据可视化

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 数值分布图 | 显示数值列的分布情况 | NumericFacetPlotter |
| 词云 | 展示文本数据的词频分布 | WordCluster |
| 散点图 | 展示两列数据的分布关系 | Scatterplot |

---

## 五、系统功能

### 5.1 性能优化

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 流式处理 | 大数据集流式处理避免内存溢出 | StreamingResultSet |
| 连接池 | 数据库连接池管理 | ConnectionPoolManager |
| 缓存机制 | 字段字典缓存提升性能 | FieldDictionaryCache |
| 懒加载 | 大量数据的懒加载展示 | LazyLoader |

### 5.2 国际化

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 多语言支持 | 支持 40+ 语言界面 | i18n, TranslationFiles |
| 语言切换 | 动态切换界面语言 | LanguageSelector |
| 本地化扩展 | 扩展模块支持本地化 | ModuleLocalization |

### 5.3 安全性

| 功能名称 | 功能描述 | 技术实现 |
|---------|----------|----------|
| 本地运行 | 所有数据处理在本地完成 | LocalServer |
| 路径验证 | 防止目录遍历攻击 | PathValidator |
| 配置隔离 | 扩展配置隔离 | ModuleConfiguration |

---

## 六、API 接口

### 6.1 后端 API

| 端点 | 方法 | 描述 |
|------|------|------|
| /command/core/get-version | GET | 获取版本信息 |
| /command/core/create-project-from-upload | POST | 通过上传创建项目 |
| /command/core/create-project | POST | 通过 URL 创建项目 |
| /command/core/export-rows | POST | 导出数据 |
| /command/core/get-history | GET | 获取操作历史 |
| /command/core/undo | POST | 撤销操作 |
| /command/core/redo | POST | 重做操作 |

### 6.2 扩展 API

| 端点 | 方法 | 扩展 | 描述 |
|------|------|------|------|
| /command/records-db/initialize-ui | POST | Records-DB | 初始化导入 UI |
| /command/records-db/parse-preview | POST | Records-DB | 预览数据 |
| /command/records-db/create-project | POST | Records-DB | 创建项目 |
| /command/records-assets/list | GET | Records-Assets | 列出资源 |
| /command/records-assets/preview | GET | Records-Assets | 预览文件 |
| /command/data-quality/save-rules | POST | Data-Quality | 保存规则 |
| /command/data-quality/run-check | POST | Data-Quality | 运行检查 |
| /command/wikibase/save-schema | POST | Wikibase | 保存模式 |

---

## 七、技术规格

### 7.1 环境要求

| 项目 | 要求 |
|------|------|
| JDK | 11 或更高版本 |
| Maven | 3.6+ |
| Node.js | 18+ |
| 内存 | 最小 2GB，推荐 4GB+ |
| 磁盘 | 根据数据量确定 |

### 7.2 支持的操作系统

| 操作系统 | 状态 |
|---------|------|
| Windows | ✅ 支持 |
| macOS | ✅ 支持 |
| Linux | ✅ 支持 |

### 7.3 支持的数据库

| 数据库 | JDBC 驱动 | 状态 |
|--------|----------|------|
| MySQL | com.mysql.cj.jdbc.Driver | ✅ 支持 |
| PostgreSQL | org.postgresql.Driver | ✅ 支持 |
| MariaDB | org.mariadb.jdbc.Driver | ✅ 支持 |
| SQLite | org.sqlite.JDBC | ✅ 支持 |
| H2 | org.h2.Driver | ✅ 测试用 |

---

## 八、版本信息

| 版本 | 发布日期 | 主要变更 |
|------|----------|----------|
| 3.x | 2024+ | 全新架构，现代化 UI |
| 2.x | 2018-2023 | 稳定版本 |
| 1.x | 2013-2017 | 初始版本（Google Refine） |

---

*文档生成时间: 2025-12-29*
*产品名称: OpenRefine*
*文档版本: 1.0*
