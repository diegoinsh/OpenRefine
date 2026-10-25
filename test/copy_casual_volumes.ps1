# -*- coding: utf-8 -*-
<#
把随案（在办）卷从投放目录拷贝到现状评测目录，供批量提取/评测使用。

背景：
    FULL\随案 下的卷是「只读、持续增补」的投放产物，评测不直接消费它；
    需要的卷先拷贝到 REG\<年度>\<档号>，再由 run_batch_extraction.ps1 -Preset court-casual
    以 REG\<年度> 为 rootPath 跑批量提取（rootPath 下每个档号目录为一卷）。

行为：
    默认只拷贝目标缺失的卷；目标已存在时比对张数与字节数，一致则跳过。
    -Force 会删除该卷目标目录后重建（只在 -Target 根下、层级校验通过时执行）。
    源目录只读不删。

用法：
    & .\test\copy_casual_volumes.ps1                          # 预演：只打印将拷贝/跳过的卷
    & .\test\copy_casual_volumes.ps1 -Apply                   # 执行拷贝
    & .\test\copy_casual_volumes.ps1 -Apply -Only HB07-2024-M1-1638,SZ05-2025-M1-4821
    & .\test\copy_casual_volumes.ps1 -Apply -Only SZ05-2025-M1-4821 -Force   # 重建该卷
#>
[CmdletBinding()]
param(
    [string[]]$Only,
    [string]$Source = 'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\FULL\随案',
    [string]$Target = 'D:\00_toubiao\自动著录\法院诉讼\ZY-FYSS\REG\2026',
    [switch]$Apply,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Source)) { throw "源目录不存在：$Source" }

$volumes = Get-ChildItem -LiteralPath $Source -Directory | Sort-Object Name
if ($Only) {
    $missing = @($Only | Where-Object { -not ($volumes.Name -contains $_) })
    if ($missing.Count -gt 0) { throw "源目录下不存在这些卷：$($missing -join ', ')" }
    $volumes = @($volumes | Where-Object { $Only -contains $_.Name })
}
if ($volumes.Count -eq 0) { throw "没有可拷贝的卷（$Source）" }

Write-Output "=== 随案卷拷贝 ==="
Write-Output "  source : $Source"
Write-Output "  target : $Target"
Write-Output "  mode   : $(if ($Apply) { 'APPLY' } else { 'DRY-RUN（加 -Apply 执行）' })"
Write-Output "  force  : $Force"

$copied = 0
$skipped = 0
$totalFiles = 0
$totalBytes = [long]0

foreach ($vol in $volumes) {
    $srcFiles = Get-ChildItem -LiteralPath $vol.FullName -File -Recurse
    $srcBytes = ($srcFiles | Measure-Object Length -Sum).Sum
    if (-not $srcBytes) { $srcBytes = 0 }
    $dst = Join-Path $Target $vol.Name

    if (Test-Path -LiteralPath $dst) {
        $dstFiles = Get-ChildItem -LiteralPath $dst -File -Recurse
        $dstBytes = ($dstFiles | Measure-Object Length -Sum).Sum
        if (-not $dstBytes) { $dstBytes = 0 }
        $same = ($dstFiles.Count -eq $srcFiles.Count) -and ($dstBytes -eq $srcBytes)
        if ($same -and -not $Force) {
            Write-Output ("  [skip] {0}  ({1} 张 / {2:N1} MB，目标已一致)" -f $vol.Name, $srcFiles.Count, ($srcBytes / 1MB))
            $skipped++
            continue
        }
        if (-not $Apply) {
            Write-Output ("  [plan] {0}  目标已存在且不一致（源 {1} 张 / 目标 {2} 张），需 -Force 重建" -f $vol.Name, $srcFiles.Count, $dstFiles.Count)
            continue
        }
        # 层级安全校验：必须在 Target 根之下至少两级
        $targetFull = [System.IO.Path]::GetFullPath($Target).TrimEnd('\')
        $dstFull = [System.IO.Path]::GetFullPath($dst)
        $rel = $dstFull.Substring($targetFull.Length).Trim('\')
        if (-not $dstFull.StartsWith($targetFull + '\', [System.StringComparison]::OrdinalIgnoreCase) -or $rel.Contains('\')) {
            throw "拒绝删除目标路径（层级校验不通过）：$dstFull"
        }
        Write-Output ("  [force] {0}  删除并重建（原 {1} 张，源 {2} 张）" -f $vol.Name, $dstFiles.Count, $srcFiles.Count)
        Remove-Item -LiteralPath $dst -Recurse -Force
    }

    if (-not $Apply) {
        Write-Output ("  [plan] {0}  {1} 张 / {2:N1} MB" -f $vol.Name, $srcFiles.Count, ($srcBytes / 1MB))
        continue
    }

    New-Item -ItemType Directory -Path $dst -Force | Out-Null
    foreach ($f in $srcFiles) {
        $relPath = $f.FullName.Substring($vol.FullName.Length).TrimStart('\')
        $dstFile = Join-Path $dst $relPath
        $dstDir = Split-Path -Parent $dstFile
        if (-not (Test-Path -LiteralPath $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
        Copy-Item -LiteralPath $f.FullName -Destination $dstFile -Force
    }
    Write-Output ("  [copy] {0}  {1} 张 / {2:N1} MB" -f $vol.Name, $srcFiles.Count, ($srcBytes / 1MB))
    $copied++
    $totalFiles += $srcFiles.Count
    $totalBytes += $srcBytes
}

Write-Output ("汇总：拷贝 {0} 卷 / 跳过 {1} 卷 / 共 {2} 张 {3:N1} MB" -f $copied, $skipped, $totalFiles, ($totalBytes / 1MB))
