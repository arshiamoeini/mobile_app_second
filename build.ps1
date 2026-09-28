[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "Firmware")]
    [string]$Target = "All",
    [switch]$NoClean
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$Gradlew = Join-Path $ProjectRoot "gradlew.bat"
$ArduinoExe = Join-Path $env:LOCALAPPDATA "LedControllerTools\arduino-cli\arduino-cli.exe"
$Apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
$Sketch = Join-Path $ProjectRoot "firmware\led_controller"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-LastExitCode([string]$Action) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE."
    }
}

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $adoptiumRoot = "C:\Program Files\Eclipse Adoptium"
    if (Test-Path -LiteralPath $adoptiumRoot) {
        $jdk = Get-ChildItem -LiteralPath $adoptiumRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like "jdk-17*" |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($jdk) { return $jdk.FullName }
    }

    throw "JDK 17 was not found. Run setup-and-build.ps1 first."
}

try {
    Write-Step "Checking installed build tools"
    $JavaHome = Find-JavaHome
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:Path = "$JavaHome\bin;$AndroidSdk\platform-tools;$env:Path"

    if ($Target -in @("All", "Android")) {
        if (-not (Test-Path -LiteralPath $Gradlew)) {
            throw "gradlew.bat was not found. Run setup-and-build.ps1 once first."
        }
        if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk "platforms\android-35\android.jar"))) {
            throw "Android SDK Platform 35 was not found. Run setup-and-build.ps1 first."
        }
        if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk "build-tools\35.0.0\aapt2.exe"))) {
            throw "Android Build Tools 35.0.0 were not found. Run setup-and-build.ps1 first."
        }

        Write-Step "Building Android app"
        $Tasks = @("testDebugUnitTest", "lintDebug", "assembleDebug")
        if (-not $NoClean) { $Tasks = @("clean") + $Tasks }

        Push-Location $ProjectRoot
        try {
            & $Gradlew @Tasks --console=plain
            Assert-LastExitCode "Android build"
        } finally {
            Pop-Location
        }

        if (-not (Test-Path -LiteralPath $Apk)) {
            throw "Android build completed but APK was not found at $Apk"
        }
        Write-Host "APK: $Apk" -ForegroundColor Green
    }

    if ($Target -in @("All", "Firmware")) {
        if (-not (Test-Path -LiteralPath $ArduinoExe)) {
            throw "Arduino CLI was not found. Run setup-and-build.ps1 first."
        }
        if (-not (Test-Path -LiteralPath (Join-Path $Sketch "led_controller.ino"))) {
            throw "ESP32 sketch was not found at $Sketch"
        }

        Write-Step "Compiling firmware for classic ESP32"
        & $ArduinoExe compile --fqbn "esp32:esp32:esp32" $Sketch
        Assert-LastExitCode "ESP32 firmware build"
    }

    Write-Host ""
    Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
} catch {
    Write-Host ""
    Write-Host "BUILD FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host "Location: $($_.InvocationInfo.PositionMessage)" -ForegroundColor DarkRed
    exit 1
}
