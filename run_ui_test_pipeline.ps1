param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$TestApkPath,

    [Parameter(Mandatory = $true)]
    [string]$TestRunner,

    [string]$PackageName = "",
    [string]$OutputDirName = "ui_test_run",
    [string]$BranchLogTag = "ANDROLOG_UI_BRANCH",
    [string]$UiLogTag = "ANDROLOG_UI_ACTION",
    [string]$DeviceId = "",
    [switch]$SkipCoverage,
    [switch]$ManualUiTest,
    [string]$LaunchActivity = ""
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

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Write-Err {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Resolve-AndroidSdkRoot {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }

    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }

    $fallback = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk"
    if (Test-Path $fallback) {
        return $fallback
    }

    return ""
}

function Resolve-Device {
    param([string]$RequestedDevice)

    if ($RequestedDevice) {
        return $RequestedDevice
    }

    $devices = @(adb devices | Where-Object { $_ -match "^\S+\s+device$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devices.Count -eq 0) {
        return ""
    }

    return $devices[0]
}

function Invoke-Adb {
    param(
        [string]$Device,
        [string[]]$AdbArgs
    )

    if ($Device) {
        & adb -s $Device @AdbArgs
    } else {
        & adb @AdbArgs
    }
}

function Get-PackageNameFromApk {
    param(
        [string]$AaptExe,
        [string]$TargetApk
    )

    $line = & "$AaptExe" dump badging "$TargetApk" | Select-String "package: name=" | Select-Object -First 1
    if (-not $line) {
        return ""
    }

    if ($line -match "name='([^']+)'") {
        return $Matches[1]
    }

    return ""
}

function Get-LaunchableActivityFromApk {
    param(
        [string]$AaptExe,
        [string]$TargetApk
    )

    $line = & "$AaptExe" dump badging "$TargetApk" | Select-String "launchable-activity:" | Select-Object -First 1
    if (-not $line) {
        return ""
    }

    if ($line -match "name='([^']+)'") {
        return $Matches[1]
    }

    return ""
}

function Test-IsSplitRequiredApk {
    param(
        [string]$AaptExe,
        [string]$TargetApk
    )

    $xml = & "$AaptExe" dump xmltree "$TargetApk" AndroidManifest.xml
    return (($xml | Select-String "android:isSplitRequired" | Measure-Object).Count -gt 0)
}

function Get-DeviceEpochOffsetMs {
    param([string]$Device)

    $uptimeLine = Invoke-Adb -Device $Device -AdbArgs @("shell", "cat", "/proc/uptime") | Select-Object -First 1
    if (-not $uptimeLine) {
        return 0
    }

    if ($uptimeLine -match "^([0-9]+\.[0-9]+)") {
        $uptimeMs = [double]$Matches[1] * 1000.0
        $nowMs = [double]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
        return [long]($nowMs - $uptimeMs)
    }

    return 0
}

function Convert-TouchEventsToUiActions {
    param(
        [string]$RawTouchPath,
        [string]$OutUiPath,
        [long]$EpochOffsetMs
    )

    if (-not (Test-Path $RawTouchPath)) {
        return 0
    }

    $currentX = $null
    $currentY = $null
    $touching = $false
    $touchStartTs = $null
    $step = 0
    $outLines = New-Object System.Collections.Generic.List[string]

    foreach ($line in Get-Content $RawTouchPath) {
        $lineTsMs = $null
        if ($line -match "^\[\s*([0-9]+\.[0-9]+)\]") {
            $lineTsMs = [long]($EpochOffsetMs + ([double]$Matches[1] * 1000.0))
        }

        if ($line -match "ABS_MT_POSITION_X\s+([0-9a-fA-F]+)") {
            $currentX = [Convert]::ToInt32($Matches[1], 16)
            continue
        }

        if ($line -match "ABS_MT_POSITION_Y\s+([0-9a-fA-F]+)") {
            $currentY = [Convert]::ToInt32($Matches[1], 16)
            continue
        }

        if ($line -match "ABS_MT_PRESSURE\s+([0-9a-fA-F]+)") {
            $pressure = [Convert]::ToInt32($Matches[1], 16)

            if ($pressure -gt 0 -and -not $touching) {
                $touching = $true
                $touchStartTs = $lineTsMs
                continue
            }

            if ($pressure -eq 0 -and $touching -and $null -ne $currentX -and $null -ne $currentY) {
                $step += 1
                $emitTs = if ($lineTsMs) { $lineTsMs } else { $touchStartTs }
                $outLines.Add("UIACTION|TS=$emitTs|SESSION=manual_touch|STEP=$step|ACTIVITY=unknown|TYPE=CLICK|TARGET=x${currentX}_y${currentY}")
                $touching = $false
                $touchStartTs = $null
                continue
            }

            continue
        }

        # Fallback for devices that expose BTN_TOUCH rather than ABS_MT_PRESSURE.
        if ($line -match "BTN_TOUCH\s+DOWN") {
            $touching = $true
            $touchStartTs = $lineTsMs
            continue
        }

        if ($line -match "BTN_TOUCH\s+UP") {
            if ($touching -and $null -ne $currentX -and $null -ne $currentY) {
                $step += 1
                $emitTs = if ($lineTsMs) { $lineTsMs } else { $touchStartTs }
                $outLines.Add("UIACTION|TS=$emitTs|SESSION=manual_touch|STEP=$step|ACTIVITY=unknown|TYPE=CLICK|TARGET=x${currentX}_y${currentY}")
            }
            $touching = $false
            $touchStartTs = $null
            continue
        }

        if ($line -match "EV_KEY\s+KEY_([A-Z0-9_]+)\s+DOWN") {
            $step += 1
            $keyValue = $Matches[1]
            $outLines.Add("UIACTION|TS=$lineTsMs|SESSION=manual_touch|STEP=$step|ACTIVITY=unknown|TYPE=KEY|VALUE=$keyValue")
            continue
        }

        # Some devices report key state as numeric value 00000001 for key-down.
        if ($line -match "EV_KEY\s+KEY_([A-Z0-9_]+)\s+([0-9a-fA-F]{8})") {
            $keyValue = [Convert]::ToInt32($Matches[2], 16)
            if ($keyValue -eq 1) {
                $step += 1
                $keyName = $Matches[1]
                $outLines.Add("UIACTION|TS=$lineTsMs|SESSION=manual_touch|STEP=$step|ACTIVITY=unknown|TYPE=KEY|VALUE=$keyName")
            }
        }
    }

    $outLines | Out-File -FilePath $OutUiPath -Encoding utf8
    return $outLines.Count
}

try {
    $scriptDir = $PSScriptRoot
    $androidSdkRoot = Resolve-AndroidSdkRoot
    if (-not $androidSdkRoot) {
        Write-Err "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
        exit 1
    }

    $platformsPath = Join-Path $androidSdkRoot "platforms"
    $buildToolsPath = Join-Path $androidSdkRoot "build-tools\36.1.0"
    $aaptExe = Join-Path $buildToolsPath "aapt.exe"
    $zipalignExe = Join-Path $buildToolsPath "zipalign.exe"
    $apksignerExe = Join-Path $buildToolsPath "apksigner.bat"

    $jarPath = Join-Path $scriptDir "target\androlog-0.1-jar-with-dependencies.jar"
    $outputBase = Join-Path $scriptDir "fse-dataset\instrumented_apk"
    $outputDir = Join-Path $outputBase $OutputDirName

    if (-not (Test-Path $ApkPath)) {
        Write-Err "APK file not found: $ApkPath"
        exit 1
    }

    if (-not (Test-Path $TestApkPath)) {
        Write-Err "Test APK not found: $TestApkPath"
        exit 1
    }

    if (-not (Test-Path $platformsPath)) {
        Write-Err "Android SDK platforms folder not found: $platformsPath"
        exit 1
    }

    if (-not (Test-Path $aaptExe)) {
        Write-Err "aapt not found: $aaptExe"
        exit 1
    }

    if (Test-IsSplitRequiredApk -AaptExe $aaptExe -TargetApk $ApkPath) {
        Write-Err "The target APK is split-required and cannot be installed alone after instrumentation. Use a universal APK (non-split) or provide full split set packaging first."
        exit 1
    }

    if (-not (Test-Path $zipalignExe)) {
        Write-Err "zipalign not found: $zipalignExe"
        exit 1
    }

    if (-not (Test-Path $apksignerExe)) {
        Write-Err "apksigner not found: $apksignerExe"
        exit 1
    }

    if (-not (Test-Path $jarPath)) {
        Write-Info "Building AndroLog fat jar..."
        & mvn package -DskipTests -q
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jarPath)) {
            Write-Err "Failed to build: $jarPath"
            exit 1
        }
    }

    if (-not $PackageName) {
        Write-Info "Auto-detecting package name from APK..."
        $PackageName = Get-PackageNameFromApk -AaptExe $aaptExe -TargetApk $ApkPath
        if (-not $PackageName) {
            Write-Err "Failed to detect package name. Pass -PackageName explicitly."
            exit 1
        }
    }

    $adbDevice = Resolve-Device -RequestedDevice $DeviceId
    if (-not $adbDevice) {
        Write-Err "No online adb device found. Start emulator/device first."
        exit 1
    }

    Write-Info "=========================================="
    Write-Info "AndroLog UI Test Pipeline"
    Write-Info "=========================================="
    Write-Info "APK: $ApkPath"
    Write-Info "Package: $PackageName"
    Write-Info "Test APK: $TestApkPath"
    Write-Info "Runner: $TestRunner"
    Write-Info "Mode: $(if ($ManualUiTest) { 'Manual UI test' } else { 'Instrumentation test' })"
    Write-Info "Device: $adbDevice"
    Write-Info "Output: $outputDir"
    Write-Info "Branch tag: $BranchLogTag"
    Write-Info "UI tag: $UiLogTag"
    Write-Info "=========================================="

    if (Test-Path $outputDir) {
        Remove-Item -Path $outputDir -Recurse -Force
    }
    New-Item -Path $outputDir -ItemType Directory -Force | Out-Null

    Write-Info "Step 1/8: Instrumenting APK..."
    & java -jar "$jarPath" -p "$platformsPath" -a "$ApkPath" -o "$outputDir" -c -m -s -b -l "$BranchLogTag"
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Instrumentation failed"
        exit 1
    }

    $apkFileName = Split-Path $ApkPath -Leaf
    $apkName = [System.IO.Path]::GetFileNameWithoutExtension($apkFileName)
    $instrumentedApk = Join-Path $outputDir "$apkName-aligned.apk"
    if (-not (Test-Path $instrumentedApk)) {
        $instrumentedApk = Join-Path $outputDir $apkFileName
    }

    if (-not (Test-Path $instrumentedApk)) {
        Write-Err "Instrumented APK not found in output directory"
        exit 1
    }

    # AndroLog signing may fail on Windows if its internal SDK paths are Unix-specific.
    # Verify and self-sign here to keep the pipeline deterministic.
    & "$apksignerExe" verify "$instrumentedApk" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "Instrumented APK appears unsigned. Applying local zipalign/apksigner fallback..."

        $alignedApk = Join-Path $outputDir "${apkName}-aligned-windows.apk"
        $signedApk = Join-Path $outputDir "${apkName}-signed-windows.apk"
        $debugKeystore = Join-Path $env:USERPROFILE ".android\debug.keystore"

        if (-not (Test-Path $debugKeystore)) {
            Write-Err "Debug keystore not found: $debugKeystore"
            exit 1
        }

        & "$zipalignExe" -f -v 4 "$instrumentedApk" "$alignedApk"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $alignedApk)) {
            Write-Err "zipalign fallback failed"
            exit 1
        }

        & "$apksignerExe" sign --ks "$debugKeystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$signedApk" "$alignedApk"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $signedApk)) {
            Write-Err "apksigner fallback failed"
            exit 1
        }

        $instrumentedApk = $signedApk
        Write-Success "Fallback signing succeeded: $instrumentedApk"
    }

    Write-Info "Step 2/8: Reinstalling target app..."
    Invoke-Adb -Device $adbDevice -AdbArgs @("uninstall", $PackageName) | Out-Null

    # Some adb versions do not support --no-incremental. Try it first, then fallback.
    Invoke-Adb -Device $adbDevice -AdbArgs @("install", "--no-incremental", "-r", $instrumentedApk)
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "adb install with --no-incremental failed, retrying with plain -r..."
        Invoke-Adb -Device $adbDevice -AdbArgs @("install", "-r", $instrumentedApk)
    }

    if ($LASTEXITCODE -ne 0) {
        Write-Err "Failed to install instrumented APK"
        exit 1
    }

    Write-Info "Step 3/8: Installing UI test APK..."
    Invoke-Adb -Device $adbDevice -AdbArgs @("install", "-r", "-t", $TestApkPath)
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Failed to install test APK"
        exit 1
    }

    Write-Info "Step 4/8: Clearing logcat buffers..."
    Invoke-Adb -Device $adbDevice -AdbArgs @("logcat", "-c") | Out-Null
    Invoke-Adb -Device $adbDevice -AdbArgs @("logcat", "-b", "main", "-c") | Out-Null
    Invoke-Adb -Device $adbDevice -AdbArgs @("logcat", "-b", "system", "-c") | Out-Null
    Invoke-Adb -Device $adbDevice -AdbArgs @("logcat", "-b", "crash", "-c") | Out-Null

    $testResultPath = Join-Path $outputDir "instrumentation_result.txt"
    $syntheticUiPath = ""
    if ($ManualUiTest) {
        Write-Info "Step 5/8: Manual UI testing mode..."

        $rawTouchPath = Join-Path $outputDir "manual_touch_events_raw.txt"
        $syntheticUiPath = Join-Path $outputDir "manual_touch_ui_actions.txt"
        $touchCaptureProc = $null
        $deviceEpochOffsetMs = Get-DeviceEpochOffsetMs -Device $adbDevice

        if ($deviceEpochOffsetMs -gt 0) {
            Write-Info "Starting background touch capture (getevent)..."
            $touchCaptureProc = Start-Process -FilePath "adb" -ArgumentList @("-s", $adbDevice, "shell", "getevent", "-lt") -RedirectStandardOutput $rawTouchPath -NoNewWindow -PassThru
        } else {
            Write-Warn "Could not compute device uptime offset; touch-to-UI synthesis disabled for this run."
        }

        $resolvedLaunchActivity = $LaunchActivity
        if (-not $resolvedLaunchActivity) {
            $resolvedLaunchActivity = Get-LaunchableActivityFromApk -AaptExe $aaptExe -TargetApk $ApkPath
        }

        if ($resolvedLaunchActivity) {
            Write-Info "Launching app activity: $resolvedLaunchActivity"
            Invoke-Adb -Device $adbDevice -AdbArgs @("shell", "am", "start", "-n", "$PackageName/$resolvedLaunchActivity") | Out-Null
        } else {
            Write-Warn "Could not detect launch activity automatically. Launch the app manually on device."
        }

        Write-Host "" -ForegroundColor Cyan
        Write-Host "==========================================" -ForegroundColor Cyan
        Write-Host "Manual UI test in progress..." -ForegroundColor Cyan
        Write-Host "Perform all target UI actions on the device now." -ForegroundColor Cyan
        Write-Host "When finished, return here and press Enter to continue calculation." -ForegroundColor Cyan
        Write-Host "==========================================" -ForegroundColor Cyan
        [void](Read-Host "Press Enter to continue")

        if ($touchCaptureProc -and -not $touchCaptureProc.HasExited) {
            Stop-Process -Id $touchCaptureProc.Id -Force
            Start-Sleep -Milliseconds 300
            $syntheticCount = Convert-TouchEventsToUiActions -RawTouchPath $rawTouchPath -OutUiPath $syntheticUiPath -EpochOffsetMs $deviceEpochOffsetMs
            Write-Success "Synthesized $syntheticCount UIACTION lines from manual touch events"
        }

        "MANUAL_UI_TEST_COMPLETED" | Out-File -FilePath $testResultPath -Encoding utf8
    } else {
        Write-Info "Step 5/8: Running instrumentation tests..."
        $testOutput = Invoke-Adb -Device $adbDevice -AdbArgs @("shell", "am", "instrument", "-w", $TestRunner)
        $testOutput | Out-File -FilePath $testResultPath -Encoding utf8
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Instrumentation command returned non-zero exit code. Check: $testResultPath"
        }
    }

    Write-Info "Step 6/8: Collecting branch/UI logs..."
    $branchLogPath = Join-Path $outputDir "branch_logs.txt"
    $uiLogPath = Join-Path $outputDir "ui_action_logs.txt"
    $uiCleanPath = Join-Path $outputDir "ui_action_logs_cleaned.txt"

    Invoke-Adb -Device $adbDevice -AdbArgs @("logcat", "-d", "${BranchLogTag}:D", "*:S") | Out-File -FilePath $branchLogPath -Encoding utf8
    Invoke-Adb -Device $adbDevice -AdbArgs @("logcat", "-d", "${UiLogTag}:D", "*:S") | Out-File -FilePath $uiLogPath -Encoding utf8

    Get-Content $uiLogPath | Select-String "UIACTION\|" | ForEach-Object { $_.Line -replace '.*?: ', '' } | Out-File -FilePath $uiCleanPath -Encoding utf8

    if ($ManualUiTest -and $syntheticUiPath -and (Test-Path $syntheticUiPath)) {
        Get-Content $syntheticUiPath | Add-Content -Path $uiCleanPath
    }

    $branchCount = (Get-Content $branchLogPath | Select-String "BRANCH=" | Measure-Object).Count
    $uiCount = (Get-Content $uiCleanPath | Select-String "UIACTION\|" | Measure-Object).Count

    Write-Success "Collected $branchCount branch lines"
    Write-Success "Collected $uiCount UI action lines"

    if ($ManualUiTest -and $uiCount -eq 0) {
        Write-Warn "Manual mode captured no UIACTION logs. Manual taps alone do not emit UI logs unless the app/test code actively writes UiActionLogger lines with tag '$UiLogTag'."
    }

    Write-Info "Step 7/8: Generating enhanced UI timeline..."
    if (-not (Test-Path "target\classes\com\jordansamhi\androlog\LogMergerEnhanced.class")) {
        & mvn compile -q
        if ($LASTEXITCODE -ne 0) {
            Write-Err "Failed to compile LogMergerEnhanced"
            exit 1
        }
    }

    $timelinePath = Join-Path $outputDir "ui_centric_timeline.txt"
    & java -Xmx2G -cp "target\classes" com.jordansamhi.androlog.LogMergerEnhanced --branch-log "$branchLogPath" --ui-log "$uiCleanPath" --output "$timelinePath" --format BURST_SUMMARY
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "Timeline generation failed"
    }

    if (-not $SkipCoverage) {
        Write-Info "Step 8/8: Generating coverage report..."
        $coveragePath = Join-Path $outputDir "coverage_report.json"
        $cfgPath = Join-Path $outputDir "static_apk.cfg"

        & java -jar "$jarPath" -p "$platformsPath" -a "$ApkPath" -l "$BranchLogTag" -c -m -s -b -pa "$branchLogPath" -j "$coveragePath" -cfg "$cfgPath"
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Coverage report generation failed"
        }
    } else {
        Write-Info "Step 8/8: Coverage generation skipped"
    }

    Write-Success "=========================================="
    Write-Success "UI test pipeline completed"
    Write-Success "=========================================="
    Write-Info "Output directory: $outputDir"
    Write-Info "  - instrumentation_result.txt"
    Write-Info "  - branch_logs.txt"
    Write-Info "  - ui_action_logs.txt"
    Write-Info "  - ui_action_logs_cleaned.txt"
    Write-Info "  - ui_centric_timeline.txt"
    if (-not $SkipCoverage) {
        Write-Info "  - coverage_report.json"
        Write-Info "  - static_apk.cfg"
    }
}
catch {
    Write-Err $_.Exception.Message
    exit 1
}
