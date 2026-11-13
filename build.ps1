# Builds both APKs with the plain Android SDK tools (no Gradle, no third-party libraries).
$ErrorActionPreference = "Stop"
$root  = $PSScriptRoot
# Machine-specific settings (SDK/JDK paths, keystore password) go in build.local.ps1, which is not committed:
#   $AndroidSdk = "C:\path\to\android-sdk"; $Jdk = "C:\path\to\jdk-17"; $KsPass = "your-password"
$AndroidSdk = $env:ANDROID_HOME
$Jdk        = $env:JAVA_HOME
$KsPass     = $env:QIXING_KS_PASS
if (Test-Path "$root\build.local.ps1") { . "$root\build.local.ps1" }
if (-not $AndroidSdk) { throw "Set ANDROID_HOME or `$AndroidSdk in build.local.ps1" }
if (-not $Jdk)        { throw "Set JAVA_HOME or `$Jdk in build.local.ps1 (JDK 17)" }
if (-not $KsPass)     { $KsPass = "android" }
$bt    = "$AndroidSdk\build-tools\35.0.0"
$jar   = "$AndroidSdk\platforms\android-36\android.jar"
$env:JAVA_HOME = $Jdk
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$out = "$root\out"
$ks  = "$root\signing\qixing.jks"   # created on first build; never commit it
$ksPass = $KsPass

New-Item -ItemType Directory -Force $out, "$root\signing" | Out-Null

# aapt2 cannot open asset folders or android.jar under non-ASCII paths, so stage them in TEMP.
$ascii = Join-Path ([System.IO.Path]::GetTempPath()) "qixing-build"
New-Item -ItemType Directory -Force $ascii | Out-Null
Copy-Item $jar "$ascii\android.jar" -Force
$jar = "$ascii\android.jar"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias qixing -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $ksPass -keypass $ksPass -dname "CN=Qixing HUD, O=Personal" | Out-Null
}

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

function Build-App($name, $targetSdk, $assetsDir) {
    $src  = "$root\$name"
    $work = "$out\$name-build"
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    New-Item -ItemType Directory -Force "$work\classes", "$work\dex" | Out-Null

    $linkArgs = @("link", "-o", "$work\base.apk", "-I", $jar, "--manifest", "$src\AndroidManifest.xml",
        "--min-sdk-version", "28", "--target-sdk-version", "$targetSdk",
        "--version-code", "1", "--version-name", "1.0")
    if ($assetsDir) { $linkArgs += @("-A", $assetsDir) }
    & "$bt\aapt2.exe" @linkArgs
    if ($LASTEXITCODE -ne 0) { throw "aapt2 failed for $name" }

    $javaFiles = @(Get-ChildItem "$src\src", "$root\common\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    # --release 8 supplies java.* (incl. LambdaMetafactory); d8 desugars lambdas for Android.
    & javac -encoding UTF-8 --release 8 -classpath $jar -nowarn `
        -d "$work\classes" @javaFiles
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $name" }

    $classes = @(Get-ChildItem "$work\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
    & "$bt\d8.bat" --release --min-api 28 --lib $jar --output "$work\dex" @classes
    if ($LASTEXITCODE -ne 0) { throw "d8 failed for $name" }

    $zip = [System.IO.Compression.ZipFile]::Open("$work\base.apk", "Update")
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$work\dex\classes.dex", "classes.dex") | Out-Null
    $zip.Dispose()

    & "$bt\zipalign.exe" -f -p 4 "$work\base.apk" "$work\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed for $name" }
    $final = "$out\qixing-$name.apk"
    & "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
        --out $final "$work\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed for $name" }
    & "$bt\apksigner.bat" verify $final
    if ($LASTEXITCODE -ne 0) { throw "verify failed for $name" }
    Write-Host "built $final ($((Get-Item $final).Length) bytes)"
}

Build-App "phone" 33 $null

# The glasses serve the phone APK over HTTP, so bundle it as an asset.
$assets = "$ascii\glasses-assets"
New-Item -ItemType Directory -Force $assets | Out-Null
Copy-Item "$out\qixing-phone.apk" "$assets\phone.apk" -Force
# targetSdk 28 on the glasses so the app may switch Wi-Fi back on (WifiManager.setWifiEnabled).
Build-App "glasses" 28 $assets
