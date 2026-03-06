# AndroLog Coverage Pipeline - Quick Reference

## Quick Start

### Automated Testing (Recommended)
```bash
./run_automated_coverage.sh <apk_path> <package_name> <output_dir> <duration_seconds> [log_tag]
```

**Example:**
```bash
./run_automated_coverage.sh \
    fse-dataset/Mood_Tracker.apk \
    moodtracker.selfcare.habittracker.mentalhealth \
    mood_coverage \
    30 \
    MOOD_TAG
```

### Interactive Testing
```bash
./run_full_coverage_pipeline.sh <apk_path> <package_name> <output_dir> [log_tag]
```

## Coverage Report Output

After running either script, you'll find in `fse-dataset/instrumented_apk/<output_dir>/`:

- **`<output>_logs.txt`** - Filtered instrumentation logs
- **`coverage_report.json`** - JSON format coverage data
- **`coverage_summary.txt`** - Human-readable text summary
- **`processing.cfg`** - Control flow graph with branch metadata (NEW)
- **`<apk_name>.apk`** - Instrumented APK
- **`<output>_full_logcat.txt`** - Complete device logs

### Coverage JSON Structure
```json
{
  "coverageSummary": {
    "methods": {"covered": 1253, "total": 159905, "percent": "0.8%"},
    "classes": {"covered": 230, "total": 24632, "percent": "0.9%"},
    "statements": {"covered": 12759, "total": 1659432, "percent": "0.8%"},
    "branches": {"covered": 2995, "total": 174444, "percent": "1.7%"}
  }
}
```

### Branch Coverage (NEW - Strict Dual-Edge)
Each `if` statement is tracked as **two edges** (TRUE and FALSE), and each `switch` case plus DEFAULT:
```
BRANCH=<method>|IF|<location>|EDGE=TRUE|COND=<condition>
BRANCH=<method>|IF|<location>|EDGE=FALSE|COND=<condition>
BRANCH=<method>|SWITCH|<location>|CASE=<value>
BRANCH=<method>|SWITCH|<location>|CASE=DEFAULT
```

### Processing CFG File (NEW)
The `processing.cfg` file contains the complete control flow graph:
```
METHOD <signature>
NODE <id> <location> <jimple_code>
EDGE <source> -> <target>
BRANCH_IF <branch_descriptor>
BRANCH_SWITCH <branch_descriptor>
ENDMETHOD
```

Use this to perform **gap analysis** - identify uncovered branches by cross-referencing with runtime logs.

## Manual Pipeline Steps

### 1. Instrument APK
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a <input.apk> \
    -o <output_dir> \
    -c -m -s -b \
    -l <LOG_TAG>
```
 \
    -cfg processing.cfg
```

**CRITICAL:** 
- Use **original (uninstrumented) APK** for coverage generation (step 4)
- Must include **same flags** (`-c -m -s -b`) in both instrumentation AND coverage generation
- Must use **same log tag** (`-l`) value in both steps
- **NEW:** `-cfg` option generates CFG file for gap analysis (optional but recommended)

### 3. Collect Logs
```bash
adb -s emulator-5554 logcat -d > full_logcat.txt
grep <LOG_TAG> full_logcat.txt > filtered_logs.txt
```

### 4. Generate Coverage Report
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a <original.apk> \
    -l <LOG_TAG> \
    -c -m -s -b \
    -pa filtered_logs.txt \
    -j coverage_output.json
```

**CRITICAL:** 
- Use **original (uninstrumented) APK** for coverage generation (step 4)
- Must include **same flags** (`-c -m -s -b`) in both instrumentation AND coverage generation
- Must use **same log tag** (`-l`) value in both steps

## Troubleshooting

### Empty Coverage Report
**Problem:** `coverage_output.json` shows all zeros  
**Solution:** Ensure `-c -m -s -b` flags are present in coverage generation command

### INSTALL_FAILED_MISSING_SPLIT
**Problem:** Installation fails with split APK error  
**Solution:** Already fixed in ApkPreparator.java - split attributes are automatically removed

### VerifyError on Installation
**Problem:** App crashes immediately after launch  
**Solution:** Use `-nr` flag to skip instrumentation for incompatible apps (e.g., Kotlin+R8 optimized)

### No Logs Found
**Problem:** Log file is empty or has zero lines  
**Solution:** Verify log tag matches between instrumentation (`-l`) and log collection (`grep`)

## Project Structure
```
AndroLog/
├── run_automated_coverage.sh      # Fully automated pipeline
├── run_full_coverage_pipeline.sh  # Interactive testing pipeline
├── example_usage.sh               # Usage examples
├── target/
│   └── androlog-0.1-jar-with-dependencies.jar
└── fse-dataset/
    ├── Mood_Tracker.apk          # Original APK
    └── instrumented_apk/
        └── <output_dir>/          # Generated outputs
            ├── <apk_name>.apk
            ├── coverage_report.json
            ├── coverage_summary.txt
            └── *_logs.txt
```

## Example Real-World Usage

```bash
# 1. Quick 10-second automated test
./run_automated_coverage.sh fse-dataset/app.apk com.example.app test1 10 TESTTAG

# 2. Longer 60-second automated test
./run_automated_coverage.sh fse-dataset/app.apk com.example.app test2 60 TESTTAG

# 3. Manual testing session
./run_full_coverage_pipeline.sh fse-dataset/app.apk com.example.app manual_test TESTTAG
# (Script pauses - manually interact with app - then press ENTER)

# 4. View results
cat fse-dataset/instrumented_apk/test1/coverage_summary.txt
python3 -m json.tool fse-dataset/instrumented_apk/test1/coverage_report.json
```

## Diagnostic Tools

### Analyze Log Statistics
```bash
cd fse-dataset/instrumented_apk/<output_dir>/
python3 analyze_logs.py <log_file>.txt
```

### Check Log Quality
```bash
# Count instrumentation entries
grep -c "YOUR_TAG" logfile.txt

# Preview log formats
grep "YOUR_TAG" logfile.txt | head -20

# Check specific types
grep "YOUR_TAG.*METHOD=" logfile.txt | wc -l
grep "YOUR_TAG.*BRANCH=" logfile.txt | wc -l
```

---

**Last Updated:** March 6, 2026  
**Version:** AndroLog v0.1 with split attribute fix and config compatibility improvements
