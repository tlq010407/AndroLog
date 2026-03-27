package com.jordansamhi.androlog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class LogParser {
    private static final String[] COVERAGE_KEYS = {
            "STATEMENT",
            "BRANCH",
            "METHOD",
            "CLASS",
            "NATIVE_METHOD",
            "NATIVE_INVOKE",
            "ACTIVITY",
            "SERVICE",
            "BROADCASTRECEIVER",
            "CONTENTPROVIDER"
    };

    private final String logIdentifier;
    private final SummaryBuilder summaryBuilder;
    private final SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();
    private final MappingResolver mappingResolver;

    private final Set<String> visitedStatements = new HashSet<>();
    private final Set<String> visitedMethods = new HashSet<>();
    private final Set<String> visitedClasses = new HashSet<>();
    private final Set<String> visitedActivities = new HashSet<>();
    private final Set<String> visitedServices = new HashSet<>();
    private final Set<String> visitedBroadcastReceivers = new HashSet<>();
    private final Set<String> visitedContentProviders = new HashSet<>();
    private final Set<String> visitedBranches = new HashSet<>();

    public LogParser(String logIdentifier, SummaryBuilder summaryBuilder) {
        this(logIdentifier, summaryBuilder, null);
    }

    public LogParser(String logIdentifier, SummaryBuilder summaryBuilder, String mappingFilePath) {
        this.logIdentifier = logIdentifier;
        this.summaryBuilder = summaryBuilder;
        this.mappingResolver = MappingResolver.fromFile(mappingFilePath);

        for (String component : this.summaryBuilder.getVisitedComponents()) {
            String logType = getType(component);
            if (logType != null) {
                switch (logType) {
                    case "statements":
                        int firstPipe = component.indexOf('|');
                        if (firstPipe > 0) {
                            visitedStatements.add(component.substring(logType.length(), firstPipe));
                        }
                        break;
                    case "methods":
                        visitedMethods.add(component.substring(logType.length()));
                        break;
                    case "classes":
                        visitedClasses.add(component.substring(logType.length()));
                        break;
                    case "activities":
                        visitedActivities.add(component.substring(logType.length()));
                        break;
                    case "services":
                        visitedServices.add(component.substring(logType.length()));
                        break;
                    case "broadcast-receivers":
                        visitedBroadcastReceivers.add(component.substring(logType.length()));
                        break;
                    case "content-providers":
                        visitedContentProviders.add(component.substring(logType.length()));
                        break;
                    case "branches":
                        visitedBranches.add(component.substring(logType.length()));
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static String getType(String component) {
        String[] logTypes = {
                "statements",
                "methods",
                "classes",
                "activities",
                "services",
                "broadcast-receivers",
                "content-providers",
                "branches"
        };

        for (String lt : logTypes) {
            if (component.startsWith(lt)) {
                return lt;
            }
        }
        return null;
    }

    public void parseLogs(String filePath) {
        try {
            Path logPath = Paths.get(filePath);
            byte[] raw = Files.readAllBytes(logPath);
            Charset charset = detectCharset(raw);
            String content = new String(raw, charset);

            String[] lines = content.split("\\R");
            for (String line : lines) {
                if (line.contains(logIdentifier) || containsCoverageMarker(line)) {
                    parseLine(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Charset detectCharset(byte[] raw) {
        if (raw.length >= 2) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
        }

        int zeroEven = 0;
        int zeroOdd = 0;
        int sample = Math.min(raw.length, 8192);

        for (int i = 0; i < sample; i++) {
            if (raw[i] == 0) {
                if ((i & 1) == 0) {
                    zeroEven++;
                } else {
                    zeroOdd++;
                }
            }
        }

        // Heuristic for UTF-16 without BOM:
        // - ASCII-heavy UTF-16LE has many zeros on odd byte positions.
        // - ASCII-heavy UTF-16BE has many zeros on even byte positions.
        if (zeroOdd > sample / 16 && zeroOdd > zeroEven * 2) {
            return StandardCharsets.UTF_16LE;
        }
        if (zeroEven > sample / 16 && zeroEven > zeroOdd * 2) {
            return StandardCharsets.UTF_16BE;
        }

        for (byte b : raw) {
            if (b == 0) {
                // Fallback when null-bytes exist but distribution is unclear.
                return StandardCharsets.UTF_16LE;
            }
        }

        return StandardCharsets.UTF_8;
    }

    private boolean containsCoverageMarker(String line) {
        for (String key : COVERAGE_KEYS) {
            if (line.contains(key + "=") || line.contains(key + ":")) {
                return true;
            }
        }
        return false;
    }

    private void parseLine(String line) {
        String logType = getLogType(line);
        if (logType == null) {
            return;
        }

        switch (logType) {
            case "STATEMENT":
                int logIndex = findPayloadStart(line, logType);
                int firstPipe = line.indexOf('|');
                if (logIndex >= 0 && firstPipe > logIndex) {
                    if (visitedStatements.contains(line.substring(logIndex, firstPipe))) {
                        summaryLogBuilder.incrementStatement(line);
                    }
                }
                break;

            case "BRANCH":
                logIndex = findPayloadStart(line, logType);
                if (logIndex < 0) {
                    break;
                }
                summaryLogBuilder.incrementBranch(line);
                break;

            case "METHOD":
                String methodFromLog = extractPayload(line, logType);
                if (methodFromLog == null) {
                    break;
                }
                matchAndIncrementMethod(methodFromLog, line);
                break;

            case "CLASS":
                String classFromLog = extractPayload(line, logType);
                if (classFromLog == null) {
                    break;
                }
                String mappedClassFromLog = mappingResolver != null
                        ? mappingResolver.mapClass(classFromLog)
                        : classFromLog;
                if (visitedClasses.contains(classFromLog) || visitedClasses.contains(mappedClassFromLog)) {
                    summaryLogBuilder.incrementClass(line);
                }
                break;

            case "NATIVE_METHOD":
                String nativeMethodFromLog = extractPayload(line, logType);
                if (nativeMethodFromLog == null) {
                    break;
                }
                summaryLogBuilder.incrementNativeSignalSeen();
                if (matchAndIncrementMethod(nativeMethodFromLog, line)) {
                    summaryLogBuilder.incrementNativeSignalMatched();
                }
                break;

            case "NATIVE_INVOKE":
                String nativeInvokeFromLog = extractPayload(line, logType);
                if (nativeInvokeFromLog == null) {
                    break;
                }
                summaryLogBuilder.incrementNativeSignalSeen();
                if (matchAndIncrementMethod(nativeInvokeFromLog, line)) {
                    summaryLogBuilder.incrementNativeSignalMatched();
                }
                break;

            case "ACTIVITY":
                String activityFromLog = extractPayload(line, logType);
                if (activityFromLog != null && visitedActivities.contains(activityFromLog)) {
                    summaryLogBuilder.incrementActivity(line);
                }
                break;

            case "SERVICE":
                String serviceFromLog = extractPayload(line, logType);
                if (serviceFromLog != null && visitedServices.contains(serviceFromLog)) {
                    summaryLogBuilder.incrementService(line);
                }
                break;

            case "BROADCASTRECEIVER":
                String receiverFromLog = extractPayload(line, logType);
                if (receiverFromLog != null && visitedBroadcastReceivers.contains(receiverFromLog)) {
                    summaryLogBuilder.incrementBroadcastReceiver(line);
                }
                break;

            case "CONTENTPROVIDER":
                String providerFromLog = extractPayload(line, logType);
                if (providerFromLog != null && visitedContentProviders.contains(providerFromLog)) {
                    summaryLogBuilder.incrementContentProvider(line);
                }
                break;

            default:
                break;
        }
    }

    private boolean matchAndIncrementMethod(String runtimeMethodLike, String originalLineForCounting) {
        if (runtimeMethodLike == null || runtimeMethodLike.isEmpty()) {
            return false;
        }

        String mappedRuntime = mappingResolver != null
                ? mappingResolver.mapMethodSignatureLike(runtimeMethodLike)
                : runtimeMethodLike;

        for (String visited : visitedMethods) {
            if (visited.contains(runtimeMethodLike) || visited.contains(mappedRuntime)) {
                summaryLogBuilder.incrementMethod(originalLineForCounting);
                return true;
            }
        }

        String[] candidates = {
                runtimeMethodLike,
                mappedRuntime,
                runtimeMethodLike.replace("::", "."),
                mappedRuntime.replace("::", "."),
                runtimeMethodLike.replace('_', '.'),
                mappedRuntime.replace('_', '.')
        };

        for (String candidate : candidates) {
            String[] parts = candidate.split("\\.");
            if (parts.length < 2) {
                continue;
            }

            String methodName = parts[parts.length - 1].replace("()", "");
            String classTail = parts[parts.length - 2];

            for (String visited : visitedMethods) {
                if ((visited.endsWith(methodName + "(") || visited.contains(methodName + "("))
                        && visited.contains(classTail)) {
                    summaryLogBuilder.incrementMethod(originalLineForCounting);
                    return true;
                }
            }
        }

        return false;
    }

    private String getLogType(String line) {
        for (String key : COVERAGE_KEYS) {
            if (line.contains(key + "=") || line.contains(key + ":")) {
                return key;
            }
        }
        return null;
    }

    private int findPayloadStart(String line, String logType) {
        int eqIndex = line.indexOf(logType + "=");
        if (eqIndex >= 0) {
            return eqIndex;
        }

        int colonIndex = line.indexOf(logType + ":");
        if (colonIndex >= 0) {
            return colonIndex;
        }

        return -1;
    }

    private String extractPayload(String line, String logType) {
        int markerIndex = line.indexOf(logType + "=");
        int separatorLength = 1;

        if (markerIndex < 0) {
            markerIndex = line.indexOf(logType + ":");
        }
        if (markerIndex < 0) {
            return null;
        }

        int start = markerIndex + logType.length() + separatorLength;
        if (start >= line.length()) {
            return "";
        }

        return line.substring(start).trim();
    }

    private boolean branchBelongsToKnownMethod(String branchPayload) {
        int firstPipe = branchPayload.indexOf('|');
        if (firstPipe < 0) {
            return false;
        }
        String methodPrefix = branchPayload.substring(0, firstPipe + 1);
        for (String knownBranch : visitedBranches) {
            if (knownBranch.startsWith(methodPrefix)) {
                return true;
            }
        }
        return false;
    }
}