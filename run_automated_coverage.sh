#!/bin/bash

# AndroLog Automated Coverage Pipeline (No Interaction)
# Usage: ./run_automated_coverage.sh <apk_path> <package_name> <output_dir_name> <test_duration_seconds> [log_tag]

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check arguments
if [ $# -lt 4 ]; then
    log_error "Usage: $0 <apk_path> <package_name> <output_dir_name> <test_duration_seconds> [log_tag]"
    log_error "Example: $0 app.apk com.example.app my_output 30 MY_TAG"
    exit 1
fi

APK_PATH="$1"
PACKAGE_NAME="$2"
OUTPUT_DIR_NAME="$3"
TEST_DURATION="$4"
LOG_TAG="${5:-ANDROLOG}"

if [ ! -f "$APK_PATH" ]; then
    log_error "APK not found: $APK_PATH"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORMS_PATH="/Users/liqi/Library/Android/sdk/platforms"
OUTPUT_DIR="$SCRIPT_DIR/fse-dataset/instrumented_apk/$OUTPUT_DIR_NAME"
JAR_PATH="$SCRIPT_DIR/target/androlog-0.1-jar-with-dependencies.jar"
ADB_DEVICE="emulator-5554"
APK_FILENAME=$(basename "$APK_PATH")
INSTRUMENTED_APK="$OUTPUT_DIR/$APK_FILENAME"

log_info "===== AUTOMATED COVERAGE PIPELINE ====="
log_info "APK: $APK_FILENAME"
log_info "Duration: ${TEST_DURATION}s"

# 1. Instrument
log_info "[1/6] Instrumenting APK..."
rm -rf "$OUTPUT_DIR"
java -jar "$JAR_PATH" -p "$PLATFORMS_PATH" -a "$APK_PATH" -o "$OUTPUT_DIR" -c -m -s -b -l "$LOG_TAG" || exit 1
log_success "Instrumented"

# 2. Check emulator
log_info "[2/6] Checking emulator..."
adb -s "$ADB_DEVICE" get-state >/dev/null 2>&1 || { log_error "Emulator not connected"; exit 1; }
log_success "Connected"

# 3. Install
log_info "[3/6] Installing..."
adb -s "$ADB_DEVICE" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
adb -s "$ADB_DEVICE" install --no-incremental -r "$INSTRUMENTED_APK" || exit 1
log_success "Installed"

# 4. Run app with monkey testing
log_info "[4/6] Running automated tests for ${TEST_DURATION}s..."
adb -s "$ADB_DEVICE" logcat -c
adb -s "$ADB_DEVICE" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

# Run monkey events
EVENTS_COUNT=$((TEST_DURATION * 10))
log_info "Executing $EVENTS_COUNT monkey events..."
adb -s "$ADB_DEVICE" shell monkey -p "$PACKAGE_NAME" --throttle 100 -v $EVENTS_COUNT >/dev/null 2>&1 &
MONKEY_PID=$!

sleep "$TEST_DURATION"
kill $MONKEY_PID 2>/dev/null || true
log_success "Test completed"

# 5. Fetch logs
log_info "[5/6] Fetching logs..."
LOG_FILE="$OUTPUT_DIR/${OUTPUT_DIR_NAME}_logs.txt"
FULL_LOGCAT="$OUTPUT_DIR/${OUTPUT_DIR_NAME}_full_logcat.txt"

adb -s "$ADB_DEVICE" logcat -d > "$FULL_LOGCAT"
grep "$LOG_TAG" "$FULL_LOGCAT" > "$LOG_FILE" || log_error "No logs with tag $LOG_TAG"

LOG_COUNT=$(wc -l < "$LOG_FILE" 2>/dev/null || echo "0")
log_success "Extracted $LOG_COUNT log entries"

# 6. Generate coverage
log_info "[6/6] Generating coverage report..."
COVERAGE_JSON="$OUTPUT_DIR/coverage_report.json"
COVERAGE_TXT="$OUTPUT_DIR/coverage_summary.txt"

java -jar "$JAR_PATH" -p "$PLATFORMS_PATH" -a "$APK_PATH" -l "$LOG_TAG" -c -m -s -b -pa "$LOG_FILE" -j "$COVERAGE_JSON" || exit 1
java -jar "$JAR_PATH" -p "$PLATFORMS_PATH" -a "$APK_PATH" -l "$LOG_TAG" -c -m -s -b -pa "$LOG_FILE" > "$COVERAGE_TXT" 2>&1

log_success "===== PIPELINE COMPLETE ====="
log_info "Output: $OUTPUT_DIR"
log_info "Logs: $LOG_COUNT entries"
log_info "JSON: $COVERAGE_JSON"
log_info "Summary: $COVERAGE_TXT"
