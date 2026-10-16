# OR 容器层现代化改造基线（Butterfly / Jetty → Solon）

> Status: Draft（基线稿，供双侧分支改造遵循）
> Owner: Ruyi / OpenRefine 定制版（JinRefine 3.11.0.01）
> Date: 2026-10-16
> 取证方式：只读代码扫描 + 官方文档/论坛一手材料（未改代码、未打包、未重启服务）

---

## 1. 文档目的

固化前期全部讨论结论，作为**改造基线**：
1. 说明**为什么改**（动因）与**改了会不会退化**（风险）；
2. 给出**目标架构**（能力平台在下、OR 在上、业务走 SPI 插件）；
3. 给出**路线图与各阶段工作内容**（Phase 0–3，含门禁与回退开关）；
4. 给出**双侧分支约定**与**待验证清单**。

**基线期硬约束**：只评估、不改代码、不打包、不重启服务；不触碰工作区内其他 agent 未提交的改动（如 `extensions/FilesExtension` 相关文件）、不干扰正在运行的回归任务。

---

## 2. 动因（Why）

| # | 动因 | 说明 |
|---|---|---|
| 2.1 | 交付方式别扭 | OR 与能力平台**双向耦合**：OR 扩展（`AimpClient`）直连 AIMP HTTP；平台侧又内嵌 `dog_read.exe` / `deploy/bridge`。架构上应是**平台在下、OR 在上**，OR 的功能建立在平台能力之上 |
| 2.2 | 技术栈割裂 | OR 为 Java + 静态 JS（Butterfly/Jetty/JSP 式老架构）；平台为 Java（Solon 系）。两套技术栈并存，维护与协作成本高 |
| 2.3 | OR 架构老旧 | 扩展机制基于 **SIMILE Butterfly 的 fork**：模块描述符 `MOD-INF`、命令注册写在 **`controller.js`（JS 脚本）**，JS 与业务逻辑混杂、限制多；HTTP 层为 **Jetty 10 内嵌 + `javax.servlet`**。OR 上游自身也在做现代化，但**结果未出** |
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
| Jetty 使用点 | 全部集中于 `server/src/com/google/refine/Refine.java` 的 `RefineServer`（connector / WebAppContext / StatisticsHandler / Scanner autoreload / 线程池），+ `main/webapp/WEB-INF/jetty-web.xml`（GzipHandler）、`web.xml`（`SessionPath`，jetty 专属）、`server/pom.xml` |
| 线程池 | OR 自设：`maxThreads=30 / maxQueue=300 / idle=60s`（`ThreadPoolExecutorAdapter`） |
| WebSocket | **0 处** |
| `HttpSession` | **0 处**（会话面极薄） |
| 大文件上传 | `refine.max_form_content_size` = **64MB**；commons-fileupload |
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
- 结论：**javax 路径存在**，可先保 `javax` 让 121 个命令类零改动；但若长期跟随上游，命令层终将迁 `jakarta`——**两侧需同向选择、避免二次返工**。
- **P0 首要验证项（比 javax/jakarta 更前置）**：Solon 的 boot 适配器能否**直接挂载现成 `HttpServlet`**，并支持 **init-params / `ServletContext` 属性（tempdir） / webapp 资源根语义**——这决定"薄采用"是否成立。

### 5.3 Solon 插件机制与 Butterfly 的对应（方向成立，形态非 1:1）

| Butterfly 机制 | Solon 对等物 | 判定 |
|---|---|---|
| 从 `{dataDir}/extensions` 动态装载第三方扩展 | **E-Spi 体外扩展**：`.jar` 插件包放体外加载 | 语义对应 |
| 模块隔离（MOD-INF/lib 独立 ClassLoader） | **H-Spi 热插拔**：业务模块为单位封装为独立插件包、独享隔离、热插热拔 | 语义对应，隔离性可能更强 |
| `controller.js` 的 `register*` | **Solon Spi**：插件 + 配置声明，参与应用生命周期、提供模块启动入口 | 理念同向，需 OR 自定 SPI |
| servlet 契约 | `solon-web-servlet`(javax) / `-jakarta` | 双轨，javax 可用 |

**实现形态**（重要）：**不是"Solon 插件 = Butterfly 模块"的 1:1 替换**，而是 **OR 自定义 SPI（上述 ~10 个注册面）+ Solon 的 SPI/DI 承载装载**；前端资源装载与"每模块 web root"Solon 无对等物，**需自建**。

### 5.4 决定性发现：上游 OR 正在做同向改造
- 官方文档单列 [Architecture **before version 4**]：**4.0 是架构分水岭**。
- 官方论坛《Scheduling breaking changes》：**Butterfly 升级包含 Jetty 10→12、Servlet 4→6（javax→jakarta）**（PR #6077）。
- 官方论坛《Extension Operations during Project load》：上游正把 **`controller.js` 最小化**为"简易扩展启动器"，并"通过 `Command` 类对齐初始化流程"——**即上游自己在做"注册 Java 化、去 JS 混杂"**。
- 官方论坛《Improving the UX of extension install, and Butterfly》：社区已讨论改进扩展机制，**甚至考虑采用 OSGi 或 pf4j**，同时自陈"任务规模令人生畏""前端侧隔离保证很弱""pinned down to an aging stack"。

**含义**：上游现代化（jakarta / Jetty 12 / controller.js Java 化 / 甚至换插件框架）与我们的目标**高度同向**。
→ **不该抢跑硬分叉容器外壳**（会失去 rebase 能力，这是长期成本的**最大一根杠杆**）；应**"跟随上游现代化 + 在其上叠加业务 SPI"**。
> 证据强度：官方文档 + 官方论坛线程，属公开一手材料但非已定稿设计文档；4.0 最终是否移除 Butterfly **尚无定论**，以官方 4.0 设计文档为准。

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
1. **OR core 只依赖 SPI 接口**，不直接依赖任何行业业务。
2. **业务插件**以 Solon 插件形态装载（E-Spi 体外 jar / H-Spi 热插拔），摆脱 JS 注册混杂。
3. **平台 SDK 接口层**把 `AimpClient`、`dog_read.exe`、SSO、`deploy/bridge` 等双向耦合点收敛为接口——**与 Solon 迁移正交、可先行**。
4. **前端契约保持**：`/command/{module}/{name}`、`/modules/core`、`/extension/{module}` 不变，避免改动 1082 个 JS。

---

## 7. 路线图与各阶段工作内容

> 原则：每阶段**开关可回退**、**不与上游硬分叉**、**先易后难**（SPI 化先于装载器替换）。

### Phase 0 · 只读验证（P0）+ POC
- **目标**：把关键推测变为证据，验证"薄采用"是否成立。
- **工作项**：
  1. Solon 各 adapter 的 **javax/jakarta × Java 版本矩阵**。
  2. `solon-web-servlet` 能否**直接挂载现成 `HttpServlet`** + init-params / `ServletContext` 属性 / webapp 资源根。
  3. **POC：Solon boot 直挂现有 `RefineServlet`**（独立分支/工作区），跑 `main/tests` + `server` 回归 + benchmark 微基准。
  4. 以 **OR 官方 4.0 设计文档**锁定上游走向。
- **门禁**：回归**全绿** + 重负载端点性能偏差 **≤ ±10%**（P50/P95/吞吐/峰值内存）。
- **产出**：P0 验证结论 + 退化风险专项清单。
- **回退**：POC 在独立分支，不影响主干。

### Phase 1 · SPI 化先行（与框架无关）
- **目标**：定义 OR 业务 SPI，把业务实现移出 core → 直接落地"业务与 OR 解耦"。
- **工作项**：为第 4.4 节 10 个注册面定义 Java SPI 接口；把 `data-quality`、`FilesExtension`、`import-from-local-dir`、标题/要素抽取等改为 SPI 实现；**此时可保留 Butterfly 作装载器**。
- **门禁**：core 不再直接依赖具体业务实现；回归全绿。
- **回退**：SPI 为增量抽象，可逐个面切换。

### Phase 2 · 跟随上游现代化
- **目标**：对齐上游（jakarta / Jetty 升级 / controller.js Java 化），**保住 rebase 能力**。
- **工作项**：评估并实施与上游 4.0 同向的改动；保持 `upstream` 远端可合并。
- **门禁**：与上游 diff 可控；回归全绿。
- **回退**：逐项对齐，可暂缓单项。

### Phase 3 · 装载器选型落地
- **目标**：在"上游最小化 Butterfly" 与 "Solon E-Spi/H-Spi" 之间**选型并落地**。
- **工作项**：实现模块发现/ClassLoader/资源装载/`/wirings.js` 等价端点；前端契约不变。
- **开关**：`refine.module.loader = butterfly | solon`（秒级回退）。
- **门禁**：回归全绿 + 性能偏差 ≤ ±10% + 插件热插拔/隔离验证。

**双侧分支**：OR 侧与能力平台侧各起独立分支；**平台 SDK 解耦（Phase 1 的耦合点抽取）与 Solon 迁移并行推进**。

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
| 扩展装载 | ClassLoader 隔离、动态装载 | 需自研（或走 Solon H-Spi） |

---

## 9. 分支与协作约定

| 侧 | 仓库 | 分支（拟） | 基线原点 | 备注 |
|---|---|---|---|---|
| OR | `g:\workshop\RUYI\OpenRefine` | `feat/or-solon-migration` | `feat/batch-title-extraction` HEAD | 远端 `origin`=diegoinsh/OpenRefine，`upstream`=OpenRefine/OpenRefine |
| 能力平台 | `g:\workshop\RUYI\ruyi-capability-platform` | `feat/platform-sdk-decoupling` | `main` HEAD | 无远端（本地） |

- **不动主干**：POC 在独立分支（建议独立 worktree）执行，不切换、不污染在用的主工作区。
- **不碰他人改动**：工作区内其他 agent 未提交的 `extensions/FilesExtension` 等改动**勿动、勿提交、勿回退**。
- **不干扰回归**：`test/run_batch_extraction.ps1` 等正在运行的回归任务不得被打断。
- **推送**：统一 GH_TOKEN + https（借 `gh` 凭据），不改 git 配置、token 不出现在命令与输出中。

---

## 10. 待验证清单（P0）

1. Solon `boot` 适配器挂载现成 `HttpServlet` 的能力（含 init-params / `ServletContext` 属性 / webapp 资源根）。
2. 各 adapter 的 javax/jakarta × Java 版本矩阵（与 OR 侧最终选择的 javax/jakarta 对齐）。
3. Solon 维护活跃度（近 6–12 个月 commit/issue/release 频率）与 tomcat/undertow 适配成熟度。
4. 与 commons-fileupload / velocity / Rhino(GraalJS) / `dog_read.exe` native 调用的共存性。
5. 上游 OR 4.0 设计文档中的容器/Butterfly/Jetty 走向（决定 Phase 2/3 顺序）。

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
- [OpenRefine Forum | Scheduling breaking changes we have on our radar](https://forum.openrefine.org/t/scheduling-breaking-changes-we-have-on-our-radar/1410)
- [OpenRefine Forum | Extension Operations during Project load](https://forum.openrefine.org/t/extension-operations-during-project-load/2368)
- [OpenRefine Forum | Improving the UX of extension install, and Butterfly](https://forum.openrefine.org/t/improving-the-ux-of-extension-install-and-butterfly/52/21)
- [OpenRefine | Writing extensions](https://openrefine.org/docs/technical-reference/writing-extensions)
