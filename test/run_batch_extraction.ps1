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
    [string]$Base = 'http://127.0.0.1:3333',
    [string]$RootPath,
    [string]$ProjectName,
    [string]$ArchiveCategory,
    [string]$ArchiveSubCategory = '',
    [string]$FixedElements = '["title","responsible_party","document_number","date"]',
    [string]$CustomElements = '[]',
    [bool]$DisableCache = $true,
    [int]$TimeoutMinutes = 9,
    [int]$PollSeconds = 5
)

$ErrorActionPreference = 'Stop'

switch ($Preset) {
    'ws' {
        $RootPath = 'D:\00_toubiao\自动著录\WS'
        $ArchiveCategory = '文书档案'
        $ArchiveSubCategory = ''
    }
    'court' {
        $RootPath = 'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\JZ07\2024'
        $ArchiveCategory = '专业档案'
        $ArchiveSubCategory = '人民法院诉讼档案'
    }
    'keji' {
        $RootPath = 'D:\00_toubiao\自动著录\KJ'
        $ArchiveCategory = '科技档案'
        $ArchiveSubCategory = ''
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
Write-Host "=== 批量提取系统测试 ==="
Write-Host "  base        : $Base"
Write-Host "  rootPath    : $RootPath"
Write-Host "  projectName : $ProjectName"
Write-Host "  category    : $ArchiveCategory / $subLabel"
Write-Host "  elements    : $FixedElements"
Write-Host "  disableCache: $DisableCache"

# 1) CSRF + 运行期配置（并发开关/并发度）
$r = Invoke-WebRequest -Uri "$Base/command/core/get-csrf-token" -SessionVariable s7 -UseBasicParsing
$tok = ($r.Content | ConvertFrom-Json).token
$cfg = (Invoke-WebRequest -Uri "$Base/command/files/extraction-config" -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
Write-Host ("config documentPagesIndependent={0} pageConcurrency={1}" -f `
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
$rs = Invoke-WebRequest -Uri "$Base/command/files/batch-extraction" -Method POST -Body $body -WebSession $s7 -UseBasicParsing -TimeoutSec 120
$j = $rs.Content | ConvertFrom-Json
Write-Host ("start: code={0} projectId={1} units={2} pages={3}" -f $j.code, $j.projectId, $j.unitCount, $j.totalPages)
if ($j.code -ne 'ok') { Write-Host "MESSAGE: $($j.message)"; exit 1 }

# 3) 轮询进度直到结束
$proj = $j.projectId
$final = $null
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollSeconds
    $p = (Invoke-WebRequest -Uri "$Base/command/files/batch-extraction" -Method POST `
            -Body @{ subCommand = 'progress'; project = $proj; csrf_token = $tok } `
            -WebSession $s7 -UseBasicParsing -TimeoutSec 60).Content | ConvertFrom-Json
    $final = $p
    Write-Host ("[{0}s] status={1} pages={2}/{3} conc={4} avgMs={5} files={6}/{7} msg={8}" -f `
        [int]((Get-Date) - $t0).TotalSeconds, $p.status, $p.processedPages, $p.totalPages, `
        $p.pageConcurrency, $p.avgPageMillis, $p.processedFiles, $p.totalFiles, $p.message)
    if ($p.status -ne 'running' -and $p.status -ne 'paused') { break }
}

$elapsed = [int]((Get-Date) - $t0).TotalSeconds
Write-Host "elapsed: ${elapsed}s  projectId=$proj"
if ($final -and $final.status -notin @('completed', 'done')) {
    Write-Host "WARN: 未正常结束，status=$($final.status)（超时 $TimeoutMinutes 分钟或失败）"
    exit 2
}
Write-Host "PROJECT_ID=$proj"
