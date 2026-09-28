[CmdletBinding()]
param(
    [switch]$SkipAndroidBuild,
    [switch]$SkipFirmwareBuild
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolsRoot = Join-Path $env:LOCALAPPDATA "LedControllerTools"
$AndroidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$GradleVersion = "8.9"
$GradleHome = Join-Path $ToolsRoot "gradle-$GradleVersion"
$ArduinoHome = Join-Path $ToolsRoot "arduino-cli"
$TempRoot = Join-Path $env:TEMP "led-controller-setup"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Add-PathEntry([string]$Entry) {
    if (-not (Test-Path -LiteralPath $Entry)) { return }

    $currentEntries = $env:Path -split ";"
    if ($currentEntries -notcontains $Entry) {
        $env:Path = "$Entry;$env:Path"
    }

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $userEntries = if ([string]::IsNullOrWhiteSpace($userPath)) { @() } else { $userPath -split ";" }
    if ($userEntries -notcontains $Entry) {
        $updatedPath = (@($userEntries) + $Entry | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ";"
        [Environment]::SetEnvironmentVariable("Path", $updatedPath, "User")
    }
}

function Download-File([string]$Uri, [string]$Destination) {
    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Write-Host "Downloading: $Uri"
            if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
                & curl.exe --fail --location --retry 3 --output $Destination $Uri
                if ($LASTEXITCODE -ne 0) { throw "curl exited with code $LASTEXITCODE" }
            } else {
                Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Destination
            }
            if (-not (Test-Path -LiteralPath $Destination)) { throw "Downloaded file was not created." }
            if ((Get-Item -LiteralPath $Destination).Length -lt 1MB) { throw "Downloaded file is unexpectedly small." }
            return
        } catch {
            Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
            if ($attempt -eq 3) { throw }
            Write-Warning "Download attempt $attempt failed. Retrying..."
            Start-Sleep -Seconds 2
        }
    }
}

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $roots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $java = Get-ChildItem -LiteralPath $root -Filter java.exe -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "jdk-?17|jdk\\-?17|jdk-17" } |
            Select-Object -First 1
        if ($java) { return (Split-Path -Parent (Split-Path -Parent $java.FullName)) }
    }
    throw "JDK 17 was not found. Install EclipseAdoptium.Temurin.17.JDK first, then open a new PowerShell window."
}

function Assert-LastExitCode([string]$Action) {
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE." }
}

try {
    Write-Step "Checking JDK 17"
    $JavaHome = Find-JavaHome
    $env:JAVA_HOME = $JavaHome
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $JavaHome, "User")
    Add-PathEntry (Join-Path $JavaHome "bin")
    & java -version
    Assert-LastExitCode "Java check"

    New-Item -ItemType Directory -Force -Path $ToolsRoot, $TempRoot | Out-Null

    Write-Step "Installing Android command-line tools"
    $SdkManager = Join-Path $AndroidSdk "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path -LiteralPath $SdkManager)) {
        $AndroidZip = Join-Path $TempRoot "android-command-tools.zip"
        $AndroidExtract = Join-Path $TempRoot "android-command-tools"
        # The direct dl.google.com endpoint returns a regional 404 on some networks.
        # Google's redirector serves the same signed archive reliably.
        $AndroidUrl = "https:" + "//" + "redirector.gvt1.com/edgedl/android/repository/" + "commandlinetools-win-13114758_latest.zip"

        Remove-Item -LiteralPath $AndroidExtract -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $AndroidZip -Force -ErrorAction SilentlyContinue
        Download-File $AndroidUrl $AndroidZip
        Expand-Archive -LiteralPath $AndroidZip -DestinationPath $AndroidExtract -Force

        $LatestTools = Join-Path $AndroidSdk "cmdline-tools\latest"
        Remove-Item -LiteralPath $LatestTools -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $LatestTools | Out-Null
        Copy-Item -Path (Join-Path $AndroidExtract "cmdline-tools\*") -Destination $LatestTools -Recurse -Force
    }
    if (-not (Test-Path -LiteralPath $SdkManager)) { throw "sdkmanager.bat was not installed correctly." }

    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    # Use Google's redirector because dl.google.com returns regional 404/manifest errors here.
    $env:SDK_TEST_BASE_URL = "https:" + "//" + "redirector.gvt1.com/edgedl/android/repository/"
    [Environment]::SetEnvironmentVariable("ANDROID_HOME", $AndroidSdk, "User")
    [Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $AndroidSdk, "User")
    Add-PathEntry (Join-Path $AndroidSdk "cmdline-tools\latest\bin")
    Add-PathEntry (Join-Path $AndroidSdk "platform-tools")

    Write-Step "Accepting Android SDK licenses"
    $LicenseAnswers = (1..100 | ForEach-Object { "y" }) -join [Environment]::NewLine
    $LicenseAnswers | & $SdkManager --sdk_root=$AndroidSdk --licenses | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Automatic license acceptance did not complete. Run this manually:"
        Write-Warning "& `"$SdkManager`" --sdk_root=`"$AndroidSdk`" --licenses"
    }

    Write-Step "Installing Android SDK 35 and platform tools"
    & $SdkManager --sdk_root=$AndroidSdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    Assert-LastExitCode "Android SDK installation"

    Write-Step "Installing Gradle $GradleVersion"
    if (-not (Test-Path -LiteralPath (Join-Path $GradleHome "bin\gradle.bat"))) {
        $GradleZip = Join-Path $TempRoot "gradle-$GradleVersion-bin.zip"
        $GradleUrl = "https:" + "//" + "services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        Remove-Item -LiteralPath $GradleZip -Force -ErrorAction SilentlyContinue
        Download-File $GradleUrl $GradleZip
        Expand-Archive -LiteralPath $GradleZip -DestinationPath $ToolsRoot -Force
    }
    Add-PathEntry (Join-Path $GradleHome "bin")
    & (Join-Path $GradleHome "bin\gradle.bat") --version
    Assert-LastExitCode "Gradle check"

    Write-Step "Preparing the Android project"
    $SdkForGradle = $AndroidSdk.Replace("\", "/")
    Set-Content -LiteralPath (Join-Path $ProjectRoot "local.properties") -Value "sdk.dir=$SdkForGradle" -Encoding ASCII

    $Gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $Gradlew)) {
        Push-Location $ProjectRoot
        try {
            & (Join-Path $GradleHome "bin\gradle.bat") wrapper --gradle-version $GradleVersion --console=plain --stacktrace
            Assert-LastExitCode "Gradle wrapper generation"
        } finally {
            Pop-Location
        }
    }

    if (-not $SkipAndroidBuild) {
        Write-Step "Running Android tests, lint, and APK build"
        Push-Location $ProjectRoot
        try {
            & $Gradlew clean testDebugUnitTest lintDebug assembleDebug
            Assert-LastExitCode "Android build"
        } finally {
            Pop-Location
        }
        $Apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
        if (-not (Test-Path -LiteralPath $Apk)) { throw "Build finished but APK was not found at $Apk" }
        Write-Host "APK: $Apk" -ForegroundColor Green
    }

    Write-Step "Installing Arduino CLI"
    $ArduinoExe = Join-Path $ArduinoHome "arduino-cli.exe"
    if (-not (Test-Path -LiteralPath $ArduinoExe)) {
        $ArduinoZip = Join-Path $TempRoot "arduino-cli.zip"
        # downloads.arduino.cc returns 403 on this network; use Arduino's official GitHub release.
        $ArduinoUrl = "https:" + "//" + "github.com/arduino/arduino-cli/releases/download/v1.5.0/arduino-cli_1.5.0_Windows_64bit.zip"
        Remove-Item -LiteralPath $ArduinoZip -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $ArduinoHome | Out-Null
        Download-File $ArduinoUrl $ArduinoZip
        Expand-Archive -LiteralPath $ArduinoZip -DestinationPath $ArduinoHome -Force
    }
    if (-not (Test-Path -LiteralPath $ArduinoExe)) { throw "arduino-cli.exe was not installed correctly." }
    Add-PathEntry $ArduinoHome
    & $ArduinoExe version
    Assert-LastExitCode "Arduino CLI check"

    Write-Step "Installing Espressif ESP32 Core 3.0.7"
    $ArduinoConfig = Join-Path $env:USERPROFILE ".arduino15\arduino-cli.yaml"
    if (-not (Test-Path -LiteralPath $ArduinoConfig)) {
        & $ArduinoExe config init
        Assert-LastExitCode "Arduino CLI configuration"
    }
    $EspressifIndex = "https:" + "//" + "espressif.github.io/arduino-esp32/package_esp32_index.json"
    $ConfigDump = (& $ArduinoExe config dump) -join "`n"
    if ($ConfigDump -notmatch [regex]::Escape($EspressifIndex)) {
        & $ArduinoExe config add board_manager.additional_urls $EspressifIndex
        Assert-LastExitCode "Adding Espressif board index"
    }
    & $ArduinoExe core update-index
    Assert-LastExitCode "Arduino core index update"
    & $ArduinoExe core install "esp32:esp32@3.0.7"
    Assert-LastExitCode "ESP32 Core installation"

    if (-not $SkipFirmwareBuild) {
        Write-Step "Compiling firmware for classic ESP32"
        $Sketch = Join-Path $ProjectRoot "firmware\led_controller"
        & $ArduinoExe compile --fqbn "esp32:esp32:esp32" $Sketch
        Assert-LastExitCode "ESP32 firmware build"
    }

    Write-Host ""
    Write-Host "Setup and builds completed successfully." -ForegroundColor Green
    if (-not $SkipAndroidBuild) {
        Write-Host "APK: $(Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk')" -ForegroundColor Green
    }
    Write-Host "Before uploading firmware, change DEVICE_PASSKEY in firmware\led_controller\led_controller.ino." -ForegroundColor Yellow
} catch {
    Write-Host ""
    Write-Host "SETUP FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host "Location: $($_.InvocationInfo.PositionMessage)" -ForegroundColor DarkRed
    exit 1
}
