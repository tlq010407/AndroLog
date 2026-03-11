import argparse
import os
import re
from collections import defaultdict
import csv

parser = argparse.ArgumentParser(description="Branch Coverage Analysis")

parser.add_argument("--cfg", required=True, help="Path to processing.cfg")
parser.add_argument("--log", required=True, help="Path to runtime log file")
parser.add_argument(
    "--out-dir",
    default=None,
    help="Output directory for uncovered_branches files (default: same directory as --cfg)",
)

args = parser.parse_args()

CFG_FILE = args.cfg
LOG_FILE = args.log
OUT_DIR = args.out_dir

FRAMEWORK_PREFIX = (
    "android.",
    "androidx.",
    "java.",
    "javax.",
    "kotlin.",
    "com.google."
)

# ----------------------------------------
# Parse CFG branches
# ----------------------------------------

def read_cfg(cfg_file):
    branches = set()

    with open(cfg_file, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:

            if line.startswith("BRANCH_IF"):
                branches.add(line.replace("BRANCH_IF ", "").strip())

            if line.startswith("BRANCH_SWITCH"):
                branches.add(line.replace("BRANCH_SWITCH ", "").strip())

    return branches


# ----------------------------------------
# Parse executed branches from log
# ----------------------------------------

def read_logs(log_file):

    executed = set()

    pattern = re.compile(r'BRANCH=(<.*>)')

    with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:

            m = pattern.search(line)

            if m:
                executed.add(m.group(1))

    return executed


# ----------------------------------------
# Filter framework branches
# ----------------------------------------

def is_app_branch(branch):

    cls = branch.split(":")[0].replace("<","")

    for p in FRAMEWORK_PREFIX:
        if cls.startswith(p):
            return False

    return True


# ----------------------------------------
# Class extraction
# ----------------------------------------

def get_class(branch):
    return branch.split(":")[0].replace("<","")


# ----------------------------------------
# Main
# ----------------------------------------

output_dir = OUT_DIR or os.path.dirname(os.path.abspath(CFG_FILE)) or os.getcwd()
os.makedirs(output_dir, exist_ok=True)
txt_output = os.path.join(output_dir, "uncovered_branches.txt")
csv_output = os.path.join(output_dir, "uncovered_branches.csv")

cfg_branches = read_cfg(CFG_FILE)

executed_branches = read_logs(LOG_FILE)

cfg_branches = {b for b in cfg_branches if is_app_branch(b)}
executed_branches = {b for b in executed_branches if is_app_branch(b)}

uncovered = cfg_branches - executed_branches


print("====================================")
print("Branch Coverage Report")
print("====================================")

print("Total CFG branches:", len(cfg_branches))
print("Executed branches :", len(executed_branches))
print("Uncovered branches:", len(uncovered))

coverage = (len(cfg_branches) - len(uncovered)) / len(cfg_branches) * 100

print("Coverage: %.2f%%" % coverage)


# ----------------------------------------
# Class grouping
# ----------------------------------------

class_map = defaultdict(list)

for b in uncovered:

    cls = get_class(b)

    class_map[cls].append(b)


print("\nTop Classes With Uncovered Branches\n")

for cls in sorted(class_map, key=lambda x: len(class_map[x]), reverse=True)[:20]:

    print(len(class_map[cls]), cls)


# ----------------------------------------
# Save uncovered branches
# ----------------------------------------

with open(txt_output, "w", encoding="utf-8") as f:

    for b in sorted(uncovered):

        f.write(b + "\n")


# ----------------------------------------
# CSV output
# ----------------------------------------

with open(csv_output, "w", newline="", encoding="utf-8") as csvfile:

    writer = csv.writer(csvfile)

    writer.writerow(["Class", "Branch"])

    for b in sorted(uncovered):

        writer.writerow([get_class(b), b])


print("\nFiles generated:")
print(txt_output)
print(csv_output)