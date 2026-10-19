package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.content.SharedPreferences;
import com.nikonpreset.usbprobe.album.AlbumController;

/** One address shared by camera connection, photo browsing, and preset import. */
public final class CameraConnectionPreferences {
    private static final String PREFERENCES = "camera-connection";
    private static final String HOST = "host";
    private static final String PORT = "port";

    private CameraConnectionPreferences() { }

    public static String loadHost(Context context) {
        return preferences(context).getString(HOST, "");
    }

    public static int loadPort(Context context) {
        try {
            int value = Integer.parseInt(loadPortText(context).trim());
            return value >= 1 && value <= 65535 ? value : AlbumController.DEFAULT_PORT;
        } catch (NumberFormatException invalid) {
            return AlbumController.DEFAULT_PORT;
        }
    }

    /** Keep an unfinished edit when the Activity is recreated. Validation happens on connect. */
    public static String loadPortText(Context context) {
        return preferences(context).getString(PORT, Integer.toString(AlbumController.DEFAULT_PORT));
    }

    public static void save(Context context, String host, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid camera port");
        saveDraft(context, host == null ? "" : host.trim(), Integer.toString(port));
    }

    /** The connection editor and confirmed endpoints use the same two preference keys. */
    public static void saveDraft(Context context, String host, String port) {
        preferences(context).edit()
                .putString(HOST, host == null ? "" : host)
                .putString(PORT, port == null ? "" : port)
                .apply();
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
