package android.database;
public interface Cursor extends AutoCloseable {
    boolean moveToNext(); boolean moveToFirst(); String getString(int column); long getLong(int column); int getInt(int column); void close();
}
