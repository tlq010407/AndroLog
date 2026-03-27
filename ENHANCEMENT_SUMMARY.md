# AndroLog Enhancement Summary

## NEW: Strict Dual-Edge Branch Coverage, CFG Export, and Semantic Branch Features (March 2026)

**Files:**
- `src/main/java/com/jordansamhi/androlog/BranchDescriptorUtil.java` 
- `src/main/java/com/jordansamhi/androlog/CfgExporter.java` (UPDATED)
- `src/main/java/com/jordansamhi/androlog/BranchLogger.java` 
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
- Source-aware location tracking: L123 if source line exists, otherwise stable fallback token such as U8f32c1ab
- True edge distinction for if statements
- Explicit case/default distinction for switch
- Human-readable condition information in Jimple form
- Better matching between runtime logs, CFG, and exported features

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
Each `switch` case is tracked individually with its value:
- **DEFAULT case is explicitly tracked** as a separate edge
- Enables identification of missing case handlers or default path gaps
This makes it possible to identify:
- missing case coverage
- never-triggered fallback/default behavior
- handler gaps in obfuscated apps
#### 4. Static apl CFG Export
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
java -jar androlog.jar -p platforms -a app.apk -l TAG -c -m -s -b -pa logs.txt -cfg static_apk.cfg
```

**Applications:**
- Gap analysis: Find all uncovered branch points
- Control flow visualization: Understand method structure
- Coverage correlation: Cross-reference CFG nodes with runtime logs
- Debugging: Identify complex conditional logic

#### 5. NEW: Semantic Branch Feature Export (static_features.jsonl)
Added a new semantic feature layer for each branch.

**Output File**
```bash
static_features.jsonl
```
Each line is a JSON object describing one branch.

**Example**
```json
{
    "branch_descriptor":"BRANCH=<p2.d$a: void run()>|IF|Ueeff9a2e|EDGE=TRUE|COND=$z0____0",
    "branch_id":"Bb930d2e0",
    "branch_kind":"IF",
    "branch_location":"Ueeff9a2e",
    "edge":"TRUE","case_label":"",
    "condition":"$z0____0",
    "path_constraints":["$z0____0"],
    
    "stmt_text":"if $z0 == 0 goto return",
    
    "class":"p2.d$a",
    "method":"run",
    "method_signature":"<p2.d$a: void run()>",
    "package":"p2",
    
    "code_origin":"app",
    "is_library":false,
    
    "component_type":"none",
    "exported":false,
    "entrypoint":false,
    
    "api_calls":[],
    "network_apis":[],
    "file_apis":[],
    "reflection_apis":[],
    "crypto_apis":[],
    
    "strings":[],
    "urls":[],
    "commands":[],
    
    "sdk_checks":[],
    "environment_checks":[],
    
    "loop_context":true,
    "async_context":false,
    "thread_context":true
    }
```
##### **Branch Semantic Fields**
**Core Metadata**
- branch_descriptor: full original branch descriptor
- branch_id: short stable identifier derived from descriptor hash
- branch_kind: IF or SWITCH
- branch_location: source line or stable fallback token
- edge: TRUE / FALSE for if
- case_label: switch case value or DEFAULT
- condition: sanitized branch condition
- path_constraints: pure constraint list, such as:
```json
["$z0____0"]
```

**Code Context**
- stmt_text
- class
- method
- method_signature
- package

**Origin Metadata**
- code_origin: "app" or "library"
- is_library: boolean

**API Behavior**
- api_calls
- network_apis
- file_apis
- reflection_apis
- crypto_apis

**Behavioral Signals**
- strings
- urls
- commands
- loop_context
- async_context
- thread_context
#### 6. BranchDescriptorUtil Utility Class
New utility for generating consistent, parseable branch descriptors.

**Responsibilities**
- builds readable descriptors for if and switch
- resolves switch labels for both TableSwitchStmt and LookupSwitchStmt
- sanitizes condition text to avoid parsing ambiguity
- provides source-line-based location when available
-mprovides stable fallback location token when line info is unavailable

#### 7. Updated LogParser for Precise Branch Matching

Branch matching logic is now more precise.

**Improvements**
- full branch-key matching instead of weak prefix-only matching
- better alignment with strict dual-edge semantics
- better support for switch case/default matching
- compatibility with descriptor-based runtime logs

#### 8. Full Pipeline Integration
The coverage pipeline now supports:
- instrumentation
- runtime log collection
- log parsing
- coverage summary generation
- CFG export
- static semantic feature export

**Example Generated Files:**
```
Files generated:
  - Instrumented APK: fse-dataset/oceanex_test/OceanEx.apk
  - Filtered logs: fse-dataset/oceanex_test/oceanex_test_logs.txt
  - Coverage JSON: fse-dataset/oceanex_test/coverage_report.json
  - Processing CFG: fse-dataset/oceanex_test/processing.cfg
  - Static features: fse-dataset/oceanex_test/static_features.jsonl
  - Coverage summary: fse-dataset/oceanex_test/coverage_summary.txt
```

---

### 1. BranchLogger Register Optimization
**File:** `src/main/java/com/jordansamhi/androlog/BranchLogger.java`

**Problem:** Branch logging injection previously created intermediate local string variables, which could disturb register type flow and increase risk of DEX verification issues.

**Solution:** Modified `insertLogStatement()` to directly pass `StringConstant.v(message)` as argument to `Log.d()` invocation, eliminating unnecessary local variable creation.

### 2. Split APK Attribute Cleanup
**File:** `src/main/java/com/jordansamhi/androlog/ApkPreparator.java`

**Problem:** Mood Tracker installation failed with `INSTALL_FAILED_MISSING_SPLIT` error. The APK contained `android:requiredSplitTypes="base"` and `android:splitTypes` attributes in AndroidManifest.xml, causing the system to expect additional split APK files.

**Solution:** Implemented `removeSplitAttributes()` method that:
1. Uses apktool to decode the APK
2. Removes split-related attributes via regex replacement
3. Rebuilds the APK
4. Signs and aligns the cleaned APK


### 3. Config Properties Compatibility Fix
**File:** `src/main/java/com/jordansamhi/androlog/ApkPreparator.java`

**Problem:** Tool configuration reading was inconsistent. Code expected `apksignerPath` and `zipalignPath` keys, but actual `config.properties` used `apksigner` and `zipalign`.

### 4. Safe Mode Implementation
**File:** `src/main/java/com/jordansamhi/androlog/Main.java`

**Problem:** Some APKs (especially Kotlin + R8 optimized) are fundamentally incompatible with Soot's Jimple transformation, causing VerifyError even with minimal instrumentation.

**Solution:** 
- Added `-nr` (no-rewrite) command line option
- Implemented auto-detection: if no instrumentation flags are specified, automatically skip Soot processing
- Implemented `copyOriginalApkToOutput()` to preserve original APK unchanged

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
```
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

1.	**Kotlin + R8 optimization**: Soot still cannot reliably transform some highly optimized Kotlin bytecode.
2.	**Low coverage percentages**: Short monkey runs often produce low percentages such as 0.8% - 1.7%; this is expected.
3.	**Library code included by default**: Use -n to exclude libraries.
4.	**Scripts assume emulator defaults**: Some scripts still assume emulator-5554.
5.	**Semantic features are heuristic**: Fields such as loop_context, async_context, and API categories are heuristic, not fully semantic ground truth.

---

**Version:** AndroLog v0.2 Enhanced (Strict Branch Coverage + CFG + Semantic Features)
**Date:** March 13, 2026  
**Contributors:** Enhanced instrumentation, split APK handling, automation pipeline, strict dual-edge branch coverage & CFG export

## Documentation Update (2026-03-27)

- AndroLog parse mode now reuses existing static_apk.cfg and static_features.jsonl when present, instead of exporting them on every run.
- AndroLog log parsing now supports UTF-16 runtime logs (including UTF-16 files without BOM), fixing false-zero coverage from mood_tag_v3_log.txt-style inputs.
- Branch_coverage log parsing now also handles UTF-16 logs, so BRANCH= lines are detected correctly without manual conversion.
- For AndroLog CLI parsing, pass Android platforms directory via -p, for example: C:\\Users\\liqitang\\AppData\\Local\\Android\\Sdk\\platforms.
- Very small non-zero coverage can still display as 0.0% because percentages are formatted to one decimal place.
