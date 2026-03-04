<p align="center">
<img width="1200px" src="https://github.com/JordanSamhi/AndroLog/blob/main/data/androlog_logo.png">
</p> 

# AndroLog

Welcome to **AndroLog**, your simple solution to insert probes into Android apps with the goal to compute code coverage at runtime.

**AndroLog** offers, so far, several levels of granularity:
- Classes
- Methods
- Statements
- **Branches (if/switch)** ⭐ NEW
- Activities
- Services
- Broadcast Receivers
- Content Providers

## :zap: Quick Start

**Automated Coverage Pipeline** (Recommended):
```bash
./run_automated_coverage.sh your_app.apk com.your.package output_folder 30 MY_TAG
```

For detailed documentation, see [COVERAGE_QUICKSTART.md](COVERAGE_QUICKSTART.md) | [ENHANCEMENT_SUMMARY.md](ENHANCEMENT_SUMMARY.md)

## :rocket: Getting started

### :arrow_down: Downloading the tool

<pre>
git clone https://github.com/JordanSamhi/AndroLog.git
</pre>

### :wrench: Installing the tool

➡️ Before compiling AndroLog, make sure to set the paths to zipalign and apksigner in the config.properties file located in `src/main/resources/`

<pre>
cd AndroLog
mvn clean install
</pre>

### :computer: Using the tool

<pre>
java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar <i>options</i>
</pre>

Options:

* ```-a``` : The path to the APK to process.
* ```-p``` : The path to Android platforms folder.
* ```-l``` : The log identifier to use.
* ```-o``` : The output where to write the instrumented APK.
* ```-pa``` : Parsing runtime output logs.
* ```-pam``` : Parsing runtime output logs per minute.
* ```-j``` : The output where to write the parsed logs in JSON format.
* ```-c``` : Logging classes.
* ```-m``` : Logging methods.
* ```-s``` : Logging statements.
* ```-b``` : Logging branches (if/switch statements). ⭐ NEW
* ```-cp``` : Logging Android components (Activity, Service, BroadcastReceiver, ContentProvider).
* ```-mc``` : Log method calls (e.g., a()-->b()).
* ```-nr``` : No-rewrite mode - skip instrumentation and copy original APK. ⭐ NEW
* ```-n``` : If set, this flag tells AndroLog to not consider libraries in the process.
* ```-pkg``` : Sets the package name of classes to be exclusively instrumented.
* ```-t``` : Number of threads to use in Soot. ⭐ NEW

### :information_source: Examples

#### Full Automated Pipeline (Recommended)

<pre>
# Automated testing with monkey (30 seconds)
./run_automated_coverage.sh my_app.apk com.example.app output_folder 30 MY_TAG

# Interactive testing (manual interaction)
./run_full_coverage_pipeline.sh my_app.apk com.example.app output_folder MY_TAG
</pre>

#### Manual Instrumentation

<pre>
# Full instrumentation with all coverage types including branches
java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Android/platforms/ \
  -l MY_SUPER_LOG \
  -o ./output/ \
  -a my_app.apk \
  -c -m -s -b
</pre>

#### Computing Code Coverage from Logs

<pre>
# IMPORTANT: Must include same flags (-c -m -s -b) used during instrumentation
java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Android/platforms/ \
  -a my_app.apk \
  -l MY_SUPER_LOG \
  -c -m -s -b \
  -pa logs.txt \
  -j coverage_report.json
</pre>

#### Safe Mode for Incompatible Apps

<pre>
# For apps that fail with VerifyError (e.g., Kotlin + R8 optimized)
java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Android/platforms/ \
  -a problematic_app.apk \
  -o ./output/ \
  -nr
</pre>

## :hammer: Built With

* [Maven](https://maven.apache.org/) - Dependency Management
