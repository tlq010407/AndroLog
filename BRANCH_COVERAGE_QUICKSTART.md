# Branch Coverage Quick Start Guide

## Feature Introduction
AndroLog now supports **Branch Coverage** statistics to track the execution coverage of `if/switch` branches in application code.

## Quick Start

### 0. Find Android SDK Path (macOS)
```bash
# Find Android SDK
find ~/Library/Android/sdk/platforms -name "android.jar" | head -5

# Example output:
# /Users/liqi/Library/Android/sdk/platforms/android-35/android.jar
# /Users/liqi/Library/Android/sdk/platforms/android-34/android.jar

# Then use platforms directory (not jar file):
# -p /Users/liqi/Library/Android/sdk/platforms
```

### 1. Code Instrumentation Phase (Add -b option)
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /path/to/Android/sdk/platforms \
  -a myapp.apk \
  -o instrumented_apk \
  -b                    # ← Enable branch coverage statistics
  -l ANDROLOG           # Log identifier
```

### 2. Parse Log to Generate Report
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /path/to/Android/sdk/platforms \
  -a myapp.apk \
  -pa logfile.txt \     # Parse log file
  -b                    # ← Enable branch coverage calculation
  -l ANDROLOG
```

### 3. Generate JSON Format Report
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /path/to/Android/sdk/platforms \
  -a myapp.apk \
  -pa logfile.txt \
  -b \
  -j output.json        # JSON format output
```

## Report Examples

**Console output:**
```
=== Coverage Summary ===
------------------------
branches         : 65.3% (131/200)
statements       : 82.1% (328/400)
methods          : 88.5% (177/200)
classes          : 92.0% (23/25)
------------------------
```

**JSON format (using -j option):**
```json
{
  "coverageSummary": {
    "branches": {
      "covered": 131,
      "total": 200,
      "percent": "65.3%"
    },
    "statements": {
      "covered": 328,
      "total": 400,
      "percent": "82.1%"
    },
    "methods": {
      "covered": 177,
      "total": 200,
      "percent": "88.5%"
    },
    "classes": {
      "covered": 23,
      "total": 25,
      "percent": "92.0%"
    }
  }
}
```

## Command Line Options Explanation

| Option | Long Name | Description | Example |
|--------|-----------|-------------|----------|
| -b | --branches | Enable branch coverage statistics | -b |
| -p | --platforms | Platforms directory (required) | -p /path/to/Android/sdk/platforms |
| -a | --apk | APK file (required) | -a app.apk |
| -o | --output | Output directory | -o instrumented_apk |
| -l | --log-identifier | Log tag | -l ANDROLOG |
| -pa | --parse | Parse log file | -pa test.log |
| -j | --json | JSON output file | -j report.json |
| -n | --non-libraries | Exclude library code | -n |
| -pkg | --package | Only count specific packages | -pkg com.example.app |

## Supported Coverage Type Combinations

```bash
# Count only branch coverage
java -jar androlog-0.1-jar-with-dependencies.jar -p ... -a ... -b

# Count branch and method coverage
java -jar androlog-0.1-jar-with-dependencies.jar -p ... -a ... -b -m

# Count branch, statement, and method coverage
java -jar androlog-0.1-jar-with-dependencies.jar -p ... -a ... -b -s -m

# Count all coverage types
java -jar androlog-0.1-jar-with-dependencies.jar -p ... -a ... -b -s -m -c -cp
```

## Implementation Details

### Branch Statistics Method
- **IfStmt**: Each if statement counts as 1 branch
- **SwitchStmt**: Each case branch (including default) counts as 1 branch

### Log Format
```
ANDROLOG: BRANCH=<method_signature>|<branch_type>|<sequence_id>
```

Examples:
```
ANDROLOG: BRANCH=com.example.MainActivity.onCreate()|IF|1
ANDROLOG: BRANCH=com.example.Utils.process()|SWITCH|5
```

## Precautions

1. **APK Size Increase**: Adding branch logs will increase APK size (typically 2-5%)
2. **Performance Impact**: Runtime will have slight performance overhead
3. **Log File Size**: Branch logs may significantly increase log file size
4. **Exclude Library Code**: Use `-n` option to exclude branch statistics in third-party libraries

## Troubleshooting

### Problem: Branch Coverage is 0%
- Ensure `-b` option was used during instrumentation
- Ensure `-b` option was also used when parsing logs
- Check if log file contains ANDROLOG marker

### Problem: APK Installation or Execution Fails
- Try re-aligning and signing APK
- Check target Android version compatibility
- Check logcat for error messages

## Complete Workflow Example

### Step-by-Step Instructions

```bash
# 0. Set Android SDK path
SDK_PLATFORMS="/Users/liqi/Library/Android/sdk/platforms"

# 1. Instrument APK (add branch logging code)
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p $SDK_PLATFORMS \
  -a original_app.apk \
  -o instrumented_dir \
  -b -s -m \                          # Count branches, statements, methods
  -l ANDROLOG

# 2. Install and run tests
adb install -r instrumented_dir/original_app.apk
# Use the application, execute test scenarios...
adb logcat > test_log.txt

# 3. Generate coverage report
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p $SDK_PLATFORMS \
  -a original_app.apk \
  -pa test_log.txt \
  -b -s -m \
  -j coverage_report.json \
  -l ANDROLOG
```

### Actual Execution Example (Verification)

```bash
$ java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p /Users/liqi/Library/Android/sdk/platforms \
  -a Test/OmniNotes-playRelease-6.3.1.apk \
  -pa Test/output/test_log.txt \
  -b \
  -l ANDROLOG

AndroLog v0.1 started on Tue Mar 03 10:22:05 SGT 2026
[*] Setting up environment...
[*] No thread count specified. Using Soot's default thread configuration.
[✓] Done.
[*] Generating Code Coverage Report...

=== Coverage Summary ===
------------------------
branches            :   0.0% (0/41711)
------------------------
[✓] Done.
```

**Output Explanation**:
- ✅ Detected **41,711 branches**
- Shows 0.0% because test log contains no branch execution records
- In actual use, ANDROLOG branch logs in logs will be counted

## Changelog

- **v0.1 + Branch Coverage**: First implementation of branch coverage feature
  - Support for if statement statistics
  - Support for switch statement statistics
  - JSON and console output formats
  - Compatible with existing coverage types
