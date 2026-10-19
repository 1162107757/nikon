package android.content;

import java.util.Map;

/** Only the Android preference APIs used by this focused host test. */
public interface SharedPreferences {
    String getString(String key, String fallback);
    boolean contains(String key);
    Map<String, ?> getAll();
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        void apply();
    }
}
