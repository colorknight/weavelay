# WeaveLay portable customer package
# Output: dist/WeaveLay  (no weavelay-tools / private keys)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if ($env:WEAVELAY_JAVA_HOME) { $env:JAVA_HOME = $env:WEAVELAY_JAVA_HOME }
if (-not $env:JAVA_HOME -and (Test-Path "G:\Java\jdk-17.0.10\bin\javac.exe")) {
    $env:JAVA_HOME = "G:\Java\jdk-17.0.10"
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    throw "JDK 17 not found. Set WEAVELAY_JAVA_HOME"
}
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$dist = Join-Path $root "dist\WeaveLay"
$appTarget = Join-Path $root "weavelay-app\target"
$modelSrc = Join-Path $root "weavelay-ocr\models\ppocrv6"
$formulaSrc = Join-Path $root "weavelay-ocr\models\pp-formula"
$formulaDevRoot = "E:\IdeaProjects\pp_formula_to_onnx"

function Ensure-FormulaModels([string]$destDir) {
    $need = @("backbone.onnx", "head_fixed.onnx", "tokenizer.json")
    $allPresent = $true
    foreach ($n in $need) {
        if (-not (Test-Path (Join-Path $destDir $n))) { $allPresent = $false; break }
    }
    if ($allPresent) { return $destDir }

    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    $bb = Join-Path $formulaDevRoot "output\onnx_models\backbone.onnx"
    $hd = Join-Path $formulaDevRoot "output\onnx_models\head_fixed.onnx"
    $tk = Join-Path $formulaDevRoot "ppocr\utils\dict\unimernet_tokenizer\tokenizer.json"
    if (-not (Test-Path $bb) -or -not (Test-Path $hd) -or -not (Test-Path $tk)) {
        throw "missing FormulaNet models. Place backbone.onnx / head_fixed.onnx / tokenizer.json under weavelay-ocr\models\pp-formula"
    }
    Write-Host "  seeding FormulaNet from $formulaDevRoot ..."
    Copy-Item $bb (Join-Path $destDir "backbone.onnx") -Force
    Copy-Item $hd (Join-Path $destDir "head_fixed.onnx") -Force
    Copy-Item $tk (Join-Path $destDir "tokenizer.json") -Force
    return $destDir
}

Write-Host "=== WeaveLay portable package ==="
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Output: $dist"
Write-Host ""

Write-Host "[1/4] mvn package weavelay-app ..."
& mvn -pl weavelay-app -am package -DskipTests -q -f pom.xml
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

$jar = Join-Path $appTarget "weavelay-app-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path (Join-Path $appTarget "lib"))) { throw "missing target/lib" }
if (-not (Test-Path (Join-Path $appTarget "javafx-lib"))) { throw "missing target/javafx-lib" }
if (-not (Test-Path $jar)) { throw "missing app jar" }
if (-not (Test-Path (Join-Path $modelSrc "ppocrv6_medium_det.onnx"))) { throw "missing OCR models" }
$formulaSrc = Ensure-FormulaModels $formulaSrc

Write-Host "[2/4] Assemble app files ..."
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force -Path (Join-Path $dist "app\lib") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $dist "app\javafx-lib") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $dist "models\ppocrv6") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $dist "models\pp-formula") | Out-Null

Copy-Item $jar (Join-Path $dist "app\weavelay-app.jar") -Force
Copy-Item (Join-Path $appTarget "lib\*") (Join-Path $dist "app\lib") -Recurse -Force
Copy-Item (Join-Path $appTarget "javafx-lib\*") (Join-Path $dist "app\javafx-lib") -Recurse -Force
Copy-Item (Join-Path $modelSrc "*") (Join-Path $dist "models\ppocrv6") -Recurse -Force
Copy-Item (Join-Path $formulaSrc "backbone.onnx") (Join-Path $dist "models\pp-formula\") -Force
Copy-Item (Join-Path $formulaSrc "head_fixed.onnx") (Join-Path $dist "models\pp-formula\") -Force
Copy-Item (Join-Path $formulaSrc "tokenizer.json") (Join-Path $dist "models\pp-formula\") -Force
Copy-Item (Join-Path $root "packaging\WeaveLay.bat") (Join-Path $dist "WeaveLay.bat") -Force
Copy-Item (Join-Path $root "packaging\README.txt") (Join-Path $dist "README.txt") -Force

Write-Host "[3/4] Build runtime (jlink) ..."
$runtime = Join-Path $dist "runtime"
if (Test-Path $runtime) { Remove-Item -Recurse -Force $runtime }
$modules = @(
    "java.base", "java.desktop", "java.logging", "java.sql", "java.xml",
    "java.naming", "java.management", "java.prefs", "java.datatransfer",
    "java.net.http", "java.security.sasl", "java.security.jgss",
    "jdk.unsupported", "jdk.crypto.ec", "jdk.zipfs", "jdk.httpserver",
    "java.scripting", "jdk.jfr", "java.compiler", "jdk.unsupported.desktop",
    "jdk.jsobject", "jdk.xml.dom", "java.xml.crypto"
) -join ","

& "$env:JAVA_HOME\bin\jlink.exe" `
    --add-modules $modules `
    --strip-debug --no-header-files --no-man-pages --compress=2 `
    --output $runtime
if ($LASTEXITCODE -ne 0) {
    Write-Host "jlink failed, copying JDK as runtime..."
    if (Test-Path $runtime) { Remove-Item -Recurse -Force $runtime }
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    Copy-Item "$env:JAVA_HOME\*" $runtime -Recurse -Force
}

Write-Host "[4/4] Sanity check ..."
if (Test-Path (Join-Path $dist "weavelay-tools")) { throw "weavelay-tools leaked into dist" }
if (Test-Path (Join-Path $dist "keys\private.ed25519.b64")) { throw "private key leaked into dist" }
if (-not (Test-Path (Join-Path $runtime "bin\java.exe"))) { throw "runtime java missing" }
if (-not (Test-Path (Join-Path $dist "app\weavelay-app.jar"))) { throw "app jar missing" }
if (-not (Test-Path (Join-Path $dist "models\ppocrv6\ppocrv6_medium_det.onnx"))) { throw "models missing" }
if (-not (Test-Path (Join-Path $dist "models\pp-formula\backbone.onnx"))) { throw "formula backbone missing" }
if (-not (Test-Path (Join-Path $dist "models\pp-formula\head_fixed.onnx"))) { throw "formula head missing" }
if (-not (Test-Path (Join-Path $dist "models\pp-formula\tokenizer.json"))) { throw "formula tokenizer missing" }

Write-Host ""
Write-Host "=== DONE ==="
Write-Host "Folder: $dist"
Write-Host "Zip this folder for customers. Keep weavelay-tools private."
Get-ChildItem $dist | Format-Table Name, Mode, Length
Get-ChildItem (Join-Path $dist "models") | Format-Table Name, Mode
$size = (Get-ChildItem $dist -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Total size: {0:N1} MB" -f $size)
