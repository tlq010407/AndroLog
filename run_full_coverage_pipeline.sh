#!/bin/bash

# AndroLog Full Coverage Pipeline Script
# Usage: ./run_full_coverage_pipeline.sh <apk_path> <package_name> <output_dir_name> [log_tag]

set -e  # Exit on error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
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

# Function to resolve device ID (interactive selection)
resolve_device_id() {
    local requested_device=$1
    
    # Use provided device if specified
    if [ -n "$requested_device" ]; then
        ADB_DEVICE="$requested_device"
        return 0
    fi
    
    # Check for ANDROLOG_DEVICE_ID environment variable
    if [ -n "$ANDROLOG_DEVICE_ID" ]; then
        ADB_DEVICE="$ANDROLOG_DEVICE_ID"
        return 0
    fi
    
    # Get list of connected devices
    local devices
    devices=$(adb devices 2>/dev/null | awk '/\tdevice$/ {print $1}')
    local count
    count=$(echo "$devices" | sed '/^$/d' | wc -l | xargs)
    
    # If exactly one device, use it
    if [ "$count" -eq 1 ]; then
        ADB_DEVICE=$(echo "$devices" | sed '/^$/d' | head -1)
        return 0
    fi
    
    # If no devices, show error
    if [ "$count" -eq 0 ]; then
        log_error "No online emulator/device found"
        local avd_name
        avd_name=$(emulator -list-avds 2>/dev/null | head -1)
        if [ -n "$avd_name" ]; then
            log_info "Start one with: emulator -avd $avd_name -no-snapshot -writable-system -port 5560"
        fi
        return 1
    fi
    
    # Multiple devices - let user choose
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

# Check arguments
if [ $# -lt 3 ]; then
    log_error "Usage: $0 <apk_path> <package_name> <output_dir_name> [log_tag] [device_id]"
    log_error "Example: $0 /path/to/app.apk com.example.app my_app_output MY_TAG emulator-5554"
    exit 1
fi

APK_PATH="$1"
PACKAGE_NAME="$2"
OUTPUT_DIR_NAME="$3"
LOG_TAG="${4:-ANDROLOG}"
REQUESTED_DEVICE="${5:-}"

# Validate APK exists
if [ ! -f "$APK_PATH" ]; then
    log_error "APK file not found: $APK_PATH"
    exit 1
fi

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORMS_PATH="/Users/liqi/Library/Android/sdk/platforms"
OUTPUT_BASE="$SCRIPT_DIR/fse-dataset/instrumented_apk"
OUTPUT_DIR="$OUTPUT_BASE/$OUTPUT_DIR_NAME"
JAR_PATH="$SCRIPT_DIR/target/androlog-0.1-jar-with-dependencies.jar"

# Resolve device ID (will be set by resolve_device_id function)
ADB_DEVICE=""

APK_FILENAME=$(basename "$APK_PATH")
APK_NAME="${APK_FILENAME%.apk}"

log_info "=========================================="
log_info "AndroLog Coverage Pipeline"
log_info "=========================================="
log_info "APK: $APK_FILENAME"
log_info "Package: $PACKAGE_NAME"
log_info "Output: $OUTPUT_DIR"
log_info "Log Tag: $LOG_TAG"
log_info "=========================================="

# Step 1: Instrument APK
log_info "Step 1/6: Instrumenting APK..."
rm -rf "$OUTPUT_DIR"
java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$APK_PATH" \
    -o "$OUTPUT_DIR" \
    -c -m -s -b \
    -l "$LOG_TAG"

if [ $? -ne 0 ]; then
    log_error "Instrumentation failed"
    exit 1
fi
log_success "APK instrumented successfully"

# Verify instrumented APK exists
INSTRUMENTED_APK="$OUTPUT_DIR/$APK_FILENAME"
if [ ! -f "$INSTRUMENTED_APK" ]; then
    log_error "Instrumented APK not found at: $INSTRUMENTED_APK"
    exit 1
fi

# Step 2: Select and check emulator connection
log_info "Step 2/6: Selecting and checking emulator connection..."
if ! resolve_device_id "$REQUESTED_DEVICE"; then
    log_error "Failed to resolve device ID"
    exit 1
fi

log_info "Using device: $ADB_DEVICE"
adb -s "$ADB_DEVICE" get-state >/dev/null 2>&1
if [ $? -ne 0 ]; then
    log_error "Device $ADB_DEVICE is not responding. Please check the device."
    exit 1
fi
log_success "Device connected: $ADB_DEVICE"

# Step 3: Uninstall old version and install instrumented APK
log_info "Step 3/6: Installing instrumented APK..."
log_info "Uninstalling existing app..."
adb -s "$ADB_DEVICE" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true

log_info "Installing new APK..."
adb -s "$ADB_DEVICE" install --no-incremental -r "$INSTRUMENTED_APK"
if [ $? -ne 0 ]; then
    log_error "Installation failed"
    exit 1
fi
log_success "APK installed successfully"

# Step 4: Clear logcat and run the app
log_info "Step 4/6: Running application and collecting logs..."
log_info "Clearing logcat buffer..."
adb -s "$ADB_DEVICE" logcat -c

log_info "Launching application..."
adb -s "$ADB_DEVICE" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

log_info "Waiting for application to initialize (15 seconds)..."
sleep 15

# Allow user to interact with the app
log_warning "=========================================="
log_warning "Application is now running!"
log_warning "Please interact with the app to generate coverage data."
log_warning "Press ENTER when you're done testing..."
log_warning "=========================================="
read -r

# Step 5: Fetch logs
log_info "Step 5/6: Fetching logs from device..."
LOG_FILE="$OUTPUT_DIR/${OUTPUT_DIR_NAME}_logs.txt"
FULL_LOGCAT="$OUTPUT_DIR/${OUTPUT_DIR_NAME}_full_logcat.txt"

log_info "Dumping full logcat to: $FULL_LOGCAT"
adb -s "$ADB_DEVICE" logcat -d > "$FULL_LOGCAT"

log_info "Extracting $LOG_TAG logs to: $LOG_FILE"
grep "$LOG_TAG" "$FULL_LOGCAT" > "$LOG_FILE" || log_warning "No logs found with tag: $LOG_TAG"

LOG_COUNT=$(wc -l < "$LOG_FILE" 2>/dev/null || echo "0")
log_success "Extracted $LOG_COUNT log entries"

if [ "$LOG_COUNT" -eq 0 ]; then
    log_warning "No instrumentation logs found. Check if the app ran correctly."
fi

# Step 6: Generate coverage report
log_info "Step 6/6: Generating coverage report..."
COVERAGE_OUTPUT="$OUTPUT_DIR/coverage_report.json"
CFG_OUTPUT="$OUTPUT_DIR/static_apk.cfg"

java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$APK_PATH" \
    -l "$LOG_TAG" \
    -c -m -s -b \
    -pa "$LOG_FILE" \
    -j "$COVERAGE_OUTPUT" \
    -cfg "$CFG_OUTPUT"

if [ $? -ne 0 ]; then
    log_error "Coverage report generation failed"
    exit 1
fi

log_success "Coverage report generated: $COVERAGE_OUTPUT"

# Generate text summary
log_info "Generating text summary..."
SUMMARY_FILE="$OUTPUT_DIR/coverage_summary.txt"
java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$APK_PATH" \
    -l "$LOG_TAG" \
    -c -m -s -b \
    -pa "$LOG_FILE" \
    -cfg "$CFG_OUTPUT" \
    > "$SUMMARY_FILE" 2>&1

# Final summary
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
log_info "  - Coverage summary: $SUMMARY_FILE"
log_success "=========================================="
