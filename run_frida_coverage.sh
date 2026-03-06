#!/bin/bash

# Frida-based Coverage Testing Script
# For Kotlin+R8 apps that fail with Soot instrumentation

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# macOS-compatible timeout function (handles both Linux and macOS)
run_with_timeout() {
    local timeout_sec=$1
    shift
    local cmd=("$@")
    
    # Try native timeout command first (Linux)
    if command -v timeout &>/dev/null; then
        timeout "$timeout_sec" "${cmd[@]}"
        return $?
    fi
    
    # Fallback: gtimeout (macOS with coreutils via homebrew)
    if command -v gtimeout &>/dev/null; then
        gtimeout "$timeout_sec" "${cmd[@]}"
        return $?
    fi
    
    # Fallback: bash-based timeout (for macOS without coreutils)
    # Run command in background and kill after timeout
    "${cmd[@]}" &
    local pid=$!
    local elapsed=0
    
    while kill -0 $pid 2>/dev/null && [ $elapsed -lt $timeout_sec ]; do
        sleep 1
        elapsed=$((elapsed + 1))
    done
    
    if kill -0 $pid 2>/dev/null; then
        kill -9 $pid 2>/dev/null || true
        return 124  # timeout exit code
    fi
    
    wait $pid
    return $?
}

# Configuration
PLATFORMS_PATH="/Users/liqi/Library/Android/sdk/platforms"
JAR_PATH="target/androlog-0.1-jar-with-dependencies.jar"
DEVICE_ID=""
MONKEY_EVENTS=10000
MONKEY_THROTTLE=100
MAPPING_FILE="${ANDROLOG_MAPPING_FILE:-}"

# Print formatted message
print_msg() {
    local level=$1
    local message=$2
    case $level in
        "INFO")  echo -e "${BLUE}[INFO]${NC} $message" ;;
        "SUCCESS") echo -e "${GREEN}[✓]${NC} $message" ;;
        "ERROR") echo -e "${RED}[✗]${NC} $message" ;;
        "WARN") echo -e "${YELLOW}[!]${NC} $message" ;;
    esac
}

# Print section header
print_header() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Check if Frida is installed
check_frida() {
    if ! command -v frida &> /dev/null; then
        print_msg "ERROR" "Frida is not installed"
        print_msg "INFO" "Install with: pip install frida frida-tools"
        return 1
    fi
    return 0
}

# Check if Frida server is running on device
check_frida_server() {
    local running
    running=$(adb -s "$DEVICE_ID" shell "ps | grep frida-server >/dev/null; echo $?" | tr -d '\r' | tail -n1)
    if [ "$running" != "0" ]; then
        print_msg "WARN" "Frida server not running on device"
        if ! adb -s "$DEVICE_ID" shell "test -x /data/local/tmp/frida-server"; then
            print_msg "ERROR" "Missing /data/local/tmp/frida-server on device"
            print_msg "INFO" "Please push frida-server first, then rerun this script"
            return 1
        fi
        print_msg "INFO" "Starting frida-server..."
        adb -s "$DEVICE_ID" shell "/data/local/tmp/frida-server >/dev/null 2>&1 &"
        sleep 2
        
        running=$(adb -s "$DEVICE_ID" shell "ps | grep frida-server >/dev/null; echo $?" | tr -d '\r' | tail -n1)
        if [ "$running" != "0" ]; then
            print_msg "ERROR" "Failed to start frida-server"
            print_msg "INFO" "Manual steps:"
            print_msg "INFO" "1. wget https://github.com/frida/frida/releases/download/16.1.11/frida-server-16.1.11-android-arm64.xz"
            print_msg "INFO" "2. unxz frida-server-16.1.11-android-arm64.xz"
            print_msg "INFO" "3. adb push frida-server-16.1.11-android-arm64 /data/local/tmp/frida-server"
            print_msg "INFO" "4. adb shell chmod +x /data/local/tmp/frida-server"
            return 1
        fi
    fi
    print_msg "SUCCESS" "Frida server is running"
    return 0
}

# Check if emulator is connected
check_emulator() {
    if [ -z "$DEVICE_ID" ]; then
        print_msg "ERROR" "No device selected"
        return 1
    fi
    if ! adb -s "$DEVICE_ID" get-state >/dev/null 2>&1; then
        print_msg "ERROR" "Emulator $DEVICE_ID is not running"
        return 1
    fi
    return 0
}

resolve_device_id() {
    local requested_device=$1
    if [ -n "$requested_device" ]; then
        DEVICE_ID="$requested_device"
        return 0
    fi

    if [ -n "$ANDROLOG_DEVICE_ID" ]; then
        DEVICE_ID="$ANDROLOG_DEVICE_ID"
        return 0
    fi

    local devices
    devices=$(adb devices 2>/dev/null | awk '/\tdevice$/ {print $1}')
    local count
    count=$(echo "$devices" | sed '/^$/d' | wc -l | xargs)

    if [ "$count" -eq 1 ]; then
        DEVICE_ID=$(echo "$devices" | sed '/^$/d' | head -1)
        return 0
    fi

    if [ "$count" -eq 0 ]; then
        print_msg "WARN" "No online emulator/device found"
        local avds
        avds=$(emulator -list-avds 2>/dev/null | sed '/^$/d')
        local avd_count
        avd_count=$(echo "$avds" | wc -l | xargs)

        if [ "$avd_count" -eq 0 ]; then
            print_msg "ERROR" "No AVD found. Create an emulator first."
            return 1
        fi

        print_msg "INFO" "Select an emulator to start:"
        local idx=1
        while IFS= read -r avd; do
            echo "  [$idx] $avd"
            idx=$((idx + 1))
        done <<< "$avds"

        local selected_index
        read -r -p "Choose AVD number: " selected_index < /dev/tty 2>/dev/null || read -r -p "Choose AVD number: " selected_index
        if ! [[ "$selected_index" =~ ^[0-9]+$ ]] || [ "$selected_index" -lt 1 ] || [ "$selected_index" -gt "$avd_count" ]; then
            print_msg "ERROR" "Invalid selection"
            return 1
        fi

        local selected_avd
        selected_avd=$(echo "$avds" | sed -n "${selected_index}p")
        local start_port="5560"

        print_msg "INFO" "Starting emulator: $selected_avd (port $start_port)"
        emulator -avd "$selected_avd" -no-snapshot -writable-system -port "$start_port" >/tmp/androlog_emulator.log 2>&1 &

        local expected_device="emulator-$start_port"
        local waited=0
        while [ $waited -lt 90 ]; do
            if adb -s "$expected_device" get-state >/dev/null 2>&1; then
                DEVICE_ID="$expected_device"
                print_msg "SUCCESS" "Emulator is ready: $DEVICE_ID"
                return 0
            fi
            sleep 2
            waited=$((waited + 2))
        done

        # Fallback: pick first available device if expected id did not come up in time
        devices=$(adb devices 2>/dev/null | awk '/\tdevice$/ {print $1}' | sed '/^$/d')
        if [ -n "$devices" ]; then
            DEVICE_ID=$(echo "$devices" | head -1)
            print_msg "WARN" "Using available device: $DEVICE_ID"
            return 0
        fi

        print_msg "ERROR" "Timed out waiting for emulator startup"
        return 1
    fi

    print_msg "INFO" "Multiple devices detected. Select one:"
    local idx=1
    while IFS= read -r dev; do
        echo "  [$idx] $dev"
        idx=$((idx + 1))
    done <<< "$(echo "$devices" | sed '/^$/d')"

    local selected_index
    read -r -p "Choose device number: " selected_index < /dev/tty 2>/dev/null || read -r -p "Choose device number: " selected_index
    if ! [[ "$selected_index" =~ ^[0-9]+$ ]] || [ "$selected_index" -lt 1 ] || [ "$selected_index" -gt "$count" ]; then
        print_msg "ERROR" "Invalid selection"
        return 1
    fi

    DEVICE_ID=$(echo "$devices" | sed '/^$/d' | sed -n "${selected_index}p")
    return 0
}

suggest_emulator_start() {
    local avd_name
    avd_name=$(emulator -list-avds 2>/dev/null | head -1)
    if [ -n "$avd_name" ]; then
        print_msg "INFO" "Start one with: emulator -avd $avd_name -no-snapshot -writable-system -port 5560"
    else
        print_msg "INFO" "No AVD found. Create one first, then run: emulator -avd <YOUR_AVD_NAME> -no-snapshot -writable-system -port 5560"
    fi
}

# Ensure device supports root (required for Frida spawn mode on non-debuggable apps)
# Frida uses spawn mode (-f) which requires:
# - adb root capability (for SELinux relaxation and process attachment)
# - OR debuggable app (rooted in AndroidManifest.xml)
# Most production builds lack both, so root check is mandatory for Frida injection
check_root_capability() {
    local out
    out=$(adb -s "$DEVICE_ID" root 2>&1 || true)
    if echo "$out" | grep -qi "cannot run as root"; then
        print_msg "ERROR" "Device $DEVICE_ID cannot adb root (likely Play Store image)"
        print_msg "INFO" "Solutions:"
        print_msg "INFO" "  1. Use Google APIs AVD (not Play Store)"
        print_msg "INFO" "  2. After starting emulator, run:"
        print_msg "INFO" "     adb root"
        print_msg "INFO" "     adb disable-verity"
        print_msg "INFO" "     adb reboot"
        print_msg "INFO" "     sleep 30  # wait for reboot"
        return 1
    fi
    sleep 1
    return 0
}

# Get APK package name
get_package_name() {
    local apk_path=$1
    aapt dump badging "$apk_path" 2>/dev/null | grep "^package:" | sed "s/package: name='\([^']*\)'.*/\1/"
}

# Uninstall package if exists
uninstall_if_exists() {
    local package_name=$1
    if adb -s "$DEVICE_ID" shell pm list packages | grep -q "package:$package_name"; then
        print_msg "INFO" "Uninstalling existing package: $package_name"
        adb -s "$DEVICE_ID" uninstall "$package_name" >/dev/null 2>&1 || true
    fi
}

# Process APK with Frida
process_apk() {
    local apk_path=$1
    local package_name=$2
    local output_dir=$3
    local log_tag=$4
    local mode=$5
    
    local apk_name=$(basename "$apk_path")
    
    print_header "Frida Coverage Testing: $apk_name"
    
    print_msg "INFO" "Package: $package_name"
    print_msg "INFO" "Tag: $log_tag"
    print_msg "INFO" "Mode: $mode"
    print_msg "INFO" "Output: $output_dir"
    
    mkdir -p "$output_dir"
    
    local log_count=0

    if [ "$mode" = "static" ]; then
        print_msg "INFO" "[Step 1/1] Running static analysis only (no monkey/UI)..."
        cat > "$output_dir/test_log.txt" <<'EOF'
[STATIC MODE] No runtime test was executed.
[STATIC MODE] This file is intentionally not populated with runtime logs.
EOF
    else
        # Step 1: Check Frida
        print_msg "INFO" "[Step 1/6] Checking Frida installation..."
        if ! check_frida; then
            return 1
        fi
        print_msg "SUCCESS" "Frida is installed"

        # Step 2: Check Frida server
        print_msg "INFO" "[Step 2/6] Checking Frida server..."
        if ! check_frida_server; then
            return 1
        fi

        # Step 3: Uninstall old version
        print_msg "INFO" "[Step 3/6] Cleaning old installation..."
        uninstall_if_exists "$package_name"

        # Step 4: Run Frida instrumentation (just copies APK)
        print_msg "INFO" "[Step 4/6] Setting up (copying original APK)..."
        if ! ANDROLOG_DEVICE_ID="$DEVICE_ID" java -jar "$JAR_PATH" \
            -p "$PLATFORMS_PATH" \
            -a "$apk_path" \
            -pkg "$package_name" \
            -o "$output_dir" \
            -c -m -s -b \
            -l "$log_tag" \
            -frida > "$output_dir/frida.log" 2>&1; then
            print_msg "WARN" "Frida instrumentation skipped (using manual setup)"
        fi

        # Find APK to install
        local apk_to_install=$(find "$output_dir" -name "*.apk" | head -1)
        if [ -z "$apk_to_install" ]; then
            apk_to_install="$apk_path"
        fi

        # Step 5: Install APK
        print_msg "INFO" "[Step 5/6] Installing APK..."
        if ! adb -s "$DEVICE_ID" install -r "$apk_to_install" > "$output_dir/install.log" 2>&1; then
            print_msg "ERROR" "Installation failed"
            tail -20 "$output_dir/install.log"
            return 1
        fi
        print_msg "SUCCESS" "Installation completed"

        # Step 6: Runtime test (manual or monkey)
        adb -s "$DEVICE_ID" logcat -c

        # Start Frida runtime injection using spawn mode (inject at app launch)
        local agent_path="$output_dir/frida-agent.js"
        local frida_pid=""
        adb -s "$DEVICE_ID" shell am force-stop "$package_name" 2>/dev/null || true
        sleep 1

        if [ "$mode" = "manual" ]; then
            print_msg "INFO" "[Step 6/6] Manual mode: Launching app with Frida injection..."
            
            # Use Frida spawn mode to inject at app startup
            if [ -f "$agent_path" ]; then
                print_msg "INFO" "Attaching Frida agent at app launch..."
                if frida -D "$DEVICE_ID" -f "$package_name" -l "$agent_path" -q -t inf > "$output_dir/frida_runtime.log" 2>&1 &
                then
                    frida_pid=$!
                    sleep 5
                    
                    # Check if frida process succeeded
                    if ! ps -p "$frida_pid" >/dev/null 2>&1; then
                        print_msg "WARN" "Frida injection failed. Check log:"
                        cat "$output_dir/frida_runtime.log" | tail -5
                        print_msg "INFO" "This usually means:"
                        print_msg "INFO" "  1. Device needs root: adb root && adb disable-verity && adb reboot"
                        print_msg "INFO" "  2. Or app is not debuggable and device cannot be rooted"
                        frida_pid=""
                    else
                        print_msg "SUCCESS" "Frida injection active"
                    fi
                else
                    print_msg "WARN" "Frida spawn command failed"
                    cat "$output_dir/frida_runtime.log"
                    frida_pid=""
                fi
            else
                print_msg "WARN" "Frida agent not found, launching without injection"
                launcher_activity=$(adb -s "$DEVICE_ID" shell cmd package resolve-activity --brief "$package_name" | tail -1 | tr -d '\r\n')
                if [ -n "$launcher_activity" ]; then
                    adb -s "$DEVICE_ID" shell am start -n "$launcher_activity" >/dev/null 2>&1 || true
                fi
            fi
            
            echo -e "${YELLOW}Manual test is active. Interact on emulator now, then press ENTER here to stop.${NC}"
            read -r -p "Press ENTER when done testing: " _ < /dev/tty 2>/dev/null || read -r -p "Press ENTER when done testing: " _
            print_msg "SUCCESS" "Manual test completed"
        else
            print_msg "INFO" "[Step 6/6] Running monkey test ($MONKEY_EVENTS events)..."
            echo -e "${YELLOW}This may take a few minutes...${NC}"

            # For monkey mode, also use spawn mode with Frida injection
            if [ -f "$agent_path" ]; then
                print_msg "INFO" "Attaching Frida agent at app launch..."
                if frida -D "$DEVICE_ID" -f "$package_name" -l "$agent_path" -q -t inf > "$output_dir/frida_runtime.log" 2>&1 &
                then
                    frida_pid=$!
                    sleep 3
                    
                    # Check if frida process succeeded
                    if ! ps -p "$frida_pid" >/dev/null 2>&1; then
                        print_msg "WARN" "Frida injection failed:"
                        cat "$output_dir/frida_runtime.log" | tail -3
                        frida_pid=""
                    fi
                else
                    print_msg "WARN" "Frida spawn command failed"
                    frida_pid=""
                fi
            else
                print_msg "WARN" "Frida agent not found, launching without injection"
                launcher_activity=$(adb -s "$DEVICE_ID" shell cmd package resolve-activity --brief "$package_name" | tail -1 | tr -d '\r\n')
                if [ -n "$launcher_activity" ]; then
                    adb -s "$DEVICE_ID" shell am start -n "$launcher_activity" >/dev/null 2>&1 || true
                fi
            fi

            adb -s "$DEVICE_ID" shell monkey \
                -p "$package_name" \
                --throttle "$MONKEY_THROTTLE" \
                --ignore-crashes \
                --ignore-timeouts \
                --ignore-security-exceptions \
                -v "$MONKEY_EVENTS" > "$output_dir/monkey.log" 2>&1 &

            local monkey_pid=$!
            wait $monkey_pid 2>/dev/null || true
            print_msg "SUCCESS" "Monkey test completed"
        fi

        # Stop Frida process if running
        if [ -n "$frida_pid" ]; then
            kill "$frida_pid" >/dev/null 2>&1 || true
            sleep 1
        fi

        sleep 2

        # Collect logs
        print_msg "INFO" "Collecting coverage logs..."
        adb -s "$DEVICE_ID" logcat -d | grep "$log_tag" > "$output_dir/test_log.txt" || true
        log_count=$(wc -l < "$output_dir/test_log.txt" | xargs)
        print_msg "SUCCESS" "Collected $log_count log entries"

        if [ "$log_count" -eq 0 ]; then
            print_msg "WARN" "No logs collected. App may not have executed."
        fi
    fi
    
    # Generate coverage report
    print_msg "INFO" "Generating coverage report..."
    local mapping_args=()
    if [ -n "$MAPPING_FILE" ] && [ -f "$MAPPING_FILE" ]; then
        print_msg "INFO" "Using mapping file: $MAPPING_FILE"
        mapping_args=(-mf "$MAPPING_FILE")
    fi

    if java -jar "$JAR_PATH" \
        -p "$PLATFORMS_PATH" \
        -a "$apk_path" \
        -hy -nb \
        -c -m -s -b \
        -l "$log_tag" \
        -pa "$output_dir/test_log.txt" \
        "${mapping_args[@]}" \
        -j "$output_dir/coverage_output.json" > "$output_dir/coverage.log" 2>&1; then
        print_msg "SUCCESS" "Coverage report generated"
    else
        print_msg "ERROR" "Coverage generation failed"
        return 1
    fi
    
    # Parse results
    local method_cov=$(cat "$output_dir/coverage_output.json" | jq -r '.coverageSummary.methods.percent' 2>/dev/null || echo "0.0%")
    local branch_cov=$(cat "$output_dir/coverage_output.json" | jq -r '.coverageSummary.branches.percent' 2>/dev/null || echo "0.0%")
    
    print_header "Results Summary"
    echo -e "${GREEN}✓ Package:${NC} $package_name"
    echo -e "${GREEN}✓ Log Entries:${NC} $log_count"
    echo -e "${GREEN}✓ Method Coverage:${NC} $method_cov"
    echo -e "${GREEN}✓ Branch Coverage:${NC} $branch_cov"
    echo -e "${GREEN}✓ Output Directory:${NC} $output_dir"
    if [ "$mode" = "static" ]; then
        print_msg "INFO" "Static mode note: no runtime test/monkey executed, runtime coverage remains 0."
    fi
    echo ""
    
    # Cleanup
    if [ "$mode" = "monkey" ]; then
        print_msg "INFO" "Uninstalling test app..."
        uninstall_if_exists "$package_name"
    fi
    
    print_msg "SUCCESS" "Test completed successfully!"
    return 0
}

# Main execution
main() {
    # Get arguments with defaults
    if [ $# -lt 2 ]; then
        print_header "Frida Coverage Testing"
        echo ""
        echo "Usage: $0 <apk_path> <package_name> [output_dir] [log_tag] [mode] [device_id]"
        echo ""
        echo "Example:"
        echo "  $0 fse-dataset/newpipe.apk org.schabi.newpipe"
        echo "  $0 /path/to/app.apk com.example.app output/my_test FRIDA_TEST static"
        echo "  $0 /path/to/app.apk com.example.app output/my_test FRIDA_TEST monkey"
        echo "  $0 /path/to/app.apk com.example.app output/my_test FRIDA_TEST manual"
        echo "  $0 /path/to/app.apk com.example.app output/my_test FRIDA_TEST monkey emulator-5560"
        echo ""
        echo "Modes:"
        echo "  static (default): pure static analysis, no device/UI"
        echo "  manual: run Frida + manual UI testing (press ENTER to stop)"
        echo "  monkey: run Frida + monkey runtime test"
        echo ""
        exit 1
    fi
    
    local apk_path=$1
    local package_name=$2
    local output_dir="${3:-output/frida_coverage_test}"
    local log_tag="${4:-FRIDA_COVERAGE}"
    local mode="${5:-static}"
    local requested_device="${6:-}"

    if [ "$mode" != "static" ] && [ "$mode" != "manual" ] && [ "$mode" != "monkey" ]; then
        print_msg "ERROR" "Invalid mode: $mode (use: static, manual, or monkey)"
        exit 1
    fi

    # Check prerequisites
    print_msg "INFO" "Checking prerequisites..."

    if [ ! -f "$JAR_PATH" ]; then
        print_msg "ERROR" "JAR not found: $JAR_PATH"
        exit 1
    fi

    if [ "$mode" = "monkey" ] || [ "$mode" = "manual" ]; then
        if ! resolve_device_id "$requested_device"; then
            exit 1
        fi
        print_msg "INFO" "Using device: $DEVICE_ID"
        if ! check_emulator; then
            print_msg "ERROR" "Please start the emulator first"
            suggest_emulator_start
            exit 1
        fi

        # Both manual and monkey modes require root for Frida spawn mode
        if ! check_root_capability; then
            print_msg "ERROR" "Root access required for Frida injection (both manual and monkey modes)"
            print_msg "INFO" "Fix: Start emulator with root-capable build, then run:"
            print_msg "INFO" "  adb root && adb disable-verity && adb reboot"
            exit 1
        fi
    fi

    print_msg "SUCCESS" "Prerequisites check passed"
    
    # Validate APK
    if [ ! -f "$apk_path" ]; then
        print_msg "ERROR" "APK not found: $apk_path"
        exit 1
    fi
    
    # Process APK
    process_apk "$apk_path" "$package_name" "$output_dir" "$log_tag" "$mode"
}

# Run main function
main "$@"
