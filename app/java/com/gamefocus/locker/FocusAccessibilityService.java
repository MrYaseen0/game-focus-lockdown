package com.gamefocus.locker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * WATCHDOG. Fires on every window change. While FOCUS mode is on:
 *  - any foreground app that is not on the user's selected list / system UI is
 *    killed (killBackgroundProcesses) and the user is bounced straight back into
 *    the game he was actually playing. No other app ever stays on screen.
 *  - the launcher is allowed ONLY until the first game reaches the foreground
 *    (so the user can navigate to PUBG/COD). After that, even Home bounces
 *    straight back into the game — full lockdown means nothing else can open.
 *  - onServiceConnected re-syncs the enabled flag from Prefs: when Samsung's
 *    system restarts this service (it does), lockdown survives and resumes.
 */
public class FocusAccessibilityService extends AccessibilityService {

    private static volatile boolean enabled = false;
    public static volatile boolean connected = false;
    private static volatile long focusStartedAt = 0;
    private static volatile long lastBounceAt = 0;
    private static String lastOffender = "";
    /** last whitelisted game seen in the foreground — bounce-back returns HERE */
    private static volatile String lastForegroundGame = "";

    private static final Set<String> LAUNCHERS = new HashSet<>(Arrays.asList(
            "com.sec.android.app.launcher",              // Samsung One UI launcher (A16)
            "com.samsung.android.launcher.unified",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"));

    /** called by MainActivity / BoosterService when FOCUS toggles */
    public static void onFocusChanged(boolean on) {
        enabled = on;
        if (on) {
            focusStartedAt = SystemClock.elapsedRealtime();
            lastBounceAt = 0;
            lastOffender = "";
            lastForegroundGame = "";
        }
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        AccessibilityServiceInfo i = new AccessibilityServiceInfo();
        i.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        i.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        i.notificationTimeout = 0; // react to the very first intruder frame
        i.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(i);
        // CRITICAL: the system can kill/relaunch this service at any time; a
        // fresh instance must not start with lockdown silently disabled.
        if (Prefs.isEnabled(this)) {
            enabled = true;
            focusStartedAt = SystemClock.elapsedRealtime();
        } else {
            enabled = false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!enabled || !Prefs.isEnabled(this) || e.getPackageName() == null) return;
        final String pkg = e.getPackageName().toString();
        Set<String> allow = Prefs.whitelistedPackages(this);
        if (allow.contains(pkg)) {
            if (!LAUNCHERS.contains(pkg)) {
                // remember which protected game the user is actually playing
                lastForegroundGame = pkg;
            }
            return;
        }

        // grace period: let the user navigate to the game right after enabling
        if (SystemClock.elapsedRealtime() - focusStartedAt < 3000) return;
        // do not fight the in-call screen while auto-reject is finishing the call
        if (pkg.startsWith("com.android.incallui") || pkg.startsWith("com.samsung.android.app.telephonyui")) return;

        // home pressed / launcher opened AFTER a game was on screen: lock it down
        boolean fromLauncher = LAUNCHERS.contains(pkg);
        if (fromLauncher && lastForegroundGame.isEmpty()) return; // still navigating to the first game

        // bounce throttling: max one action per 300 ms — an intruder app dies and
        // the game is back on screen before the user can tap anything in it
        long now = SystemClock.elapsedRealtime();
        if (now - lastBounceAt < 600) return;
        boolean sameAsLast = pkg.equals(lastOffender) && now - lastBounceAt < 2500;
        lastBounceAt = now;
        lastOffender = pkg;

        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.killBackgroundProcesses(pkg);
        } catch (Exception ignore) {}

        if (!sameAsLast) {
            launchPrimaryGame();
        } else {
            // offender keeps coming back: hard bounce home first
            performGlobalAction(GLOBAL_ACTION_HOME);
            launchPrimaryGame();
        }
    }

    private void launchPrimaryGame() {
        PackageManager pm = getPackageManager();
        // 1) bounce back into the exact game the user was playing (PUBG vs COD)
        if (!lastForegroundGame.isEmpty() && tryLaunch(pm, lastForegroundGame)) return;
        // 2) else the best known installed game, in fixed priority order
        //    (KNOWN_GAMES, never an arbitrary whitelisted app like a movie app)
        for (String g : Prefs.KNOWN_GAMES) {
            if (tryLaunch(pm, g)) return;
        }
        // 3) nothing to return to: home screen only — no other app may stay open
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private boolean tryLaunch(PackageManager pm, String pkg) {
        try {
            if (pm.getLaunchIntentForPackage(pkg) == null) return false;
            Intent it = pm.getLaunchIntentForPackage(pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(it);
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    @Override public void onDestroy() {
        connected = false;
        super.onDestroy();
    }

    @Override public void onInterrupt() {}
}
