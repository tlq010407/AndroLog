package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.files.LibrariesManager;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CfgExporter {
    // CfgExporter: Exports control flow graphs and static features for methods in application classes

    private final boolean includeLibraries;
    private final String targetPackage;

    public CfgExporter(boolean includeLibraries, String targetPackage) {
        this.includeLibraries = includeLibraries;
        this.targetPackage = targetPackage;
    }

    public void export(String outputPath) throws IOException {
            // Main export method: writes CFG and static features to files
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
            // Checks if the class should be included based on library and package filters
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
        // Export CFG for a single method
        Body body;
        try {
            body = method.retrieveActiveBody();
        } catch (Throwable ignored) {
            // Skip methods that cannot retrieve body
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
            // Write node info for CFG

            writer.write(String.format(
                    "NODE %d L%s %s%n",
                    nodeIndex,
                    lineNumber > 0 ? String.valueOf(lineNumber) : "?",
                    sanitize(stmt.toString())
            ));

            // Handle branch statements (if/switch) and write features
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

            // Write edges for CFG
            List<Unit> successors = graph.getSuccsOf(unit);
            for (Unit successor : successors) {
                Integer successorIndex = indices.get(successor);
                if (successorIndex != null) {
                    writer.write(String.format("EDGE %d -> %d%n", nodeIndex, successorIndex));
                }
            }
        }

        writer.write("ENDMETHOD\n\n");
        // End of method CFG
    }

        private void writeFeature(
                BufferedWriter featuresWriter,
                String branchDescriptor,
                SootClass sootClass,
                SootMethod method,
                Stmt stmt
        ) throws IOException {

            // Write static features for a branch descriptor and statement
            ParsedBranchDescriptor parsed = parseBranchDescriptor(branchDescriptor);

            String stmtText = sanitize(stmt.toString());

            // Extract various features from statement text
            List<String> apiCalls = extractApiCalls(stmtText);
            List<String> networkApis = extractNetworkApis(stmtText);
            List<String> fileApis = extractFileApis(stmtText);
            List<String> reflectionApis = extractReflectionApis(stmtText);
            List<String> cryptoApis = extractCryptoApis(stmtText);

            List<String> strings = extractStrings(stmtText);
            List<String> urls = extractUrls(stmtText);
            List<String> commands = extractCommands(stmtText);

            boolean loopContext = detectLoopContext(stmtText);
            boolean threadContext = detectThreadContext(method);
            boolean asyncContext = detectAsyncContext(method);

            // Build JSON feature object
            String json = "{" 
                + "\"branch_descriptor\":\"" + escapeJson(branchDescriptor) + "\"," 
                + "\"branch_uid\":\"" + escapeJson(parsed.branchUid) + "\"," 
                + "\"branch_kind\":\"" + escapeJson(parsed.branchKind) + "\"," 
                + "\"branch_location\":\"" + escapeJson(parsed.branchLocation) + "\"," 
                + "\"edge\":\"" + escapeJson(parsed.edge) + "\"," 
                + "\"case_label\":\"" + escapeJson(parsed.caseLabel) + "\"," 
                + "\"condition\":\"" + escapeJson(parsed.condition) + "\"," 

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

                + "\"path_constraints\":[]," 

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
            // Parse branch descriptor string into structured fields
        ParsedBranchDescriptor parsed = new ParsedBranchDescriptor();
        parsed.branchUid = descriptor;
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

    private String sanitize(String value) {
            // Remove whitespace and control characters from string
        return value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private String escapeJson(String value) {
            // Escape JSON special characters
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static class ParsedBranchDescriptor {
            // Structure for parsed branch descriptor fields
        String branchUid;
        String branchKind;
        String branchLocation;
        String edge;
        String caseLabel;
        String condition;
    }

    private String toJsonArray(List<String> values) {
            // Convert list of strings to JSON array
        if (values.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<String> extractApiCalls(String stmt) {
            // Extract API call signatures from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("<") && stmt.contains(">")) {
            int start = stmt.indexOf("<");
            int end = stmt.indexOf(">");
            if (start >= 0 && end > start) {
                result.add(stmt.substring(start + 1, end));
            }
        }

        return result;
    }

    private List<String> extractNetworkApis(String stmt) {
            // Extract network API usages from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("HttpURLConnection")) result.add("HttpURLConnection");
        if (stmt.contains("Socket")) result.add("Socket");
        if (stmt.contains("OkHttp")) result.add("OkHttp");
        if (stmt.contains("URLConnection")) result.add("URLConnection");

        return result;
    }

    private List<String> extractFileApis(String stmt) {
            // Extract file API usages from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("FileInputStream")) result.add("FileInputStream");
        if (stmt.contains("FileOutputStream")) result.add("FileOutputStream");
        if (stmt.contains("RandomAccessFile")) result.add("RandomAccessFile");

        return result;
    }

    private List<String> extractReflectionApis(String stmt) {
            // Extract reflection API usages from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("Class.forName")) result.add("Class.forName");
        if (stmt.contains("getMethod")) result.add("getMethod");
        if (stmt.contains("invoke")) result.add("invoke");

        return result;
    }

    private List<String> extractCryptoApis(String stmt) {
            // Extract crypto API usages from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("Cipher")) result.add("Cipher");
        if (stmt.contains("SecretKey")) result.add("SecretKey");
        if (stmt.contains("MessageDigest")) result.add("MessageDigest");

        return result;
    }

    private List<String> extractStrings(String stmt) {
            // Extract string literals from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("\"")) {
            result.add(stmt);
        }

        return result;
    }

    private List<String> extractUrls(String stmt) {
            // Extract URLs from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("http://") || stmt.contains("https://")) {
            result.add("url");
        }

        return result;
    }

    private List<String> extractCommands(String stmt) {
            // Extract command execution patterns from statement
        List<String> result = new ArrayList<>();

        if (stmt.contains("Runtime.getRuntime")) result.add("runtime_exec");
        if (stmt.contains("exec(")) result.add("exec");

        return result;
    }

    private boolean detectThreadContext(SootMethod method) {
            // Detect if method is a thread context (e.g., run())
        return method.getName().equals("run");
    }

    private boolean detectAsyncContext(SootMethod method) {
            // Detect if method is asynchronous context
        return method.getName().contains("async")
                || method.getName().contains("background");
    }

    private boolean detectLoopContext(String stmt) {
            // Detect if statement is in a loop context
        return stmt.contains("goto")
                || stmt.contains("hasNext");
    }
}