package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.content.SharedPreferences;
import com.nikonpreset.usbprobe.album.CameraProtocol;
import java.util.Map;

/** Production migration, separate protocol endpoints and unfinished-edit preservation. */
public final class CameraConnectionPreferencesTest {
    private static int checks;
    public static void main(String[] args) {
        defaultsAndLegacyMigration();
        independentEndpointsAndReconnect();
        unfinishedEditsSurviveRecreation();
        invalidPortsUseEachSelectedProtocolDefault();
        unknownProtocolAndRejectedSaves();
        fujiAddressModeMigrationAndIsolation();
        System.out.println("PASS: " + checks + " camera connection preference checks");
    }

    private static void defaultsAndLegacyMigration() {
        Context fresh = new Context();
        check(CameraConnectionPreferences.loadProtocol(fresh) == CameraProtocol.NIKON, "Existing users default to Nikon");
        check(CameraConnectionPreferences.loadHost(fresh).isEmpty(), "Fresh Nikon host retains the existing editor suggestion behavior");
        check(CameraConnectionPreferences.loadPort(fresh) == 15740, "Fresh Nikon port remains 15740");
        CameraConnectionPreferences.selectProtocol(fresh, CameraProtocol.FUJIFILM_X_T20);
        check(CameraConnectionPreferences.loadHost(fresh).isEmpty(), "Fresh Fuji has no hardcoded address before selected-network gateway resolution");
        check(CameraConnectionPreferences.loadFujiAutoAddress(fresh), "Fresh Fuji uses automatic selected-network gateway resolution");
        check(CameraConnectionPreferences.loadPort(fresh) == 55740, "Fuji has its own default port");
        check(CameraConnectionPreferences.loadHost(fresh, CameraProtocol.NIKON).isEmpty(), "Selecting Fuji never writes its default into the Nikon endpoint");

        Context legacy = new Context();
        legacy.getSharedPreferences("MainActivity", 0).edit().putString("preset-wifi-host", "192.168.8.1")
                .putInt("preset-wifi-port", 15741).apply();
        check(CameraConnectionPreferences.loadHost(legacy).equals("192.168.8.1"), "Legacy Nikon hostname migrates");
        check(CameraConnectionPreferences.loadPort(legacy) == 15741, "Legacy numeric port migrates without changing its value");
        check(CameraConnectionPreferences.loadPortText(legacy).equals("15741"), "Migrated numeric port becomes an editable string");
        CameraConnectionPreferences.selectProtocol(legacy, CameraProtocol.FUJIFILM_X_T20);
        check(CameraConnectionPreferences.loadHost(legacy).isEmpty(), "Nikon migration does not populate or guess a Fuji endpoint");

        Context partial = new Context();
        raw(partial).edit().putString("host", "192.168.9.1").apply();
        partial.getSharedPreferences("MainActivity", 0).edit().putString("preset-wifi-host", "192.168.8.1")
                .putString("preset-wifi-port", "16500").apply();
        check(CameraConnectionPreferences.loadHost(partial).equals("192.168.9.1"), "Partial migration preserves an existing modern Nikon host");
        check(CameraConnectionPreferences.loadPort(partial) == 16500, "Partial migration fills only the missing port");

        Context malformed = new Context();
        malformed.getSharedPreferences("MainActivity", 0).edit().putInt("preset-wifi-host", 123)
                .putBoolean("preset-wifi-port", true).apply();
        check(CameraConnectionPreferences.loadHost(malformed).isEmpty(), "Unsupported legacy host type falls back safely");
        check(CameraConnectionPreferences.loadPort(malformed) == 15740, "Unsupported legacy port type uses Nikon default");
    }

    private static void independentEndpointsAndReconnect() {
        Context context = new Context();
        CameraConnectionPreferences.save(context, " 192.168.10.1 ", 16000, CameraProtocol.NIKON);
        CameraConnectionPreferences.save(context, "192.168.20.1", 55800, CameraProtocol.FUJIFILM_X_T20);
        check(CameraConnectionPreferences.loadProtocol(context) == CameraProtocol.FUJIFILM_X_T20, "Explicit Fuji save selects Fuji");
        check(CameraConnectionPreferences.loadHost(context).equals("192.168.20.1")
                && CameraConnectionPreferences.loadPort(context) == 55800, "Selected Fuji exposes its own endpoint");
        check(CameraConnectionPreferences.loadHost(context, CameraProtocol.NIKON).equals("192.168.10.1")
                && CameraConnectionPreferences.loadPortText(context, CameraProtocol.NIKON).equals("16000"), "Fuji save preserves trimmed Nikon endpoint");
        CameraConnectionPreferences.selectProtocol(context, CameraProtocol.NIKON);
        check(CameraConnectionPreferences.loadHost(context).equals("192.168.10.1"), "Switching back restores Nikon endpoint");
        CameraConnectionPreferences.selectProtocol(context, CameraProtocol.FUJIFILM_X_T20);
        CameraConnectionPreferences.save(context, "192.168.30.1", 17000, CameraProtocol.NIKON);
        check(CameraConnectionPreferences.loadProtocol(context) == CameraProtocol.NIKON, "An explicit Nikon reconnect selects Nikon even after Fuji was selected");
        check(CameraConnectionPreferences.loadHost(context).equals("192.168.30.1") && CameraConnectionPreferences.loadPort(context) == 17000, "Nikon reconnect updates only Nikon endpoint");
        check(CameraConnectionPreferences.loadHost(context, CameraProtocol.FUJIFILM_X_T20).equals("192.168.20.1")
                && CameraConnectionPreferences.loadPortText(context, CameraProtocol.FUJIFILM_X_T20).equals("55800"), "Nikon reconnect leaves Fuji endpoint unchanged");
        CameraConnectionPreferences.selectProtocol(context, CameraProtocol.FUJIFILM_X_T20);
        CameraConnectionPreferences.save(context, "192.168.40.1", 55900);
        check(CameraConnectionPreferences.loadHost(context).equals("192.168.40.1")
                && CameraConnectionPreferences.loadHost(context, CameraProtocol.NIKON).equals("192.168.30.1"), "Compatibility save overload respects the selected protocol without overwriting Nikon");
    }

    private static void unfinishedEditsSurviveRecreation() {
        Context context = new Context();
        CameraConnectionPreferences.saveDraft(context, "192.168.1.", "15x", CameraProtocol.NIKON);
        CameraConnectionPreferences.saveDraft(context, "192.168.0.", "557x", CameraProtocol.FUJIFILM_X_T20);
        Context recreated = context.recreated();
        check(CameraConnectionPreferences.loadProtocol(recreated) == CameraProtocol.FUJIFILM_X_T20, "Selection survives Context recreation");
        check(CameraConnectionPreferences.loadHost(recreated).equals("192.168.0.")
                && CameraConnectionPreferences.loadPortText(recreated).equals("557x"), "Fuji unfinished address and port remain editable");
        check(CameraConnectionPreferences.loadPort(recreated) == 55740, "Unfinished Fuji port uses Fuji operational default");
        CameraConnectionPreferences.selectProtocol(recreated, CameraProtocol.NIKON);
        check(CameraConnectionPreferences.loadHost(recreated).equals("192.168.1.")
                && CameraConnectionPreferences.loadPortText(recreated).equals("15x"), "Switching after recreation restores the Nikon draft exactly");
        check(CameraConnectionPreferences.loadPort(recreated) == 15740, "Unfinished Nikon port uses Nikon operational default");
        CameraConnectionPreferences.saveDraft(recreated, "192.168.", "1");
        check(CameraConnectionPreferences.loadHost(recreated, CameraProtocol.FUJIFILM_X_T20).equals("192.168.0.")
                && CameraConnectionPreferences.loadPortText(recreated, CameraProtocol.FUJIFILM_X_T20).equals("557x"), "Editing selected Nikon draft cannot alter Fuji draft");
    }

    private static void invalidPortsUseEachSelectedProtocolDefault() {
        for (CameraProtocol protocol : CameraProtocol.values()) {
            Context context = new Context();
            for (String invalid : new String[] {"", "-1", "0", "65536", "not-a-port", "999999999999999999999999"}) {
                CameraConnectionPreferences.saveDraft(context, "192.168.0.1", invalid, protocol);
                check(CameraConnectionPreferences.loadPort(context) == protocol.defaultPort, "Invalid port uses the selected model's default");
                check(CameraConnectionPreferences.loadPortText(context).equals(invalid), "Invalid draft text is retained for correction");
            }
            CameraConnectionPreferences.saveDraft(context, "192.168.0.1", " 45678 ", protocol);
            check(CameraConnectionPreferences.loadPort(context) == 45678, "Valid editable port accepts surrounding whitespace");
        }
    }

    private static void unknownProtocolAndRejectedSaves() {
        Context context = new Context();
        CameraConnectionPreferences.save(context, "192.168.50.1", 18000, CameraProtocol.NIKON);
        raw(context).edit().putString("protocol", "future-unknown-camera").apply();
        check(CameraConnectionPreferences.loadProtocol(context) == CameraProtocol.NIKON, "Unknown stored protocol falls back to Nikon");
        check(CameraConnectionPreferences.loadHost(context).equals("192.168.50.1") && CameraConnectionPreferences.loadPort(context) == 18000, "Unknown protocol fallback preserves existing Nikon endpoint");
        CameraConnectionPreferences.selectProtocol(context, CameraProtocol.FUJIFILM_X_T20);
        for (int invalid : new int[] {0, 65536}) {
            Map<String, ?> before = raw(context).getAll();
            try {
                CameraConnectionPreferences.save(context, "192.168.99.1", invalid, CameraProtocol.NIKON);
                throw new AssertionError("Invalid confirmed port was accepted");
            } catch (IllegalArgumentException expected) { check(before.equals(raw(context).getAll()), "Rejected confirmed endpoint cannot change selection or either saved camera"); }
        }
    }

    private static void fujiAddressModeMigrationAndIsolation() {
        Context context = new Context();
        CameraConnectionPreferences.save(context, "192.168.10.1", 16000, CameraProtocol.NIKON);
        raw(context).edit().putString("protocol", CameraProtocol.FUJIFILM_X_T20.name())
                .putString("fuji-xt20-host", "192.168.0.1").putString("fuji-xt20-port", "55740").apply();
        check(CameraConnectionPreferences.loadFujiAutoAddress(context), "An old saved Fuji endpoint upgrades to automatic gateway mode without relying on that address");
        check(CameraConnectionPreferences.loadHost(context, CameraProtocol.NIKON).equals("192.168.10.1")
                && CameraConnectionPreferences.loadPortText(context, CameraProtocol.NIKON).equals("16000"), "Fuji address-mode migration preserves Nikon endpoint");
        CameraConnectionPreferences.saveFujiAutoAddress(context, false);
        Context recreated = context.recreated();
        check(!CameraConnectionPreferences.loadFujiAutoAddress(recreated), "Explicit manual Fuji address mode persists across Context recreation");
        check(CameraConnectionPreferences.loadProtocol(recreated) == CameraProtocol.FUJIFILM_X_T20, "Saving Fuji address mode does not change the selected camera protocol");
        CameraConnectionPreferences.save(recreated, "192.168.186.99", 55740, CameraProtocol.FUJIFILM_X_T20);
        check(!CameraConnectionPreferences.loadFujiAutoAddress(recreated), "Saving a manual Fuji endpoint does not silently turn automatic resolution back on");
        CameraConnectionPreferences.save(recreated, "192.168.30.1", 17000, CameraProtocol.NIKON);
        check(!CameraConnectionPreferences.loadFujiAutoAddress(recreated), "Nikon reconnect leaves the independent Fuji manual choice unchanged");
        check(CameraConnectionPreferences.loadHost(recreated, CameraProtocol.FUJIFILM_X_T20).equals("192.168.186.99"), "Nikon reconnect preserves the manual Fuji endpoint");
        CameraConnectionPreferences.saveFujiAutoAddress(recreated, true);
        check(CameraConnectionPreferences.loadFujiAutoAddress(recreated.recreated()), "Explicit automatic Fuji mode persists");
        check(CameraConnectionPreferences.loadProtocol(recreated) == CameraProtocol.NIKON
                && CameraConnectionPreferences.loadHost(recreated).equals("192.168.30.1")
                && CameraConnectionPreferences.loadPort(recreated) == 17000, "Toggling Fuji address mode cannot select Fuji or overwrite the Nikon endpoint");
        check(CameraConnectionPreferences.loadHost(recreated, CameraProtocol.FUJIFILM_X_T20).equals("192.168.186.99"), "Automatic mode does not destroy a user's saved manual endpoint");
        CameraConnectionPreferences.saveDraft(recreated, "192.168.90.", "557x", CameraProtocol.FUJIFILM_X_T20);
        CameraConnectionPreferences.saveFujiAutoAddress(recreated, false);
        CameraConnectionPreferences.saveFujiAutoAddress(recreated, true);
        check(CameraConnectionPreferences.loadHost(recreated).equals("192.168.90.")
                && CameraConnectionPreferences.loadPortText(recreated).equals("557x"), "Manual/automatic toggles preserve unfinished Fuji edits");
        raw(recreated).edit().putString("fuji-xt20-auto-address", "future-unknown-mode").apply();
        check(CameraConnectionPreferences.loadFujiAutoAddress(recreated), "Unknown future address mode falls back to automatic gateway resolution");
    }
    private static SharedPreferences raw(Context context) { return context.getSharedPreferences("camera-connection", 0); }
    private static void check(boolean passed, String detail) { if (!passed) throw new AssertionError(detail); checks++; }
}
