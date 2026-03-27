package com.jordansamhi.androlog;

import com.jordansamhi.androlog.frida.CompatibilityDetector;
import com.jordansamhi.androlog.frida.CompatibilityDetector.InstrumentationMode;
import com.jordansamhi.androlog.frida.FridaInstrumentation;
import com.jordansamhi.androlog.utils.Constants;
import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.TmpFolder;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOption;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.instrumentation.Logger;
import com.jordansamhi.androspecter.printers.Writer;
import soot.options.Options;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        System.out.printf("%s v%s started on %s%n%n", Constants.TOOL_NAME, Constants.VERSION, new Date());

        CommandLineOptions options = CommandLineOptions.v();
        options.setAppName("AndroLog");
        options.addOption(new CommandLineOption("platforms", "p", "Platform file", true, true));
        options.addOption(new CommandLineOption("parse", "pa", "Parse log file", true, false));
        options.addOption(new CommandLineOption("parse-per-minute", "pam", "Parse log file per-minute", true, false));
        options.addOption(new CommandLineOption("output", "o", "Instrumented APK output", true, false));
        options.addOption(new CommandLineOption("json", "j", "Parsed logs JSON output", true, false));
        options.addOption(new CommandLineOption("cfg-output", "cfg", "Processing CFG output (.cfg)", true, false));
        options.addOption(new CommandLineOption("apk", "a", "Apk file", true, true));
        options.addOption(new CommandLineOption("log-identifier", "l", "Log identifier", true, false));
        options.addOption(new CommandLineOption("classes", "c", "Log classes", false, false));
        options.addOption(new CommandLineOption("methods", "m", "Log methods", false, false));
        options.addOption(new CommandLineOption("statements", "s", "Log statements", false, false));
        options.addOption(new CommandLineOption("branches", "b", "Log branches (if/switch)", false, false));
        options.addOption(new CommandLineOption("components", "cp", "Log Android components", false, false));
        options.addOption(new CommandLineOption("no-rewrite", "nr", "Skip Soot rewrite and copy original APK to output", false, false));
        options.addOption(new CommandLineOption("non-libraries", "n", "Whether to include libraries (by default: include libraries)", false, false));
        options.addOption(new CommandLineOption("package", "pkg", "Package name that will exclusively be instrumented", true, false));
        options.addOption(new CommandLineOption("method-calls", "mc", "Log method calls (e.g., a()-->b())", false, false));
        options.addOption(new CommandLineOption("threads", "t", "Number of threads to use in Soot", true, false));
        options.addOption(new CommandLineOption("auto-detect", "ad", "Auto-detect instrumentation mode (Soot vs Frida)", false, false));
        options.addOption(new CommandLineOption("frida", "fr", "Use Frida runtime instrumentation mode", false, false));
        options.addOption(new CommandLineOption("hybrid", "hy", "Hybrid mode: static denominator + runtime logs", false, false));
        options.addOption(new CommandLineOption("no-bodies", "nb", "Safe scan mode for Kotlin/R8 apps", false, false));
        options.addOption(new CommandLineOption("mapping-file", "mf", "R8/ProGuard mapping.txt path for name resolution", true, false));
        options.parseArgs(args);

        boolean includeLibraries = !options.hasOption("non-libraries");

        String logIdentifier = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
        String outputApk = Optional.ofNullable(options.getOptionValue("output")).orElse(TmpFolder.v().get());
        String outputJson = Optional.ofNullable(options.getOptionValue("json")).orElse(TmpFolder.v().get());

        if (options.hasOption("frida")) {
            Writer.v().pinfo("Frida mode enabled via --frida flag");
            handleFridaInstrumentation();
            return;
        }

        InstrumentationMode mode = InstrumentationMode.SOOT;
        if (options.hasOption("auto-detect")) {
            String apkPath = options.getOptionValue("apk");
            mode = CompatibilityDetector.detectMode(apkPath);
            Writer.v().pinfo("Auto-detected mode: " + CompatibilityDetector.getDescription(mode));

            if (mode == InstrumentationMode.FRIDA) {
                Writer.v().pinfo("This app requires Frida instrumentation (Kotlin+R8 detected)");
                handleFridaInstrumentation();
                return;
            }
        }

        Writer.v().pinfo("Setting up environment...");
        SootUtils su = new SootUtils();
        su.setupSootWithOutput(options.getOptionValue("platforms"), options.getOptionValue("apk"), outputApk, true);
        Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
        applyThreadOption();
        Writer.v().psuccess("Done.");

        Path path = Paths.get(options.getOptionValue("apk"));
        String fileName = path.getFileName().toString();

        String packageName = null;
        if (options.hasOption("package")) {
            packageName = options.getOptionValue("package");
        }

        if (options.hasOption("parse") || options.hasOption("parse-per-minute")) {
            Writer.v().pinfo("Generating Code Coverage Report...");

            String logFilePath;
            if (options.hasOption("parse")) {
                logFilePath = options.getOptionValue("parse");
            } else {
                logFilePath = options.getOptionValue("parse-per-minute");
            }

            SummaryBuilder summaryBuilder = SummaryBuilder.v();
            summaryBuilder.setSootUtils(su);
            boolean noBodiesMode = options.hasOption("no-bodies") || options.hasOption("hybrid");
            summaryBuilder.build(includeLibraries, noBodiesMode);

            if (options.hasOption("branches")) {
                String explicitCfgPath = options.getOptionValue("cfg-output");
                String cfgOutputPath = resolveCfgOutputPath(explicitCfgPath, outputJson, logFilePath);
                String featuresOutputPath = resolveFeaturesOutputPath(cfgOutputPath);
                boolean cfgExists = Files.exists(Paths.get(cfgOutputPath));
                boolean featuresExist = Files.exists(Paths.get(featuresOutputPath));
                boolean shouldExportCfg = explicitCfgPath != null || !cfgExists || !featuresExist;

                if (shouldExportCfg) {
                    try {
                        Writer.v().pinfo("Exporting CFG and static features...");
                        CfgExporter cfgExporter = new CfgExporter(includeLibraries, packageName);
                        cfgExporter.export(cfgOutputPath);
                        Writer.v().pinfo("Processing CFG written to " + cfgOutputPath);
                        Writer.v().pinfo("Static features written to " + featuresOutputPath);
                    } catch (Exception cfgError) {
                        Writer.v().perror("Failed to export processing CFG/static features: " + cfgError.getMessage());
                        cfgError.printStackTrace();
                    }
                } else {
                    Writer.v().pinfo("Reusing existing CFG and static features.");
                    Writer.v().pinfo("Processing CFG: " + cfgOutputPath);
                    Writer.v().pinfo("Static features: " + featuresOutputPath);
                }
            }

            String mappingPath = options.hasOption("mapping-file")
                    ? options.getOptionValue("mapping-file")
                    : null;

            if (mappingPath != null && !mappingPath.trim().isEmpty()) {
                Writer.v().pinfo("Using mapping file: " + mappingPath);
            }

            LogParser lp = new LogParser(logIdentifier, summaryBuilder, mappingPath);
            lp.parseLogs(logFilePath);

            SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();
            SummaryStatistics stats = new SummaryStatistics();

            if (options.hasOption("json")) {
                if (options.hasOption("parse")) {
                    stats.compareSummariesToJson(summaryBuilder, summaryLogBuilder, outputJson);
                } else {
                    stats.compareSummariesPerMinuteToJson(summaryBuilder, summaryLogBuilder, outputJson);
                }
                Writer.v().psuccess("Done.");
                Writer.v().pinfo("The parsed logs are now available in " + outputJson);
            } else {
                if (options.hasOption("parse")) {
                    stats.compareSummaries(summaryBuilder, summaryLogBuilder);
                } else {
                    stats.compareSummariesPerMinute(summaryBuilder, summaryLogBuilder);
                }
                Writer.v().psuccess("Done.");
            }
        } else {
            boolean noRewrite = options.hasOption("no-rewrite");
            boolean hasInstrumentation = isInstrumentationRequested();

            if (noRewrite || !hasInstrumentation) {
                if (noRewrite) {
                    Writer.v().pinfo("No-rewrite mode enabled. Copying original APK to output.");
                } else {
                    Writer.v().pinfo("No instrumentation flags selected. Skipping rewrite and copying original APK to output.");
                }
                copyOriginalApkToOutput(options.getOptionValue("apk"), outputApk);
                Writer.v().psuccess(String.format("APK copied to: %s", outputApk));
                return;
            }

            Writer.v().pinfo("Instrumentation in progress...");
            Logger.v().setTargetPackage(packageName);
            BranchLogger.v().setTargetPackage(packageName);

            if (options.hasOption("method-calls")) {
                Logger.v().logAllMethodCalls(logIdentifier, includeLibraries);
            }
            if (options.hasOption("statements")) {
                Logger.v().logAllStatements(logIdentifier, includeLibraries);
            }
            if (options.hasOption("branches")) {
                BranchLogger.v().logAllBranches(logIdentifier, includeLibraries);
            }
            if (options.hasOption("methods")) {
                Logger.v().logAllMethods(logIdentifier, includeLibraries);
            }
            if (options.hasOption("classes")) {
                Logger.v().logAllClasses(logIdentifier, includeLibraries);
            }
            if (options.hasOption("components")) {
                Logger.v().logActivities(logIdentifier, includeLibraries);
                Logger.v().logContentProviders(logIdentifier, includeLibraries);
                Logger.v().logServices(logIdentifier, includeLibraries);
                Logger.v().logBroadcastReceivers(logIdentifier, includeLibraries);
            }

            Logger.v().instrument();
            System.out.printf("%s v%s finished Instrumentation at %s%n%n", Constants.TOOL_NAME, Constants.VERSION, new Date());
            Writer.v().psuccess("Done.");
            Writer.v().pinfo("Exporting new apk...");
            Logger.v().exportNewApk(outputApk);
            Writer.v().psuccess(String.format("Apk written in: %s", outputApk));

            Writer.v().pinfo("Signing and aligning APK...");
            ApkPreparator ap = new ApkPreparator(String.format("%s/%s", outputApk, fileName));
            ap.prepareApk();
            Writer.v().psuccess("Done.");

            Writer.v().pinfo("The apk is now instrumented, install it and execute it to generate logs.");
        }
    }

    private static boolean isInstrumentationRequested() {
        CommandLineOptions options = CommandLineOptions.v();
        return options.hasOption("method-calls")
                || options.hasOption("statements")
                || options.hasOption("branches")
                || options.hasOption("methods")
                || options.hasOption("classes")
                || options.hasOption("components");
    }

    private static void copyOriginalApkToOutput(String sourceApkPath, String outputDir) {
        try {
            Path sourcePath = Paths.get(sourceApkPath);
            Path outputDirectoryPath = Paths.get(outputDir);
            Files.createDirectories(outputDirectoryPath);
            Path targetPath = outputDirectoryPath.resolve(sourcePath.getFileName());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Writer.v().perror("Problem while copying original APK: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void applyThreadOption() {
        CommandLineOptions options = CommandLineOptions.v();
        if (options.hasOption("threads")) {
            try {
                int numThreads = Integer.parseInt(options.getOptionValue("threads"));
                if (numThreads > 0) {
                    Options.v().set_num_threads(numThreads);
                    Writer.v().pinfo(String.format("Using %d threads for Soot processing", numThreads));
                } else {
                    Writer.v().perror("Invalid number of threads. Must be a positive integer.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                Writer.v().perror("Invalid format for thread count. Please provide a numeric value.");
                System.exit(1);
            }
        } else {
            Writer.v().pinfo("No thread count specified. Using Soot's default thread configuration.");
        }
    }

    private static void handleFridaInstrumentation() {
        try {
            CommandLineOptions options = CommandLineOptions.v();

            String apkPath = options.getOptionValue("apk");
            String packageName = Optional.ofNullable(options.getOptionValue("package")).orElse(null);
            String logTag = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
            String outputDir = Optional.ofNullable(options.getOptionValue("output")).orElse("/tmp/androlog_frida");
            Files.createDirectories(Paths.get(outputDir));

            String deviceId = detectDeviceId();
            FridaInstrumentation frida = new FridaInstrumentation(deviceId, apkPath, packageName, logTag, outputDir);

            Writer.v().pinfo("Initializing Frida instrumentation pipeline on device " + deviceId + "...");
            if (!frida.executeInstrumentationPipeline()) {
                Writer.v().perror("Frida instrumentation pipeline failed");
                System.exit(1);
            }

            Writer.v().psuccess("Frida instrumentation completed");
            Writer.v().pinfo("Next steps:");
            Writer.v().pinfo("1. Install APK: adb install -r <copied-apk-under-output-dir>");
            Writer.v().pinfo("2. Launch app and interact with UI");
            Writer.v().pinfo("3. Collect logs: adb logcat -d | grep '" + logTag + "' > logs.txt");
            Writer.v().pinfo("4. Generate report: java -jar ... -pa logs.txt -j report.json");

        } catch (Exception e) {
            Writer.v().perror("Frida instrumentation error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String detectDeviceId() {
        String envDeviceId = System.getenv("ANDROLOG_DEVICE_ID");
        if (envDeviceId != null && !envDeviceId.trim().isEmpty()) {
            return envDeviceId.trim();
        }

        try {
            Process process = new ProcessBuilder("adb", "devices").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("emulator-") && line.endsWith("\tdevice")) {
                        return line.split("\\t")[0];
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "emulator-5554";
    }

    private static String resolveCfgOutputPath(String explicitPath, String outputJsonPath, String logFilePath) {
        if (explicitPath != null && !explicitPath.trim().isEmpty()) {
            return explicitPath.trim();
        }

        if (outputJsonPath != null && !outputJsonPath.trim().isEmpty()) {
            Path outputJson = Paths.get(outputJsonPath);
            Path parent = outputJson.getParent();
            if (parent != null) {
                return parent.resolve("static_apk.cfg").toString();
            }
        }

        if (logFilePath != null && !logFilePath.trim().isEmpty()) {
            Path logPath = Paths.get(logFilePath);
            Path parent = logPath.getParent();
            if (parent != null) {
                return parent.resolve("static_apk.cfg").toString();
            }
        }

        return "static_apk.cfg";
    }

    private static String resolveFeaturesOutputPath(String cfgOutputPath) {
        Path cfgPath = Paths.get(cfgOutputPath);
        Path parent = cfgPath.getParent();

        if (parent != null) {
            return parent.resolve("static_features.jsonl").toString();
        }

        return "static_features.jsonl";
    }
}