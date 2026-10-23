# -*- coding: utf-8 -*-
<#
.SYNOPSIS
  系统回归一键脚本：启动健康 → 读狗授权 → 批量提取（RCP 通道）→ benchmark 三层评测 → 基线对比。

.DESCRIPTION
  串起三步既有工具，不重复实现逻辑：
    * test\run_batch_extraction.ps1  —— 经能力平台(8090)的 OR 代理通道驱动批量提取
    * mock-data-gen\benchmark\cli.py —— or-result / evaluate / compare
  每一阶段失败即终止并回显原因，绝不"带病继续"（回归结论必须可信）。

  与项目规则一致：默认走平台通道（产品真实调用栈），-DirectOr 仅排障时用。

.PARAMETER Preset
  语料预设（court / court-casual / ws / keji），决定 ArchiveCategory 与要素配置。

.PARAMETER RootPaths
  覆盖预设目录，可传多个：每个目录各跑一次提取 + 各自评测（run_id 自动加 -vN 后缀）。
  不传则用 Preset 的默认目录。

.PARAMETER Subset
  benchmark 轮次口径；none = 不限制（评测 result 里实际出现的卷）。

.PARAMETER BaselineRun
  用于 compare 的历史 run_id（须与本次同口径）。留空则跳过对比。

.PARAMETER SkipExtraction
  只做评测（复用已有项目，需配合 -RunId 指向已存在的 run）。

.EXAMPLE
  # 冒烟：法院 JZ07 目录，评完与上次基线对比
  .\test\system_regression.ps1 -Preset court -BaselineRun 20261022-or-regress-trial-level

.EXAMPLE
  # 完整轮：三个法院目录各跑一次（凑 standard 口径的卷集）
  .\test\system_regression.ps1 -Preset court -RootPaths `
      'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\HB07\2024', `
      'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\JZ07\2024', `
      'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\SZ05\2025' -Subset standard
#>
[CmdletBinding()]
param(
    [ValidateSet('court', 'court-casual', 'ws', 'keji')]
    [string]$Preset = 'court',
    [string[]]$RootPaths = @(),
    [ValidateSet('none', 'quick', 'standard', 'full')]
    [string]$Subset = 'none',
    [string]$RunId = '',
    [string]$BaselineRun = '',
    [string]$PlatformBase = 'http://127.0.0.1:8090',
    [int]$TimeoutMinutes = 60,
    [switch]$SkipHealth,
    [switch]$SkipExtraction,
    [switch]$SkipEvaluate,
    [string]$MockDataGen = 'g:\workshop\mock-data-gen',
    [string]$Py = 'C:\ProgramData\Anaconda3\envs\py39ruyi\python.exe'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$extractScript = Join-Path $PSScriptRoot 'run_batch_extraction.ps1'
$cli = Join-Path $MockDataGen 'benchmark\cli.py'

if (-not $RunId) { $RunId = "20261023-or-regress-$(Get-Date -Format HHmm)" }
if (-not (Test-Path $extractScript)) { throw "缺少提取脚本：$extractScript" }
if (-not (Test-Path $cli)) { throw "缺少 benchmark cli：$cli" }
if (-not (Test-Path $Py)) { throw "Python 不存在：$Py" }

$result = [ordered]@{
    stages   = @()
    projects = @()
    runs     = @()
    reports  = @()
}
function Add-Stage([string]$name, [string]$status, [string]$detail = '') {
    $script:result.stages += [pscustomobject]@{ stage = $name; status = $status; detail = $detail }
    $color = if ($status -eq 'OK') { 'Green' } else { 'Red' }
    Write-Host ("  [{0,-4}] {1,-22} {2}" -f $status, $name, $detail) -ForegroundColor $color
}
function Fail([string]$msg) {
    Write-Host ""
    Write-Host "REGRESSION FAILED: $msg" -ForegroundColor Red
    Write-Host ""
    Write-Host "阶段汇总:" -ForegroundColor Yellow
    $result.stages | Format-Table -AutoSize | Out-String | Write-Host
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  系统回归（RCP -> OR -> benchmark）" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  preset   : $Preset"
Write-Host "  roots    : $(if ($RootPaths.Count) { $RootPaths -join ' | ' } else { '(预设默认)' })"
Write-Host "  subset   : $Subset"
Write-Host "  runId    : $RunId"
Write-Host "  baseline : $(if ($BaselineRun) { $BaselineRun } else { '(不对比)' })"
Write-Host ""

# ============================================================
# S0: 启动健康（端口 + 平台读狗登录）
# ============================================================
$licenseLeft = ''
if (-not $SkipHealth) {
    Write-Host "[S0] 服务健康检查" -ForegroundColor Yellow

    $ports = @(
        @{ Port = 3333;  Name = 'JinRefine (OR)' },
        @{ Port = 8090;  Name = '平台 RCP' },
        @{ Port = 7998;  Name = 'AIMP' },
        @{ Port = 11434; Name = 'Ollama' }
    )
    $missing = @()
    foreach ($p in $ports) {
        $listen = Get-NetTCPConnection -State Listen -LocalPort $p.Port -ErrorAction SilentlyContinue
        if ($listen) { Add-Stage "port:$($p.Port)" 'OK' $p.Name }
        else { Add-Stage "port:$($p.Port)" 'FAIL' "$($p.Name) 未监听"; $missing += $p.Port }
    }
    if ($missing.Count -gt 0) {
        Fail ("端口未就绪：{0}。请先从托盘启动全部服务再重跑。" -f ($missing -join ','))
    }

    # 读狗登录：同时验证 加密狗驱动 + 许可桥 + API 密码（0x13000038 的现场探针）
    try {
        $lr = Invoke-WebRequest -Uri "$PlatformBase/api/auth/dog" -Method POST -UseBasicParsing -TimeoutSec 20
        $login = $lr.Content | ConvertFrom-Json
        $token = $null
        if ($login -and $login.data) { $token = $login.data.token }
        if (-not $token) { Fail '平台读狗登录未返回 token' }
        if ($login.data.license) {
            $licenseLeft = [string]$login.data.license.leftDays
            Add-Stage 'dog-login' 'OK' ("user={0} licenseLeftDays={1}" -f $login.data.username, $licenseLeft)
        } else {
            Add-Stage 'dog-login' 'OK' ("user={0}" -f $login.data.username)
        }
    } catch {
        Fail ("平台读狗登录失败：{0}（若为授权错误，检查加密狗与 API 密码）" -f $_.Exception.Message.Split([char]10)[0])
    }
} else {
    Write-Host "[S0] 已跳过服务健康检查" -ForegroundColor DarkGray
}

# ============================================================
# S1: 批量提取（每个 root 一次）
# ============================================================
$extractElapsed = @{}
if (-not $SkipExtraction) {
    Write-Host ""
    Write-Host "[S1] 批量提取（平台代理通道）" -ForegroundColor Yellow

    $roots = if ($RootPaths.Count) { $RootPaths } else { @($null) }
    $idx = 0
    foreach ($root in $roots) {
        $idx++
        $suffix = if ($roots.Count -gt 1) { "-v$idx" } else { '' }
        $runThis = "$RunId$suffix"
        $projName = "REG-$Preset-$RunId$suffix"
        $eargs = @{
            Preset         = $Preset
            Base           = $PlatformBase
            ProjectName    = $projName
            TimeoutMinutes = $TimeoutMinutes
        }
        if ($root) { $eargs['RootPath'] = $root }

        Write-Host "  --- 提取 #$idx $(if ($root) { $root } else { '(预设默认目录)' }) ---" -ForegroundColor Gray
        $t0 = Get-Date
        $out = & $extractScript @eargs 2>&1
        $rc = $LASTEXITCODE
        $out | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        $elapsed = [int]((Get-Date) - $t0).TotalSeconds

        if ($rc -ne 0) { Fail "批量提取失败（rc=$rc，目录=$root）" }
        $m = ($out | Out-String) | Select-String -Pattern 'PROJECT_ID=(\S+)' | Select-Object -First 1
        if (-not $m) { Fail "提取结束但未解析到 PROJECT_ID（目录=$root）" }
        $projId = $m.Matches[0].Groups[1].Value

        Add-Stage "extract#$idx" 'OK' "project=$projId elapsed=${elapsed}s"
        $result.projects += [pscustomobject]@{ run_id = $runThis; project = $projId; root = "$root"; seconds = $elapsed }
        $extractElapsed[$runThis] = $elapsed
    }
} else {
    Write-Host ""
    Write-Host "[S1] 已跳过批量提取（-SkipExtraction）" -ForegroundColor DarkGray
    if ($result.projects.Count -eq 0) {
        $result.projects += [pscustomobject]@{ run_id = $RunId; project = ''; root = ''; seconds = 0 }
    }
}

# ============================================================
# S2: benchmark 评测（or-result -> evaluate）
# ============================================================
if (-not $SkipEvaluate) {
    Write-Host ""
    Write-Host "[S2] benchmark 评测" -ForegroundColor Yellow
    Push-Location $MockDataGen
    try {
        foreach ($pr in $result.projects) {
            $runThis = $pr.run_id
            $note = "preset=$Preset; subset=$Subset; licenseLeftDays=$licenseLeft; via=RCP"
            Write-Host "  --- run $runThis ---" -ForegroundColor Gray

            if ($pr.project) {
                & $Py $cli or-result --project $pr.project --run-id $runThis --note $note 2>&1 |
                    ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
                if ($LASTEXITCODE -ne 0) { Fail "or-result 失败（project=$($pr.project)）" }
                Add-Stage "or-result:$runThis" 'OK' "project=$($pr.project)"
            }

            $evalArgs = @('evaluate', '--result', $runThis)
            if ($Subset -ne 'none') { $evalArgs += @('--subset', $Subset) }
            & $Py $cli @evalArgs 2>&1 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
            if ($LASTEXITCODE -ne 0) { Fail "evaluate 失败（run=$runThis）" }
            $reportPath = Join-Path $MockDataGen "benchmark\runs\$runThis\report.md"
            Add-Stage "evaluate:$runThis" 'OK' (Split-Path $reportPath -Leaf)
            if (Test-Path $reportPath) { $result.reports += $reportPath }
            $result.runs += $runThis
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host ""
    Write-Host "[S2] 已跳过评测（-SkipEvaluate）" -ForegroundColor DarkGray
}

# ============================================================
# S3: 基线对比（可选）
# ============================================================
if ($BaselineRun -and $result.runs.Count -gt 0) {
    Write-Host ""
    Write-Host "[S3] 基线对比" -ForegroundColor Yellow
    Push-Location $MockDataGen
    try {
        $baseDir = Join-Path $MockDataGen "benchmark\runs\$BaselineRun"
        if (-not (Test-Path $baseDir)) {
            Add-Stage 'compare' 'FAIL' "基线 run 不存在：$BaselineRun"
        } else {
            foreach ($runThis in $result.runs) {
                $cmpArgs = @('compare', '--run-a', $BaselineRun, '--run-b', $runThis)
                if ($Subset -ne 'none') { $cmpArgs += @('--subset', $Subset) }
                & $Py $cli @cmpArgs 2>&1 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
                if ($LASTEXITCODE -ne 0) { Add-Stage "compare:$runThis" 'FAIL' '见上方输出' }
                else {
                    $cmpMd = Join-Path $MockDataGen "benchmark\runs\_compare\${BaselineRun}_vs_$runThis\compare.md"
                    Add-Stage "compare:$runThis" 'OK' (Split-Path $cmpMd -Leaf)
                    if (Test-Path $cmpMd) { $result.reports += $cmpMd }
                }
            }
        }
    } finally {
        Pop-Location
    }
}

# ============================================================
# 汇总
# ============================================================
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  回归完成" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
$result.stages | Format-Table -AutoSize | Out-String | Write-Host

if ($result.projects.Count -gt 0) {
    Write-Host "提取耗时：" -ForegroundColor Yellow
    $result.projects | ForEach-Object { "  {0,-40} {1,6}s  project={2}" -f $_.run_id, $_.seconds, $_.project }
}

if ($result.reports.Count -gt 0) {
    Write-Host ""
    Write-Host "报告：" -ForegroundColor Yellow
    $result.reports | ForEach-Object { "  $_" }
    Write-Host ""
    Write-Host "（issues.md 与 report.md 同目录，可直接当改造 backlog）" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "REGRESSION PASSED" -ForegroundColor Green
exit 0
