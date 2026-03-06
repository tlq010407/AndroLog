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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        System.out.printf("%s v%s started on %s\n%n", Constants.TOOL_NAME, Constants.VERSION, new Date());

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

        boolean includeLibraries = !CommandLineOptions.v().hasOption("n");

        String logIdentifier = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
        String outputApk = Optional.ofNullable(options.getOptionValue("output")).orElse(TmpFolder.v().get());
        String outputJson = Optional.ofNullable(options.getOptionValue("json")).orElse(TmpFolder.v().get());

        // Explicit Frida mode
        if (CommandLineOptions.v().hasOption("frida")) {
            Writer.v().pinfo("Frida mode enabled via --frida flag");
            handleFridaInstrumentation();
            return;
        }

        // Auto-detect instrumentation mode
        InstrumentationMode mode = InstrumentationMode.SOOT;
        if (CommandLineOptions.v().hasOption("ad")) {
            String apkPath = CommandLineOptions.v().getOptionValue("apk");
            mode = CompatibilityDetector.detectMode(apkPath);
            Writer.v().pinfo("Auto-detected mode: " + CompatibilityDetector.getDescription(mode));
            
            // Force Frida mode if detected
            if (mode == InstrumentationMode.FRIDA) {
                Writer.v().pinfo("This app requires Frida instrumentation (Kotlin+R8 detected)");
                handleFridaInstrumentation();
                return;
            }
        }

        Writer.v().pinfo("Setting up environment...");
        SootUtils su = new SootUtils();
        su.setupSootWithOutput(CommandLineOptions.v().getOptionValue("platforms"), CommandLineOptions.v().getOptionValue("apk"), outputApk, true);
        Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
        applyThreadOption();
        Writer.v().psuccess("Done.");

        Path path = Paths.get(CommandLineOptions.v().getOptionValue("apk"));
        String fileName = path.getFileName().toString();

        String packageName = null;
        if (CommandLineOptions.v().hasOption("pkg")) {
            packageName = CommandLineOptions.v().getOptionValue("package");
        }

        if (CommandLineOptions.v().hasOption("pa") || CommandLineOptions.v().hasOption("pam")) {
            Writer.v().pinfo("Generating Code Coverage Report...");
            String logFilePath = "";
            if (CommandLineOptions.v().hasOption("pa")) {
                logFilePath = CommandLineOptions.v().getOptionValue("parse");
            } else if (CommandLineOptions.v().hasOption("pam")) {
                logFilePath = CommandLineOptions.v().getOptionValue("parse-per-minute");
            }
            SummaryBuilder summaryBuilder = SummaryBuilder.v();
            summaryBuilder.setSootUtils(su);
            boolean noBodiesMode = CommandLineOptions.v().hasOption("nb") || CommandLineOptions.v().hasOption("hy");
            summaryBuilder.build(includeLibraries, noBodiesMode);

            if (CommandLineOptions.v().hasOption("b")) {
                String explicitCfgPath = CommandLineOptions.v().getOptionValue("cfg-output");
                String cfgOutputPath = resolveCfgOutputPath(explicitCfgPath, outputJson, logFilePath);
                try {
                    CfgExporter cfgExporter = new CfgExporter(includeLibraries, packageName);
                    cfgExporter.export(cfgOutputPath);
                    Writer.v().pinfo("Processing CFG written to " + cfgOutputPath);
                } catch (Exception cfgError) {
                    Writer.v().perror("Failed to export processing CFG: " + cfgError.getMessage());
                }
            }

            String mappingPath = CommandLineOptions.v().hasOption("mf")
                    ? CommandLineOptions.v().getOptionValue("mapping-file")
                    : null;
            LogParser lp = new LogParser(logIdentifier, summaryBuilder, mappingPath);
            lp.parseLogs(logFilePath);

            SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

            SummaryStatistics stats = new SummaryStatistics();
            if (CommandLineOptions.v().hasOption("j")) {
                if (CommandLineOptions.v().hasOption("pa")) {
                    stats.compareSummariesToJson(summaryBuilder, summaryLogBuilder, outputJson);
                } else if (CommandLineOptions.v().hasOption("pam")) {
                    stats.compareSummariesPerMinuteToJson(summaryBuilder, summaryLogBuilder, outputJson);
                }
                Writer.v().psuccess("Done.");

                Writer.v().pinfo("The parsed logs are now available in " + outputJson);
            } else {
                if (CommandLineOptions.v().hasOption("pa")) {
                    stats.compareSummaries(summaryBuilder, summaryLogBuilder);
                } else if (CommandLineOptions.v().hasOption("pam")) {
                    stats.compareSummariesPerMinute(summaryBuilder, summaryLogBuilder);
                }
                Writer.v().psuccess("Done.");
            }
        } else {
            boolean noRewrite = CommandLineOptions.v().hasOption("nr");
            boolean hasInstrumentation = isInstrumentationRequested();

            if (noRewrite || !hasInstrumentation) {
                if (noRewrite) {
                    Writer.v().pinfo("No-rewrite mode enabled. Copying original APK to output.");
                } else {
                    Writer.v().pinfo("No instrumentation flags selected. Skipping rewrite and copying original APK to output.");
                }
                copyOriginalApkToOutput(CommandLineOptions.v().getOptionValue("apk"), outputApk);
                Writer.v().psuccess(String.format("APK copied to: %s", outputApk));
                return;
            }

            Writer.v().pinfo("Instrumentation in progress...");
            Logger.v().setTargetPackage(packageName);
            BranchLogger.v().setTargetPackage(packageName);
            if (CommandLineOptions.v().hasOption("mc")) {
                Logger.v().logAllMethodCalls(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("s")) {
                Logger.v().logAllStatements(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("b")) {
                BranchLogger.v().logAllBranches(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("m")) {
                Logger.v().logAllMethods(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("c")) {
                Logger.v().logAllClasses(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("cp")) {
                Logger.v().logActivities(logIdentifier, includeLibraries);
                Logger.v().logContentProviders(logIdentifier, includeLibraries);
                Logger.v().logServices(logIdentifier, includeLibraries);
                Logger.v().logBroadcastReceivers(logIdentifier, includeLibraries);
            }
            Logger.v().instrument();
            System.out.printf("%s v%s finished Instrumentation at %s\n%n", Constants.TOOL_NAME, Constants.VERSION, new Date());
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
        return CommandLineOptions.v().hasOption("mc")
                || CommandLineOptions.v().hasOption("s")
                || CommandLineOptions.v().hasOption("b")
                || CommandLineOptions.v().hasOption("m")
                || CommandLineOptions.v().hasOption("c")
                || CommandLineOptions.v().hasOption("cp");
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

    /**
     * Configures the number of threads used by the Soot framework based on the
     * command-line option provided by the user.
     * <p>
     * If the "--threads" (or "-t") option is specified and contains a valid positive
     * integer, this value is passed to {@code Options.v().set_num_threads()}.
     * Otherwise, Soot's default thread configuration is used.
     * <p>
     * Logs appropriate messages for valid, missing, or invalid input.
     * Exits the program if the input value is non-numeric or non-positive.
     */
    private static void applyThreadOption() {
        if (CommandLineOptions.v().hasOption("threads")) {
            try {
                int numThreads = Integer.parseInt(CommandLineOptions.v().getOptionValue("threads"));
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

    /**
     * Handle Frida-based instrumentation for Kotlin+R8 apps
     */
    private static void handleFridaInstrumentation() {
        try {
            String apkPath = CommandLineOptions.v().getOptionValue("apk");
            String packageName = Optional.ofNullable(CommandLineOptions.v().getOptionValue("package")).orElse(null);
            String logTag = Optional.ofNullable(CommandLineOptions.v().getOptionValue("log-identifier")).orElse("ANDROLOG");
            String outputDir = Optional.ofNullable(CommandLineOptions.v().getOptionValue("output")).orElse("/tmp/androlog_frida");
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
                return parent.resolve("processing.cfg").toString();
            }
        }

        if (logFilePath != null && !logFilePath.trim().isEmpty()) {
            Path logPath = Paths.get(logFilePath);
            Path parent = logPath.getParent();
            if (parent != null) {
                return parent.resolve("processing.cfg").toString();
            }
        }

        return "processing.cfg";
    }
}
