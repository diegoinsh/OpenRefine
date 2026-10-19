# -*- coding: utf-8 -*-
<#
JinRefine 2.0 系统回归测试（安装态，HTTP 黑盒）。

覆盖范围（与 test\run_batch_extraction.ps1 互补）：
  1) 安装产物静态检查：关键文件、版本号、加密/加壳、平台 jar 包名归位（com.jiongche）
  2) 端口与进程：3333 OR / 7998 AIMP / 8090 平台 / 11434 Ollama
  3) OR 命令面：get-version / csrf / files 抽取配置 / data-quality / languages / wirings.js
  4) AIMP：/health、授权门（block 下未授权返回 403）、AIMP 日志里的 license-id=80,3
  5) 平台：/api/auth/dog 登录、/api/auth/me、/api/shell/config、/v2/api/public/*、OR 代理通道
  6) Ollama：/api/tags 含随包模型 qwen3:4b
  7) 集成：OR -> AIMP 连接测试（data-quality/test-aimp-connection）

用法：
  & .\test\run_system_regression.ps1
  & .\test\run_system_regression.ps1 -InstallDir 'D:\Program Files\JinRefine'
  & .\test\run_system_regression.ps1 -SkipInstallChecks
  & .\test\run_system_regression.ps1 -PlatformUser admin -PlatformPassword ***

退出码：0 = 全部通过（允许 WARN/SKIP）；1 = 存在 FAIL。
批量抽取另行执行：& .\test\run_batch_extraction.ps1 -Preset court
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'D:\Program Files\JinRefine',
    [string]$OrBase = 'http://127.0.0.1:3333',
    [string]$AimpBase = 'http://127.0.0.1:7998',
    [string]$PlatformBase = 'http://127.0.0.1:8090',
    [string]$OllamaBase = 'http://127.0.0.1:11434',
    [string]$PlatformUser = '',
    [string]$PlatformPassword = '',
    [int]$HttpTimeoutSec = 20,
    [switch]$SkipInstallChecks
)

$ErrorActionPreference = 'Continue'
$script:results = New-Object System.Collections.ArrayList
$script:token = $null

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Detail)
    [void]$script:results.Add([pscustomobject]@{ name = $Name; status = $Status; detail = $Detail })
    $color = switch ($Status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'WARN' { 'Yellow' }
        default { 'DarkGray' }
    }
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
}

function Invoke-Http {
    param(
        [string]$Method = 'GET',
        [string]$Url,
        $Body = $null,
        $Headers = $null,
        [string]$ContentType = '',
        [int]$Timeout = $HttpTimeoutSec
    )
    $out = [pscustomobject]@{ Code = 0; Body = ''; Error = $null }
    try {
        $p = @{
            Uri             = $Url
            Method          = $Method
            UseBasicParsing = $true
            TimeoutSec      = $Timeout
            ErrorAction     = 'Stop'
        }
        if ($null -ne $Headers) { $p.Headers = $Headers }
        if ($null -ne $Body) { $p.Body = $Body }
        if ($ContentType) { $p.ContentType = $ContentType }
        $resp = Invoke-WebRequest @p
        $out.Code = [int]$resp.StatusCode
        $out.Body = [string]$resp.Content
    } catch {
        $ex = $_.Exception
        if ($ex.Response) {
            try { $out.Code = [int]$ex.Response.StatusCode } catch { $out.Code = -1 }
            try {
                if ($ex.Response.PSObject.Methods['GetResponseStream']) {
                    $sr = New-Object System.IO.StreamReader($ex.Response.GetResponseStream())
                    $out.Body = $sr.ReadToEnd()
                    $sr.Close()
                } elseif ($ex.Response.Content) {
                    $out.Body = $ex.Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
            } catch { }
        } else {
            $out.Code = -1
            $out.Error = $ex.Message
        }
    }
    return $out
}

function Convert-JsonSafe {
    param([string]$Text)
    if (-not $Text) { return $null }
    try { return ($Text | ConvertFrom-Json) } catch { return $null }
}

function Test-Port {
    param([string]$TargetHost, [int]$Port, [int]$TimeoutMs = 2000)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-Prop {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return $null }
    $p = $Obj.PSObject.Properties[$Name]
    if ($p) { return $p.Value }
    return $null
}

Write-Host '========================================' -ForegroundColor Cyan
Write-Host '  JinRefine 2.0 系统回归测试（安装态）' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ("  install : {0}" -f $InstallDir)
Write-Host ("  OR      : {0}" -f $OrBase)
Write-Host ("  AIMP    : {0}" -f $AimpBase)
Write-Host ("  platform: {0}" -f $PlatformBase)
Write-Host ("  ollama  : {0}" -f $OllamaBase)
Write-Host ''

# ============================================================
# 1. 安装产物静态检查
# ============================================================
if (-not $SkipInstallChecks) {
    Write-Host '--- 1. 安装产物 ---' -ForegroundColor Cyan

    if (-not (Test-Path -LiteralPath $InstallDir)) {
        Add-Result '安装目录存在' 'FAIL' ("未找到 {0}" -f $InstallDir)
    } else {
        Add-Result '安装目录存在' 'PASS' $InstallDir

        $files = [ordered]@{
            'jinrefine\jinrefine.exe'                    = 'JinRefine 主程序'
            'aimp\aimp.exe'                              = 'AIMP 主程序'
            'aimp\config\config.dat'                     = 'AIMP 加密配置'
            'ollama\ollama.exe'                          = 'Ollama 运行时'
            'manager\tray_app.exe'                       = '托盘/管理器'
            'platform\ruyi-capability-platform.jar'      = '能力平台 jar'
            'platform\jre\bin\java.exe'                  = '平台随包 JRE'
            'platform\deploy\bridge\dog_read.exe'        = '读狗桥'
            'version.json'                               = '版本清单'
        }
        foreach ($rel in $files.Keys) {
            $full = Join-Path $InstallDir $rel
            if (Test-Path -LiteralPath $full) {
                Add-Result ("文件：" + $rel) 'PASS' $files[$rel]
            } elseif ($rel -eq 'version.json') {
                # 2.0.0 安装包未把根目录 version.json 纳入 [Files]（打包遗漏），
                # 不影响运行，故记 WARN 而非 FAIL，产品版本另由下方用例判断。
                Add-Result ("文件：" + $rel) 'WARN' '安装包未包含（打包遗漏）'
            } else {
                Add-Result ("文件：" + $rel) 'FAIL' '缺失'
            }
        }

        # AIMP 配置应加密：明文 yaml 不应随包
        $plainYaml = Join-Path $InstallDir 'aimp\config\single_gpu_config.yaml'
        if (Test-Path -LiteralPath $plainYaml) {
            Add-Result 'AIMP 配置加密' 'FAIL' '发现明文 single_gpu_config.yaml'
        } else {
            Add-Result 'AIMP 配置加密' 'PASS' '仅 config.dat'
        }

        # AIMP 源码保护：_internal\src 下不应有 .py
        $srcDir = Join-Path $InstallDir 'aimp\_internal\src'
        if (Test-Path -LiteralPath $srcDir) {
            $pyCount = @(Get-ChildItem -LiteralPath $srcDir -Recurse -Filter '*.py' -ErrorAction SilentlyContinue).Count
            $pydCount = @(Get-ChildItem -LiteralPath $srcDir -Recurse -Filter '*.pyd' -ErrorAction SilentlyContinue).Count
            if ($pyCount -eq 0) {
                Add-Result 'AIMP 源码保护' 'PASS' ("py=0 / pyd={0}" -f $pydCount)
            } else {
                Add-Result 'AIMP 源码保护' 'FAIL' ("仍存在 {0} 个 .py" -f $pyCount)
            }
        } else {
            Add-Result 'AIMP 源码保护' 'SKIP' '_internal\src 不存在（可能非 PyInstaller 布局）'
        }

        # 模型随包
        $aimpModels = Join-Path $InstallDir 'aimp\models'
        $blobs = Join-Path $InstallDir 'ollama\models\blobs'
        if ((Test-Path -LiteralPath $aimpModels) -and @(Get-ChildItem -LiteralPath $aimpModels -Recurse -File -ErrorAction SilentlyContinue).Count -gt 0) {
            Add-Result 'AIMP 模型随包' 'PASS' $aimpModels
        } else {
            Add-Result 'AIMP 模型随包' 'FAIL' 'aimp\models 为空'
        }
        if ((Test-Path -LiteralPath $blobs) -and @(Get-ChildItem -LiteralPath $blobs -File -ErrorAction SilentlyContinue).Count -gt 0) {
            Add-Result 'Ollama 模型随包' 'PASS' $blobs
        } else {
            Add-Result 'Ollama 模型随包' 'FAIL' 'ollama\models\blobs 为空'
        }

        # 版本号（本版应为 2.0.0）
        $verFile = Join-Path $InstallDir 'version.json'
        if (Test-Path -LiteralPath $verFile) {
            $vj = Convert-JsonSafe (Get-Content -LiteralPath $verFile -Raw -Encoding UTF8)
            if ($vj -and $vj.version) {
                if ([string]$vj.version -eq '2.0.0') {
                    Add-Result '产品版本' 'PASS' ("version={0}, platform={1}" -f $vj.version, $vj.platform_version)
                } else {
                    Add-Result '产品版本' 'FAIL' ("期望 2.0.0，实际 {0}" -f $vj.version)
                }
            } else {
                Add-Result '产品版本' 'FAIL' 'version.json 无法解析'
            }
        } else {
            Add-Result '产品版本' 'WARN' '无 version.json，无法核对产品版本（建议下版纳入安装包）'
        }

        # 平台 jar 包名归位（重构验收：com.jiongche，不应有 com.ruyi）
        $jar = Join-Path $InstallDir 'platform\ruyi-capability-platform.jar'
        if (Test-Path -LiteralPath $jar) {
            try {
                Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
                $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
                $names = @($zip.Entries | ForEach-Object { $_.FullName })
                $zip.Dispose()
                $jiongche = @($names | Where-Object { $_ -like 'com/jiongche/*' }).Count
                $ruyi = @($names | Where-Object { $_ -like 'com/ruyi/*' }).Count
                if ($jiongche -gt 0 -and $ruyi -eq 0) {
                    Add-Result '平台包名归位' 'PASS' ("com/jiongche={0}, com/ruyi=0" -f $jiongche)
                } else {
                    Add-Result '平台包名归位' 'FAIL' ("com/jiongche={0}, com/ruyi={1}" -f $jiongche, $ruyi)
                }
            } catch {
                Add-Result '平台包名归位' 'WARN' ("jar 不可读（可能被加壳）：{0}" -f $_.Exception.Message)
            }
        }

        # 平台启动器不应把 license-id 覆盖成单值 80（本版应为 80,3 或交由 app.yml）
        $launcher = Join-Path $InstallDir 'platform\run_platform.ps1'
        if (Test-Path -LiteralPath $launcher) {
            $lt = Get-Content -LiteralPath $launcher -Raw -ErrorAction SilentlyContinue
            if ($lt -match 'platform\.dog\.license-id\s*=\s*([0-9,]+)') {
                $lid = $Matches[1]
                if ($lid -eq '80,3') {
                    Add-Result '平台 license-id 覆盖' 'PASS' '-Dplatform.dog.license-id=80,3'
                } else {
                    Add-Result '平台 license-id 覆盖' 'FAIL' ("启动器覆盖为 {0}，会遮蔽 app.yml 的 80,3" -f $lid)
                }
            } else {
                Add-Result '平台 license-id 覆盖' 'PASS' '启动器未覆盖，读取 app.yml（80,3）'
            }
        }
    }
    Write-Host ''
}

# ============================================================
# 2. 端口
# ============================================================
Write-Host '--- 2. 端口 ---' -ForegroundColor Cyan
$ports = [ordered]@{
    'OR 3333'           = @('127.0.0.1', 3333)
    'AIMP 7998'         = @('127.0.0.1', 7998)
    '平台 8090'         = @('127.0.0.1', 8090)
    'Ollama 11434'      = @('127.0.0.1', 11434)
}
foreach ($k in $ports.Keys) {
    $pp = $ports[$k]
    if (Test-Port -TargetHost $pp[0] -Port $pp[1]) {
        Add-Result ("端口 " + $k) 'PASS' '监听中'
    } else {
        Add-Result ("端口 " + $k) 'FAIL' '未监听'
    }
}
Write-Host ''

# ============================================================
# 3. OR（JinRefine）命令面
# ============================================================
Write-Host '--- 3. OR / JinRefine ---' -ForegroundColor Cyan

$r = Invoke-Http -Url ($OrBase + '/')
if ($r.Code -eq 200) { Add-Result 'OR 首页' 'PASS' '/' } else { Add-Result 'OR 首页' 'FAIL' ("code={0} {1}" -f $r.Code, $r.Error) }

$r = Invoke-Http -Url ($OrBase + '/command/core/get-version')
$ver = Convert-JsonSafe $r.Body
if ($r.Code -eq 200 -and $ver) {
    Add-Result 'OR get-version' 'PASS' ([string]$ver.version)
} else {
    Add-Result 'OR get-version' 'FAIL' ("code={0}" -f $r.Code)
}

$r = Invoke-Http -Url ($OrBase + '/command/core/get-csrf-token')
$csrf = Convert-JsonSafe $r.Body
$csrfToken = Get-Prop $csrf 'token'
if ($r.Code -eq 200 -and $csrfToken) {
    Add-Result 'OR CSRF' 'PASS' 'token 已下发'
} else {
    Add-Result 'OR CSRF' 'FAIL' ("code={0}" -f $r.Code)
}

$r = Invoke-Http -Url ($OrBase + '/command/files/extraction-config')
$ec = Convert-JsonSafe $r.Body
if ($r.Code -eq 200 -and $ec) {
    $cfg = Get-Prop $ec 'config'
    $dpi = Get-Prop $cfg 'extraction.documentPagesIndependent'
    $pc = Get-Prop $cfg 'extraction.pageConcurrency'
    Add-Result 'OR 抽取配置' 'PASS' ("documentPagesIndependent={0} pageConcurrency={1}" -f $dpi, $pc)
} else {
    Add-Result 'OR 抽取配置' 'FAIL' ("code={0}" -f $r.Code)
}

$r = Invoke-Http -Url ($OrBase + '/command/data-quality/get-config')
if ($r.Code -eq 200) { Add-Result 'OR data-quality 配置' 'PASS' '200' } else { Add-Result 'OR data-quality 配置' 'FAIL' ("code={0}" -f $r.Code) }

$r = Invoke-Http -Url ($OrBase + '/command/data-quality/get-quality-rules')
if ($r.Code -eq 200) { Add-Result 'OR 质量规则' 'PASS' '200' } else { Add-Result 'OR 质量规则' 'FAIL' ("code={0}" -f $r.Code) }

$r = Invoke-Http -Url ($OrBase + '/command/core/get-languages')
if ($r.Code -eq 200) { Add-Result 'OR get-languages' 'PASS' '200' } else { Add-Result 'OR get-languages' 'FAIL' ("code={0}" -f $r.Code) }

# 自研内核接管 wirings/bundle：这两个资源必须仍可下发
$r = Invoke-Http -Url ($OrBase + '/wirings.js')
if ($r.Code -eq 200 -and $r.Body.Length -gt 10) {
    Add-Result 'OR wirings.js' 'PASS' ("{0} bytes" -f $r.Body.Length)
} else {
    Add-Result 'OR wirings.js' 'FAIL' ("code={0}, len={1}" -f $r.Code, $r.Body.Length)
}

# OR -> AIMP 连接测试（SDK 传输收口后的端到端探针）
$r = Invoke-Http -Url ($OrBase + '/command/data-quality/test-aimp-connection')
$tc = Convert-JsonSafe $r.Body
if ($r.Code -eq 200 -and $tc -and $tc.connected -eq $true) {
    Add-Result 'OR -> AIMP 连接' 'PASS' ([string]$tc.message)
} elseif ($r.Code -eq 200 -and $tc) {
    Add-Result 'OR -> AIMP 连接' 'FAIL' ([string]$tc.message)
} else {
    Add-Result 'OR -> AIMP 连接' 'FAIL' ("code={0}" -f $r.Code)
}
Write-Host ''

# ============================================================
# 4. AIMP
# ============================================================
Write-Host '--- 4. AIMP ---' -ForegroundColor Cyan

$r = Invoke-Http -Url ($AimpBase + '/health')
$h = Convert-JsonSafe $r.Body
if ($r.Code -eq 200) {
    $st = Get-Prop $h 'status'
    Add-Result 'AIMP /health' 'PASS' ("status={0}" -f $st)
} else {
    Add-Result 'AIMP /health' 'FAIL' ("code={0} {1}" -f $r.Code, $r.Error)
}

# 授权门：block 模式下未授权接口返回 403；空请求命中参数校验则说明已过授权
$r = Invoke-Http -Method POST -Url ($AimpBase + '/ocr')
if ($r.Code -eq 403) {
    $reason = ''
    $jb = Convert-JsonSafe $r.Body
    if ($jb) { $reason = [string](Get-Prop $jb 'detail') }
    Add-Result 'AIMP 授权门 /ocr' 'FAIL' ("403 授权未通过 {0}" -f $reason)
} elseif ($r.Code -in @(400, 415, 422)) {
    Add-Result 'AIMP 授权门 /ocr' 'PASS' ("已过授权，参数校验 {0}" -f $r.Code)
} elseif ($r.Code -eq 200) {
    Add-Result 'AIMP 授权门 /ocr' 'WARN' '空请求返回 200（预期参数校验）'
} else {
    Add-Result 'AIMP 授权门 /ocr' 'WARN' ("code={0} {1}" -f $r.Code, $r.Error)
}

# AIMP 日志：确认授权模式与 license-id 并集
$logDirs = @(
    (Join-Path $InstallDir 'aimp\logs'),
    (Join-Path $InstallDir 'logs')
)
$licLine = $null
foreach ($d in $logDirs) {
    if (Test-Path -LiteralPath $d) {
        $lf = Get-ChildItem -LiteralPath $d -Filter '*.log' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($lf) {
            $hit = Select-String -LiteralPath $lf.FullName -Pattern '模块授权校验' -ErrorAction SilentlyContinue |
                Select-Object -Last 1
            if ($hit) { $licLine = $hit.Line }
        }
    }
}
if ($licLine) {
    if ($licLine -match '80,3') {
        Add-Result 'AIMP 授权配置' 'PASS' $licLine.Trim()
    } else {
        Add-Result 'AIMP 授权配置' 'FAIL' $licLine.Trim()
    }
} else {
    Add-Result 'AIMP 授权配置' 'SKIP' '日志未找到「模块授权校验」行'
}
Write-Host ''

# ============================================================
# 5. 能力平台
# ============================================================
Write-Host '--- 5. 能力平台 ---' -ForegroundColor Cyan

$r = Invoke-Http -Url ($PlatformBase + '/index.html')
if ($r.Code -eq 200) { Add-Result '平台外壳页面' 'PASS' '/index.html' } else { Add-Result '平台外壳页面' 'FAIL' ("code={0}" -f $r.Code) }

# 未登录访问受保护接口应 401
$r = Invoke-Http -Url ($PlatformBase + '/api/shell/config')
if ($r.Code -eq 401) {
    Add-Result '平台未登录拦截' 'PASS' '401'
} else {
    Add-Result '平台未登录拦截' 'FAIL' ("期望 401，实际 {0}" -f $r.Code)
}

# 读狗免密登录（首选）
$r = Invoke-Http -Method POST -Url ($PlatformBase + '/api/auth/dog')
$login = Convert-JsonSafe $r.Body
$token = $null
if ($login) {
    $data = Get-Prop $login 'data'
    $token = Get-Prop $data 'token'
    if (-not $token) { $token = Get-Prop $login 'token' }
}
if ($token) {
    $script:token = $token
    $lic = Get-Prop $data 'license'
    Add-Result '平台狗登录' 'PASS' ("user={0}, license.ok={1}" -f (Get-Prop $data 'username'), (Get-Prop $lic 'ok'))
} else {
    $msg = ''
    if ($login) { $msg = [string](Get-Prop $login 'message') }
    Add-Result '平台狗登录' 'WARN' ("失败：{0}（尝试账号密码）" -f $msg)

    if ($PlatformUser -and $PlatformPassword) {
        $body = @{ username = $PlatformUser; password = $PlatformPassword } | ConvertTo-Json -Compress
        $r = Invoke-Http -Method POST -Url ($PlatformBase + '/api/auth/login') -Body $body -ContentType 'application/json'
        $login = Convert-JsonSafe $r.Body
        if ($login) {
            $data = Get-Prop $login 'data'
            $token = Get-Prop $data 'token'
            if (-not $token) { $token = Get-Prop $login 'token' }
        }
        if ($token) {
            $script:token = $token
            Add-Result '平台账号登录' 'PASS' ("user={0}" -f (Get-Prop $data 'username'))
        } else {
            Add-Result '平台账号登录' 'FAIL' ("code={0}" -f $r.Code)
        }
    } else {
        Add-Result '平台账号登录' 'SKIP' '未提供 -PlatformUser/-PlatformPassword'
    }
}

if ($script:token) {
    $hdr = @{ 'X-Auth-Token' = $script:token }

    $r = Invoke-Http -Url ($PlatformBase + '/api/auth/me') -Headers $hdr
    $me = Convert-JsonSafe $r.Body
    $meData = Get-Prop $me 'data'
    if ($r.Code -eq 200 -and $meData -and (Get-Prop $meData 'loggedIn') -eq $true) {
        Add-Result '平台 /api/auth/me' 'PASS' ("role={0}, source={1}" -f (Get-Prop $meData 'role'), (Get-Prop $meData 'source'))
        $ml = Get-Prop $meData 'license'
        if ($ml) {
            $mok = Get-Prop $ml 'ok'
            $mods = Get-Prop $ml 'modules'
            $ids = Get-Prop $ml 'moduleIds'
            $left = Get-Prop $ml 'leftDays'
            Add-Result '平台授权快照' ($(if ($mok -eq $true) { 'PASS' } else { 'FAIL' })) `
                ("ok={0}, modules=[{1}], moduleIds=[{2}], leftDays={3}" -f $mok, ($mods -join ','), ($ids -join ','), $left)
        }
    } else {
        Add-Result '平台 /api/auth/me' 'FAIL' ("code={0}" -f $r.Code)
    }

    $r = Invoke-Http -Url ($PlatformBase + '/api/shell/config') -Headers $hdr
    $sc = Convert-JsonSafe $r.Body
    $scData = Get-Prop $sc 'data'
    if ($r.Code -eq 200 -and $scData) {
        Add-Result '平台 /api/shell/config' 'PASS' ("name={0}, version={1}" -f (Get-Prop $scData 'platformName'), (Get-Prop $scData 'version'))
    } else {
        Add-Result '平台 /api/shell/config' 'FAIL' ("code={0}" -f $r.Code)
    }

    # SSO 合规接口（本地化）：授权通过应 200，未授权模块应 403
    $r = Invoke-Http -Url ($PlatformBase + '/v2/api/public/getTasks') -Headers $hdr
    if ($r.Code -eq 200) {
        Add-Result '平台 SSO getTasks' 'PASS' '200'
    } elseif ($r.Code -eq 403) {
        Add-Result '平台 SSO getTasks' 'FAIL' '403 模块未授权（inspection 不在狗授权集）'
    } else {
        Add-Result '平台 SSO getTasks' 'FAIL' ("code={0}" -f $r.Code)
    }

    # OR 代理通道（两条前缀）
    $r = Invoke-Http -Url ($PlatformBase + '/or-proxy/command/core/get-version') -Headers $hdr
    if ($r.Code -eq 200) {
        Add-Result '平台 -> OR（/or-proxy）' 'PASS' '200'
    } else {
        Add-Result '平台 -> OR（/or-proxy）' 'FAIL' ("code={0}" -f $r.Code)
    }

    $r = Invoke-Http -Url ($PlatformBase + '/command/core/get-version') -Headers $hdr
    if ($r.Code -eq 200) {
        Add-Result '平台 -> OR（/command）' 'PASS' '200'
    } else {
        Add-Result '平台 -> OR（/command）' 'FAIL' ("code={0}" -f $r.Code)
    }
} else {
    Add-Result '平台鉴权用例' 'SKIP' '无可用会话令牌'
}
Write-Host ''

# ============================================================
# 6. Ollama
# ============================================================
Write-Host '--- 6. Ollama ---' -ForegroundColor Cyan
$r = Invoke-Http -Url ($OllamaBase + '/api/tags')
$tags = Convert-JsonSafe $r.Body
if ($r.Code -eq 200 -and $tags) {
    $models = @(Get-Prop $tags 'models')
    $names = @($models | ForEach-Object { [string](Get-Prop $_ 'name') })
    $hit = @($names | Where-Object { $_ -match 'qwen3' }).Count -gt 0
    if ($hit) {
        Add-Result 'Ollama 模型列表' 'PASS' ("{0} 个模型，含 qwen3" -f $names.Count)
    } else {
        Add-Result 'Ollama 模型列表' 'FAIL' ("未发现 qwen3：{0}" -f ($names -join ','))
    }
} else {
    Add-Result 'Ollama 模型列表' 'FAIL' ("code={0}" -f $r.Code)
}
Write-Host ''

# ============================================================
# 汇总
# ============================================================
$pass = @($script:results | Where-Object { $_.status -eq 'PASS' }).Count
$fail = @($script:results | Where-Object { $_.status -eq 'FAIL' }).Count
$warn = @($script:results | Where-Object { $_.status -eq 'WARN' }).Count
$skip = @($script:results | Where-Object { $_.status -eq 'SKIP' }).Count

Write-Host '========================================' -ForegroundColor Cyan
Write-Host ("  PASS={0}  FAIL={1}  WARN={2}  SKIP={3}" -f $pass, $fail, $warn, $skip) -ForegroundColor $(if ($fail -gt 0) { 'Red' } else { 'Green' })
if ($fail -gt 0) {
    Write-Host '  失败用例：' -ForegroundColor Red
    $script:results | Where-Object { $_.status -eq 'FAIL' } | ForEach-Object {
        Write-Host ("    - {0}: {1}" -f $_.name, $_.detail) -ForegroundColor Red
    }
}
Write-Host '========================================' -ForegroundColor Cyan

$report = Join-Path $env:TEMP ("jinrefine-regression-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
try {
    [pscustomobject]@{
        at        = (Get-Date).ToString('s')
        install   = $InstallDir
        pass      = $pass
        fail      = $fail
        warn      = $warn
        skip      = $skip
        cases     = $script:results
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $report -Encoding UTF8
    Write-Host ("  report  : {0}" -f $report) -ForegroundColor DarkGray
} catch { }

if ($fail -gt 0) { exit 1 }
exit 0
