package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.jimple.infoflow.collect.ConcurrentHashSet;
import soot.util.Chain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SummaryBuilder is a singleton class that aggregates information about various program components
 * using the Soot framework. It tracks details about methods, classes, Android components (like Activities, Services),
 * and Jimple statements within the application being analyzed.
 */
public class SummaryBuilder {
    private static SummaryBuilder instance;
    private SootUtils su;
    private final Map<String, Integer> summary = new ConcurrentHashMap<>();
    private final Set<String> visitedComponents = new ConcurrentHashSet<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private SummaryBuilder() {
    }

    /**
     * Returns the singleton instance of SummaryBuilder.
     *
     * @return The singleton instance of SummaryBuilder.
     */
    public static SummaryBuilder v() {
        if (instance == null) {
            instance = new SummaryBuilder();
        }
        return instance;
    }

    /**
     * Sets the SootUtils instance for use in the summary building process.
     *
     * @param su The SootUtils instance to set.
     */
    public void setSootUtils(SootUtils su) {
        this.su = su;
    }

    /**
     * Adds a transformation phase to the Soot PackManager.
     *
     * @param phaseName The name of the transformation phase.
     * @param logic     The logic to be applied in this transformation phase.
     */
    private void addTransformation(String phaseName, Consumer<Body> logic) {
        PackManager.v().getPack("jtp").add(new Transform(phaseName, new BodyTransformer() {
            @Override
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                logic.accept(b);
            }
        }));
    }

    /**
     * Increments the count for a given key in the summary map.
     *
     * @param key The key to increment in the summary.
     */
    private synchronized void increment(String key) {
        summary.merge(key, 1, Integer::sum);
    }

    /**
     * Increments the component count in the summary if it has not been visited.
     *
     * @param type      The type of the component.
     * @param component The name of the component.
     */
    private synchronized void incrementComponent(String type, String component) {
        if (visitedComponents.add(type + component)) {
            increment(type);
        }
    }

    /**
     * Adds a transformation to gather method information.
     */
    private void getInfoMethods(boolean includeLibraries) {
        addTransformation("jtp.methods", b -> {
            if (isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            incrementComponent("methods", b.getMethod().getSignature());
        });
    }

    /**
     * Adds a transformation to gather class information.
     */
    private void getInfoClasses(boolean includeLibraries) {
        addTransformation("jtp.classes", b -> {
            if (isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            incrementComponent("classes", b.getMethod().getDeclaringClass().getName());
        });
    }

    private boolean isLogCheckerClass(SootMethod sm) {
        String className = sm.getDeclaringClass().getName();
        return className.equals(Constants.LOG_CHECKER_CLASS);
    }

    /**
     * Adds a transformation to gather information on Android components like Activities, Services, etc.
     */
    private void getInfoComponents(boolean includeLibraries) {
        addTransformation("jtp.components", b -> {
            SootClass sc = b.getMethod().getDeclaringClass();
            if (!includeLibraries && LibrariesManager.v().isLibrary(sc)) {
                return;
            }
            String actualComponentType = su.getComponentType(sc);
            switch (actualComponentType) {
                case "Activity":
                    incrementComponent("activities", sc.getName());
                    break;
                case "Service":
                    incrementComponent("services", sc.getName());
                    break;
                case "BroadcastReceiver":
                    incrementComponent("broadcast-receivers", sc.getName());
                    break;
                case "ContentProvider":
                    incrementComponent("content-providers", sc.getName());
                    break;
            }
        });
    }

    /**
     * Adds a transformation to gather statement information.
     */
    private void getInfoStatements(boolean includeLibraries) {
        addTransformation("jtp.statements", b -> {
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            int cnt = 0;
            for (Unit u : units) {
                cnt++;
                Stmt stmt = (Stmt) u;
                if (stmt instanceof IdentityStmt) {
                    continue;
                }
                if (stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
                    continue;
                }
                if (stmt instanceof MonitorStmt) {
                    continue;
                }
                String stmt_log = String.format("STATEMENT=%s|%s|%d", b.getMethod(), stmt, cnt);
                incrementComponent("statements", stmt_log);
            }
        });
    }

    /**
     * Adds a transformation to gather branch information (if and switch statements).
     */
    private void getInfoBranches(boolean includeLibraries) {
        addTransformation("jtp.branches", b -> {
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            for (Unit u : units) {
                Stmt stmt = (Stmt) u;
                if (stmt instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) stmt;
                    incrementComponent("branches", BranchDescriptorUtil.buildIfDescriptor(b.getMethod(), ifStmt, "TRUE"));
                    incrementComponent("branches", BranchDescriptorUtil.buildIfDescriptor(b.getMethod(), ifStmt, "FALSE"));
                } else if (stmt instanceof SwitchStmt) {
                    SwitchStmt switchStmt = (SwitchStmt) stmt;
                    for (int i = 0; i < switchStmt.getTargets().size(); i++) {
                        String branch_log = BranchDescriptorUtil.buildSwitchDescriptor(b.getMethod(), switchStmt, i);
                        incrementComponent("branches", branch_log);
                    }
                    incrementComponent("branches", BranchDescriptorUtil.buildSwitchDefaultDescriptor(b.getMethod(), switchStmt));
                }
            }
        });
    }

    /**
     * Executes all transformation phases to build the summary.
     */
    public void build(boolean includeLibraries) {
        build(includeLibraries, false);
    }

    public void build(boolean includeLibraries, boolean noBodiesMode) {
        if (noBodiesMode) {
            buildSafeWithoutJtp(includeLibraries);
            return;
        }

        if (CommandLineOptions.v().hasOption("c")) {
            getInfoClasses(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("cp")) {
            getInfoComponents(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("m")) {
            getInfoMethods(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("s")) {
            getInfoStatements(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("b")) {
            getInfoBranches(includeLibraries);
        }
        PackManager.v().runPacks();
    }

    private void buildSafeWithoutJtp(boolean includeLibraries) {
        Chain<SootClass> appClasses = Scene.v().getApplicationClasses();

        for (SootClass sootClass : appClasses) {
            if (!includeLibraries && LibrariesManager.v().isLibrary(sootClass)) {
                continue;
            }

            if (CommandLineOptions.v().hasOption("c")) {
                incrementComponent("classes", sootClass.getName());
            }

            if (CommandLineOptions.v().hasOption("cp")) {
                String actualComponentType = su.getComponentType(sootClass);
                switch (actualComponentType) {
                    case "Activity":
                        incrementComponent("activities", sootClass.getName());
                        break;
                    case "Service":
                        incrementComponent("services", sootClass.getName());
                        break;
                    case "BroadcastReceiver":
                        incrementComponent("broadcast-receivers", sootClass.getName());
                        break;
                    case "ContentProvider":
                        incrementComponent("content-providers", sootClass.getName());
                        break;
                }
            }

            List<SootMethod> methods = sootClass.getMethods();
            for (SootMethod method : methods) {
                if (isLogCheckerClass(method)) {
                    continue;
                }

                if (CommandLineOptions.v().hasOption("m")) {
                    incrementComponent("methods", method.getSignature());
                }

                if ((!CommandLineOptions.v().hasOption("s") && !CommandLineOptions.v().hasOption("b"))
                        || !method.isConcrete()) {
                    continue;
                }

                try {
                    Body body = method.retrieveActiveBody();

                    if (CommandLineOptions.v().hasOption("s")) {
                        int cnt = 0;
                        for (Unit unit : body.getUnits()) {
                            cnt++;
                            Stmt stmt = (Stmt) unit;
                            if (stmt instanceof IdentityStmt || stmt instanceof ReturnStmt
                                    || stmt instanceof ReturnVoidStmt || stmt instanceof MonitorStmt) {
                                continue;
                            }
                            String stmtLog = String.format("STATEMENT=%s|%s|%d", method, stmt, cnt);
                            incrementComponent("statements", stmtLog);
                        }
                    }

                    if (CommandLineOptions.v().hasOption("b")) {
                        for (Unit unit : body.getUnits()) {
                            Stmt stmt = (Stmt) unit;
                            if (stmt instanceof IfStmt) {
                                IfStmt ifStmt = (IfStmt) stmt;
                                incrementComponent("branches", BranchDescriptorUtil.buildIfDescriptor(method, ifStmt, "TRUE"));
                                incrementComponent("branches", BranchDescriptorUtil.buildIfDescriptor(method, ifStmt, "FALSE"));
                            } else if (stmt instanceof SwitchStmt) {
                                SwitchStmt switchStmt = (SwitchStmt) stmt;
                                int numTargets = switchStmt.getTargets().size();
                                for (int i = 0; i < numTargets; i++) {
                                    String branchLog = BranchDescriptorUtil.buildSwitchDescriptor(method, switchStmt, i);
                                    incrementComponent("branches", branchLog);
                                }
                                incrementComponent("branches", BranchDescriptorUtil.buildSwitchDefaultDescriptor(method, switchStmt));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Retrieves the generated summary of components.
     *
     * @return A map representing the summary of components.
     */
    public Map<String, Integer> getSummary() {
        return summary;
    }

    public Set<String> getVisitedComponents() { return visitedComponents; }
}