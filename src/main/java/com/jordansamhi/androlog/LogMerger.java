package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LogMerger combines branch logs and UI action logs into a unified timeline.
 * 
 * This enables correlation of UI interactions with branch coverage to support
 * LLM-guided test generation.
 * 
 * @author Liqi Tang
 */
public class LogMerger {
    
    /**
     * Represents a single event (branch or UI action) with a timestamp.
     */
    private static class TimelineEvent implements Comparable<TimelineEvent> {
        long timestamp;
        String type;  // "BRANCH" or "UIACTION"
        String message;
        String session;
        
        @Override
        public int compareTo(TimelineEvent other) {
            return Long.compare(this.timestamp, other.timestamp);
        }
        
        @Override
        public String toString() {
            return String.format("%d\t%s\t%s\t%s", timestamp, type, session, message);
        }
    }
    
    private List<TimelineEvent> events = new ArrayList<>();
    private Pattern timestampPattern = Pattern.compile("\\b(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\b");
    
    /**
     * Parse a branch log file.
     * 
     * Extracts timestamp and branch descriptor from logcat-style lines.
     * 
     * @param branchLogPath Path to branch log file
     * @throws IOException If file cannot be read
     */
    public void parseBranchLog(String branchLogPath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(branchLogPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                TimelineEvent event = parseBranchLine(line);
                if (event != null) {
                    events.add(event);
                }
            }
        }
    }
    
    /**
     * Parse a UI action log file.
     * 
     * @param uiLogPath Path to UI action log file
     * @throws IOException If file cannot be read
     */
    public void parseUiLog(String uiLogPath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(uiLogPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                TimelineEvent event = parseUiLine(line);
                if (event != null) {
                    events.add(event);
                }
            }
        }
    }
    
    /**
     * Parse a branch log line.
     * 
     * Example format:
     * 02-24 11:55:10.123  1234  5678 D BRANCH_LOG: BRANCH=<method>|IF|L145|EDGE=FALSE|COND=...
     * 
     * @param line Log line
     * @return TimelineEvent or null if not a branch log
     */
    private TimelineEvent parseBranchLine(String line) {
        if (!line.contains("BRANCH=")) {
            return null;
        }
        
        TimelineEvent event = new TimelineEvent();
        event.type = "BRANCH";
        
        // Extract timestamp from logcat format
        event.timestamp = extractLogcatTimestamp(line);
        
        // Extract branch descriptor
        int branchIndex = line.indexOf("BRANCH=");
        if (branchIndex != -1) {
            event.message = line.substring(branchIndex);
        } else {
            return null;
        }
        
        // Try to extract session if present (optional)
        event.session = "default";
        
        return event;
    }
    
    /**
     * Parse a UI action log line.
     * 
     * Example format:
     * 02-24 11:55:10.123  1234  5678 D UI_LOG: UIACTION|TS=1709798300123|SESSION=T1|STEP=2|...
     * 
     * @param line Log line
     * @return TimelineEvent or null if not a UI action log
     */
    private TimelineEvent parseUiLine(String line) {
        if (!line.contains("UIACTION|")) {
            return null;
        }
        
        TimelineEvent event = new TimelineEvent();
        event.type = "UIACTION";
        
        // Extract the UIACTION message
        int actionIndex = line.indexOf("UIACTION|");
        if (actionIndex == -1) {
            return null;
        }
        
        String message = line.substring(actionIndex);
        event.message = message;
        
        // Extract timestamp from TS= field
        Pattern tsPattern = Pattern.compile("TS=(\\d+)");
        Matcher tsMatcher = tsPattern.matcher(message);
        if (tsMatcher.find()) {
            event.timestamp = Long.parseLong(tsMatcher.group(1));
        } else {
            // Fallback to logcat timestamp
            event.timestamp = extractLogcatTimestamp(line);
        }
        
        // Extract session ID
        Pattern sessionPattern = Pattern.compile("SESSION=([^|]+)");
        Matcher sessionMatcher = sessionPattern.matcher(message);
        if (sessionMatcher.find()) {
            event.session = sessionMatcher.group(1);
        } else {
            event.session = "default";
        }
        
        return event;
    }
    
    /**
     * Extract timestamp from logcat line format.
     * 
     * Converts "MM-DD HH:MM:SS.mmm" to milliseconds since epoch.
     * Note: This is approximate since logcat doesn't include year.
     * Uses current year as reference.
     * 
     * @param line Logcat line
     * @return Timestamp in milliseconds
     */
    private long extractLogcatTimestamp(String line) {
        Matcher matcher = timestampPattern.matcher(line);
        if (matcher.find()) {
            try {
                int month = Integer.parseInt(matcher.group(1));
                int day = Integer.parseInt(matcher.group(2));
                int hour = Integer.parseInt(matcher.group(3));
                int minute = Integer.parseInt(matcher.group(4));
                int second = Integer.parseInt(matcher.group(5));
                int millis = Integer.parseInt(matcher.group(6));
                
                // Create approximate timestamp
                // Note: This assumes current year, which is fine for correlation
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.MONTH, month - 1);
                cal.set(Calendar.DAY_OF_MONTH, day);
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, second);
                cal.set(Calendar.MILLISECOND, millis);
                
                return cal.getTimeInMillis();
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        
        return 0;
    }
    
    /**
     * Sort all events by timestamp.
     */
    public void sort() {
        Collections.sort(events);
    }
    
    /**
     * Write merged timeline to file.
     * 
     * Format:
     * TIMESTAMP    TYPE        SESSION    MESSAGE
     * 
     * @param outputPath Path to output file
     * @throws IOException If file cannot be written
     */
    public void writeTimeline(String outputPath) throws IOException {
        sort();
        
        try (FileWriter writer = new FileWriter(outputPath)) {
            writer.write("# Merged Timeline: Branch Coverage + UI Actions\n");
            writer.write("# Format: TIMESTAMP\\tTYPE\\tSESSION\\tMESSAGE\n");
            writer.write("#\n");
            
            for (TimelineEvent event : events) {
                writer.write(event.toString());
                writer.write("\n");
            }
        }
    }
    
    /**
     * Write human-readable timeline with relative timestamps.
     * 
     * @param outputPath Path to output file
     * @throws IOException If file cannot be written
     */
    public void writeHumanReadableTimeline(String outputPath) throws IOException {
        sort();
        
        if (events.isEmpty()) {
            return;
        }
        
        long startTime = events.get(0).timestamp;
        
        try (FileWriter writer = new FileWriter(outputPath)) {
            writer.write("Merged Timeline: Branch Coverage + UI Actions\n");
            writer.write("==============================================\n\n");
            
            String currentSession = "";
            
            for (TimelineEvent event : events) {
                // Print session separator
                if (!event.session.equals(currentSession)) {
                    writer.write(String.format("\n--- SESSION: %s ---\n\n", event.session));
                    currentSession = event.session;
                }
                
                long relativeTime = event.timestamp - startTime;
                double seconds = relativeTime / 1000.0;
                
                String prefix = event.type.equals("BRANCH") ? "BRANCH  " : "UI     ";
                
                writer.write(String.format("[+%.3fs] %s %s\n", 
                    seconds, prefix, formatMessage(event)));
            }
        }
    }
    
    /**
     * Format an event message for human-readable output.
     * 
     * @param event The timeline event
     * @return Formatted message string
     */
    private String formatMessage(TimelineEvent event) {
        if (event.type.equals("UIACTION")) {
            return formatUiMessage(event.message);
        } else {
            return formatBranchMessage(event.message);
        }
    }
    
    /**
     * Format a UI action message for readability.
     * 
     * Converts: UIACTION|TS=...|SESSION=...|STEP=3|ACTIVITY=MainActivity|TYPE=CLICK|TARGET=btn_add|TEXT=Add
     * To: Step 3: CLICK btn_add "Add" @ MainActivity
     * 
     * @param message Raw UIACTION message
     * @return Formatted string
     */
    private String formatUiMessage(String message) {
        Map<String, String> fields = parseFields(message);
        
        String step = fields.getOrDefault("STEP", "?");
        String type = fields.getOrDefault("TYPE", "?");
        String target = fields.getOrDefault("TARGET", "");
        String text = fields.getOrDefault("TEXT", "");
        String value = fields.getOrDefault("VALUE", "");
        String activity = fields.getOrDefault("ACTIVITY", "");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Step ").append(step).append(": ");
        sb.append(type);
        
        if (!target.isEmpty()) {
            sb.append(" ").append(target);
        }
        
        if (!text.isEmpty()) {
            sb.append(" \"").append(text).append("\"");
        }
        
        if (!value.isEmpty()) {
            sb.append(" = ").append(value);
        }
        
        if (!activity.isEmpty()) {
            String shortActivity = activity.substring(activity.lastIndexOf('.') + 1);
            sb.append(" @ ").append(shortActivity);
        }
        
        return sb.toString();
    }
    
    /**
     * Format a branch message for readability.
     * 
     * Converts: BRANCH=<com.app.TaskManager: void saveTask()>|IF|L145|EDGE=FALSE|COND=title.isEmpty()
     * To: saveTask() L145 IF FALSE: title.isEmpty()
     * 
     * @param message Raw BRANCH message
     * @return Formatted string
     */
    private String formatBranchMessage(String message) {
        Map<String, String> fields = parseFields(message);
        
        String signature = fields.getOrDefault("BRANCH", "");
        String type = fields.getOrDefault("IF", fields.getOrDefault("SWITCH", "?"));
        String location = fields.getOrDefault("", "");  // Location is between type and EDGE/CASE
        String edge = fields.getOrDefault("EDGE", fields.getOrDefault("CASE", "?"));
        String condition = fields.getOrDefault("COND", "");
        
        // Extract method name from signature
        String methodName = signature;
        if (signature.contains(": ")) {
            int colonIndex = signature.lastIndexOf(": ");
            methodName = signature.substring(colonIndex + 2);
            if (methodName.contains("(")) {
                methodName = methodName.substring(0, methodName.indexOf(")") + 1);
            }
        }
        
        // Extract location from the raw message since it's between pipes
        Pattern locPattern = Pattern.compile("\\|(IF|SWITCH)\\|([^|]+)\\|");
        Matcher locMatcher = locPattern.matcher(message);
        if (locMatcher.find()) {
            location = locMatcher.group(2);
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(methodName).append(" ");
        sb.append(location).append(" ");
        sb.append(type).append(" ");
        sb.append(edge);
        
        if (!condition.isEmpty()) {
            sb.append(": ").append(condition);
        }
        
        return sb.toString();
    }
    
    /**
     * Parse pipe-delimited fields into a map.
     * 
     * @param message Pipe-delimited message
     * @return Map of field names to values
     */
    private Map<String, String> parseFields(String message) {
        Map<String, String> fields = new HashMap<>();
        String[] parts = message.split("\\|");
        
        for (String part : parts) {
            int equalsIndex = part.indexOf('=');
            if (equalsIndex != -1) {
                String key = part.substring(0, equalsIndex);
                String value = part.substring(equalsIndex + 1);
                fields.put(key, value);
            }
        }
        
        return fields;
    }
    
    /**
     * Get all events.
     * 
     * @return List of timeline events
     */
    public List<TimelineEvent> getEvents() {
        return events;
    }
    
    /**
     * Get summary statistics.
     * 
     * @return Summary string
     */
    public String getSummary() {
        int branchCount = 0;
        int uiActionCount = 0;
        Set<String> sessions = new HashSet<>();
        
        for (TimelineEvent event : events) {
            if (event.type.equals("BRANCH")) {
                branchCount++;
            } else if (event.type.equals("UIACTION")) {
                uiActionCount++;
            }
            sessions.add(event.session);
        }
        
        return String.format(
            "Timeline Summary:\n" +
            "  Total events: %d\n" +
            "  Branch logs: %d\n" +
            "  UI actions: %d\n" +
            "  Sessions: %d",
            events.size(), branchCount, uiActionCount, sessions.size()
        );
    }
    
    /**
     * Command-line interface for log merging.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("LogMerger - Merge branch logs and UI action logs");
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  java LogMerger --branch-log <file> --ui-log <file> --output <file>");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  --branch-log <file>   Path to branch log file");
            System.out.println("  --ui-log <file>       Path to UI action log file");
            System.out.println("  --output <file>       Path to output merged timeline");
            System.out.println("  --human-readable      Generate human-readable format");
            return;
        }
        
        String branchLog = null;
        String uiLog = null;
        String output = "merged_timeline.txt";
        boolean humanReadable = false;
        
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
                case "--human-readable":
                    humanReadable = true;
                    break;
            }
        }
        
        if (branchLog == null || uiLog == null) {
            System.err.println("Error: Both --branch-log and --ui-log are required");
            return;
        }
        
        try {
            LogMerger merger = new LogMerger();
            
            System.out.println("Parsing branch log: " + branchLog);
            merger.parseBranchLog(branchLog);
            
            System.out.println("Parsing UI action log: " + uiLog);
            merger.parseUiLog(uiLog);
            
            System.out.println("\n" + merger.getSummary() + "\n");
            
            if (humanReadable) {
                System.out.println("Writing human-readable timeline: " + output);
                merger.writeHumanReadableTimeline(output);
            } else {
                System.out.println("Writing timeline: " + output);
                merger.writeTimeline(output);
            }
            
            System.out.println("Done!");
            
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
