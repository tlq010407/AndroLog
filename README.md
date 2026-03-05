<p align="center">
<img width="1200px" src="https://github.com/JordanSamhi/AndroLog/blob/main/data/androlog_logo.png">
</p> 

# AndroLog

Welcome to **AndroLog**, your comprehensive solution for inserting probes into Android apps to compute code coverage at runtime. AndroLog provides automated pipelines, multiple coverage granularities, and robust handling of various Android app configurations.

## :sparkles: Features

**AndroLog** offers multiple levels of granularity:
- **Classes** - Track execution at the class level
- **Methods** - Monitor which methods are invoked
- **Statements** - Fine-grained statement-level tracking
- **Branches (if/switch)** ⭐ NEW - Conditional branch coverage
- **Activities** - Activity lifecycle tracking
- **Services** - Service execution monitoring
- **Broadcast Receivers** - Receiver invocation tracking
- **Content Providers** - Content provider access logging
- **Method Calls** - Call chain analysis (e.g., a()-->b())

### :rocket: Key Enhancements

- ✅ **Automated Coverage Pipelines** - One-command testing and reporting
- ✅ **Branch Coverage** - Complete if/switch statement coverage
- ✅ **Safe Mode (`-nr`)** - Handle incompatible apps (Kotlin + R8)
- ✅ **Split APK Support** - Automatic split attribute cleanup
- ✅ **Multi-threading** - Configurable Soot thread count (`-t`)
- ✅ **JSON & Text Reports** - Structured and human-readable output

## :zap: Quick Start

### Option 1: Automated Testing (Recommended)
Fully automated pipeline with monkey testing:
```bash
./run_automated_coverage.sh your_app.apk com.your.package output_folder 30 MY_TAG
```

### Option 2: Interactive Testing
Manual interaction workflow:
```bash
./run_full_coverage_pipeline.sh your_app.apk com.your.package output_folder MY_TAG
```

### Option 3: Manual Workflow
See the [Manual Pipeline](#manual-pipeline) section below.

---

## :books: Documentation

- **[COVERAGE_QUICKSTART.md](COVERAGE_QUICKSTART.md)** - Quick reference guide with examples
- **[ENHANCEMENT_SUMMARY.md](ENHANCEMENT_SUMMARY.md)** - Detailed changelog and improvements
- **[example_usage.sh](example_usage.sh)** - Executable usage examples

---

## :rocket: Getting Started

### :arrow_down: Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/JordanSamhi/AndroLog.git
   cd AndroLog
   ```

2. **Configure Android SDK paths:**
   
   Edit `src/main/resources/config.properties` and set paths to your Android SDK tools:
   ```properties
   zipalign=/path/to/sdk/build-tools/XX.X.X/zipalign
   apksigner=/path/to/sdk/build-tools/XX.X.X/apksigner
   ```

3. **Build the tool:**
   ```bash
   mvn clean install
   ```

The JAR will be generated at: `target/androlog-0.1-jar-with-dependencies.jar`

---

## :computer: Usage

### Command-Line Options

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar [options]
```

| Option | Description |
|--------|-------------|
| `-a <path>` | Path to the APK to process (required) |
| `-p <path>` | Path to Android platforms folder (required) |
| `-l <tag>` | Log identifier/tag to use |
| `-o <path>` | Output directory for instrumented APK |
| `-c` | Enable class-level logging |
| `-m` | Enable method-level logging |
| `-s` | Enable statement-level logging |
| `-b` | Enable branch coverage (if/switch) ⭐ NEW |
| `-cp` | Enable component logging (Activity, Service, etc.) |
| `-mc` | Enable method call chain logging |
| `-n` | Exclude library code from instrumentation |
| `-pkg <name>` | Instrument only specified package |
| `-t <num>` | Number of threads for Soot ⭐ NEW |
| `-nr` | No-rewrite mode (safe mode) ⭐ NEW |
| `-pa <file>` | Parse runtime logs for coverage |
| `-pam <file>` | Parse logs per minute |
| `-j <file>` | Output coverage in JSON format |

---

## :hammer_and_wrench: Automated Pipelines

### 1. Automated Testing Pipeline

**File:** `run_automated_coverage.sh`

**Features:**
- Full instrumentation with all coverage types
- Automated monkey testing (configurable duration)
- Log collection and filtering
- JSON and text report generation
- Complete hands-off workflow

**Usage:**
```bash
./run_automated_coverage.sh <apk> <package> <output_dir> <duration_seconds> [log_tag]
```

**Example:**
```bash
./run_automated_coverage.sh \
    myapp.apk \
    com.example.myapp \
    test_run1 \
    30 \
    MYAPP_TAG
```

**Output:**
- `<output_dir>/<apk_name>.apk` - Instrumented APK
- `<output_dir>_logs.txt` - Filtered instrumentation logs
- `<output_dir>_full_logcat.txt` - Complete device logs
- `coverage_report.json` - Structured coverage data
- `coverage_summary.txt` - Human-readable summary

### 2. Interactive Testing Pipeline

**File:** `run_full_coverage_pipeline.sh`

**Features:**
- Same as automated, but pauses for manual testing
- User controls when testing is complete
- Ideal for exploratory or manual testing scenarios

**Usage:**
```bash
./run_full_coverage_pipeline.sh <apk> <package> <output_dir> [log_tag]
```

**Workflow:**
1. Instruments APK
2. Installs to device/emulator
3. Launches app
4. **Pauses for manual interaction**
5. User presses ENTER when done
6. Collects logs and generates reports

---

## :wrench: Manual Pipeline

### Step 1: Instrument APK
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/Android/sdk/platforms \
    -a input.apk \
    -o output_folder \
    -c -m -s -b \
    -l MY_LOG_TAG
```

### Step 2: Install and Test
```bash
# Install instrumented APK
adb install -r output_folder/instrumented.apk

# Launch app
adb shell monkey -p com.example.app -c android.intent.category.LAUNCHER 1

# Test the app (manually or with automation tools)
# ...

# Collect logs
adb logcat -d > full_logs.txt
grep MY_LOG_TAG full_logs.txt > filtered_logs.txt
```

### Step 3: Generate Coverage Report
```bash
# CRITICAL: Use ORIGINAL APK, not instrumented one
# CRITICAL: Include SAME flags (-c -m -s -b) used during instrumentation
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/Android/sdk/platforms \
    -a input.apk \
    -l MY_LOG_TAG \
    -c -m -s -b \
    -pa filtered_logs.txt \
    -j coverage_report.json
```

---

## :chart_with_upwards_trend: Coverage Report Format

### JSON Output Structure
```json
{
  "coverageSummary": {
    "methods": {
      "covered": 1253,
      "total": 159905,
      "percent": "0.8%"
    },
    "classes": {
      "covered": 230,
      "total": 24632,
      "percent": "0.9%"
    },
    "statements": {
      "covered": 12759,
      "total": 1659432,
      "percent": "0.8%"
    },
    "branches": {
      "covered": 2995,
      "total": 174444,
      "percent": "1.7%"
    }
  },
  "executedMethods": [...],
  "executedClasses": [...],
  "executedStatements": [...],
  "executedBranches": [...]
}
```

### Text Report Example
```
==================== COVERAGE SUMMARY ====================
Methods:    1,253 / 159,905  (0.8%)
Classes:    230 / 24,632     (0.9%)
Statements: 12,759 / 1,659,432 (0.8%)
Branches:   2,995 / 174,444  (1.7%)
==========================================================
```

---

## :bulb: Usage Examples

### Full Coverage - All Types
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/platforms \
    -a app.apk \
    -o output \
    -c -m -s -b -cp \
    -l FULL_COVERAGE
```

### Method and Branch Coverage Only
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/platforms \
    -a app.apk \
    -o output \
    -m -b \
    -l METHOD_BRANCH
```

### Exclude Libraries
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/platforms \
    -a app.apk \
    -o output \
    -c -m -s -b \
    -n \
    -l NO_LIBS
```

### Package-Specific Instrumentation
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/platforms \
    -a app.apk \
    -o output \
    -c -m -s -b \
    -pkg com.mycompany.myapp \
    -l PKG_ONLY
```

### Safe Mode for Incompatible Apps
```bash
# For apps that fail with VerifyError (e.g., Kotlin + R8)
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/platforms \
    -a problematic.apk \
    -o output \
    -nr \
    -l SAFE_MODE
```

### Multi-threaded Processing
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /path/to/platforms \
    -a app.apk \
    -o output \
    -c -m -s -b \
    -t 4 \
    -l FAST_BUILD
```

---

## :warning: Troubleshooting

### Empty Coverage Report (All Zeros)
**Problem:** Coverage report shows 0% for all metrics  
**Solution:** Ensure `-c -m -s -b` flags are included in BOTH instrumentation AND coverage generation commands

### INSTALL_FAILED_MISSING_SPLIT
**Problem:** APK installation fails with split APK error  
**Solution:** ✅ Already fixed - split attributes are automatically removed

### VerifyError on App Launch
**Problem:** App crashes immediately after installation  
**Cause:** Kotlin + R8 optimization creates bytecode incompatible with Soot  
**Solution:** Use `-nr` (no-rewrite) flag to skip instrumentation

### No Logs Collected
**Problem:** Log file is empty after testing  
**Solution:** Verify log tag matches between:
- Instrumentation command (`-l TAG`)
- Log filtering (`grep TAG logcat.txt`)

### Instrument Build Fails
**Problem:** Maven build or instrumentation fails  
**Solution:**
1. Check `config.properties` paths are correct
2. Verify Android SDK tools are installed
3. Ensure Java 8+ is being used

---

## :test_tube: Tested Apps

### ✅ Compatible Apps
| App | Type | Coverage Types | Notes |
|-----|------|----------------|-------|
| Mood Tracker | Java + ProGuard | All (`-c -m -s -b`) | Excellent results |
| OmniNotes | Java + ProGuard | All | Fully working |
| Sunflower | Kotlin | All | Works with standard flags |

### ❌ Incompatible Apps
| App | Type | Issue | Workaround |
|-----|------|-------|------------|
| NewPipe | Kotlin + R8 | VerifyError | Use `-nr` mode |

---

## :file_folder: Project Structure

```
AndroLog/
├── src/main/java/com/jordansamhi/androlog/
│   ├── Main.java                    # Entry point, CLI handling
│   ├── BranchLogger.java            # Branch coverage instrumentation
│   ├── ApkPreparator.java           # APK signing, split removal
│   ├── SummaryBuilder.java          # Coverage report generation
│   └── LogParser.java               # Log parsing and analysis
├── src/main/resources/
│   └── config.properties            # Android SDK tool paths
├── target/
│   └── androlog-0.1-jar-with-dependencies.jar
├── run_automated_coverage.sh        # Automated testing pipeline
├── run_full_coverage_pipeline.sh    # Interactive testing pipeline
├── example_usage.sh                 # Usage examples
├── COVERAGE_QUICKSTART.md           # Quick reference
├── ENHANCEMENT_SUMMARY.md           # Detailed changelog
├── pom.xml                          # Maven configuration
└── artifacts/
    ├── acvtool/                     # ACVTool (alternative coverage tool)
    └── cosmo/                       # COSMO (source-based coverage)
```

---

## :link: Related Tools

### ACVTool
**Location:** `artifacts/acvtool/`  
**Description:** Alternative Android code coverage tool based on Smali bytecode representation  
**Documentation:** [artifacts/acvtool/readme.md](artifacts/acvtool/readme.md)

### COSMO
**Location:** `artifacts/cosmo/COSMO/`  
**Description:** Gradle-based coverage tool supporting both source code and APK instrumentation  
**Documentation:** [artifacts/cosmo/COSMO/README.md](artifacts/cosmo/COSMO/README.md)

---

## :hammer: Built With

* [Maven](https://maven.apache.org/) - Dependency management
* [Soot](https://soot-oss.github.io/soot/) - Java bytecode analysis and transformation
* [Android SDK](https://developer.android.com/studio) - APK signing and alignment tools

---

## :page_facing_up: License

See [LICENSE](LICENSE) file for details.

---

## :calendar: Version History

**Latest:** v0.1 (March 2026)
- ✅ Branch coverage support (`-b` flag)
- ✅ Safe mode for incompatible apps (`-nr` flag)
- ✅ Split APK attribute cleanup
- ✅ Multi-threading support (`-t` flag)
- ✅ Automated testing pipelines
- ✅ JSON and text report generation
- ✅ Config properties compatibility fix

---

## :busts_in_silhouette: Contributing

Contributions are welcome! Please ensure:
1. Code compiles with `mvn clean install`
2. Existing tests pass
3. New features include appropriate documentation

---

## :email: Contact

For questions, issues, or contributions, please open an issue on GitHub.

---

**Last Updated:** March 5, 2026  
**Maintainer:** Jordan Samhi
