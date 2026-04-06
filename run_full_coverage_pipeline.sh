#!/bin/bash

# AndroLog Full Coverage Pipeline Script
# Usage: ./run_full_coverage_pipeline.sh <apk_path> <package_name> <output_dir_name> [log_tag] [device_id]

set -euo pipefail

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

resolve_device_id() {
    local requested_device="${1:-}"

    if [ -n "$requested_device" ]; then
        ADB_DEVICE="$requested_device"
        return 0
    fi

    if [ -n "${ANDROLOG_DEVICE_ID:-}" ]; then
        ADB_DEVICE="$ANDROLOG_DEVICE_ID"
        return 0
    fi

    local devices
    devices=$(adb devices 2>/dev/null | awk '/\tdevice$/ {print $1}')

    local count
    count=$(echo "$devices" | sed '/^$/d' | wc -l | xargs)

    if [ "$count" -eq 1 ]; then
        ADB_DEVICE=$(echo "$devices" | sed '/^$/d' | head -1)
        return 0
    fi

    if [ "$count" -eq 0 ]; then
        log_error "No online emulator/device found"
        local avd_name
        avd_name=$(emulator -list-avds 2>/dev/null | head -1 || true)
        if [ -n "$avd_name" ]; then
            log_info "Start one with: emulator -avd $avd_name -no-snapshot -writable-system -port 5560"
        fi
        return 1
    fi

    log_info "Multiple devices detected. Select one:"
    local idx=1
    while IFS= read -r dev; do
        echo "  [$idx] $dev"
        idx=$((idx + 1))
    done <<< "$(echo "$devices" | sed '/^$/d')"

    local selected_index
    read -r -p "Choose device number: " selected_index < /dev/tty 2>/dev/null || read -r -p "Choose device number: " selected_index

    if ! [[ "$selected_index" =~ ^[0-9]+$ ]] || [ "$selected_index" -lt 1 ] || [ "$selected_index" -gt "$count" ]; then
        log_error "Invalid selection"
        return 1
    fi

    ADB_DEVICE=$(echo "$devices" | sed '/^$/d' | sed -n "${selected_index}p")
    return 0
}

# ---------- args ----------
if [ $# -lt 3 ]; then
    log_error "Usage: $0 <apk_path> <package_name> <output_dir_name> [log_tag] [device_id] [mapping_file]"
    log_error "Example: $0 /path/to/app.apk com.example.app my_app_output MY_TAG emulator-5554 /path/to/mapping.txt"
    exit 1
fi

APK_PATH="$1"
PACKAGE_NAME="$2"
OUTPUT_DIR_NAME="$3"
LOG_TAG="${4:-ANDROLOG}"
REQUESTED_DEVICE="${5:-}"
MAPPING_FILE="${6:-}"

if [ ! -f "$APK_PATH" ]; then
    log_error "APK file not found: $APK_PATH"
    exit 1
fi

if [ -n "$MAPPING_FILE" ] && [ ! -f "$MAPPING_FILE" ]; then
    log_error "Mapping file not found: $MAPPING_FILE"
    exit 1
fi

# ---------- config ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORMS_PATH="/Users/liqi/Library/Android/sdk/platforms"
OUTPUT_BASE="$SCRIPT_DIR/fse-dataset/instrumented_apk"
OUTPUT_DIR="$OUTPUT_BASE/$OUTPUT_DIR_NAME"
JAR_PATH="$SCRIPT_DIR/target/androlog-0.1-jar-with-dependencies.jar"

ADB_DEVICE=""
APK_FILENAME="$(basename "$APK_PATH")"
APK_NAME="${APK_FILENAME%.apk}"

LOG_FILE="$OUTPUT_DIR/${OUTPUT_DIR_NAME}_logs.txt"
FULL_LOGCAT="$OUTPUT_DIR/${OUTPUT_DIR_NAME}_full_logcat.txt"
COVERAGE_OUTPUT="$OUTPUT_DIR/coverage_report.json"
CFG_OUTPUT="$OUTPUT_DIR/static_apk.cfg"
STATIC_FEATURES_OUTPUT="$OUTPUT_DIR/static_features.jsonl"
SUMMARY_FILE="$OUTPUT_DIR/coverage_summary.txt"

if [ ! -f "$JAR_PATH" ]; then
    log_error "Jar not found: $JAR_PATH"
    exit 1
fi

mkdir -p "$OUTPUT_BASE"

# Build optional mapping args
MAPPING_ARGS=()
if [ -n "$MAPPING_FILE" ]; then
    MAPPING_ARGS=(-mf "$MAPPING_FILE")
fi

log_info "=========================================="
log_info "AndroLog Coverage Pipeline"
log_info "=========================================="
log_info "APK: $APK_FILENAME"
log_info "Package: $PACKAGE_NAME"
log_info "Output: $OUTPUT_DIR"
log_info "Log Tag: $LOG_TAG"
log_info "Jar: $JAR_PATH"
if [ -n "$MAPPING_FILE" ]; then
    log_info "Mapping: $MAPPING_FILE"
else
    log_info "Mapping: <none>"
fi
log_info "=========================================="

# ---------- Step 1 ----------
log_info "Step 1/6: Instrumenting APK..."
rm -rf "$OUTPUT_DIR"

java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$APK_PATH" \
    -o "$OUTPUT_DIR" \
    -c -m -s -b \
    -l "$LOG_TAG"

log_success "APK instrumented successfully"

INSTRUMENTED_APK="$OUTPUT_DIR/$APK_FILENAME"
if [ ! -f "$INSTRUMENTED_APK" ]; then
    log_error "Instrumented APK not found at: $INSTRUMENTED_APK"
    exit 1
fi

# ---------- Step 2 ----------
log_info "Step 2/6: Selecting and checking emulator connection..."
if ! resolve_device_id "$REQUESTED_DEVICE"; then
    log_error "Failed to resolve device ID"
    exit 1
fi

log_info "Using device: $ADB_DEVICE"
if ! adb -s "$ADB_DEVICE" get-state >/dev/null 2>&1; then
    log_error "Device $ADB_DEVICE is not responding"
    exit 1
fi
log_success "Device connected: $ADB_DEVICE"

# ---------- Step 3 ----------
log_info "Step 3/6: Installing instrumented APK..."
log_info "Uninstalling existing app..."
adb -s "$ADB_DEVICE" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true

log_info "Installing new APK..."
if ! adb -s "$ADB_DEVICE" install --no-incremental -r "$INSTRUMENTED_APK"; then
    log_error "Installation failed"
    exit 1
fi
log_success "APK installed successfully"

# ---------- Step 4 ----------
log_info "Step 4/6: Running application and collecting logs..."
log_info "Clearing logcat buffer..."
adb -s "$ADB_DEVICE" logcat -c

log_info "Launching application..."
adb -s "$ADB_DEVICE" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

log_info "Waiting for application to initialize (15 seconds)..."
sleep 15

log_warning "=========================================="
log_warning "Application is now running!"
log_warning "Please interact with the app to generate coverage data."
log_warning "Press ENTER when you're done testing..."
log_warning "=========================================="
read -r

# ---------- Step 5 ----------
log_info "Step 5/6: Fetching logs from device..."
log_info "Dumping full logcat to: $FULL_LOGCAT"
adb -s "$ADB_DEVICE" logcat -d > "$FULL_LOGCAT"

log_info "Extracting $LOG_TAG logs to: $LOG_FILE"
grep "$LOG_TAG" "$FULL_LOGCAT" > "$LOG_FILE" || true

LOG_COUNT=$(wc -l < "$LOG_FILE" 2>/dev/null || echo "0")
log_success "Extracted $LOG_COUNT log entries"

if [ "$LOG_COUNT" -eq 0 ]; then
    log_warning "No instrumentation logs found. Check if the app ran correctly."
fi

# ---------- Step 6 ----------
log_info "Step 6/6: Generating coverage report and static analysis..."

java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$APK_PATH" \
    -l "$LOG_TAG" \
    -c -m -s -b \
    -pa "$LOG_FILE" \
    -j "$COVERAGE_OUTPUT" \
    -cfg "$CFG_OUTPUT" \
    ${MAPPING_ARGS[@]+"${MAPPING_ARGS[@]}"}

log_success "Coverage report generated: $COVERAGE_OUTPUT"

if [ ! -f "$CFG_OUTPUT" ]; then
    log_error "Static CFG was not generated: $CFG_OUTPUT"
    exit 1
fi
log_success "Static APK CFG generated: $CFG_OUTPUT"

if [ ! -f "$STATIC_FEATURES_OUTPUT" ]; then
    log_warning "static_features.jsonl was not found at: $STATIC_FEATURES_OUTPUT"
    log_warning "Check whether your CfgExporter writes static_features.jsonl beside the cfg output."
else
    FEATURES_COUNT=$(wc -l < "$STATIC_FEATURES_OUTPUT" 2>/dev/null || echo "0")
    log_success "Static features generated: $STATIC_FEATURES_OUTPUT ($FEATURES_COUNT entries)"
fi

# ---------- summary text ----------
log_info "Generating text summary..."
java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$APK_PATH" \
    -l "$LOG_TAG" \
    -c -m -s -b \
    -pa "$LOG_FILE" \
    -cfg "$CFG_OUTPUT" \
    ${MAPPING_ARGS[@]+"${MAPPING_ARGS[@]}"} \
    > "$SUMMARY_FILE" 2>&1 || true

# ---------- Final summary ----------
log_success "=========================================="
log_success "Pipeline completed successfully!"
log_success "=========================================="
log_info "Output directory: $OUTPUT_DIR"
log_info "Files generated:"
log_info "  - Instrumented APK: $INSTRUMENTED_APK"
log_info "  - Full logcat: $FULL_LOGCAT"
log_info "  - Filtered logs: $LOG_FILE ($LOG_COUNT entries)"
log_info "  - Coverage JSON: $COVERAGE_OUTPUT"
log_info "  - Static APK CFG: $CFG_OUTPUT"

if [ -f "$STATIC_FEATURES_OUTPUT" ]; then
    FEATURES_COUNT=$(wc -l < "$STATIC_FEATURES_OUTPUT" 2>/dev/null || echo "0")
    log_info "  - Static features: $STATIC_FEATURES_OUTPUT ($FEATURES_COUNT entries)"
else
    log_info "  - Static features: NOT GENERATED"
fi

log_info "  - Coverage summary: $SUMMARY_FILE"
if [ -n "$MAPPING_FILE" ]; then
    log_info "  - Mapping used: $MAPPING_FILE"
else
    log_info "  - Mapping used: NONE"
fi
log_success "=========================================="