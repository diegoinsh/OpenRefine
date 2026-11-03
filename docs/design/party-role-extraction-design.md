# 原告 / 被告 稳定提取方案（评审稿）

> 目标：在**不改动现有抽取主流程**的前提下，为法院诉讼档案卷宗稳定产出「原告」「被告」两项卷级要素，
> 并让它们在卷级目录、RCP 对外接口返回值、定制接口字段组合（`{原告}` / `{被告}`）中都能直接使用。
>
> 本文档为方案评审稿，评审通过后再实施。涉及仓库：`ruyi-aimp`（抽取）、`OpenRefine/FilesExtension`（卷级汇总与列）、
> `ruyi-capability-platform`（模板默认项与对外接口）。

---

## 一、现状梳理（原告/被告在链路中走到了哪一步）

### 1.1 页级抽取（ruyi-aimp）

- 提示词第 7 条「当事人」**刻意只取姓名本身**，并明确要求剥掉「原告 / 被告 / 上诉人 / 被上诉人」等诉讼地位字样；
  页面上只有称谓没有姓名时留空。因此**页级 LLM 输出天然不含角色**（这是既有设计，不能直接改）。
- 角色信息的现有来源是 `pipeline_manager._fill_party_roles()`（纯规则，非 LLM）：
  - 触发条件：仅「卷宗封面」——页号 ≤ 3 且命中 ≥4 个封面栏目标签（`_COVER_LABELS`）；
  - 做法：对 OCR 全文跑正则 `_PARTY_ROLE_PATTERN`（`(原告|被告|上诉人|被上诉人|申请人|被申请人|第三人)\s*[:：]\s*(姓名片段)`）；
  - 产物：页级结果里追加内部键 `party_roles`（JSON 字符串，confidence 0.9），**不落任何数据列**
    （`BatchExtractionCommand` 明确「不追加列名未知的键」）。
- 结论：**随案卷宗（未整理、无封面）拿不到 `party_roles`**；封面缺失时整卷无角色来源。

### 1.2 卷级分件（OpenRefine FilesExtension）

- `BatchExtractionManager.firstPartyRoles(pageValues)` 取页级 `party_roles` 第一个非空值；
- 仅用于 `_party_side()` 区分「原告身份证明 / 被告身份证明」等按方成件的场景，**不参与卷级要素汇总**。

### 1.3 卷级汇总（BatchExtractionManager.VolumeSummary）

- 只累加 `ExtractionTemplate.VOLUME_LEVEL_ELEMENT_KEYS` 里的 7 个键：
  `anyou`、`dangshiren`、`shenji`、`jiean_fangshi`、`baoguan_qixian`、`miji`、`kaifang_zhuangtai`；
- `party_roles` 不在其中，**卷级行没有原告/被告的数据来源**。

### 1.4 对外接口（ruyi-capability-platform）

- 卷级行通过 `ExtractionService.volumes()` 原样回传（OR `extraction-result` 的列名 → 行值）；
- `TitleRenderer` **已经实现** `{原告}` / `{被告}`：
  - `{原告}` → `volume.get("原告")`，`{被告}` → `volume.get("被告")`；
  - `{当事人}` 还会用件级「责任者」就近匹配卷级原告/被告名单，拼成「原告+姓名」；
- `TitleDecomposer` 反算同样接收卷级行，可识别卷级变量；
- 但**卷级行当前不存在「原告」「被告」两列**，所以这两个变量恒为空。

### 1.5 缺口归纳

| 缺口 | 表现 |
|---|---|
| G1 触发面太窄 | `party_roles` 只在封面抽，随案卷/无封面卷永远为空 |
| G2 正则覆盖不足 | 只认「角色:姓名」；「原告郭长海」这类无冒号栏、或角色与姓名换行的情况漏掉 |
| G3 卷级无聚合 | 即使页级抽到 `party_roles`，卷级汇总也不读，卷级行无列 |
| G4 接口无值 | `{原告}`/`{被告}` 已支持但取不到值 |

---

## 二、规则可行性结论（实测）

### 2.1 实测方法

对 `g:\workshop\mock-data-gen\test\digest_real_*.json`（9 卷逐页 OCR 文本）统计：
行首角色词 + 冒号/空格 + 短姓名片段（≤30 字、剔除代理人/法定代表人/辩护人等行）的命中情况。

### 2.2 实测结果

| 卷 | 页数 | 命中页 | 角色行 | 角色分布 | 主要命中件 |
|---|---|---|---|---|---|
| 0158（二审·随案） | 61 | 2（3%） | 7 | 上诉人4 / 被上诉人3 | 民事上诉状、调解笔录 |
| 0241（二审·完整） | 61 | 2（3%） | 7 | 上诉人4 / 被上诉人3 | 民事上诉状、调解笔录 |
| 0287（二审） | 48 | 7（15%） | 23 | 上诉人14 / 被上诉人8 / 被告1 | 庭审笔录、原审判决书、上诉书、宣判笔录 |
| 0316（二审·举证卷） | 105 | 9（9%） | 25 | 上诉人14 / 被上诉人9 / 被告1 / 申请人1 | 庭审笔录、原审判决书、上诉书、仲裁裁决 |
| 0852（二审·改判） | 106 | 9（8%） | 25 | 同上 | 同上 |
| 1638（一审·调解） | 41 | 4（10%） | 8 | 原告3 / 被告5 | 民事起诉状、民事调解书、结案登记表 |
| 1836（一审） | 51 | 4（8%） | 8 | 原告3 / 被告5 | 民事起诉状、民事判决书、结案登记表 |
| 2158（一审） | 51 | 4（8%） | 8 | 原告3 / 被告5 | 民事起诉状、民事判决书、结案登记表 |
| 4821（一审） | 52 | 9（17%） | 29 | 原告12 / 被告17 | 庭审笔录、民事起诉状、判决书、宣判笔录 |

### 2.3 结论

1. **每卷都能命中角色栏**，一次命中即可确定整卷双方名单（角色栏是同一批名字重复出现）。
2. 角色栏集中在「起诉状 / 上诉状 / 判决书 / 调解书 / 结案登记表 / 庭审·宣判·调解笔录」等**必有文种**上，
   不依赖模型判断，OCR 文本即可正则召回。
3. 一审卷稳定产出「原告 / 被告」；二审卷稳定产出「上诉人 / 被上诉人」。
4. 因此 **规则路径可行，且不需要改 LLM 提示词**。真正要补的是「触发面」「正则覆盖」「卷级聚合」三处。

> 备注：0158 / 0241 的原审判决书页未进入命中件列表（该页角色行存在缩进/折行），但上诉状已稳定命中，
> 说明「每卷至少一处命中」的结论成立；扩展规则时需把「行首缩进 ≤ 2 空格」和「角色与姓名折行」一并纳入。

---

## 三、推荐方案（方案 A：纯规则，零提示词改动）

### 3.1 AIMP：把 `party_roles` 从「仅封面」扩到「角色栏页」

改造点集中在 `pipeline_manager._fill_party_roles()` 与调用点（`_fill_special_page_title`），**不改提示词、不改请求结构、不改页级输出列**。

**触发策略（最终实现口径，2026-11-02）**

- 保留封面路径（最高优先，命中即 `party_roles`，confidence 0.95）；
- 新增「文书首部角色栏」路径（任意页，confidence 0.85），逐行解析，满足全部条件才采信：
  1. 角色词位于**行首**或**紧挨分隔符**（`，,；;、` 或空格），正文里的「本院认为，原告…」不算；
  2. 角色词后紧跟 `：`/`:`（**冒号形式**，任意页可用）；
  3. **无冒号形式仅封面放行**（封面可能写成「原告 郭长海」）——文书正文里没有冒号的角色词
     一律不采信，避免「原告郭长海不服本院判决」被当成角色栏；
  4. 行长度 ≤ 80 字、单个姓名 token ≤ 20 字；
  5. **笔录 / 质证 / 核对 / 证据 / 清单 / 送达回证 / 发票 / 缴款 / 收据 / 身份证 /
     营业执照 / 执业证** 类页面**整页跳过**：这些页面的行首角色词是「发言人标记」或证据陈述，
     取值不是姓名（评审稿曾把笔录列入白名单，实测会大量误抽，最终改为整页跳过）；
  6. 姓名 token 按分隔符（`、，,；;：: 空白`）逐个校验：丢掉含数字/日期、性别/住址/证件等
     描述性片段（`出生/公民身份/身份号码/住所/住址/户籍/性别/年龄/工作单位/职务/律师/委托/代理/汉族/民族/法定代`）、
     陈述性用语、动词连接词（`诉称/辩称/不服/提交/认为/上诉/申请/本院/依法/判决/…/与/及/等`）
     以及含 `。！？《》()（）` 的片段。
- 括注别名同时记入一个角色：`上诉人（原审原告）：袁连顺` → `上诉人` 与 `原审原告` 都记；
  括注不在角色词表（如「委托代理人」）时只记主角色。

**角色词表**

```
原审原告 / 原审被告 / 被申请执行人 / 申请执行人 / 被上诉人 / 被申请人 / 被执行人 /
反诉原告 / 反诉被告 / 公诉机关 / 上诉人 / 申请人 / 自诉人 / 被害人 / 被告人 / 第三人 /
原告 / 被告
```

**跨页合并**

- AIMP 侧**不做跨页合并**：每页产出自己的 `party_roles`（页级内部键，JSON 字符串，
  `confidence` 封面 0.95 / 文书首部 0.85），由 OpenRefine 卷级汇总跨页/跨件归并（见 §3.2）。
  这样同一件内多份文书（判决书＋送达回证）各自的角色栏都能进入候选页，召回更完整。

**零提示词增量机制**

- `ruyi-capability-platform` 的模板 `customElements` 里的 key 会被注入 AIMP 提示词，
  若直接加 `yuangao` / `beigao` 会新增 LLM 输出要求；
- 因此把这两个 key 加入 `src/processors/extraction_processor.py` 的
  `RULE_INFERRED_ELEMENTS = ('shenji', 'yuangao', 'beigao')`：AIMP 在构造「附加要素」清单与
  期望输出键时**剔除**它们，既不进提示词、也不占 decode token；
- 页级结果里同时**不写** `yuangao` / `beigao`（只写内部键 `party_roles`），
  卷级两列完全由 OpenRefine 汇总产出。

**成本**：纯正则，逐页 O(文本行数)，无 LLM 调用、无 token 增长；对既有页级字段零影响。

### 3.2 OpenRefine：卷级聚合与新增两列

**聚合输入**

- 页级 `party_roles`（AIMP 逐页产出，这里按件扫描**全部页候选**后跨页/跨件归并）；
- 件级最终 `dangshiren`（已过围栏、已做错别字归并、题名/责任者已定稿）。

**聚合规则（最终实现口径）**

0. **累加时机**：`VolumeSummary.add()` 里在卷级要素循环之前单独调用 `collectPartyRoles(values, candidates)`；
   解析的是**该件的全部页候选**（`candidates["party_roles"]`），不是件级合并后的单一值——
   件级合并只保留置信度最高那一页，会漏掉一件内多份文书的角色栏。
   每个姓名按「姓名 → 出现过的件序号集合」记录（同一件多页只算一件）。
1. **每列只取第一个非空角色**的名单（按 §3.4 的角色顺序，保序），**不跨角色并集**：
   二审卷的「上诉人」可能原本是被告，与「原审原告」并成一列会把双方混起来。
2. 名字格式复用件级规范化：全角逗号分隔、去重保序。
3. 同一名字同时落在原告 / 被告两侧：比较它在两侧角色集合里**出现过的件数**，
   **>（严格大于）**对方才保留，平票两列都丢（宁空勿错），并记 info 日志。
4. **与件级最终当事人名单交叉校验**（`keepKnownParties`）：

   - 卷级 `partyNames` 整卷为空 → 不校验，角色栏就是唯一来源；
   - 姓名精确命中 → 采用；
   - 否则只在「等长且编辑距离 = 1」的近邻里找：**唯一近邻**按名单正字替换（手写材料错字），
     多个近邻或无匹配 → 丢弃（防 OCR 幻觉/正文误抽）。
5. 整卷一个角色都判不出时：该列留空（不臆造），走 `missingFields` 语义。

**来源页定位**

- 角色栏是页级内部键、不在卷级要素清单里，来源页登记在 `party_roles` 名下
  （`collectElementPages("party_roles", name, candidates, name)`，按「候选取值包含该姓名」筛选）；
- `pageSourcesFor("yuangao"/"beigao")` 回落到 `party_roles` 取值，并按全角逗号拆成多个姓名
  逐个合并去重，使卷级两列单元格可点击定位到角色栏所在页。

**列定义**

- `ExtractionTemplate.VOLUME_LEVEL_ELEMENT_KEYS` 增加 `yuangao`、`beigao`；
- `VOLUME_SUMMARY_COLUMNS` **不动**（要素列由模板 `customElements` 动态生成，新 key 自动出列）；
- `VolumeSummary` 增加两个聚合字段与来源页收集（复用 `collectElementPages`，
  按「名字包含」匹配页级候选），使卷级「原告 / 被告」单元格可点击定位到角色栏所在页。

**件级归属（统一逻辑，2026-11-02 追加）**

卷级名单定稿后，再给**每一件**算出该件自己的「原告」「被告」，供件题名格式化与按件区分使用
（询问笔录等每位当事人单独一份的材料因此能逐件区分）：

- 件内「出现过的姓名」来源三处：件级 `dangshiren`、该件各页级 `dangshiren` 候选、
  该件各页 `party_roles` 角色栏姓名；
- 与卷级最终名单**求交**（`piecePlaintiffs = 件内姓名 ∩ 卷级原告`，被告同理），顺序沿用卷级名单顺序；
- 卷级名单为空时件级也留空，不臆造；
- 件级两列为空是正常结果（本件与该角色无关），**不进 `missingFields`**；
- 落列 key：`piece_yuangao` / `piece_beigao`（件级要素，进 `sheet=inner`），
  只有模板登记了这两项才建列；旧项目/旧模板行为不变；
- `applyPiecePartyColumns` 在 `rebuildVolumeSummary` 写完卷级行后按件行号回填；
  件行号由 `writePieceRow → accumulateVolumeSummary → VolumeSummary.add(..., rowIndex)` 贯通。


**模板默认项**

`ruyi-capability-platform/src/main/resources/template-defaults/extraction-default.json`
的 `customElements` 增加两项（默认 include）：

```json
{ "name": "原告", "key": "yuangao", "action": "include",
  "description": "提取原告（或原审原告/上诉人）姓名或名称，多个用全角逗号分隔" },
{ "name": "被告", "key": "beigao", "action": "include",
  "description": "提取被告（或原审被告/被上诉人）姓名或名称，多个用全角逗号分隔" }
```

> 说明：`dangshiren` 的 description 里「并注明诉讼地位」与现行提示词（剥角色）不一致，本次不动，
> 只在原告/被告两项上明确角色口径。

### 3.3 接口返回与定制字段组合

**对外返回值**

- 卷级行（`sheet=volume`）自动多出 `"原告"`、`"被告"` 两列，值即上述聚合结果（全角逗号分隔）；
- 模板登记 `piece_yuangao` / `piece_beigao` 时，件级行（`sheet=inner`）多出 `"原告"`、`"被告"`
  （件内当事人与卷级名单求交，见 §3.2）；未登记时件级结构不变；
- 卷级空值走既有 `missingFields`（`[{key,name}]`）语义，不视为抽取失败；件级空值不进 `missingFields`。

**定制字段组合**

- `TitleRenderer` 的 `{原告}` / `{被告}` 在件级规则中**件级列优先、卷级兜底**，
  卷级规则仍取卷级列；列一出现即生效；
- `{当事人}` 的「责任者就近匹配原告/被告」逻辑同样自动生效（`roleOf()` 已实现）；
- `TitleDecomposer` 反算可继续使用 `volume` 中的新列做锚定；
- 注意既有口径差异：卷级列用**全角逗号**，`TitleRenderer.names()` 输出时统一为**顿号**——
  这是既有行为，本次不改，避免影响现有定制规则输出。

### 3.4 审级 / 案件类型口径映射（关键决策）

| 场景 | 原告列 | 被告列 |
|---|---|---|
| 民事 / 行政 一审 | `原告` | `被告` |
| 民事 / 行政 二审 | 优先 `原审原告`；取不到时用 `上诉人` | 优先 `原审被告`；取不到时用 `被上诉人` |
| 刑事自诉 | `自诉人` | `被告人` |
| 刑事公诉 | `公诉机关` | `被告人` |
| 执行 / 其他 | `申请执行人`（无则 `申请人`） | `被申请执行人`（无则 `被执行人`） |

实现为两个集中式常量（在 `BatchExtractionManager.VolumeSummary` 里，按顺序取**第一个非空角色**）：

```java
PLAINTIFF_ROLE_ORDER = [公诉机关, 自诉人, 原告, 原审原告, 上诉人, 申请执行人, 申请人]
DEFENDANT_ROLE_ORDER = [被告人, 被告, 原审被告, 被上诉人, 被申请执行人, 被执行人]
```

调整二审 / 刑事 / 公诉口径时**只改这两个列表**（顺序即优先级），不动抽取与汇总主流程。

- 依据：`party_roles` 的键本身就是角色名，按上表归并即可；
- 二审「上诉人 / 被上诉人」不等于一审的「原告 / 被告」（上诉人可能是原审被告），
  所以优先取原审文书中的角色栏，取不到才退回上诉人称谓，并在日志中留痕。

---

## 四、可选方案 B：提示词搭车（默认关闭，仅作兜底）

> 仅在 A 方案判不出、且业务明确要求「必须有值」时才启用；**默认关闭**，不影响现有响应时间。

**做法**：在同一次当事人抽取里多输出一个键，不新增请求、不新增字段说明：

页级输出 JSON 增加一项：

```json
"party_roles": {"原告": ["姓名"], "被告": ["姓名"]}
```

提示词只在原第 7 条末尾追加一句（约 35 字）：

```
若本页有"原告：/被告："等角色栏，另输出 party_roles（无角色栏时给 null）；只照录栏内姓名，不得从正文推断。
```

**成本评估**

- prefll：约 +35 token（提示词长度增量 <1%）；
- decode：每页多输出约 10–20 token，页均耗时增幅约 1%–3%；
- 风险：模型会从正文「原告称……」推断角色 → 必须复用 A 方案的**行首/短名/白名单校验**，
  校验不过按 null 处理（校验是纯规则，可完全复用）。

**开关**：`party_role_llm_fallback: false`（配置项），A 方案上线后先不做。

---

## 五、兼容性与回归边界

1. **不改抽取主流程**：页级请求/响应结构、件级列、卷级既有 7 个要素的取值逻辑全部不变；
   新增的只是 `party_roles` 的触发面（内部键）与两个卷级要素列。
2. **不改既有当事人语义**：`dangshiren` 仍只存姓名、不带角色、全角逗号；错别字归并与低频去噪逻辑不动。
3. **非诉讼档案不受影响**：`_fill_special_page_title` 已按 `archive_sub_category` 以「诉讼档案」结尾限定；
   其他门类（文书、科技等）走不到 `party_roles` 分支。
4. **存量项目**：模板 `customElements` 已固化在项目 metadata，新建项目才自动带「原告 / 被告」列；
   存量项目不自动加列（不迁移、不重算），避免破坏已验收数据。
5. **回归基线**：0158 / 0241 / 4821 三卷重跑，逐项比对件级题名/页区间/既有卷级要素，
   要求除新增两列外**其余字段零变化**。

---

## 六、实施步骤（评审通过后）

1. **AIMP**
   - `pipeline_manager._fill_party_roles`：触发面扩展为「封面 + 文书首部角色栏页」，加 §3.1 的 6 条约束；
   - 跨页合并与冲突消解；日志记录命中页与丢弃原因；
   - 单测：`test/` 下新增角色栏抽取用例（含正文「原告不服」负例、核对页正例、跨角色冲突）。
2. **OpenRefine**
   - `ExtractionTemplate.VOLUME_LEVEL_ELEMENT_KEYS` 增 `yuangao` / `beigao`；
   - `VolumeSummary` 增聚合、来源页、与件级名单交叉校验；
   - `VolumeSummaryTest` 增原告/被告用例（含缺封面、二审角色、冲突丢弃）。
3. **RCP 平台**
   - `extraction-default.json` 增两项模板默认要素；
   - 验证 `missingFields` 自动包含新列（如实现是从「卷级要素列清单」推导，则无需改代码）；
   - `SSO-Integration-Guide.md` §4.8 卷级要素列表补「原告 / 被告」，
     并说明「上诉人 / 被上诉人」归并口径与空值语义（附加式变更，不升 `api_version`）；
   - `examples/sso-demo` 的 volume 样例暂不补两列（已入库样例是旧项目真实导出；
     新项目响应多出两列，按列名取值即可，README 已写明）。
4. **验证**
   - 三卷回归（0158/0241/4821）＋ `VolumeSummaryTest` ＋ AIMP 单测；
   - 定制接口实测：配置 `{原告}、{被告}` 变量，确认输出与卷级列一致。

---

## 七、决策结论（2026-11-02 已确认）

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 二审 / 刑事的角色口径 | **按 §3.4 映射表归并**：二审优先「原审原告/原审被告」（来源原审判决书等原审文书），取不到再退回「上诉人/被上诉人」；刑事自诉「自诉人/被告人」、公诉「公诉机关/被告人」；执行「申请执行人/被申请执行人」 |
| D2 | 存量项目补列 | **只对新建项目生效**；存量项目不迁移、不重算，避免破坏已验收数据 |
| D3 | LLM 搭车兜底（方案 B） | **暂不做**，保持默认关闭；等真实客户卷宗出现规则判不出的样本再评估 |
| D4 | 卷级列名 | **固定两列「原告」「被告」**；刑事/二审实际角色按 §3.4 归入，接口文档说明口径 |

> 后续调整提示：D1 的映射表是集中式常量（见实施时的角色归并表），二审、刑事、公诉等口径若需调整，
> 只改映射表与接口文档，不动抽取与汇总主流程。

---

## 八、实施记录（2026-11-02 完成）

### 8.1 代码改动清单

| 仓库 / 文件 | 改动 |
|---|---|
| `ruyi-aimp/src/core/pipeline_manager.py` | `_fill_party_roles` 重写为逐行解析；新增 `_parse_role_lines`、`_role_name_tokens`、`_role_markers` 与角色/描述/陈述词表；调用点 `_fill_special_page_title` 先按页题名调一次（`cover=False`），封面分支再以 `cover=True` 调一次（封面置信度 0.95 覆盖） |
| `ruyi-aimp/src/processors/extraction_processor.py` | `RULE_INFERRED_ELEMENTS = ('shenji', 'yuangao', 'beigao', 'piece_yuangao', 'piece_beigao')`，这些 key 不进提示词、不占 decode token |
| `OpenRefine/.../ExtractionTemplate.java` | `VOLUME_LEVEL_ELEMENT_KEYS` 增 `yuangao`、`beigao`；`VOLUME_SUMMARY_COLUMNS` 未动（卷级列由 `customElements` 动态生成） |
| `OpenRefine/.../BatchExtractionManager.java` | `VolumeSummary` 增 `roleNamePieces`、角色顺序常量、`collectPartyRoles`、`parsePartyRoles`、`resolvePartyRoles`、`firstRoleNames`、`rolePieceSet`、`keepKnownParties`、`canonicalPartyName`、`putPartyColumn`；`pageSourcesFor` 回落 `party_roles`；`add()` 中单独累加角色栏 |
| `OpenRefine/.../module/scripts/index/import-from-local-dir.js` | `Refine.EXTRA_ELEMENTS` 增「原告」「被告」两项（默认勾选，提示词为空说明口径） |
| 前端 i18n / 抽取规则对话框 | `el-plaintiff`、`el-defendant`（含 prompt 说明）、`LABELS.yuangao/beigao` |
| `ruyi-capability-platform/.../template-defaults/extraction-default.json` | `customElements` 在「当事人」后增 `yuangao` / `beigao`（include） |
| `ruyi-capability-platform/docs/SSO-Integration-Guide.md` | §4.8 卷级列表与示例补「原告 / 被告」、归并口径表、空值语义；§4.8.1 说明卷级定制规则可引用 `{原告}` `{被告}` |
| `examples/sso-demo/README.md` | 说明新增两列、`missingFields` 语义；样例为旧项目导出（不含两列） |
| `OpenRefine/test/run_batch_extraction.ps1` | `$CourtCustomElements` 增 `yuangao`/`beigao`（直接驱动 OR 的系统测试用；描述留空） |
| `ruyi-capability-platform/.../static/app.js` | 模板编辑界面：`yuangao`/`beigao` 的描述框清空并禁用，占位提示「规则汇总，不调用模型（暂未接线）」 |
| `ruyi-capability-platform/.../customization/TitleRenderer.java` | `{原告}` / `{被告}` 改为**件级优先、卷级兜底**（`firstNames(row, volume, key)`） |
| `ruyi-capability-platform/.../template-defaults/extraction-default.json` | 件级再增 `piece_yuangao` / `piece_beigao`（include，描述留空） |
| `OpenRefine/test/run_batch_extraction.ps1` | `$CourtCustomElements` 再增 `piece_yuangao`/`piece_beigao` |
| `ruyi-aimp/.../extraction_processor.py` | `RULE_INFERRED_ELEMENTS` 增 `piece_yuangao` / `piece_beigao`（同样不进提示词） |
| `OpenRefine/.../BatchExtractionManager.java`（件级归属） | `VolumeSummary` 增 `piecePlaintiffs` / `pieceDefendants` / `piecePagePartyNames` / `pieceRowIndexes`；新增 `collectPiecePageParties`、`resolvePiecePartyColumns`、`pieceHasName`、`applyPiecePartyColumns`；`add()` 增带 `rowIndex` 重载并透传 |

### 8.2 测试

- `VolumeSummaryTest`：新增 6 个用例（一审角色栏、二审原审优先、冲突/未知名丢弃、当事人为空时不校验、卷级页定位、未归并噪声不得放行角色栏），全套通过；
- `ruyi-aimp/test/test_volume_level_element_constraints.py` 第 18 段「角色栏抽取」16 项：全套 155/155 通过。

### 8.4 健壮性补强（2026-11-02）

1. **签名/盖章栏不采信**（AIMP）：`_ROLE_SIGNATURE_PAREN_WORDS = ('签名','签字','签章','盖章','捺印')`。
   `_parse_role_lines` 遇到角色词**紧后括注**含这些词时跳过该标记——落款「被告（签名）：王振华」是签署位，
   不是当事人身份栏。只认紧后 0–12 字括注，所以「被告：王振华（签名）」不受影响。
   - 实测：SZ05-2025-M1-4821 第 27 页（件「协议」）`party_roles` 把法定代表人王振华记成被告，修复后消失。
   - 边界：整卷只有落款签名栏、没有首部身份栏的极端件会漏抽该角色；卷级跨件累积 + 下述第 2 条校验兜底。
2. **交叉校验基线同源**（OpenRefine）：`resolvePartyRoles(knownParties)` 接收 `mergePartyNames` 之后的最终名单，
   `keepKnownParties` / `canonicalPartyName` 不再读未归并的 `partyNames`。此前卷级「当事人」列已按件间重复度
   剔除的单次噪声，仍能作为基线放行角色栏姓名（王振华即由此混入）。名单为空时依旧不校验。
3. **回归（RCP 第三方接口方式，项目 REG-YG3-1102d/1102e）**：4821 被告恢复为「苏州恒盛精密机械有限公司」；
   0158「郭长海，郭秀萍 / 郭秀梅，郭建军」、0241「袁连顺，袁凤莲 / 袁凤仙，袁凤鸣」不变。

### 8.5 原告 / 被告提示词：未接线（重要）

- `RULE_INFERRED_ELEMENTS = ('shenji','yuangao','beigao')`：`extraction_processor` 组装请求时会把这三个 key
  从 elements 列表剔除（L2109）并跳过附加要素清单（L2143），**不进提示词、不占 decode token**。
  因此 `customElements` 里 `yuangao`/`beigao` 的 `description` 只是占位文案，**不会发给模型**。
- 防误解措施：RCP 模板库（`extraction-rules-01`）与内置默认模板 `extraction-default.json` 的这两项 `description`
  已置空；模板编辑界面描述框清空并禁用；`test/run_batch_extraction.ps1` 同步留空。
- 后端不因空描述报错：平台 `saveExtractionTemplate` 不校验 `description`；OR `CustomElementType.validate`
  仅对 `action=adjust` 要求非空；AIMP 直接忽略这两个 key 的描述。
- **以下两段文案仅存档备查，未实施，勿当作已生效提示词**：
  - 原告：由文书首部角色栏纯规则汇总（不调用模型）：一审取「原告」，二审优先「原审原告」、取不到再退回「上诉人」，刑事自诉取「自诉人」、公诉取「公诉机关」；多个姓名用中文全角逗号隔开。
  - 被告：由文书首部角色栏纯规则汇总（不调用模型）：一审取「被告」，二审优先「原审被告」、取不到再退回「被上诉人」，刑事取「被告人」；多个姓名用中文全角逗号隔开。

### 8.6 已知边界（口径约束，不是缺陷）

1. 二审卷若卷内**没有**原审文书（只有上诉状/答辩状），按 D1 退回「上诉人 / 被上诉人」栏目，
   此时「原告」列实际是上诉人——上诉人可能是原审被告，方向可能与真实诉讼地位相反；
2. 整卷当事人抽取为空时，角色栏直接采信、不做交叉校验；
3. 件级「原告」「被告」是**件内当事人与卷级名单的交集**；卷级判错时件级会跟着错（同一口径，不另起判断）；
4. 方案 B（LLM 搭车兜底）未实现，开关不引入。

