"""Re-enable all third-party apps frozen by freeze_apps.py."""
import subprocess

ADB = r"sdk\platform-tools-src\platform-tools-latest-windows\adb.exe"

def adb(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True, text=True, timeout=30)

out = adb("pm", "list", "packages", "-3", "-d").stdout  # disabled packages
pkgs = []
for line in out.splitlines():
    line = line.strip()
    if line.startswith("package:"):
        pkgs.append(line.split(":",1)[1].strip())

n = 0
for p in pkgs:
    r = adb("pm", "enable", p)
    n += 1
    print("  restored", p)
print("\nRestored %d apps." % n)