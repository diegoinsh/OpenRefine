# -*- coding: utf-8 -*-
<#
批量提取「系统测试」驱动（HTTP 直驱 OpenRefine FilesExtension，不依赖界面点击）。

用途：对某个样本目录跑一遍批量提取，打印每轮进度/并发度/耗时，供法院 / 文书 / 科技
（PDF）三条链路做回归。与 `docs/test/BASELINE-2026-10-06.md` 的语料、真值锚点配套使用。

用法：
    & .\test\run_batch_extraction.ps1 -Preset ws
    & .\test\run_batch_extraction.ps1 -Preset court -ProjectName 'REG-法院-1015'
    & .\test\run_batch_extraction.ps1 -Preset keji
    & .\test\run_batch_extraction.ps1 -RootPath 'D:\xx' -ArchiveCategory '科技档案' -ProjectName 'REG-xx'

端点：
    默认经**能力平台（RCP，:8090）**的 OR 代理通道（/command/**），先读狗登录取
    X-Auth-Token，再驱动 OR —— 与产品真实调用栈一致（平台授权门 -> OR）。
    -DirectOr 时直连 JinRefine(OR) :3333（仅排障用，绕过平台授权门）。

预设（目录与门类取自 BASELINE 文档的同款语料）：
    ws    → D:\00_toubiao\自动著录\WS                                   文书档案
    court → D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\JZ07\2024            专业档案 / 人民法院诉讼档案
    keji  → D:\00_toubiao\自动著录\KJ                                   科技档案（PDF 类型）

要点：
  - disableCache 默认 true：回归不看缓存结果（空结果也不写缓存，见 ExtractionPageCache），
    A/B 只比速度时才可置 false 复用已落盘页。
  - 起始会打印 extraction.documentPagesIndependent / extraction.pageConcurrency：
    前者决定非诉讼档案是否并入无上下文并发路径（B/§11.6 的开关）。
  - 结束后用返回的 projectId 在 OpenRefine 里看件数与要素（或用 docs/test 的导出脚本取 data.zip）。
#>
[CmdletBinding()]
param(
    [ValidateSet('ws', 'court', 'keji')]
    [string]$Preset,
    # 默认走能力平台（RCP）的 OR 代理通道；-DirectOr 时直连 JinRefine(OR) :3333
    [string]$Base = 'http://127.0.0.1:8090',
    [switch]$DirectOr,
    [string]$PlatformUser = '',
    [string]$PlatformPassword = '',
    [string]$RootPath,
    [string]$ProjectName,
    [string]$ArchiveCategory,
    [string]$ArchiveSubCategory = '',
    [string]$FixedElements = '["title","responsible_party","document_number","date"]',
    [string]$CustomElements = '[]',
    [bool]$DisableCache = $true,
    [int]$TimeoutMinutes = 60,
    [int]$PollSeconds = 5
)

$ErrorActionPreference = 'Stop'

# 「非必选要素」两级配置（与 import-from-local-dir.js 的 OPTIONAL_ELEMENTS + EXTRA_ELEMENTS 一致）：
#   默认勾选 2 项：密级 / 开放状态 —— 各门类界面默认带上，落在卷级 sheet
#   法院额外 5 项：案由 / 当事人 / 审级 / 结案方式 / 保管期限 —— 仅诉讼档案候选，同样落卷级 sheet
# 每项的 description 即 AIMP 侧该要素的提示词原文（extraction_processor 直接采用）。
$OptionalElements = @'
[
  {"name":"密级","key":"miji","action":"include","description":"提取文件的密级（提取值：公开、内部、秘密、机密、绝密）。仅当页面上出现“内部”“秘密”“机密”“绝密”“公开”等密级字样（一般在版心左上角或正文标题下方）时才输出；页面无密级字样时必须省略本要素，不要给出“公开”等默认值。"},
  {"name":"开放状态","key":"kaifang_zhuangtai","action":"include","description":"提取档案的开放标识（取值类型为：开放、控制、延期开放），只认标题页或落款页上“信息公开选项”“开放标识”“开放状态”这类档案开放审核字样。注意：庭审笔录、判决书正文里的“是否公开审理”“公开开庭”“不公开审理”指的是审理方式，不是档案开放标识，不得据此提取；本页没有档案开放审核字样时必须省略本要素，不要臆造或给出默认值。"}
]
'@

# 法院侧「非必选要素」全量 7 项（2 默认 + 5 额外），取自 2099269551514「回归-法院JZ07-2024-配齐要素B」
# （147 件基线）对应的登记表记录，与该项目卷级 sheet 的列清单
# （密级|开放状态|案由|当事人|审级|结案方式|保管期限）一致 —— 即「配齐 7 要素」口径。
$CourtCustomElements = @'
[
  {"name":"密级","key":"miji","action":"include","description":"提取文件的密级（提取值：公开、内部、秘密、机密、绝密）。仅当页面上出现“内部”“秘密”“机密”“绝密”“公开”等密级字样（一般在版心左上角或正文标题下方）时才输出；页面无密级字样时必须省略本要素，不要给出“公开”等默认值。"},
  {"name":"开放状态","key":"kaifang_zhuangtai","action":"include","description":"提取档案的开放标识（取值类型为：开放、控制、延期开放），只认标题页或落款页上“信息公开选项”“开放标识”“开放状态”这类档案开放审核字样。注意：庭审笔录、判决书正文里的“是否公开审理”“公开开庭”“不公开审理”指的是审理方式，不是档案开放标识，不得据此提取；本页没有档案开放审核字样时必须省略本要素，不要臆造或给出默认值。"},
  {"name":"案由","key":"anyou","action":"include","description":"提取案件的事由，即当事人争议的法律关系性质，一般在卷宗封面“案由”栏或裁判文书首部；只能取原文中出现的、以“……纠纷”或“……案”结尾的案件事由表述，原文怎么写就取什么，不得把“纠纷”当作固定值套用，也不得取送达回证、通知书等表格中“送达文件名称”栏的内容或文书名称。"},
  {"name":"当事人","key":"dangshiren","action":"include","description":"提取案件当事人的姓名或名称，一般在卷宗封面或裁判文书首部；当事人取诉讼主体本身（原告、被告、上诉人、被上诉人、第三人等），委托诉讼代理人（律师）、辩护人、证人、鉴定人、审判人员都不是当事人，不得作为当事人取值；卷内“受托人”“委托诉讼代理人”处署名的人名是代理人，不是当事人；只取姓名或名称本身，不要带“原告”“被告”“上诉人”“被上诉人”“第三人”等诉讼地位称谓，不要附身份证号、电话、住址、性别、出生日期等身份信息，也不要用“与”“和”等连词连接；多个当事人用中文全角逗号“，”隔开。本页只有“原告”“被告”等诉讼地位字样、没有对应的姓名或名称时必须留空，不得用占位文字或符号代替，也不得按表格格式自行补齐成“原告…、被告…”这类套话；当事人栏为空白、涂改或无法辨认时同样留空。"},
  {"name":"审级","key":"shenji","action":"include","description":"提取案件的审理程序层级（提取值类型为：一审、二审、再审、执行），一般在卷宗封面或裁判文书首部案号处。"},
  {"name":"结案方式","key":"jiean_fangshi","action":"include","description":"提取案件的结案方式（取值类型：判决、裁定、调解、撤诉、终结），在下列两处依据提取：①本页法院裁判结论（如“判决如下”“裁定如下”；二审“驳回上诉，维持原判”同属判决）；②卷宗封面或结案登记表的“结案方式”栏。答辩状、上诉状等当事人材料中提出的诉求，以及通知书、送达回证等表格中的内容，都不是结案方式；本页没有依据时不要提取。"},
  {"name":"保管期限","key":"baoguan_qixian","action":"include","description":"提取档案的保管期限，取值以卷宗封面或归档章上标注的字样为准（人民法院诉讼档案：永久、定期60年、定期20年）；本页未出现保管期限字样时不要提取，不要给出默认值或猜测值。"}
]
'@

switch ($Preset) {
    'ws' {
        $RootPath = 'D:\00_toubiao\自动著录\WS'
        $ArchiveCategory = '文书档案'
        $ArchiveSubCategory = ''
        if (-not $PSBoundParameters.ContainsKey('CustomElements')) { $CustomElements = $OptionalElements }
    }
    'court' {
        $RootPath = 'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\JZ07\2024'
        $ArchiveCategory = '专业档案'
        $ArchiveSubCategory = '人民法院诉讼档案'
        # 法院侧必须「配齐 7 要素」：非必选项不配全会导致卷级 7 列缺失/空值
        if (-not $PSBoundParameters.ContainsKey('CustomElements')) { $CustomElements = $CourtCustomElements }
    }
    'keji' {
        $RootPath = 'D:\00_toubiao\自动著录\KJ'
        $ArchiveCategory = '科技档案'
        $ArchiveSubCategory = ''
        if (-not $PSBoundParameters.ContainsKey('CustomElements')) { $CustomElements = $OptionalElements }
    }
}

if (-not $RootPath) { throw '缺少 -RootPath（或指定 -Preset ws|court|keji）' }
if (-not $ArchiveCategory) { throw '缺少 -ArchiveCategory（或指定 -Preset）' }
if (-not (Test-Path -LiteralPath $RootPath)) { throw "目录不存在：$RootPath" }
if (-not $ProjectName) {
    $tag = if ($Preset) { $Preset } else { 'custom' }
    $ProjectName = "REG-$tag-$(Get-Date -Format yyyyMMdd-HHmmss)"
}

$subLabel = if ($ArchiveSubCategory) { $ArchiveSubCategory } else { '（无二级）' }
Write-Output "=== 批量提取系统测试 ==="
Write-Output "  base        : $Base"
Write-Output "  rootPath    : $RootPath"
Write-Output "  projectName : $ProjectName"
Write-Output "  category    : $ArchiveCategory / $subLabel"
Write-Output "  elements    : $FixedElements"
$customKeyList = (ConvertFrom-Json $CustomElements) | ForEach-Object { $_.key }
$customKeyList = if ($customKeyList) { $customKeyList -join ',' } else { '（无）' }
Write-Output "  customKeys  : $customKeyList"
Write-Output "  disableCache: $DisableCache"

# 0) 平台鉴权（默认经能力平台；-DirectOr 时直连 OR，不登录）
$headers = @{}
if (-not $DirectOr) {
    Write-Output "  endpoint    : platform ($Base -> OR proxy)"
    $login = $null
    try {
        $lr = Invoke-WebRequest -Uri "$Base/api/auth/dog" -Method POST -UseBasicParsing -TimeoutSec 20
        $login = $lr.Content | ConvertFrom-Json
    } catch {
        $code = -1
        if ($_.Exception.Response) { try { $code = [int]$_.Exception.Response.StatusCode } catch { } }
        Write-Output ("  dog login   : FAILED (code={0})" -f $code)
    }
    $token = $null
    if ($login -and $login.data) { $token = $login.data.token }
    if (-not $token -and $PlatformUser -and $PlatformPassword) {
        $body = @{ username = $PlatformUser; password = $PlatformPassword } | ConvertTo-Json -Compress
        $lr = Invoke-WebRequest -Uri "$Base/api/auth/login" -Method POST -Body $body `
                -ContentType 'application/json' -UseBasicParsing -TimeoutSec 20
        $login = $lr.Content | ConvertFrom-Json
        if ($login -and $login.data) { $token = $login.data.token }
    }
    if (-not $token) {
        throw '平台登录失败：无可用令牌（请插入加密狗，或提供 -PlatformUser/-PlatformPassword）'
    }
    $headers['X-Auth-Token'] = $token
    $uname = ''; $lok = ''; $left = ''
    if ($login -and $login.data) {
        $uname = [string]$login.data.username
        if ($login.data.license) { $lok = [string]$login.data.license.ok; $left = [string]$login.data.license.leftDays }
    }
    Write-Output ("  login       : user={0} license.ok={1} leftDays={2}" -f $uname, $lok, $left)
} else {
    Write-Output "  endpoint    : direct OR ($Base)"
}

# 1) CSRF + 运行期配置（并发开关/并发度）
$r = Invoke-WebRequest -Uri "$Base/command/core/get-csrf-token" -SessionVariable s7 -UseBasicParsing -Headers $headers
$tok = ($r.Content | ConvertFrom-Json).token
$cfg = (Invoke-WebRequest -Uri "$Base/command/files/extraction-config" -UseBasicParsing -Headers $headers -TimeoutSec 30).Content | ConvertFrom-Json
Write-Output ("config documentPagesIndependent={0} pageConcurrency={1}" -f `
    $cfg.config.'extraction.documentPagesIndependent', $cfg.config.'extraction.pageConcurrency')

# 2) 启动批量提取
$body = @{
    subCommand         = 'start'
    rootPath           = $RootPath
    projectName        = $ProjectName
    template           = 'batch-title-volume'
    archiveCategory    = $ArchiveCategory
    archiveSubCategory = $ArchiveSubCategory
    fixedElements      = $FixedElements
    customElements     = $CustomElements
    disableCache       = "$DisableCache".ToLower()
    csrf_token         = $tok
}
$t0 = Get-Date
$rs = Invoke-WebRequest -Uri "$Base/command/files/batch-extraction" -Method POST -Body $body -WebSession $s7 -Headers $headers -UseBasicParsing -TimeoutSec 120
$j = $rs.Content | ConvertFrom-Json
Write-Output ("start: code={0} projectId={1} units={2} pages={3}" -f $j.code, $j.projectId, $j.unitCount, $j.totalPages)
if ($j.code -ne 'ok') { Write-Output "MESSAGE: $($j.message)"; exit 1 }

# 3) 轮询进度直到结束
$proj = $j.projectId
$final = $null
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollSeconds
    $p = (Invoke-WebRequest -Uri "$Base/command/files/batch-extraction" -Method POST `
            -Body @{ subCommand = 'progress'; project = $proj; csrf_token = $tok } `
            -WebSession $s7 -Headers $headers -UseBasicParsing -TimeoutSec 60).Content | ConvertFrom-Json
    $final = $p
    Write-Output ("[{0}s] status={1} pages={2}/{3} conc={4} avgMs={5} files={6}/{7} msg={8}" -f `
        [int]((Get-Date) - $t0).TotalSeconds, $p.status, $p.processedPages, $p.totalPages, `
        $p.pageConcurrency, $p.avgPageMillis, $p.processedFiles, $p.totalFiles, $p.message)
    if ($p.status -ne 'running' -and $p.status -ne 'paused') { break }
}

$elapsed = [int]((Get-Date) - $t0).TotalSeconds
Write-Output "elapsed: ${elapsed}s  projectId=$proj"
if ($final -and $final.status -notin @('completed', 'done')) {
    Write-Output "WARN: 未正常结束，status=$($final.status)（超时 $TimeoutMinutes 分钟或失败）"
    exit 2
}
Write-Output "PROJECT_ID=$proj"
