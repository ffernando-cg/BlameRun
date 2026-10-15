package dev.blamerun;

import java.util.Locale;

/** What each player's action bar shows. */
public enum HudMode {
    FACES,
    TEXT,
    OFF;

    public static HudMode parse(String raw, HudMode fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
