package android.net.wifi;
public final class WifiNetworkSpecifier {
    public final String ssid, password;
    private WifiNetworkSpecifier(String ssid, String password) { this.ssid = ssid; this.password = password; }
    public static final class Builder {
        private String ssid, password = "";
        public Builder setSsid(String value) { ssid = value; return this; }
        public Builder setWpa2Passphrase(String value) { password = value; return this; }
        public WifiNetworkSpecifier build() { return new WifiNetworkSpecifier(ssid, password); }
    }
}
