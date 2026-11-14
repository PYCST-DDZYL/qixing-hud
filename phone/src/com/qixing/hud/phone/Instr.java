package com.qixing.hud.phone;

import com.qixing.hud.common.L;

/** Turns OSRM maneuvers into arrow codes and short Chinese / English instructions. */
final class Instr {
    private Instr() {}

    static boolean isRoundabout(String type) {
        return type.startsWith("roundabout") || type.startsWith("rotary")
                || type.equals("exit roundabout") || type.equals("exit rotary");
    }

    static String code(String type, String mod) {
        if ("arrive".equals(type)) return "arrive";
        if ("depart".equals(type)) return "depart";
        if (type.equals("exit roundabout") || type.equals("exit rotary")) return dirCode(mod);
        if (isRoundabout(type)) return "roundabout";
        if ("uturn".equals(mod)) return "uturn";
        if ("fork".equals(type) || "merge".equals(type) || "on ramp".equals(type) || "off ramp".equals(type)) {
            if (mod.contains("left")) return "slight-left";
            if (mod.contains("right")) return "slight-right";
            return "straight";
        }
        return dirCode(mod);
    }

    private static String dirCode(String mod) {
        switch (mod) {
            case "left": return "left";
            case "right": return "right";
            case "sharp left": return "sharp-left";
            case "sharp right": return "sharp-right";
            case "slight left": return "slight-left";
            case "slight right": return "slight-right";
            case "uturn": return "uturn";
            default: return "straight";
        }
    }

    /** Short action, e.g. "左转" / "Turn left". Shown on the HUD and spoken, so keep it brief. */
    static String action(Route.Step s) {
        String t = s.type, m = s.modifier;
        if ("arrive".equals(t)) return L.t("到达目的地", "Arrive");
        if ("depart".equals(t)) return L.t("出发", "Start");
        if (t.equals("exit roundabout") || t.equals("exit rotary")) return L.t("驶出环岛", "Exit roundabout");
        if (isRoundabout(t)) {
            return s.exit > 0 ? L.t("环岛第" + s.exit + "出口", "Roundabout exit " + s.exit)
                    : L.t("进入环岛", "Enter roundabout");
        }
        if ("uturn".equals(m)) return L.t("掉头", "U-turn");
        if ("fork".equals(t)) {
            if (m.contains("left")) return L.t("靠左走", "Keep left");
            if (m.contains("right")) return L.t("靠右走", "Keep right");
            return L.t("保持直行", "Keep straight");
        }
        if ("merge".equals(t)) {
            if (m.contains("left")) return L.t("向左汇入", "Merge left");
            if (m.contains("right")) return L.t("向右汇入", "Merge right");
            return L.t("汇入", "Merge");
        }
        boolean end = "end of road".equals(t);
        switch (m) {
            case "left": return end ? L.t("路口尽头左转", "Left at road end") : L.t("左转", "Turn left");
            case "right": return end ? L.t("路口尽头右转", "Right at road end") : L.t("右转", "Turn right");
            case "sharp left": return L.t("向左急转", "Sharp left");
            case "sharp right": return L.t("向右急转", "Sharp right");
            case "slight left": return L.t("向左前方", "Bear left");
            case "slight right": return L.t("向右前方", "Bear right");
            default: return L.t("直行", "Go straight");
        }
    }

    static String road(Route.Step s) {
        if ("arrive".equals(s.type)) return "";
        return s.name == null || s.name.isEmpty() ? "" : L.t("进入 ", "onto ") + s.name;
    }

    /** Name changes / continue-straight steps are not real turns for a cyclist. */
    static boolean silent(Route.Step s) {
        String t = s.type;
        boolean straight = s.modifier.isEmpty() || "straight".equals(s.modifier);
        return ("new name".equals(t) || "continue".equals(t) || "notification".equals(t)
                || "use lane".equals(t)) && straight;
    }
}
