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

                writeFeature(featuresWriter, branchTrue, sootClass, method);
                writeFeature(featuresWriter, branchFalse, sootClass, method);

            } else if (stmt instanceof SwitchStmt) {
                SwitchStmt switchStmt = (SwitchStmt) stmt;

                for (int i = 0; i < switchStmt.getTargets().size(); i++) {
                    String branchSwitch = BranchDescriptorUtil.buildSwitchDescriptor(method, switchStmt, i);
                    writer.write("BRANCH_SWITCH " + branchSwitch + "\n");
                    writeFeature(featuresWriter, branchSwitch, sootClass, method);
                }

                String branchDefault = BranchDescriptorUtil.buildSwitchDefaultDescriptor(method, switchStmt);
                writer.write("BRANCH_SWITCH " + branchDefault + "\n");
                writeFeature(featuresWriter, branchDefault, sootClass, method);
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
            SootMethod method
    ) throws IOException {
        ParsedBranchDescriptor parsed = parseBranchDescriptor(branchDescriptor);

        String json = "{"
                + "\"branch_descriptor\":\"" + escapeJson(branchDescriptor) + "\","
                + "\"branch_uid\":\"" + escapeJson(parsed.branchUid) + "\","
                + "\"branch_kind\":\"" + escapeJson(parsed.branchKind) + "\","
                + "\"branch_location\":\"" + escapeJson(parsed.branchLocation) + "\","
                + "\"edge\":\"" + escapeJson(parsed.edge) + "\","
                + "\"case_label\":\"" + escapeJson(parsed.caseLabel) + "\","
                + "\"condition\":\"" + escapeJson(parsed.condition) + "\","
                + "\"class\":\"" + escapeJson(sootClass.getName()) + "\","
                + "\"method\":\"" + escapeJson(method.getName()) + "\","
                + "\"method_signature\":\"" + escapeJson(method.getSignature()) + "\","
                + "\"package\":\"" + escapeJson(sootClass.getPackageName()) + "\","
                + "\"component_type\":\"none\","
                + "\"exported\":false,"
                + "\"entrypoint\":false,"
                + "\"intent_actions\":[],"
                + "\"intent_categories\":[],"
                + "\"intent_data\":[],"
                + "\"permissions_required\":[],"
                + "\"api_calls\":[],"
                + "\"sensitive_apis\":[],"
                + "\"network_apis\":[],"
                + "\"file_apis\":[],"
                + "\"reflection_apis\":[],"
                + "\"crypto_apis\":[],"
                + "\"ad_sdk\":false,"
                + "\"third_party_sdk\":[],"
                + "\"strings\":[],"
                + "\"urls\":[],"
                + "\"commands\":[],"
                + "\"path_constraints\":[],"
                + "\"sdk_checks\":[],"
                + "\"environment_checks\":[],"
                + "\"loop_context\":false,"
                + "\"async_context\":false,"
                + "\"thread_context\":false"
                + "}";

        featuresWriter.write(json);
        featuresWriter.newLine();
    }

    private ParsedBranchDescriptor parseBranchDescriptor(String descriptor) {
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
        return value
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
        String branchUid;
        String branchKind;
        String branchLocation;
        String edge;
        String caseLabel;
        String condition;
    }
}