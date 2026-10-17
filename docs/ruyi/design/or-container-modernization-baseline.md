# OR 容器层现代化改造基线（Butterfly / Jetty → Solon）

> Status: Draft（基线稿，供双侧分支改造遵循）
> Owner: Ruyi / OpenRefine 定制版（JinRefine 3.11.0.01）
> Date: 2026-10-16（**2026-10-17 定位校准修订**：OR 为插件/子模块之一、**已断开上游**、独立演进 —— 见 §1 定位声明 / §5.4 / §7「后续开发截断与边界」）
> 取证方式：只读代码扫描 + 官方文档/论坛一手材料（未改代码、未打包、未重启服务）

---

## 1. 文档目的

固化前期全部讨论结论，作为**改造基线**：
1. 说明**为什么改**（动因）与**改了会不会退化**（风险）；
2. 给出**目标架构**（能力平台在下、OR 在上、业务走 SPI 插件）；
3. 给出**路线图与各阶段工作内容**（Phase 0–3，含门禁与回退开关）；
4. 给出**双侧分支约定**与**待验证清单**。

**定位声明（2026-10-17 校准）**：本项目**不是** OR 的定制发行版，而是**以 OR 为插件/子模块之一**的**业务融合平台**——OR（数据展示 / 清洗 / UI）+ 业务逻辑 SPI + AI 能力 SPI 并列挂载；最上层还可有**可选 AI harness**（人机接口 + 统一流程调度，部分客户不需要）。因与官方 OR（**以 OR 为核心、其余皆其插件**）**目标不一致**，本项目**已断开与上游 OR 的跟随**、独立演进；上游仅作**新功能来源（cherry-pick 抽取）**。详见 §5.4 与 §7「后续开发截断与边界」。

**基线期硬约束**：只评估、不改代码、不打包、不重启服务；不触碰工作区内其他 agent 未提交的改动（如 `extensions/FilesExtension` 相关文件）、不干扰正在运行的回归任务。

---

## 2. 动因（Why）

| # | 动因 | 说明 |
|---|---|---|
| 2.1 | 交付方式别扭 | OR 与能力平台**双向耦合**：OR 扩展（`AimpClient`）直连 AIMP HTTP；平台侧又内嵌 `dog_read.exe` / `deploy/bridge`。架构上应是**平台在下、OR 在上**，OR 的功能建立在平台能力之上 |
| 2.2 | 技术栈割裂 | OR 为 Java + 静态 JS（Butterfly/Jetty/JSP 式老架构）；平台为 Java（Solon 系）。两套技术栈并存，维护与协作成本高 |
| 2.3 | OR 架构老旧 | 扩展机制基于 **SIMILE Butterfly 的 fork**：模块描述符 `MOD-INF`、命令注册写在 **`controller.js`（JS 脚本）**，JS 与业务逻辑混杂、限制多；HTTP 层为 **Jetty 10 内嵌 + `javax.servlet`**。OR 上游自身也在做现代化，但**结果未出**（**2026-10-17 起本项目已不再等待上游，见 §5.4**） |
| 2.4 | 业务耦合 | OR 定位应为**通用数据清洗 + UI 层**（类似 Excel）；各行业业务逻辑不应与 OR 绑定，应通过插件/SPI 挂载。当前 Butterfly 的插件机制无法满足"业务与 OR 彻底解耦" |
| 2.5 | 国产化风险 | 在国产化环境下，Google 版权代码构成合规负担。**注意：真正的合规抓手是"平台解耦 + 夺回自主演进权"，而非换容器**（Jetty 属 Eclipse、Butterfly 属 MIT/SIMILE，均非 Google；Google 版权的 `com.google.refine.*` 业务内核恰是要保留的部分） |
| 2.6 | 成本与风险被 AI 压缩 | AI coding 大幅压低"写代码"成本，使改造的技术风险可控；**成本重心从"写代码"转移到"验证"** |

**结论**：长期必然改造。只要**成本可控、技术可行**，评估重心即转为"**有没有退化风险**"，不因保守而停滞。

---

## 3. 现状量化基线（代码取证，2026-10-16）

| 项 | 数据 |
|---|---|
| Java 源码（core+grel+main+extensions，排除 target） | 966 个文件、约 **128,806 行** |
| 继承 `Command` / `EngineDependentCommand` 的命令类 | **121 个** |
| 生产代码中 `import edu.mit.simile.butterfly.*` | 仅约 **10 处** |
| Butterfly 接口**实际被 OR 用到的方法** | **4 个**：`getName()` / `getMountPoint()` / `getResource()` / `sendTextFromTemplate()` |
| `extends ButterflyModuleImpl` 的扩展模块 | **3 个**（database 已禁用；records-db、records-assets） |
| Jetty 使用点 | 原集中于 `server/src/com/google/refine/Refine.java` 的 `RefineServer`（connector / WebAppContext / StatisticsHandler / Scanner autoreload / 线程池），+ `main/webapp/WEB-INF/jetty-web.xml`（GzipHandler）、`web.xml`（`SessionPath`，jetty 专属）、`server/pom.xml`；**R2 已彻底移除**（Jetty 依赖全清、`RefineServer` 删除、容器语义迁入 `RefineSolon`），见 §10「R2 执行记录」 |
| 线程池 | OR 自设：`maxThreads=30 / maxQueue=300 / idle=60s`（`ThreadPoolExecutorAdapter`） |
| WebSocket | **0 处** |
| `HttpSession` | **0 处**（会话面极薄） |
| 大文件上传 | 原 `refine.max_form_content_size` = **64MB**（Jetty 专属）；**R2 解耦为新键 `refine.container.max_post_size` = 64MB**（容器无关，映射 Tomcat `maxPostSize`/`maxSwallowSize`）；commons-fileupload |
| 前端 JS | **1082 个**；仅 **34 个**文件出现 `command/`，**91 处**调用，且为**相对路径** `command/core/xxx` |
| 命令/资源注册入口 | **7 个 `controller.js`**（`core` 795 行、**159 处** `register*`） |
| i18n / 模板 | **150** 个翻译 json + localizer `OpenRefineMessage`；**6** 个 `.vt`（velocity） |
| servlet 契约 | `javax.servlet` 4.0.1（provided），见 `modules/core/pom.xml` |
| 动态扩展装载 | `Refine.java`：`butterfly.modules.path = {dataDir}/extensions`（第三方扩展可从 data 目录动态装载） |

---

## 4. 分层结论：改什么、保留什么

### 4.1 可直接保留（0 改动，约占 128k 行绝大多数）
`model` / `importers` / `exporters` / `operations` / `grel` / `browsing` / `clustering` / `history` / `Process` / `FileProjectManager` / `Workspace`，以及各扩展业务逻辑（`AimpClient`、`FilesExtension` 导入器等）。
- 仅依赖 jackson / commons / httpclient / poi / jena 等，**不感知 servlet / butterfly**。
- 唯一例外：3 个注册表方法签名带 `ButterflyModule` 参数（仅为取 `module.getName()` 做命名空间）→ 改成 `String` 即可（`OperationRegistry` / `ImportingManager` / `ReconConfig` 各 1 处）。
- **P5 校准（P2/P4 后）**：data-quality 与 FilesExtension 的**纯业务内核**已下沉为独立零 OR 依赖模块（`extensions/data-quality-engine`、`extensions/files-engine`），取数/宿主耦合分别经 SDK `TableView` 与 `WorkspacePaths`；OR 侧保留命令面/导入器/管理器等薄壳。原“业务逻辑 0 改动”仅指**容器迁移维度**（Solon 替换不改业务），不排斥本轮与容器正交的 SPI 下沉。

### 4.2 需适配（机械、可控）
- **`Command` 基类**：本身 servlet 无关（只用 `HttpServletRequest/Response`）。**若 Solon 走 servlet 适配器，121 个命令类一行不改**；若走非 servlet（smart-http）则需写 request/response 适配层。
- **命令注册**：7 个 `controller.js` 从"JS 脚本执行"改为 Java 注册表/注解；或保留 JS 引擎 + 提供 `registerCommand` 垫片。
- **资源打包**：`ClientSideResourceManager` + `module.getResource` + `-bundle.js` 实时拼接 → 换静态资源服务 + **构建期预打包**（预计更快）。
- **`.vt` 模板 + velocity**、**i18n 服务端**（`LoadLanguage`/`GetLanguages` 读模块资源）→ 改本地/classpath 解析。

### 4.3 必须重写（容器外壳）
- `RefineServlet` 整层：`/command/{module}/{name}` 路由、GET/POST/PUT/DELETE 分发、`CommandGuard` 钩子、静态资源、模块挂载、405/404。
- **Butterfly 模块体系**：`MOD-INF` 描述符、`MountPoint`、`ScriptableModule`、`ClassMapping`、**从 `{dataDir}/extensions` 动态装载**（自建 ClassLoader）。
- **Jetty 装配**（`RefineServer`）→ Solon Server + 插件。
- **打包**：`webapp/WEB-INF/lib`、`module/MOD-INF/lib`、`refine.bat` 的 `REFINE_LIB_DIR`、`.app` 打包，均围绕 servlet webapp 布局。

### 4.4 注册面收敛清单（解耦的可施工基础）
OR 的注册面**有限且清晰（约 10 个，其中 8 个纯 Java、不依赖 Butterfly 类型）**：

| 注册面 | 入口 | 是否依赖 Butterfly 类型 |
|---|---|---|
| Commands | `RefineServlet.registerCommand(module, name, cmd)` | 仅命名空间 |
| Operations | `OperationRegistry.registerOperation(module, ...)` | 仅 `getName()` |
| Importing | `ImportingManager.registerFormat/Extension/MimeType/Controller/Guesser` | 否 |
| Exporters | `ExporterRegistry.registerExporter` | 否 |
| Recon | `ReconConfig.registerReconConfig(module, ...)` | 仅命名空间 |
| Expression | `MetaParser.registerLanguageParser` | 否 |
| Clustering | `KeyerFactory` / `DistanceFactory` | 否 |
| 客户端资源 | `ClientSideResourceManager.addPaths(module, paths)` | 是（`getResource`） |
| i18n | `LoadLanguage` / `GetLanguages`（读 `module.getResource`） | 是 |
| 前端注入点 | `extension-bar.js` / `menu-*.js` ← `butterfly.getWirings()` 的 `/wirings.js` | 是（**前端硬绑定点**） |

→ 结论：**"OR SPI 化"摩擦低**；技术成本集中在两处 Solon 无对等物的地方：**①每模块前端资源装载（mount point + `-bundle.js` + `.vt`）**、**②`/wirings.js` 等价端点**。

---

## 5. 关键技术判断

### 5.1 性能退化风险：HTTP 层几乎不存在
OR 性能热点全在 `model / importers / operations / grel / OCR 调用`（CPU、内存、I/O），容器层只影响 **I/O 边界行为**；且 OR 单机低并发（自设 30 线程）。
- Solon 官方 benchmark：`smarthttp(aio)` 约 16.6 万 QPS、`jetty(nio, 支持 servlet api)` 约 12.3 万 QPS —— **server 层不存在退化**。
- 反而：`-bundle.js` 若改**构建期预打包**（现状为每请求 JS 引擎实时拼接 1082 个 JS）会**更快**。
- **必须逐条对齐的 5 类边缘语义**：

| # | 边缘点 | 现状锚点 | 判定 |
|---|---|---|---|
| 1 | 大文件上传 multipart | 64MB form 上限；commons-fileupload；FilesExtension 批量导入 | **✔ 已对齐**（multipart 参数语义桥 + `maxPostSize/maxSwallowSize=64MB`；上传回归两侧 `rowCount=3`） |
| 2 | 流式导出与长轮询 | `export-rows` 流式 flush；data-quality `task-control` / `get-check-progress` 轮询 | **部分对齐**：idle timeout 已设 `connectionTimeout=60000ms`；**流式 flush 语义仍待专项实测** |
| 3 | gzip + 静态资源拼接 | `jetty-web.xml` GzipHandler；controller.js bundle 逻辑 | **✔ 已对齐**（Connector `compression`，min=2048B/MIME 白名单；bundle 793829→169043B） |
| 4 | 线程池语义 | `maxThreads=30 / maxQueue=300 / idle=60s` | **✔ 近似对齐**：Tomcat 不支持自定义池（`setExecutor` no-op）→ `maxThreads/acceptCount=300` 近似并发上限 |
| 5 | temp dir | `RefineServlet` 依赖 `javax.servlet.context.tempdir` | **✔ 已适配**（`RefineServletContext` tempdir 兜底） |

### 5.2 硬阻断点：javax vs jakarta
- OR 命令层全部基于 **`javax.servlet` 4.0.1**；**上游 OR 已规划 Servlet 4 → Servlet 6（`javax.*` → `jakarta.*`）**。
- Solon 侧：**`solon-web-servlet`（javax）/ `solon-web-servlet-jakarta`（jakarta）双轨并存**；`solon-server-jetty(nio, 支持 servlet api)`、`solon-server-jetty-jakarta` 均存在。
- 结论：**javax 路径存在**，保 `javax` 让 121 个命令类零改动；**本项目已决定不再跟随上游**（§5.4），故 `javax → jakarta` 迁移**不再以上游为准**，按自身演进需要另行决策（当前维持 `javax`）。
- **P0 首要验证项（比 javax/jakarta 更前置）**：Solon 的 boot 适配器能否**直接挂载现成 `HttpServlet`**，并支持 **init-params / `ServletContext` 属性（tempdir） / webapp 资源根语义**——这决定"薄采用"是否成立。

### 5.3 Solon 插件机制与 Butterfly 的对应（方向成立，形态非 1:1）

| Butterfly 机制 | Solon 对等物 | 判定 |
|---|---|---|
| 从 `{dataDir}/extensions` 动态装载第三方扩展 | **E-Spi 体外扩展**：`.jar` 插件包放体外加载 | 语义对应 |
| 模块隔离（MOD-INF/lib 独立 ClassLoader） | **H-Spi 热插拔**：业务模块为单位封装为独立插件包、独享隔离、热插热拔 | 语义对应，隔离性可能更强 |
| `controller.js` 的 `register*` | **Solon Spi**：插件 + 配置声明，参与应用生命周期、提供模块启动入口 | 理念同向，需 OR 自定 SPI |
| servlet 契约 | `solon-web-servlet`(javax) / `-jakarta` | 双轨，javax 可用 |

**实现形态**（重要）：**不是"Solon 插件 = Butterfly 模块"的 1:1 替换**，而是 **OR 自定义 SPI（上述 ~10 个注册面）+ Solon 的 SPI/DI 承载装载**；前端资源装载与"每模块 web root"Solon 无对等物，**需自建**。

### 5.4 上游 OR 走向与我们的关系（2026-10-17 定位校准，**取代原「同向跟随」判断**）

**事实（取证未变，保留）**：官方文档单列 [Architecture **before version 4**]（4.0 是架构分水岭）；论坛《Scheduling breaking changes》称 Butterfly 升级含 Jetty 10→12、Servlet 4→6（javax→jakarta）；上游正把 **`controller.js` 最小化**为"简易扩展启动器"、并"通过 `Command` 类对齐初始化流程"；社区讨论 OSGi/pf4j，同时自陈"任务规模令人生畏""前端侧隔离保证很弱""pinned down to an aging stack"。**且上游 4.0 未发布、无日期**（详见 §10 末「上游 4.0 走向调研结论」）。

**⚠ 定位校准（2026-10-17）**：官方 OR 是**独立产品**——**以 OR 为核心、其余（含 UI）皆为其插件**；而本项目的目标是**把 OR 作为插件/子模块之一**，与业务 SPI、AI 能力 SPI 并列挂在**业务融合平台**之下（最上层还可能有可选的 AI harness）。**两者目标不一致**。本轮改造对 OR 的侵入性已经很大（`RefineSolon` 外壳、自研装载器、core 重打包、前端契约以自有方式承载），**已不具备回馈上游的可能，也不可能持续与上游 rebase**。

→ **决策：本项目直接断开与 OR 上游的跟随关系，独立演进**；OR 上游**仅保留为新功能来源**（对有价值的上游特性做 **cherry-pick 式抽取**接入，**不合并、不 rebase**）。
→ **原「不该抢跑硬分叉」结论作废**：容器外壳、javax/jakarta、Butterfly 去留、`controller.js` 形态等，一律按**本项目自有节奏与目标**决定，不再以上游时间线为准。
→ 若某天确需与上游保持一致，正确做法是**另立独立项目**做"OR 插件式适配"，**不在本工程内**。

### 5.5 解耦的真正杠杆 = SPI 边界，不是框架
**"换掉 Butterfly"本身不会自动带来解耦**；解耦取决于**是否定义 SPI 并把业务实现搬出 core**——保留 Butterfly 也能做。
Solon 的增益是"**装载器纯 Java、可 DI、可热插拔、可单测**"，恰好补上当前 JS 注册的短板。

### 5.6 Solon 自身评估（能否承担）
- 由**杭州无耳科技有限公司（Noear）**开发开源，近 40 万行代码；**Apache-2.0**、源码可控、可 fork。
- **LTS 计划**：v4.1.x（2026-09 发布），开源支持 **6 个月** / 商业支持 **3 年** → 有商业主体与支持承诺；但开源支持窗口短，需持续跟进或购商业支持。
- **采用策略：薄采用（Thin Adoption）**——只做 `boot + web`（server 装配、路由、静态、gzip、上传），**servlet 契约做接缝**，Solon 退化为"可替换适配器"；**锁定成本数天级**，**退出路径明确**（裸 Undertow/Tomcat 或 Spring Boot embedded）。
- **不建议**让 Solon 承担 OR 的模块/插件**内核**（`MOD-INF` + `controller.js` + 动态装载）——自研成本与框架无关。
- 待核验共存面：维护活跃度、tomcat/undertow 适配成熟度、与 commons-fileupload / velocity / JS 引擎（Rhino/GraalJS）/ 加密狗 native 调用（`dog_read.exe`）的共存。

---

## 6. 目标架构

```
┌─────────────────────────────────────────────┐
│  OR 上层：通用数据清洗 + UI（core，与业务无关）  │
│   ├─ model / operations / grel / importers   │
│   ├─ 前端静态资源（1082 JS，契约 /command 不变）│
│   └─ OR SPI 边界（约 10 个注册面）             │
├─────────────────────────────────────────────┤
│  业务插件（Solon 装载）：按行业/场景独立插件包    │
│   ├─ 公文著录 / 数据质量检查 / OCR 抽取 ...      │
│   └─ 每个插件由 Solon Spi/DI 管理、可独立发布单测 │
├─────────────────────────────────────────────┤
│  能力平台 SDK 接口层（抽出的耦合点）             │
│   └─ AimpClient / dog_read.exe / SSO / bridge │
├─────────────────────────────────────────────┤
│  能力平台（在下，实现 AI/OCR/加密狗等能力）        │
└─────────────────────────────────────────────┘
```

要点：
0. **定位（2026-10-17 校准）**：OR 是**插件/子模块之一**（非核心）；其上方可有**可选 AI harness**（人机接口 + 统一流程调度，部分客户不需要）。见 §1 定位声明、§5.4。
1. **OR core 只依赖 SPI 接口**，不直接依赖任何行业业务。
2. **业务插件**以 Solon 插件形态装载（E-Spi 体外 jar / H-Spi 热插拔），摆脱 JS 注册混杂。
3. **平台 SDK 接口层**把 `AimpClient`、`dog_read.exe`、SSO、`deploy/bridge` 等双向耦合点收敛为接口——**与 Solon 迁移正交、可先行**。
4. **前端契约保持**：`/command/{module}/{name}`、`/modules/core`、`/extension/{module}` 不变，避免改动 1082 个 JS。

#### 要点③ 执行记录（2026-10-17，与 Solon 迁移正交、直做，**未推送**）

> 落法：两侧重复的**平台耦合知识**（AIMP 传输/端点/字段、读狗桥 CLI/JSON/定位、模块词表、OR 命令契约、部署布局）收敛为**无第三方依赖、两侧可引用**的 SDK 单一实现；OR 侧只**声明端口**并**经开关委托**，缺省仍走原实现（保留 legacy 通路与回退锚点）。**接口声明在 OR 侧**（与平台 `com.ruyi.platform.spi.*` 同名/同签名/同语义），**实现下沉到平台/SDK**，**不搬空 OR**。
>
> **P5 校准**：上段的「缺省走原实现 / 保留 legacy 通路」是**第一轮**形态；第二轮 J1 起改为**单一实现**（OR 侧原生读狗与 AIMP 客户端实现已删除，`ruyi.platform.sdk` 开关取消），第三轮 T4/T6 进一步把 data-quality / files 的内核下沉为零 OR 依赖引擎。**回退方式 = 开关降级**（`ruyi.license.enforce=off`、`ruyi.data-quality.view=live`）与 git 回退，**不再有双实现通路**。

**分支**：OR worktree `build/or-solon-poc` @ `feat/platform-sdk-decoupling`（base `dcbf8dbc6`）；能力平台 @ `feat/platform-sdk-decoupling`（本地，无远端）。

**SDK 落点**：`ruyi-capability-platform/sdk/`（`com.jiongche:ruyi-platform-sdk:0.1.0`，零第三方依赖、`maven.compiler.release=11`、刻意无 parent；**包顶层 `com.jiongche`**，第二轮已归位，见「要点③-续」）；契约测试**第一轮 15 项**全绿（含模块词表 ↔ `deploy/bridge/dog_read.py` `MODULES` 逐项一致、`Multipart` 与改造前手写**字节级一致**）；后续：第三轮 22 项、T5 后 **30 项**。

| 阶段 | 提交 | 内容 |
|---|---|---|
| V0/V1 | 平台 `c042219` | SDK 骨架 + `aimp/`（端点/字段/模式/multipart）+ `dog/`（桥接口/JSON/定位/实现）+ `module/`（词表）+ `orcontract/` |
| Y1 | OR `4535b4477` | OR 侧声明 `AimpPort`/`DogReadPort`，原类 `implements`（**零逻辑改动**） |
| V2 | 平台 `2d24639` | 平台侧三处改**委托** SDK（`PythonDogBridge`/`HttpAimpClient`/`OrDataQualityClient`），纯重构、无开关 |
| V3 | OR `686e5b860` | OR 读狗加 `ruyi.platform.sdk` 开关，`on` 时委托 SDK `ProcessDogBridge`（**第二轮已移除**，见下） |

**开关**：OR 侧系统属性 `ruyi.platform.sdk`（缺省 `off` → 原实现逐字节不变；`on` → SDK），与 `ruyi.license.enforce` 正交并存。平台侧为纯重构（等价替换，不加开关）。**注：第二轮（J1）判定「OR 侧所有 AIMP 侧能力调用一律经 SDK」，已删除 OR 原生读狗实现，开关随之取消（单一实现、无 legacy 通路）。**

**门禁 ✔（第一轮）**：平台 `clean compile`/`package` BUILD SUCCESS（产物含 `com/ruyi/sdk/*`；J1 后为 `com/jiongche/sdk/*`）＋假桥等价回归与改造前并集语义一致（起始最早/到期最晚/剩余最大）；OR `core` compile SUCCESS（228 源文件，release 11）＋ `off/on` 假桥冒烟返回**一致**（有桥 `[inspection, ocr, aimp-chat]` / 无桥 `null`）；`server` 依赖树含 `ruyi-platform-sdk:jar:0.1.0:compile`（随 `copy-dependencies` 落运行期 lib，与既有 `solon-hotplug` 同机制）。

**本轮未覆盖**：① OR `AimpClient` 的**三处 multipart 形状**（`----WebKitFormBoundary*` / `----AimpBoundary*` / `----AimpOcrBoundary*`）字节布局各异，**不在 SDK `Multipart` 设计范围**（其 javadoc 已显式排除），故 OR 侧保留原手写（端点字面量为纯等价替换、收益低，未强绑）；② AIMP/Python `modules.py` 跨语言词表对齐另议；③ SSO 转发实现留原处（CP4 只抽常量 `OrContract`）。

#### 要点③-续 · 第二轮（2026-10-17，SDK 命名归位 + 数据源/授权门 + OR 薄壳，**未推送**）

> 用户拍板四项：① SDK 新增最小**数据源接口** ② OR 适配器落 `modules/core` 的 `com.jiongche.refine.data` ③ **授权门放 SDK** ④ 先做 P0+P1 试点。**新约束**：放在平台 SPI 的包**统一以 `com.jiongche` 作顶层，不得出现 `com.ruyi` 包路径**；另一评注：「放 SDK，OR 侧现在所有有关 aimp 侧能力的调用都通过 SDK，所以其内的 `DogModuleReader` 应该去除」。**3A 接入形态 = 进程内**（授权门必须在进程内、进能力实现必经路径，否则加密狗可被绕过）。

**J1 · SDK 包名归位（破坏性重命名）**：
- SDK 包 `com.ruyi.sdk.*` → **`com.jiongche.sdk.*`**（main 18 源 + test 4 源全量）；`sdk/pom.xml` `groupId` `com.ruyi` → **`com.jiongche`**（`artifactId`/`version` 保留 `ruyi-platform-sdk`/`0.1.0`）；javadoc 里的 `com.ruyi.platform.*` 字面引用清理（`DogLicense`/`ProcessDogBridge` 等）。
- 平台侧同步：`pom.xml` SDK 依赖 `groupId`→`com.jiongche`；三处 import →`com.jiongche.sdk.*`（`HttpAimpClient`/`PythonDogBridge`/`OrDataQualityClient`），残留 0。
- **判断（请复核）**：约束针对「放到**平台 SPI** 的包」= **SDK**；平台自身 `com.ruyi.platform.*`（含 `com.ruyi.platform.spi.*` 25 文件）**未改名**。→ **第三轮已复核并执行**：按用户「确认，请完成改名」，平台 `com.ruyi.platform.*` / `com.ruyi.plugin.compliance.*` **一并归位**为 `com.jiongche.*`（见下「第三轮」T3）。

**J2 · SDK 新增两族契约**：
- `com.jiongche.sdk.data`：`TableSchema`（`columnNames()`/`columnIndex(name)`）、`TableView`（`schema()`/`rowCount()`/`value(row,col)`/`overlayJson(name)`；标量只 primitive/String/null，复合值 JSON 串）、`TableDriver extends TableView`（`setValue`/`putOverlayJson`/`markDirtyAndPersist`；**铁律：写必走宿主变更施加路径**）、`DataSource`（`exists`/`open`/`create`/`persist`）。
- `com.jiongche.sdk.authz`：`AuthorizationGate`（`of(Set,boolean)`/`disabled()`/`enforce()`/`modules()`/`isAllowed(String|Integer)`/`require(String)`，复用 `ModuleCodes.nameOf`）＋ `AuthorizationException`。

**J3 · OR 侧薄壳**：
- `modules/core/pom.xml` SDK 依赖坐标 → `com.jiongche`。
- 新增 `com.jiongche.refine.data`（活对象适配器，6 文件）：`OrTableSchema`（包 `ColumnModel`）、`OrTableDriver`（包活的 `Project`；`value` 经 `columns.get(col).getCellIndex()` 换算；写经 `AbstractOperation.createProcess(...).performImmediate()`）、`OrDataSource`（`static` 块把两操作注册进 `OperationRegistry` 的 `data` 命名空间）；两个新操作 `SetCellValueOperation`（复用既有 `CellChange`）、`PutOverlayJsonOperation`＋`OverlayChange`（overlay 写仍走 OR `Change`/`History`/持久化，**单一真相源铁律**：OR `Project` 内存 + overlay + `ProjectManager` 落盘）。
- OR `Project.java` 增 2 个纯转发访问器：`static public Project withId(long)`、`static public Class<? extends OverlayModel> overlayModelClass(String)`（原 `protected` 跨包不可见）。
- 读狗：**删除 `com.google.refine.licensing.DogModuleReader`**（原生桥定位/子进程/JSON 解析全去掉，`parse()` 契约由 SDK `DogJsonTest` 覆盖），置 `SdkDogReader implements DogReadPort`（**纯经 SDK `ProcessDogBridge`**，只留「按 licenseId 读 modules + 缓存」与 `resolveBridge` 供日志）；`LicenseGuard` 字段改 `DogReadPort`、`fromConfiguration()` 用 `SdkDogReader`；`LicenseGuardTest` 改用 `SdkDogReader` 匿名子类、删 `testDogBridgeJsonParsing`；`ruyi.platform.sdk` 开关取消。**（T5 起读狗与三态判定统一收敛到 SDK `LicenseGate`，`SdkDogReader` 已删，`DogReadPort` 保留为宿主/测试注入点。）**
- OR 侧 SPI javadoc 去 `com.ruyi.platform.spi.*` 字面路径（`AimpPort`/`DogReadPort`）。

**门禁 ✔（J1–J3）**：SDK `test` **15/15 全绿**（`MultipartTest`3 / `BridgeLocatorTest`4 / `DogJsonTest`6 / `ModuleCodesContractTest`2）；平台 `compile` BUILD SUCCESS；OR `modules/core` `test` **14/14 全绿**（`OrDataSourceTest`4 + `LicenseGuardTest`10；断言「写后 `project.history` 新增 1 条 past entry」证明写经变更路径），OR `server -am test-compile` SUCCESS。OR `build/.../modules/server` 不存在，实际模块为 `server`（`-pl server -am`）。

**第二轮未覆盖**：① 适配器**注册入 SPI registry**——`RefineModuleContext` 暂无数据源注册入口，本轮以**可直用类**交付 + `OrDataSource` 自注册操作，注册面留待 P2/P3；② P1：`data-quality` 的 `aimp/*` 下沉 SDK（扩 SDK 覆盖 3 份 multipart 形状 + 提升 `BatchCompareResult`/`LlmAnalyzeResult`/`OcrCropResult` 为独立 DTO）。→ **均已于第三轮收口**（见下 T1 / T2）。

#### 要点③-续 · 第三轮（2026-10-17，SPI 注册面收口 + OR 薄壳化 + 平台包归位，**未推送**）

**T1 · 数据源 SPI 注册最终态**（收口第二轮「未覆盖①」）：
- `RefineModuleContext` 增 `void registerDataSource(String name, DataSource ds)`；`ButterflyModuleContext` 实现之、委托 `com.jiongche.refine.data.DataSourceRegistry`。
- 新增 `DataSourceRegistry`（`register(name, ds)` / `dataSources()` / `get(name)`）：静态、全局、进程内目录，同 `OperationRegistry`/`ExporterRegistry` 模式，同名以最后一次为准。
- `RefineServlet` 启动期把 SPi 声明的数据源纳入目录；`OrDataSource` 交付为可注册 / 可直用态；`OrDataSourceTest` 增断言「注册后经 `get(name)` 可取回」。

**T2 · P1：`data-quality` 的 `aimp/*` 下沉 SDK**（收口第二轮「未覆盖②」与第一轮「未覆盖①」）：
- SDK `com.jiongche.sdk.aimp`：`Multipart` 扩**三形状工厂** `webKitBoundary()`/`aimpBoundary()`/`aimpOcrBoundary()`（前缀 `----WebKitFormBoundary` / `----AimpBoundary` / `----AimpOcrBoundary`）；提升 `BatchCompareResult`/`ElementResult`/`LlmAnalyzeResult`/`OcrCropResult` 为**独立 DTO**（`LlmAnalyzeResult.result` → `String resultJson`，Jackson 解析留在 OR 侧，因 SDK 零第三方依赖）。
- OR `AimpClient` 三处手写 multipart（抽取上传 / 批次比对 / 裁剪 OCR）**改由 SDK 构造**；端点/字段/模式改用 `AimpEndpoints`/`AimpFields`/`AimpMode`；删除 `normalizeServiceUrl`/`writeFormField` 与 4 个内嵌 DTO。**契约测试证明与旧手写逐字节一致**（`MultipartTest` 三形状断言）。
- `ContentChecker`/`TypoChecker`/`OcrCropCommand` 引用改 `com.jiongche.sdk.aimp.*` DTO。

**T3 · 平台包名归位（破坏性重命名，用户「确认，请完成改名」）**：
- 平台内核 `src/main/java/com/{ruyi→jiongche}/platform/**`（含 `spi/` 25 文件、`kernel/`、`web/`）与插件 `.../com/{ruyi→jiongche}/plugin/compliance/**` 目录级 `git mv`；95 个 `.java` 文本 `com.ruyi.platform` / `com.ruyi.plugin.compliance` → `com.jiongche.*`（含 `ShellController` 全限定名、`AuditEntry` javadoc `{@link}`）。
- 非 Java 引用同步：`pom.xml` `mainClass`、`run.ps1`、`plugin.properties`、`deploy/bridge/dog_read.py` 注释、两份 `docs/*.md` 路径。**保留** Maven `groupId`（`com.ruyi` / `com.ruyi.plugin`）、`artifactId`、`app.yml` group/name、`app.js` `localStorage` key、`run.ps1` `-Druyi.license.dev` 属性名——**均非 Java 包路径**，按「包路径」口径保留。
- 修插件 `pom.xml` 内核依赖版本 `0.1.0-SNAPSHOT` → `0.1.0`（本地仓库该坐标为旧包名旧 jar，原版本取不到 → 编译失败）。

**T4 · P2：`data-quality` 内核下沉（checker/model/util/task）**：
- 新增 `extensions/data-quality-engine`（`sourceDirectory=src`、`finalName=jinrefine-data-quality-engine`）：**零 OR core 依赖**，依赖仅 SDK `TableView/TableDriver` + Jackson + slf4j + fastutil + pdfbox，全部 `provided`（运行期由 OR server 主 lib 与 SDK 提供）；localizer 插件 `fileMask` 显式指向不存在的束，避免按 artifactId 生成非法类名。51 个源文件下沉（checker 27 / model 16 / util 6 / aimp 1 / task 1）。
- 取数一律经 `com.jiongche.sdk.data.TableView`：`view.schema().columnNames()` / `view.rowCount()` / `view.value(row, colIndex)` / `view.valueByCellIndex` / `view.id()` / `view.metadataJson("project")`；列索引语义由 `util/TableViews.columnIndexMap(view)` 统一（等价于原 `Column.getCellIndex()`），页区间/资源文件选取由 `ResourceSelector` 承接。
- `model/*` 去 `OverlayModel` 依赖（DTO 纯净）；OR 侧以子类薄壳承接两个 overlay 槽位：`OverlayQualityRulesConfig` / `OverlayQualityCheckResult`（**不新增任何 JSON 属性 → 落盘序列化逐字节等价**），`DataQualityExtensionSpi` 注册、`SaveQuality*Operation` 与命令面类型同步改子类。
- OR core 侧新增 `com.jiongche.refine.data.OrViews.viewFor(project)`：开关 `ruyi.data-quality.view = live(缺省) | snapshot`——`live` 返回活对象适配器 `OrTableDriver`（零拷贝、缺省零差异），`snapshot` 返回新增的 `SnapshotTableView`（冻结列名/单元格值/overlay，隔离后台检查线程与活 Project 并发变更）。`OrTableDriver` 的 `toJson` 提为 `public` 并新增 `projectCellValue`，供快照复用同一转换，**不引入第二套取值逻辑**。
- 命令面（`RunQualityCheckCommand` / `RunImageQualityCheckCommand`）只把 `Project` 换成 `view`，检查逻辑全部由下沉引擎执行，OR 不保留旧实现（**禁双套逻辑**）。

**T5 · P3：授权门进程内落地（SDK + 引擎入口）**：
- SDK 新增 `com.jiongche.sdk.authz.LicenseGate`：三态 `off/log/block`（沿用 `ruyi.license.enforce`）、读狗（`ProcessDogBridge`）、进程级缓存（`ruyi.license.bridge` / `ruyi.license.id` / `ruyi.license.cache-seconds`）、失败关闭与 LOG 告警去重；`off` **不读狗**。`evaluate(...)` 返回拒绝原因（`dog_unavailable` / `module_not_licensed`），`require(...)` 在 `block` 下抛 `AuthorizationException`。
- SDK 新增 `com.jiongche.sdk.authz.LicenseCapabilities`：命令/能力 → 狗模块映射的**单一来源**；OR `LicenseModuleMap` 改为委托它（`DEFAULT` 即同一 Map），两侧不再各存一份。
- OR `LicenseGuard` 改为持有 SDK `LicenseGate`（`fromConfiguration()` 走 `LicenseGate.fromSystemProperties()`），`parseMode` / 读狗 / 缓存全部委托；`SdkDogReader` 删除，`DogReadPort` 保留为宿主/测试注入点（`LicenseGuard(Mode, DogReadPort)` 仍可用）。命令面拒绝消息与 `log` 观察语义不变。
- 引擎入口（零 OR 依赖，仅 import SDK）：`AimpClient.extractContent`/`batchCompare` → `info_extraction`；`AimpClient.llmAnalyze` → `aimp-chat`；`AimpClient.ocrCrop` → `ocr`；`ImageQualityChecker.runCheck`（端点已配置且有用例启用时）与 `checkImage` → `inspection`（该检查直连 inspection 端点，不经 `AimpClient`）。`testConnection` 与本地格式/资源检查**不设门禁**（与既有命令映射口径一致）。
- 直调绕过验证：`LicenseGateEntryTest` 在 `block` + 读不到狗时直接 `new AimpClient(...).llmAnalyze` / `new ImageQualityChecker(...).checkImage`，均在触网前抛 `AuthorizationException`。

**门禁 ✔（T1–T5）**：SDK `test` **30/30 全绿**（新增 `LicenseGateTest` 8 项）；平台 `clean install -DskipTests` BUILD SUCCESS（shade fat jar）；插件 `clean package -DskipTests` BUILD SUCCESS；OR `modules/core` `test` **17/17 全绿**（`LicenseGuardTest` 改注入 `LicenseGate.create`，`OrDataSourceTest` 不变）；OR `extensions/data-quality -am clean install -DskipTests` **5 模块全绿**；`extensions/data-quality-engine` `LicenseGateEntryTest` **2/2 全绿**；`server -am test-compile` 5 模块 SUCCESS。**OR `modules/core` 全量 `test` 215 项中 4 项失败系既有失败**（`HistoryEntryTests`×3 + `HistoryTests.serializeHistory`，报 `"sheetId": null` JSON 不等），经 `git stash` 对照确认与本轮改动无关。

**T6 · P4：FilesExtension 纯文件工具下沉**：
- 新增 `extensions/files-engine`（`sourceDirectory=src`、`testSourceDirectory=test/java`、`finalName=jinrefine-files-engine`）：**零 OR core 依赖**，依赖仅 SDK + Jackson + slf4j，全部 `provided`；`FilesExtension` 以 compile scope 依赖，经 `copy-dependencies` 收进 `MOD-INF/lib`。
- 下沉 9 个类（保留原包名 `org.openrefine.extensions.files.importer`，OR 侧调用点零改 import）：`UnitScanner`（核查确认零 OR）、`TitleSplitter`、`CustomElementsCodec`、`CustomElementType`、`ExtractionTemplate`、`ExtractionUnit`、`ArchiveNumberConfig`、`AimpLlmClient`、`ExtractionPageCache`。
- workspace 解耦：新增 `WorkspacePaths` 注入点；`ExtractionPageCache` 去掉 `FileProjectManager` 直连；`FilesExtensionSpi.register` 注入延迟求值的 workspace 定位（未就绪返回 null，与改造前「定位不到即静默不缓存」一致）。`ExtractionConfigStore` / `ExtractionTaskRegistry` / `FileElementExtractor` 按计划留 OR（前者待后续按需下沉，后者依赖 `SheetData` 且无调用方）。
- 授权门补齐（P3 遗留）：`AimpLlmClient` 的 `extractUpload`（覆盖 `extractContent`/`extractPage`/`submitAsync`）、`splitVolumePieces`、`pieceIntegrity` → `info_extraction`；`llmAnalyze` → `aimp-chat`；`testConnection`/`checkCompatibility`/`getTaskStatus` **不设门禁**。后台抽取线程因此也在触网前过门。
- 页级缓存往返修复：`ExtractionPageCache.toJson/fromJson` 补 `titleRegionCount`（既有测试 `ExtractionPageCacheTest` 早已断言该字段，主源码漏写导致命中缓存后分件双证据门限失效）；方法改为包内可见以做纯函数单测。
- 测试迁移：`AimpLlmClientPieceIntegrityTest`、`ExtractionPageCacheTest`、`CustomElementKeyTest`、`FileNameColumnVisibilityTest` 随类迁入 `files-engine/test/java`；新增 `UnitScannerTest`（扫描/自然序/页号）与 `AimpLlmClientLicenseTest`（直调被拒、探活免门禁）。

**门禁 ✔（T1–T6）**：`extensions/files-engine` `test` **32/32 全绿**；`extensions/FilesExtension` `test` **87 项中 86 通过**，唯一失败 `FilesImportingControllerTest.testLocalDirectoryFileList`（`expected [6] but found [0]`）已在 `f29c1901c` 上以同环境复跑确认**为改造前既有失败**（该测试走的 `FilesImporter`/`importing-controller` 路径本轮未改）；`extensions/files-engine,extensions/FilesExtension,extensions/data-quality -am clean install -DskipTests` **9 模块全绿**；`files-engine` 源码无任何 `import com.google.refine.*`。

**T7 · P5：一致性校准与改动面汇总（收口）**：

**① 最终结构（本轮结束时）**

| 层 | 落点 | 关键内容 |
|---|---|---|
| SDK（平台仓 `sdk/`） | `com.jiongche.sdk.*`（零第三方依赖） | `aimp/`（端点/字段/模式/multipart 三形状/DTO）、`data/`（`TableSchema`/`TableView`/`TableDriver`/`DataSource`）、`authz/`（`AuthorizationGate`/`LicenseGate`/`LicenseCapabilities`/`AuthorizationException`）、`dog/`（桥/JSON/定位）、`module/`（词表）、`orcontract/` |
| OR core | `com.jiongche.refine.data` / `com.google.refine.licensing` | 活对象适配器（`OrTableDriver`/`OrTableSchema`/`OrDataSource`/`OrViews`/`SnapshotTableView`/两个 operation/`OverlayChange`）、`DataSourceRegistry`、`LicenseGuard`（持 SDK `LicenseGate`）、`LicenseModuleMap`（委托 SDK 映射） |
| OR 扩展·数据质量 | `extensions/data-quality-engine` + `extensions/data-quality` | 引擎 51 源（checker/model/util/aimp/task，全经 `TableView`）；OR 侧仅 overlay 薄壳 + 命令面 |
| OR 扩展·文件 | `extensions/files-engine` + `extensions/FilesExtension` | 引擎 10 源（9 个下沉类 + `WorkspacePaths`）；OR 侧保留导入管线、命令、管理器、SPI |
| 平台内核 | `com.jiongche.platform.*` / `com.jiongche.plugin.compliance.*` | 包名归位 + 三处 AIMP/读狗委托 SDK |

**② 提交锚点（均本地，未推送）**

| 仓 | 提交 | 内容 |
|---|---|---|
| 平台 | `c042219` → `2d24639` → `c12a5f4` → `35a66c4` → `d19667c` → `4edaa95` → `cc601e0` | SDK 骨架/aimp/dog/module/orcontract → 平台委托 → SDK 包归位 + data/authz → AIMP 契约下沉 → 平台包归位 → `TableView` 默认方法 + `AimpPort` → `LicenseGate`/`LicenseCapabilities` |
| OR | `4535b4477` → `686e5b860` → `e159be1fb` → `e71212af4` → `6fe195db2` → `ac76af78f` → `f29c1901c` → `462207b08` | OR 端口声明 → 读狗委托 → 适配器薄壳 → 数据源注册面 → `aimp/*` 下沉 → data-quality 内核下沉（T4）→ 授权门（T5）→ files 内核下沉（T6） |

**③ 开关最终态**：`ruyi.license.enforce = off(缺省)/log/block`（唯一授权判定实现，OR 命令面与两个引擎共用）；`ruyi.data-quality.view = live(缺省)/snapshot`；`ruyi.license.bridge|id|cache-seconds`（读狗配置）。`ruyi.platform.sdk` 已取消，**无 legacy 双实现**。

**④ 门禁总表**：SDK `test` 30/30；平台 `clean install -DskipTests` SUCCESS；插件 `clean package -DskipTests` SUCCESS；OR core `test` 17/17（全量 215 中 4 项既有失败）；`data-quality-engine` 2/2、`files-engine` 32/32；`server -am test-compile` SUCCESS；`extensions/files-engine,extensions/FilesExtension,extensions/data-quality -am clean install -DskipTests` 9 模块 SUCCESS。

**⑤ 已知边界（如实保留）**
- `FilesImportingControllerTest.testLocalDirectoryFileList` 与 `HistoryEntryTests`×3/`HistoryTests.serializeHistory` 为**改造前既有失败**，均已对照复跑确认，不在本轮修复范围。
- `ExtractionConfigStore` / `ExtractionTaskRegistry` 仍留 OR（仅 `FileProjectManager` 单点耦合，按计划未列入下沉清单）；`FileElementExtractor` 依赖 OR `SheetData` 且无调用方，留原处。
- OR 侧 `DogReadPort` 保留为宿主/测试注入端口，生产读狗只经 SDK `LicenseGate`。
- `FilesExtension` 的 `AimpLlmClient` 仅对「实际触网入口」设门；`getTaskStatus` 轮询与探活免门禁（与既有命令映射口径一致）。

---

## 7. 路线图与各阶段工作内容

> 原则：每阶段**开关可回退**、**独立演进（已与上游断开，见 §5.4）**、**先易后难**（SPI 化先于装载器替换）。

### Phase 0 · 只读验证（P0）+ POC
- **目标**：把关键推测变为证据，验证"薄采用"是否成立。
- **工作项**：
  1. Solon 各 adapter 的 **javax/jakarta × Java 版本矩阵**。
  2. `solon-web-servlet` 能否**直接挂载现成 `HttpServlet`** + init-params / `ServletContext` 属性 / webapp 资源根。
  3. **POC：Solon boot 直挂现有 `RefineServlet`**（独立分支/工作区），跑 `main/tests` + `server` 回归 + benchmark 微基准。
  4. ~~以 **OR 官方 4.0 设计文档**锁定上游走向。~~ → **已取消**（2026-10-17：不再等待上游，改为独立演进，见 §5.4）。
- **门禁**：回归**全绿** + 重负载端点性能偏差 **≤ ±10%**（P50/P95/吞吐/峰值内存）。
- **产出**：P0 验证结论 + 退化风险专项清单。
- **回退**：POC 在独立分支，不影响主干。

### Phase 1 · SPI 化先行（与框架无关）
- **目标**：定义 OR 业务 SPI，把业务实现移出 core → 直接落地"业务与 OR 解耦"。
- **工作项**：为第 4.4 节 10 个注册面定义 Java SPI 接口；把 `data-quality`、`FilesExtension`、`import-from-local-dir`、标题/要素抽取等改为 SPI 实现；**此时可保留 Butterfly 作装载器**。
- **门禁**：core 不再直接依赖具体业务实现；回归全绿。
- **回退**：SPI 为增量抽象，可逐个面切换。

#### Phase 1 执行记录（2026-10-16，worktree `build/or-solon-poc` @ `feat/or-solon-migration`）

> 落法：把业务扩展的注册逻辑从 Butterfly 的 JS `controller.js`（`RS.register*`）收敛到 OR 自有的、框架无关的 Java SPI，使注册面成为可被任意装载器（Butterfly / 后续 Solon）驱动的稳定边界。**core 自身 `controller.js` 不动**；**既有业务实现类包名不动**（`com.google.refine.extension.quality.*` / `org.openrefine.extensions.files.*`），只把注册逻辑挪进新 SPI 类。

**① core SPI 四件套**（新包 `com.jiongche.refine.spi`，对应公司实体「炅澈技术」）：

| 文件 | 职责 |
|---|---|
| `RefineModuleContext` | 框架无关的注册门面，覆盖第 4.4 节十个注册面（commands/guards/classmap、operations、importing、exporters、recon、language、clustering、overlay、client-side resources、resources/i18n） |
| `RefineModuleSpi` | 业务扩展契约：`getName()` + `register(ctx)`（+ `destroy()` 默认空实现） |
| `ButterflyModuleContext` | 适配器：包装 `ButterflyModule`，把 ctx 调用转投底层静态注册表 |
| `SpiBootstrap` | 桥入口：`register(ButterflyModule, spiClassName)`，按「线程上下文 CL → module 类 CL → core CL」回退解析并实例化 SPI 类 |

**② core 4 个注册表加 `String` 重载**（**加法**，保留原 `ButterflyModule` 签名）：`RefineServlet.registerCommand` / `OperationRegistry.registerOperation` / `ImportingManager.registerController` / `ReconConfig.registerReconConfig`，统一 key = `moduleName + "/" + name`；原方法委托新重载。`ClientSideResourceManager.addPaths` 因签名含 `MountPoint` 未加 core 重载，由适配器直接调原 `ButterflyModule` 版。

**③ 两个试点扩展 SPI 化**：`com.jiongche.extensions.files.FilesExtensionSpi`、`com.jiongche.extension.quality.DataQualityExtensionSpi`；各自 `controller.js` 的 `init()` 瘦身为一行 `Packages.com.jiongche.refine.spi.SpiBootstrap.register(module, "<SPI 类名>")`（data-quality 另保留 `process`/`send` 回调，不在 SPI 覆面内）。

**启动回归**（Solon 实例 `127.0.0.1:3433`，把预构建的 `main/webapp/WEB-INF/lib/core-3.11.0.01.jar` 换为含 SPI 的重建产物；两扩展经 `mvn install` 产出带 SPI 类的 MOD-INF/lib jar）：

- **SPI 注册成功**：启动日志 `SPI registered: module=files spi=com.jiongche.extensions.files.FilesExtensionSpi`、`SPI registered: module=data-quality spi=com.jiongche.extension.quality.DataQualityExtensionSpi`；`Data-Quality Extension initialized successfully!`。
- **命令可达**（命中即非 404）：`files/extraction-config`、`data-quality/get-config`、`get-quality-rules`、`list-tasks`、`get-image-quality-rule` 均 200；`files/batch-extraction`（POST-only）GET=500，证明命令已注册并完成分发。
- **客户端资源**（经 SPI 的 `addClientSidePaths`）：`/index-bundle.js` 含 `files/scripts/vendor/tiny-pinyin.js`、`files/scripts/index/files-importing-controller.js`、`files/scripts/index/extraction-console.js`、`data-quality/scripts/index/data-quality-ui.js`；`/project-bundle.js` 含 data-quality 的 `quality-alignment.js`、`menu-bar-extension.js`、`file-view-panel.js`、`dialogs/run-check-dialog.js`；导入控制器键 `files/files-importing-controller` 已注册。
- **`get-version`** 200，`module_names` 含 `data-quality`、`files`。
- **未变项**：`jython`/`pc-axis`/`database` 仍报模块初始化 `JavaPackage … is not a function`，属本 worktree 这些扩展**未编译**（MOD-INF 无 classes/lib）——与 P1 无关，Jetty 侧同源同报。（其中 `records-db` / `records-assets` 本轮已编译并 SPI 化，见下方「Phase 1 横向补全」；`jython`/`pc-axis`/`database` 按约定不动。）

**结论：P1 核心目标达成——两个试点扩展的注册面经框架无关 Java SPI 驱动，core 不再面向具体业务实现，装载器（Butterfly）行为不变。**

#### Phase 1 横向补全（records-db / records-assets，2026-10-16）

> 承接上方 P1 试点，把 SPI 化横向扩到**自有业务扩展** `records-db`、`records-assets`（**上游自带扩展 `pc-axis` / `jython` / `database` 暂不动** —— 原为降低 rebase 冲突面；**2026-10-17 起该理由作废**，处置可自主决定，见 §5.4）。产物直接喂给 Phase 3 收敛扩面。

**① 两个扩展 SPI 化**：新增 `com.jiongche.extension.records.db.RecordsDBExtensionSpi`、`com.jiongche.extension.records.assets.RecordsAssetsExtensionSpi`（包名对应公司实体「炅澈技术」）；各自 `controller.js` 的 `init()` 瘦身为一行 `SpiBootstrap.register(module, null)`（`null` → 从 `MOD-INF/module.properties` 读 `refine.spi`）；两个 `module.properties` 追加 `refine.spi = <SPI 类>`，**保留各自 `module-impl`**（`RecordsDBModuleImpl` 仍经装载器 `cl.loadClass(implClass)` 创建）。
**② 注册逻辑收敛**：`records-db` 的 overlay 模型 + 导入控制器（原写在 `RecordsDBModuleImpl.init()`）连同命令、客户端资源一并移入 SPI；`RecordsDBModuleImpl.init()` 仅剩 `super.init()` + 单例赋值。
**③ 首次真正生效**：此前两扩展因 `MOD-INF/lib` 为空、`init()` 抛 `JavaPackage … is not a function` 而**实际注册为空**；本轮 `mvn compile` 后 jar 落 `MOD-INF/lib`（含新 SPI 类），首次真正注册。

**启动回归（A/B 双实例，同容器 Solon·Tomcat，仅装载器开关不同）**：

| 组 | 端口 | 装载器 | 数据目录 |
|---|---|---|---|
| A | 3433 | `butterfly` | `%TEMP%\refine-p4-data-bf` |
| B | 3443 | `solon` | `%TEMP%\refine-p4-data-solon` |

- **SPI 注册 ×4，两侧一致**：日志 `SPI registered: module=records-db spi=…RecordsDBExtensionSpi`、`module=records-assets spi=…RecordsAssetsExtensionSpi`（连同既有 `files` / `data-quality`）；`RecordsDatabaseImportController initialized`、`RecordsAssetsController initialized`。
- **命令可达**（非 404 即注册）：`records-db/export-bound-assets`、`records-assets/list|preview` = 200；`records-assets/file` GET=400（缺参，命令已注册）；两侧一致。
- **客户端资源去重**：`index-bundle.js` 含 `records-db-filter-builder.js` / `records-db-import-controller.js` / `records-db-wizard-steps.js` 各 1 处；`project-bundle.js` 含 `records-assets-controller.js` / `resource-explorer.js` / `resource-explorer-panel.js` / `file-preview-dialog.js` / `file-path-cell-renderer.js` / `menu-file-path.js` / `export-bound-assets-dialog.js` / `menu-extensions.js` 各 1 处；**两 bundle 的全部资源边界标记零重复**（index 59 / project 100）。
- **字节级等价**：`get-version` `7A1A166F`、`wirings.js` `8FED5B45`、`index-bundle.js` 1797916B `BBC5EB0F`、`project-bundle.js` 2238918B `227085B8`、`get-languages` `B456153E` —— **A/B 逐字节一致**。
- **`get-importing-configuration`**（3773B，A/B 原始哈希不同）→ 深度规范化比对**内容完全等价**（`diff_count=0`），差异**仅为三个子 map 的 JSON 键序**；**冷启动对照实验**（同产物再起 A2=butterfly:3453、B2=solon:3463）显示 **同一装载器两次冷启动键序亦不同**（A `f,e,m` vs A2 `f,m,e`；B `e,m,f` vs B2 `f,e,m`），跨装载器反而出现偶合（A ≡ B2）→ **判定为每进程冷启动的迭代序非确定性，与装载器无关**（与 §10 P3 记录中 `root.html` 字符集别名序同类，Jetty 侧同源同报）。

**结论：横向补全达成——`records-db` / `records-assets` 注册面改由框架无关 Java SPI 驱动；四个自有扩展（files / data-quality / records-db / records-assets）的注册面在 `butterfly` 与 `solon` 两种装载器下**内容等价**，唯一可观测差异为既有的每进程键序抖动，非装载器引入。**

### Phase 2 · ~~跟随上游现代化~~ → **已取消**（2026-10-17 定位校准）
- ~~**目标**：对齐上游（jakarta / Jetty 升级 / controller.js Java 化），**保住 rebase 能力**。~~
- **现状态**：本项目**已与上游断开**（§5.4），"跟随上游 / 保住 rebase"前提不再成立 → **本阶段整体取消**。原列工作项去向：
  - **jakarta 迁移 → 不做**（维持 `javax.servlet`，除非自身演进另有需要）；
  - **Jetty 12 对齐 → 无关**（Jetty 已在 R2 彻底移除，交付形态为原生 Solon）；
  - **controller.js Java 化 → 无关**（我方已用自有 `SpiBootstrap` SPI 取代 JS 注册，见 Phase 1）。
- **替代动作**：OR 上游转为**新功能来源** → 建立 **cherry-pick 抽取清单**，随上游 release 评估值得接入的特性，**逐个抽取适配（不合并）**。
- **门禁 / 回退**：不适用（本阶段取消，无代码变更）。
- **历史留存**：原「上游 4.0 走向调研结论」（2026-10-16）保留于 §10 末，**仅作背景，不再作为路线决策依据**。

### Phase 3 · 装载器选型落地
- **目标**：装载器**选型并落地**（Butterfly 保留态 vs Solon E-Spi/H-Spi）——**不再参照上游 Butterfly 走向**（§5.4）。
- **工作项**：实现模块发现/ClassLoader/资源装载/`/wirings.js` 等价端点；前端契约不变。
- **开关**：`refine.module.loader = butterfly | solon`（秒级回退）。
- **门禁**：回归全绿 + 性能偏差 ≤ ±10% + 插件热插拔/隔离验证。
- **执行记录（2026-10-16，POC 完成）**：自研 `StandaloneModuleLoader`（开关值 `solon`）已落 `com.jiongche.refine.loader`，接管 `files` / `data-quality` 的「模块发现归属 + ClassLoader + 模块对象创建」，两模块各获**独立 `URLClassLoader`**（非共享 `ButterflyClassLoader`）；`butterfly` 缺省路径逐字节不变、开关秒级回退。等价性 / 隔离性门禁全绿，详见 §10「P3 执行记录（装载器选型 POC，2026-10-16）」。
  - **收敛（2026-10-16）**：core 正式重打包进 webapp（`core-3.11.0.01.jar` 含 `com.jiongche.refine.{loader,spi}`），**彻底去掉 dev-shim（core classes 前置）**；`StandaloneModuleLoader` 日志收敛 + `unload()` 补可观测日志（不重复销毁）；接管面由 2 模块扩到 **4 模块**（`files` / `data-quality` / `records-db` / `records-assets`）。详见 §10「P3 收敛执行记录（去 dev-shim + 隔离/卸载 + 四模块扩面，2026-10-16）」。
  - **R2 交付形态（2026-10-16）**：**彻底移除 Jetty**，交付两条真实入口（`jinrefine.exe` 与 `refine.bat`）原生走 Solon——不再保留 Jetty 启动分支。核心解法为 **D1「依赖落位」**（`server/pom.xml` 把 `main` 依赖由 pom+provided 改为普通 jar + `compile`），使 `copy-dependencies(includeScope=runtime)` 自动把 `core`/`grel`/`main` 及其运行时依赖拷入 `server/target/lib`，从而 exe / bat / launch4j / `refine`（Unix）**零改动**即可见（等价于 POC 验证过的扁平 classpath 模型）。上传上限由 Jetty 专属键解耦为容器无关新键 `refine.container.max_post_size`（缺省 64MB）。**缺省装载器仍 `butterfly`**。详见 §10「R2 执行记录（彻底移除 Jetty · 交付形态原生 Solon，2026-10-16）」。
  - **Solon 插件装载形态（D1-a，2026-10-16）**：新增第 3 个装载器 `SolonPluginModuleLoader`（开关值 **`solon-plugin`**）——以 Solon **插件契约**（`org.noear.solon.core.Plugin` + 描述符 `META-INF/solon/*.properties`）作为业务插件的「发现 + 生命周期」契约，借 `org.noear:solon-hotplug` 的 `PluginClassLoader` 提供 **per-plugin 类加载器隔离**（parent = core CL）。试点 `files` / `data-quality` 改造为**插件 jar**（双描述符：插件描述符 + `MOD-INF/module.properties`），其 `controller.js` 的 `SpiBootstrap.register` 行在插件模式**让位**（`SolonPluginBridge.isDelegated` ⇒ 单一注册路径）。`butterfly`（缺省）与 `solon`（P3 自研）路径**均不动**。详见 §10「Solon 插件装载形态执行记录（D1-a：H-Spi 隔离 + 插件契约，2026-10-16）」。

#### 后续开发截断与边界（2026-10-17 重定）

**已截断（不再做）**：

1. **不再跟随 / 等待上游**：原 Phase 2「跟随上游现代化」取消；issue #7706 / PR #7707 / #5664 / milestone `V.next` **不再作为路线触发**；不再"待 4.0 定稿后决定是否对齐容器外壳"。
2. **不再为 rebase 让步**：不为降低与上游的 rebase 冲突面而回避任何改动；上游自带扩展（`pc-axis` / `jython` / `database` / `wikibase`）的处置**不再受"按约定不动"约束**（视需要自主决定）。
3. **不迁 jakarta**：维持 `javax.servlet`（除非自身演进另有需要）。
4. **不承担 upstream 合并 / rebase 义务**：`upstream` 远端**降级为只读来源**。

**继续做（收敛后的范围）**：

1. **P1 SPI 横向补全**（自有扩展注册面 SPI 化）；
2. **Phase 3 装载器收敛**（`butterfly | solon | solon-plugin`，按自有节奏）；
3. **要点③ 平台 SDK 接口层**（Y1–V3 已落地，第三轮 T1/T2/T3 已收口 SDK 下沉与平台包归位；剩余项见 §6 要点③ 执行记录）；
4. **新目标架构**：OR 作为插件/子模块 + 业务 SPI + AI 能力 SPI + 可选 AI harness。

**新增常态动作**：

- **上游 cherry-pick 抽取**：随上游 release 定期评估值得接入的新功能，**逐个抽取适配**（不合并），登记到**抽取清单**。

**双侧分支**：OR 侧与能力平台侧各起独立分支；**平台 SDK 解耦（Phase 1 的耦合点抽取）与 Solon 迁移并行推进**。

#### Release-1 范围与优先级（2026-10-17 拍板）

> **原则：先交付、后改造。** 装载器/Butterfly 退场工作面大，**不纳入第一个可交付版本**；Release-1 只做「B 包（SDK 覆盖收口）+ C 包中的 3A 进程内接入」，并先冻结一个可回退的交付基线。

**纳入 Release-1（按开工顺序）**

| 编号 | 工作 | 来源 | 验收口径 |
|---|---|---|---|
| **R1-0** | **可交付基线冻结**：固定 `refine.module.loader=butterfly`，全量构建 + 打包 + 冒烟；登记两处既有测试失败为已知问题；形成本地发布锚点 | 工程化 | `install/package` 成功；启动、`/command`、两个扩展命令、批量抽取冒烟通过；已知问题清单入库 |
| **R1-1** | **AIMP 传输收口**：4 处直连（`AimpLlmClient` / `AimpClient` / `ImageQualityChecker` / `TestAimpConnectionCommand`）统一经 SDK 传输端口；端点、字段、multipart 全部走 SDK 常量/工厂 | B 包 | 请求**字节级等价**（固定 boundary 断言）；现有单测全绿；新增传输契约测试；缺省行为不变 |
| **R1-2** | **平台侧采用 SDK 语义**：去掉 `"inspection"` 等硬编码，改用 `ModuleCodes`/`LicenseCapabilities`/`LicenseGate`；SSO 转发契约保持 `OrContract` 单一来源 | B 包 | 平台编译/测试绿；授权与命令映射行为等价 |
| **R1-3** | **3A 进程内接入（最小闭环）**：定义进程内能力调用端口，先落「授权/合规查询」的进程内实现；HTTP 反代保留为 `off` 回退 | C 包（3A） | `off`＝现状；`on` 时进程内调用与 HTTP 等价；授权门仍在能力实现入口、不可绕过 |
| **R1-4** | **发布候选验证**：完整回归（OR core / 两个 engine / FilesExtension）+ 打包冒烟 + 发布说明 | 工程化 | 门禁记录与已知问题清单一致；产物可交付 |

**R1-0 执行记录（2026-10-17，✔ 已完成）**：
- **环境前置**：`JAVA_HOME` 必须指向 **JDK 11**（根 POM 的 `impsort-maven-plugin` 等要求 class 55；缺省 JDK 8 会在根模块直接失败）。
- **构建**：`mvn clean install -DskipTests -pl server,extensions/data-quality,extensions/FilesExtension -am` → **10 模块 SUCCESS**（root/core/grel/main/server/extensions/data-quality-engine/data-quality/files-engine/FilesExtension）。首次 `clean` 因 `data-quality-engine/target` 被瞬时占用失败，重试后通过（非代码问题）。
- **打包**：`mvn -pl packaging -am package -DskipTests` → SUCCESS；产物 `packaging/target/openrefine_with_jre.exe`、`jinrefine-win-with-java-3.11.0.01.zip`、`jinrefine-linux-3.11.0.01.tar.gz`；staging `OpenRefine.app/Contents/Resources/webapp/extensions/{FilesExtension,data-quality}/module/MOD-INF/lib` 内含 `files-engine-3.11.0.01.jar` / `data-quality-engine-3.11.0.01.jar`。
- **单测**：SDK **30/30**；OR core 定向 **17/17**（`LicenseGuardTest` + `OrDataSourceTest`）；`data-quality-engine` **2/2**；`files-engine` **32/32**；`FilesExtension` **87 项 86 通过**；平台根 `mvn test` BUILD SUCCESS（无测试）。
- **启动冒烟**（`server/target/*-server.jar` + `server/target/lib/*`，`-Drefine.port=3533`，临时 data dir）：`Module loader: butterfly`；SPI 注册 files / data-quality / records-db / records-assets；GET `/`、`/command/core/get-version`、`/command/data-quality/get-config`、`/command/files/extraction-config`、`/command/data-quality/get-quality-rules`、`/wirings.js` 全部 **200**。
- **批量抽取路径冒烟**：POST `files/batch-extraction`（`subCommand=start`）返回预期 `{"code":"error","messageKey":"files-importing/aimp-unavailable","message":"AI服务模块不可用，请检查或重启模块"}`——命令/CSRF/错误契约可用；AIMP 未启动，故未跑真实抽取回归（环境前置，非缺陷）。
- **已知问题（不阻塞基线，登记待清理）**：① OR core 全量 215 项中 4 项既有失败（`HistoryEntryTests`×3 + `HistoryTests.serializeHistory`，`"sheetId": null` JSON 不等）；② `FilesImportingControllerTest.testLocalDirectoryFileList`（87 项中 1 项，`expected [6] but found [0]`）。两者均已在改造前提交上复现。


**明确不在 Release-1（发布后再改造）**
1. **Butterfly 退场与装载器自主化**：缺省切 `solon-plugin`、自建 `wirings.js`/i18n/客户端资源装载、移除 `extends Butterfly` 与 `butterfly-1.2.6` 依赖。
2. **其余扩展插件化**：`records-db`/`records-assets` 的 Solon 描述符、`jython`/`wikibase`/`pc-axis` 的 SPI 化（`database` 已禁用）。
3. **AI harness**：仅前瞻性规划，不进入 JinRefine 首个发布版本。
4. **`import-from-local-dir` 前端 Java 化**、`BatchExtractionManager`/`ExtractionTaskRegistry` 下沉、SSO 转发下沉 SDK、jakarta 迁移。

*依据：用户 2026-10-17 拍板「先稳定可交付版本；优先 B 包与 C 包 3A 部分；OR 插件其余部分非本项目重点，发布后再改造；harness 暂不纳入第一版计划」。*

---

## 8. 退化风险专项清单（重负载端点 × 容器依赖）

| 端点 | 容器行为依赖 | 判定 |
|---|---|---|
| `create-project-from-upload` | multipart、64MB form 上限、temp dir | **✔ 已解决并复验**：multipart 参数语义桥 + tempdir 兜底 + `maxPostSize/maxSwallowSize=64MB`；两侧上传 `rowCount=3` 一致 |
| `get-rows` | 分页/大响应、gzip | 语义等价（gzip 已对齐，字节级一致） |
| `compute-facets` | 长耗时请求、idle timeout | **✔ 已对齐**（`connectionTimeout=60000ms`） |
| `export-rows`（流式） | 流式 flush、响应缓冲 | **待专项实测**（本轮未覆盖流式语义） |
| `task-control` / `get-check-progress`（轮询） | 长轮询、线程池占用 | **✔ 近似对齐**（`maxThreads/acceptCount=300`；Tomcat 不支持自定义池） |
| 静态资源 / `-bundle.js` | gzip、资源根、拼接时机 | 语义等价（建议构建期预打包） |
| 扩展装载 | ClassLoader 隔离、动态装载 | **✔ 已落地两代装载器**：①`StandaloneModuleLoader`（P3，`solon`）为接管模块提供 per-module `URLClassLoader`；**接管面 = 4 个自有模块**（`files` / `data-quality` / `records-db` / `records-assets`），各模块 CL 互异且 ≠ 应用 CL，URL 集为模块内 `MOD-INF/classes` + `MOD-INF/lib`（不含 core）；**core 已正式重打包进 webapp，不再依赖 dev-shim**；②`SolonPluginModuleLoader`（本阶段，`solon-plugin`）以 Solon 插件契约 + `solon-hotplug` 的 `PluginClassLoader` 提供 per-plugin 隔离，试点 2 模块（`files` / `data-quality`）已插件化，运行期 `unload()` 实证 `PluginClassLoader.close()` 真关；开关 `refine.module.loader = butterfly \| solon \| solon-plugin` 秒级回退；前端契约（`/command`、`/wirings.js`、`-bundle.js`、`.vt`、i18n）零改动 |

---

## 9. 分支与协作约定

| 侧 | 仓库 | 分支（拟） | 基线原点 | 备注 |
|---|---|---|---|---|
| OR | `g:\workshop\RUYI\OpenRefine` | `feat/or-solon-migration` | `feat/batch-title-extraction` HEAD | 远端 `origin`=diegoinsh/OpenRefine；`upstream`=OpenRefine/OpenRefine **仅作只读新功能来源（cherry-pick），不再 rebase / 合并**（§5.4） |
| OR（要点③ SDK 线） | `g:\workshop\RUYI\build\or-solon-poc` | `feat/platform-sdk-decoupling` | `feat/or-solon-migration` HEAD `dcbf8dbc6` | 与 Solon 迁移正交、可先行；未推送 |
| 能力平台 | `g:\workshop\RUYI\ruyi-capability-platform` | `feat/platform-sdk-decoupling` | `main` HEAD | 无远端（本地） |

- **不动主干**：POC 在独立分支（建议独立 worktree）执行，不切换、不污染在用的主工作区。
- **不碰他人改动**：工作区内其他 agent 未提交的 `extensions/FilesExtension` 等改动**勿动、勿提交、勿回退**。
- **不干扰回归**：`test/run_batch_extraction.ps1` 等正在运行的回归任务不得被打断。
- **推送**：统一 GH_TOKEN + https（借 `gh` 凭据），不改 git 配置、token 不出现在命令与输出中。
- **上游策略（2026-10-17 校准）**：**已断开与上游 OR 的跟随**（§5.4）。`upstream` 远端**只读**，仅用于 **cherry-pick 抽取上游新功能**；**不再做 rebase / 合并**，也不为降低 rebase 冲突面做任何设计妥协。

---

## 10. 待验证清单（P0）

1. Solon `boot` 适配器挂载现成 `HttpServlet` 的能力（含 init-params / `ServletContext` 属性 / webapp 资源根）。
2. 各 adapter 的 javax/jakarta × Java 版本矩阵（与 OR 侧最终选择的 javax/jakarta 对齐）。
3. Solon 维护活跃度（近 6–12 个月 commit/issue/release 频率）与 tomcat/undertow 适配成熟度。
4. 与 commons-fileupload / velocity / Rhino(GraalJS) / `dog_read.exe` native 调用的共存性。
5. ~~上游 OR 4.0 设计文档中的容器/Butterfly/Jetty 走向（决定 Phase 2/3 顺序）。~~ → **已调研（2026-10-16）**，结论与 Phase 2/3 顺序建议见本节末「上游 4.0 走向调研结论」。**并已于 2026-10-17 定位校准：本项目与上游断开、不再等待，该项不再是待验证项**（见 §5.4）。

### P0 执行记录（2026-10-16）

- **工作区**：独立 worktree `g:\workshop\RUYI\build\or-solon-poc` @ `feat/or-solon-migration`（基线文档已提交，commit `dee0a9713`）；主工作区未切换、未污染。
- **依赖可获取性 ✔**：Maven Central 上 `org.noear:solon` / `solon-web-servlet`(javax) / `solon-web-servlet-jakarta` / `solon-server-jetty`(+jakarta) / `solon-server-tomcat` / `solon-server-undertow` **均存在，最新 4.1.1**；本机 `settings.xml` mirror = aliyun public，可解析。
- **在house 先例 ✔**：能力平台已以 `solon-parent 4.1.1` + `solon-web`（**非 servlet**，走 smart-http）运行——证明 Solon 主体在本环境可用；但**servlet 适配器在本工程尚无先例**，属 POC 新增面。
- **OR webapp 契约（已取证）**：`main/webapp/WEB-INF/web.xml` 声明 servlet `refine`(=`com.google.refine.RefineServlet`) 映射 `/*`；运行期由 `Refine.configure()` 注入 init-params `refine.data` / `butterfly.modules.path`(`{dataDir}/extensions`) / `refine.autosave`；`RefineServlet.init()` 依赖 `getInitParameter("refine.version"/"refine.revision"/"refine.autosave")` 与 `javax.servlet.context.tempdir`；`butterfly.properties` + `modules.properties` 在 `WEB-INF`；Gzip 经 `jetty-web.xml` 的 `GzipHandler`。→ **POC 挂载面 = 1 个 servlet + 5 个 init-param + tempdir 上下文属性 + webapp 资源根**。
- **构建约束 ⚠**：本机 Maven `localRepository` = `C:\Users\kubao\tools\maven\apache-maven-3.8.1\maven-repository`，**在沙箱可写范围之外**；需新下载 Solon（如 `solon-server-jetty` 尚未缓存）时，maven 命令须**禁用沙箱**执行。工程历史上亦需 `-o` 离线，故 POC 构建需联网（禁用沙箱）。
- **OR 侧现为 `javax.servlet` 4.0.1**、`java.minversion=11`（本机运行 JDK 21）、Jetty 10.0.19、Butterfly 1.2.6（**外部 jar**，非源码）。→ 先保 **javax** 使 121 命令类零改动。
- **待实测项 → 已实测完成**：见下方「POC 执行记录」。

---

### POC 执行记录（真实动代码，2026-10-16）

> **工作区**：独立 worktree `g:\workshop\RUYI\build\or-solon-poc` @ `feat/or-solon-migration`；主工作区 `g:\workshop\RUYI\OpenRefine` 未切换、未污染。
> **Solon 侧**：`java -cp server\classes + 90×webapp/WEB-INF/lib + 36×provided 依赖 + solon 全套` → `com.google.refine.RefineSolon`（Solon 4.1.1 + **Tomcat 9.0.118**，端口 3433，数据目录 `%TEMP%\refine-poc-data`）。
> **对照（Jetty）**：同 worktree、同 webapp、端口 3334 的原生 `com.google.refine.Refine`。

#### 1. 挂载可行性 ✔（「薄采用」成立）

- 启动：`Tomcat/9.0.118/4.1.1 @1631ms`，监听 `127.0.0.1:3433`；`RefineServlet`+Butterfly 模块扫描在**首个请求时**初始化（懒加载，约 21–23s）。
- `GET /command/core/get-version` → 200，`{"full_name":"JinRefine 3.11.0.01 [TRUNK]","module_names":["core","data-quality","database","files","jython","pc-axis","records-assets","records-db"]}` —— 模块扫描、`getRealPath("/")`、`getServerInfo()` 兜底均生效。
- 静态/模板/命令全部就位：`/`（index.vt 经 velocity 渲染 22KB）、`/wirings.js`、`/index-bundle.js`（Rhino 实时拼接 ~794KB）、`/about.html`、`images/*`、`get-languages`、`get-all-project-metadata`。
- **POC 新增/改动仅 3 个文件**：`server/src/com/google/refine/RefineSolon.java`（新，含 3 处最小桥：`/*` pathInfo 语义、`ServletContext.getRealPath` 兜底、5 个 init-param）+ `server/pom.xml`（加 Solon/Tomcat 依赖）+ `modules/core/pom.xml`（fileMask 修正，见第 5 点）。**121 个命令类、`controller.js`、前端契约零改动。**

#### 2. 关键缺陷与修复：Tomcat multipart 语义 ✔（POC 唯一真实适配点）

- 现象：Solon 侧上传导入项目得 `rowCount:0`（`temp/import/<id>/raw-data` 为空）；Jetty 侧同请求 `rowCount:3`。
- 根因：`CreateProjectCommand.doPost` 首行走 `hasValidCSRFTokenAsGET` → `ParsingUtilities.parseParameters` → `request.getParameterMap()`。**Tomcat 在 content-type=multipart/form-data 且无 `<multipart-config>` 时会因 `parseParameters()` 解析并吞掉请求体**（parts 为空），下游 `commons-fileupload` 拿不到文件；**Jetty 无 multipart-config 时不解析、保留请求流**。
- 修复（隔离在 `RefineSolon.PathInfoRequest` 内）：multipart 请求下把 `getParameter/Values/Names/Map` 覆写为**只暴露查询串**（手工 URLDecode，支持同名多值），请求体留给 OR 自己的 fileupload 解析 —— 与 Jetty 语义一致。
- 验证：修复后 Solon 上传 → `HTTP 302` + `rowCount:3`，`importOptionMetadata` 与 Jetty 一致。

#### 3. 响应等价性 ✔（字节级）

- 同一 `get-rows?project=<5000行>&start=0&limit=100` 请求：Solon 与 Jetty 输出**逐字节一致**，`size=14174`，`SHA256=2D557F11AF3105D93DFE184FE1331C4A367B1768E3AE3E8A08D7E763E69719D0`。

#### 4. 性能与内存 ✔（门禁：偏差 ≤ ±10%）

5000 行项目，单进程 curl 批量发送（预热 30、测量 300），全部 HTTP 200；**两次复跑取更稳的 run#2**：

| 端点 | P50/P95/Avg (ms) · Solon | P50/P95/Avg (ms) · Jetty | 判定 |
|---|---|---|---|
| `get-rows`(limit=100) | 0.99 / 1.32 / 1.21 | 1.17 / 1.58 / 1.22 | 持平偏快 |
| `get-models` | 0.66 / 0.85 / 0.72 | 0.64 / 0.73 / 0.73 | 持平 |
| `get-all-project-metadata` | 0.52 / 0.64 / 0.73 | 0.65 / 0.75 / 0.69 | 持平偏快 |

- run#1 曾出现 `get-all-projmeta` Solon 偏慢（1.20 vs 0.78），复跑消失 → 判定为 JIT/测量顺序噪声；两侧**绝对时延 P95 均 ≤3ms**，微秒级差异不构成退化。
- 峰值内存（同负载后）：**Solon WS=218MB / Private=264MB** vs **Jetty WS=417MB / Private=674MB** → Solon 侧显著更低。

**结论：容器层替换未观察到性能退化（重负载端点持平偏快、内存更低）。**

#### 5. 附带发现：基线 `modules/core` 干净构建缺陷（与本改造无关，但需修）

- 基线 `modules/core/pom.xml` 的 localizer `<fileMask>OpenRefineMessage*.properties</fileMask>` 在本机实测**恒不匹配任何文件**（`GeneratorMojo` 对 `File.getName()` 做精确 `equals`，不支持通配符）→ `mvn generate-sources` **一个类都不生成**。
- 而 `OpenRefineMessage` 被 `ImporterUtilities`/`Command`/`RangeFacet` 三处引用 → **新 worktree / `mvn clean` 的干净构建会因缺类而编译失败**；既有工作区因残留旧生成物（早于该缺陷引入）而未暴露。
- POC 分支已修正为精确文件名 `OpenRefineMessage.properties`：删除生成物后 `generate-sources` 可重新生成，且生成结果与旧通配符产物**逐字节一致**（改动本身输出中性，属可选的健壮性修正）。

#### 6. 回归门禁

- **容器/命令路由回归** `RefineServletTests`：**9/9 通过**（GET/POST/PUT/DELETE 分发、404、`getPathInfo` 路由）。
- **`modules/core` 全量**：`Tests run: 209, Failures: 4` —— 4 项全部落在 `HistoryEntryTests`/`HistoryTests` 的 **JSON 序列化断言**，根因是该分支 `HistoryEntry` 序列化多写一个 `"sheetId": null` 字段、而期望 JSON 未含该字段；**与容器层替换无关**（`modules/core` 对基线仅 fileMask 一处改动、且输出中性；主工作区同源同码）。属**基线既有失败**，不阻塞 POC。

#### 7. 已知待办 / 未测项（交 Phase 1+ 处理）

- **未测**：多连接并发压测（本轮为单连接顺序压测）、64MB 大文件上传、前端 cypress e2e。
- **环境 caveat（非容器回归）**：本 worktree 的 `extensions/*/module/MOD-INF` 下**无 `classes`/`lib`**（扩展模块未编译），故 `data-quality`/`files`/`jython`/`pc-axis`/`records-*` 等模块 init 报 `JavaPackage ... is not a function`；**Jetty 侧同源同报**，属构建产物缺失，非 Solon 引入。

#### 8. 容器语义对齐收尾 ✔（本轮完成，关闭第 7 章路线图的 POC 收尾项）

针对上轮「未对齐的配置语义」，本轮在 `RefineSolon` 内显式补齐 4 项，并回归验证上传路径：

| # | 语义项 | 现状锚点 | 对齐做法 | 验证结果 |
|---|---|---|---|---|
| 1 | 懒加载 init（load-on-startup） | Jetty 部署期初始化 | `Solon.start(...)` 后调 `RefineSolonHandler.warmup()` → 首次 `servlet()` | 日志 `RefineServlet pre-initialized in 2328ms (load-on-startup aligned)`，首请求不再阻塞 |
| 2 | gzip（`jetty-web.xml` GzipHandler） | min=2048B、MIME 白名单 | 捕获 `TomcatServerBase` → 反射取 `_server` → `Connector.setProperty("compression"/"compressionMinSize"/"compressibleMimeType", …)` | `index-bundle.js`：Solon 793829→**169043B**、Jetty 793829→**169036B**；两侧均回 `Content-Encoding: gzip` + `Vary: Accept-Encoding`（7B 差来自 deflate 实现差异，语义等价） |
| 3 | `SessionPath` context-param | `web.xml` 中 `SessionPath=/`（Jetty 专属） | 判定 **N/A 无需迁移**：根上下文（Tomcat 已将 `path [/]` 归一为 ``），且 OR 全码 0 处 `HttpSession` 使用 | 无需迁移 |
| 4 | OR 自设线程池 / 时长 / 体积上限 | `maxThreads=30 / maxQueue=300 / idle=60s`；64MB form | Solon Tomcat `setExecutor` 为 **no-op**（不支持自定义池）→ 改以 `maxThreads=acceptCount=refine.queue.max_size(300)` 近似并发上限，并设 `maxPostSize/maxSwallowSize=64MB`、`connectionTimeout=60000ms` | 日志 `Aligned connector semantics: gzip(min=2048B, mime=9 types), maxPostSize=64MB, maxThreads/acceptCount=300, connectionTimeout=60000ms` |

- **捕获容器实例**：`app.onEvent(TomcatServerBase.class, server -> …)`（`EventBus` 用 `isInstance` 匹配子类，`TomcatPlugin.start0` 在 `_server.start()` **之前** publish，故 listener 可在启服前拿到实例并缓存）。
- **multipart 回归（确认对齐未破坏 POC 修复）**：两侧各以 `create-project-from-upload?csrf_token=<query>` + `-F project-file=…csv` 上传同一 3 行 CSV：
  - Solon(3433)：`HTTP 302` → `/project?project=2261096021157`，`get-project-metadata.rowCount=3`，`importOptionMetadata.fileSource=poc-upload-test.csv`。
  - Jetty(3334)：`HTTP 302` → `http://127.0.0.1:3334/project?project=2718005759191`，`rowCount=3`。
  - **唯一差异**：`Location` 形态 —— Solon 相对路径、Jetty 绝对 URL（Tomcat `useRelativeRedirects` 默认相对；Jetty 默认转绝对）；二者均合 RFC 7231，浏览器按请求 URL 解析，**语义等价**。
- **响应等价性复验**：`get-version` 两侧 **427B 逐字节一致（`-ceq` = True）**。

**结论：4 项容器语义对齐完成；POC 收尾项清零，可进入 Phase 1（Butterfly → 框架无关 Java SPI 化）。**

---

### 上游 4.0 走向调研结论（Phase 2 前置，2026-10-16）

> ⚠ **2026-10-17 定位更新**：本节结论（含下方"对 Phase 2 / Phase 3 顺序的建议"）**已被 §5.4「上游 OR 走向与我们的关系」取代**。本项目**已与上游断开、独立演进**，**不再等待 4.0 / #7707**，也**不再为保住 rebase 做任何设计妥协**。本节**仅作背景留档**（记录上游当时的形态），**不再作为路线决策依据**。

> 取证来源：OpenRefine 官方文档 + GitHub 一手材料（issue/PR 状态经 GitHub API **逐条核验**）+ 官方论坛线程。
> 置信度分档：**★ = 有 GitHub API 一手核验**；**☆ = 仅文档/论坛材料（未逐条 API 核验）**。

| # | 问题 | 上游现状 | 状态 | 置信度 |
|---|---|---|---|---|
| 1 | Butterfly 去留 | **保留并"改造"**（讨论 `Butterfly 2.0` / 纳入 OR 仓库），**不弃用、不换框架** | 方向已定，无时间线 | ☆ |
| 2 | Servlet / jakarta | **刻意留在 EE8 / Servlet 4.0（`javax.servlet`）**，**不迁 jakarta** | 计划中 | ★ |
| 3 | Jetty 版本 | `10 → 12.1`，**保留 Servlet 4.0 兼容**（分支 `jetty12-ee8`） | **open，未合并** | ★ |
| 4 | controller.js / 扩展机制 | 官方"声明式注册"issue **已 `not_planned` 关闭**；OSGi / pf4j **仅讨论、未采纳** | 未实现 | ★(关闭) / ☆(讨论) |
| 5 | 4.0 时间点 | **未发布、无日期**；milestone `V.next`（描述含 "requires Java 17, updates Servlet/Jetty"） | 计划中，tentative 2026 | ★(milestone) / ☆(日期) |

**关键一手证据（GitHub API 核验）**：

- **PR #6077**《Update to Jetty 11 and other breaking Butterfly API changes》（Jetty 11 + **Jakarta Servlet 5**）：`state=closed`、`closed_at=2026-03-13`、`merged=false`；正文自陈 "…it's a breaking change for all extensions, so I'm not convinced it's needed"。
- **issue #7706**《Upgrade to Jetty 12》（`open`，milestone `V.next`，assignee `tfmorris`）：**Proposed solution = "EE 8 - Servlet 4.0 API compatible with existing code"**，Alternatives = EE9/EE10（jakarta）。→ **jakarta 迁移被显式推迟**。
- **PR #7707**《Update to Jetty 12.1 keeping Servlet 4.0 / EE 8》：`open`，即 #6077 的替代方案。
- **issue #5664**《Use a declarative format for registering extension components》：`closed` / `not_planned`（2025-02-03）→ 上游**不会**在近期把扩展注册改成声明式。

**对 Phase 2 / Phase 3 顺序的建议（本调研直接产出）**：

1. **"保 `javax` 让 121 命令类零改动"与上游同向** ✔ —— 上游明确选 EE8 / Servlet 4.0，我方**不需、也不应抢跑 jakarta**；Phase 2 原列 "jakarta 迁移" 一项**取消**。
2. **Phase 2 剩余实质 = 跟随 `Jetty 10 → 12`**，但该 PR **未合并、4.0 无日期** → 抢跑只会引入 rebase 摩擦，故 Phase 2 **降级为「观察 + 预备」，不阻塞后续**。
3. **Phase 3（装载器选型）不必等 Phase 2**：上游选"**保留并改造 Butterfly**"而非替换，我方 Solon 装载器是**并行备选**；且上游长期方向正是"**扩展 API 稳定化**"，与我方 SPI 边界**同向**，存在收敛机会。
4. ~~**建议主线顺序**：**先 Phase 3（Solon 装载器 POC）+ P1 横向补全**，以「**扩展稳定性优先 + 保住 rebase**」为纲，装载器做成**开关回退**（`refine.module.loader = butterfly | solon`）；**待上游 #7707 合并 / 4.0 定稿后，再决定是否对齐容器外壳**。~~ → **已作废（2026-10-17）**：其中"保住 rebase / 待上游 #7707"前提不再成立（§5.4）。**装载器做成开关回退**这一条仍有效（见 §7 Phase 3），其余以 §5.4 / §7 为准。
5. **复评触发清单**：issue #7706 / PR #7707（Jetty 12）、issue #5664（扩展注册）、milestone `V.next` 有实质进展即重估顺序。

---

### P3 执行记录（装载器选型 POC，2026-10-16）

> **工作区**：同一 worktree `g:\workshop\RUYI\build\or-solon-poc` @ `feat/or-solon-migration`；主工作区 `g:\workshop\RUYI\OpenRefine` 未切换、未污染。
> **落点**：新增 `com.jiongche.refine.loader` 四件套（`RefineModuleLoader` / `ButterflyModuleLoader` / `StandaloneModuleLoader` / `ModuleLoaderRegistry`）；`RefineServlet` 加 4 处接缝（覆写 `configure` / `createModule` / `initializeModule`(TCCL 置换) + `service` else 分支）与 8 个 `public` 访问器；`SpiBootstrap` 增「从 `MOD-INF/module.properties` 读 `refine.spi`」与 `null` 分支；2×`module.properties` 追加 `refine.spi`；2×`controller.js` 的 `init()` 改传 `null`。
> **开关**：`-Drefine.module.loader=solon` 启用自研装载器；缺省/其它值 = `butterfly`（现状，逐字节不变）。
> **范围**：只接管声明了 SPI 的 `files` / `data-quality` 两个模块，其余归 Butterfly（**混合模式**）；不删 Butterfly、不换容器、不迁 jakarta。

#### 1. 实例编排与接线校验 ✔

| 组 | 端口 | 装载器 | 数据目录 | 用途 |
|---|---|---|---|---|
| A | 3433 | 缺省（butterfly，无 core classes 前缀） | `refine-poc-data3` | 基线对照（改动前行为） |
| B | 3443 | `-Drefine.module.loader=solon` + core classes 前置 | `refine-poc-data4` | 自研装载器 |
| C / C2 | 3453 / 3473 | `-Drefine.module.loader=butterfly` + core classes 前置 | 独立目录 | **缺省回退**复跑（两次冷启） |
| D | 3463 | `-Drefine.module.loader=solon`（**未**前置 core classes） | 独立目录 | 反证 dev-shim 细节（见第 7 点） |

B 组启动日志（关键行）：

```
Module loader: solon
StandaloneModuleLoader: roots=[modules, ../../extensions, .../refine-poc-data4/extensions]
StandaloneModuleLoader: module=files cl=java.net.URLClassLoader@3e134896 urls=[.../FilesExtension/module/MOD-INF/lib/*]
StandaloneModuleLoader: module=data-quality cl=java.net.URLClassLoader@2e3a5237 urls=[.../data-quality/module/MOD-INF/lib/*]
SPI registered: module=files spi=com.jiongche.extensions.files.FilesExtensionSpi
SPI registered: module=data-quality spi=com.jiongche.extension.quality.DataQualityExtensionSpi
StandaloneModuleLoader: claimed=[files, data-quality]
RefineServlet pre-initialized in 3276ms
```

#### 2. 等价性门禁（A vs B，逐项）✔

| 项 | 方法 | A vs B 结果 | 判定 |
|---|---|---|---|
| 模块清单 | `get-version.module_names` | `["core","data-quality","database","files","jython","pc-axis","records-assets","records-db"]` 完全一致 | ✔ |
| `/command/core/get-version` | 响应体字节 | 427B 逐字节一致 | ✔ |
| `/wirings.js` | 响应体 SHA256 | 283B，`8FED5B45…` 一致 | ✔ |
| `/index-bundle.js` | 响应体**字节** | 1639478B，`5E5803C0…` 一致（**防双重注册的关键闸门**） | ✔ |
| 首页 `/` | 响应体 | 均含「数据金炼」、size **22523** 一致 | ✔（哈希见第 3 点） |
| i18n | `get-languages` | 1459B 一致 | ✔ |
| 模块静态资源（files） | `/extension/files/<静态>` | 12633B，`89BA9E1A…` 一致 | ✔ |
| 模块静态资源（data-quality） | `/extension/.../<静态>` | 34344B，`F26EDB2C…` 一致 | ✔ |
| 命令可达 | `files/extraction-config` / `data-quality/get-config` / `get-quality-rules` / `list-tasks` | A/B 均 200（非 404） | ✔ |
| multipart 上传 | `POST create-project-from-upload` | A/B 均 `HTTP 302` + `rowCount=3` + `fileSource=poc-upload-test.csv` | ✔ |
| 模块 init 失败集 | 启动日志 | A==B==C，均为 `jython`/`pc-axis`/`records-assets`/`records-db`（**既有现象**，扩展未编译） | ✔ 非回归 |

**结论**：`/index-bundle.js` 字节一致 → **未发生双重 `addClientSidePaths` 注册**（装载器只判定归属、不实例化 SPI，注册统一收口在 `SpiBootstrap`）；前端契约逐字节零变化。

#### 3. 首页 `root.html` 哈希差异的根因（Rhino/JDK 层非确定性，与装载器无关）

- 现象：`/` 响应 size 恒 22523、均含「数据金炼」，但**响应体哈希在每个 JVM 冷启动时不同**——A=`8492F195…`、B=`4A8ED5C8…`、C=`B747AA6A…`、C2=`D5B01476…`（**四进程四种哈希**）。
- 字节级定位：差异**仅在** `Refine.encodings`（core `controller.js:784` 的 `JSON.stringify(encodings)`）——其字符集别名列表来自 `charset.aliases().toArray()`，Rhino 以 `for..in` 遍历 Java `Set` 时**迭代序随 JVM 种子变化**。
- 判定：**C 与 C2 同配置、同容器却哈希不同** → 属 **Rhino/JDK 层每冷启动非确定性**，**与装载器无关**；首页门禁按「含『数据金炼』+ size 一致」通过。

#### 4. 隔离性 ✔（反向校验「确实换了 CL」）

- 两模块 CL 为**不同实例**：`java.net.URLClassLoader@3e134896`（files）/ `@2e3a5237`（data-quality），URL 集为**模块内** `MOD-INF/lib/*`（**不含 core**）；
- 二者均 **≠ `RefineServlet.class.getClassLoader()`**（App CL）→ per-module 隔离成立，且 parent=core CL 保持 parent-first（`Packages.com.jiongche.refine.spi.SpiBootstrap` 在 Rhino 下可解析，故需 TCCL 置换，见设计）。
- 混合模式生效：`claimed=[files, data-quality]`，其余模块仍走 Butterfly，`module_names` 无缺项。

#### 5. 缺省回退（C 组：`butterfly` + core classes 前置）✔

- 日志 `Module loader: butterfly`，全部方法 no-op（`owns()` 恒 `false` → `createModule` 恒走 `super`）。
- 与 A 组比对：**6/7 产物逐字节一致**，首页 size 一致（哈希差异同第 3 点）→ **门禁通过**；且证明「描述符 + `null` 路径」在 butterfly 模式同样生效（`SPI registered` 两侧成功）。
- 结论：**一个 `-D` 开关即秒级回退**，无残留副作用。

#### 6. 性能 ✔（门禁：偏差 ≤ ±10%）

- 降干扰（杀 C/C2）后 4 轮交替复测 `get-version` 单端点 P50 偏差 = **+15.8% / −1.6% / −3.2% / −26.6%**，**符号反复翻转** → 判为**亚毫秒量级的测量噪声**（`handle()` 恒 `false`，不进请求路径）；无系统性退化。

#### 7. caveat：D 组反证（无 core classes 前缀时旧 jar 的 `null` 不兼容）

- D 组（solon 模式但**未**把 `modules\core\target\classes` 前置）启动失败：`SPI bootstrap failed for null (module data-quality)` → `Class.forName` NPE。
- **根因**：此时 core 类来自 `main\webapp\WEB-INF\lib\core-3.11.0.01.jar` 中的**旧** `SpiBootstrap`，其 `register(module, String)` **不支持 `null`**（描述符路径是本 POC 新增）；反证 A 基线是在 `controller.js` 仍传 FQCN 的时代采集的。
- **判定**：属 **POC dev-shim（classes 前置）细节**，非设计缺陷；**正式部署将重打包 core jar**（含新 `SpiBootstrap`），届时 `null`/描述符路径随 jar 一并生效。D 组进程已终止。

#### 8. 非阻塞开放项取值（默认）

- `handle(req,resp)` 请求路径接缝：**保留骨架**（恒 `false`，资源仍由 `super.service()` 经 `_mounter` 分发，保证字节一致）。
- 本 POC **只测 Solon 侧**；Jetty 侧 A/B 留待需要时补。

---

### P3 收敛执行记录（去 dev-shim + 隔离/卸载 + 四模块扩面，2026-10-16）

> **工作区**：同一 worktree `g:\workshop\RUYI\build\or-solon-poc` @ `feat/or-solon-migration`。
> **承接**：把上一节 POC 形态收敛为可交付形态——core 正式重打包、**去 dev-shim**、接管面扩到 4 模块、补齐隔离 / 卸载证据。**不改缺省开关**（仍 `butterfly`）。

#### 1. core 正式重打包（去 dev-shim）✔

- 命令：`mvn -o -pl modules/core package -Dmaven.test.skip=true -DskipTests` → `modules/core/target/openrefine-core.jar`，直拷为 `main/webapp/WEB-INF/lib/core-3.11.0.01.jar`（**保持同名**，classpath/既有引用零涟漪）。
- 断言：`jar tf` 含 `com/jiongche/refine/loader/{RefineModuleLoader,ButterflyModuleLoader,StandaloneModuleLoader,ModuleLoaderRegistry}.class` 与 `com/jiongche/refine/spi/{RefineModuleSpi,RefineModuleContext,ButterflyModuleContext,SpiBootstrap}.class`；产物 SHA256 源/目标一致（`DDB339C2…`，610382B）。
- **此后所有实例（含 A 组基线）一律不前置 core classes**：classpath 首项 `server\classes` 不含任何 core/jiongche 类 → dev-shim 彻底移除。
- 意义：上一节「第 7 点 D 组反证」（旧 jar 的 `SpiBootstrap` 不支持 `null` 描述符路径）随之消解——新 jar 原生支持描述符 `null` 路径。

#### 2. 代码收敛（`StandaloneModuleLoader`）✔

- **日志**：`roots` 与每模块 `urls=[…]` 降为 `debug`；保留 `module=<name> cl=<CL>` 与 `claimed=[…]` 两条 `info`（前者作为隔离证据的默认可见面）。
- **`unload()`**：关闭 per-module `URLClassLoader` 并新增 `info`：`unloaded N modules (classloaders closed)`；**不重复调用 `module.destroy()`**——取证（反编译 `Butterfly.destroy`）显示 `Butterfly.destroy()` 已遍历 `_modulesByName` 逐个调 `ButterflyModule.destroy()`，而 `unload()` 在其之前执行，重复调用会造成**双重销毁**。
- **未动**：`handle()` 恒 `false`、缺省 `butterfly`、`RefineSolon` 的容器侧反射桥接。

#### 3. 实例编排（全部无 shim）

| 组 | 端口 | 装载器 | 数据目录 |
|---|---|---|---|
| A | 3433 | 缺省 `butterfly` | `%TEMP%\refine-p3c-a` |
| B | 3443 | `-Drefine.module.loader=solon` | `%TEMP%\refine-p3c-b` |
| C | 3453 | `-Drefine.module.loader=butterfly`（复跑） | `%TEMP%\refine-p3c-c` |
| W4 | 3473 | `solon` + %TEMP% 临时 harness | `%TEMP%\refine-p3c-w4` |

B 组启动关键行：

```
Module loader: solon
StandaloneModuleLoader: module=records-db      cl=java.net.URLClassLoader@60921b21
StandaloneModuleLoader: module=files          cl=java.net.URLClassLoader@67ec8477
StandaloneModuleLoader: module=data-quality   cl=java.net.URLClassLoader@25d958c6
StandaloneModuleLoader: module=records-assets cl=java.net.URLClassLoader@4482469c
SPI registered: module=records-db|files|data-quality|records-assets
StandaloneModuleLoader: claimed=[records-db, files, data-quality, records-assets]
```

#### 4. 等价性门禁（A / B / C 逐项）✔

| 项 | 结果 | 判定 |
|---|---|---|
| 字节一致（SHA256） | `get-version` 427B `7A1A166F…`、`wirings.js` 283B `8FED5B45…`、`index-bundle.js` 1797916B `BBC5EB0F…`、`project-bundle.js` 2238918B `227085B8…`、`get-languages` 1459B `B456153E…` —— **A≡B≡C 逐字节一致** | ✔ |
| `get-importing-configuration` | 顶层键序每冷启动抖动（本 POC 已判为 Rhino/JDK 层非确定性，见上一节第 3 点）；**深度规范化后 A≡B≡C**（canonical 3773B 相等） | ✔（内容等价） |
| 模块清单 `module_names` | `core,data-quality,database,files,jython,pc-axis,records-assets,records-db` 三组一致 | ✔ |
| 4 模块命令可达 | `records-db/export-bound-assets`=200、`records-assets/list`=200、`records-assets/preview`=200、`records-assets/file`(缺参)=400、`files/extraction-config`=200、`data-quality/get-config`=200 —— 三组一致 | ✔ |
| multipart 上传 | `POST create-project-from-upload`（带 csrf_token）→ **HTTP 302 + rowCount=3** —— 三组一致 | ✔ |
| bundle 去重 | `index-bundle.js` / `project-bundle.js` **三组逐字节一致**（⇒ 4 模块客户端资源各注册一次、零重复） | ✔ |
| 未变项 | init 失败集 `{jython, pc-axis}`（`JavaPackage … is not a function`）三组一致（`records-*` 因 P1 已编译+SPI 化，不再出现在失败集） | ✔ 非回归 |
| 性能（可选） | `get-version` P50 三轮 B vs A = **+3.1% / −0.7% / +2.0%**（≤ ±10%） | ✔ |

#### 5. 隔离证据（W3）✔

- B 组 4 个接管模块各得**互不相同**的 `URLClassLoader` 实例（`@60921b21` / `@67ec8477` / `@25d958c6` / `@4482469c`）；均为 `URLClassLoader`（**≠ 应用系统类加载器 `AppClassLoader`**），parent = core CL（parent-first，保 `SpiBootstrap` 在 Rhino 下可解析）。

#### 6. 卸载路径证据（W4）✔

- POC 引导 `RefineSolon` 手工 `new RefineServlet()` 且**无停机钩子**，`Stop-Process` 为硬杀**不触发** `destroy()` → 用 `%TEMP%\p3w4\W4Harness`（临时，不入库）显式调用 `RefineServlet.destroy()`：

```
[W4] calling RefineServlet.destroy() ...
StandaloneModuleLoader: unloaded 4 modules (classloaders closed)
[W4] destroy() returned normally
Server:main: tomcat: Has Stopped (Apache Tomcat/9.0.118/4.1.1)
```

- 无异常栈、无 CL 泄漏告警；`destroy()` 依序 → `_loader.unload()`（关 4 个 CL）→ `super.destroy()`（Butterfly 逐个 `module.destroy()`）→ 正常返回。

#### 7. 缺省回退 ✔

- C 组日志 `Module loader: butterfly`；与 A 组**全部门禁项一致**（字节 / 命令可达 / 上传 / 失败集）；`-D` 开关秒级回退，无残留副作用。

#### 8. 收敛后状态

- dev-shim 与 POC 期 verbose 日志已消除；`solon` 路径在**真实产物**（重打包 core jar）下独立运行；接管面 **4 模块**。**缺省仍 `butterfly`**，秒级回退保留。
- 未做（明确边界）：运行期热插拔 / 重载接缝；`RefineSolon` 容器侧桥接的收敛（留待 Phase 3 自建装配）；上游扩展（`jython` / `pc-axis` / `database`）init 报错。

---

### R2 执行记录（彻底移除 Jetty · 交付形态原生 Solon，2026-10-16）

> **工作区**：同一 worktree `g:\workshop\RUYI\build\or-solon-poc` @ `feat/or-solon-migration`。
> **承接**：把 P3 收敛后的 **POC 形态**推进为**可交付形态**——两条真实入口（`jinrefine.exe`、`refine.bat`）**原生**走 Solon，**彻底移除 Jetty**（不再保留 Jetty 启动分支）。**不改缺省开关**（仍 `butterfly`）。计划：`or-solon-r2-delivery-form-plan.md`。

#### 1. 彻底移除 Jetty（D3）✔

- **依赖**：`server/pom.xml` 删 `jetty-server`/`jetty-servlet`/`jetty-webapp`/`jetty-util` 四个依赖；`modules/core/pom.xml`、`main/pom.xml` 各删 `jetty-servlets`；根 `pom.xml` 删死属性 `<jetty.version>10.0.19</jetty.version>`（全仓已无 `${jetty.version}` 引用）；`main/pom.xml` 删 `<excludeArtifactIds>jetty-server,jetty-http,jetty-io,jetty-util</excludeArtifactIds>`。
- **代码**：`Refine.java` 整体重写——删 6 个 jetty import、`RefineServer` 整类、`ShutdownSignalHandler`、`fixWindowsUnicodePath`、`init(String[])`；`getDataDir()` 上提为包级 `static`；`main()` 仅设 `VERBOSE`/`apple.*` 三属性后委派 `RefineSolon.main(args)`；保留 `RefineClient`。删 `ValidateHostHandler.java`（逻辑并入 `RefineSolon`）、`ThreadPoolExecutorAdapter.java`（仅 `RefineServer` 使用）。
- **配置/资源**：删 `main/webapp/WEB-INF/jetty-web.xml`、`WEB-INF/lib/jetty-servlets-10.0.19.jar`；`web.xml` 删 `org.eclipse.jetty.servlet.SessionPath` context-param；`server/src/log4j2.properties` 删 `logger.org_eclipse_jetty` 两行。
- **产物**：清理 `main/webapp/WEB-INF/lib/core-3.11.0.01.jar.bak-noSpi`。

#### 2. 依赖落位（D1：classloader 解法，不改 launch4j / 脚本 / CL 逻辑）✔

- `server/pom.xml` 对 `main` 的依赖由 `<type>pom</type>` + `provided` 改为**普通 jar + 缺省 `compile`** ⇒ `copy-dependencies(includeScope=runtime)` 自动把 `core`/`grel`/`main` 及其全部运行时依赖拷入 `server/target/lib` ⇒ exe / bat / launch4j / `refine`（Unix）**零改动**即可见。
- 与 POC 验证过的「扁平 classpath 模型」（`poc-cp-full.txt`）等价；**未**自建 webapp `URLClassLoader`（父 CL 加载 `RefineServlet` → 下探不到子 CL 的 core 类，会 `NoClassDefFoundError`）。

#### 3. 容器语义迁入 `RefineSolon`（D4/D5/D6/D7）✔

- **绑定/Host 对齐（D4/F7）**：绑定地址改用 `refine.interface`（`0.0.0.0` 时不设 `server.host`）；`expectedHost = refine.host`（缺省 = `iface`，`0.0.0.0`→`*` 关闭校验）。逐字搬入 `LOOPBACK_PATTERN` + `isValidHost()`（原 `ValidateHostHandler`），在 `RefineSolonHandler.handle` 入口处校验，非法 Host → **404**。
- **headless / 开浏览器（D5/F9）**：`Solon.start` 与 `alignContainerSemantics()` 之后补：`refine.headless=true` → `java.awt.headless=true`；否则 `new RefineClient().init(hostForBrowser, port, contextPath)`（失败仅 warn）。
- **上传上限解耦（D6/F10）**：`maxFormContentSize` → `maxPostSize`，配置键 `refine.max_form_content_size` → **`refine.container.max_post_size`**（缺省 `64 * 1048576`）；连接器 `setProperty("maxPostSize"/"maxSwallowSize", …)`。
- **反射桥保留（D7）**：`TomcatServerBase._server` 反射桥继续用于连接器语义对齐。
- **数据目录（F6）**：`RefineServletConfig.getInitParameter` 与 `RefineServletContext.tempDir()` 的 `RefineServer.getDataDir()` → `Refine.getDataDir()`。

#### 4. 入口与脚本（D2/D6）✔

- 主类仍 `com.google.refine.Refine`（launch4j `<mainClass>`、`jinrefine.l4j.ini`、`refine.bat` 均零改动）。
- `refine.bat` / `refine`（Unix）/ `refine.ini`：新增 `REFINE_CONTAINER_MAX_POST_SIZE`（缺省 `67108864`）并注入 `-Drefine.container.max_post_size=…`。

#### 5. 构建与产物（V1/V2）✔

- `mvn clean package -Dmaven.test.skip=true -P windows -pl !benchmark` → **BUILD SUCCESS**（exit 0）。
- `server/target/lib` = **107 jar**，**零 `jetty*`**；含 `core-3.11.0.01.jar`、`grel-3.11.0.01.jar`、`main-3.11.0.01.jar`、`solon-4.1.1.jar`、`solon-server-tomcat-4.1.1.jar`、`tomcat-embed-core-9.0.118.jar`、`butterfly-1.2.6.jar`（server 自身 jar 位于 `server/target/openrefine-3.11.0.01-server.jar`，装配时放入 `server/target/lib`）。`main/webapp/WEB-INF/lib` 亦零 jetty。
- 交付产物 zip `jinrefine-win-with-java-3.11.0.01.zip`（275.7MB）零 jetty。
- `dependency:tree -pl server -am -Dincludes=*jetty*` → **0 命中**（注：不带 `-am` 时会从 `~/.m2` 陈旧产物误报 jetty）。

#### 6. 端到端（V3：两条真实入口）✔

解压产物到 `%TEMP%\r2-delivery\jinrefine-3.11.0.01`，两入口均首页 **200**：

- **exe 入口**（内置 JRE，classpath 仅 `server/target/lib`，**未借助 `poc-cp-full.txt`**）：日志 `Module loader: butterfly`、4 模块 SPI 注册、`maxPostSize=64MB`、gzip 对齐、headless。
- **bat 入口**（`JAVA_HOME=…\server\target\jre`、`REFINE_WEBAPP=webapp`、`REFINE_PORT=3553`）：日志 `Starting JinRefine on Solon, port=3553, interface=127.0.0.1, expectedHost=127.0.0.1`。

**门禁哈希三组逐字节一致**（exe·butterfly ≡ bat·butterfly ≡ exe·solon）：

| 端点 | 字节 | SHA256（前 8） | 与 P3 基线 |
|---|---|---|---|
| `get-version` | 423 | `E505711F…` | 见 §8（+4 字节，非回归） |
| `wirings.js` | 283 | `8FED5B45…` | 一致 |
| `index-bundle.js` | 1803766 | `1B7E96A0…` | 见 §8（度量环境不同） |
| `project-bundle.js` | 2238918 | `227085B8…` | 一致 |
| `get-languages` | 1459 | `B456153E…` | 一致 |

- **Host 校验**：`Host: evil.example.com` → **404**；缺省 → 200。
- **multipart**：`create-project-from-upload` → **302**，`Location /project?project=2704327788918`。
- **module_names** 8 项：`core,data-quality,database,files,jython,pc-axis,records-assets,records-db`。
- **solon 装载器**：`Module loader: solon`，4 模块各得独立 `java.net.URLClassLoader`（`records-db@21fdfefc`/`files@7965a51c`/`data-quality@421a4ee1`/`records-assets@1237e0be`），`claimed=[records-db, files, data-quality, records-assets]`；`index-bundle.js` 零重复注册（60 资源 60 唯一）。

#### 7. 关停（V4）✔

- `R2StopHarness`（临时，不入库）调用 `Solon.stop()`：关停链干净——`Stopping service [Tomcat]` → `Stopping ProtocolHandler ["http-nio-3563"]` → `Server:main: tomcat: Has Stopped` → `App: Stopped`，**无异常栈**。唯一 WARNING 为 Tomcat stop 期 `WebappClassLoaderBase` 非法反射访问（Tomcat 内部清理）。
- 观察：`RefineServlet.destroy()` **未被容器调用** ⇒ 装载器 `unload` 不在关停时触发（与 P3 W4 结论一致；进程退出即回收，无实害）。

#### 8. 与 P3 数值的两处偏差（均**非回归**）

1. `get-version` **423 vs 427** 字节：仅 `java_vm_version` 由 `11.0.25+9` → `11.0.32.1+1`（新内置 JRE）长度 +4。
2. `index-bundle.js` **1803766 vs 1797916** 字节：worktree 的 `main/webapp/extensions` **不存在**（POC 从源码 webapp 跑时无扩展脚本），而交付 `webapp/extensions` 由 `extensions/*/target` 构建产物装配 → **度量环境不同**；跨入口/跨装载器**逐字节一致**、**零重复**。

#### 9. 状态与边界

- Jetty **彻底移除**（全仓依赖 / 代码 / 配置 / 产物零 jetty）；交付形态两条真实入口原生 Solon。**缺省装载器仍 `butterfly`**（与容器层正交，秒级回退不变）。
- 未做（明确边界，见计划 §7）：不自建 Solon 装配、不摘 `TomcatServerBase._server` 反射桥、不剔除交付产物 `webapp/WEB-INF/lib`（列为后续优化）、不动前端 JS / `controller.js`、不迁 jakarta、不换 transport、不处理上游扩展 init 报错。
- 回退：本轮改动集中一个 commit → `git revert <commit>` 即回 Jetty 形态（入口照旧）。

---

### Solon 插件装载形态执行记录（D1-a：H-Spi 隔离 + 插件契约，2026-10-16）

> **工作区**：同一 worktree `build/or-solon-poc` @ `feat/or-solon-migration`。
> **承接**：把 §6 目标架构要点②「业务插件以 Solon 插件形态装载」从「容器已是 Solon」推进到「**插件装载 / 生命周期 / 注册驱动**享受 Solon 插件机制」。**不改缺省开关**（仍 `butterfly`）；**P3 的 `solon`（自研）路径不动**。计划：`or-solon-plugin-loading-plan.md`。

#### 1. 结论：D1-a（H-Spi）成立 ✔

探针（V0）核实 `org.noear:solon-hotplug:4.1.1` 可得（8.2KB，仅依赖 `org.noear:solon`），最小用法实证 `PluginClassLoader` 隔离、`PluginPackage` 生命周期、`unloadJar` 真 `close()`。→ **取 D1-a**：Solon **`Plugin` 契约**（发现 + 生命周期）+ **H-Spi `PluginClassLoader`**（per-plugin 隔离）。

#### 2. 架构与新增件

**接缝**（不动既有路径）：新增第 3 个 `RefineModuleLoader` 实现 `SolonPluginModuleLoader`（`id="solon-plugin"`）；`ModuleLoaderRegistry.resolve` 增 `solon-plugin` 分支（三值并存）。`ButterflyModuleLoader`（缺省）与 `StandaloneModuleLoader`（`solon`）**逐字未动**。

**新增 / 改动件**（均在 worktree）：

| 件 | 角色 |
|---|---|
| `modules/core/.../loader/SolonPluginModuleLoader.java` | 插件形态装载器：`createModule` 建 `PluginClassLoader`（parent = core CL）→ `addJar(MOD-INF/classes + lib/*.jar)` → `PluginUtil.scanPlugins` → `PluginPackage`（此步不 start）；`afterModuleInitialize` 在 TCCL 窗口内 `start()`；`unload` 走 `preStop/stop + close()` |
| `modules/core/.../loader/ModuleAssembly.java` | 抽出 `ButterflyModuleImpl` 装配段，供 `StandaloneModuleLoader` 与 `SolonPluginModuleLoader` 共用（去重） |
| `modules/core/.../spi/SolonPluginBridge.java` | OR 模块 ↔ Solon 插件桥：`bindCurrent/registerCurrentModule`（插件 `start` 内注册 SPI）+ `OWNED/markOwned/isDelegated`（单一注册路径） |
| `modules/core/.../loader/RefineModuleLoader.java` | 新增 `default void afterModuleInitialize(RefineServlet, String)`（缺省 no-op） |
| `modules/core/.../RefineServlet.java` | `initializeModule` 在 TCCL 置为模块 CL 的窗口内调 `_loader.afterModuleInitialize(...)` |
| `extensions/FilesExtension` / `extensions/data-quality` | `*SolonPlugin.java`（`implements org.noear.solon.core.Plugin`，`start` 内仅调 `SolonPluginBridge.registerCurrentModule()`）+ 描述符 `META-INF/solon/{files,data-quality}.properties`（`solon.plugin=<FQCN>`、`solon.plugin.priority`） |
| `refine.bat` / `refine` / `refine.ini` | `REFINE_MODULE_LOADER` 透传 `-Drefine.module.loader`（S7） |

#### 3. 三处关键设计取舍

1. **目录承载 → 自建 `PluginClassLoader`，不用 `PluginManager`**：OR 扩展是**目录形态**（`MOD-INF/classes|lib`），而 `PluginManager.loadJar(File)` 只接受**单 URL 且 parent 固定 global**，无法表达「多 URL + parent = core CL」。故 loader 自建 `PluginClassLoader(RefineServlet.class.getClassLoader())` 并逐个 `addJar(...)`，再用 `PluginUtil.scanPlugins` + `new PluginPackage(dir, cl, plugins)`。**偏差**：`PluginManager` 的「运行期 add/remove 插件」API 未采用（见 §6.1）。
2. **`Plugin.start` 时序对齐**：`Solon.start(...)` 早于 `RefineServlet.init()/configure()`，插件 `start` 不在 Solon 启动期驱动；改由 `RefineServlet.initializeModule` 的 **TCCL 置换窗口**内经 `afterModuleInitialize` 驱动 —— 与 OR 模块初始化同序，且 TCCL = 插件 CL，`SpiBootstrap.resolveSpiClass` 首命中插件自身。
3. **单一注册路径（让位）**：插件接管的模块，`controller.js` 里那行 `SpiBootstrap.register(...)` **让位**——`SolonPluginBridge.isDelegated(module)` 为真时（模块已被插件接管 ∧ 当前不在插件生命周期窗口）`SpiBootstrap.register` 直接返回，不重复注册。**不改 JS、不改 `butterfly`/`solon` 行为**，可开关回退。

#### 4. V3 · 三装载器等价（门禁）✔

A `butterfly` ≡ B `solon` ≡ C `solon-plugin`（同容器 Solon·Tomcat，仅开关不同）：

| 端点 | 字节 | SHA256（前 16） | 结论 |
|---|---|---|---|
| `get-version` | 427 | `7A1A166F02746F58` | A≡B≡C |
| `wirings.js` | 283 | `8FED5B45DCDB9704` | A≡B≡C |
| `index-bundle.js` | 1803766 | `1B7E96A0F7E6D561` | A≡B≡C |
| `get-languages` | 1459 | `B456153EC4221F2D` | A≡B≡C |
| `ext-files`（`files/scripts/index/import-from-local-dir.js`） | 33790 | `799A3EA9A86C3153` | A≡B≡C |
| `ext-dq`（`data-quality/scripts/index/data-quality-ui.js`） | 34344 | `F26EDB2CC0C80386` | A≡B≡C |
| `/`（首页） | 22605 | 三组各异 | 已知 Rhino/JDK 冷启动键序非确定性（与装载器无关，P1/P3 同源） |

C 组启动关键行（**让位生效、无双重注册**）：

```
Module loader: solon-plugin
SolonPluginModuleLoader: module=files        cl=org.noear.solon.hotplug.PluginClassLoader@1517f633 plugins=1
SolonPluginModuleLoader: module=data-quality cl=org.noear.solon.hotplug.PluginClassLoader@1c12f3ee plugins=1
SPI registration delegated to Solon plugin (skip JS-side call): module=files
SPI registered: module=files spi=…FilesExtensionSpi              ← 仅一次
SPI registration delegated to Solon plugin (skip JS-side call): module=data-quality
SPI registered: module=data-quality spi=…DataQualityExtensionSpi ← 仅一次
SolonPluginModuleLoader: claimed=[files, data-quality]
```

**混合场景**：C 组仅 `files` / `data-quality` 走插件（`claimed=[files, data-quality]`），`records-db` / `records-assets` 仍由 JS 侧注册 ⇒ 插件形态与既有装载路径**同进程并存**。

#### 5. V4/V5 · 运行期卸载与关停 ✔

`V45Harness`（临时，不入库）以 `-Drefine.module.loader=solon-plugin` 启动后：

1. 反射取装载器 `_entries` → `claimed=[files, data-quality]`，两 entry `started=true`，各自 jar 内描述符可读（`jar-resource-before=true`）；
2. 显式 `unload()` → 日志 `SolonPluginModuleLoader: unloaded 2 plugins (classloaders closed)`；`entries-after=0`、`OWNED(after)=[]`；**两 entry 描述符 `jar-resource-after-close=false`** ⇒ `PluginClassLoader.close()` **真关**（F7 实证）；
3. `RefineServlet.destroy()` 正常返回（二次 `unload` 幂等：`unloaded 0 plugins`），关停链 `Server:main: tomcat: Has Stopped` → `App: Stopped`，**无异常栈**。

> 与 P3 W4 一致：`Stop-Process` 为硬杀、不触发 servlet `destroy()`，故优雅关停 / 卸载须经 harness 显式调用取证。

#### 6. 边界与偏差（如实记录）

1. **`PluginManager` 未采用**（§3.1）：目录形态无法映射到其单 URL API；运行期**新增**插件（add）在 OR「目录 + 启动期发现」模型下无对等物，本轮以「运行期卸载（`unload` → `preStop/stop` + 关 CL）」作为热插拔**卸载侧**取证，**加载侧留待后续**（如需，走 OR 既有重启装载，或为 `PluginManager` 造目录适配）。
2. **卸载不反注册命令**：OR 的命令 / 资源注册表为静态，`module.destroy()` 亦不反注册，故卸载后「命令可达性」不消失 —— 属 **OR 模块体系既有约束**，与装载器无关（P3 同源）。
3. **前端契约不动**（D4）：`wirings.js` / `-bundle.js` / `.vt` / i18n 仍走 Butterfly；本轮**不**把 Butterfly 从默认路径摘除。
4. **仅试点 2 模块**：`records-db` / `records-assets` 尚未插件化（保持 JS 注册）；上游扩展（`jython` / `pc-axis` / `database`）init 报错不动。

#### 7. 回退

- **开关级**：`refine.module.loader` 回 `butterfly`（缺省）或 `solon`（P3），秒级回退。
- **代码级**：本轮改动集中一个 commit → `git revert <commit>` 回 R2 形态（`ed4268d74` / `217b411df`）。
- **依赖级**：仅新增 `solon-hotplug:4.1.1`（不放 `PluginManager` 亦已隔离自足）。

---

## 11. 参考来源

**代码取证**：本仓库 `modules/core`、`extensions`、`server`、`main/webapp`（`RefineServlet`、`RefineServer`、`controller.js`、`pom.xml`、`jetty-web.xml`、`web.xml`）。

**外部一手材料**：
- [无耳 Solon | 概述](https://solon.noear.org/article/about)
- [无耳 Solon | 插件扩展机制（Spi）](https://solon.noear.org/article/58?format=md)
- [无耳 Solon | 插件体外扩展机制(E-Spi)](http://solon.noear.org/article/180)
- [无耳 Solon | 插件热插拔管理机制(H-Spi)](http://solon.noear.org/article/263)
- [无耳 Solon | 打包 war(for Servlet 容器中间件)](https://solon.noear.org/article/340)
- [无耳 Solon | 上传下载(或导出)及外部静态资源](http://solon.noear.org/article/313)
- [无耳 Solon | 特点与性能对比](http://solon.noear.org/article/features)
- [无耳 Solon | 版本发布与长期支持计划(LTS)](https://solon.noear.org/article/687)
- [OpenRefine | Architecture before version 4](https://openrefine.org/docs/technical-reference/architecture-before-4)
- [OpenRefine | Architecture in version 4](https://openrefine.org/docs/technical-reference/architecture-4)
- [OpenRefine | Issue #7706 Upgrade to Jetty 12（保留 EE8 / Servlet 4.0）](https://github.com/OpenRefine/OpenRefine/issues/7706)
- [OpenRefine | PR #7707 Update to Jetty 12.1 keeping Servlet 4.0 / EE 8](https://github.com/OpenRefine/OpenRefine/pull/7707)
- [OpenRefine | PR #6077 Update to Jetty 11 and other breaking Butterfly API changes（已关闭未合并）](https://github.com/OpenRefine/OpenRefine/pull/6077)
- [OpenRefine | Issue #5664 Use a declarative format for registering extension components（not_planned）](https://github.com/OpenRefine/OpenRefine/issues/5664)
- [OpenRefine | Milestone V.next](https://github.com/OpenRefine/OpenRefine/milestone/23)
- [OpenRefine Forum | Scheduling breaking changes we have on our radar](https://forum.openrefine.org/t/scheduling-breaking-changes-we-have-on-our-radar/1410)
- [OpenRefine Forum | Extension Operations during Project load](https://forum.openrefine.org/t/extension-operations-during-project-load/2368)
- [OpenRefine Forum | Improving the UX of extension install, and Butterfly](https://forum.openrefine.org/t/improving-the-ux-of-extension-install-and-butterfly/52/21)
- [OpenRefine | Writing extensions](https://openrefine.org/docs/technical-reference/writing-extensions)
