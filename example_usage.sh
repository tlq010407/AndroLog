#!/bin/bash

# ============================================================
# AndroLog Coverage Pipeline Examples
# Supports:
#   - Static CFG export
#   - Static features extraction
#   - R8/ProGuard mapping resolution
# ============================================================


# ------------------------------------------------------------
# Example 1: Interactive mode (Manual testing)
# ------------------------------------------------------------
# Wait for user interaction before collecting logs
./run_full_coverage_pipeline.sh \
    fse-dataset/Mood_Tracker.apk \
    moodtracker.selfcare.habittracker.mentalhealth \
    mood_test_1 \
    MOOD_TAG


# ------------------------------------------------------------
# Example 2: Automated mode (Monkey testing for 30 seconds)
# ------------------------------------------------------------
./run_automated_coverage.sh \
    fse-dataset/Mood_Tracker.apk \
    moodtracker.selfcare.habittracker.mentalhealth \
    mood_auto_1 \
    30 \
    MOOD_AUTO


# ------------------------------------------------------------
# Example 3: Short automated test (10 seconds)
# ------------------------------------------------------------
./run_automated_coverage.sh \
    fse-dataset/omninote.apk \
    it.feio.android.omninotes \
    omni_quick \
    10 \
    OMNI_TEST


# ------------------------------------------------------------
# Example 4: Process existing logs + generate CFG
# ------------------------------------------------------------
# This will generate:
#
#   coverage_report.json
#   static_apk.cfg
#   static_features.jsonl
#
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a fse-dataset/Mood_Tracker.apk \
    -l MOOD_FULL \
    -c -m -s -b \
    -pa fse-dataset/instrumented_apk/mood_full/mood_full_logs.txt \
    -j fse-dataset/instrumented_apk/mood_full/coverage_report.json \
    -cfg fse-dataset/instrumented_apk/mood_full/static_apk.cfg


# ------------------------------------------------------------
# Example 5: Process logs WITH R8 mapping file
# ------------------------------------------------------------
# Useful for obfuscated apps
#
# mapping.txt is generated during APK build
#
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a fse-dataset/Mood_Tracker.apk \
    -l MOOD_FULL \
    -c -m -s -b \
    -pa fse-dataset/instrumented_apk/mood_full/mood_full_logs.txt \
    -j fse-dataset/instrumented_apk/mood_full/coverage_report.json \
    -cfg fse-dataset/instrumented_apk/mood_full/static_apk.cfg \
    -mf fse-dataset/instrumented_apk/mood_full/mapping.txt


# ------------------------------------------------------------
# Example 6: Safe mode for problematic apps (skip rewrite)
# ------------------------------------------------------------
# Some apps crash during Soot rewrite (e.g., NewPipe)
#
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a fse-dataset/newpipe.apk \
    -o fse-dataset/instrumented_apk/newpipe_safe \
    -nr \
    -l NEWPIPE


# ------------------------------------------------------------
# Example 7: Static CFG extraction ONLY (no logs required)
# ------------------------------------------------------------
# Useful for static analysis / LLM test generation
#
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a fse-dataset/Mood_Tracker.apk \
    -l STATIC_ONLY \
    -c -m -s -b \
    -cfg fse-dataset/static_apk.cfg


echo ""
echo "================================================"
echo "AndroLog examples completed"
echo ""
echo "Generated artifacts:"
echo "  coverage_report.json"
echo "  static_apk.cfg"
echo "  static_features.jsonl"
echo "================================================"