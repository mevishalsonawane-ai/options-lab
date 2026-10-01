"""IraGoldAlgo on a real Android emulator (CI only: .github/workflows/gold-emulator.yml).

    python3 android/tools/gold_emulator_test.py <IraGoldAlgo apk> <report dir>

Installs the APK, lets it run unrestricted in the background (the app asks for that on first start), grants
notifications, then drives it through the screen's accessibility tree (uiautomator) the way a person would: create a
PIN, skip the fingerprint offer, Home (the live gold price arrives, arm the strategy), Trades, Settings (lot size, a
reset that is cancelled, security and back), and checks that the app never crashed. The window is FLAG_SECURE, so no
screenshots: every screen's visible text is saved to the report instead. Exit code 1 on any failed step.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.iragoldalgo.app"
PIN = "135792"
APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "report.txt"), "w")
failures = []


def say(s):
    print(s, flush=True)
    log.write(s + "\n")
    log.flush()


def adb(*a, check=True):
    r = subprocess.run(["adb", *a], capture_output=True, text=True, timeout=120)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(a)}: {r.stderr.strip()}")
    return r.stdout


def nodes():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if "<hierarchy" in xml:
            out = []
            for n in ET.fromstring(xml[xml.index("<hierarchy"):]).iter("node"):
                text = n.get("text") or n.get("content-desc") or ""
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    out.append((text, (x1 + x2) // 2, (y1 + y2) // 2, n.get("package")))
            return out
        time.sleep(1)
    return []


def texts():
    return [t for t, _, _, _ in nodes() if t]


def find(text, exact=True):
    for t, x, y, _ in nodes():
        if (t == text) if exact else (text in t):
            return x, y
    return None


def wait(text, timeout=30, exact=False):
    end = time.time() + timeout
    while time.time() < end:
        if find(text, exact):
            return True
        time.sleep(1)
    return False


def tap(text, exact=True, timeout=20, scroll=True):
    end = time.time() + timeout
    while time.time() < end:
        p = find(text, exact)
        if p:
            adb("shell", "input", "tap", str(p[0]), str(p[1]))
            time.sleep(1.2)
            return True
        if scroll:
            adb("shell", "input", "swipe", "540", "1500", "540", "700", "300")
        time.sleep(1)
    return False


def screen(name):
    t = texts()
    with open(os.path.join(OUT, f"{name}.txt"), "w") as f:
        f.write("\n".join(t))
    say(f"  [{name}] " + " | ".join(t[:40]))
    return t


def step(name, ok):
    say(("PASS " if ok else "FAIL ") + name)
    if not ok:
        failures.append(name)


def crashed():
    lc = adb("logcat", "-d", "-b", "crash", check=False)
    return [l for l in lc.splitlines() if PKG in l or "FATAL EXCEPTION" in l]


say(f"APK {APK}")
adb("logcat", "-c", check=False)
adb("install", "-r", "-g", APK)
adb("shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}", check=False)
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
adb("shell", "am", "start", "-n", f"{PKG}/com.optionslab.app.MainActivity")

# First start: create the PIN (entered twice), then skip the fingerprint offer if it comes.
step("the PIN pad appears", wait("1", timeout=60, exact=True))
screen("01-create-pin")
for round_ in (1, 2):
    for d in PIN:
        tap(d, timeout=10, scroll=False)
    if find("✓"):
        tap("✓", scroll=False)
    time.sleep(2)
if wait("Not now", timeout=8, exact=True):
    tap("Not now", scroll=False)

def shot(name):
    adb("shell", "screencap", "-p", "/sdcard/shot.png", check=False)
    adb("pull", "/sdcard/shot.png", os.path.join(OUT, f"{name}.png"), check=False)
    screen(name)


# Home.
step("Home opens after the PIN", wait("Paper account", timeout=45))
screen("02-home-first")

# This is a throwaway test phone: the owner's own switch (Security, confirmed with the PIN) lets the screens be captured.
tap("Settings", scroll=False)
step("security opens", tap("Open security") and wait("‹ Settings", timeout=15))
step("security has no Zerodha parts", not any("Zerodha" in t or "Kite" in t for t in texts()))
tap("Allow screenshots and screen recording")
if wait("Confirm it is you", timeout=10):
    tap("PIN", scroll=False)
    adb("shell", "input", "text", PIN)
    time.sleep(0.5)
    tap("Confirm", scroll=False)
time.sleep(2)
shot("06-security")
step("back to settings", tap("‹ Settings", scroll=False) and wait("LOT SIZE", timeout=15))

tap("Home", scroll=False)
time.sleep(2)
step("the gold price arrives from the feed", wait("$", timeout=90) and any(re.match(r"^\$[\d,]+\.\d\d$", t) for t in texts()))
step("the balance starts at $1,000.00", "$1,000.00" in texts())
step("the strategy starts unarmed", any("Not armed" in t for t in texts()))
shot("02-home")
tap("Armed", exact=True)
time.sleep(4)
adb("shell", "input", "swipe", "540", "1500", "540", "700", "300")
time.sleep(1)
armed = texts()
shot("03-home-armed")
step("arming changes the status", not any(t == "Not armed" for t in armed))

# Trades.
step("Trades opens", tap("Trades", scroll=False) and wait("Closed trades", timeout=15))
step("no trades yet", wait("No trades yet.", timeout=5))
shot("04-trades")

# Settings.
step("Settings opens", tap("Settings", scroll=False) and wait("LOT SIZE", timeout=15))
shot("05-settings")
step("lot size 0.05 is chosen", tap("0.05", scroll=False))
step("a reset asks first", tap("$5,000", scroll=False) and wait("Reset the paper account to $5,000?", timeout=10))
shot("05b-reset-asks")
step("cancel keeps the account", tap("Cancel") and not wait("Reset the paper account to $5,000?", timeout=3))
# The dark theme.
step("dark theme", tap("Dark"))
time.sleep(1)
tap("Home", scroll=False)
time.sleep(2)
step("Home shows the 0.05 lot", wait("0.05 lot (5 oz)", timeout=10))
shot("07-home-dark")
tap("Trades", scroll=False)
time.sleep(1)
shot("08-trades-dark")
tap("Settings", scroll=False)
time.sleep(1)
shot("09-settings-dark")

# Crashes, and the process still alive.
time.sleep(5)
c = crashed()
step("no crash in the log", not c)
if c:
    say("\n".join(c[:80]))
step("the app is still running", adb("shell", "pidof", PKG, check=False).strip() != "")
with open(os.path.join(OUT, "logcat.txt"), "w") as f:
    f.write(adb("logcat", "-d", "-v", "time", check=False))

say(f"\n{len(failures)} failed: {failures}" if failures else "\nAll steps passed.")
sys.exit(1 if failures else 0)
