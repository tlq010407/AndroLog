# Cold Path Pipeline Summary

## Why We Do This
We do this to improve Android test effectiveness by targeting what has not been exercised yet.

Instead of random/manual exploration only, this pipeline finds uncovered branches and generates actionable probe tasks so testing can focus on cold paths.

Key goals:
- Increase branch coverage faster.
- Prioritize high-value untested logic.
- Turn static + runtime evidence into concrete test/probe actions.
- Build a repeatable workflow for future APKs.

## End-to-End Flow
1. Run app tests and collect runtime branch logs.
2. Use `branch_coverage_analysis.py` to compute uncovered branches.
3. Use `probe_gen.py` to enrich uncovered branches with bytecode context and generate probe prompts.
4. Execute probes (manually or with LLM-assisted command generation), then rerun tests and iterate.

## Script 1: `branch_coverage_analysis.py`

### What It Does
Compares:
- static branches from `processing.cfg`
- executed branches from runtime logs

Then reports coverage and exports uncovered branches.

### Inputs
- `--cfg`: path to `processing.cfg`
- `--log`: path to runtime log file containing `BRANCH=<...>`
- `--out-dir` (optional): output directory
  - default: same directory as `--cfg`

### Core Workflow
1. Parse CFG branch definitions:
- `BRANCH_IF ...`
- `BRANCH_SWITCH ...`
2. Parse executed `BRANCH=<...>` entries from logs.
3. Filter framework classes (`android.*`, `androidx.*`, `java.*`, `javax.*`, `kotlin.*`, `com.google.*`).
4. Compute uncovered set:
- `uncovered = cfg_branches - executed_branches`
5. Print report:
- total branches
- executed branches
- uncovered branches
- coverage percentage
6. Group uncovered branches by class and print top classes.
7. Export uncovered list.

### Outputs
Written to `--out-dir` (or `--cfg` directory):
- `uncovered_branches.txt`
- `uncovered_branches.csv` (columns: `Class`, `Branch`)

### Typical Command
```powershell
python .\branch_coverage_analysis.py \
  --cfg .\fse-dataset\instrumented_apk\timeplanner_manual_touchcheck_20260309\processing.cfg \
  --log .\fse-dataset\instrumented_apk\timeplanner_manual_touchcheck_20260309\runtime_log.txt
```

## Script 2: `probe_gen.py`

### What It Does
Generates LLM-ready probe prompts from uncovered branches by attaching static bytecode context from APK methods (via AndroGuard).

Latest behavior: generated prompts now instruct the LLM to return strict JSON test cases (not free-form text), so outputs are easier to parse and automate.

### Inputs
- `--apk`: APK path
- `--uncovered`: uncovered file (`.txt` or `.csv`)
- `--top`: how many uncovered branches to process
- `--max-instructions`: max method instructions included per prompt
- `--out-md`: markdown output file name/path
- `--out-jsonl`: jsonl output file name/path
- `--out-dir` (optional): output directory override
  - default: same directory as `--apk`

### Core Workflow
1. Parse uncovered branch records into structured fields.
2. Analyze APK with AndroGuard (`AnalyzeAPK`).
3. Build app-method index and match branch class/method to method bodies.
4. Extract bytecode instructions for context.
5. Infer simple SDK hints from branch condition tokens when possible (for example, FALSE edge on `< 22` implies `SDK >= 22`).
6. Generate per-branch probe prompts with strict JSON output requirements.
6. Export markdown + jsonl artifacts.

### Outputs
Written to `--out-dir` (or `--apk` directory):
- Markdown prompts (default: `cold_path_prompts.md`)
- JSONL prompts (default: `cold_path_prompts.jsonl`)

Each JSONL line includes:
- branch metadata
- method descriptor
- instruction list
- generated prompt text

Prompt-level JSON schema requirement (asked from target LLM):
- `test_id`
- `type` (`adb_probe|automation_draft`)
- `target_uid`
- `target_edge`
- `requires_sdk_min`
- `preconditions`
- `commands`
- `expected_signal`
- `verification_steps`
- `coverage_verification`
- `risk`

Coverage hard-check requirement in generated prompts:
- re-run branch log collection after probe
- re-run `branch_coverage_analysis.py`
- verify target `UID + EDGE` is no longer uncovered (or explain inconclusive evidence)

### Typical Command (Top 50)
```powershell
python .\probe_gen.py \
  --apk .\fse-dataset\Time_Planner_Schedule.apk \
  --uncovered .\uncovered_branches.csv \
  --top 50 \
  --out-md cold_path_prompts_top50.md \
  --out-jsonl cold_path_prompts_top50.jsonl
```

## Practical Notes
- Run `branch_coverage_analysis.py` first, then `probe_gen.py`.
- Keep outputs next to instrumented artifacts for easier traceability.
- Iterate: run probes, collect new logs, recompute uncovered branches, regenerate prompts.
- For best LLM quality, feed one cold branch block (or one JSONL record) at a time.
- Fill app context when sending prompts to LLM: package name, main activity, device/Android version, and allowed adb command scope.
