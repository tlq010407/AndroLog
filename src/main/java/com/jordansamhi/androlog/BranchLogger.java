package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.Map;
import java.util.function.Consumer;

/**
 * BranchLogger is responsible for instrumenting branch statements (if and switch)
 * in the code to track branch coverage during execution.
 *
 * @author Liqi Tang
 */
public class BranchLogger {
    private static BranchLogger instance;
    private String targetPackage;

    /**
     * Private constructor to prevent external instantiation.
     */
    private BranchLogger() {
    }

    /**
     * Returns the singleton instance of BranchLogger.
     *
     * @return the singleton instance of BranchLogger.
     */
    public static BranchLogger v() {
        if (instance == null) {
            instance = new BranchLogger();
        }
        return instance;
    }

    /**
     * Sets the target package name for filtering which classes should be instrumented.
     *
     * @param targetPackage The package name to target for instrumentation.
     */
    public void setTargetPackage(String targetPackage) {
        this.targetPackage = targetPackage;
    }

    /**
     * Checks if the method's declaring class is within the target package.
     *
     * @param sm The SootMethod instance.
     * @return true if the class is within the target package, false otherwise.
     */
    private boolean isTargetPackage(SootMethod sm) {
        if (targetPackage == null) {
            return true;
        }
        return sm.getDeclaringClass().getName().startsWith(targetPackage);
    }

    /**
     * Adds a transformation to the Jimple Transformation Pack (jtp) for a specific phase.
     *
     * @param phaseName The name of the phase during which this transformation should be applied.
     * @param logic     The logic to execute during the transformation, encapsulated as a Consumer of Body.
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
     * Adds log statements for all branch instructions (if and switch statements).
     *
     * @param tagToLog         The tag to be used in the log statements.
     * @param includeLibraries A boolean flag indicating whether library methods should be included in the logging.
     *                         If true, log statements will be added to both application and library methods.
     *                         If false, only application methods will be logged.
     */
    public void logAllBranches(String tagToLog, boolean includeLibraries) {
        addTransformation("jtp.branches", b -> {
            if (isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!isTargetPackage(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }

            Chain<Unit> units = b.getUnits();
            int branchCounter = 0;

            // Create array from units to avoid concurrent modification
            Unit[] unitsArray = units.toArray(new Unit[0]);

            for (Unit u : unitsArray) {
                Stmt stmt = (Stmt) u;

                if (stmt instanceof IfStmt) {
                    branchCounter++;
                    String branchLog = String.format("BRANCH=%s|IF|%d", b.getMethod().getSignature(), branchCounter);
                    insertLogStatement(units, stmt, tagToLog, branchLog, b);

                } else if (stmt instanceof SwitchStmt) {
                    SwitchStmt switchStmt = (SwitchStmt) stmt;
                    int numTargets = switchStmt.getTargets().size();
                    
                    // Log each case
                    for (int i = 0; i < numTargets; i++) {
                        branchCounter++;
                        String branchLog = String.format("BRANCH=%s|SWITCH|%d", b.getMethod().getSignature(), branchCounter);
                        insertLogStatement(units, stmt, tagToLog, branchLog, b);
                    }
                }
            }
        });
    }

    /**
     * Inserts a log statement after a stmt.
     *
     * @param units    The chain of units.
     * @param stmt     The statement after which log should be inserted.
     * @param tagToLog The log tag.
     * @param message  The log message.
     * @param b        The method body.
     */
    private void insertLogStatement(Chain<Unit> units, Unit stmt, String tagToLog, String message, Body b) {
        // Find the next unit
        Unit nextUnit = units.getSuccOf(stmt);
        if (nextUnit == null) {
            return;
        }

        try {
            Jimple jimple = Jimple.v();

            // Create Log.d() call
            SootClass logClass = Scene.v().getSootClass("android.util.Log");
            SootMethod logMethod = logClass.getMethod("int d(java.lang.String,java.lang.String)");

            // Create arguments
            java.util.List<Value> args = new java.util.ArrayList<>();
            args.add(StringConstant.v(tagToLog));
            args.add(StringConstant.v(message));

            // Create invoke statement
            InvokeExpr expr = jimple.newStaticInvokeExpr(logMethod.makeRef(), args);
            InvokeStmt invoke = jimple.newInvokeStmt(expr);

            // Insert before next unit
            units.insertBefore(invoke, nextUnit);
        } catch (Exception e) {
            // Silently fail if Log class unavailable
        }
    }

    /**
     * Checks if the given method belongs to the log checker class.
     *
     * @param sm The SootMethod instance to be checked.
     * @return true if the method's declaring class is the log checker class, false otherwise.
     */
    private boolean isLogCheckerClass(SootMethod sm) {
        String className = sm.getDeclaringClass().getName();
        return className.equals(Constants.LOG_CHECKER_CLASS);
    }
}
