package com.termi.app.terminal;

public final class TextStyle {
    public static final int CHARACTER_ATTRIBUTE_BOLD = 1;
    public static final int CHARACTER_ATTRIBUTE_ITALIC = 1 << 1;
    public static final int CHARACTER_ATTRIBUTE_UNDERLINE = 1 << 2;
    public static final int CHARACTER_ATTRIBUTE_BLINK = 1 << 3;
    public static final int CHARACTER_ATTRIBUTE_INVERSE = 1 << 4;
    public static final int CHARACTER_ATTRIBUTE_INVISIBLE = 1 << 5;
    public static final int CHARACTER_ATTRIBUTE_STRIKETHROUGH = 1 << 6;
    public static final int CHARACTER_ATTRIBUTE_PROTECTED = 1 << 7;
    public static final int CHARACTER_ATTRIBUTE_DIM = 1 << 8;

    public static final int COLOR_INDEX_FOREGROUND = 256;
    public static final int COLOR_INDEX_BACKGROUND = 257;
    public static final int COLOR_INDEX_CURSOR = 258;

    private static final int STYLE_MASK = 0x1FF;
    private static final int FG_SHIFT = 9;
    private static final int BG_SHIFT = 19;
    private static final int COLOR_MASK = 0x3FF;

    public static long encode(int foreColor, int backColor, int effect) {
        return ((long)(effect & STYLE_MASK)) |
               (((long)(foreColor & COLOR_MASK)) << FG_SHIFT) |
               (((long)(backColor & COLOR_MASK)) << BG_SHIFT);
    }

    public static int decodeForeColor(long style) {
        return (int)((style >> FG_SHIFT) & COLOR_MASK);
    }

    public static int decodeBackColor(long style) {
        return (int)((style >> BG_SHIFT) & COLOR_MASK);
    }

    public static int decodeEffect(long style) {
        return (int)(style & STYLE_MASK);
    }
}
