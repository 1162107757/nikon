package cc.huevia.ui;

import android.content.Context;
import android.graphics.Color;

/** Stable semantic colors for the cloud-white and graphite application surfaces. */
public final class UiPalette {
    public final int surface, container, text, secondary, primary, onPrimary, primaryContainer, outline, success, error;
    public final int card, glass, rim;
    public final boolean dark;
    public UiPalette(Context context) {
        dark = AppAppearance.isDark(context);
        surface = color(dark ? "#22252A" : "#F7F8FA");
        container = color(dark ? "#30353D" : "#EEF0F4");
        card = color(dark ? "#2A2E34" : "#FFFFFF");
        text = color(dark ? "#F4F5F7" : "#20242B");
        secondary = color(dark ? "#ADB6C3" : "#626B78");
        primary = color(dark ? "#8DBBFF" : "#0065DC");
        onPrimary = color(dark ? "#07244A" : "#FFFFFF");
        primaryContainer = color(dark ? "#263D5A" : "#E9F1FF");
        outline = color(dark ? "#474E58" : "#D9DEE6");
        success = color(dark ? "#7ED9A1" : "#258449");
        error = color(dark ? "#FFB4AB" : "#BA302A");
        // The substantial tint keeps navigation labels readable over photographs.
        glass = color(dark ? "#E6282C32" : "#ECFFFFFF");
        rim = color(dark ? "#30FFFFFF" : "#CFFFFFFF");
    }
    private static int color(String hex) { return Color.parseColor(hex); }
}
