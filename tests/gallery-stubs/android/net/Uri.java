package android.net;
public final class Uri {
    private final String value;
    private Uri(String value) { this.value = value; }
    public static Uri parse(String value) { return new Uri(value); }
    public String getScheme() { return java.net.URI.create(value).getScheme(); }
    public String getAuthority() { return java.net.URI.create(value).getAuthority(); }
    public String getPath() { return java.net.URI.create(value).getPath(); }
    @Override public String toString() { return value; }
}
