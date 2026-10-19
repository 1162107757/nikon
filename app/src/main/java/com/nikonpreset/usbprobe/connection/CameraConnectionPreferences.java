package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.content.SharedPreferences;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.album.CameraProtocol;

/** Separate saved endpoints per protocol; the legacy endpoint remains Nikon's. */
public final class CameraConnectionPreferences {
    private static final String PREFERENCES = "camera-connection";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String PROTOCOL = "protocol";
    private static final String FUJI_AUTO_ADDRESS = "fuji-xt20-auto-address";

    private CameraConnectionPreferences() { }

    public static String loadHost(Context context) {
        return loadHost(context, loadProtocol(context));
    }

    public static CameraProtocol loadProtocol(Context context) {
        return CameraProtocol.fromStored(preferences(context).getString(PROTOCOL, ""));
    }

    public static void selectProtocol(Context context, CameraProtocol protocol) {
        preferences(context).edit().putString(PROTOCOL, protocol.name()).apply();
    }

    /** Existing Fuji endpoints were suggestions or phone IPs; upgrade defaults to the actual selected gateway. */
    public static boolean loadFujiAutoAddress(Context context) {
        return !"manual".equals(preferences(context).getString(FUJI_AUTO_ADDRESS, "auto"));
    }

    public static void saveFujiAutoAddress(Context context, boolean automatic) {
        preferences(context).edit().putString(FUJI_AUTO_ADDRESS, automatic ? "auto" : "manual").apply();
    }

    public static String loadHost(Context context, CameraProtocol protocol) {
        return preferences(context).getString(key(HOST, protocol), protocol == CameraProtocol.NIKON ? "" : protocol.defaultHost);
    }

    public static int loadPort(Context context) {
        try {
            int value = Integer.parseInt(loadPortText(context).trim());
            return value >= 1 && value <= 65535 ? value : loadProtocol(context).defaultPort;
        } catch (NumberFormatException invalid) {
            return loadProtocol(context).defaultPort;
        }
    }

    /** Keep an unfinished edit when the Activity is recreated. Validation happens on connect. */
    public static String loadPortText(Context context) {
        return loadPortText(context, loadProtocol(context));
    }

    public static String loadPortText(Context context, CameraProtocol protocol) {
        return preferences(context).getString(key(PORT, protocol), Integer.toString(protocol.defaultPort));
    }

    public static void save(Context context, String host, int port) {
        save(context, host, port, loadProtocol(context));
    }

    public static void save(Context context, String host, int port, CameraProtocol protocol) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid camera port");
        saveDraft(context, host == null ? "" : host.trim(), Integer.toString(port), protocol);
    }

    /** The connection editor and confirmed endpoints use the same two preference keys. */
    public static void saveDraft(Context context, String host, String port) {
        saveDraft(context, host, port, loadProtocol(context));
    }

    public static void saveDraft(Context context, String host, String port, CameraProtocol protocol) {
        preferences(context).edit()
                .putString(PROTOCOL, protocol.name())
                .putString(key(HOST, protocol), host == null ? "" : host)
                .putString(key(PORT, protocol), port == null ? "" : port)
                .apply();
    }

    private static String key(String key, CameraProtocol protocol) {
        return protocol == CameraProtocol.NIKON ? key : "fuji-xt20-" + key;
    }

    private static synchronized SharedPreferences preferences(Context context) {
        SharedPreferences current = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!current.contains(HOST) || !current.contains(PORT)) {
            // Activity.getPreferences() used MainActivity's local class name before
            // the connection editor and preset importer shared their configuration.
            SharedPreferences previous = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            SharedPreferences.Editor migration = current.edit();
            if (!current.contains(HOST)) {
                Object host = previous.getAll().get("preset-wifi-host");
                migration.putString(HOST, host instanceof String ? (String) host : "");
            }
            if (!current.contains(PORT)) {
                Object port = previous.getAll().get("preset-wifi-port");
                migration.putString(PORT, port instanceof String || port instanceof Number
                        ? port.toString() : Integer.toString(AlbumController.DEFAULT_PORT));
            }
            migration.apply();
        }
        return current;
    }
}
