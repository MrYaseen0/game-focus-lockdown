"""Deep-freeze every third-party app except PUBG/COD (and Gamer Pro) via ADB.
Run: python .sharrowkin/scripts/freeze_apps.py
Re-enable with unfreeze_apps.py
"""
import subprocess, sys

ADB = r"sdk\platform-tools-src\platform-tools-latest-windows\adb.exe"
KEEP = {
    "com.gamefocus.locker",
    "com.tencent.ig", "com.pubg.imobile", "com.vng.pubgmobile",
    "com.rekoo.pubgm", "com.activision.callofduty.shooter",
    "com.garena.game.codm", "com.tencent.tmgp.kr.callofduty",
}

def adb(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True, text=True, timeout=30)

# third-party packages only (never touch system apps)
out = adb("pm", "list", "packages", "-3").stdout
pkgs = []
for line in out.splitlines():
    line = line.strip()
    if line.startswith("package:"):
        pkgs.append(line.split(":",1)[1].strip())

frozen = 0
for p in pkgs:
    if p in KEEP:
        print("  keep   ", p); continue
    r = adb("pm", "disable-user", "--user", "0", p)
    if "disabled" in (r.stdout + r.stderr).lower() or r.returncode == 0:
        frozen += 1
        print("  frozen ", p)
    else:
        print("  skip   ", p, r.stderr.strip()[:60])

print("\nFrozen %d third-party apps. Run unfreeze_apps.py to restore." % frozen)