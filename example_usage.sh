#!/bin/bash

# Quick examples for using AndroLog coverage pipeline

# Example 1: Interactive mode (Manual testing)
# Wait for user interaction before collecting logs
./run_full_coverage_pipeline.sh \
    fse-dataset/Mood_Tracker.apk \
    moodtracker.selfcare.habittracker.mentalhealth \
    mood_test_1 \
    MOOD_TAG

# Example 2: Automated mode (Monkey testing for 30 seconds)
# Fully automated with monkey testing
./run_automated_coverage.sh \
    fse-dataset/Mood_Tracker.apk \
    moodtracker.selfcare.habittracker.mentalhealth \
    mood_auto_1 \
    30 \
    MOOD_AUTO

# Example 3: Short automated test (10 seconds)
./run_automated_coverage.sh \
    fse-dataset/omninote.apk \
    it.feio.android.omninotes \
    omni_quick \
    10 \
    OMNI_TEST

# Example 4: Process existing logs (if you already have logs)
# IMPORTANT: Must include -c -m -s -b flags AND -l log tag
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a fse-dataset/Mood_Tracker.apk \
    -l MOOD_FULL \
    -c -m -s -b \
    -pa fse-dataset/instrumented_apk/mood_full/mood_full_logs.txt \
    -j fse-dataset/instrumented_apk/mood_full/coverage_report.json

# Example 5: Safe mode for problematic apps (like NewPipe)
# Use -nr flag to skip rewrite and get original APK
java -jar target/androlog-0.1-jar-with-dependencies.jar \
    -p /Users/liqi/Library/Android/sdk/platforms \
    -a fse-dataset/newpipe.apk \
    -o fse-dataset/instrumented_apk/newpipe_safe \
    -nr \
    -l NEWPIPE
