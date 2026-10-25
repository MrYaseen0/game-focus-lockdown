"""Disable heavy non-essential system apps to free RAM for gaming."""
import subprocess

ADB = r"sdk\platform-tools-src\platform-tools-latest-windows\adb.exe"

# Heavy apps safe to disable for gaming (not core system)
HEAVY = [
    "com.google.android.youtube",
    "com.google.android.googlequicksearchbox",
    "com.android.vending",
    "com.samsung.android.app.routines",
    "com.samsung.android.app.routines:RoutineUIProcess",
    "com.samsung.android.game.gametools",
    "com.samsung.android.wallpaper.live",
    "com.samsung.android.widget.pictureframe",
    "com.samsung.android.rubin.app",
    "com.sec.android.app.samsungapps",
    "com.google.android.as",
    "com.google.android.ext.services",
    "com.google.android.providers.media.module",
    "com.samsung.android.samsungpass",
    "com.samsung.android.app.contacts",
    "com.google.android.webview",
    "com.sec.android.daemonapp",
    "com.samsung.android.honeyboard",  # only if using another keyboard
]

def adb(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True, text=True, timeout=30)

for p in HEAVY:
    # skip if not installed
    r = adb("pm", "list", "packages", p)
    if p not in r.stdout:
        print("  not installed:", p); continue
    # try disable-user, then disable, then hide
    for cmd in (["pm","disable-user","--user","0",p], ["pm","disable",p], ["pm","hide",p]):
        r = adb(*cmd)
        msg = (r.stdout + r.stderr).lower()
        if "disabled" in msg or "hidden" in msg or r.returncode == 0:
            print("  frozen:", p, "->", cmd[1]); break
    else:
        print("  FAILED:", p)