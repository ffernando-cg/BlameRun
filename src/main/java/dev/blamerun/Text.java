package dev.blamerun;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.object.ObjectContents;

/** Shared text helpers. */
public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** The player's skin face rendered inline in text (1.21.9+ "object" component). */
    public static Component head(UUID id) {
        return Component.object(ObjectContents.playerHead(id));
    }

    /** 1:02:03 or 12:34. */
    public static String time(long millis) {
        if (millis < 0) {
            return "--:--";
        }
        long total = millis / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    /** Difference against the record: green when faster, red when slower. */
    public static String delta(long millis) {
        String sign = millis <= 0 ? "-" : "+";
        String color = millis <= 0 ? "green" : "red";
        return "<" + color + ">" + sign + time(Math.abs(millis)) + "</" + color + ">";
    }
}
