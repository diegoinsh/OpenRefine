<#
RuyiRefine Windows packaging + Virbox Java BCE protection build script.

Prerequisites:
1. Maven is installed and available in PATH.
2. Virbox Protector is installed and virboxprotector_con.exe path is known.
3. Before first use, add the following line to conf/ruyirefine.l4j.ini:
   -javaagent:server/target/lib/sjt_agent.jar
   So that ruyirefine.exe automatically loads the Virbox Java Agent.
4. If you want to fix the Java protection password, pass it via -JavaPassword.
5. A Virbox Java BCE project configuration (.ssp) has been created and saved
   by the GUI under the VirboxJavaDir directory (see parameters below).

Typical usage (run in the repository root PowerShell):

  scripts/build_ruyirefine_win_with_virbox.ps1 `
    -VirboxCliPath "C:\Program Files (x86)\senseshield\sdk\Tool\VirboxProtect\bin\virboxprotector_con.exe" `
    -JavaPassword "your-pass" `
    -Platforms "windows-x64" `
    -VirboxCloudUser "******" `
    -VirboxPin "1234"

NOTE: This script intentionally uses only ASCII characters to avoid encoding
      issues on older PowerShell versions.
#>
param(
	[string]$VirboxCliPath    = 'C:\Program Files (x86)\senseshield\sdk\Tool\VirboxProtect\bin64\VirboxProtector_con.exe',
	[string]$JavaPassword,
	[string]$Platforms        = 'windows-x64',
	# Virbox Java project directory (where jars will be copied and .ssp must exist)
	[string]$VirboxJavaDir    = 'G:\workshop\RUYI\deploy\ruyirefine\java_source',
	# Virbox output directory (protected jars and sjt_agent.jar will be written here)
	[string]$VirboxOutputDir  = 'G:\workshop\RUYI\deploy\ruyirefine\java_bce_output',
	# Stable jar file name used inside Virbox project (.ssp must refer to this name)
	[string]$StableJarName    = 'ruyirefine-server.jar',
	# Virbox license / cloud parameters (optional, but required in cloud mode)
	[string]$VirboxCloudMode  = 'cloud',
	[string]$VirboxCloudUser,
	[string]$VirboxPin
)

$ErrorActionPreference = 'Stop'

# Script directory = the directory containing this script
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
# Repository root = parent of the script directory
$RepoRoot  = Split-Path -Parent $ScriptDir
Set-Location $RepoRoot

Write-Host '[1/6] Running Maven packaging for Windows...' -ForegroundColor Cyan

# Build ALL modules first (including extensions like records-assets) so that
# their jars are copied into module/MOD-INF/lib before assembly packaging.
$mvnCmd = 'mvn package -DskipTests -P windows'
& cmd /c $mvnCmd
if ($LASTEXITCODE -ne 0) {
    throw 'Maven packaging failed.'
}

Write-Host '[2/6] Locating latest Windows zip artifact...' -ForegroundColor Cyan
$zip = Get-ChildItem 'packaging/target' -Filter 'ruyirefine-win-with-java-*.zip' |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1
if (-not $zip) {
    throw 'No ruyirefine-win-with-java-*.zip found under packaging/target.'
}
Write-Host "  Using zip: $($zip.FullName)"

Write-Host '[3/6] Extracting zip to temporary release directory...' -ForegroundColor Cyan
$workDir = Join-Path 'packaging/target' 'ruyirefine_release'
if (Test-Path $workDir) {
    Remove-Item $workDir -Recurse -Force
}
New-Item $workDir -ItemType Directory | Out-Null

Expand-Archive -Path $zip.FullName -DestinationPath $workDir -Force

# Root directory inside extracted zip (for example ruyirefine-3.10-SNAPSHOT)
$rootDir = Get-ChildItem $workDir | Where-Object { $_.PSIsContainer } | Select-Object -First 1
if (-not $rootDir) {
    throw 'No root directory found after extraction.'
}
Write-Host "  Root dir: $($rootDir.FullName)"

$serverLibDir = Join-Path $rootDir.FullName 'server/target/lib'
if (-not (Test-Path $serverLibDir)) {
	throw "Directory server/target/lib not found: $serverLibDir"
}

# Webapp and extension lib directories
$webInfLibDir        = Join-Path $rootDir.FullName 'webapp/WEB-INF/lib'
$recordsDbLibDir     = Join-Path $rootDir.FullName 'webapp/extensions/records-db/module/MOD-INF/lib'
$recordsAssetsLibDir = Join-Path $rootDir.FullName 'webapp/extensions/records-assets/module/MOD-INF/lib'

foreach ($dir in @($webInfLibDir, $recordsDbLibDir, $recordsAssetsLibDir)) {
    if (-not (Test-Path $dir)) {
        throw "Required directory not found: $dir"
    }
}

# Locate all jars that need protection in the extracted release tree
$serverJar = Get-ChildItem $serverLibDir -Filter 'openrefine-*-server.jar' | Select-Object -First 1
if (-not $serverJar) {
	throw "openrefine-*-server.jar not found under $serverLibDir."
}

$coreJar = Get-ChildItem $webInfLibDir -Filter 'core-*.jar' | Select-Object -First 1
if (-not $coreJar) {
	throw "core-*.jar not found under $webInfLibDir."
}

$mainJar = Get-ChildItem $webInfLibDir -Filter 'openrefine-main.jar' | Select-Object -First 1
if (-not $mainJar) {
	throw "openrefine-main.jar not found under $webInfLibDir."
}

$grelJar = Get-ChildItem $webInfLibDir -Filter 'grel-*.jar' | Select-Object -First 1
if (-not $grelJar) {
	throw "grel-*.jar not found under $webInfLibDir."
}

$recordsDbJar = Get-ChildItem $recordsDbLibDir -Filter 'openrefine-records-db.jar' | Select-Object -First 1
if (-not $recordsDbJar) {
	throw "openrefine-records-db.jar not found under $recordsDbLibDir."
}

$recordsAssetsJar = Get-ChildItem $recordsAssetsLibDir -Filter 'ruyirefine.jar' | Select-Object -First 1
if (-not $recordsAssetsJar) {
	throw "ruyirefine.jar not found under $recordsAssetsLibDir."
}

# Specification for all jars that will be protected by Virbox (original path + stable name)
$jarSpecs = @(
	[pscustomobject]@{ Key = 'server';         OriginalPath = $serverJar.FullName;         StableName = $StableJarName },
	[pscustomobject]@{ Key = 'core';           OriginalPath = $coreJar.FullName;           StableName = 'ruyirefine-core.jar' },
	[pscustomobject]@{ Key = 'main';           OriginalPath = $mainJar.FullName;           StableName = 'ruyirefine-main.jar' },
	[pscustomobject]@{ Key = 'grel';           OriginalPath = $grelJar.FullName;           StableName = 'ruyirefine-grel.jar' },
	[pscustomobject]@{ Key = 'records-db';     OriginalPath = $recordsDbJar.FullName;      StableName = 'ruyirefine-records-db.jar' },
	[pscustomobject]@{ Key = 'records-assets'; OriginalPath = $recordsAssetsJar.FullName;  StableName = 'ruyirefine-records-assets.jar' }
)

Write-Host '[4/6] Preparing Virbox project/input/output directories...' -ForegroundColor Cyan

# Ensure Virbox Java project dir exists (must also contain the .ssp configuration)
if (-not (Test-Path $VirboxJavaDir)) {
    New-Item $VirboxJavaDir -ItemType Directory | Out-Null
}

# Clean previous protected output and recreate
if (Test-Path $VirboxOutputDir) {
    Remove-Item $VirboxOutputDir -Recurse -Force
}
New-Item $VirboxOutputDir -ItemType Directory | Out-Null

# Remove ALL old jars in project dir to avoid ambiguity, but keep .ssp and other config
Get-ChildItem $VirboxJavaDir -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Write-Host "  Cleaned old jars in $VirboxJavaDir"

# Copy current jars into Virbox project dir with their stable names
foreach ($spec in $jarSpecs) {
	$stableJarPath = Join-Path $VirboxJavaDir $spec.StableName
	Copy-Item $spec.OriginalPath -Destination $stableJarPath -Force
}

if (-not (Test-Path $VirboxCliPath)) {
    throw "Virbox CLI tool not found: $VirboxCliPath"
}

Write-Host '[5/6] Protecting server jar with Virbox Protector (Java BCE)...' -ForegroundColor Cyan
Write-Host "  Jar directory: $VirboxJavaDir"

# Use directory path - Virbox will auto-detect .ssp file in that directory
$arguments = @(
	'-java', $VirboxJavaDir
)

if ($VirboxCloudMode) {
	$arguments += @('-c', $VirboxCloudMode)
}
if ($VirboxCloudUser) {
	$arguments += @('-u', $VirboxCloudUser)
}
if ($VirboxPin) {
	$arguments += @('-p', $VirboxPin)
}

$arguments += @(
	'-o', $VirboxOutputDir,
	"--platforms=$Platforms"
)
if ($JavaPassword) {
	$arguments += "--java-pass=$JavaPassword"
}

& $VirboxCliPath @arguments
if ($LASTEXITCODE -ne 0) {
    throw 'Virbox Protector returned non-zero exit code.'
}

# For each jar spec, locate the protected jar by stable name in the Virbox output directory
$jarSpecs = $jarSpecs | ForEach-Object {
	$spec = $_
	$expected = $spec.StableName
	$protected = Get-ChildItem $VirboxOutputDir -Filter $expected -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
	if (-not $protected) {
		throw "Protected jar for '$($spec.Key)' ($expected) not found under Virbox output directory."
	}
	Add-Member -InputObject $spec -NotePropertyName ProtectedPath -NotePropertyValue $protected.FullName -Force | Out-Null
	$spec
}

$sjtAgent = Get-ChildItem $VirboxOutputDir -Filter 'sjt_agent.jar' -Recurse | Select-Object -First 1
if (-not $sjtAgent) {
    throw 'sjt_agent.jar not found under Virbox output directory.'
}

# Overwrite each original jar in the release tree with its protected version
foreach ($spec in $jarSpecs) {
	Copy-Item $spec.ProtectedPath -Destination $spec.OriginalPath -Force
}

# Copy sjt_agent.jar into server lib dir (used by -javaagent in ruyirefine.l4j.ini)
Copy-Item $sjtAgent.FullName -Destination $serverLibDir -Force

Write-Host '[6/6] Re-packaging protected Windows release zip...' -ForegroundColor Cyan

$timestamp    = Get-Date -Format 'yyyyMMdd_HHmmss'
$finalZipName = "ruyirefine-win-with-java-protected-$($rootDir.Name)-$timestamp.zip"
$finalZip     = Join-Path 'packaging/target' $finalZipName

if (Test-Path $finalZip) {
    Remove-Item $finalZip -Force
}

Compress-Archive -Path $rootDir.FullName -DestinationPath $finalZip

Write-Host 'Done. Protected release zip:' -ForegroundColor Green
Write-Host "  $finalZip" -ForegroundColor Green
