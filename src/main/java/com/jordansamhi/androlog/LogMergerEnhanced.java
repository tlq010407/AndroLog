package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enhanced LogMerger with burst detection, causal mapping, and app/framework classification.
 * 
 * Improvements over basic merge:
 * - Unified timestamp alignment using millisecond precision
 * - Branch burst detection (groups consecutive branches)
 * - Causal mapping (assigns bursts to preceding UI actions)
 * - App/framework/platform classification
 * - Multiple output formats
 * 
 * @author Liqi Tang
 */
public class LogMergerEnhanced {
    
    // ===== Event Model =====
    
    private static class Event implements Comparable<Event> {
        long ts;              // timestamp in milliseconds
        String session;       // TestRun_001, default, etc.
        EventType type;       // UI or BRANCH
        String subtype;       // SCREEN, CLICK, INPUT, BRANCH_HIT
        String activity;      // Activity name (UI events)
        String target;        // UI element ID or branch location
        String method;        // Branch method signature
        String branchId;      // Branch unique ID
        String edge;          // TRUE/FALSE for if, CASE=X for switch
        String condition;     // Branch condition expression
        CodeCategory category; // APP, FRAMEWORK, PLATFORM
        long threadId;        // Thread ID
        String raw;           // Original log line
        
        @Override
        public int compareTo(Event other) {
            int cmp = Long.compare(this.ts, other.ts);
            if (cmp != 0) return cmp;
            // UI events before branches at same timestamp
            if (this.type == EventType.UI && other.type == EventType.BRANCH) return -1;
            if (this.type == EventType.BRANCH && other.type == EventType.UI) return 1;
            return 0;
        }
    }
    
    private enum EventType {
        UI, BRANCH
    }
    
    private enum CodeCategory {
        APP,         // Application code (com.yourapp.*)
        FRAMEWORK,   // UI/Support libraries (androidx.*, com.google.android.material.*)
        PLATFORM     // Android platform (android.*, java.*, kotlin.*)
    }
    
    // ===== Burst Model =====
    
    private static class BranchBurst {
        long startTs;
        long endTs;
        List<Event> branches = new ArrayList<>();
        String session;
        Event causativeAction;  // Nearest preceding UI action
        long deltaMs;           // Time from UI action to burst start
        
        int size() {
            return branches.size();
        }
        
        Map<String, Long> getTopMethods(int limit) {
            return branches.stream()
                .filter(e -> e.method != null)
                .collect(Collectors.groupingBy(e -> e.method, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                    (e1, e2) -> e1, LinkedHashMap::new));
        }
        
        long countByCategory(CodeCategory cat) {
            return branches.stream().filter(e -> e.category == cat).count();
        }
    }

    private static class UIHotspot {
        String action;
        int branchCount;
        int cfgEdgeCount;
        int methodCount;
        Map<String, Integer> methodFreq = new HashMap<>();
    }

    private static class UIActionStats {
        String action;
        int branchCount;
        int cfgEdges;
        boolean effective;
        long minDeltaMs = Long.MAX_VALUE;
    }
    
    // ===== Configuration =====
    
    private static final long BURST_GAP_MS = 50;  // Max gap between branches in same burst
    private static final long CAUSALITY_WINDOW_MS = 2000;  // Max time from UI to branch
    
    // ===== Data =====
    
    private List<Event> events = new ArrayList<>();
    private List<BranchBurst> bursts = new ArrayList<>();
    private Map<String, List<Event>> eventsBySession = new LinkedHashMap<>();
    
    // ===== Patterns =====
    
    private Pattern logcatTsPattern = Pattern.compile("\\b(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\b");
    
    // ===== Public API =====
    
    public void parseBranchLog(String path) throws IOException {
        int total = 0, parsed = 0;
        StringBuilder pending = null;
        try (BufferedReader reader = openReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;

                if (line.contains("BRANCH=")) {
                    if (pending != null) {
                        Event prev = parseBranchLine(pending.toString());
                        if (prev != null) {
                            events.add(prev);
                            parsed++;
                        }
                    }
                    pending = new StringBuilder(line.trim());
                    continue;
                }

                // logcat wrapping fix: continuation lines belong to previous BRANCH record
                if (pending != null) {
                    if (looksLikeLogcatStart(line)) {
                        Event prev = parseBranchLine(pending.toString());
                        if (prev != null) {
                            events.add(prev);
                            parsed++;
                        }
                        pending = null;
                    } else {
                        pending.append(line.trim());
                    }
                }
            }
        }

        if (pending != null) {
            Event prev = parseBranchLine(pending.toString());
            if (prev != null) {
                events.add(prev);
                parsed++;
            }
        }

        System.out.printf("  Parsed %d / %d branch lines\n", parsed, total);
    }
    
    public void parseUiLog(String path) throws IOException {
        int total = 0, parsed = 0;
        StringBuilder pending = null;
        try (BufferedReader reader = openReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;

                if (line.contains("UIACTION|")) {
                    if (pending != null) {
                        Event prev = parseUiLine(pending.toString());
                        if (prev != null) {
                            events.add(prev);
                            parsed++;
                        }
                    }
                    pending = new StringBuilder(line.trim());
                    continue;
                }

                // line wrapping fix for cleaned UI logs
                if (pending != null) {
                    if (looksLikeLogcatStart(line)) {
                        Event prev = parseUiLine(pending.toString());
                        if (prev != null) {
                            events.add(prev);
                            parsed++;
                        }
                        pending = null;
                    } else {
                        pending.append(line.trim());
                    }
                }
            }
        }

        if (pending != null) {
            Event prev = parseUiLine(pending.toString());
            if (prev != null) {
                events.add(prev);
                parsed++;
            }
        }

        System.out.printf("  Parsed %d / %d UI lines\n", parsed, total);
    }

    private BufferedReader openReader(String path) throws IOException {
        PushbackInputStream in = new PushbackInputStream(new FileInputStream(path), 3);
        byte[] bom = new byte[3];
        int read = in.read(bom, 0, bom.length);

        Charset encoding = StandardCharsets.UTF_8;
        int unread;

        if (read >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
            encoding = StandardCharsets.UTF_8;
            unread = read - 3;
        } else if (read >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
            encoding = StandardCharsets.UTF_16LE;
            unread = read - 2;
        } else if (read >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
            encoding = StandardCharsets.UTF_16BE;
            unread = read - 2;
        } else {
            encoding = Charset.defaultCharset();
            unread = read;
        }

        if (unread > 0) {
            in.unread(bom, read - unread, unread);
        }

        return new BufferedReader(new InputStreamReader(in, encoding));
    }
    
    /**
     * Process all events: sort, group, detect bursts, map causality.
     */
    public void process() {
        // Step 1: Sort all events by timestamp
        Collections.sort(events);
        
        // Step 2: Group by session
        for (Event e : events) {
            eventsBySession.computeIfAbsent(e.session, k -> new ArrayList<>()).add(e);
        }
        
        // Step 3: Detect branch bursts
        detectBranchBursts();
        
        // Step 4: Map bursts to UI actions
        mapBurstsToUiActions();
    }
    
    /**
     * Write merged timeline in various formats.
     */
    public void writeTimeline(String outputPath, OutputFormat format) throws IOException {
        try (FileWriter writer = new FileWriter(outputPath)) {
            switch (format) {
                case HUMAN_READABLE:
                    writeHumanReadable(writer);
                    break;
                case APP_CENTRIC:
                    writeAppCentric(writer);
                    break;
                case BURST_SUMMARY:
                    writeBurstSummary(writer);
                    break;
                case RAW:
                    writeRaw(writer);
                    break;
            }
        }
    }
    
    public enum OutputFormat {
        HUMAN_READABLE,  // Full timeline with bursts
        APP_CENTRIC,     // Only app code + UI
        BURST_SUMMARY,   // Compact burst-per-action view
        RAW              // All events chronologically
    }
    
    // ===== Parsing Implementation =====
    
    private Event parseBranchLine(String line) {
        if (!line.contains("BRANCH=")) {
            return null;
        }
        
        // Skip logcat headers
        if (line.contains("beginning of")) {
            return null;
        }
        
        Event e = new Event();
        e.type = EventType.BRANCH;
        e.subtype = "BRANCH_HIT";
        e.raw = line;
        
        // Extract timestamp (prefer TS= if present, otherwise logcat time)
        Pattern tsPattern = Pattern.compile("TS=(\\d+)");
        Matcher tsMatcher = tsPattern.matcher(line);
        if (tsMatcher.find()) {
            e.ts = Long.parseLong(tsMatcher.group(1));
        } else {
            e.ts = extractLogcatTimestamp(line);
        }
        
        // Extract session
        Pattern sessionPattern = Pattern.compile("SESSION=([^|]+)");
        Matcher sessionMatcher = sessionPattern.matcher(line);
        e.session = sessionMatcher.find() ? sessionMatcher.group(1) : "default";
        
        // Extract thread ID
        Pattern tidPattern = Pattern.compile("TID=(\\d+)");
        Matcher tidMatcher = tidPattern.matcher(line);
        if (tidMatcher.find()) {
            e.threadId = Long.parseLong(tidMatcher.group(1));
        }
        
        // Extract branch descriptor components
        int branchIdx = line.indexOf("BRANCH=");
        if (branchIdx == -1) return null;
        
        String branchPayload = line.substring(branchIdx);
        
        // Parse: BRANCH=<method>|IF|location|EDGE=TRUE|COND=...
        String[] parts = branchPayload.split("\\|");
        if (parts.length < 4) return null;
        
        e.method = extractMethodFromBranch(parts[0]);
        e.branchId = parts.length > 2 ? parts[2] : "";
        
        for (String part : parts) {
            if (part.startsWith("EDGE=")) {
                e.edge = part.substring(5);
            } else if (part.startsWith("COND=")) {
                e.condition = part.substring(5);
            } else if (part.startsWith("CASE=")) {
                e.edge = part;  // Store full CASE=value
            }
        }
        
        // Classify code category
        e.category = classifyMethod(e.method);
        
        return e;
    }
    
    private Event parseUiLine(String line) {
        if (!line.contains("UIACTION|")) {
            return null;
        }
        
        Event e = new Event();
        e.type = EventType.UI;
        e.raw = line;
        
        int actionIdx = line.indexOf("UIACTION|");
        if (actionIdx == -1) return null;
        
        String payload = line.substring(actionIdx);
        
        // Parse pipe-delimited fields
        Map<String, String> fields = new HashMap<>();
        for (String part : payload.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        
        // Extract fields
        String tsValue = fields.getOrDefault("TS", "0");
        e.ts = Long.parseLong(tsValue.replaceAll("\\D", ""));
        e.session = fields.getOrDefault("SESSION", "default");
        e.subtype = fields.getOrDefault("TYPE", "UNKNOWN");
        e.activity = fields.getOrDefault("ACTIVITY", "");
        e.target = fields.getOrDefault("TARGET", "");
        e.category = CodeCategory.APP;  // UI actions are always app-level
        
        return e;
    }
    
    private long extractLogcatTimestamp(String line) {
        Matcher matcher = logcatTsPattern.matcher(line);
        if (matcher.find()) {
            try {
                int month = Integer.parseInt(matcher.group(1));
                int day = Integer.parseInt(matcher.group(2));
                int hour = Integer.parseInt(matcher.group(3));
                int minute = Integer.parseInt(matcher.group(4));
                int second = Integer.parseInt(matcher.group(5));
                int millis = Integer.parseInt(matcher.group(6));
                
                // Smart year inference: logcat lacks year, so infer from current time
                // If we see a date that's "impossible" (e.g., March when we're in Feb),
                // adjust year accordingly
                Calendar now = Calendar.getInstance();
                int currentYear = now.get(Calendar.YEAR);
                int currentMonth = now.get(Calendar.MONTH) + 1; // Calendar.MONTH is 0-indexed
                
                // If log month is > current month by more than 1, assume last year
                // If log month is < current month by more than 6, assume next year
                int inferredYear = currentYear;
                if (month > currentMonth + 1) {
                    inferredYear = currentYear - 1;
                } else if (month < currentMonth - 6) {
                    inferredYear = currentYear + 1;
                }
                
                Calendar cal = Calendar.getInstance();
                cal.set(inferredYear, month - 1, day, hour, minute, second);
                cal.set(Calendar.MILLISECOND, millis);
                
                return cal.getTimeInMillis();
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private boolean looksLikeLogcatStart(String line) {
        return line != null && line.matches("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*");
    }
    
    private String extractMethodFromBranch(String branchPart) {
        // "BRANCH=<com.example.Class: returnType method(params)>"
        if (!branchPart.startsWith("BRANCH=<")) {
            return "";
        }
        int start = branchPart.indexOf('<') + 1;
        int end = branchPart.indexOf('>');
        if (end == -1) return "";
        return branchPart.substring(start, end);
    }
    
    private CodeCategory classifyMethod(String method) {
        if (method == null || method.isEmpty()) {
            return CodeCategory.PLATFORM;
        }
        
        // Extract class name
        String className = method.split(":")[0].trim();
        
        // App code
        if (className.startsWith("com.albul.") || 
            className.startsWith("com.olekdia.") ||
            !className.contains(".")) {  // Sometimes obfuscated
            return CodeCategory.APP;
        }
        
        // Framework/Support
        if (className.startsWith("androidx.") ||
            className.startsWith("com.google.android.material.") ||
            className.startsWith("android.support.")) {
            return CodeCategory.FRAMEWORK;
        }
        
        // Platform
        if (className.startsWith("android.") ||
            className.startsWith("java.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("dalvik.")) {
            return CodeCategory.PLATFORM;
        }
        
        // Default: likely obfuscated app code or third-party library
        return CodeCategory.APP;
    }
    
    // ===== Burst Detection =====
    
    private void detectBranchBursts() {
        bursts.clear();
        
        for (String session : eventsBySession.keySet()) {
            List<Event> sessionEvents = eventsBySession.get(session);
            List<Event> branchEvents = sessionEvents.stream()
                .filter(e -> e.type == EventType.BRANCH)
                .collect(Collectors.toList());
            
            if (branchEvents.isEmpty()) continue;
            
            BranchBurst currentBurst = new BranchBurst();
            currentBurst.session = session;
            currentBurst.startTs = branchEvents.get(0).ts;
            currentBurst.branches.add(branchEvents.get(0));
            
            for (int i = 1; i < branchEvents.size(); i++) {
                Event prev = branchEvents.get(i - 1);
                Event curr = branchEvents.get(i);
                
                long gap = curr.ts - prev.ts;
                
                if (gap <= BURST_GAP_MS) {
                    // Same burst
                    currentBurst.branches.add(curr);
                    currentBurst.endTs = curr.ts;
                } else {
                    // New burst
                    bursts.add(currentBurst);
                    
                    currentBurst = new BranchBurst();
                    currentBurst.session = session;
                    currentBurst.startTs = curr.ts;
                    currentBurst.branches.add(curr);
                }
            }
            
            // Add final burst
            if (!currentBurst.branches.isEmpty()) {
                currentBurst.endTs = currentBurst.branches.get(currentBurst.branches.size() - 1).ts;
                bursts.add(currentBurst);
            }
        }
    }
    
    // ===== Causal Mapping =====
    
    private void mapBurstsToUiActions() {
        // 1. Global mapping first: find nearest preceding UI action (ignore session)
        for (BranchBurst burst : bursts) {
            Event nearestUi = null;
            long minDelta = Long.MAX_VALUE;
            
            // Search ALL UI events globally, not restricted by session
            for (Event e : events) {
                if (e.type != EventType.UI) continue;
                if (e.ts > burst.startTs) continue;  // Must precede burst
                
                long delta = burst.startTs - e.ts;
                if (delta < minDelta && delta <= CAUSALITY_WINDOW_MS) {
                    minDelta = delta;
                    nearestUi = e;
                }
            }
            
            if (nearestUi != null) {
                burst.causativeAction = nearestUi;
                burst.deltaMs = minDelta;
            }
        }
        
        // 2. Filter startup noise: remove unmapped bursts before first UI action
        long firstUiTs = events.stream()
            .filter(e -> e.type == EventType.UI)
            .mapToLong(e -> e.ts)
            .min()
            .orElse(Long.MAX_VALUE);
        
        bursts.removeIf(b -> b.causativeAction == null && b.startTs < firstUiTs);
        
        // 3. Filter framework noise: remove FRAMEWORK/PLATFORM branches from each burst
        for (BranchBurst burst : bursts) {
            burst.branches.removeIf(e -> e.category != CodeCategory.APP);
        }
        
        // Remove empty bursts after filtering
        bursts.removeIf(b -> b.branches.isEmpty());
    }
    
    // ===== Output Formats =====
    
    private void writeBurstSummary(FileWriter writer) throws IOException {
        writer.write("================================================================================\n");
        writer.write("  ENHANCED TIMELINE: UI Actions → Branch Bursts\n");
        writer.write("================================================================================\n\n");
        
        writer.write("Configuration:\n");
        writer.write(String.format("  Burst gap threshold: %d ms\n", BURST_GAP_MS));
        writer.write(String.format("  Causality window: %d ms\n", CAUSALITY_WINDOW_MS));
        writer.write("\n");
        
        writer.write("Summary:\n");
        writer.write(String.format("  Total events: %d\n", events.size()));
        writer.write(String.format("  Branch events: %d\n", events.stream().filter(e -> e.type == EventType.BRANCH).count()));
        writer.write(String.format("  UI actions: %d\n", events.stream().filter(e -> e.type == EventType.UI).count()));
        writer.write(String.format("  Branch bursts detected: %d\n", bursts.size()));
        writer.write(String.format("  Sessions: %d\n", eventsBySession.size()));
        
        long mappedCount = bursts.stream().filter(b -> b.causativeAction != null).count();
        writer.write(String.format("  Mapped bursts: %d / %d (%.1f%%)\n", 
            mappedCount, bursts.size(), 
            bursts.isEmpty() ? 0.0 : 100.0 * mappedCount / bursts.size()));
        writer.write("\n");
        
        // UI-centric output: show ALL UI actions in chronological order
        List<Event> uiActions = events.stream()
            .filter(e -> e.type == EventType.UI)
            .collect(Collectors.toList());
        
        writer.write("================================================================================\n");
        writer.write("  UI-CENTRIC TIMELINE\n");
        writer.write("================================================================================\n\n");
        
        for (Event uiAction : uiActions) {
            // Show UI action
            writer.write(String.format("[%s] UI %s %s", 
                formatTimestamp(uiAction.ts),
                uiAction.subtype,
                uiAction.target));
            if (uiAction.activity != null && !uiAction.activity.isEmpty()) {
                writer.write(String.format(" @ %s", uiAction.activity));
            }
            if (uiAction.session != null && !uiAction.session.equals("default")) {
                writer.write(String.format(" [%s]", uiAction.session));
            }
            writer.write("\n\n");
            
            // Find all bursts caused by this UI action
            List<BranchBurst> relatedBursts = bursts.stream()
                .filter(b -> b.causativeAction == uiAction)
                .sorted(Comparator.comparingLong(b -> b.deltaMs))
                .collect(Collectors.toList());
            
            if (relatedBursts.isEmpty()) {
                writer.write("   (no app code branches detected)\n\n");
            } else {
                for (BranchBurst burst : relatedBursts) {
                    writeUICentricBurst(writer, burst);
                }
            }
        }
        
        // Show unmapped bursts at the end
        List<BranchBurst> unmapped = bursts.stream()
            .filter(b -> b.causativeAction == null)
            .collect(Collectors.toList());
        
        if (!unmapped.isEmpty()) {
            writer.write("================================================================================\n");
            writer.write("  UNMAPPED BURSTS (no clear UI cause)\n");
            writer.write("================================================================================\n\n");
            for (BranchBurst burst : unmapped) {
                writer.write(String.format("[%s] %d app branches\n",
                    formatTimestamp(burst.startTs), burst.size()));
                Map<String, Long> topMethods = burst.getTopMethods(3);
                for (Map.Entry<String, Long> entry : topMethods.entrySet()) {
                    writer.write(String.format("   %s (%d)\n", entry.getKey(), entry.getValue()));
                }
                writer.write("\n");
            }
        }

        writeHotspotSection(writer, uiActions);
        writeIneffectiveSection(writer, uiActions);
    }

    private void writeHotspotSection(FileWriter writer, List<Event> uiActions) throws IOException {
        Map<String, UIHotspot> hotspots = computeHotspots();
        List<UIHotspot> sorted = new ArrayList<>(hotspots.values());
        sorted.sort(Comparator.comparingInt((UIHotspot h) -> h.branchCount).reversed()
            .thenComparingInt(h -> h.cfgEdgeCount).reversed());

        writer.write("================================================================================\n");
        writer.write("  UI COVERAGE HOTSPOTS\n");
        writer.write("================================================================================\n\n");

        if (sorted.isEmpty()) {
            writer.write("(no mapped hotspot data)\n\n");
            return;
        }

        int rank = 1;
        for (UIHotspot h : sorted) {
            writer.write(String.format("%d. %s\n", rank++, h.action));
            writer.write(String.format("   branches: %d\n", h.branchCount));
            writer.write(String.format("   cfg edges (approx): %d\n", h.cfgEdgeCount));
            writer.write(String.format("   methods: %d\n", h.methodCount));

            List<Map.Entry<String, Integer>> topMethods = new ArrayList<>(h.methodFreq.entrySet());
            topMethods.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            topMethods = topMethods.stream().limit(3).collect(Collectors.toList());
            if (!topMethods.isEmpty()) {
                writer.write("   top methods: ");
                writer.write(topMethods.stream()
                    .map(e -> simplifyMethodName(e.getKey()) + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(", ")));
                writer.write("\n");
            }
            writer.write("\n");
        }
    }

    private void writeIneffectiveSection(FileWriter writer, List<Event> uiActions) throws IOException {
        Map<String, UIActionStats> statsMap = computeActionEffectiveness(uiActions);
        List<UIActionStats> stats = new ArrayList<>(statsMap.values());
        stats.sort(Comparator.comparingInt((UIActionStats s) -> s.branchCount).reversed());

        writer.write("================================================================================\n");
        writer.write("  UI ACTION EFFECTIVENESS\n");
        writer.write("================================================================================\n\n");

        writer.write("Effective Actions\n\n");
        boolean hasEffective = false;
        for (UIActionStats s : stats) {
            if (!s.effective) continue;
            hasEffective = true;
            writer.write(String.format("%s\n", s.action));
            writer.write(String.format("  branches: %d\n", s.branchCount));
            writer.write(String.format("  cfg edges (approx): %d\n", s.cfgEdges));
            if (s.minDeltaMs != Long.MAX_VALUE) {
                writer.write(String.format("  min delta: %d ms\n", s.minDeltaMs));
            }
            writer.write("\n");
        }
        if (!hasEffective) {
            writer.write("(none)\n\n");
        }

        writer.write("Ineffective Actions\n\n");
        boolean hasIneffective = false;
        for (UIActionStats s : stats) {
            if (s.effective) continue;
            hasIneffective = true;
            writer.write(String.format("%s\n", s.action));
            writer.write("  branches: 0\n\n");
        }
        if (!hasIneffective) {
            writer.write("(none)\n\n");
        }
    }

    private Map<String, UIHotspot> computeHotspots() {
        Map<String, UIHotspot> hotspots = new HashMap<>();

        for (BranchBurst burst : bursts) {
            if (burst.causativeAction == null) continue;
            String action = buildActionLabel(burst.causativeAction);
            UIHotspot h = hotspots.computeIfAbsent(action, k -> {
                UIHotspot x = new UIHotspot();
                x.action = k;
                return x;
            });

            h.branchCount += burst.branches.size();

            Set<String> uniqueEdges = burst.branches.stream()
                .filter(e -> e.branchId != null && !e.branchId.isEmpty())
                .map(e -> e.branchId + "|" + (e.edge == null ? "" : e.edge))
                .collect(Collectors.toSet());
            h.cfgEdgeCount += uniqueEdges.size();

            Set<String> uniqueMethods = burst.branches.stream()
                .filter(e -> e.method != null && !e.method.isEmpty())
                .map(e -> e.method)
                .collect(Collectors.toSet());
            h.methodCount += uniqueMethods.size();

            for (Event e : burst.branches) {
                if (e.method != null && !e.method.isEmpty()) {
                    h.methodFreq.merge(e.method, 1, Integer::sum);
                }
            }
        }

        return hotspots;
    }

    private Map<String, UIActionStats> computeActionEffectiveness(List<Event> uiActions) {
        Map<String, UIActionStats> stats = new LinkedHashMap<>();

        for (Event ui : uiActions) {
            String action = buildActionLabel(ui);
            stats.computeIfAbsent(action, k -> {
                UIActionStats s = new UIActionStats();
                s.action = k;
                return s;
            });
        }

        for (BranchBurst burst : bursts) {
            if (burst.causativeAction == null) continue;
            String action = buildActionLabel(burst.causativeAction);
            UIActionStats s = stats.computeIfAbsent(action, k -> {
                UIActionStats x = new UIActionStats();
                x.action = k;
                return x;
            });

            s.branchCount += burst.branches.size();
            s.minDeltaMs = Math.min(s.minDeltaMs, burst.deltaMs);

            Set<String> uniqueEdges = burst.branches.stream()
                .filter(e -> e.branchId != null && !e.branchId.isEmpty())
                .map(e -> e.branchId + "|" + (e.edge == null ? "" : e.edge))
                .collect(Collectors.toSet());
            s.cfgEdges += uniqueEdges.size();
        }

        // Ineffective rule: no mapped branches OR nearest mapped burst later than 500ms
        for (UIActionStats s : stats.values()) {
            s.effective = s.branchCount > 0 && s.minDeltaMs <= 500;
        }

        return stats;
    }

    private String buildActionLabel(Event ui) {
        String subtype = ui.subtype == null ? "UNKNOWN" : ui.subtype;
        String target = ui.target == null || ui.target.isEmpty() ? "(unknown-target)" : ui.target;
        return subtype + " " + target;
    }
    
    private void writeBurstSummaryLine(FileWriter writer, BranchBurst burst) throws IOException {
        long appCount = burst.countByCategory(CodeCategory.APP);
        long frameworkCount = burst.countByCategory(CodeCategory.FRAMEWORK);
        long platformCount = burst.countByCategory(CodeCategory.PLATFORM);
        
        String confidenceLevel;
        if (burst.deltaMs < 100) {
            confidenceLevel = "HIGH";
        } else if (burst.deltaMs < 500) {
            confidenceLevel = "MED";
        } else {
            confidenceLevel = "LOW";
        }
        
        writer.write(String.format("   └─ burst (+%d ms, %s confidence): %d branches [APP:%d  FW:%d  SYS:%d]\n",
            burst.deltaMs,
            confidenceLevel,
            burst.size(),
            appCount,
            frameworkCount,
            platformCount));
        
        // Show top methods (app code only)
        Map<String, Long> topMethods = burst.branches.stream()
            .filter(e -> e.category == CodeCategory.APP)
            .filter(e -> e.method != null)
            .collect(Collectors.groupingBy(e -> simplifyMethodName(e.method), Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(3)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (e1, e2) -> e1, LinkedHashMap::new));
        
        if (!topMethods.isEmpty()) {
            writer.write("      top app methods: ");
            writer.write(topMethods.entrySet().stream()
                .map(e -> String.format("%s(%d)", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ")));
            writer.write("\n");
        }
    }
    
    private void writeUICentricBurst(FileWriter writer, BranchBurst burst) throws IOException {
        writer.write(String.format("   → branch burst (+%d ms, %d app branches)\n",
            burst.deltaMs, burst.size()));
        
        // Show top methods
        Map<String, Long> topMethods = burst.getTopMethods(5);
        if (!topMethods.isEmpty()) {
            writer.write("      Methods:\n");
            for (Map.Entry<String, Long> entry : topMethods.entrySet()) {
                writer.write(String.format("         %s (%d)\n", 
                    simplifyMethodName(entry.getKey()), entry.getValue()));
            }
        }
        
        writer.write("\n");
    }
    
    private void writeHumanReadable(FileWriter writer) throws IOException {
        writer.write("================================================================================\n");
        writer.write("  MERGED TIMELINE: Branch Coverage + UI Actions\n");
        writer.write("================================================================================\n\n");
        
        for (String session : eventsBySession.keySet()) {
            writer.write(String.format("\n--- SESSION: %s ---\n\n", session));
            
            List<Event> sessionEvents = eventsBySession.get(session);
            long startTs = sessionEvents.isEmpty() ? 0 : sessionEvents.get(0).ts;
            
            for (Event e : sessionEvents) {
                long relativeTs = e.ts - startTs;
                writer.write(String.format("[+%.3fs] %-7s  %s\n", 
                    relativeTs / 1000.0,
                    e.type,
                    formatEventMessage(e)));
            }
        }
    }
    
    private void writeAppCentric(FileWriter writer) throws IOException {
        writer.write("================================================================================\n");
        writer.write("  APP-CENTRIC TIMELINE (Framework/Platform filtered out)\n");
        writer.write("================================================================================\n\n");
        
        for (String session : eventsBySession.keySet()) {
            writer.write(String.format("\n--- SESSION: %s ---\n\n", session));
            
            List<Event> sessionEvents = eventsBySession.get(session);
            List<Event> appEvents = sessionEvents.stream()
                .filter(e -> e.type == EventType.UI || e.category == CodeCategory.APP)
                .collect(Collectors.toList());
            
            long startTs = appEvents.isEmpty() ? 0 : appEvents.get(0).ts;
            
            for (Event e : appEvents) {
                long relativeTs = e.ts - startTs;
                writer.write(String.format("[+%.3fs] %-7s  %s\n", 
                    relativeTs / 1000.0,
                    e.type,
                    formatEventMessage(e)));
            }
        }
    }
    
    private void writeRaw(FileWriter writer) throws IOException {
        writer.write("Raw chronological event list\n");
        writer.write("============================\n\n");
        
        for (Event e : events) {
            writer.write(String.format("%d\t%s\t%s\t%s\n",
                e.ts, e.type, e.session, e.raw));
        }
    }
    
    // ===== Utilities =====
    
    private String formatTimestamp(long ts) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts);
        return String.format("%02d:%02d:%02d.%03d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND));
    }
    
    private String formatEventMessage(Event e) {
        if (e.type == EventType.UI) {
            return String.format("%s %s @ %s", e.subtype, e.target, e.activity);
        } else {
            String methodName = simplifyMethodName(e.method);
            return String.format("%s %s (%s) [%s]", 
                methodName,
                e.branchId,
                e.edge,
                e.category);
        }
    }
    
    private String simplifyMethodName(String fullMethod) {
        if (fullMethod == null || fullMethod.isEmpty()) return "";
        
        // Extract just class.method from "com.example.Class: returnType method(params)"
        String[] parts = fullMethod.split(":");
        if (parts.length < 2) return fullMethod;
        
        String className = parts[0].trim();
        String methodPart = parts[1].trim();
        
        // Get simple class name
        String simpleClass = className.substring(className.lastIndexOf('.') + 1);
        
        // Get method name (before parameters)
        String methodName = methodPart.split("\\(")[0].trim();
        methodName = methodName.substring(methodName.lastIndexOf(' ') + 1);
        
        return simpleClass + "." + methodName;
    }
    
    // ===== CLI Entry Point =====
    
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: LogMergerEnhanced --branch-log <path> --ui-log <path> --output <path> [--format <FORMAT>]");
            System.err.println("Formats: HUMAN_READABLE (default), APP_CENTRIC, BURST_SUMMARY, RAW");
            System.exit(1);
        }
        
        String branchLog = null;
        String uiLog = null;
        String output = null;
        OutputFormat format = OutputFormat.BURST_SUMMARY;
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--branch-log":
                    branchLog = args[++i];
                    break;
                case "--ui-log":
                    uiLog = args[++i];
                    break;
                case "--output":
                    output = args[++i];
                    break;
                case "--format":
                    format = OutputFormat.valueOf(args[++i].toUpperCase());
                    break;
            }
        }
        
        if (branchLog == null || uiLog == null || output == null) {
            System.err.println("Missing required arguments");
            System.exit(1);
        }
        
        try {
            LogMergerEnhanced merger = new LogMergerEnhanced();
            
            System.out.println("Parsing branch log: " + branchLog);
            merger.parseBranchLog(branchLog);
            
            System.out.println("Parsing UI log: " + uiLog);
            merger.parseUiLog(uiLog);
            
            System.out.println("Processing timeline...");
            merger.process();
            
            System.out.println("Writing output: " + output);
            merger.writeTimeline(output, format);
            
            System.out.println("\nTimeline Summary:");
            System.out.println("  Total events: " + merger.events.size());
            System.out.println("  Branch events: " + merger.events.stream().filter(e -> e.type == EventType.BRANCH).count());
            System.out.println("  UI actions: " + merger.events.stream().filter(e -> e.type == EventType.UI).count());
            System.out.println("  Branch bursts: " + merger.bursts.size());
            System.out.println("  Sessions: " + merger.eventsBySession.size());
            
            long mappedBursts = merger.bursts.stream().filter(b -> b.causativeAction != null).count();
            System.out.println(String.format("  Mapped bursts: %d / %d (%.1f%%)",
                mappedBursts, merger.bursts.size(),
                merger.bursts.isEmpty() ? 0.0 : (100.0 * mappedBursts / merger.bursts.size())));
            
            System.out.println("\nDone!");
            
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
