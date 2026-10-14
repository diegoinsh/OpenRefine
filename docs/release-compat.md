# 两端版本对应与发布约定（OpenRefine × ruyi-aimp）

本项目的「诉讼档案批量自动著录」等功能由两个仓库配合完成：OpenRefine（JinRefine）侧负责界面、
分件编排与卷级汇总，ruyi-aimp 侧负责 OCR / 大模型提取。任一方单独升级都可能让功能失效，
因此需要一套「以后能找回某个版本」的机制。

此前的问题：两边版本号各自演进、互不相关，且 AIMP 内部就有 1.0.0 / 2.0.0 两套；调用方
连上服务就直接开跑，从不校验对端版本——版本不配套时表现为一连串莫名其妙的运行时错误
（少字段、行为不一致），排查困难。

## 一、三层对齐机制

三层各解决一个问题，缺一不可：

| 层 | 解决什么 | 落地方式 |
|---|---|---|
| ① 接口契约版本 | 两边**能不能一起跑** | AIMP `/health` 返回 `api_version`；OR 侧 `AimpLlmClient.SUPPORTED_API_VERSIONS` 校验，不匹配直接给明确提示 |
| ② 构建元数据 | **是哪个构建/提交** | AIMP `/health` 与启动日志带 `version` + `git_sha`；OR 侧 jar MANIFEST 带 `Implementation-Version` + `Git-Sha` |
| ③ 发布批次 | 以后**怎么找回来** | 两边打**同名 tag**，并在本文的兼容矩阵里记一行 |

**为什么不用「强制同号」**：AIMP 是通用提取服务，未来可能被 OpenRefine 之外的前端复用，
把它的版本号绑死在某个前端上并不合适。契约版本 + 批次号已足够表达"这一对是配套的"。

## 二、接口契约版本（api_version）

- 定义处（唯一来源）：AIMP 侧 `src/__init__.py` 的 `__api_version__`
- 消费处：OR 侧 `AimpLlmClient.SUPPORTED_API_VERSIONS`
- 当前值：**2**

**必须递增 api_version 的情形**（任一发生即递增，并在本文矩阵中记录）：

- 请求/响应字段被删除、改名或语义变化（如 `/extract/upload` 的 `results` 结构、
  `/extract/split-pieces` 的件级返回结构）；
- 新增**必填**请求参数；
- 既有字段取值域变化导致旧调用方解析异常（如日期格式、当事人分隔符约定）。

仅新增可选字段、内部算法调整、提示词优化等**向后兼容**改动，不需要递增。

## 三、对端版本可见性

**AIMP 侧**

- `GET /health` 返回：`{ status, version, api_version, git_sha, timestamp, models_loaded, gpu_available }`
- 启动日志打印：`当前版本: 2.0.0（接口版本 v2，提交 b4946c2）`
- `git_sha` 由 `src/__init__.py:git_sha()` 读取当前仓库短 sha；打包分发（无 `.git`）时为 `unknown`

**OpenRefine 侧**

- 调用前握手：`AimpLlmClient.checkCompatibility()` 先 GET `/health`，一次判定「可用」与「版本配套」
- 不可达 → 提示「AI服务模块不可用，请检查或重启模块」
- 版本不匹配 → 提示「AI服务模块版本不匹配：本扩展要求接口版本 X，当前模块为 Y，请更新模块后重试」
- 握手成功 → 日志记录对端版本（`AIMP 服务握手成功：接口 v2，模块 2.0.0+b4946c2`）
- 构建产物：jar MANIFEST 带 `Implementation-Version`、`Git-Sha`
  （打包时以 `mvn -Dgit.sha=$(git rev-parse --short HEAD)` 注入）

## 四、兼容矩阵

发布时在下方追加一行；`git_sha` 取发布提交的短 sha。

| 发布批次 | OpenRefine | ruyi-aimp | AIMP 接口版本 | 说明 |
|---|---|---|---|---|
| 待发布（本次） | 3.11.0.01（扩展 0.1.0）<br>分支 `feat/batch-title-extraction` | 2.0.0<br>分支 `feat/extract-upload-enhance` | v2 | 诉讼档案卷级著录口径落地：卷内/卷级分表、汇总规则、点击定位、成文日期加权择优；两端首次引入契约版本握手 |

## 五、发布流程

1. 两端各自提交、确认联调通过；
2. 两端打**同名 tag**：`rel-YYYY.MM.DD`（例如 `rel-2026.09.28`）；
   - `git tag -a rel-2026.09.28 -m "..."` 后分别 `git push origin rel-2026.09.28`
3. 在本文「兼容矩阵」补一行：批次名、两端版本与 tag、AIMP 接口版本、变更要点；
4. 若本次改动了接口结构，先递增 AIMP `__api_version__` 并同步
   `AimpLlmClient.SUPPORTED_API_VERSIONS`，再发布。

## 六、排障时的定位顺序

1. 看 OR 侧提示或日志里的「接口版本」是否匹配 → 不匹配就是模块没更新，直接换模块；
2. 匹配但仍异常 → 用日志里的 `git_sha` 到两端仓库 `git show <sha>` 核对当时代码；
3. 需要知道"某个批次是什么组合" → 查本文兼容矩阵。
