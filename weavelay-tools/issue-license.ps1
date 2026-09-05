# VENDOR ONLY — do not ship to customers.
# Default: 365-day licence. See 授权签发说明.md
#
#   .\weavelay-tools\issue-license.ps1 -MachineId <hex> -Out moss.weavelaylic -Customer moss
#   .\weavelay-tools\issue-license.ps1 -MachineId <hex> -Out trial.weavelaylic -Days 90
#   .\weavelay-tools\issue-license.ps1 -MachineId <hex> -Out year.weavelaylic -Until 2027-03-31
#   .\weavelay-tools\issue-license.ps1 -MachineId <hex> -Out forever.weavelaylic -Perpetual

param(
    [Parameter(Mandatory = $true)]
    [string] $MachineId,

    [string] $Out = "license.weavelaylic",

    [string] $Customer = "",

    [int] $Days = 365,

    [string] $Until = "",

    [switch] $Perpetual
)

$ErrorActionPreference = "Stop"
$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $toolsDir "..")
Set-Location $root

$env:JAVA_HOME = "G:\Java\jdk-17.0.10"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$key = Join-Path $toolsDir "keys\private.ed25519.b64"
if (-not (Test-Path $key)) {
    Write-Error "Private key not found: $key"
}

$issuedDir = Join-Path $toolsDir "issued"
New-Item -ItemType Directory -Force -Path $issuedDir | Out-Null

if ([System.IO.Path]::IsPathRooted($Out)) {
    $outPath = $Out
} else {
    $outPath = Join-Path $issuedDir $Out
}

$env:WEAVELAY_LICENSE_PRIVATE_KEY_FILE = $key

$argList = @("issue", "--machine", $MachineId, "--out", $outPath)
if ($Customer) {
    $argList += @("--customer", $Customer)
}
if ($Perpetual -and ($Until -or $PSBoundParameters.ContainsKey("Days"))) {
    throw "Use -Perpetual alone, or -Days / -Until (not together)."
}
if ($Until -and $PSBoundParameters.ContainsKey("Days")) {
    throw "Use either -Days or -Until, not both."
}
if ($Perpetual) {
    $argList += @("--perpetual")
} elseif ($Until) {
    $argList += @("--until", $Until)
} else {
    if ($Days -le 0) {
        throw "-Days must be > 0, or pass -Perpetual."
    }
    $argList += @("--days", "$Days")
}
$execArgs = ($argList -join " ")

Write-Host "Issuing licence..."
Write-Host "  machine=$MachineId"
Write-Host "  out=$outPath"
if ($Customer) { Write-Host "  customer=$Customer" }
if ($Perpetual) {
    Write-Host "  expires=perpetual"
} elseif ($Until) {
    Write-Host "  until=$Until"
} else {
    Write-Host "  days=$Days"
}
Write-Host ""
Write-Host "Please wait - first run may take ~1 minute to compile..."
Write-Host ""

mvn -q -pl weavelay-tools -am package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

mvn -q -pl weavelay-tools exec:java "-Dexec.args=$execArgs"
if ($LASTEXITCODE -ne 0) { throw "issue failed" }

Write-Host ""
Write-Host "Done. Licence file:"
Write-Host "  $outPath"
if (Test-Path $outPath) {
    explorer.exe /select,$outPath
}
