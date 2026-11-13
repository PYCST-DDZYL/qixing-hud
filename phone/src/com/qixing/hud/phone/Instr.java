package com.qixing.hud.phone;

/** Turns OSRM maneuvers into arrow codes and Chinese instructions. */
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

    /** Short action, e.g. "左转". */
    static String action(Route.Step s) {
        String t = s.type, m = s.modifier;
        if ("arrive".equals(t)) return "到达目的地";
        if ("depart".equals(t)) return "出发";
        if (t.equals("exit roundabout") || t.equals("exit rotary")) return "驶出环岛";
        if (isRoundabout(t)) return s.exit > 0 ? "环岛第" + s.exit + "出口" : "进入环岛";
        if ("uturn".equals(m)) return "掉头";
        if ("fork".equals(t)) {
            if (m.contains("left")) return "靠左走";
            if (m.contains("right")) return "靠右走";
            return "保持直行";
        }
        if ("merge".equals(t)) {
            if (m.contains("left")) return "向左汇入";
            if (m.contains("right")) return "向右汇入";
            return "汇入";
        }
        boolean end = "end of road".equals(t);
        switch (m) {
            case "left": return end ? "路口尽头左转" : "左转";
            case "right": return end ? "路口尽头右转" : "右转";
            case "sharp left": return "向左急转";
            case "sharp right": return "向右急转";
            case "slight left": return "向左前方";
            case "slight right": return "向右前方";
            default: return "直行";
        }
    }

    static String road(Route.Step s) {
        if ("arrive".equals(s.type)) return "";
        return s.name == null || s.name.isEmpty() ? "" : "进入 " + s.name;
    }

    /** Name changes / continue-straight steps are not real turns for a cyclist. */
    static boolean silent(Route.Step s) {
        String t = s.type;
        boolean straight = s.modifier.isEmpty() || "straight".equals(s.modifier);
        return ("new name".equals(t) || "continue".equals(t) || "notification".equals(t)
                || "use lane".equals(t)) && straight;
    }
}
