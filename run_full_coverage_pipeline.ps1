param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$PackageName,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirName,

    [Parameter(Mandatory = $false)]
    [string]$LogTag = "ANDROLOG",

    [Parameter(Mandatory = $false)]
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-Warning-Custom {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Test-CommandExists {
    param([string]$CommandName)
    return [bool](Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Write-AsciiFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [AllowNull()]
        [AllowEmptyCollection()]
        [object[]]$Lines
    )

    $encoding = [System.Text.Encoding]::ASCII

    if ($null -eq $Lines) {
        $Lines = @()
    }

    $stringLines = @()
    foreach ($line in $Lines) {
        if ($null -eq $line) {
            continue
        }
        $stringLines += [string]$line
    }

    [System.IO.File]::WriteAllLines($Path, $stringLines, $encoding)
}

function Normalize-LogFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return
    }

    $lines = Get-Content -Path $Path -ErrorAction SilentlyContinue
    if ($null -eq $lines) {
        $lines = @()
    }

    if ($lines.Count -gt 0) {
        $lines[0] = $lines[0].TrimStart([char]0xFEFF)
    }

    [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.Encoding]::ASCII)
}

function Resolve-DeviceId {
    param(
        [string]$RequestedDevice,
        [string]$EmulatorExe
    )

    if ($RequestedDevice) {
        return $RequestedDevice
    }

    if ($env:ANDROLOG_DEVICE_ID) {
        return $env:ANDROLOG_DEVICE_ID
    }

    $devicesOutput = adb devices 2>$null | Where-Object { $_ -match "^\S+\s+device$" }
    $devices = @($devicesOutput | ForEach-Object { ($_ -split "\s+")[0] })
    $count = $devices.Count

    if ($count -eq 1) {
        return $devices[0]
    }

    if ($count -eq 0) {
        Write-Error-Custom "No online emulator/device found"

        if (Test-Path $EmulatorExe) {
            $avdName = (& $EmulatorExe -list-avds 2>$null | Select-Object -First 1)
            if ($avdName) {
                Write-Info "Start one with:"
                Write-Host "  `"$EmulatorExe`" -avd $avdName -no-snapshot -writable-system -port 5560"
            }
        }

        return $null
    }

    Write-Info "Multiple devices detected. Select one:"
    for ($i = 0; $i -lt $count; $i++) {
        Write-Host "  [$($i + 1)] $($devices[$i])"
    }

    $selection = Read-Host "Choose device number"
    if (-not ($selection -match '^\d+$')) {
        Write-Error-Custom "Invalid selection"
        return $null
    }

    $index = [int]$selection - 1
    if ($index -lt 0 -or $index -ge $count) {
        Write-Error-Custom "Invalid selection"
        return $null
    }

    return $devices[$index]
}

try {
    # -------- Configuration --------
    $ScriptDir = $PSScriptRoot
    $AndroidSdkRoot = "C:\Users\liqitang\AppData\Local\Android\Sdk"
    $PlatformsPath = Join-Path $AndroidSdkRoot "platforms"
    $EmulatorExe = Join-Path $AndroidSdkRoot "emulator\emulator.exe"
    $OutputBase = Join-Path $ScriptDir "fse-dataset\instrumented_apk"
    $OutputDir = Join-Path $OutputBase $OutputDirName
    $JarPath = Join-Path $ScriptDir "target\androlog-0.1-jar-with-dependencies.jar"

    $BuildToolsVersion = "36.1.0"
    $BuildToolsPath = Join-Path $AndroidSdkRoot "build-tools\$BuildToolsVersion"
    $ZipalignExe = Join-Path $BuildToolsPath "zipalign.exe"
    $ApkSignerExe = Join-Path $BuildToolsPath "apksigner.bat"
    $DebugKeystore = Join-Path $env:USERPROFILE ".android\debug.keystore"

    # -------- Pre-checks --------
    if (-not (Test-Path $ApkPath)) {
        Write-Error-Custom "APK file not found: $ApkPath"
        exit 1
    }

    if (-not (Test-Path $JarPath)) {
        Write-Error-Custom "AndroLog jar not found: $JarPath"
        exit 1
    }

    if (-not (Test-Path $PlatformsPath)) {
        Write-Error-Custom "Android SDK platforms path not found: $PlatformsPath"
        exit 1
    }

    if (-not (Test-Path $ZipalignExe)) {
        Write-Error-Custom "zipalign not found: $ZipalignExe"
        exit 1
    }

    if (-not (Test-Path $ApkSignerExe)) {
        Write-Error-Custom "apksigner not found: $ApkSignerExe"
        exit 1
    }

    if (-not (Test-Path $DebugKeystore)) {
        Write-Error-Custom "Debug keystore not found: $DebugKeystore"
        Write-Host 'Create it with:'
        Write-Host 'keytool -genkeypair -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"'
        exit 1
    }

    if (-not (Test-CommandExists "java")) {
        Write-Error-Custom "java not found in PATH"
        exit 1
    }

    if (-not (Test-CommandExists "adb")) {
        Write-Error-Custom "adb not found in PATH"
        exit 1
    }

    $ApkFilename = Split-Path $ApkPath -Leaf
    $ApkName = [System.IO.Path]::GetFileNameWithoutExtension($ApkFilename)

    Write-Info "=========================================="
    Write-Info "AndroLog Coverage Pipeline"
    Write-Info "=========================================="
    Write-Info "APK: $ApkFilename"
    Write-Info "Package: $PackageName"
    Write-Info "Output: $OutputDir"
    Write-Info "Log Tag: $LogTag"
    Write-Info "Platforms: $PlatformsPath"
    Write-Info "=========================================="

    $platformDirs = Get-ChildItem -Path $PlatformsPath -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
    if ($platformDirs) {
        Write-Info "Installed SDK platforms: $($platformDirs -join ', ')"
    }

    $Android33Jar = Join-Path $PlatformsPath "android-33\android.jar"
    if (-not (Test-Path $Android33Jar)) {
        Write-Warning-Custom "android-33\android.jar not found."
        Write-Warning-Custom "If AndroLog/Soot asks for API 33, install Android SDK Platform 33 in Android Studio SDK Manager."
    }

    # -------- Step 1: Instrument APK --------
    Write-Info "Step 1/6: Instrumenting APK..."
    if (Test-Path $OutputDir) {
        Remove-Item -Recurse -Force $OutputDir
    }
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

    & java -jar "$JarPath" `
        -p "$PlatformsPath" `
        -a "$ApkPath" `
        -o "$OutputDir" `
        -c -m -s -b `
        -l "$LogTag"

    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "Instrumentation failed"
        exit 1
    }
    Write-Success "APK instrumented successfully"

    $UnsignedApk = Join-Path $OutputDir $ApkFilename
    if (-not (Test-Path $UnsignedApk)) {
        Write-Error-Custom "Instrumented APK not found at: $UnsignedApk"
        exit 1
    }

    # -------- Step 1.5: Align and sign APK --------
    Write-Info "Step 1.5/6: Aligning and signing instrumented APK..."

    $AlignedApk = Join-Path $OutputDir "$ApkName-aligned.apk"

    if (Test-Path $AlignedApk) {
        Remove-Item -Force $AlignedApk
    }

    & "$ZipalignExe" -f -p 4 "$UnsignedApk" "$AlignedApk"
    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "zipalign failed"
        exit 1
    }

    & "$ApkSignerExe" sign `
        --ks "$DebugKeystore" `
        --ks-pass pass:android `
        --key-pass pass:android `
        "$AlignedApk"

    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "APK signing failed"
        exit 1
    }

    & "$ApkSignerExe" verify --verbose "$AlignedApk"
    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "APK signature verification failed"
        exit 1
    }

    Write-Success "APK aligned and signed successfully"

    $InstrumentedApk = $AlignedApk

    # -------- Step 2: Select and check emulator/device --------
    Write-Info "Step 2/6: Selecting and checking emulator connection..."
    $AdbDevice = Resolve-DeviceId -RequestedDevice $DeviceId -EmulatorExe $EmulatorExe
    if (-not $AdbDevice) {
        Write-Error-Custom "Failed to resolve device ID"
        exit 1
    }

    Write-Info "Using device: $AdbDevice"
    $deviceState = adb -s "$AdbDevice" get-state 2>$null
    if ($LASTEXITCODE -ne 0 -or $deviceState -notmatch "device") {
        Write-Error-Custom "Device $AdbDevice is not responding. Please check the device."
        exit 1
    }
    Write-Success "Device connected: $AdbDevice"

    # -------- Step 3: Install instrumented APK --------
    Write-Info "Step 3/6: Installing instrumented APK..."
    Write-Info "Uninstalling existing app..."
    adb -s "$AdbDevice" uninstall "$PackageName" 2>$null | Out-Null

    Write-Info "Installing new APK..."
    adb -s "$AdbDevice" install --no-incremental -r "$InstrumentedApk"
    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "Installation failed"
        exit 1
    }
    Write-Success "APK installed successfully"

    # -------- Step 4: Launch app and wait for manual interaction --------
    Write-Info "Step 4/6: Running application and collecting logs..."
    Write-Info "Clearing logcat buffer..."
    adb -s "$AdbDevice" logcat -c
    adb -s "$AdbDevice" logcat -b main -c
    adb -s "$AdbDevice" logcat -b system -c
    adb -s "$AdbDevice" logcat -b crash -c

    Write-Info "Launching application..."

    $LaunchActivity = adb -s "$AdbDevice" shell cmd package resolve-activity --brief "$PackageName" 2>$null |
        Select-Object -Last 1

    if (-not $LaunchActivity -or $LaunchActivity -notmatch "/") {
        Write-Warning-Custom "Could not resolve launcher activity automatically. Trying monkey fallback..."
        adb -s "$AdbDevice" shell "monkey -p $PackageName -c android.intent.category.LAUNCHER 1" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Error-Custom "Failed to launch app with both am start and monkey."
            exit 1
        }
    }
    else {
        $LaunchActivity = $LaunchActivity.Trim()
        Write-Info "Resolved launcher activity: $LaunchActivity"
        adb -s "$AdbDevice" shell am start -n "$LaunchActivity" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Error-Custom "Failed to launch app via am start: $LaunchActivity"
            exit 1
        }
    }

    Write-Info "Waiting for application to initialize (15 seconds)..."
    Start-Sleep -Seconds 15

    Write-Warning-Custom "=========================================="
    Write-Warning-Custom "Application is now running!"
    Write-Warning-Custom "Please interact with the app to generate coverage data."
    Write-Warning-Custom "Press ENTER when you're done testing..."
    Write-Warning-Custom "=========================================="
    Read-Host | Out-Null

    # -------- Step 5: Fetch logs --------
    Write-Info "Step 5/6: Fetching logs from device..."
    $LogFile = Join-Path $OutputDir "${OutputDirName}_logs.txt"
    $FullLogcat = Join-Path $OutputDir "${OutputDirName}_full_logcat.txt"

    Write-Info "Dumping only tagged logs for $LogTag ..."
    cmd /c "adb -s $AdbDevice logcat -b main -b system -b crash -d -v time | findstr /C:`"$LogTag`" > `"$LogFile`""

    if (-not (Test-Path $LogFile)) {
        New-Item -ItemType File -Path $LogFile -Force | Out-Null
    }

    Normalize-LogFile -Path $LogFile

    $LogCount = 0
    if (Test-Path $LogFile) {
        $LogCount = (Get-Content $LogFile -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
    }

    Write-Success "Extracted $LogCount log entries"

    if ($LogCount -eq 0) {
        Write-Warning-Custom "No instrumentation logs found. Check whether the app ran correctly and whether the log tag matches."
    }

    # -------- Step 6: Generate coverage report --------
    Write-Info "Step 6/6: Generating coverage report..."
    $CoverageOutput = Join-Path $OutputDir "coverage_report.json"
    $CfgOutput = Join-Path $OutputDir "processing.cfg"

    Normalize-LogFile -Path $LogFile

    & java -jar "$JarPath" `
        -p "$PlatformsPath" `
        -a "$ApkPath" `
        -l "$LogTag" `
        -c -m -s -b `
        -pa "$LogFile" `
        -j "$CoverageOutput" `
        -cfg "$CfgOutput"

    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "Coverage report generation failed"
        exit 1
    }

    Write-Success "Coverage report generated: $CoverageOutput"

    # -------- Generate text summary --------
    Write-Info "Generating text summary..."
    $SummaryFile = Join-Path $OutputDir "coverage_summary.txt"

    $summaryLines = @( & java -jar "$JarPath" `
        -p "$PlatformsPath" `
        -a "$ApkPath" `
        -l "$LogTag" `
        -c -m -s -b `
        -pa "$LogFile" `
        -cfg "$CfgOutput" )

    Write-AsciiFile -Path $SummaryFile -Lines $summaryLines

    if ($LASTEXITCODE -ne 0) {
        Write-Warning-Custom "Coverage summary generation returned a non-zero exit code."
    }

    # -------- Final summary --------
    Write-Success "=========================================="
    Write-Success "Pipeline completed successfully!"
    Write-Success "=========================================="
    Write-Info "Output directory: $OutputDir"
    Write-Info "Files generated:"
    Write-Info "  - Instrumented APK: $InstrumentedApk"
    Write-Info "  - Full logcat: $FullLogcat"
    Write-Info "  - Filtered logs: $LogFile ($LogCount entries)"
    Write-Info "  - Coverage JSON: $CoverageOutput"
    Write-Info "  - Processing CFG: $CfgOutput"
    Write-Info "  - Coverage summary: $SummaryFile"
    Write-Success "=========================================="
}
catch {
    Write-Error-Custom $_.Exception.Message
    exit 1
}