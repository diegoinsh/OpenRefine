# 装载器自主化与 Butterfly 退场 · 发布后开发计划（工作包 A–D）

> 工作 worktree：`g:\workshop\RUYI\build\or-solon-poc`（分支 `feat/platform-sdk-decoupling`）；主工作区 `g:\workshop\RUYI\OpenRefine` 勿动。
> 基线 SSOT：`docs\ruyi\design\or-container-modernization-baseline.md`（§4 分层结论 / §6 目标架构 / §7 Phase 3）。
> 前置成果：`.trae\documents\or-business-plugin-extraction-plan.md`（P0–P5 与 Release-1 均已完成）。
> 事实核对时间：2026-10-17（两个仓工作区干净，下述结论均来自当前 HEAD 源码）。
> 修订：2026-10-17 深夜——补充生态选型结论、A0 依赖倒置与 golden 基线、Rhino 宿主细节、待决策清单（§12）。

---

## 1. Context（为什么做 / 现在做到哪）

**Release-1 已交付**（见 [release-notes-release-1.md](file:///g:/workshop/RUYI/build/or-solon-poc/docs/ruyi/release-notes-release-1.md)），交付基线为 `refine.module.loader=butterfly`。Release-1 收口的是**平台耦合点下沉**这条线：

| Release-1 项 | 状态 | 产物 |
|---|---|---|
| R1-0 可交付基线冻结 | ✔ | 固定 `butterfly`；10 模块 install；冒烟通过 |
| R1-1 AIMP 传输收口 | ✔ | SDK `AimpTransport` / `HttpAimpTransport`；4 处直连归零 |
| R1-2 平台采用 SDK 语义 | ✔ | `PlatformFilter`/`DogLicenseService` 改用 `ModuleNames`/`ModuleCodes`；`CapabilityGate` 内部持 SDK `LicenseGate` |
| R1-3 3A 进程内最小闭环 | ✔ | `CapabilityGate` / `GuardedAimpClient`；开关 `off/log/block`，缺省 `off` |
| R1-4 发布候选验证 | ✔ | 全量回归 + 打包 + 发布说明 |
| R1-5 既有失败清理 | ✔ | core 215/215、FilesExtension 87/87；`HistoryEntry.sheetId` NON_NULL、Files 测试 Windows 适配 |
| R2-1 自研请求内核（A0–A5 切片） | ✔ | `ModuleView`/descriptor/graph/registry + wirings/bundle/静态/模板；缺省 `solon-plugin`；golden 9/9 |

**baseline §6 目标架构仍未达成**，缺口即 Release-1 明确后置的清单：

| §6 目标 | 达成度 | 缺口 |
|---|---|---|
| OR core 只依赖 SPI 接口 | Java 编译面达成 | 框架门面仍绑 Butterfly（`RefineServlet extends Butterfly`、资源/i18n/wirings 全走 Butterfly） |
| 业务插件以 Solon 插件形态装载 | 约 1/4 | 8 个模块中仅 files / data-quality 有 `META-INF/solon`；缺省装载器仍是 `butterfly` |
| 平台 SDK 收敛耦合点 | 大部分达成 | SSO 转发仍未进 SDK；桥产物仍属平台部署物 |
| 前端契约保持 | 达成 | `/command/*`、`/extension/*`、`wirings.js`、`-bundle.js`、`.vt` 均未变 |

本计划只覆盖上表缺口，**不改变 Release-1 已交付的行为**。

---

## 2. 现状事实（已核验）

### 2.1 装载器

| 事实 | 位置 |
|---|---|
| 三值开关 `butterfly | solon | solon-plugin`，**缺省 `butterfly`** | [ModuleLoaderRegistry.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/jiongche/refine/loader/ModuleLoaderRegistry.java#L20-L41) |
| `refine.ini` 里 `solon-plugin` 仍是注释 | [refine.ini](file:///g:/workshop/RUYI/build/or-solon-poc/refine.ini#L27) |
| 开关的 init-param 通道存在，但 `RefineSolon` 与 `web.xml` 都未提供 → 实际只有 `-D` / `REFINE_MODULE_LOADER` 生效 | [RefineSolon.java](file:///g:/workshop/RUYI/build/or-solon-poc/server/src/com/google/refine/RefineSolon.java#L454-L467) |
| `RefineServlet extends Butterfly`；非 `/command` 请求交 `super.service` | [RefineServlet.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/google/refine/RefineServlet.java#L79)、[L332-L334](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/google/refine/RefineServlet.java#L332-L334) |
| Butterfly 仍是装配内核（`wireModules`/`configureModules`/`MountPoint`）；两个新装载器只接管 `createModule` 与初始化窗口 | [ModuleAssembly.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/jiongche/refine/loader/ModuleAssembly.java)、[StandaloneModuleLoader.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/jiongche/refine/loader/StandaloneModuleLoader.java#L1-L8) |
| Butterfly 依赖仍在 `main` / `server` / `modules/core` 三处 pom | [main/pom.xml](file:///g:/workshop/RUYI/build/or-solon-poc/main/pom.xml#L195-L198)、[server/pom.xml](file:///g:/workshop/RUYI/build/or-solon-poc/server/pom.xml#L188-L191)、[modules/core/pom.xml](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/pom.xml#L76-L79) |
| `handle()` 接缝恒 `false`（已为 Solon 接管预留） | [RefineModuleLoader.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/jiongche/refine/loader/RefineModuleLoader.java#L55-L56) |

### 2.2 Butterfly 绑定的三处（必须改造）

| 机制 | 现状 | 位置 |
|---|---|---|
| `wirings.js` | core `controller.js` 调 `butterfly.getWirings(request)`，输出 `var ModuleWirings = {...}` | [controller.js](file:///g:/workshop/RUYI/build/or-solon-poc/main/webapp/modules/core/MOD-INF/controller.js#L650-L658) |
| `-bundle.js` 拼接 | JS 侧遍历 `ClientSideResourceManager.getPaths`，用 `qualifiedPath.module.getResource(path)` 读文件再写出 | [controller.js](file:///g:/workshop/RUYI/build/or-solon-poc/main/webapp/modules/core/MOD-INF/controller.js#L669-L709) |
| `.vt` 服务端模板 | `butterfly.sendTextFromTemplate(...)`（Butterfly 的 Velocity 封装） | [controller.js](file:///g:/workshop/RUYI/build/or-solon-poc/main/webapp/modules/core/MOD-INF/controller.js#L793-L794) |
| 客户端资源寻址 | `ClientSideResourceManager.resolve()` 依赖 `module.getMountPoint()`，为 null 时直接报错 | [ClientSideResourceManager.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/google/refine/ClientSideResourceManager.java#L107-L124) |
| i18n 资源读取 | `LoadLanguageCommand` / `GetLanguagesCommand` 仍直接持 `ButterflyModule` 调 `getResource` | [LoadLanguageCommand.java](file:///g:/workshop/RUYI/build/or-solon-poc/main/src/com/google/refine/commands/lang/LoadLanguageCommand.java#L149) |
| `controller.js` 执行宿主 | `ButterflyModuleImpl.scriptInit()` 用 Rhino 执行脚本，注入 `butterfly` / `module` / `logger` 三个全局对象，并预置 `String.prototype.trim/startsWith/endsWith`（core `controller.js` 的 `path.endsWith("-bundle.js")` 依赖） | Butterfly 1.2.6 `ButterflyModuleImpl` / `ButterflyScope` |
| `wirings.js` 数据 | `Butterfly.js` 的 `Butterfly.prototype.getWirings`：遍历 mountPaths，输出 `{ moduleName: mountPath }` | Butterfly 1.2.6 `Butterfly.js` |
| 模板输出过滤 | `sendTextFromTemplate` 经 `LinkRewriter` 包装响应 Writer，处理 `@@...@@` 与图片路径 | `ButterflyModuleImpl.getFilteringWriter` |

### 2.3 前端（**不需要**因换容器改造）

| 事实 | 位置 |
|---|---|
| `DOM.loadHTML` 用同步 `$.ajax` 取 HTML 片段；唯一耦合是 `ModuleWirings[module]` 这一行 | [dom.js](file:///g:/workshop/RUYI/build/or-solon-poc/main/webapp/modules/core/scripts/util/dom.js#L123-L136) |
| `Ajax` 是 OR 自己的 `$.ajax` 薄封装 | [ajax.js](file:///g:/workshop/RUYI/build/or-solon-poc/main/webapp/modules/core/scripts/util/ajax.js#L34-L60) |
| CSRF 走 `command/core/get-csrf-token`；token 是进程内 Guava Cache + SecureRandom，不依赖 session | [csrf.js](file:///g:/workshop/RUYI/build/or-solon-poc/main/webapp/modules/core/scripts/util/csrf.js#L10-L29)、[CSRFTokenFactory.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/google/refine/commands/CSRFTokenFactory.java#L51-L66) |
| 后端只读 `request.getParameter("csrf_token")` | [Command.java](file:///g:/workshop/RUYI/build/or-solon-poc/modules/core/src/main/java/com/google/refine/commands/Command.java#L274-L284) |

### 2.4 扩展插件化

| 扩展 | `refine.spi` | `META-INF/solon` |
|---|---|---|
| files | ✅ | ✅ `src/main/resources/META-INF/solon/files.properties` |
| data-quality | ✅ | ✅ `src/META-INF/solon/data-quality.properties` |
| records-db | ✅ | ❌ |
| records-assets | ✅ | ❌ |
| jython | ❌ | ❌ |
| pc-axis | ❌ | ❌ |
| wikibase（module name = `wikidata`） | ❌ | ❌ |
| database（`module.disabled`） | ❌ | ❌（已禁用，处置待定） |

### 2.5 SDK / 平台（Release-1 后）

| 事实 | 位置 |
|---|---|
| SDK 已有 AIMP 传输层 `AimpTransport` / `Request` / `Response` / `HttpAimpTransport`（零第三方依赖） | `ruyi-capability-platform/sdk/src/main/java/com/jiongche/sdk/aimp/` |
| 四个触网点已全部经传输层：`AimpClient`、`TestAimpConnectionCommand`、`ImageQualityChecker`、`AimpLlmClient` | 各文件 `new HttpAimpTransport(...)` |
| `AimpLlmClient` 已改用 `AimpEndpoints` + `Multipart`（无手写 boundary） | [AimpLlmClient.java](file:///g:/workshop/RUYI/build/or-solon-poc/extensions/files-engine/src/org/openrefine/extensions/files/importer/AimpLlmClient.java#L210-L242) |
| extensions 下已无 `HttpURLConnection` / `WebKitFormBoundary` 残留 | grep 0 匹配 |
| 平台 `CapabilityGate` 已持 SDK `LicenseGate`（数据源为平台 `LicenseService`，与 OR 侧同源） | `platform/kernel/service/CapabilityGate.java#L32-L45` |
| 3A 进程内最小闭环已有：`CapabilityGate` + `GuardedAimpClient`；合规任务/结果查询仍走 HTTP 适配 | 同上 |
| SDK 仍无 `sso` 包；SSO 转发在平台 `web/sso/*` | `SsoComplianceController`、`OrDataQualityClient` |
| 装载器 / SPI 相关自动化测试：**0 个** | 全仓 `*Test*.java` 无相关符号 |

### 2.6 生态选型结论（2026-10-17 晚核对）

**原则：只自研 Butterfly 特有的契约；其余全部复用成熟开源件。**

| 链条 | 选型 | 结论 |
|---|---|---|
| 容器 / 路由 / 请求入口 | Solon 4.1.1 | **复用**（现状） |
| 模块类加载 / 插件生命周期 | Solon hotplug `PluginClassLoader` / `PluginPackage` | **复用** |
| 静态资源 | `solon-web-staticfiles` 4.1.1：`StaticMappings` / `FileStaticRepository` / `StaticMimes` | **复用**（API 已 `javap` 核实：`add/remove/find/count`） |
| `controller.js` 执行 | Mozilla Rhino 1.7.14（当前由 Butterfly 传递引入） | **复用**；自研仅 4 方法 shim |
| `.vt` 模板 | Apache Velocity 2.4.1 | **复用** |
| JSON | Jackson | **复用** |
| 模块描述符 / `extends` / `requires` / 拓扑排序 | PF4J / OSGi / JGraphT 均不贴合 | **自研**（`ModuleDescriptor` / `ModuleGraph` / `ModuleRegistry`） |
| `wirings.js` | 无通用实现；本质是 mount 表 | **自研**（几十行） |
| `-bundle.js` 拼接 | Webpack / esbuild / Module Federation 不适用（非 ESM + 字节契约） | **自研**（百余行） |

**明确不采用**：
- **PF4J**：描述符、生命周期、类加载策略与 `MOD-INF/module.properties` + `implements/extends` 不同，等于同时维护两套插件模型。
- **OSGi**：HTTP Whiteboard 语义最接近，但需完整框架 + Bundle Manifest + Servlet Bridge + `javax/jakarta` 约束；对 8 个模块过度设计。**借语义，不借运行时。**
- **JGraphT**：模块数为个位数，手写 Kahn 更透明、零依赖。
- **GraalJS**：无必要，Rhino 已在依赖树中，`controller.js` 只需 ES5 级能力。
- **JupyterLab federated extensions / Module Federation**：前端是全局脚本，要求字节等价，不适用。

---

## 3. 目标与非目标

**目标**：缺省装载器切到 `solon-plugin`，移除 Butterfly 依赖与 `extends Butterfly`，前端契约（URL、JSON 结构、全局变量名）逐字节不变。

**非目标**（明确不做，避免范围膨胀）：
- 不迁 `jakarta.servlet`；
- 不改前端 jQuery / `Ajax` / `DOM.loadHTML` / `.vt` 模板内容；
- 不做前端 ESM / 模块联邦改造；
- 不重写 OR importing 管线与命令实现。

---

## 4. 工作包 A：装载器自主化与 Butterfly 退场（主体）

### A0 · 依赖倒置与 golden 基线（新增，先于 A1）

**A0.1 先抽 `ModuleView`，把 Butterfly 降为适配器**

新增 `com.jiongche.refine.kernel.ModuleView`，只保留 OR 真正使用的 4 个方法：

```java
public interface ModuleView {
    String getName();
    File   getPath();
    URL    getResource(String path);
    String getMountPoint();   // "/" 或 "/extension/{name}/"
}
```

改造三处，使 core 不再以 `ButterflyModule` 为领域类型：

1. `ClientSideResourceManager.QualifiedPath.module` 由 `ButterflyModule` 改为 `ModuleView`；`addPaths` / `resolve` 同步改签名。
2. `ButterflyModuleContext` 增加 `ModuleView` 分支（旧 `ButterflyModule` 分支保留到 A7）。
3. `SpiBootstrap.register(...)` 增加 `ModuleView` 重载；旧 `ButterflyModule` 重载委托新重载。

新增 `ButterflyModuleView implements ModuleView` 作为过渡适配器。**此步零行为变化**，但 A7 删除 Butterfly 时业务代码/SPI/测试桩无需再改一轮。

**A0.2 golden 输出基线**

在任何行为改动前，固定以下响应的 SHA256 + 规范化 JSON：

- `/wirings.js`
- `/index-bundle.js`、`/project-bundle.js`
- `/`、`/about.html`
- `/extension/files/**`、`get-languages`

比对方式：JSON 深度比较（键序允许）；bundle/静态资源按字节 SHA256；首页允许 `Refine.encodings` 键序抖动（既有 Rhino/JDK 非确定性）。每个 A 子包完成后必须复跑。

**A0.3 范围冻结：本轮保留 `controller.js` / Rhino**

`controller.js` 共 7 个、core 795 行 / 159 处注册。本轮只做 Butterfly 退场，**不把 JS 注册面迁到 Java 注解/SPI**；Rhino 宿主只补 Butterfly 的 4 方法 shim。JS 化单独立项，避免 A 包范围膨胀。

**验收**：`modules/core` 全量 215/215、FilesExtension 87/87；golden 基线全等；`-Drefine.module.loader=butterfly` 仍可回退。

### A1 · 静态资源与 mount（复用 Solon 现成件）

**现状**：模块资源靠 Butterfly `MountPoint` → `ClientSideResourceManager.resolve()`；`handle()` 恒 false。

**做法**：
1. `server/pom.xml` 引入 `org.noear:solon-web-staticfiles`。
2. 每个模块装载时 `StaticMappings.add("/extension/{module}/", new FileStaticRepository(modulePath))`；core 挂 `/`。
3. `ClientSideResourceManager.resolve()` 改为查**自建 mount 表**（模块名 → URL 前缀），不再调 `module.getMountPoint()`。
4. 卸载时 `StaticMappings.remove(repo)` + `router().remove(prefix)`。
5. **静态资源由 `ModuleRequestDispatcher` 调 `StaticMappings.find(path)` 输出，不注册全局 `StaticResourceHandler`**——保证 `module.process()` 始终先于静态命中（data-quality / records-db 的自定义端点依赖此顺序）。
6. 静态服务必须保留：`MOD-INF/**` → 403；路径 `normalize()` 后仍须位于模块目录内（防穿越）；MIME 优先用 `StaticMimes.findByFileName`，关键类型与基线一致。

**为什么用现成件**：这是 OSGi HTTP Whiteboard `resource.pattern` + `resource.prefix` 的等价语义（Liferay 生产验证十年）。已验证 API：`StaticMappings.add/remove/count`、`StaticResourceHandler`、`FileStaticRepository`。

**验收**：`/extension/files/scripts/index/import-from-local-dir.js` 等资源 200 且字节一致；卸载后 404。

**回退**：`refine.module.loader=butterfly`。

### A2 · 模块描述符与装配（必须自研）

**现状**：`ModuleAssembly` 复刻 Butterfly 的装配逻辑，仍调 `ButterflyMounter` / `registerModule`；`module.properties` 的 `implements` / `extends` / `module-impl` / `before` / `after` 无替代。

**做法**：
1. 新增 `ModuleDescriptor`：解析 `MOD-INF/module.properties` 全字段（`name`、`implements`、`extends`、`module-impl`、`refine.spi`、`before`/`after`）。
2. 新增 `ModuleGraph`：按 `before`/`after` 拓扑排序；有环时 fail-fast 并打印冲突链。
3. `ModuleAssembly` 改为只依赖自有 `RefineModule` 抽象（name / path / classloader / properties / mount prefix），不再 import `edu.mit.simile.butterfly.*`。
4. 模块对象创建：`module-impl` 反射替换逻辑保留（`RecordsDBModuleImpl` 等仍依赖该机制）。

**验收**：模块装载顺序与 Butterfly 基线一致（对比 `before/after` 解析日志）；`extends` 链继承属性等价。

### A3 · wirings 生成器（必须自研）

**现状**：`butterfly.getWirings(request)` 直接产出；`StaticMappings.locationMap` 包私有不可枚举。

**做法**：
1. 自建 `WiringRegistry`：模块注册时记录 `{name, mountPrefix, classMapping, clientSidePaths, dependencies}`。
2. 新增 `GetWiringsCommand`（仍在 core），或由新增的 `handle()` 直接响应 `wirings.js`。
3. 输出 JSON **保持现有外层结构与键名**（前端 `ModuleWirings[module]` 直接消费），只把数据源换成 `WiringRegistry`。

**验收**：`GET /wirings.js` 与基线逐字段等价（键序差异允许，按 JSON 深度比较）；`DOM.loadHTML` 全站可用。

### A4 · `-bundle.js` 拼接 Java 化

**现状**：core `controller.js` 用 Rhino 遍历 `ClientSideResourceManager.getPaths` 并 `module.getResource(path)` 读文件写出。

**做法**：
1. 新增 Java `BundleHandler`（或 core 命令）：按 bundle 名取路径列表 → 用模块 CL 的 `getResourceAsStream` 读 → 按序拼接，保留 `/* ===== {fullPath} ===== */` 分隔注释与去重语义。
2. 顺序来源从「注册调用顺序」升级为 `WiringRegistry` 的声明依赖 + 拓扑排序（借鉴 Drupal `libraries.yml` 的 `dependencies` 模型）。
3. 资源读取统一走 `PluginPackage.getResource` / 模块 CL，不再用 `ButterflyModule.getResource`。

**硬约束**：前端是非 ESM 全局脚本，**bundle 拼接必须保留**；不能改成 Module Federation。

**验收**：`/index-bundle.js`、`/project-bundle.js` 与基线字节一致（含分隔注释与顺序）。

### A5 · `.vt` 与 i18n 资源寻址

**现状**：`.vt` 由 `butterfly.sendTextFromTemplate` 渲染；i18n 命令直接持 `ButterflyModule`。

**做法**：
1. `.vt` 渲染改为直接调 Velocity（OR 已直接依赖 velocity，`HttpUtilities` 已有现成用法可复用），模板资源用模块 CL 解析。
2. `LoadLanguageCommand` / `GetLanguagesCommand` 的取资源改为 `RefineModuleContext.openResource(path)`（该原语已存在）。
3. `RefineModuleContext` 增 `registerLanguageBundle(...)`（可选），把 i18n 注册面补进 SPI 门面。
4. 新增 `RhinoControllerHost` + `ButterflyScriptShim`：按 `ButterflyModuleImpl.scriptInit()` 的语义执行 `MOD-INF/controller.js`，注入 `module`（即 `RefineModule`）/ `butterfly` / `logger`；`butterfly` 只实现 `getWirings` / `sendString` / `sendTextFromTemplate` / `responded`，并预置 `String.prototype.trim/startsWith/endsWith`。
5. `getWirings` 直接由 `ModuleRegistry` 的 mount 表生成（`{moduleName: mountPath}`），不再经过 Butterfly.js。
6. `sendTextFromTemplate` 先直接 `VelocityEngine.evaluate`；若与基线字节不一致，再移植 `LinkRewriter` 的 `@@...@@` / 图片路径过滤。

**验收**：`get-languages`、页面 HTML 与基线等价；切换语言正常。

### A6 · 扩展插件化收尾

| 模块 | 动作 |
|---|---|
| records-db / records-assets | 补 `META-INF/solon/*.properties` + `*SolonPlugin`（`Plugin.start` 内调 `SolonPluginBridge.registerCurrentModule()`） |
| jython | 判断是否保留；保留则 SPI 化（`MetaParser.registerLanguageParser` 已在门面内） |
| pc-axis | 同上（`registerImporterFormat/Extension` + `addClientSidePaths` 已在门面内） |
| wikibase（wikidata） | 当前被 `butterfly.modules.ignore` 忽略；决定退役或 SPI 化 |
| database | 已 `module.disabled`；建议正式移出构建 |

**验收**：`solon-plugin` 模式下 claimed 集合覆盖全部保留模块；每个模块 `Plugin.start/stop` 日志成对出现。

### A7 · 缺省切换与 Butterfly 移除

**顺序**：
1. `refine.ini` 打开 `REFINE_MODULE_LOADER=solon-plugin`，全量回归；
2. `ModuleLoaderRegistry` 缺省值改为 `solon-plugin`（保留 `butterfly` 值一个版本用于回退）；
3. `RefineServlet` 去掉 `extends Butterfly`，改 `extends HttpServlet` 并自持三个注册表；
4. 删除 `butterfly` 依赖（`main` / `server` / `modules/core` 三处 pom）与 `ButterflyModuleLoader` / `ModuleAssembly` 中的 Butterfly 调用；
5. 删除 `main/webapp/WEB-INF/butterfly.properties` 与 `modules.properties`（或转为自有配置）。

**验收**：`mvn dependency:tree` 无 `butterfly`；产物无 `butterfly-1.2.6.jar`；全部回归通过。

**回退**：切换前每一步都可用 `refine.module.loader=butterfly` 回退；第 3 步之后回退方式为 git revert。

---

## 5. 工作包 B：SDK 剩余项（发布后）

Release-1 已完成 B 包主体（传输、平台语义、授权门）；剩余仅：

| 项 | 现状 | 做法 | 验收 |
|---|---|---|---|
| B1 SSO 转发下沉 SDK | ✔ 已完成（2026-10-18）：SDK 新增 `com.jiongche.sdk.sso`（12 端点契约、任务状态、任务/导入/报告 DTO、`SsoDataQualityClient` 原始 JSON 客户端）与 `com.jiongche.sdk.web.RestModel`；模板 JSON 随 SDK 发布；平台 `SsoComplianceController` 直接引用 SDK，`OrDataQualityClient` 与平台 `spi/RestModel` 已删除 | 平台仅保留映射策略、Solon 适配与 SQLite 任务表 | SDK test 44/44；平台 package + 启动 + SSO 冒烟通过（模板 200、取或建、真实 Excel→OR 项目 READY、错误路径 400） |
| B2 部署布局归属 | ✔ 已完成（2026-10-18）：`docs/deployment-layout.md` 明确「布局知识在 SDK、产物由平台发布」，`BridgeLocator` javadoc 同步 | 不改 `deploy/bridge` 产物归属 | 文档一致 |

---

## 6. 工作包 C：3A 进程内剩余项

Release-1 已完成**最小闭环**（`CapabilityGate` / `GuardedAimpClient`，开关缺省 `off`，与 HTTP 反代并存）。剩余：

| 项 | 现状 | 做法 |
|---|---|---|
| C1 合规任务/结果查询进程内化 | 仍走 HTTP 适配 | 以共享存储或进程内服务替代；保留 HTTP 作为 `off` 回退路径 |
| C2 `off/on` 等价性门禁自动化 | R1-3 为冒烟验证 | 补 A/B 对照测试（同一请求经 `off` 与 `on` 返回等价） |
| C3 AI harness | 无代码，Release-1 明确后置 | 按 baseline §6 定位（可选层）单独立项，不在本计划范围 |

---

## 7. 工作包 D：工程化与合入

| 项 | 现状 | 做法 | 验收 |
|---|---|---|---|
| D1 装载器/SPI 自动化测试 | 部分完成（2026-10-17）：`ModuleDescriptorReaderTest` 2、`ModuleGraphTest` 3、`ModuleRegistryTest` 1、`ModuleLoaderRegistryTest` 2；golden 由 `scripts/verify_kernel_golden.ps1` 覆盖 | 补 `WiringRegistryTest` / `BundleHandlerTest`（纯函数字节断言） | core 新增用例全绿 |
| D2 开关通道完整 | 部分完成：`RefineSolon.RefineServletConfig` 已补 `refine.module.loader` init-param；三通道行为一致 | `web.xml` 可选补 | init-param / `-D` / 环境变量一致 |
| D3 既有失败测试修复 | ✔ 已完成（2026-10-17）：`HistoryEntry.sheetId` 加 `@JsonInclude(NON_NULL)`；`FilesImportingControllerTest` 修 Windows 路径 JSON 转义与 POSIX 权限断言 | — | core 215/215、FilesExtension 87/87 **全绿** |
| D4 分支合入主工作区 | 主工作区 `OpenRefine` 尚无 `com/jiongche/refine/{loader,spi,data}` 与两个 engine 模块 | 迁移分支 → 主工作区；打包 + 两条入口（`jinrefine.exe` / `refine.bat`）回归 | 主工作区与 worktree 行为一致 |

---

## 8. 参考实现与硬约束

| 需求 | 参照 | 可借鉴部分 |
|---|---|---|
| mount + 资源卸载 | **OSGi HTTP Whiteboard**（`osgi.http.whiteboard.resource.pattern` / `resource.prefix`）；Liferay `/o/` | 声明式前缀 + 生命周期绑定（Solon `StaticMappings` 已是等价物） |
| 服务端清单 → 前端装配 | **JupyterLab** `get_federated_extensions()` → `PageConfig`；Kibana 旧 `uiExports` | 清单只放「模块 + 入口 + 元数据」，不放资源路径列表 |
| 资源依赖排序 | **Drupal** `*.libraries.yml` 的 `dependencies`；WordPress `wp_enqueue_script` | 声明依赖 + 拓扑排序，替代隐式调用顺序 |
| 模块继承/装配 | **OSGi** `Bundle-ManifestVersion` / `Require-Bundle` | 字段命名与语义参照（OR 的 `implements/extends` 仍自研） |

**硬约束**：OR 前端是全局脚本（`<script>` 顺序 + 全局变量），**不是 ESM**。因此 Module Federation / Kibana 新版插件 API / Theia 前端贡献点均不可直接用；`-bundle.js` 拼接必须保留。

---

## 9. 启动顺序（按依赖拓扑）

```
D3（既有失败测试，✔ 已完成）
A0.1（ModuleView 依赖倒置，零行为变化）
A0.2（golden 输出基线）
  └─ A1（dispatcher + static mount）
       └─ A2（ModuleDescriptor + ModuleGraph + ModuleRegistry，shadow 对比）
            ├─ A3（wirings）──┐
            ├─ A4（bundle）   ├─ A6（扩展插件化收尾）
            └─ A5（.vt/i18n + Rhino host） ┘        │
                                                     └─ A7（缺省切换 + 移除 Butterfly）
B1/B2、C1/C2（与 A 并行）
D1/D2（随 A1–A5 同步补）
D4（最后）
```

---

## 10. 验收门禁总表

| 门禁 | 判定 |
|---|---|
| 资源等价 | `/extension/{module}/**` 全部资源 200，内容 SHA256 与基线一致 |
| wirings 等价 | `/wirings.js` JSON 深度比较无差异（键序允许） |
| bundle 等价 | `/index-bundle.js`、`/project-bundle.js` 字节一致 |
| 模板 / i18n 等价 | `get-languages` 与页面 HTML 等价 |
| 模块生命周期 | `Plugin.start/stop` 成对；卸载后路由与静态资源均 404 |
| 无 Butterfly | `dependency:tree` 无 butterfly；产物无 `butterfly-1.2.6.jar` |
| 前端不改 | `git diff --stat` 在 `main/webapp/modules/*/scripts/**` 为 0 |
| 回归 | `modules/core` 全量 test + FilesExtension / data-quality 扩展 test + 两条真实入口启动 |

---

## 11. 风险

| 风险 | 处置 |
|---|---|
| `wirings` 键序/结构漂移导致前端静默失败 | 以 JSON 深度比较为准，不做字节比较；上线前用浏览器控制台断言 `ModuleWirings` 可解析 |
| `module.properties` 的 `extends` 语义复杂（属性继承、覆盖） | A2 先做字段级回归（逐模块 dump 继承结果，与 Butterfly 对照） |
| 模块目录 vs jar 形态不匹配 | 先保留目录形态（当前 `SolonPluginModuleLoader` 已能发现 `MOD-INF` 内描述符），热管理能力后置 |
| 卸载后静态注册表不反注册（命令仍可达） | A1/A3 明确登记「注册 → 反注册」配对，单测覆盖 |
| 一次性切换缺省装载器风险高 | 按 A7 的 5 步渐进，前 4 步均可开关回退 |
| 模块初始化顺序变化导致 bundle 字节漂移 | golden `/index-bundle.js`、`/project-bundle.js` 字节比对；必要时用 `before/after` 固定顺序，并保存 `modules.order` 快照 |
| Rhino 预置函数缺失导致 `-bundle.js` 静默失效 | `RhinoControllerHost` 必须预置 `String.prototype.trim/startsWith/endsWith`；增加 core controller 的冒烟用例 |
| `sendTextFromTemplate` 的 `LinkRewriter` 过滤差异 | 首页/错误页字节比对；有差异再移植 `@@...@@` 与图片路径过滤 |
| 静态资源越权 | `MOD-INF/**` 一律 403；路径 normalize 后校验仍在模块根内；补安全回归用例 |
| 静态 Handler 抢先于 `module.process()` | 不注册全局 `StaticResourceHandler`，统一由 `ModuleRequestDispatcher` 先 process 后 static |
| `controller.js` 范围膨胀 | A0.3 冻结：本轮只补 Rhino shim，不做 JS→Java 迁移 |

---

## 12. 待决策清单（2026-10-17 深夜，一次性给出）

> 背景：Release-1 + R1-5 已完成，后续到 §6 目标架构的主体是 A 包。以下决策点需用户拍板后开始整晚执行。

| 编号 | 决策点 | 选项 | 推荐 | 影响 |
|---|---|---|---|---|
| D1 | **今晚交付范围** | ① A0+A1+A2（内核骨架 + dispatcher/静态 shadow）② A0–A5（新内核可完整接管请求，缺省仍 butterfly）③ A0–A7 全量 | **①**（②为 stretch） | 直接决定能否在早上给出可验收、可回退的成果；③ 无法保证质量 |
| D2 | **`controller.js` / Rhino** | ① 保留，仅补 4 方法 shim ② 本轮把 7 个 controller.js 迁到 Java SPI/注解 | **①** | ② 会把 A 包扩大成两项工程，且触碰 159 处注册，风险高 |
| D3 | **新增依赖** | ① 允许新增 `solon-web-staticfiles` + 显式 `org.mozilla:rhino` ② 尽量零新增（改用 Tomcat DefaultServlet / 手写静态 + 保留 Butterfly 传递 Rhino） | **①** | ② 会引入容器绑定或依赖传递侥幸，且 Rhino 版本不可控 |
| D4 | **扩展插件化范围** | ① 仅 files / data-quality（已有 `META-INF/solon`）② 加 records-db / records-assets ③ 再加 jython / pc-axis / wikibase / database | **②** | A6 的验收面；③ 中 database 建议移出构建，wikibase 建议退役，需单独拍板 |
| D5 | **缺省装载器与 Butterfly 删除** | ① 今晚缺省仍 `butterfly`，新内核用开关验证 ② 切 `solon-plugin`，保留 `butterfly` 回退一个版本 ③ 直接删除 Butterfly | **①**（D1=②时用②） | ③ 要求 A0–A6 全部通过，今晚不现实 |
| D6 | **`module-impl` 改造** | ① 允许 RecordsDB / RecordsAssets 改为 `AbstractRefineModule` ② 保留 `ButterflyModuleImpl` 基类 | **①** | ② 则 A7 无法删除 Butterfly 依赖 |
| D7 | **输出等价标准** | ① JSON 深度比较 + bundle/静态字节 SHA256 + 首页容错 ② 仅语义等价 | **①** | ② 无法发现前端静默失败 |
| D8 | **配置键兼容** | ① 继续读 `butterfly.modules.path` / `ignore` / `default.mountpoint` ② 新键 `refine.modules.*` + 兼容旧键 | **①** | ② 增加一层映射，但长期更干净；可放到 A7 之后 |
| D9 | **验收门禁** | ① 单测 + golden + 一次真实启动冒烟 ② 只跑单测 ③ 单测 + 打包产物 | **①**（D1=②时补打包） | 无冒烟无法证明 `/wirings.js`、bundle、模板真的可用 |
| D10 | **提交与回填** | ① 允许本地分阶段提交（不推送）+ 回填 baseline/release notes ② 只在最后一次性提交 ③ 不提交 | **①** | 分阶段提交可在失败时精确回退；推送需另行授权 |
| D11 | **若今晚未能全绿** | ① 保留 `butterfly` 开关，交付已完成切片 + 如实记录 ② 不回退，继续硬推 | **①** | 防止把可交付基线改坏 |

**关键结论（必须先对齐）**：A 包全部 + A6/A7 + 打包冒烟，一个晚上**无法保证**完成并验收；可承诺的是一到两个可独立验收的切片（推荐 D1=①，最多 D1=②）。

---

## 13. 附：本轮文档修订记录

- 新增 §2.6 生态选型结论：复用 Solon hotplug / solon-web-staticfiles / Rhino / Velocity；自研 descriptor / graph / wirings / bundle。
- 新增 A0：`ModuleView` 依赖倒置 + golden 输出基线 + 冻结 `controller.js` 范围。
- A1 补充：静态资源由 `ModuleRequestDispatcher` 调 `StaticMappings.find`，不注册全局 StaticHandler；保留 `MOD-INF` 403 与路径穿越校验。
- A5 补充：Rhino 宿主、`butterfly` 4 方法 shim、`String.prototype` 预置、`LinkRewriter` 回退策略。
- §9 启动顺序加入 A0；§11 风险补充 6 项；新增 §12 待决策清单。

---

## 14. R2-1 执行记录（2026-10-17 深夜，✔ 已完成切片）

**用户决策**：今晚范围 A0–A5；保留 `controller.js`/Rhino；允许新增静态/Rhino 依赖；RecordsDB/Assets 改基类并加入插件化；**缺省切 `solon-plugin`**；验收采用 JSON 深度 + 字节 SHA256 + 单测 + 启动冒烟；本地分阶段提交；未全绿保留 `butterfly` 回退；允许明早继续推进未完成项。

**已完成**：

1. **A0 依赖倒置**：新增 `com.jiongche.refine.kernel.ModuleView` 与 `ButterflyModuleView`（过渡适配器）；`ClientSideResourceManager.QualifiedPath.module`、`ButterflyModuleContext`、`SpiBootstrap`、`SolonPluginBridge` 全部改为面向 `ModuleView`/`RefineModule`。旧 `ButterflyModule` 重载保留，零行为变化。
2. **A2 内核骨架**：新增 `ModuleDescriptor`、`ModuleDescriptorReader`、`ModuleGraph`（Kahn + 环检测）、`ModuleRegistry`（最长 mount 前缀、相对路径、依赖图顺序）。
3. **A1/A3/A4/A5 请求内核**：
   - `ModuleRequestDispatcher`：`wirings.js` → `WiringsHandler`；`*-bundle.js` → `BundleHandler`；core `templatedFiles` → `TemplateService`；其余 `module.process()` 优先，再静态。
   - `StaticResourceService`：复用 `module.getResource`，补 MIME、目录 `index.html`、`MOD-INF` 403、越界校验。
   - `BundleHandler`：与 core controller.js 相同的分隔注释/顺序/换行，字节一致。
   - `TemplateService`：Velocity 2.4.1 直接渲染，复刻 `params/projectID/styleInjection/scriptInjection/encodingJson`。
4. **A6 扩展插件化**：新增 `RecordsDBSolonPlugin`、`RecordsAssetsSolonPlugin` 与 `META-INF/solon/*.properties`；`claimed=[records-db, files, data-quality, records-assets]`。
5. **缺省切换**：`ModuleLoaderRegistry` 缺省改为 `solon-plugin`；`refine.ini` 注释同步；`-Drefine.module.loader=butterfly` 回退实测 200。
6. **测试**：`modules/core` **223/223**；FilesExtension **87/87**；files-engine **33/33**；data-quality-engine **3/3**；新增 `ModuleDescriptorReaderTest` 2、`ModuleGraphTest` 3、`ModuleRegistryTest` 1、`ModuleLoaderRegistryTest` 2。
7. **Golden 基线**：`docs/ruyi/design/golden-release1/`（9 项 + `SHA256SUMS.txt`）；`scripts/verify_kernel_golden.ps1` 实测 **9/9 PASS**。`index-bundle.js`、`project-bundle.js`、`/`、`/about.html`、静态资源均 **SHA256 一致**；`wirings.js` **JSON 深度一致**（仅键序不同）。

**明确未完成（下一阶段）**：

- **A2 完整替代**：模块发现、`wireModules/configureModules`、`module-impl` 装配仍由 `Butterfly.configure()`/`ModuleAssembly` 完成；当前内核只接管请求服务与注册表。
- **A5 Rhino host**：`controller.js` 仍由 Butterfly 的 Rhino 宿主执行；`ButterflyScriptShim` 尚未落地。
- **A7**：Butterfly 依赖与 `extends Butterfly` 未删除；`core`/`jython`/`pc-axis`/`database` 仍由 Butterfly 装载。
- **B1/B2、C1/C2、D1/D2/D4** 未动。

**已知坑**：

- `records-db`/`records-assets` 的 `src` 同时作为资源目录后，localizer 会把 `META-INF/solon/*.properties` 误当资源束；已加 `fileMask=no-localizer-bundles.properties`。
- `records-db` 执行 `mvn install` 会触发既有 Javadoc 错误（`JsonFieldExtractor` 注释中的 `>`）；构建时用 `-Dmaven.javadoc.skip=true`。
- `database` 扩展仍打印 `DatabaseModuleImpl` `ClassNotFoundException`，属既有禁用行为。

**验收命令**：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11.0.17'
mvn -q -pl modules/core test
mvn -q -pl extensions/FilesExtension,extensions/files-engine,extensions/data-quality-engine test
# 启动（缺省 solon-plugin）后：
powershell -ExecutionPolicy Bypass -File .\scripts\verify_kernel_golden.ps1 -BaseUrl http://127.0.0.1:3533
# 回退：
java '-Drefine.port=3534' '-Drefine.module.loader=butterfly' ... com.google.refine.Refine
```

> 上面的 `refine.module.loader` 回退在 A7 后已不存在（见 §15）。

---

## 15. A7 执行记录（2026-10-18，✔ 已完成）

**目标**：彻底删除 Butterfly 依赖，模块发现/装配/`controller.js` 宿主/请求分发全部由自研内核承担。

**代码**：

1. **新增内核**
   - `kernel/AbstractRefineModule`：`RefineModule` 默认实现（描述符/目录/类加载器/挂载前缀/extends 回退/`getResource` 越界校验）。
   - `kernel/RhinoControllerHost`：Rhino 1.7.14 执行 `MOD-INF/controller.js`，注入 `module` / `butterfly` / `logger`，预置 `String.prototype.trim/startsWith/endsWith`；`butterfly` shim 只保留 `getWirings/sendString/sendTextFromTemplate/responded`。
   - `kernel/ModuleKernel`：`butterfly.properties` + init-param 多根发现、`MOD-INF` 递归、ignore（取首个逗号前 token，保持旧语义）、稳定 Kahn、每模块 `PluginClassLoader`、`module-impl` 反射装配（缺失时回退默认实现）、Solon 插件生命周期、TCCL 切换、反序销毁。
   - `spi/DefaultModuleContext`：框架无关的 `RefineModuleContext` 实现。
2. **`RefineServlet`** 改为 `extends HttpServlet`，删除 loader 接缝与 Butterfly 状态访问器；`service()` 非 `/command` 走 `ModuleKernel`；`getModule()` 返回 `RefineModule`。
3. **类型迁移**：`SpiBootstrap` / `SolonPluginBridge` / `ClientSideResourceManager` / `OperationRegistry` / `ImportingManager` / `ReconConfig` / `LoadLanguageCommand` / `GetLanguagesCommand` / `HttpUtilities` 全部去 Butterfly 类型；`records-db` / `records-assets` / `database` 的 module-impl 改继承 `AbstractRefineModule`。
4. **删除**：`ButterflyModuleLoader`、`StandaloneModuleLoader`、`SolonPluginModuleLoader`、`RefineModuleLoader`、`ModuleLoaderRegistry`、`ModuleAssembly`、`ButterflyModuleView`、`ButterflyModuleContext`、`ModuleLoaderRegistryTest`。
5. **依赖**：core/main/server/database 删除 `org.openrefine.dependencies:butterfly`；新增 `org.mozilla:rhino:1.7.14`；显式声明 `commons-lang:2.6`（历史 `Validate.notNull` 抛 `IllegalArgumentException` 的语义不能变）。
6. **运行期清理**：`refine.bat` / `refine` / `refine.ini` 删除 `REFINE_MODULE_LOADER` 透传；`RefineSolon` 删除 `refine.module.loader` init-param；服务启动日志固定为 `Module loader: solon-kernel`。

**字节一致性关键点**：Butterfly 用 `HashMap<String, Module>` 存模块并按 `keySet()` 迭代初始化，bundle 顺序即该顺序。`ModuleGraph` 的稳定 Kahn 以同样的 `HashMap` 迭代序作 tie-breaker（`requires`/`extends` 仍优先），实测初始化序恢复为
`[core, database, jython, records-db, module, files, pc-axis, data-quality, records-assets]`，`index-bundle.js` / `project-bundle.js` / `index.html` 恢复 SHA256 一致。

**验收（2026-10-18 实测）**：

- `modules/core` **221/221**；FilesExtension **87/87**；files-engine **33/33**；data-quality-engine **3/3**。
- `server` / `main` / `core` / `grel` 及 6 个扩展 `package` SUCCESS。
- 启动冒烟（`solon-kernel`）：9 个模块全部装载，`registry` 与 `order` 均为上述顺序；`/command/files/extraction-config` 返回 200，证明扩展 SPI 已注册。
- Golden **9/9 PASS**（含三项字节 SHA256 与 `get-version.json`）。
- `server/target/lib`、`main/webapp/WEB-INF/lib` 均无 `butterfly-*.jar`，Rhino 仅 1.7.14。

**启动冒烟命令**（先 `package` 扩展，使各自 `module/MOD-INF/lib` 就绪；**勿**把扩展 `target/classes` 直接塞进 app classpath，否则 Solon 会在模块初始化窗口外启动其 `META-INF/solon` 插件）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11.0.17'
mvn -o -pl extensions/FilesExtension,extensions/data-quality,extensions/records-db,extensions/records-assets,extensions/jython,extensions/pc-axis -am -DskipTests package
$root='G:\workshop\RUYI\build\or-solon-poc'
java -cp "$root\server\target\openrefine-3.11.0.01-server.jar;$root\server\target\lib\*" `
  "-Drefine.webapp=$root\main\webapp" "-Drefine.data_dir=$env:TEMP\jinrefine-a7-data" `
  -Drefine.port=3533 -Drefine.autosave=5 -Drefine.headless=true com.google.refine.Refine
```

**已知既有失败（非 A7 引入，未修）**：`main` 模块 1039 项中 38 项失败，全部来自多 Sheet 改造（`260002e8a`）：① `MassRowColumnChange` 等 change 对单 Sheet `new Project()` 取 `getActiveSheetData()` 为 null（26 项）；② `ExcelImporterTests` 列数与多 Sheet 导入预期不符（9 项）；③ 根 pom surefire 固定 `-Duser.language=fr`，`RowDuplicatesRemovalOperationTests` 断言英文（3 项）。A7 前即存在，建议单独立项修复。
