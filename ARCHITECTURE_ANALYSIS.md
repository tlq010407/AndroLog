# AndroLog Architecture & Frida Hook Analysis

**Date:** March 11, 2026  
**Version:** AndroLog v0.1

---

## Executive Summary

AndroLog is a **dual-mode Android coverage analysis tool** supporting:
- **Soot-based static instrumentation** (bytecode rewriting)
- **Frida-based runtime hooking** (no APK modification)

This document analyzes all available functions, Frida hook capabilities, and coverage reporting mechanisms.

---

## System Architecture

### Core Components

```
AndroLog
├── Main.java                      # Entry point, mode selection
├── Instrumentation (Soot Mode)
│   ├── Logger.java               # Method/class/component logging
│   ├── BranchLogger.java         # Branch (if/switch) instrumentation
│   └── SummaryBuilder.java       # Static analysis aggregation
├── Runtime (Frida Mode)
│   ├── FridaInstrumentation.java # Frida agent generator & manager
│   └── CompatibilityDetector.java# Auto-detect Kotlin+R8 apps
├── Analysis
│   ├── LogParser.java            # Parse runtime logs
│   ├── SummaryLogBuilder.java    # Build coverage from logs
│   └── SummaryStatistics.java    # Generate JSON/text reports
└── Scripts
    ├── static-analysis           # Pure static Soot analysis
    ├── run_frida_coverage.sh     # Static/Monkey mode wrapper
    ├── frida-manual              # Manual Frida test
    └── frida-manual-soot         # Frida + Soot hybrid
```

---

## Coverage Metrics Tracked

### 1. **Classes** (`-c`)
- **What:** All Java/Kotlin classes in app
- **Tracked by:** Class enumeration
- **Format:** `CLASS=com.example.MyClass`

### 2. **Methods** (`-m`)
- **What:** All method definitions
- **Tracked by:** Method signature analysis
- **Soot Format:** `<com.example.MyClass: void onCreate()>`
- **Frida Format:** `com.example.MyClass.onCreate()`

### 3. **Statements** (`-s`)
- **What:** Individual Jimple instructions (bytecode level)
- **Tracked by:** Unit iteration in method bodies
- **Format:** `STATEMENT=<method>|<jimple_stmt>|<line_num>`
- **Excludes:** Identity stmts, return stmts, monitor stmts

### 4. **Branches** (`-b`)
- **What:** Conditional branches (if/switch)
- **Tracked by:** IfStmt and SwitchStmt detection
- **Format:** `BRANCH=<method>|IF|<num>` or `BRANCH=<method>|SWITCH|<num>`

### 5. **Android Components** (`-cp`)
- **Activities:** Lifecycle methods
- **Services:** Background services
- **BroadcastReceivers:** Event handlers
- **ContentProviders:** Data providers

---

## Frida Hook Capabilities

### What Frida **CAN** Hook

#### **1. Class Enumeration**
```javascript
Java.enumerateLoadedClasses({
  onMatch: function(className) {
    // Log: CLASS=org.schabi.newpipe.MainActivity
  }
})
```
- **Works:** Real-time class discovery
- **Coverage:** Classes loaded during app execution
- **Limitation:** Only sees classes actually loaded

#### **2. Method Execution (via Method.invoke)**
```javascript
var Method = Java.use('java.lang.reflect.Method');
Method.invoke.overload('java.lang.Object', '[Ljava.lang.Object;').implementation = function(receiver, args) {
  var className = this.getDeclaringClass().getName();
  var methodName = this.getName();
  // Log: METHOD=com.example.MyClass.myMethod()
  return this.invoke(receiver, args);
};
```
- **Works:** Catches reflected method calls
- **Coverage:** Methods invoked via reflection (common in Android framework)
- **Limitation:** Misses direct non-reflected calls

#### **3. Statement Tracking (Synthetic)**
```javascript
// For each hooked method
Log.d(TAG, 'STATEMENT=' + methodSig + '|1');
```
- **Works:** Partial (synthetic markers)
- **Coverage:** Statement-level tracking via proxy
- **Limitation:** Not actual Jimple statement tracking, just method entry

#### **4. Branch Tracking**
- **Status:** Not implemented in current Frida agent
- **Reason:** Would require per-branch hooks (high overhead)
- **Workaround:** Use Soot mode for branch analysis

---

## Frida Agent Deep Dive

### Current Implementation (`FridaInstrumentation.generateFridaAgent()`)

#### Phase 1: Class Enumeration (2s delay)
```javascript
setTimeout(function() {
  Java.enumerateLoadedClasses({
    onMatch: function(cls) {
      if (cls.indexOf(TARGET) === 0) {
        Log.d(TAG, 'CLASS=' + cls);
      }
    }
  });
}, 2000);
```
**Why delayed?** Avoids hooking system classes during startup (stability)

#### Phase 2: Method Hooking (3.5s delay)
```javascript
setTimeout(function() {
  var Method = Java.use('java.lang.reflect.Method');
  var invoke = Method.invoke.overload('java.lang.Object', '[Ljava.lang.Object;');
  
  invoke.implementation = function(receiver, args) {
    // Log method info
    var sig = declClassName + '.' + methodName + '()';
    Log.d(TAG, 'METHOD=' + sig);
    
    // Call original
    return invoke.call(this, receiver, args);
  };
}, 3500);
```
**Why Method.invoke?** 
- Android heavily uses reflection
- Catches framework callbacks (onCreate, onClick, etc.)
- Lower overhead than hooking every method

#### Phase 3: Statement Logging (5s delay)
```javascript
setTimeout(function() {
  for (var methodSig in seenMethod) {
    Log.d(TAG, 'STATEMENT=' + methodSig + '|1');
  }
}, 5000);
```
**Why synthetic?** 
- True per-statement hooking = massive overhead
- One log per method = approximation

---

## Coverage Report Generation

### Workflow

```
1. Static Analysis (Soot)
   └─> Enumerate ALL classes/methods/statements/branches in APK
       (This is the "total" count)

2. Runtime Execution (Frida/Manual/Monkey)
   └─> Collect logs: CLASS=..., METHOD=..., STATEMENT=..., BRANCH=...

3. Log Parsing (LogParser.java)
   └─> Match runtime logs against static analysis data
       (This is the "covered" count)

4. Report Generation (SummaryStatistics.java)
   └─> Calculate percentages: (covered / total) * 100
   └─> Output JSON: coverage_output.json
```

### JSON Output Structure
```json
{
  "coverageSummary": {
    "methods": {
      "covered": 3022,      // Methods executed at runtime
      "total": 23426,       // Methods found in APK
      "percent": "12.9%"
    },
    "classes": {
      "covered": 592,
      "total": 3684,
      "percent": "16.1%"
    },
    "statements": {
      "covered": 14532,
      "total": 723640,
      "percent": "2.0%"
    },
    "branches": {
      "covered": 1247,
      "total": 93107,
      "percent": "1.3%"
    }
  }
}
```

---

## Soot vs Frida Comparison

| Feature | Soot Mode | Frida Mode |
|---------|-----------|------------|
| **APK Modification** | ✅ Rewrites bytecode | ❌ No modification |
| **Kotlin+R8 Support** | ⚠️ Limited (VerifyError) | ✅ Full support |
| **Classes** | ✅ 100% enumeration | ✅ Runtime discovery |
| **Methods** | ✅ All methods | ⚠️ Reflected calls only |
| **Statements** | ✅ True Jimple tracking | ⚠️ Synthetic markers |
| **Branches** | ✅ Per-branch logging | ❌ Not implemented |
| **Performance** | Fast (pre-instrumented) | Slow (runtime hooks) |
| **Stability** | ⚠️ App may crash | ✅ Safe (passive hooks) |
| **Setup** | Requires APK signing | Requires Frida server |

---

## What Frida **Could** Hook (Future)

### 1. **Direct Method Calls**
```javascript
// Hook specific methods directly
var MyClass = Java.use('org.schabi.newpipe.MainActivity');
MyClass.onCreate.implementation = function(savedInstanceState) {
  Log.d(TAG, 'METHOD=org.schabi.newpipe.MainActivity.onCreate()');
  return this.onCreate(savedInstanceState);
};
```
**Pros:** Catches direct calls (not just reflection)  
**Cons:** Requires class pre-loading, can cause crashes

### 2. **Branch-Level Hooks**
```javascript
// Intercept IfStmt equivalents
if (condition) {
  Log.d(TAG, 'BRANCH=MyClass.myMethod|IF|1|TRUE');
} else {
  Log.d(TAG, 'BRANCH=MyClass.myMethod|IF|1|FALSE');
}
```
**Pros:** True branch coverage  
**Cons:** Extremely high overhead, complex implementation

### 3. **ClassLoader Hooks**
```javascript
var ClassLoader = Java.use('java.lang.ClassLoader');
ClassLoader.loadClass.overload('java.lang.String').implementation = function(name) {
  Log.d(TAG, 'CLASS=' + name);
  return this.loadClass(name);
};
```
**Pros:** Earlier class detection  
**Cons:** System classes noise

### 4. **ART/Dalvik Hooks**
```javascript
// Hook at VM level (requires native Frida)
Interceptor.attach(Module.findExportByName("libart.so", "art::ArtMethod::Invoke"), {
  onEnter: function(args) {
    // Direct method invocation tracking
  }
});
```
**Pros:** Catches ALL method calls  
**Cons:** Native code, high complexity, device-specific

---

## Current Implementation Status

### Fully Implemented
- Pure static analysis (Soot with `-no-bodies` for Kotlin)
- Frida class enumeration
- Frida method hooking (reflection-based)
- Log parsing with format fallback
- JSON/text coverage reports
- Automated scripts (static, monkey mode toggle)

### Partially Working
- Frida statement tracking (synthetic only, not true Jimple)
- Monkey mode log collection (0% coverage issue - Frida agent not injecting properly)

### Not Implemented
- Frida branch tracking
- Frida direct method call hooks (non-reflection)
- Real-time coverage dashboard
- Per-minute coverage tracking with Frida

---

## Current Limitations

### Frida Mode Issues
1. **Monkey test produces 0% coverage**
   - **Root cause:** Frida agent not injecting or logs not persisting
   - **Evidence:** `test_log.txt` empty or only shows agent init
   - **Workaround:** Use manual Frida test with ENTER-to-finish

2. **Only reflection-based methods detected**
   - **Root cause:** Hooking `Method.invoke` misses direct calls
   - **Impact:** Lower method coverage than Soot mode
   - **Workaround:** Hybrid Frida+Soot analysis

3. **No branch tracking**
   - **Root cause:** Not implemented (overhead concerns)
   - **Impact:** Branch coverage always 0%
   - **Workaround:** Use Soot mode exclusively for branch analysis

### Soot Mode Issues
1. **Kotlin+R8 VerifyError**
   - **Root cause:** Aggressive R8 optimization breaks Soot verification
   - **Workaround:** Use `-no-bodies` flag (loses statement/branch details)
   - **Better fix:** Use Frida mode for Kotlin apps

2. **Method signature format mismatch**
   - **Soot:** `<com.example.Class: void method()>`
   - **Frida:** `com.example.Class.method()`
   - **Fix:** LogParser now has fallback matching

---

## Recommended Usage

### For Pure Static Analysis
```bash
./static-analysis fse-dataset/newpipe.apk output/static
```
- **Use when:** You only need total counts, not runtime coverage
- **Output:** JSON with all classes/methods/statements/branches (covered=0)

### For Kotlin+R8 Apps (Frida Preferred)
```bash
./frida-manual  # Interactive test
# OR
ANDROLOG_DEVICE_ID=emulator-5560 ./run_frida_coverage.sh app.apk com.pkg output TAG monkey
```
- **Use when:** App crashes with Soot instrumentation
- **Output:** Class/method coverage (statements/branches limited)

### For Java Apps (Soot Preferred)
```bash
java -jar androlog.jar -p platforms/ -a app.apk -c -m -s -b -o output/
adb install output/app.apk
# Use app, collect logs
java -jar androlog.jar -p platforms/ -a app.apk -c -m -s -b -pa logs.txt -j report.json
```
- **Use when:** Standard Java app without R8
- **Output:** Full coverage (all metrics)

---

## Cold Path Extraction Pipeline (New)

To improve branch coverage targeting, the project now includes a two-step cold path workflow:

1. `branch_coverage_analysis.py`
2. `probe_gen.py`

This pipeline converts uncovered branch data into LLM-ready, executable test generation tasks.

### Step 1: Uncovered Branch Extraction (`branch_coverage_analysis.py`)

**Purpose:**
- Compare static CFG branches (`processing.cfg`) with runtime executed branches (log file)
- Export uncovered branches for follow-up probing

**Key behavior (current):**
- Supports `--out-dir`
- Default output directory is the same directory as `--cfg`
- Outputs:
  - `uncovered_branches.txt`
  - `uncovered_branches.csv`

### Step 2: Probe Prompt Generation (`probe_gen.py`)

**Purpose:**
- Read uncovered branches (`.txt` or `.csv`)
- Enrich each branch with APK method bytecode context using AndroGuard
- Generate prompts for LLM-driven test/probe creation

**Key behavior (current):**
- Supports `--out-dir`
- Default output directory is the same directory as `--apk`
- Outputs markdown + JSONL prompt artifacts
- Prompt template now enforces **strict JSON output** from target LLM

### Strict JSON Test Case Schema (Requested from LLM)

Each generated prompt asks the LLM to return JSON objects containing:
- `test_id`
- `type` (`adb_probe|automation_draft`)
- `target_uid`
- `target_edge`
- `requires_sdk_min`
- `preconditions`
- `commands`
- `expected_signal`
- `verification_steps`
- `coverage_verification`
- `risk`

### Coverage Hard-Check Requirement

The generated prompt also requires post-probe verification:
1. Re-run branch log collection
2. Re-run `branch_coverage_analysis.py`
3. Verify target `UID + EDGE` is no longer uncovered (or mark evidence inconclusive)

### Why This Matters

This closes the loop from "coverage gap discovery" to "actionable test generation":
- Not only identifies uncovered branches
- Also provides structured guidance to generate concrete, reproducible probes
- Improves repeatability for iterative coverage growth

---

## How to Verify Coverage is Working

### Check 1: Log Collection
```bash
# During runtime test
adb logcat | grep ANDROLOG
# Should see:
# ANDROLOG: CLASS=com.example.MyClass
# ANDROLOG: METHOD=com.example.MyClass.onCreate()
# ANDROLOG: STATEMENT=<...>|1
```

### Check 2: Log File Content
```bash
cat output/test_log.txt | wc -l
# Should be > 100 lines for decent coverage
```

### Check 3: Coverage JSON
```bash
cat output/coverage_output.json | jq '.coverageSummary.methods.covered'
# Should be > 0
```

### Check 4: Frida Agent Injection
```bash
# In Frida runtime log
cat output/frida_runtime.log | grep "agent ready"
# Should see: "Frida agent ready (safe hook mode)"
```

---

## Future Enhancement Opportunities

### High Priority
1. **Fix Monkey Mode Frida Injection**
   - Device ID not passed to Java → Frida fails to start
   - Solution: Pass `ANDROLOG_DEVICE_ID` to FridaInstrumentation constructor

2. **Implement Direct Method Hooks**
   - Current: Only reflection-based
   - Target: Hook all app methods directly
   - Risk: High crash potential

3. **Add Branch Tracking to Frida**
   - Current: Not implemented
   - Challenge: Performance overhead
   - Approach: Selective branch hooking

### Medium Priority
4. **Real-time Coverage Dashboard**
   - Stream logs → Live coverage updates
   - WebSocket server + HTML dashboard

5. **Hybrid Mode Auto-Switch**
   - Auto-detect Kotlin+R8 → Use Frida
   - Auto-detect Java → Use Soot
   - Already partially implemented via `CompatibilityDetector`

### Low Priority
6. **Coverage Diff Tool**
   - Compare two test runs
   - Identify newly covered code

7. **Fuzzing Integration**
   - Feed coverage data to fuzzer
   - Guide test generation

---

## Key Files Reference

| File | Purpose | Lines |
|------|---------|-------|
| `Main.java` | Entry point, mode selection | 269 |
| `FridaInstrumentation.java` | Frida agent generator | 367 |
| `SummaryBuilder.java` | Static analysis aggregator | 247 |
| `LogParser.java` | Parse runtime logs | 175 |
| `BranchLogger.java` | Branch instrumentation | 200 |
| `static-analysis` | Pure static script | 113 |
| `run_frida_coverage.sh` | Frida wrapper script | 314 |
| `branch_coverage_analysis.py` | Uncovered branch extraction from CFG + logs | Python |
| `probe_gen.py` | Cold path prompt generation with APK bytecode context | Python |
| `COLD_PATH_PIPELINE_SUMMARY.md` | End-to-end cold path workflow documentation | Markdown |

---

## Conclusion

AndroLog provides a **flexible dual-mode architecture**:

- **Soot mode** excels at **comprehensive static analysis** and **traditional Java apps**
- **Frida mode** excels at **Kotlin+R8 apps** and **non-invasive runtime hooking**

Current Frida implementation focuses on **stability over coverage depth**:
- ✅ Classes: Near-complete
- ⚠️ Methods: Reflection-based only
- ⚠️ Statements: Synthetic markers
- ❌ Branches: Not tracked

For production coverage testing, **use Soot mode when possible**. Use Frida mode as a fallback for incompatible apps, accepting lower statement/branch granularity.


## Documentation Update (2026-03-27)

- AndroLog parse mode now reuses existing static_apk.cfg and static_features.jsonl when present, instead of exporting them on every run.
- AndroLog log parsing now supports UTF-16 runtime logs (including UTF-16 files without BOM), fixing false-zero coverage from mood_tag_v3_log.txt-style inputs.
- ranch_coverage log parsing now also handles UTF-16 logs, so BRANCH= lines are detected correctly without manual conversion.
- For AndroLog CLI parsing, pass Android platforms directory via -p, for example: C:\\Users\\liqitang\\AppData\\Local\\Android\\Sdk\\platforms.
- Very small non-zero coverage can still display as  .0% because percentages are formatted to one decimal place.
