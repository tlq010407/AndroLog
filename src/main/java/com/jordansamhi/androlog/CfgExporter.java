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

/**
 * Exports method-level processing CFG information as a plain .cfg file.
 */
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

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
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
                    exportMethodCfg(writer, method);
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

    private void exportMethodCfg(BufferedWriter writer, SootMethod method) throws IOException {
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
            writer.write(String.format("NODE %d L%s %s%n",
                    nodeIndex,
                    lineNumber > 0 ? String.valueOf(lineNumber) : "?",
                    sanitize(stmt.toString())));

            if (stmt instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) stmt;
                writer.write("BRANCH_IF " + BranchDescriptorUtil.buildIfDescriptor(method, ifStmt, "TRUE") + "\n");
                writer.write("BRANCH_IF " + BranchDescriptorUtil.buildIfDescriptor(method, ifStmt, "FALSE") + "\n");
            } else if (stmt instanceof SwitchStmt) {
                SwitchStmt switchStmt = (SwitchStmt) stmt;
                for (int i = 0; i < switchStmt.getTargets().size(); i++) {
                    writer.write("BRANCH_SWITCH " + BranchDescriptorUtil.buildSwitchDescriptor(method, switchStmt, i) + "\n");
                }
                writer.write("BRANCH_SWITCH " + BranchDescriptorUtil.buildSwitchDefaultDescriptor(method, switchStmt) + "\n");
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

    private String sanitize(String value) {
        return value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
    }
}
