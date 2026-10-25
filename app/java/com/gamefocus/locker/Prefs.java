package com.gamefocus.locker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Tiny SharedPreferences holder shared by the activity and all services. */
public final class Prefs {
    public static final String FILE = "focus";

    // Games that must never be killed/blocked. Prefilled with PUBG Mobile + COD Mobile.
    public static final String[] KNOWN_GAMES = {
            "com.tencent.ig",                       // PUBG Mobile (global)
            "com.pubg.imobile",                     // Battlegrounds Mobile India
            "com.vng.pubgmobile",                   // PUBG Mobile VN
            "com.rekoo.pubgm",                      // PUBG Mobile TW/KR alt
            "com.activision.callofduty.shooter",    // COD Mobile (global)
            "com.garena.game.codm",                 // COD Mobile (Garena)
            "com.tencent.tmgp.kr.callofduty",       // COD Mobile KR
    };

    public static boolean autoSetupDone(Context c) { return sp(c).getBoolean("auto_setup_done", false); }

    public static void setAutoSetupDone(Context c, boolean v) { sp(c).edit().putBoolean("auto_setup_done", v).apply(); }

    /** packages the user explicitly unticked/removed — auto-detect must never re-add them */
    public static Set<String> removedGames(Context c) {
        return new HashSet<>(sp(c).getStringSet("removed_games", new HashSet<>()));
    }

    public static void markRemoved(Context c, String pkg) {
        Set<String> s = sp(c).getStringSet("removed_games", new HashSet<>());
        Set<String> out = new HashSet<>(s);
        out.add(pkg);
        sp(c).edit().putStringSet("removed_games", out).apply();
    }

    public static void unmarkRemoved(Context c, String pkg) {
        Set<String> s = sp(c).getStringSet("removed_games", new HashSet<>());
        Set<String> out = new HashSet<>(s);
        out.remove(pkg);
        sp(c).edit().putStringSet("removed_games", out).apply();
    }

    /** one-time reset of the polluted auto-detected selection (old build bug) */
    public static boolean migratedV2(Context c) { return sp(c).getBoolean("migrated_v2", false); }

    public static void setMigratedV2(Context c, boolean v) { sp(c).edit().putBoolean("migrated_v2", v).apply(); }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) { return sp(c).getBoolean("enabled", false); }

    public static void setEnabled(Context c, boolean on) { sp(c).edit().putBoolean("enabled", on).apply(); }

    /** Tracks whether the user deliberately stopped lockdown; auto-start honors it. */
    public static boolean userStopped(Context c) { return sp(c).getBoolean("user_stopped", false); }
    public static void setUserStopped(Context c, boolean v) { sp(c).edit().putBoolean("user_stopped", v).apply(); }

    public static Set<String> whitelistedPackages(Context c) {
        Set<String> out = new HashSet<>();
        // The locker app itself + essential system UI are always allowed so the
        // watchdog never fights its own UI, the launcher, keyboard or lock screen.
        out.add(c.getPackageName());
        out.add("com.android.systemui");            // status bar, keyguard
        out.add("com.samsung.android.honeyboard");  // Samsung keyboard
        out.add("com.samsung.android.launcher.unified"); // One UI launcher
        out.add("com.sec.android.app.launcher");    // older Samsung launcher
        Set<String> games = sp(c).getStringSet("games", null);
        if (games != null) out.addAll(games);
        else { for (String g : KNOWN_GAMES) out.add(g); }
        return out;
    }

    public static Set<String> selectedGames(Context c) {
        Set<String> def = new HashSet<>();
        def.add("com.tencent.ig");
        def.add("com.activision.callofduty.shooter");
        return new HashSet<>(sp(c).getStringSet("games", def));
    }

    public static void setSelectedGames(Context c, Set<String> games) {
        sp(c).edit().putStringSet("games", new HashSet<>(games)).apply();
    }

    public static int kills(Context c) { return sp(c).getInt("kills", 0); }

    public static void addKills(Context c, int n) { sp(c).edit().putInt("kills", kills(c) + n).apply(); }

    public static int callsRejected(Context c) { return sp(c).getInt("calls_rejected", 0); }

    public static void addCallRejected(Context c) { sp(c).edit().putInt("calls_rejected", callsRejected(c) + 1).apply(); }

    private Prefs() {}
}
