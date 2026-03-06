package com.jordansamhi.androlog;

import soot.SootMethod;
import soot.jimple.IfStmt;
import soot.jimple.LookupSwitchStmt;
import soot.jimple.Stmt;
import soot.jimple.SwitchStmt;
import soot.jimple.TableSwitchStmt;

/**
 * Utility class that builds stable, human-readable branch descriptors.
 */
public final class BranchDescriptorUtil {

    private BranchDescriptorUtil() {
    }

    public static String buildIfDescriptor(SootMethod method, IfStmt ifStmt, String edgeType) {
        String location = buildLocationToken(ifStmt);
        String condition = sanitize(ifStmt.getCondition().toString(), 180);
        return String.format("BRANCH=%s|IF|%s|EDGE=%s|COND=%s", method.getSignature(), location, edgeType, condition);
    }

    public static String buildSwitchDescriptor(SootMethod method, SwitchStmt switchStmt, int caseIndex) {
        String location = buildLocationToken((Stmt) switchStmt);
        String caseLabel = resolveCaseLabel(switchStmt, caseIndex);
        return String.format("BRANCH=%s|SWITCH|%s|CASE=%s", method.getSignature(), location, caseLabel);
    }

    public static String buildSwitchDefaultDescriptor(SootMethod method, SwitchStmt switchStmt) {
        String location = buildLocationToken((Stmt) switchStmt);
        return String.format("BRANCH=%s|SWITCH|%s|CASE=DEFAULT", method.getSignature(), location);
    }

    private static String resolveCaseLabel(SwitchStmt switchStmt, int caseIndex) {
        if (switchStmt instanceof TableSwitchStmt) {
            TableSwitchStmt tableSwitchStmt = (TableSwitchStmt) switchStmt;
            return String.valueOf(tableSwitchStmt.getLowIndex() + caseIndex);
        }
        if (switchStmt instanceof LookupSwitchStmt) {
            LookupSwitchStmt lookupSwitchStmt = (LookupSwitchStmt) switchStmt;
            return String.valueOf(lookupSwitchStmt.getLookupValue(caseIndex));
        }
        return String.valueOf(caseIndex);
    }

    private static String buildLocationToken(Stmt stmt) {
        int sourceLine = stmt.getJavaSourceStartLineNumber();
        if (sourceLine > 0) {
            return "L" + sourceLine;
        }
        return "U" + Integer.toHexString(System.identityHashCode(stmt));
    }

    private static String sanitize(String raw, int maxLength) {
        String normalized = raw
                .replace('|', '_')
                .replace('=', '_')
                .replace('\n', '_')
                .replace('\r', '_')
                .replace('\t', '_')
                .replaceAll("\\s+", "_");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
