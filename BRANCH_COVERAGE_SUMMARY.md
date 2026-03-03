# AndroLog Branch Coverage Implementation Summary

## Overview
Successfully added branch coverage functionality to the AndroLog project to track the execution coverage of if/switch branches in code.

## Modified Files List

### 1. **SummaryBuilder.java** - Add Branch Statistics
- **Changes**:
  - Added `getInfoBranches()` method to traverse IfStmt and SwitchStmt in code
  - Added check for "-b" option in `build()` method to enable branch statistics
  - Count IfStmt (each counts as one branch)
  - Count SwitchStmt (each case counts as one branch)

### 2. **SummaryLogBuilder.java** - Add Branch Recording Method
- **Changes**:
  - Added `incrementBranch(String branch)` method to record executed branches
  - This method is consistent with other component types (methods, classes, statements, etc.)

### 3. **LogParser.java** - Add Branch Log Parsing
- **Changes**:
  - Added `visitedBranches` Set to store all expected branches
  - Updated constructor to initialize visitedBranches
  - Added "branches" type detection in `getType()` method
  - Added BRANCH log type handling logic in `parseLine()` method

### 4. **BranchLogger.java** - New Branch Instrumentation Class
- **Features**:
  - Singleton pattern implementation, consistent with androspecter's Logger
  - `logAllBranches(String tagToLog, boolean includeLibraries)` method
  - Insert logs before and after IfStmt processing
  - Insert logs at each case in SwitchStmt
  - Support target package filtering and library code filtering options
  - Log format: `BRANCH=<method>|<type>|<id>`

### 5. **Main.java** - Add Command Line Options and Integration
- **Changes**:
  - Added "-b"/"--branches" command line option: `"Log branches (if/switch)"`
  - Integrated BranchLogger into instrumentation process
  - Set target package parameter when initializing BranchLogger
  - Added handling for "-b" option in command line check

## Usage

### Generate Branch Coverage Report
```bash
java -jar androspec.jar \
  -p platforms.txt \
  -a app.apk \
  -o instrumented_apk \
  -b              # Enable branch coverage statistics
  -l ANDROLOG     # Log identifier
```

### Parse Log to Generate Coverage Report
```bash
java -jar androspec.jar \
  -p platforms.txt \
  -a app.apk \
  -pa log.txt     # Parse log file
  -b              # Enable branch coverage
  -l ANDROLOG
```

### Generate JSON Format Report
```bash
java -jar androspec.jar \
  -p platforms.txt \
  -a app.apk \
  -pa log.txt \
  -b \
  -j output.json  # JSON output
```

## Coverage Report Example

The report will display:
```
=== Coverage Summary ===
------------------------
branches         : 75.5% (151/200)
statements       : 85.2% (342/401)
methods          : 90.1% (181/201)
classes          : 95.0% (19/20)
------------------------
```

## Log Format

Branch log examples:
```
03-03 10:15:30.123 ANDROLOG: BRANCH=com.example.App.onCreate|(IfStmt)V|1
03-03 10:15:31.456 ANDROLOG: BRANCH=com.example.App.onCreate|(SwitchStmt)V|2
03-03 10:15:32.789 ANDROLOG: BRANCH=com.example.App.onClick|(IfStmt)V|3
```

## Technical Details

### Branch Identification
- **IfStmt**: Conditional statement, counts as one branch
- **SwitchStmt**: Switch statement, each case (including default) counts as one branch

### Calculation Method
- **Total branches**: Number of all executable branches in code
- **Covered branches**: Number of branches actually executed in logs
- **Coverage rate** = (covered branches / total branches) × 100%

### Filter Options
- `-n`: Exclude branch statistics in library code
- `--package`/`-pkg`: Only count branches in specific packages

## Backward Compatibility
- All modifications are backward compatible
- Branch coverage will not be calculated when `-b` option is not used
- Existing other coverage types (statement, method, etc.) are not affected

## Precautions
1. Branch coverage statistics occur during code instrumentation phase
2. APK needs to be recompiled to include branch logging code
3. Branch logs will increase APK size and runtime overhead
4. Recommended for use during testing phase; use with caution in production environment
