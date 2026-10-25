package com.gamefocus.locker;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private Button focusBtn;
    private TextView statusText, ramText;
    private LinearLayout permBox, gameBox;
    /** package name -> label of every installed game found by the auto-detector */
    private final Map<String, String> detectedGames = new HashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        requestNotifPermissionIfNeeded();
        autoSetup(); // open the app -> games auto-detected, FOCUS auto-enabled on first run
    }

    @Override protected void onResume() {
        super.onResume();
        reArmIfEnabled();
        refresh();
    }

    /** Every time the app comes to the front while FOCUS is on: re-enforce total
     *  DND, re-sync the watchdog state and make sure the booster service runs.
     *  This is what keeps notifications invisible even while you are inside
     *  GAME FOCUS checking the app list. */
    private void reArmIfEnabled() {
        if (!Prefs.isEnabled(this)) return;
        FocusAccessibilityService.onFocusChanged(true); // refresh watchdog state synchronously
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        BoosterService.applyDnd(this);
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean serviceUp = false;
        try {
            for (ActivityManager.RunningServiceInfo rs : am.getRunningServices(200)) {
                if (rs.service.getPackageName().equals(getPackageName())) { serviceUp = true; break; }
            }
        } catch (Exception ignore) {}
        if (!serviceUp) {
            Intent it = new Intent(this, BoosterService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it); else startService(it);
        }
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0b1020"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("GAME FOCUS — LOCKDOWN");
        title.setTextColor(Color.parseColor("#ffd54a"));
        title.setTextSize(24); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Total shutdown of everything except PUBG / COD.\nMedia sound always stays ON.");
        sub.setTextColor(Color.parseColor("#9fb0d0")); sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER); sub.setPadding(0, dp(6), 0, dp(10));
        root.addView(sub);

        focusBtn = new Button(this);
        focusBtn.setTextSize(20); focusBtn.setTypeface(Typeface.DEFAULT_BOLD);
        focusBtn.setPadding(0, dp(18), 0, dp(18));
        focusBtn.setOnClickListener(v -> toggleFocus());
        root.addView(focusBtn, new LinearLayout.LayoutParams(-1, -2));

        // hard-wired utility row: free RAM now / stop every background process
        LinearLayout boostRow = new LinearLayout(this);
        boostRow.setOrientation(LinearLayout.HORIZONTAL);
        Button ramBtn = new Button(this);
        ramBtn.setText("FREE RAM NOW");
        ramBtn.setTextSize(13);
        ramBtn.setOnClickListener(v -> freeRamNow());
        Button stopBtn = new Button(this);
        stopBtn.setText("STOP ALL BG");
        stopBtn.setTextSize(13);
        stopBtn.setOnClickListener(v -> stopAllBg());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        half.setMargins(dp(3), dp(10), dp(3), 0);
        boostRow.addView(ramBtn, half);
        boostRow.addView(stopBtn, half);
        root.addView(boostRow);

        ramText = new TextView(this);
        ramText.setTextColor(Color.parseColor("#59d879"));
        ramText.setTextSize(12);
        ramText.setPadding(0, dp(6), 0, 0);
        root.addView(ramText);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#cfe8ff")); statusText.setTextSize(13);
        statusText.setPadding(0, dp(12), 0, dp(4));
        root.addView(statusText);

        root.addView(section("SELECT YOUR GAMES (never killed, always allowed)"));
        Button addGameBtn = new Button(this);
        addGameBtn.setText("+ ADD A GAME (protect any app)");
        addGameBtn.setTextSize(13);
        addGameBtn.setOnClickListener(v -> showAddGameDialog());
        root.addView(addGameBtn, new LinearLayout.LayoutParams(-1, -2));
        gameBox = new LinearLayout(this); gameBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gameBox);

        root.addView(section("PERMISSIONS — TURN ALL 5 ON FOR FULL LOCKDOWN"));
        permBox = new LinearLayout(this); permBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(permBox);

        root.addView(section("SAMSUNG ONE UI SETTINGS (strongly recommended)"));
        root.addView(note("Settings → Battery → Background usage limits → Never sleeping apps → add GAME FOCUS.\n"
                + "Settings → Device care → Battery → More settings → disable 'Adaptive battery'.\n"
                + "These stop Samsung from killing the booster itself."));

        setContentView(scroll);
    }

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(13); tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#7f8bb0"));
        tv.setPadding(0, dp(22), 0, dp(6));
        return tv;
    }

    private TextView note(String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(12); tv.setTextColor(Color.parseColor("#8a97b8"));
        return tv;
    }

    private CheckBox gameRow(final String pkg, String label, boolean installed) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label + (installed ? "" : "  (not installed)"));
        cb.setTextSize(15);
        cb.setTextColor(Color.WHITE);
        cb.setChecked(Prefs.selectedGames(this).contains(pkg));
        cb.setEnabled(installed);
        cb.setOnCheckedChangeListener((v, on) -> {
            Set<String> s = Prefs.selectedGames(this);
            if (on) {
                s.add(pkg);
                Prefs.unmarkRemoved(this, pkg); // re-protection also clears the removal marker
            } else {
                s.remove(pkg);
                Prefs.markRemoved(this, pkg); // never let auto-detect re-add what the user unticked
            }
            Prefs.setSelectedGames(this, s);
        });
        return cb;
    }

    private LinearLayout permRow(String title, boolean ok, View.OnClickListener go) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView tv = new TextView(this);
        tv.setText((ok ? "✓ " : "✗ ") + title);
        tv.setTextSize(14); tv.setTextColor(ok ? Color.parseColor("#59d879") : Color.parseColor("#ff7676"));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(tv);
        if (!ok) {
            Button b = new Button(this);
            b.setText("OPEN"); b.setTextSize(12);
            b.setOnClickListener(go);
            row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        return row;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ state

    private void refresh() {
        boolean on = Prefs.isEnabled(this);
        focusBtn.setText(on ? "■ STOP GAME MODE" : "▶ START — GAME MODE (LOCKDOWN)");
        focusBtn.setBackgroundColor(Color.parseColor(on ? "#e5453c" : "#1fae5a"));
        focusBtn.setTextColor(Color.WHITE);

        int kills = Prefs.kills(this), rejects = Prefs.callsRejected(this);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long availMb = mi.availMem >> 20, totalMb = mi.totalMem >> 20;
        statusText.setText(on
                ? "STATE: LOCKDOWN ACTIVE ✔\nBackground apps killed this session: " + kills
                + "\nCalls auto-rejected: " + rejects
                + "\nFree RAM right now: " + availMb + " / " + totalMb + " MB (all of it reserved for the game)"
                + "\n\nOpen PUBG or COD now — everything else gets crushed."
                : "STATE: OFF — press START, then open PUBG or COD yourself.\nFree RAM: " + availMb + " / " + totalMb + " MB");
        refreshGames();
        refreshPerms();
    }

    private void refreshGames() {
        gameBox.removeAllViews();
        PackageManager pm = getPackageManager();
        Set<String> shown = new LinkedHashSet<>();
        // known PUBG / COD packages first (if installed) ...
        for (String pkg : Prefs.KNOWN_GAMES) {
            try { if (pm.getPackageInfo(pkg, 0) != null) shown.add(pkg); } catch (Exception ignore) {}
        }
        // ... then everything the user picked with "+ ADD A GAME" ...
        for (String pkg : Prefs.selectedGames(this)) {
            if (!shown.contains(pkg) && !isEssentialUi(pkg)) shown.add(pkg);
        }
        // ... then every other game auto-detected from the installed list
        for (Map.Entry<String, String> e : detectedGames.entrySet()) {
            if (!shown.contains(e.getKey()) && !isEssentialUi(e.getKey())) shown.add(e.getKey());
        }
        for (String pkg : shown) {
            boolean installed;
            try { installed = pm.getPackageInfo(pkg, 0) != null; } catch (Exception e) { installed = false; }
            gameBox.addView(gameRow(pkg, gameLabel(pkg), installed));
        }
        if (shown.isEmpty()) {
            gameBox.addView(note("No game found — install PUBG / COD Mobile, then reopen GAME FOCUS."));
        } else {
            gameBox.addView(note(shown.size() + " protected game(s): auto-detected + anything you added.\n"
                    + "Untick to exclude, or press '+ ADD A GAME' to protect any other app."));
        }
    }

    /** Scans all installed apps for games. FIRST RUN: auto-detect everything
     *  (known PUBG/CoD packages + CATEGORY_GAME) and auto-enable FOCUS.
     *  EVERY LATER RUN: only known PUBG/CoD packages are auto-added, and never
     *  anything the user explicitly unticked — the protected list is the
     *  user-selected list, so apps like a "movie" app flagged CATEGORY_GAME
     *  can never sneak back into the whitelist. */
    private void autoSetup() {
        PackageManager pm = getPackageManager();
        Set<String> removed = Prefs.removedGames(this);
        Set<String> knownInstalled = new HashSet<>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (isEssentialUi(ai.packageName)) continue;
                boolean isGame = ai.category == ApplicationInfo.CATEGORY_GAME || inKnownGames(ai.packageName);
                if (!isGame) continue;
                detectedGames.put(ai.packageName, String.valueOf(pm.getApplicationLabel(ai)));
                if (inKnownGames(ai.packageName)) knownInstalled.add(ai.packageName);
            }
        } catch (Exception ignore) {}
        Set<String> sel = Prefs.selectedGames(this);
        // one-time migration from the old buggy build: its auto-detect silently
        // whitelisted EVERY app flagged as a game (MovieBox, etc.). Reset the
        // selection to the known PUBG/CoD family; anything else must be added
        // again explicitly with "+ ADD A GAME" (and then it always sticks).
        if (!Prefs.migratedV2(this)) {
            Set<String> fresh = new HashSet<>();
            for (String pkg : Prefs.KNOWN_GAMES) {
                try { if (pm.getPackageInfo(pkg, 0) != null) fresh.add(pkg); } catch (Exception ignore) {}
            }
            fresh.removeAll(removed);
            Prefs.setSelectedGames(this, fresh);
            Prefs.setMigratedV2(this, true);
            sel = Prefs.selectedGames(this);
        }
        boolean firstRun = !Prefs.autoSetupDone(this);
        if (firstRun) {
            // first run: take everything detected that the user has not removed
            for (String pkg : detectedGames.keySet()) if (!removed.contains(pkg)) sel.add(pkg);
            Prefs.setAutoSetupDone(this, true);
        } else {
            // later runs: only PUBG/CoD-family packages, honoring explicit removals
            for (String pkg : knownInstalled) if (!removed.contains(pkg)) sel.add(pkg);
        }
        Prefs.setSelectedGames(this, sel);
        // auto-start lockdown on (re)open unless the user explicitly stopped it
        if (!Prefs.isEnabled(this) && !Prefs.userStopped(this) && !sel.isEmpty()) {
            toggleFocus();
        }
    }

    /** Packages that must never be auto-added as "games" even if flagged as one. */
    private boolean isEssentialUi(String pkg) {
        return pkg.equals(getPackageName()) || pkg.startsWith("com.android.") || pkg.startsWith("com.samsung.android.");
    }

    private boolean inKnownGames(String pkg) {
        for (String k : Prefs.KNOWN_GAMES) if (pkg.startsWith(k)) return true;
        return false;
    }

    private String gameLabel(String pkg) {
        String detected = detectedGames.get(pkg);
        if (detected != null && !detected.equals(pkg)) return detected;
        if (pkg.startsWith("com.tencent.ig")) return "PUBG MOBILE (Global)";
        if (pkg.startsWith("com.pubg.imobile")) return "Battlegrounds Mobile India";
        if (pkg.startsWith("com.vng.pubgmobile")) return "PUBG Mobile (VN)";
        if (pkg.startsWith("com.rekoo.pubgm")) return "PUBG Mobile (KR/TW)";
        if (pkg.startsWith("com.activision.callofduty.shooter")) return "COD MOBILE (Global)";
        if (pkg.startsWith("com.garena.game.codm")) return "COD MOBILE (Garena)";
        if (pkg.startsWith("com.tencent.tmgp.kr")) return "COD MOBILE (KR)";
        return pkg;
    }

    private void refreshPerms() {
        permBox.removeAllViews();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        boolean acc = isAccessibilityOn();
        permBox.addView(permRow("1. Accessibility service (watchdog — bounces you back to the game)", acc,
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        boolean dnd = nm != null && nm.isNotificationPolicyAccessGranted();
        permBox.addView(permRow("2. Do-Not-Disturb access (hides calls / SMS / WhatsApp)", dnd,
                v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));

        boolean nls = isNotificationListenerOn();
        permBox.addView(permRow("3. Notification access (wipes leftover notifications)", nls,
                v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));

        PowerManager pmgr = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batt = pmgr != null && pmgr.isIgnoringBatteryOptimizations(getPackageName());
        permBox.addView(permRow("4. Ignore battery optimization (booster never sleeps)", batt, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));

        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED;
        permBox.addView(permRow("5. Phone (auto-reject incoming calls during lockdown)", phone,
                v -> requestPermissions(new String[]{
                        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS}, 7)));
    }

    private boolean isAccessibilityOn() {
        if (FocusAccessibilityService.connected) return true;
        String cfg = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return cfg != null && cfg.contains(getPackageName() + "/" + FocusAccessibilityService.class.getName());
    }

    private boolean isNotificationListenerOn() {
        Set<String> list = new HashSet<>();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null) for (String s : flat.split(":")) list.add(s.toLowerCase());
        for (String s : list) if (s.contains(getPackageName().toLowerCase())) return true;
        return false;
    }

    // ------------------------------------------------------------ one-tap utilities

    /** FREE RAM NOW: brutal immediate sweep + before/after RAM report. */
    private void freeRamNow() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        ramText.setText("Cleaning RAM…");
        new Thread(() -> {
            int kills = BoosterService.killBackground(this, true);
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            long freedMb = Math.max(0, (after.availMem - before.availMem) >> 20);
            long availMb = after.availMem >> 20, totalMb = after.totalMem >> 20;
            final String msg = "RAM CLEANED ✔ — " + kills + " background process group(s) killed\n"
                    + "Freed just now: + " + freedMb + " MB → free RAM now " + availMb
                    + " / " + totalMb + " MB\nThat free RAM is ready for PUBG / COD — open your game.";
            runOnUiThread(() -> ramText.setText(msg));
        }).start();
    }

    /** STOP ALL BG: two aggressive sweeps back-to-back to catch respawn stragglers. */
    private void stopAllBg() {
        ramText.setText("Stopping every background process…");
        new Thread(() -> {
            int kills = BoosterService.killBackground(this, true);
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            kills += BoosterService.killBackground(this, true);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            long availMb = mi.availMem >> 20;
            final int k = kills;
            runOnUiThread(() -> {
                ramText.setText("STOPPED ✔ — " + k + " background processes killed. Free RAM: " + availMb + " MB.");
                Toast.makeText(this, "All background processes stopped. Only your games + system survive.", Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    /** + ADD A GAME: pick ANY installed app and protect it from the killer forever. */
    private void showAddGameDialog() {
        PackageManager pm = getPackageManager();
        Set<String> selected = Prefs.selectedGames(this);
        final java.util.List<String> pkgs = new ArrayList<>();
        final java.util.List<String> labels = new ArrayList<>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(getPackageName()) || isEssentialUi(ai.packageName)) continue;
                if (selected.contains(ai.packageName)) continue;
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue; // only apps you can open
                pkgs.add(ai.packageName);
                labels.add(pm.getApplicationLabel(ai) + "  (" + ai.packageName + ")");
            }
        } catch (Exception ignore) {}
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "Every installed app is already protected.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ADD A GAME\n(it will never be killed)")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    Set<String> s = Prefs.selectedGames(this);
                    s.add(pkgs.get(which));
                    Prefs.unmarkRemoved(this, pkgs.get(which));
                    Prefs.setSelectedGames(this, s);
                    Toast.makeText(this, "Protected: " + labels.get(which), Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    // ------------------------------------------------------------ focus toggle

    private void toggleFocus() {
        boolean on = !Prefs.isEnabled(this);
        Prefs.setEnabled(this, on);
        Prefs.setUserStopped(this, !on);
        FocusAccessibilityService.onFocusChanged(on);
        if (on) {
            // sound-safe lockdown: PRIORITY DND with MEDIA explicitly allowed
            BoosterService.applyDnd(this);
            Intent it = new Intent(this, BoosterService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it); else startService(it);
        } else {
            stopService(new Intent(this, BoosterService.class));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            // resurrection sweep: clear whatever background clutter returned
            BoosterService.killBackgroundNow(this);
        }
        refresh();
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
        }
    }
}
