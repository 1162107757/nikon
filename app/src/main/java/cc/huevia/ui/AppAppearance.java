package cc.huevia.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import cc.huevia.R;

/** App-only appearance preference; the device's actual night configuration stays intact. */
public final class AppAppearance {
    public static final String SYSTEM = "system";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    private static final String PREFERENCES = "app-appearance";
    private static final String KEY_MODE = "mode";

    private AppAppearance() {}

    public static String mode(Context context) {
        try {
            return validMode(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getString(KEY_MODE, SYSTEM));
        } catch (ClassCastException invalidStoredType) {
            return SYSTEM;
        }
    }

    public static void setMode(Context context, String mode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, validMode(mode)).apply();
    }

    public static String label(Context context) {
        switch (mode(context)) {
            case LIGHT: return "浅色";
            case DARK: return "深色";
            default: return "跟随系统";
        }
    }

    public static boolean isDark(Context context) {
        switch (mode(context)) {
            case LIGHT: return false;
            case DARK: return true;
            default:
                return (context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }
    }

    /**
     * Apply before super.onCreate and before rebuilding the UI after an appearance change.
     * Explicit theme parents also update subsequently created framework dialogs and controls.
     * No Activity recreation or resource configuration override is needed, so camera sessions
     * survive and SYSTEM can always read the real device configuration.
     */
    public static void apply(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.AppThemeDark : R.style.AppThemeLight);
    }

    private static String validMode(String value) {
        return LIGHT.equals(value) || DARK.equals(value) ? value : SYSTEM;
    }
}
