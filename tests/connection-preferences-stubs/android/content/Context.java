package android.content;

import java.util.HashMap;
import java.util.Map;

/** Shared in-memory backing survives the replacement Context used to model recreation. */
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> backing;
    public Context() { this(new HashMap<>()); }
    private Context(Map<String, SharedPreferences> backing) { this.backing = backing; }
    public Context recreated() { return new Context(backing); }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return backing.computeIfAbsent(name, ignored -> new MemoryPreferences());
    }

    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : (String) value;
        }
        public boolean contains(String key) { return values.containsKey(key); }
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                public Editor putString(String key, String value) { pending.put(key, value); return this; }
                public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                public Editor remove(String key) { pending.put(key, null); return this; }
                public void apply() {
                    for (var entry : pending.entrySet()) {
                        if (entry.getValue() == null) values.remove(entry.getKey());
                        else values.put(entry.getKey(), entry.getValue());
                    }
                }
            };
        }
    }
}
