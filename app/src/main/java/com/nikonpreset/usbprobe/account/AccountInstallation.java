package com.nikonpreset.usbprobe.account;

import android.content.Context;
import java.io.IOException;
import java.util.UUID;

/** Random installation hint for abuse controls; never an identity or a device hardware identifier. */
public final class AccountInstallation {
    private AccountInstallation() {}
    public static synchronized String id(Context context) throws IOException {
        android.content.SharedPreferences preferences = context.getSharedPreferences("account-installation", Context.MODE_PRIVATE);
        String value = preferences.getString("id", "");
        if (value != null && value.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) return value;
        value = UUID.randomUUID().toString();
        if (!preferences.edit().putString("id", value).commit()) throw new IOException("无法保存安全验证信息，请重试。");
        return value;
    }
}
