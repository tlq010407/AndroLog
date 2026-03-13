package com.jordansamhi.androlog;

import soot.SootMethod;
import soot.jimple.IfStmt;
import soot.jimple.LookupSwitchStmt;
import soot.jimple.Stmt;
import soot.jimple.SwitchStmt;
import soot.jimple.TableSwitchStmt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class that builds stable, human-readable branch descriptors.
 */
public final class BranchDescriptorUtil {

    private BranchDescriptorUtil() {
    }

    public static String buildIfDescriptor(SootMethod method, IfStmt ifStmt, String edgeType) {
        String location = buildLocationToken(method, ifStmt);
        String condition = sanitize(ifStmt.getCondition().toString(), 180);
        return String.format(
                "BRANCH=%s|IF|%s|EDGE=%s|COND=%s",
                method.getSignature(),
                location,
                edgeType,
                condition
        );
    }

    public static String buildSwitchDescriptor(SootMethod method, SwitchStmt switchStmt, int caseIndex) {
        String location = buildLocationToken(method, (Stmt) switchStmt);
        String caseLabel = resolveCaseLabel(switchStmt, caseIndex);
        return String.format(
                "BRANCH=%s|SWITCH|%s|CASE=%s",
                method.getSignature(),
                location,
                caseLabel
        );
    }

    public static String buildSwitchDefaultDescriptor(SootMethod method, SwitchStmt switchStmt) {
        String location = buildLocationToken(method, (Stmt) switchStmt);
        return String.format(
                "BRANCH=%s|SWITCH|%s|CASE=DEFAULT",
                method.getSignature(),
                location
        );
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

    private static String buildLocationToken(SootMethod method, Stmt stmt) {
        int sourceLine = stmt.getJavaSourceStartLineNumber();
        if (sourceLine > 0) {
            return "L" + sourceLine;
        }

        // fallback: stable hash based on method signature + stmt text
        String stableSeed = method.getSignature() + "|" + normalizeStmt(stmt.toString());
        return "U" + shortSha1(stableSeed);
    }

    private static String normalizeStmt(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String shortSha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) { // 8 hex chars
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
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