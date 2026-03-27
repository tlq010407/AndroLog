package com.jordansamhi.androlog;

import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime helper for branch logging that adds timestamps and session tracking.
 * 
 * This class is injected into the instrumented APK to enhance branch logs with:
 * - Precise millisecond timestamps (TS=...)
 * - Session identifiers (SESSION=...)
 * - Thread information (TID=...)
 * 
 * Usage during instrumentation:
 * Instead of: Log.d(tag, "BRANCH=...")
 * Use: Log.d(tag, BranchLogHelper.format("BRANCH=..."))
 * 
 * @author AndroLog
 */
public class BranchLogHelper {
    
    private static String currentSession = "default";
    private static final AtomicLong bootReferenceTime = new AtomicLong(0);
    
    /**
     * Set the current test session ID.
     * Call this before starting a test run to tag all subsequent branch logs.
     * 
     * @param sessionId Session identifier (e.g., "TestRun_001")
     */
    public static void setSession(String sessionId) {
        currentSession = sessionId;
    }
    
    /**
     * Get the current session ID.
     * 
     * @return Current session identifier
     */
    public static String getSession() {
        return currentSession;
    }
    
    /**
     * Format a branch log message with timestamp and session.
     * 
     * Input:  "BRANCH=<method>|IF|U123|EDGE=TRUE|COND=..."
     * Output: "BRANCH=<method>|IF|U123|EDGE=TRUE|COND=...|TS=1773033988158|SESSION=TestRun_001|TID=6666"
     * 
     * @param branchDescriptor Branch descriptor from instrumentation
     * @return Enhanced log message with timestamp and session
     */
    public static String format(String branchDescriptor) {
        long timestamp = System.currentTimeMillis();
        long threadId = Thread.currentThread().getId();
        
        return String.format("%s|TS=%d|SESSION=%s|TID=%d", 
            branchDescriptor, 
            timestamp, 
            currentSession, 
            threadId);
    }
    
    /**
     * Format with custom session (for compatibility with external callers).
     * 
     * @param branchDescriptor Branch descriptor
     * @param sessionId Session identifier
     * @return Enhanced log message
     */
    public static String format(String branchDescriptor, String sessionId) {
        long timestamp = System.currentTimeMillis();
        long threadId = Thread.currentThread().getId();
        
        return String.format("%s|TS=%d|SESSION=%s|TID=%d", 
            branchDescriptor, 
            timestamp, 
            sessionId, 
            threadId);
    }
    
    /**
     * Initialize session from system properties or intent extras.
     * This allows the test runner to set the session externally.
     */
    public static void initializeSessionFromContext() {
        // Try to read from system property first
        String propSession = System.getProperty("androlog.session");
        if (propSession != null && !propSession.isEmpty()) {
            currentSession = propSession;
            return;
        }
        
        // Otherwise use default
        currentSession = "default";
    }
}
