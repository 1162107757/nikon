package cc.huevia.ui;

import android.content.Context;
import android.graphics.Color;

/** Orange light: warm photographic surfaces, terracotta selection and charcoal actions. */
public final class UiPalette {
    public final int surface, container, text, secondary, primary, onPrimary, primaryContainer, outline, success, error;
    public final int card, glass, rim, action, onAction, actionEnd, onPrimaryContainer, photoAccent, photoTrack;
    public final boolean dark;
    public UiPalette(Context context) {
        dark = AppAppearance.isDark(context);
        surface = color(dark ? "#201F1D" : "#FAFAF8");
        container = color(dark ? "#33302C" : "#EFEDE9");
        card = color(dark ? "#292724" : "#FFFFFF");
        text = color(dark ? "#F6F1EB" : "#262320");
        secondary = color(dark ? "#BAB1A7" : "#706A63");
        primary = color(dark ? "#F3B493" : "#B3432D");
        onPrimary = color(dark ? "#352016" : "#FFFFFF");
        primaryContainer = color(dark ? "#51382D" : "#F4D6C0");
        onPrimaryContainer = color(dark ? "#F3B493" : "#85311F");
        photoAccent = color("#F3B493"); photoTrack = color("#635247");
        outline = color(dark ? "#4C4640" : "#E4DFD8");
        success = color(dark ? "#9AC8AE" : "#42735B");
        error = color(dark ? "#FFB4A7" : "#AD3B30");
        action = color(dark ? "#F0E8DF" : "#46413B");
        actionEnd = color(dark ? "#DAD0C5" : "#27241F");
        onAction = color(dark ? "#26211C" : "#FFFFFF");
        // The substantial tint keeps navigation labels readable over photographs.
        glass = color(dark ? "#F0292724" : "#F5FAFAF8");
        rim = color(dark ? "#30FFFFFF" : "#CFFFFFFF");
    }
    private static int color(String hex) { return Color.parseColor(hex); }
}
