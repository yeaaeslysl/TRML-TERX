package com.termi.app.terminal;

public final class WcWidth {
    public static int width(int ucs) {
        if (ucs < 32 || (ucs >= 0x7F && ucs < 0xA0)) return -1;
        if (isWide(ucs)) return 2;
        return 1;
    }

    private static boolean isWide(int ucs) {
        return (ucs >= 0x1100 && ucs <= 0x115F) ||
               ucs == 0x2329 || ucs == 0x232A ||
               (ucs >= 0x2E80 && ucs <= 0xA4CF && ucs != 0x303F) ||
               (ucs >= 0xAC00 && ucs <= 0xD7A3) ||
               (ucs >= 0xF900 && ucs <= 0xFAFF) ||
               (ucs >= 0xFE10 && ucs <= 0xFE19) ||
               (ucs >= 0xFE30 && ucs <= 0xFE6F) ||
               (ucs >= 0xFF00 && ucs <= 0xFF60) ||
               (ucs >= 0xFFE0 && ucs <= 0xFFE6) ||
               (ucs >= 0x20000 && ucs <= 0x2FFFD) ||
               (ucs >= 0x30000 && ucs <= 0x3FFFD);
    }
}
