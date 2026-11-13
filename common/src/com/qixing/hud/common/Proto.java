package com.qixing.hud.common;

import java.util.UUID;

/** Phone <-> glasses link constants. Messages are one JSON object per line, UTF-8. */
public final class Proto {
    public static final int TCP_PORT = 47800;
    public static final int BEACON_PORT = 47801;
    public static final int HTTP_PORT = 47802;
    public static final String BEACON_PREFIX = "QXHUD1:";
    public static final UUID BT_UUID = UUID.fromString("6b1c2a50-4b6e-4f0e-9d3a-7f1c5e2b8a41");
    public static final String BT_NAME = "QixingHUD";

    private Proto() {}
}
