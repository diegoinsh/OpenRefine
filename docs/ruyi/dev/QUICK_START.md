# 快速开发指南

## 环境设置

### 前置要求

- Java 8+
- Maven 3.6+
- Node.js 12+ (可选，用于前端开发)
- Git

### 克隆和设置

```bash
# 克隆 OpenRefine 仓库
git clone https://github.com/diegoinsh/OpenRefine.git
cd OpenRefine

# 切换到开发分支
git checkout feat/catalog-mode-assets-docs

# 构建 OpenRefine
mvn clean package -DskipTests
```

---

## 项目结构

```
OpenRefine/
├── extensions/
│   ├── database/              # 参考实现
│   ├── records-db/            # 新建：Records-DB 扩展
│   └── records-assets/        # 新建：Records-Assets 扩展
├── docs/ruyi/
│   ├── design/
│   │   └── architecture.md    # 架构设计
│   ├── dev/
│   │   ├── be_records_db.md
│   │   ├── ui_records_db.md
│   │   ├── be_records_assets.md
│   │   ├── IMPLEMENTATION_PLAN.md
│   │   └── DEVELOPMENT_TASKS.md
│   ├── api/
│   │   └── endpoints.md
│   └── adr/
│       └── 0001-catalog-mode-and-assets-extension.md
└── ...
```

---

## 开发工作流

### 1. 创建扩展骨架

```bash
# 复制 database 扩展作为模板
cp -r extensions/database extensions/records-db

# 修改 pom.xml
# 修改 artifactId: records-db
# 修改 name: Records Database Import Extension

# 修改 MOD-INF/module.properties
# 修改 name, description 等
```

### 2. 后端开发

#### 创建 Java 类

```bash
# 创建包结构
mkdir -p extensions/records-db/src/com/google/refine/extension/records/{model,parser,query,preset}

# 创建主控制器
touch extensions/records-db/src/com/google/refine/extension/records/RecordsDatabaseImportController.java
```

#### 实现 ImportingController

```java
public class RecordsDatabaseImportController implements ImportingController {
    
    @Override
    public void init(ServletContext context) {
        // 初始化
    }
    
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        String subCommand = request.getParameter("subCommand");
        
        if ("initialize-ui".equals(subCommand)) {
            handleInitializeUI(request, response);
        } else if ("parse-preview".equals(subCommand)) {
            handleParsePreview(request, response);
        } else if ("create-project".equals(subCommand)) {
            handleCreateProject(request, response);
        }
    }
    
    private void handleInitializeUI(HttpServletRequest request, 
            HttpServletResponse response) throws IOException {
        // 返回初始化数据
        JSONObject result = new JSONObject();
        result.put("mode", "catalog");
        result.put("presets", new JSONArray(Arrays.asList("kubao", "flat_table", "generic_json")));
        
        response.setContentType("application/json");
        response.getWriter().write(result.toString());
    }
    
    // 其他方法...
}
```

#### 编译和测试

```bash
# 编译扩展
cd extensions/records-db
mvn clean package

# 运行测试
mvn test

# 查看覆盖率
mvn jacoco:report
```

### 3. 前端开发

#### 创建 JavaScript 文件

```bash
# 创建前端文件
mkdir -p extensions/records-db/module/scripts/{index,project}
touch extensions/records-db/module/scripts/index/records-db-import-controller.js
touch extensions/records-db/module/scripts/index/records-db-source-ui.js
```

#### 实现导入控制器

```javascript
// records-db-import-controller.js
Refine.registerImportingController(new ImportingController({
    id: 'records-db',
    label: i18n.t('records.db.label'),
    uiClass: RecordsDBSourceUI
}));
```

#### 实现 UI

```javascript
// records-db-source-ui.js
var RecordsDBSourceUI = function(controller) {
    this._controller = controller;
};

RecordsDBSourceUI.prototype.attachUI = function(bodyDiv, onDone) {
    // 创建 UI
    var html = '<div id="records-db-wizard">';
    html += '<div id="step-1">Select Profile</div>';
    html += '</div>';
    
    bodyDiv.innerHTML = html;
};
```

#### i18n 配置

```json
// module/langs/en.json
{
  "records": {
    "db": {
      "label": "Records Database",
      "wizard": {
        "selectProfile": {
          "title": "Select Profile"
        }
      }
    }
  }
}
```

### 4. 测试

#### 后端单元测试

```java
// src/test/java/com/google/refine/extension/records/SchemaProfileParserTest.java
public class SchemaProfileParserTest {
    
    @Test
    public void testParseValidProfile() {
        String json = "{\"mode\":\"catalog\",\"preset\":\"kubao\"}";
        SchemaProfile profile = SchemaProfileParser.parse(json);
        
        assertEquals("catalog", profile.getMode());
        assertEquals("kubao", profile.getPreset());
    }
}
```

#### 前端测试

```javascript
// module/scripts/test/records-db-import-controller-test.js
describe('RecordsDBImportController', function() {
    it('should register controller', function() {
        expect(Refine.importingControllers['records-db']).toBeDefined();
    });
});
```

#### 集成测试

```bash
# 启动 OpenRefine
./refine

# 访问 http://localhost:3333
# 测试导入流程
```

---

## 常见任务

### 添加新的预设模板

1. 创建 `PresetName.java` 类
2. 实现 `Preset` 接口
3. 在 `PresetFactory` 中注册
4. 编写测试

### 添加新的文件预览类型

1. 创建 `FileTypeHandler.java`
2. 在 `FilePreviewHandler` 中注册
3. 实现预览逻辑
4. 编写测试

### 添加新的 i18n 语言

1. 创建 `module/langs/xx.json`
2. 翻译所有键
3. 在 `module.properties` 中注册

---

## 调试技巧

### 后端调试

```bash
# 启用 Maven 调试
mvn -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005 clean package

# 在 IDE 中连接到 localhost:5005
```

### 前端调试

```javascript
// 在浏览器控制台中
console.log(Refine.importingControllers);
console.log(Refine.i18n);
```

### 日志查看

```bash
# 查看 OpenRefine 日志
tail -f refine.log

# 启用调试日志
# 编辑 refine.ini，设置 REFINE_JAVA_OPTS="-Dlog4j.configuration=file:log4j-debug.properties"
```

---

## 代码规范

### Java 代码规范

- 遵循 Google Java Style Guide
- 使用 4 空格缩进
- 类名使用 PascalCase
- 方法名使用 camelCase
- 常量使用 UPPER_SNAKE_CASE

### JavaScript 代码规范

- 遵循 Google JavaScript Style Guide
- 使用 2 空格缩进
- 使用 `var` 或 `const`
- 避免全局变量
- 使用 JSDoc 注释

### i18n 键命名规范

```
records.db.{feature}.{component}.{element}

示例:
- records.db.wizard.selectProfile.title
- records.db.filters.excludeExported
- records.db.error.connectionFailed
```

---

## 提交代码

### 提交前检查

```bash
# 运行所有测试
mvn clean test

# 检查代码风格
mvn checkstyle:check

# 检查覆盖率
mvn jacoco:report

# 查看覆盖率报告
open target/site/jacoco/index.html
```

### 提交信息格式

```
feat(records-db): implement schema profile parser

- Add SchemaProfile data model
- Add SchemaProfileParser for JSON parsing
- Add SchemaProfileValidator for validation
- Add unit tests with 90% coverage

Closes #123
```

### 推送代码

```bash
# 推送到开发分支
git push origin feat/catalog-mode-assets-docs

# 创建 Pull Request
# 在 GitHub 上创建 PR，等待审查
```

---

## 参考资源

- [OpenRefine 扩展开发指南](https://github.com/OpenRefine/OpenRefine/wiki/Extensions)
- [Database 扩展源代码](https://github.com/OpenRefine/OpenRefine/tree/master/extensions/database)
- [OpenRefine API 文档](https://docs.openrefine.org/manual/api)
- [i18n 系统文档](https://github.com/OpenRefine/OpenRefine/wiki/Internationalization)

---

## 获取帮助

- 查看 docs/ruyi/dev/ 中的详细设计文档
- 查看 docs/ruyi/api/endpoints.md 中的 API 规范
- 查看 docs/ruyi/adr/ 中的架构决策
- 在 GitHub Issues 中提问

