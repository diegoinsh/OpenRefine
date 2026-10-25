# JinRefine kernel golden verification.
# Usage: powershell -File .\scripts\verify_kernel_golden.ps1 -BaseUrl http://127.0.0.1:3533
# Assumes a server is already running and the golden baseline exists under docs/ruyi/design/golden-release1.

param(
    [string]$BaseUrl = 'http://127.0.0.1:3533',
    [string]$GoldenDir = 'docs/ruyi/design/golden-release1'
)

$ErrorActionPreference = 'Stop'

$items = @(
    @{ Url = '/wirings.js'; File = 'wirings.js'; Mode = 'wirings' },
    @{ Url = '/index-bundle.js'; File = 'index-bundle.js'; Mode = 'bytes' },
    @{ Url = '/project-bundle.js'; File = 'project-bundle.js'; Mode = 'bytes' },
    @{ Url = '/'; File = 'index.html'; Mode = 'bytes' },
    @{ Url = '/about.html'; File = 'about.html'; Mode = 'bytes' },
    @{ Url = '/command/core/get-languages'; File = 'get-languages.json'; Mode = 'bytes' },
    @{ Url = '/command/core/get-version'; File = 'get-version.json'; Mode = 'version' },
    @{ Url = '/extension/files/scripts/index/import-from-local-dir.js'; File = 'files-import-from-local-dir.js'; Mode = 'bytes' },
    @{ Url = '/extension/data-quality/scripts/quality-alignment.js'; File = 'data-quality-alignment.js'; Mode = 'bytes' }
)

$tmp = Join-Path $env:TEMP ('jinrefine-golden-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$failed = 0
foreach ($item in $items) {
    $golden = Join-Path $GoldenDir $item.File
    if (-not (Test-Path $golden)) {
        Write-Host ("MISSING {0}" -f $item.File) -ForegroundColor Yellow
        $failed++
        continue
    }

    $actual = Join-Path $tmp $item.File
    try {
        Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $item.Url) -OutFile $actual | Out-Null
    } catch {
        Write-Host ("FAIL    {0} : {1}" -f $item.File, $_.Exception.Message) -ForegroundColor Red
        $failed++
        continue
    }

    if ($item.Mode -eq 'wirings') {
        $goldenJson = ((Get-Content $golden -Raw) -replace '^var ModuleWirings = ', '' -replace ';$', '') | ConvertFrom-Json
        $actualJson = ((Get-Content $actual -Raw) -replace '^var ModuleWirings = ', '' -replace ';$', '') | ConvertFrom-Json
        $diff = @()
        foreach ($prop in $goldenJson.PSObject.Properties) {
            if ($actualJson.PSObject.Properties.Name -notcontains $prop.Name -or $actualJson.($prop.Name) -ne $prop.Value) {
                $diff += ("{0}: {1} -> {2}" -f $prop.Name, $prop.Value, $actualJson.($prop.Name))
            }
        }
        if ($diff.Count -eq 0) {
            Write-Host ("PASS    {0} (JSON)" -f $item.File) -ForegroundColor Green
        } else {
            Write-Host ("FAIL    {0} (JSON): {1}" -f $item.File, ($diff -join '; ')) -ForegroundColor Red
            $failed++
        }
    } elseif ($item.Mode -eq 'version') {
        # 构建标识（revision / full_version / full_name）随提交变化，不进入 golden 判定；
        # 其余字段（版本、JVM、模块清单等）逐一比较。
        $ignore = @('revision', 'full_version', 'full_name')
        $since = [string][char]1
        $goldenJson = Get-Content $golden -Raw | ConvertFrom-Json
        $actualJson = Get-Content $actual -Raw | ConvertFrom-Json
        $diff = @()
        foreach ($prop in $goldenJson.PSObject.Properties) {
            if ($ignore -contains $prop.Name) {
                continue
            }
            $actualValue = $actualJson.($prop.Name)
            $goldenValue = $prop.Value
            if ($goldenValue -is [array]) {
                if (($actualValue -join $since) -ne ($goldenValue -join $since)) {
                    $diff += ("{0}: {1} -> {2}" -f $prop.Name, ($goldenValue -join ','), ($actualValue -join ','))
                }
            } elseif ($actualValue -ne $goldenValue) {
                $diff += ("{0}: {1} -> {2}" -f $prop.Name, $goldenValue, $actualValue)
            }
        }
        if ($diff.Count -eq 0) {
            Write-Host ("PASS    {0} (JSON, build id ignored)" -f $item.File) -ForegroundColor Green
        } else {
            Write-Host ("FAIL    {0} (JSON): {1}" -f $item.File, ($diff -join '; ')) -ForegroundColor Red
            $failed++
        }
    } else {
        $goldenHash = (Get-FileHash $golden -Algorithm SHA256).Hash
        $actualHash = (Get-FileHash $actual -Algorithm SHA256).Hash
        if ($goldenHash -eq $actualHash) {
            Write-Host ("PASS    {0} ({1} bytes)" -f $item.File, (Get-Item $actual).Length) -ForegroundColor Green
        } else {
            Write-Host ("FAIL    {0} SHA256 {1} != {2}" -f $item.File, $actualHash, $goldenHash) -ForegroundColor Red
            $failed++
        }
    }
}

Remove-Item -Recurse -Force $tmp

if ($failed -gt 0) {
    Write-Host ("{0} item(s) failed" -f $failed) -ForegroundColor Red
    exit 1
}
Write-Host 'All golden items passed' -ForegroundColor Green
exit 0
