package com.jordansamhi.androlog;

import soot.Body;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.IfStmt;
import soot.jimple.Stmt;
import soot.jimple.SwitchStmt;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jordansamhi.androspecter.files.LibrariesManager;

public class CfgExporter {
    // Exports CFG and per-branch static semantic features.

    private static final Pattern ANGLE_API_PATTERN = Pattern.compile("<([^<>]+)>");
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s\"'>]+)");

    private final boolean includeLibraries;
    private final String targetPackage;

    public CfgExporter(boolean includeLibraries, String targetPackage) {
        this.includeLibraries = includeLibraries;
        this.targetPackage = targetPackage;
    }

    public void export(String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path featuresPath = (parent != null)
                ? parent.resolve("static_features.jsonl")
                : Paths.get("static_features.jsonl");

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             BufferedWriter featuresWriter = Files.newBufferedWriter(featuresPath, StandardCharsets.UTF_8)) {

            writer.write("# AndroLog Processing CFG\n");
            writer.write("# Format: METHOD/NODE/EDGE/BRANCH_IF/BRANCH_SWITCH\n\n");

            for (SootClass sootClass : Scene.v().getApplicationClasses()) {
                if (!isTargetClass(sootClass)) {
                    continue;
                }

                for (SootMethod method : sootClass.getMethods()) {
                    if (!method.isConcrete()) {
                        continue;
                    }
                    exportMethodCfg(writer, featuresWriter, sootClass, method);
                }
            }
        }
    }

    private boolean isTargetClass(SootClass sootClass) {
        if (!includeLibraries && LibrariesManager.v().isLibrary(sootClass)) {
            return false;
        }
        return targetPackage == null || sootClass.getName().startsWith(targetPackage);
    }

    private void exportMethodCfg(
            BufferedWriter writer,
            BufferedWriter featuresWriter,
            SootClass sootClass,
            SootMethod method
    ) throws IOException {
        Body body;
        try {
            body = method.retrieveActiveBody();
        } catch (Throwable ignored) {
            return;
        }

        UnitGraph graph = new ExceptionalUnitGraph(body);
        List<Unit> units = new ArrayList<>();
        for (Unit unit : body.getUnits()) {
            units.add(unit);
        }

        Map<Unit, Integer> indices = new HashMap<>();
        for (int i = 0; i < units.size(); i++) {
            indices.put(units.get(i), i);
        }

        writer.write("METHOD " + method.getSignature() + "\n");

        for (Unit unit : units) {
            int nodeIndex = indices.get(unit);
            Stmt stmt = (Stmt) unit;
            int lineNumber = stmt.getJavaSourceStartLineNumber();

            writer.write(String.format(
                    "NODE %d L%s %s%n",
                    nodeIndex,
                    lineNumber > 0 ? String.valueOf(lineNumber) : "?",
                    sanitize(stmt.toString())
            ));

            if (stmt instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) stmt;

                String branchTrue = BranchDescriptorUtil.buildIfDescriptor(method, ifStmt, "TRUE");
                String branchFalse = BranchDescriptorUtil.buildIfDescriptor(method, ifStmt, "FALSE");

                writer.write("BRANCH_IF " + branchTrue + "\n");
                writer.write("BRANCH_IF " + branchFalse + "\n");

                writeFeature(featuresWriter, branchTrue, sootClass, method, stmt);
                writeFeature(featuresWriter, branchFalse, sootClass, method, stmt);

            } else if (stmt instanceof SwitchStmt) {
                SwitchStmt switchStmt = (SwitchStmt) stmt;

                for (int i = 0; i < switchStmt.getTargets().size(); i++) {
                    String branchSwitch = BranchDescriptorUtil.buildSwitchDescriptor(method, switchStmt, i);
                    writer.write("BRANCH_SWITCH " + branchSwitch + "\n");
                    writeFeature(featuresWriter, branchSwitch, sootClass, method, stmt);
                }

                String branchDefault = BranchDescriptorUtil.buildSwitchDefaultDescriptor(method, switchStmt);
                writer.write("BRANCH_SWITCH " + branchDefault + "\n");
                writeFeature(featuresWriter, branchDefault, sootClass, method, stmt);
            }

            List<Unit> successors = graph.getSuccsOf(unit);
            for (Unit successor : successors) {
                Integer successorIndex = indices.get(successor);
                if (successorIndex != null) {
                    writer.write(String.format("EDGE %d -> %d%n", nodeIndex, successorIndex));
                }
            }
        }

        writer.write("ENDMETHOD\n\n");
    }

    private void writeFeature(
            BufferedWriter featuresWriter,
            String branchDescriptor,
            SootClass sootClass,
            SootMethod method,
            Stmt stmt
    ) throws IOException {

        ParsedBranchDescriptor parsed = parseBranchDescriptor(branchDescriptor);
        String stmtText = sanitize(stmt.toString());

        List<String> apiCalls = extractApiCalls(stmtText);
        List<String> networkApis = extractNetworkApis(stmtText);
        List<String> fileApis = extractFileApis(stmtText);
        List<String> reflectionApis = extractReflectionApis(stmtText, apiCalls);
        List<String> cryptoApis = extractCryptoApis(stmtText);

        List<String> strings = extractStrings(stmtText);
        List<String> urls = extractUrls(stmtText);
        List<String> commands = extractCommands(stmtText);

        List<String> pathConstraints = buildPathConstraints(parsed);

        boolean loopContext = detectLoopContext(stmtText, apiCalls, method);
        boolean threadContext = detectThreadContext(method);
        boolean asyncContext = detectAsyncContext(method);

        String json = "{"
                + "\"branch_descriptor\":\"" + escapeJson(branchDescriptor) + "\","
                + "\"branch_id\":\"" + escapeJson(parsed.branchId) + "\","
                + "\"branch_kind\":\"" + escapeJson(parsed.branchKind) + "\","
                + "\"branch_location\":\"" + escapeJson(parsed.branchLocation) + "\","
                + "\"edge\":\"" + escapeJson(parsed.edge) + "\","
                + "\"case_label\":\"" + escapeJson(parsed.caseLabel) + "\","
                + "\"condition\":\"" + escapeJson(parsed.condition) + "\","
                + "\"path_constraints\":" + toJsonArray(pathConstraints) + ","

                + "\"stmt_text\":\"" + escapeJson(stmtText) + "\","

                + "\"class\":\"" + escapeJson(sootClass.getName()) + "\","
                + "\"method\":\"" + escapeJson(method.getName()) + "\","
                + "\"method_signature\":\"" + escapeJson(method.getSignature()) + "\","
                + "\"package\":\"" + escapeJson(sootClass.getPackageName()) + "\","

                + "\"component_type\":\"none\","
                + "\"exported\":false,"
                + "\"entrypoint\":false,"

                + "\"api_calls\":" + toJsonArray(apiCalls) + ","
                + "\"network_apis\":" + toJsonArray(networkApis) + ","
                + "\"file_apis\":" + toJsonArray(fileApis) + ","
                + "\"reflection_apis\":" + toJsonArray(reflectionApis) + ","
                + "\"crypto_apis\":" + toJsonArray(cryptoApis) + ","

                + "\"strings\":" + toJsonArray(strings) + ","
                + "\"urls\":" + toJsonArray(urls) + ","
                + "\"commands\":" + toJsonArray(commands) + ","

                + "\"sdk_checks\":[],"
                + "\"environment_checks\":[],"

                + "\"loop_context\":" + loopContext + ","
                + "\"async_context\":" + asyncContext + ","
                + "\"thread_context\":" + threadContext
                + "}";

        featuresWriter.write(json);
        featuresWriter.newLine();
    }

    private ParsedBranchDescriptor parseBranchDescriptor(String descriptor) {
        ParsedBranchDescriptor parsed = new ParsedBranchDescriptor();
        parsed.branchId = stableBranchId(descriptor);
        parsed.branchKind = "";
        parsed.branchLocation = "";
        parsed.edge = "";
        parsed.caseLabel = "";
        parsed.condition = "";

        String[] parts = descriptor.split("\\|");
        if (parts.length >= 3) {
            parsed.branchKind = parts[1];
            parsed.branchLocation = parts[2];
        }

        for (String part : parts) {
            if (part.startsWith("EDGE=")) {
                parsed.edge = part.substring("EDGE=".length());
            } else if (part.startsWith("CASE=")) {
                parsed.caseLabel = part.substring("CASE=".length());
            } else if (part.startsWith("COND=")) {
                parsed.condition = part.substring("COND=".length());
            }
        }

        return parsed;
    }

    private List<String> buildPathConstraints(ParsedBranchDescriptor parsed) {
        List<String> result = new ArrayList<>();
        if (parsed.condition != null && !parsed.condition.isEmpty()) {
            if (parsed.edge != null && !parsed.edge.isEmpty()) {
                result.add("EDGE=" + parsed.edge + ": " + parsed.condition);
            } else {
                result.add(parsed.condition);
            }
        } else if (parsed.caseLabel != null && !parsed.caseLabel.isEmpty()) {
            result.add("CASE=" + parsed.caseLabel);
        }
        return result;
    }

    private String stableBranchId(String descriptor) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(descriptor.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("B");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "B" + Integer.toHexString(descriptor.hashCode());
        }
    }

    private String sanitize(String value) {
        return value == null ? "" : value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static class ParsedBranchDescriptor {
        String branchId;
        String branchKind;
        String branchLocation;
        String edge;
        String caseLabel;
        String condition;
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<String> extractApiCalls(String stmt) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = ANGLE_API_PATTERN.matcher(stmt);
        while (matcher.find()) {
            String sig = matcher.group(1).trim();
            if (!sig.isEmpty()) {
                result.add(sig);
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> extractNetworkApis(String stmt) {
        Set<String> result = new LinkedHashSet<>();

        if (stmt.contains("HttpURLConnection")) result.add("HttpURLConnection");
        if (stmt.contains("Socket")) result.add("Socket");
        if (stmt.contains("OkHttp")) result.add("OkHttp");
        if (stmt.contains("URLConnection")) result.add("URLConnection");
        if (stmt.contains("java.net.URL")) result.add("java.net.URL");

        return new ArrayList<>(result);
    }

    private List<String> extractFileApis(String stmt) {
        Set<String> result = new LinkedHashSet<>();

        if (stmt.contains("FileInputStream")) result.add("FileInputStream");
        if (stmt.contains("FileOutputStream")) result.add("FileOutputStream");
        if (stmt.contains("RandomAccessFile")) result.add("RandomAccessFile");
        if (stmt.contains("java.io.File")) result.add("java.io.File");

        return new ArrayList<>(result);
    }

    private List<String> extractReflectionApis(String stmt, List<String> apiCalls) {
        Set<String> result = new LinkedHashSet<>();

        if (stmt.contains("Class.forName")) result.add("Class.forName");
        if (stmt.contains("java.lang.reflect.Method")) result.add("java.lang.reflect.Method");
        if (stmt.contains("java.lang.reflect.Field")) result.add("java.lang.reflect.Field");
        if (stmt.contains("java.lang.reflect.Constructor")) result.add("java.lang.reflect.Constructor");
        if (stmt.contains("getDeclaredMethod")) result.add("getDeclaredMethod");
        if (stmt.contains("getDeclaredField")) result.add("getDeclaredField");
        if (stmt.contains("getMethod(") || stmt.contains(" getMethod")) result.add("getMethod");
        if (stmt.contains("getField(") || stmt.contains(" getField")) result.add("getField");

        for (String api : apiCalls) {
            if (api.contains("java.lang.reflect.Method:") && api.contains(" invoke(")) {
                result.add("Method.invoke");
            }
            if (api.contains("java.lang.reflect.Field:") && api.contains(" get(")) {
                result.add("Field.get");
            }
            if (api.contains("java.lang.reflect.Field:") && api.contains(" set(")) {
                result.add("Field.set");
            }
            if (api.contains("java.lang.Class:") && api.contains(" forName(")) {
                result.add("Class.forName");
            }
        }

        return new ArrayList<>(result);
    }

    private List<String> extractCryptoApis(String stmt) {
        Set<String> result = new LinkedHashSet<>();

        if (stmt.contains("Cipher")) result.add("Cipher");
        if (stmt.contains("SecretKey")) result.add("SecretKey");
        if (stmt.contains("MessageDigest")) result.add("MessageDigest");
        if (stmt.contains("Mac")) result.add("Mac");

        return new ArrayList<>(result);
    }

    private List<String> extractStrings(String stmt) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = STRING_LITERAL_PATTERN.matcher(stmt);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return new ArrayList<>(result);
    }

    private List<String> extractUrls(String stmt) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(stmt);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return new ArrayList<>(result);
    }

    private List<String> extractCommands(String stmt) {
        Set<String> result = new LinkedHashSet<>();

        if (stmt.contains("Runtime.getRuntime")) result.add("Runtime.getRuntime");
        if (stmt.contains(".exec(") || stmt.contains(" exec(")) result.add("exec");
        if (stmt.contains("ProcessBuilder")) result.add("ProcessBuilder");

        return new ArrayList<>(result);
    }

    private boolean detectThreadContext(SootMethod method) {
        return "run".equals(method.getName());
    }

    private boolean detectAsyncContext(SootMethod method) {
        String name = method.getName().toLowerCase();
        return name.contains("async")
                || name.contains("background")
                || name.contains("worker")
                || name.contains("doinbackground");
    }

    private boolean detectLoopContext(String stmtText, List<String> apiCalls, SootMethod method) {
        String lowerStmt = stmtText.toLowerCase();

        if (lowerStmt.contains("hasnext")) {
            return true;
        }

        for (String api : apiCalls) {
            String lowerApi = api.toLowerCase();
            if (lowerApi.contains("java.util.iterator")
                    || lowerApi.contains(" iterator(")
                    || lowerApi.contains(" hasnext(")
                    || lowerApi.contains(" next(")) {
                return true;
            }
        }

        try {
            Body body = method.retrieveActiveBody();
            boolean seenIterator = false;
            boolean seenHasNextOrNext = false;

            for (Unit unit : body.getUnits()) {
                String u = sanitize(unit.toString()).toLowerCase();
                if (u.contains("iterator()") || u.contains("java.util.iterator")) {
                    seenIterator = true;
                }
                if (u.contains("hasnext()") || u.contains("next()")) {
                    seenHasNextOrNext = true;
                }
                if (seenIterator && seenHasNextOrNext) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }
}