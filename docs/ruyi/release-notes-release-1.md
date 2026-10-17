# JinRefine Release-1 发布说明（候选）

- **版本**：JinRefine 3.11.0.01（Release-1 RC）
- **日期**：2026-10-17
- **范围**：R1-0 可交付基线、R1-1 AIMP 传输收口、R1-2 平台 SDK 语义、R1-3 进程内授权门
- **状态**：可交付候选（本地构建与冒烟通过，未推送）

## 1. 交付物

| 交付物 | 路径 | 说明 |
|---|---|---|
| Windows 全量包 | `packaging/target/jinrefine-win-with-java-3.11.0.01.zip` | 含 JRE，约 276 MB |
| Linux 全量包 | `packaging/target/jinrefine-linux-3.11.0.01.tar.gz` | 含 JRE，约 234 MB |
| Windows 启动器 | `packaging/target/openrefine_with_jre.exe` | 薄启动器，需与完整目录配套 |
| 能力平台 | `ruyi-capability-platform/target/ruyi-capability-platform.jar` | Solon 外壳（含 SDK） |
| 合规插件 | `plugins/compliance-plugin/target/compliance-plugin.jar` | shaded，投放 `ext/` |

打包产物内已确认包含：

- `webapp/extensions/FilesExtension/module/MOD-INF/lib/files-engine-3.11.0.01.jar`
- `webapp/extensions/data-quality/module/MOD-INF/lib/data-quality-engine-3.11.0.01.jar`
- `webapp/WEB-INF/lib/ruyi-platform-sdk-0.1.0.jar`

## 2. 本版内容

- **AIMP 传输单一实现**：新增 SDK `AimpTransport` / `HttpAimpTransport`，OR 侧 4 处直连（抽取、内容检查、图像巡检、连接测试）全部收口；端点、字段、multipart 统一经 SDK；请求字节级回归通过。
- **平台语义统一**：模块词表、开发授权快照、OR 命令审计白名单全部取自 SDK（`ModuleNames` / `ModuleCodes` / `OrContract`）。
- **进程内授权门**：平台能力入口（`AimpClient`）新增 `CapabilityGate` / `GuardedAimpClient`，插件直调 AIMP 也不能绕过加密狗授权；缺省关闭，行为零差异。

## 3. 验证结果

| 项目 | 结果 |
|---|---|
| SDK | 36/36 |
| OR core | 215/215 |
| data-quality-engine | 3/3 |
| files-engine | 33/33 |
| FilesExtension | 87/87 |
| data-quality 扩展 | `mvn test` BUILD SUCCESS（无测试；配置已修复） |
| OR 10 模块 `install -DskipTests` | SUCCESS |
| `packaging -am package` | SUCCESS（exe / zip / tar.gz） |
| 平台 + 合规插件 | 打包 SUCCESS |
| OR 启动冒烟 | `/`、`get-version`、`data-quality/get-config`、`files/extraction-config`、`get-quality-rules`、`wirings.js`、`get-languages` 均 200 |
| 批量抽取契约 | 返回预期 `files-importing/aimp-unavailable`（AIMP 未启动） |
| 平台启动冒烟 | `/` 200、`/api/auth/me` 200；日志 `capability-gate=off` |

## 4. 开关与回退

| 开关 | 缺省 | 说明 |
|---|---|---|
| `refine.module.loader` | `butterfly` | Release-1 冻结，不切换 Solon 插件装载 |
| `ruyi.license.enforce` | `off` | `off` / `log` / `block` |
| `ruyi.platform.capability.gate` | `off` | 平台能力入口授权门，`off` 不读狗 |
| `ruyi.data-quality.view` | `live` | `live` / `snapshot` |

本地回退锚点：

- `r1-0-baseline-20261017`（OR `34ecfa0cb`）
- `r1-1-aimp-transport`（OR `7d3b12cbf`）
- `r1-2-sdk-semantics`（OR `8e208d46a`）
- R1-3：平台 `b4f70bf`、OR `b1b61fd52`（按选择未打标签）

## 5. 已知问题（不阻塞发布）

1. **真实 AIMP 抽取未回归**：本次环境未启动 AIMP；已验证命令/CSRF/错误契约与请求字节形状，端到端抽取需在联调环境复跑。
2. **平台无单元测试**：以构建、启动、授权门冒烟与过滤器状态码验证。
3. **启动日志噪声**：已禁用的 `database` 扩展仍会打印 `ClassNotFoundException`，属既有行为。

## 6. 不在 Release-1 范围

- Butterfly 退场与装载器自主化（`wirings.js`/i18n/客户端资源/去 `extends Butterfly`）。
- `records-db`/`records-assets` 的 Solon 描述符、`jython`/`wikibase`/`pc-axis` 的 SPI 化。
- AI harness。
- 合规任务/结果查询的进程内化（当前仍走 SSO → OR HTTP 适配）。
- `import-from-local-dir` 前端 Java 化、`BatchExtractionManager`/`ExtractionTaskRegistry` 下沉、SSO 转发下沉 SDK、jakarta 迁移。
