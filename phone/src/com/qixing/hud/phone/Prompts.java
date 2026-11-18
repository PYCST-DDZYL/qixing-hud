package com.qixing.hud.phone;

import com.qixing.hud.common.L;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides what to say before each turn. Pure Java so it can be tested on a computer with real
 * routes and simulated GPS.
 * <ul>
 * <li>"前方250米，左转，进入 X" between 260 and 90 m, "前方50米，左转" between 60 and 8 m.</li>
 * <li>Distances are counted to the moment the rider hears the prompt: the fix is already a little
 * old, and making the speech and playing it (on the glasses) takes about a second. Without this,
 * on a real route the rider was a median 10 m closer to the turn than the prompt said.</li>
 * </ul>
 */
final class Prompts {
    /** Seconds from a fix to the rider hearing the prompt, beyond the fix's own age. */
    static double SPEAK_LAG = 1.0;

    private final Set<String> spoken = new HashSet<>();

    void clear() {
        spoken.clear();
    }

    /**
     * @param nextIdx index of the next real maneuver in r.steps
     * @param mdist   distance to it along the route, metres (from the shown position)
     * @param spd     ground speed, m/s
     * @param fixAge  age of the fix, seconds
     * @return what to say now, or null
     */
    String next(Route r, int nextIdx, double mdist, double spd, double fixAge) {
        Route.Step next = r.steps.get(nextIdx);
        String key = r.id + ":" + nextIdx;
        String act = Instr.action(next);
        String road = Instr.road(next);
        boolean arrive = "arrive".equals(next.type);
        String say = act + (arrive ? "" : (road.isEmpty() ? "" : L.t("，", " ") + road));
        // A second turn right after this one would otherwise be announced too late to act on
        // ("前方10米…"): mention it now, the way car navigation says "随后右转".
        String then = "";
        int afterIdx = -1;
        for (int i = nextIdx + 1; i < r.steps.size(); i++) {
            Route.Step s = r.steps.get(i);
            if (s.silent && !"arrive".equals(s.type)) continue;
            if (s.along - next.along < 50) {
                afterIdx = i;
                then = L.t("，随后", ", then ") + lc(Instr.action(s));
            }
            break;
        }
        // Where the rider will be when the prompt is heard.
        double lead = Math.max(0, spd) * (Math.min(3, Math.max(0, fixAge)) + SPEAK_LAG);
        double d = mdist - lead;
        String out = null;
        if (d <= 260 && d > 90 && spoken.add(key + ":far")) {
            out = ahead(d) + lc(say) + then;
        } else if (d <= 60 && mdist > 8 && spoken.add(key + ":near")) {
            // (the lower bound on the distance itself: the lead must never skip this prompt)
            spoken.add(key + ":far");
            out = ahead(Math.max(0, d)) + lc(act) + then;
        }
        if (afterIdx >= 0 && spoken.contains(key + ":near")) {
            // Already announced as "随后…": no separate, too-late prompt for it.
            spoken.add(r.id + ":" + afterIdx + ":far");
            spoken.add(r.id + ":" + afterIdx + ":near");
        }
        return out;
    }

    /** "前方250米，" / "In 250 meters, ". */
    static String ahead(double m) {
        return L.en ? "In " + roundSay(m) + ", " : "前方" + roundSay(m) + "，";
    }

    /** English reads better mid-sentence in lower case ("In 50 meters, turn left"). */
    static String lc(String s) {
        if (!L.en || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String roundSay(double m) {
        if (m >= 1000) return String.format(Locale.US, "%.1f", m / 1000) + L.t("公里", " kilometers");
        long v = m >= 100 ? Math.round(m / 50.0) * 50 : Math.max(10, Math.round(m / 10.0) * 10);
        return v + L.t("米", " meters");
    }
}
