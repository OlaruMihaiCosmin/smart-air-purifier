"""Ruleaza toate testele native fara acces la retea; emulatorul se verifica separat."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

folder = Path(__file__).resolve().parent
compiler = shutil.which("g++")
if not compiler:
    raise SystemExit("Este necesar g++ in PATH, inclusiv directorul bibliotecilor sale DLL")
failed = []
with tempfile.TemporaryDirectory(prefix="smartair-policies-") as tmp:
    binary = Path(tmp) / "policies.exe"
    result = subprocess.run([compiler,"-std=c++17","-Wall","-Wextra","-I",str(folder.parents[1]),
                             str(folder/"reliability_test.cpp"),"-o",str(binary)])
    if result.returncode or subprocess.run([str(binary)]).returncode:
        failed.append("reliability_test.cpp")
for name in ("history", "settings", "timer", "night_mode", "journal", "offset", "warmup", "calibration", "cloud", "diagnostic", "clock_display",
             "history_integration", "journal_integration"):
    script = folder / ("run_" + name + "_tests.py")
    command = [sys.executable, str(script)]
    if name == "history_integration":
        command.append("--mutations")
    print(script.name, flush=True)
    if subprocess.run(command).returncode:
        failed.append(script.name)
print("Suite esuate: " + (", ".join(failed) if failed else "niciuna"), flush=True)
raise SystemExit(bool(failed))
