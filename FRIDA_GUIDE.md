# AndroLog Frida Integration Guide

## Overview

AndroLog now supports **Frida-based runtime instrumentation** for modern Kotlin+R8 apps that are incompatible with Soot bytecode modification.

## Features

- ✅ **No APK modification** - Original APK runs without risk of VerifyError
- ✅ **Supports Kotlin+R8** - Works with modern Android apps
- ✅ **Runtime hooks** - Instruments code during execution
- ✅ **Same output format** - Coverage reports identical to Soot mode
- ✅ **Strict dual-edge branch coverage** - Full IF/SWITCH dual-edge analysis (NEW)
- ✅ **CFG export** - Control flow graph with branch metadata (NEW)
- ✅ **Readable branch descriptors** - Location + condition information (NEW)

## Requirements

### On Your Machine
```bash
pip install frida
pip install frida-tools
```

### On Android Device/Emulator
```bash
# Download appropriate frida-server
# https://github.com/frida/frida/releases

# For Android 12+ (API 31+):
chmod +x frida-server-16.1.11-android-[arch]
adb push frida-server-16.1.11-android-arm64 /data/local/tmp/frida-server
adb shell chmod +x /data/local/tmp/frida-server

# Start frida-server
adb shell /data/local/tmp/frida-server &
```

## Usage

### Option 1: Auto-Detect Mode (Recommended)

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /path/to/sdk/platforms \
  -a /path/to/app.apk \
  -c -m -s -b \
  -ad \
  -l MY_TAG \
  -o output_dir
```

The `-ad` flag automatically detects if Frida is needed:
- **Soot mode** → Java+ProGuard apps
- **Frida mode** → Kotlin+R8 apps

### Option 2: Explicit Frida Mode

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /path/to/sdk/platforms \
  -a /path/to/app.apk \
  -c -m -s -b \
  -frida \
  -l MY_TAG \
  -o output_dir
```

### Option 3: Using Provided Scripts

```bash
# For apps requiring Frida instrumentation
./run_frida_coverage.sh \
  "/path/to/app.apk" \
  "com.example.app" \
  "my_test" \
  "MY_TAG"
```

## Full Workflow Example

### Step 1: Prepare Device

```bash
# Ensure frida-server is running
adb shell ps | grep frida-server

# If not running:
adb shell /data/local/tmp/frida-server &
sleep 2
```

### Step 2: Instrument APK

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /Users/liqi/Library/Android/sdk/platforms \
  -a /path/to/app.apk \
  -c -m -s -b \
  -ad \
  -l APP_COVERAGE \
  -o frida_output
```

Output:
```
[*] Initializing Frida instrumentation pipeline...
[*] Checking Frida server...
[✓] Frida server is already running
[*] Installing Frida agent...
[✓] Frida agent generated: frida_output/frida-agent.js
[*] Instrumenting APK (Frida mode: copying original)...
[✓] Original APK copied to: frida_output/app.apk
[*] Frida instrumentation pipeline completed
```

### Step 3: Install APK

```bash
adb uninstall com.example.app
adb install -r frida_output/app.apk
```

### Step 4: Clear Logs & Run Tests

```bash
adb logcat -c

# Method 1: Automated monkey test (10,000 events)
adb shell monkey \
  -p com.example.app \
  --throttle 100 \
  --ignore-crashes \
  --ignore-timeouts \
  -v 10000

# OR Method 2: Manual UI interaction (better coverage)
adb shell am start -W -n com.example.app/.MainActivity
# Interact with the app for 1-2 minutes
```

### Step 5: Collect Logs

```bash
adb logcat -d | grep "APP_COVERAGE" > coverage_logs.txt
echo "✓ Collected $(wc -l < coverage_logs.txt) log entries"
```

### Step 6: Generate Coverage Report

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /Users/liqi/Library/Android/sdk/platforms \
  -a /path/to/original/app.apk \
  -c -m -s -b \
  -pa coverage_logs.txt \
  -j coverage_report.json
```

### Step 7: View Results

```bash
cat coverage_report.json | jq '.coverageSummary'
```

Output:
```json
{
  "methods": {
    "covered": 1234,
    "total": 50000,
    "percent": "2.5%"
  },
  "classes": {
    "covered": 567,
    "total": 10000,
    "percent": "5.7%"
  },
  "statements": {
    "covered": 12345,
    "total": 500000,
    "percent": "2.5%"
  },
  "branches": {
    "covered": 5678,
    "total": 100000,
    "percent": "5.7%"
  }
}
```

## Troubleshooting

### Frida Server Not Found
```
Error: Failed to start Frida server

Solution:
adb shell /data/local/tmp/frida-server &
sleep 2
```

### APK Won't Install
```
adb: failed to install: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]

Solution:
adb uninstall com.example.app
adb install -r app.apk
```

### No Logs Collected
```
Collected 0 log entries

Solution:
1. Verify log tag matches (-l flag)
2. Check app actually ran: adb shell pm list packages | grep app_name
3. Ensure frida-server is running: adb shell ps | grep frida
```

### APK Shows VerifyError

This shouldn't happen with Frida mode, but if it does:
```bash
# Try safe mode (copy without instrumentation)
java -jar ... -nr
```

## Comparison: Soot vs Frida

| Feature | Soot | Frida |
|---------|------|-------|
| **APK Modification** | ✅ Modifies | ❌ No modification |
| **Java+ProGuard** | ✅ Works | ✅ Works |
| **Kotlin+R8** | ❌ VerifyError | ✅ Works |
| **Branch Coverage** | ✅ Yes | ✅ Yes |
| **Method Coverage** | ✅ Yes | ✅ Yes |
| **Class Coverage** | ✅ Yes | ✅ Yes |
| **Performance** | 🟠 Medium | 🟠 Medium |
| **Setup Complexity** | 🟢 Simple | 🟡 Moderate |

## Performance Notes

- **Monkey testing**: 10,000 events = ~5-15 minutes
- **Manual UI testing**: 1-2 minutes interaction = better coverage (3-5x)
- **Log collection**: Scale with event count (226K events = ~2-5 minutes)

## Advanced Features (Coming Soon)

- [ ] Custom hook expressions
- [ ] Selective method instrumentation
- [ ] Performance profiling
- [ ] Method call tracking

## Support

For issues with:
- **Frida installation**: https://frida.re/docs/installation/
- **AndroLog features**: Refer to README.md
- **Android emulator**: https://developer.android.com/studio/run/emulator
