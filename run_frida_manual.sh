#!/bin/bash

# Manual Frida Coverage Testing Script
# Usage: ./run_frida_manual.sh <apk_path> <package_name> <output_dir> <log_tag>

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
DEVICE_ID="${ANDROLOG_DEVICE_ID:-emulator-5554}"
PLATFORMS_PATH="$HOME/Library/Android/sdk/platforms"
JAR_PATH="target/androlog-0.1-jar-with-dependencies.jar"

print_msg() {
    local type=$1
    local msg=$2
    case $type in
        "ERROR") echo -e "${RED}[✗] $msg${NC}" ;;
        "SUCCESS") echo -e "${GREEN}[✓] $msg${NC}" ;;
        "INFO") echo -e "${BLUE}[INFO] $msg${NC}" ;;
        "WARN") echo -e "${YELLOW}[⚠] $msg${NC}" ;;
    esac
}

print_separator() {
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Check arguments
if [ $# -lt 4 ]; then
    echo "Usage: $0 <apk_path> <package_name> <output_dir> <log_tag>"
    echo "Example: $0 app.apk com.example.app output/manual_test FRIDA_MANUAL"
    exit 1
fi

apk_path="$1"
package_name="$2"
output_dir="$3"
log_tag="$4"

# Validate inputs
if [ ! -f "$apk_path" ]; then
    print_msg "ERROR" "APK file not found: $apk_path"
    exit 1
fi

if [ ! -d "$PLATFORMS_PATH" ]; then
    print_msg "ERROR" "Android platforms not found: $PLATFORMS_PATH"
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    print_msg "ERROR" "AndroLog JAR not found: $JAR_PATH"
    exit 1
fi

mkdir -p "$output_dir"

print_separator
echo -e "${GREEN}  Manual Frida Coverage Testing${NC}"
print_separator
print_msg "INFO" "Package: $package_name"
print_msg "INFO" "Tag: $log_tag"
print_msg "INFO" "Output: $output_dir"
print_msg "INFO" "Device: $DEVICE_ID"
echo ""

# Step 1: Check Frida
print_msg "INFO" "[Step 1/5] Checking Frida installation..."
if ! command -v frida &> /dev/null; then
    print_msg "ERROR" "Frida not installed. Run: pip install frida-tools"
    exit 1
fi
print_msg "SUCCESS" "Frida is installed"

# Step 2: Check Frida server
print_msg "INFO" "[Step 2/5] Checking Frida server on device..."
if ! adb -s "$DEVICE_ID" shell "ps | grep frida-server" &> /dev/null; then
    print_msg "WARN" "Frida server not running. Starting..."
    adb -s "$DEVICE_ID" shell "/data/local/tmp/frida-server &" &
    sleep 2
fi
print_msg "SUCCESS" "Frida server is running"

# Step 3: Generate Frida agent
print_msg "INFO" "[Step 3/5] Generating Frida agent..."
java -jar "$JAR_PATH" \
    -p "$PLATFORMS_PATH" \
    -a "$apk_path" \
    -pkg "$package_name" \
    -o "$output_dir" \
    -c -m -s -b \
    -l "$log_tag" \
    -frida > "$output_dir/frida.log" 2>&1

agent_path="$output_dir/frida-agent.js"
if [ ! -f "$agent_path" ]; then
    print_msg "ERROR" "Failed to generate Frida agent"
    exit 1
fi
print_msg "SUCCESS" "Frida agent generated: $agent_path"

# Step 4: Uninstall old version and install
print_msg "INFO" "[Step 4/5] Installing APK..."
adb -s "$DEVICE_ID" uninstall "$package_name" &> /dev/null || true

apk_to_install=$(find "$output_dir" -name "*.apk" | head -1)
if [ -z "$apk_to_install" ]; then
    apk_to_install="$apk_path"
fi

if ! adb -s "$DEVICE_ID" install -r "$apk_to_install" > "$output_dir/install.log" 2>&1; then
    print_msg "ERROR" "Installation failed"
    cat "$output_dir/install.log"
    exit 1
fi
print_msg "SUCCESS" "APK installed"

# Step 5: Start Frida and launch app
print_msg "INFO" "[Step 5/5] Starting Frida instrumentation..."
adb -s "$DEVICE_ID" logcat -c

print_separator
echo -e "${GREEN}▶ Frida is now running and attached to: $package_name${NC}"
echo -e "${YELLOW}"
echo "  Instructions:"
echo "  1. The app will launch automatically"
echo "  2. Interact with the app manually (click buttons, navigate, etc.)"
echo "  3. When done, press ENTER to stop (or Ctrl+C)"
echo "  4. Logs will be automatically collected and analyzed"
echo -e "${NC}"
print_separator
echo ""

# Trap Ctrl+C to collect logs before exit
cleanup() {
    echo ""
    print_msg "INFO" "Stopping Frida and collecting logs..."
    
    # Stop Frida if running
    if [ -n "$frida_pid" ]; then
        kill $frida_pid 2>/dev/null || true
        wait $frida_pid 2>/dev/null || true
    fi
    
    sleep 2
    
    # Collect logs
    adb -s "$DEVICE_ID" logcat -d | grep "$log_tag" > "$output_dir/test_log.txt" || true
    log_count=$(wc -l < "$output_dir/test_log.txt" | xargs)
    print_msg "SUCCESS" "Collected $log_count log entries"
    
    # Generate coverage report
    print_msg "INFO" "Generating coverage report..."
    if java -jar "$JAR_PATH" \
        -p "$PLATFORMS_PATH" \
        -a "$apk_path" \
        -c -m -s -b \
        -l "$log_tag" \
        -pa "$output_dir/test_log.txt" \
        -j "$output_dir/coverage_output.json" \
        > "$output_dir/report.log" 2>&1; then
        
        # Parse results
        if [ -f "$output_dir/coverage_output.json" ]; then
            method_cov=$(grep -o '"percent": "[0-9.]*%"' "$output_dir/coverage_output.json" | sed -n '1p' | sed 's/.*"\([0-9.]*%\)"/\1/' || echo "0.0%")
            class_cov=$(grep -A3 '"classes"' "$output_dir/coverage_output.json" | grep '"percent":' | sed 's/.*"\([0-9.]*%\)"/\1/' || echo "0.0%")
            branch_cov=$(grep -A3 '"branches"' "$output_dir/coverage_output.json" | grep '"percent":' | sed 's/.*"\([0-9.]*%\)"/\1/' || echo "0.0%")
            
            print_msg "SUCCESS" "Coverage report generated"
            print_separator
            echo -e "${GREEN}  Results Summary${NC}"
            print_separator
            echo -e "✓ Package: $package_name"
            echo -e "✓ Log Entries: $log_count"
            echo -e "✓ Class Coverage: ${class_cov}"
            echo -e "✓ Method Coverage: ${method_cov}"
            echo -e "✓ Branch Coverage: ${branch_cov}"
            echo -e "✓ Output: $output_dir/coverage_output.json"
            print_separator
        fi
    else
        print_msg "WARN" "Report generation had issues (see $output_dir/report.log)"
    fi
    
    # Uninstall
    print_msg "INFO" "Uninstalling test app..."
    adb -s "$DEVICE_ID" uninstall "$package_name" &> /dev/null || true
    print_msg "SUCCESS" "Manual testing completed!"
    exit 0
}

trap cleanup INT TERM

# Clear logcat before starting
adb -s "$DEVICE_ID" logcat -c

# Launch app normally first (no Frida yet)
print_msg "INFO" "Launching app normally..."
# Force stop first to ensure clean state
adb -s "$DEVICE_ID" shell am force-stop "$package_name" 2>/dev/null || true
sleep 1

# Resolve the launcher activity dynamically
launcher_activity=$(adb -s "$DEVICE_ID" shell cmd package resolve-activity --brief "$package_name" | tail -1 | tr -d '\r\n')
if [ -z "$launcher_activity" ]; then
    print_msg "ERROR" "Could not find launcher activity for $package_name"
    exit 1
fi

# Launch the app
adb -s "$DEVICE_ID" shell am start -n "$launcher_activity" > /dev/null 2>&1

# Wait for app to fully start and stabilize
sleep 4

# Check if app is running
if ! adb -s "$DEVICE_ID" shell "pidof $package_name" > /dev/null 2>&1; then
    print_msg "ERROR" "App failed to start"
    exit 1
fi

pid=$(adb -s "$DEVICE_ID" shell pidof "$package_name" | tr -d '\r\n')
print_msg "SUCCESS" "App is running (PID: $pid)"

# Now attach Frida to the running process (safer than spawn mode)
print_msg "INFO" "Attaching Frida to running process..."
frida -D "$DEVICE_ID" -p "$pid" -l "$agent_path" > "$output_dir/frida_runtime.log" 2>&1 &
frida_pid=$!

# Give Frida time to attach and inject
sleep 2

# Verify Frida attached successfully
if ps -p $frida_pid > /dev/null 2>&1; then
    print_msg "SUCCESS" "Frida attached successfully"
else
    print_msg "ERROR" "Frida failed to attach"
    exit 1
fi

# Now the app is running with Frida attached
print_separator
echo -e "${GREEN}▶ App is live! Interact with it on the emulator now.${NC}"
echo -e "${YELLOW}Press ENTER when you're done testing (or Ctrl+C).${NC}"
print_separator
echo ""

# Wait for ENTER; Ctrl+C is handled by trap
# Read from /dev/tty to avoid Frida background output interfering
read -r -p "Press ENTER when done testing: " _ < /dev/tty 2>/dev/null || read -r -p "Press ENTER when done testing: " _
cleanup
