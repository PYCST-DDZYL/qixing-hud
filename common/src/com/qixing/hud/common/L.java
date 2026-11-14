package com.qixing.hud.common;

import java.util.Locale;

/** Two-language text: Chinese or English. The phone decides; the glasses follow the phone. */
public final class L {
    /** True = English. Defaults to the device language until a setting or the phone says otherwise. */
    public static volatile boolean en = !Locale.getDefault().getLanguage().equals("zh");

    private L() {}

    public static String t(String zh, String english) {
        return en ? english : zh;
    }

    /** "auto" follows the device language; "zh" / "en" force one. */
    public static void apply(String setting) {
        if ("en".equals(setting)) en = true;
        else if ("zh".equals(setting)) en = false;
        else en = !Locale.getDefault().getLanguage().equals("zh");
    }
}
