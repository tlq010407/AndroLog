# Branch Coverage Implementation Technical Documentation

## Architecture Overview

The Branch Coverage implementation follows AndroLog's existing architectural patterns, divided into two main phases:

### Phase 1: Code Instrumentation (Instrumentation Phase)
```
APK Analysis
    ↓
SummaryBuilder (counts all branches)
    ↓
BranchLogger (inserts log statements at branches)
    ↓
Modified APK (contains branch logging code)
```

### Phase 2: Log Parsing (Log Parsing Phase)
```
Execution Logs
    ↓
LogParser (extracts branch logs)
    ↓
SummaryLogBuilder (counts executed branches)
    ↓
SummaryStatistics (calculates coverage)
    ↓
Coverage Report (text or JSON format)
```

## Core Classes Explanation

### 1. BranchLogger.java (New)

**Responsibility**: Insert log statements at branch locations in code

```java
public class BranchLogger {
    // Singleton pattern, consistent with androspecter's Logger
    public static BranchLogger v()
    
    // Set target package filter
    public void setTargetPackage(String targetPackage)
    
    // Core method: instrument all branches with logging
    public void logAllBranches(String tagToLog, boolean includeLibraries)
    
    // Helper method: insert log statement at specific location
    private void insertLogStatement(...)
}
```

**How it works**:
1. Add transform to Soot's jtp (Jimple Transform Pack)
2. Iterate through all units in method body
3. Identify IfStmt and SwitchStmt
4. Use Jimple API to insert Log.d() calls after branches

### 2. SummaryBuilder.java (Modified)

**New method**: `getInfoBranches(boolean includeLibraries)`

```java
private void getInfoBranches(boolean includeLibraries) {
    addTransformation("jtp.branches", b -> {
        // Iterate through each method body
        Chain<Unit> units = b.getUnits();
        int branchCounter = 0;
        
        for (Unit u : units) {
            Stmt stmt = (Stmt) u;
            if (stmt instanceof IfStmt) {
                branchCounter++;
                String branchLog = String.format("BRANCH=%s|IF|%d", 
                    b.getMethod().getSignature(), branchCounter);
                incrementComponent("branches", branchLog);
            } else if (stmt instanceof SwitchStmt) {
                // Handle multiple cases in switch
                ...
            }
        }
    });
}
```

**Integration in build() method**:
```java
if (CommandLineOptions.v().hasOption("b")) {
    getInfoBranches(includeLibraries);
}
```

### 3. LogParser.java (Modified)

**Key changes**:
- Add `visitedBranches` Set to track all expected branches
- Initialize visitedBranches in constructor
- Add BRANCH type handling in `parseLine()`

```java
private void parseLine(String line) {
    String logType = getLogType(line);
    
    if (logType != null) {
        switch (logType) {
            // ... other types
            case "BRANCH":
                int logIndex = line.indexOf(logType + "=");
                int firstPipe = line.indexOf('|');
                if (visitedBranches.contains(
                    line.substring(logIndex, firstPipe))) {
                    summaryLogBuilder.incrementBranch(line);
                }
                break;
        }
    }
}
```

### 4. SummaryLogBuilder.java (Modified)

**New method**:
```java
public void incrementBranch(String branch) {
    incrementComponent("branches", branch);
}
```

This method follows the existing pattern, delegating to `incrementComponent()` to handle counting, deduplication, and minute-level statistics.

### 5. Main.java (Modified)

**Command line option**:
```java
options.addOption(new CommandLineOption(
    "branches", "b", "Log branches (if/switch)", false, false));
```

**Integration**:
```java
if (CommandLineOptions.v().hasOption("b")) {
    BranchLogger.v().logAllBranches(logIdentifier, includeLibraries);
}
```

### 6. SummaryStatistics.java (No modification needed)

Because it uses a generic map traversal mechanism:
```java
summary.keySet().forEach(category ->
    printFormattedPercentage(category, 
        summary.getOrDefault(category, 0), 
        logSummary.getOrDefault(category, 0)));
```

Automatically supports any new categories added to summary.

## Log Format Specification

### Log format generated during code instrumentation

```
BRANCH=<method_signature>|<statement_type>|<sequence_id>
```

**Examples**:
```
BRANCH=com.example.MainActivity.onCreate()|IF|1
BRANCH=com.example.MainActivity.onCreate()|IF|2
BRANCH=com.example.utils.Utils.process()|SWITCH|3
BRANCH=com.example.utils.Utils.process()|SWITCH|4
BRANCH=com.example.utils.Utils.process()|SWITCH|5
```

### Runtime log format

```
MM-dd HH:mm:ss.SSS ANDROLOG: BRANCH=<method_signature>|<statement_type>|<sequence_id>
```

**Examples**:
```
03-03 10:15:30.123 ANDROLOG: BRANCH=com.example.MainActivity.onCreate()|IF|1
03-03 10:15:31.456 ANDROLOG: BRANCH=com.example.utils.Utils.process()|SWITCH|3
```

## Jimple API Usage

### Key API Calls

```java
// Get Jimple factory
Jimple jimple = Jimple.v();

// Create local variable
Local msgLocal = jimple.newLocal("name", RefType.v("java.lang.String"));
b.getLocals().add(msgLocal);

// Create assignment statement
AssignStmt assign = jimple.newAssignStmt(msgLocal, StringConstant.v(message));

// Create static invoke expression
InvokeExpr expr = jimple.newStaticInvokeExpr(logMethod.makeRef(), args);

// Create invoke statement
InvokeStmt invoke = jimple.newInvokeStmt(expr);

// Insert into unit chain
units.insertBefore(assign, nextUnit);
units.insertAfter(invoke, assign);
```

## Error Handling

### Exception Scenarios

1. **Log class not available**
   ```java
   try {
       // ... logging code
   } catch (Exception e) {
       // Silently fail if Log class unavailable
   }
   ```

2. **Target unit not in chain**
   ```java
   if (nextUnit == null) {
       return;
   }
   ```

3. **Library code exclusion**
   ```java
   if (!includeLibraries && 
       LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
       return;
   }
   ```

## Performance Considerations

### APK Size Increase
- Each branch's logging code approximately 10-20 bytes
- Total increase: number of branches × 15 bytes (average)
- For medium-sized application (2000 branches): approximately 30KB increase

### Runtime Overhead
- Per branch execution: 1 assignment + 1 method call
- Usually no more than 2-5% of total execution time

### Log Output Size
- Each log entry approximately 80-120 bytes
- Total size depends on branch execution frequency

## Extension Suggestions

### Possible Enhancement Features

1. **Conditional Branch Counting**
   ```java
   // Distinguish between true/false branches
   addBranchLogStatement(..., branchId + "-true");
   addBranchLogStatement(..., branchId + "-false");
   ```

2. **Loop Coverage**
   ```java
   else if (stmt instanceof LoopStmt) {
       // Track loop execution count
   }
   ```

3. **Path Coverage**
   ```java
   // Record branch combination execution paths
   String path = computeBranchPath(visitedBranches);
   ```

## Test Suggestions

### Unit Test Scenarios

1. **Simple if statement**
   ```java
   if (x > 0) {
       // branch 1
   } else {
       // branch 2
   }
   ```

2. **Nested if statement**
   ```java
   if (a) {
       if (b) { // branch 2
       }
   } else { // branch 1alt
   }
   ```

3. **Switch statement**
   ```java
   switch (value) {
       case 1: // branch 1
       case 2: // branch 2
       default: // branch 3
   }
   ```

## File Manifest

| File | Type | Changes |
|------|------|---------|
| BranchLogger.java | New | Complete implementation |
| SummaryBuilder.java | Modified | +getInfoBranches(), modify build() |
| SummaryLogBuilder.java | Modified | +incrementBranch() |
| LogParser.java | Modified | +BRANCH handling logic |
| Main.java | Modified | +"-b" option, BranchLogger integration |
| SummaryStatistics.java | No change | Automatic support |

## Version Information

- AndroLog version: 0.1 + Branch Coverage
- Dependency: androspecter 1.1.5
- Soot version: provided by androspecter
- Compilation target: Java 1.8+
