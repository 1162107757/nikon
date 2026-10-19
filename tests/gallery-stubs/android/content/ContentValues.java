package android.content;
import java.util.HashMap;
public final class ContentValues extends HashMap<String, Object> {
    public void put(String name, String value) { super.put(name, value); }
    public void put(String name, Integer value) { super.put(name, value); }
}
