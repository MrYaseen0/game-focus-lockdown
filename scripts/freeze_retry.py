import subprocess

ADB = r"sdk\platform-tools-src\platform-tools-latest-windows\adb.exe"

def adb(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True, text=True, timeout=30)

KEEP = {
    "com.gamefocus.locker", "com.yaseen.gamebooster",
    "com.samsung.android.game.gamehome",
    "com.tencent.ig", "com.pubg.imobile", "com.vng.pubgmobile",
    "com.rekoo.pubgm", "com.activision.callofduty.shooter",
    "com.garena.game.codm", "com.tencent.tmgp.kr.callofduty",
}

# third-party enabled packages still (not frozen)
out = adb("pm", "list", "packages", "-3").stdout
pkgs = [l.split(":",1)[1].strip() for l in out.splitlines() if l.startswith("package:")]

for p in pkgs:
    if p in KEEP:
        continue
    # try disable (device-admin style) then hide
    r = adb("pm", "disable", p)
    msg = (r.stdout + r.stderr).lower()
    if "disabled" in msg or r.returncode == 0:
        print("  disabled ", p)
    else:
        r2 = adb("pm", "hide", p)
        msg2 = (r2.stdout + r2.stderr).lower()
        if "hidden" in msg2 or r2.returncode == 0:
            print("  hidden   ", p)
        else:
            print("  still on ", p)
PY