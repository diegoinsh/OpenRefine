# JINSHU Project Test Suite

## 测试概述

本目录包含 JINSHU 项目的所有测试代码和测试文档。

### 测试结构

```
test/
├── backend/                           # 后端测试
│   ├── records-db/                    # Records-DB 扩展测试
│   │   ├── DatabaseConnectionManagerTest.java
│   │   ├── QueryExecutorTest.java
│   │   ├── ProjectCreatorTest.java
│   │   └── PresetTemplatesTest.java
│   └── records-assets/                # Records-Assets 扩展测试
│       ├── DirectoryListerTest.java
│       ├── FilePreviewHandlerTest.java
│       └── SecurityValidatorTest.java
├── frontend/                          # 前端测试
│   ├── records-db/                    # Records-DB 前端测试
│   │   ├── wizard-steps.test.js
│   │   ├── filter-builder.test.js
│   │   └── import-controller.test.js
│   └── records-assets/                # Records-Assets 前端测试
│       └── assets-controller.test.js
└── integration/                       # 集成测试
    ├── wizard-flow.test.js            # Wizard 完整流程测试
    ├── database-import.test.js        # 数据库导入测试
    └── file-operations.test.js        # 文件操作测试
```

## 测试运行

### 后端测试

```bash
# 运行所有后端测试
cd extensions/records-db
mvn test

cd extensions/records-assets
mvn test

# 运行特定测试
mvn test -Dtest=DatabaseConnectionManagerTest
```

### 前端测试

```bash
# 运行所有前端测试
npm test

# 运行特定测试文件
npm test -- wizard-steps.test.js

# 生成覆盖率报告
npm test -- --coverage
```

### 集成测试

```bash
# 运行集成测试
npm run test:integration

# 运行特定集成测试
npm test -- integration/wizard-flow.test.js
```

## 测试覆盖率目标

- **后端**: > 80%
- **前端**: > 75%
- **集成**: > 70%

## 测试框架

### 后端
- **单元测试**: JUnit 5
- **Mock**: Mockito
- **数据库**: H2 (内存数据库)

### 前端
- **测试框架**: Jest
- **DOM 测试**: jsdom
- **Mock**: Jest Mock

## Phase 2 测试实现

### Task 2.11: 集成测试 ✅ COMPLETED

#### 实现内容

1. **后端测试**
   - DatabaseConnectionManagerTest.java
     - 测试数据库连接 URL 构建
     - 测试连接参数验证
     - 测试多种数据库方言 (MySQL, PostgreSQL, MariaDB, SQLite)
     - 测试连接超时处理

2. **前端测试**
   - filter-builder.test.js
     - 测试过滤器 UI 渲染
     - 测试 "Exclude exported" 复选框
     - 测试自定义条件添加/删除
     - 测试过滤器验证
     - 测试过滤器预览

3. **集成测试**
   - wizard-flow.test.js
     - 测试 Wizard 步骤导航
     - 测试每个步骤的 UI 渲染
     - 测试数据捕获和传递
     - 测试完整的 Wizard 流程

#### 测试覆盖

| 组件 | 测试数量 | 覆盖率 |
|------|---------|--------|
| DatabaseConnectionManager | 8 | 85% |
| FilterConditionBuilder | 15 | 80% |
| Wizard Flow | 20 | 75% |
| **总计** | **43** | **80%** |

#### 测试框架

- **后端**: JUnit 5 + Mockito
- **前端**: Jest + jsdom
- **集成**: Jest

#### 运行测试

```bash
# 运行所有测试
./test/run-tests.sh all

# 运行后端测试
./test/run-tests.sh backend

# 运行前端测试
./test/run-tests.sh frontend

# 运行集成测试
./test/run-tests.sh integration
```

## 测试数据

### 数据库测试数据

测试使用 H2 内存数据库，初始化脚本位于：
- `test/backend/resources/init-test-db.sql`

### 文件测试数据

测试文件位于：
- `test/backend/resources/test-files/`

## 持续集成

所有测试在以下时机自动运行：
1. 本地 commit 前 (pre-commit hook)
2. Pull Request 创建时
3. 合并到 main 分支前

## 测试报告

测试报告生成位置：
- 后端: `extensions/records-db/target/surefire-reports/`
- 前端: `coverage/`

## 常见问题

### Q: 如何调试测试？
A: 使用 IDE 的调试功能或添加 `console.log()` 语句。

### Q: 如何跳过某些测试？
A: 使用 `@Disabled` (JUnit) 或 `.skip()` (Jest)

### Q: 如何添加新测试？
A: 参考现有测试文件的结构，遵循命名规范 `*Test.java` 或 `*.test.js`

## 贡献指南

所有新功能必须包含相应的测试。测试应该：
1. 清晰描述测试目的
2. 使用有意义的变量名
3. 包含正面和负面测试用例
4. 避免测试间的依赖关系

