# AndroLog Enhancement Summary

### NEW: Strict Dual-Edge Branch Coverage & CFG Export (March 6, 2026)

**Files:**
- `src/main/java/com/jordansamhi/androlog/BranchDescriptorUtil.java` (NEW)
- `src/main/java/com/jordansamhi/androlog/CfgExporter.java` (NEW)
- `src/main/java/com/jordansamhi/androlog/BranchLogger.java` (UPDATED)
- `src/main/java/com/jordansamhi/androlog/SummaryBuilder.java` (UPDATED)
- `src/main/java/com/jordansamhi/androlog/LogParser.java` (UPDATED)
- `src/main/java/com/jordansamhi/androlog/Main.java` (UPDATED)
- `run_full_coverage_pipeline.sh` (UPDATED)

**Enhancements:**

#### 1. Hypergranular Branch Identification
Instead of simple numeric IDs, branches now have **readable descriptors**:

**Before:**
```
BRANCH=%s|IF|%d
BRANCH=%s|SWITCH|%d
```

**After:**
```
BRANCH=<method_sig>|IF|<location>|EDGE=TRUE|COND=<condition>
BRANCH=<method_sig>|IF|<location>|EDGE=FALSE|COND=<condition>
BRANCH=<method_sig>|SWITCH|<location>|CASE=<value>
BRANCH=<method_sig>|SWITCH|<location>|CASE=DEFAULT
```

**Benefits:**
- Location tracking: Source line (L123) or identity hash (U3c7a65ec) if unavailable
- Edge type: TRUE/FALSE for IF, case values for SWITCH, DEFAULT for uncovered default path
- Condition expression: Jimple IR representation for understanding branch logic
- Unique identification: No hash collisions, can correlate with CFG

#### 2. Strict Dual-Edge Coverage for IF Statements
Traditional single-branch counting is replaced with **dual-edge coverage**:
- Each `if` statement generates 2 branch edges: **TRUE** (condition satisfied) and **FALSE** (condition not satisfied)
- Both edges must be executed separately to achieve 100% IF coverage
- Enables precise identification of untested code paths

Example:
```java
if (user != null) {  // Branch point
    user.login();    // TRUE edge must be taken AND
}                    // FALSE edge must be taken (when user == null)
```

#### 3. Complete SWITCH Coverage including DEFAULT
- Each `switch` case is tracked individually with its value
- **DEFAULT case is explicitly tracked** as a separate edge
- Enables identification of missing case handlers or default path gaps

#### 4. Processing CFG Export (NEW)
Added `-cfg` option to export complete control flow graph with embedded branch metadata:

**CFG Format:**
```
METHOD <method_signature>
NODE <id> <location> <jimple_code>
EDGE <source> -> <target>
BRANCH_IF <branch_descriptor>
BRANCH_SWITCH <branch_descriptor>
ENDMETHOD
```

**Usage:**
```bash
java -jar androlog.jar -p platforms -a app.apk -l TAG -c -m -s -b -pa logs.txt -cfg processing.cfg
```

**Applications:**
- Gap analysis: Find all uncovered branch points
- Control flow visualization: Understand method structure
- Coverage correlation: Cross-reference CFG nodes with runtime logs
- Debugging: Identify complex conditional logic

#### 5. BranchDescriptorUtil Utility Class
New utility for generating consistent, parseable branch descriptors:
- Extracts source line numbers or identity hash codes for location
- Handles both TableSwitchStmt and LookupSwitchStmt case resolution
- Sanitizes condition expressions to prevent parsing errors

#### 6. Updated LogParser for Precise Branch Matching
Enhanced branch matching logic:
- Full branch key matching instead of prefix-only
- Backward compatibility with method-level fallback
- Handles edge-type filtering (TRUE/FALSE/CASE=value)

#### 7. Full Pipeline Integration
- `run_full_coverage_pipeline.sh` automatically calls CFG generation
- Final report displays `processing.cfg` output path
- All JSON/text summary generation includes CFG exports

**Example Output:**
```
Files generated:
  - Instrumented APK: fse-dataset/oceanex_test/OceanEx.apk
  - Filtered logs: fse-dataset/oceanex_test/oceanex_test_logs.txt (15432 entries)
  - Coverage JSON: fse-dataset/oceanex_test/coverage_report.json
  - Processing CFG: fse-dataset/oceanex_test/processing.cfg ⭐ NEW
  - Coverage summary: fse-dataset/oceanex_test/coverage_summary.txt
```

---

### 1. BranchLogger Register Optimization
**File:** `src/main/java/com/jordansamhi/androlog/BranchLogger.java`

**Problem:** Branch logging injection was creating intermediate String local variables, disturbing register type flow and potentially causing DEX verification errors.

**Solution:** Modified `insertLogStatement()` to directly pass `StringConstant.v(message)` as argument to `Log.d()` invocation, eliminating unnecessary local variable creation.

**Code Change:**
```java
// Before:
Local msgLocal = Jimple.v().newLocal("logMsg", RefType.v("java.lang.String"));
body.getLocals().add(msgLocal);
AssignStmt assignStmt = Jimple.v().newAssignStmt(msgLocal, StringConstant.v(message));
units.insertBefore(assignStmt, u);
// ... pass msgLocal to Log.d()

// After:
// Directly pass StringConstant.v(message) to Log.d() arguments
```

### 2. Split APK Attribute Cleanup
**File:** `src/main/java/com/jordansamhi/androlog/ApkPreparator.java`

**Problem:** Mood Tracker installation failed with `INSTALL_FAILED_MISSING_SPLIT` error. The APK contained `android:requiredSplitTypes="base"` and `android:splitTypes` attributes in AndroidManifest.xml, causing the system to expect additional split APK files.

**Solution:** Implemented `removeSplitAttributes()` method that:
1. Uses apktool to decode the APK
2. Removes split-related attributes via regex replacement
3. Rebuilds the APK
4. Signs and aligns the cleaned APK

**Code Addition:**
```java
private void removeSplitAttributes() throws IOException, InterruptedException {
    // Decode APK with apktool
    // Remove android:requiredSplitTypes and android:splitTypes from manifest
    // Build cleaned APK
}
```

### 3. Config Properties Compatibility Fix
**File:** `src/main/java/com/jordansamhi/androlog/ApkPreparator.java`

**Problem:** Tool configuration reading was inconsistent. Code expected `apksignerPath` and `zipalignPath` keys, but actual `config.properties` used `apksigner` and `zipalign`.

**Solution:** Added fallback logic in constructor:
```java
String apksignerKey = cfg.getProperty("apksigner");
if (apksignerKey == null) {
    apksignerKey = cfg.getProperty("apksignerPath");
}
// Same for zipalign
```

### 4. Safe Mode Implementation
**File:** `src/main/java/com/jordansamhi/androlog/Main.java`

**Problem:** Some APKs (especially Kotlin + R8 optimized) are fundamentally incompatible with Soot's Jimple transformation, causing VerifyError even with minimal instrumentation.

**Solution:** 
- Added `-nr` (no-rewrite) command line option
- Implemented auto-detection: if no instrumentation flags are specified, automatically skip Soot processing
- Implemented `copyOriginalApkToOutput()` to preserve original APK unchanged

**Code Addition:**
```java
options.addOption(new CommandLineOption("no-rewrite", "nr", 
    "Skip Soot rewrite and copy original APK to output", false, false));

if (noRewrite || !hasInstrumentation) {
    copyOriginalApkToOutput(inputApk, outputPath);
    return;
}
```

### 5. Complete Automation Scripts

#### `run_automated_coverage.sh`
**Purpose:** Fully automated coverage pipeline with monkey testing

**Features:**
- Instruments APK with all coverage types (`-c -m -s -b`)
- Installs to emulator
- Runs configurable-duration monkey testing
- Collects and filters logs
- Generates JSON and text coverage reports
- **Fixed:** Added `-c -m -s -b` flags to coverage generation commands

**Usage:**
```bash
./run_automated_coverage.sh <apk> <package> <output_dir> <duration_seconds> [log_tag]
```

#### `run_full_coverage_pipeline.sh`
**Purpose:** Interactive testing workflow

**Features:**
- Similar to automated version
- Pauses after app launch for manual interaction
- User presses ENTER when done testing
- **Fixed:** Added `-c -m -s -b` and `-l` flags to coverage commands

**Usage:**
```bash
./run_full_coverage_pipeline.sh <apk> <package> <output_dir> [log_tag]
```

### 6. Documentation

#### `COVERAGE_QUICKSTART.md`
Quick reference guide with:
- Command examples
- JSON output structure
- Troubleshooting guidance
- Common error solutions

#### `COVERAGE_PIPELINE_GUIDE.md`
Comprehensive documentation with:
- Detailed workflow explanations
- Parameter references
- Best practices
- Advanced usage scenarios

#### `example_usage.sh`
Executable examples for:
- Interactive mode
- Automated mode
- Manual log processing
- Safe mode usage

## Critical Fixes for Coverage Generation

### The Missing Flags Bug
**Problem:** Initial automation scripts generated empty coverage reports (all zeros).

**Root Cause:** `SummaryBuilder.build(includeLibraries)` checks for command-line flags (`-c`, `-m`, `-s`, `-b`) to determine which component types to collect. When parsing logs with `-pa`, if these flags were omitted, no components were collected, resulting in zero matches.

**Solution:** Added `-c -m -s -b` flags to ALL coverage generation commands:
```bash
# Before (WRONG - produces empty report):
java -jar androlog.jar -p $PLATFORMS -a $APK -l $TAG -pa $LOGS -j output.json

# After (CORRECT - produces valid report):
java -jar androlog.jar -p $PLATFORMS -a $APK -l $TAG -c -m -s -b -pa $LOGS -j output.json
```

**Updated Files:**
- `run_automated_coverage.sh` (2 commands fixed)
- `run_full_coverage_pipeline.sh` (2 commands fixed)
- `example_usage.sh` (1 example fixed)

## Directory Structure
```
AndroLog/
├── src/main/java/com/jordansamhi/androlog/
│   ├── Main.java                          # Added -nr flag, auto-detection, -cfg wiring
│   ├── BranchLogger.java                  # Optimized register usage + dual-edge instrumentation
│   ├── BranchDescriptorUtil.java          # NEW: Readable branch descriptor generation
│   ├── CfgExporter.java                   # NEW: CFG export with branch metadata
│   ├── SummaryBuilder.java                # Aligned with strict dual-edge counting
│   ├── LogParser.java                     # Enhanced for precise branch matching
│   ├── ApkPreparator.java                 # Added split removal, config fix
│   └── ...
├── run_automated_coverage.sh              # Full automation with monkey
├── run_full_coverage_pipeline.sh          # Interactive testing workflow + CFG export
├── COVERAGE_QUICKSTART.md                 # Updated with CFG documentation
├── ENHANCEMENT_SUMMARY.md                 # This file
├── FRIDA_GUIDE.md                         # Frida instrumentation guide
├── README.md                              # Main documentation (updated)
└── target/
- **Build Command:** `mvn clean package`
- **JAR Location:** `target/androlog-0.1-jar-with-dependencies.jar`
- **Build Date:** March 6, 2026
- **Status:** All tests passing, fully functional with strict dual-edge branch coverage
- **Build Command:** `mvn clean package`
- **JAR Location:** `target/androlog-0.1-jar-with-dependencies.jar`
- **Build Date:** March 4, 2026
- **Status:** All tests passing, fully functional

## Usage Recommendations

### For Java/ProGuard Apps (Recommended)
```bash
./run_automated_coverage.sh app.apk com.example.app output 30 TESTTAG
```

### For Kotlin/R8 Apps (Use Safe Mode)
```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p $PLATFORMS -a app.apk -o output -nr
```

### For Manual Testing
```bash
./run_full_coverage_pipeline.sh app.apk com.example.app output TESTTAG
```

## Known Limitations

1. **Kotlin + R8 Optimization:** Soot cannot reliably transform highly optimized Kotlin bytecode
2. **Coverage Percentages:** Low percentages (0.8-1.7%) are normal for short monkey tests
3. **Library Code:** Includes all libraries by default; use `-n` flag to exclude libraries
4. **Emulator Required:** Scripts assume `emulator-5554` device name

## Future Improvements

1. Add support for custom emulator device names
2. Implement retry logic for flaky monkey tests
3. Add coverage diff comparison between test runs
4. Support for multiple concurrent test devices
5. Integration with CI/CD pipelines

---

**Version:** AndroLog v0.1 Enhanced (Strict Branch Coverage)
**Date:** March 6, 2026  
**Contributors:** Enhanced instrumentation, split APK handling, automation pipeline, strict dual-edge branch coverage & CFG export
