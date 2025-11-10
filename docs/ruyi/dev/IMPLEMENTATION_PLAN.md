# Catalog Mode + Assets 实现计划

## 项目概览

**目标**: 为 OpenRefine 实现 Catalog Mode（目录模式）和 Assets 扩展，支持文档+元数据导入和文件预览

**分支**: `feat/catalog-mode-assets-docs`

**预计工期**: 4-6 周（分 3 个阶段）

## 阶段划分

### Phase 1: 基础框架 (1-2 周)

**目标**: 建立扩展骨架和基本 API 端点

#### 后端任务

1. **创建 records-db 扩展骨架**
   - 复制 database 扩展结构
   - 创建 MOD-INF/controller.js
   - 创建 pom.xml 和 Maven 配置
   - 实现 RecordsDatabaseImportController.java

2. **实现 Schema Profile 解析器**
   - SchemaProfile.java (数据模型)
   - SchemaProfileParser.java (JSON 解析)
   - SchemaProfileValidator.java (验证)

3. **实现 P0 查询策略 (Server-side JSON)**
   - QueryBuilder.java (基础查询构建)
   - JsonFieldExtractor.java (JSON 字段提取)
   - FilterApplier.java (过滤逻辑)

4. **创建 records-assets 扩展骨架**
   - 创建扩展目录结构
   - RecordsAssetsController.java
   - 基础路径验证

#### 前端任务

1. **创建 UI 框架**
   - records-db-import-controller.js
   - records-db-source-ui.js (基础 UI)
   - i18n 键定义 (records.db.*)

2. **实现 Wizard 基础流程**
   - Step 1: Select Profile (UI 组件)
   - Step 2: Select Fields (UI 组件)
   - 基础导航逻辑

#### 测试任务

1. 单元测试框架设置
2. SchemaProfile 解析测试
3. 基础 API 端点测试

---

### Phase 2: 核心功能 (2-3 周)

**目标**: 实现完整的 Catalog Mode 导入流程

#### 后端任务

1. **完成 Records-DB 控制器**
   - initialize-ui 端点
   - parse-preview 端点 (100 行预览)
   - create-project 端点

2. **实现预设模板**
   - KubaoPreset.java
   - FlatTablePreset.java
   - GenericJsonPreset.java

3. **实现 P1 SQL 推送 (可选)**
   - MySQLQueryBuilder.java
   - PostgreSQLQueryBuilder.java
   - 性能优化

4. **完成 Records-Assets 基础**
   - /command/records-assets/list 端点
   - /command/records-assets/preview 端点
   - 路径验证和安全检查

#### 前端任务

1. **完成 Wizard 流程**
   - Step 3: Configure Filters
   - Step 4: Preview
   - Step 5: Create Project

2. **实现过滤器 UI**
   - 字段条件构建器
   - "Exclude exported" 复选框 (条件显示)
   - 过滤逻辑验证

3. **实现预览面板**
   - 数据表格显示
   - 分页支持
   - 错误处理

4. **i18n 完整化**
   - 所有 UI 文本使用 i18n 键
   - 支持中文/英文

#### 测试任务

1. 集成测试 (Wizard 流程)
2. API 端点测试
3. 数据导入测试
4. 过滤逻辑测试

---

### Phase 3: 优化和扩展 (1-2 周)

**目标**: 性能优化、文件预览、文档完善

#### 后端任务

1. **性能优化**
   - 结果集流式处理
   - 缓存字段字典
   - 连接池配置

2. **文件预览功能**
   - 图片缩略图生成和缓存
   - PDF 嵌入支持 (pdf.js)
   - Word 文件下载
   - 文本文件预览

3. **错误处理和日志**
   - 完整的错误代码定义
   - 详细的日志记录
   - 安全日志 (路径遍历尝试)

#### 前端任务

1. **Assets 预览 UI**
   - 目录树导航
   - 文件预览面板
   - 缩略图显示

2. **性能优化**
   - 懒加载目录树
   - 分页加载
   - 缓存管理

3. **用户体验**
   - 加载指示器
   - 错误提示
   - 响应式设计

#### 测试任务

1. 性能测试 (大数据集)
2. 安全测试 (路径遍历)
3. 文件预览测试
4. 端到端测试

---

## 技术栈

### 后端
- **语言**: Java 8+
- **框架**: OpenRefine 扩展框架
- **数据库**: MySQL, PostgreSQL, MariaDB, SQLite
- **JSON**: Jackson
- **测试**: JUnit 4, Mockito

### 前端
- **语言**: JavaScript (ES6+)
- **框架**: jQuery (OpenRefine 标准)
- **i18n**: OpenRefine i18n 系统
- **文件预览**: pdf.js
- **测试**: Jasmine, Karma

---

## 关键文件清单

### 后端文件

```
extensions/records-db/
├── src/com/google/refine/extension/records/
│   ├── RecordsDatabaseImportController.java
│   ├── model/
│   │   ├── SchemaProfile.java
│   │   └── QueryResult.java
│   ├── parser/
│   │   ├── SchemaProfileParser.java
│   │   └── SchemaProfileValidator.java
│   ├── query/
│   │   ├── QueryBuilder.java
│   │   ├── JsonFieldExtractor.java
│   │   └── FilterApplier.java
│   └── preset/
│       ├── KubaoPreset.java
│       ├── FlatTablePreset.java
│       └── GenericJsonPreset.java
├── module/MOD-INF/
│   ├── controller.js
│   └── module.properties
└── pom.xml

extensions/records-assets/
├── src/com/google/refine/extension/assets/
│   ├── RecordsAssetsController.java
│   ├── security/
│   │   ├── PathValidator.java
│   │   └── AllowedRootsConfig.java
│   ├── preview/
│   │   ├── FilePreviewHandler.java
│   │   └── ThumbnailGenerator.java
│   └── directory/
│       └── DirectoryLister.java
├── module/MOD-INF/
│   ├── controller.js
│   └── module.properties
└── pom.xml
```

### 前端文件

```
extensions/records-db/module/scripts/
├── index/
│   ├── records-db-import-controller.js
│   └── records-db-source-ui.js
├── project/
│   └── records-db-preview.js
└── langs/
    ├── en.json
    └── zh-CN.json

extensions/records-assets/module/scripts/
├── index/
│   ├── records-assets-controller.js
│   └── records-assets-ui.js
└── langs/
    ├── en.json
    └── zh-CN.json
```

---

## 依赖关系

```
Phase 1 (基础框架)
    ↓
Phase 2 (核心功能)
    ├─ Records-DB 完整实现
    └─ Records-Assets 基础实现
    ↓
Phase 3 (优化和扩展)
    ├─ 性能优化
    ├─ 文件预览
    └─ 文档完善
```

---

## 风险和缓解措施

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 数据库兼容性问题 | 高 | 早期集成测试，支持多个数据库 |
| i18n 键管理混乱 | 中 | 建立命名规范，自动化验证 |
| 性能问题 (大数据集) | 中 | 早期性能测试，流式处理 |
| 安全漏洞 (路径遍历) | 高 | 严格的路径验证，安全审计 |
| 跨浏览器兼容性 | 低 | 标准 JavaScript，充分测试 |

---

## 成功标准

- ✅ 所有 API 端点实现并通过测试
- ✅ Wizard UI 完整可用
- ✅ 支持 Kubao、Flat Table、Generic JSON 三个预设
- ✅ 文件预览功能正常
- ✅ 所有 UI 文本使用 i18n
- ✅ 安全验证通过
- ✅ 性能测试通过 (1000+ 行数据)
- ✅ 文档完整

