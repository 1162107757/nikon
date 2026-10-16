package com.nikonpreset.usbprobe.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** Semantic roles shared by all consumer screens; brand stays fixed across wallpaper changes. */
public final class UiPalette {
    public final int surface, container, text, secondary, primary, onPrimary, primaryContainer, outline, success, error;
    public final boolean dark;
    public UiPalette(Context context) {
        dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surface = color(dark ? "#151618" : "#FFFFFF");
        container = color(dark ? "#242629" : "#F5F6F7");
        text = color(dark ? "#F1F2EC" : "#202124");
        secondary = color(dark ? "#BDC2BC" : "#686D75");
        primary = color(dark ? "#FFB3BF" : "#D9001B");
        onPrimary = color(dark ? "#4D0011" : "#FFFFFF");
        primaryContainer = color(dark ? "#48252C" : "#FFE8EB");
        outline = color(dark ? "#8B938B" : "#D9DCE1");
        success = color(dark ? "#99DBB0" : "#21633C");
        error = color(dark ? "#FFB4AB" : "#B3261E");
    }
    private static int color(String hex) { return Color.parseColor(hex); }
}
