package com.nikonpreset.usbprobe.account;

import android.content.Context;

/** The app's sole account repository. All Activity instances share its generation and store lifecycle. */
public final class AndroidAccounts {
    private static final AccountRepositoryProvider SHARED = new AccountRepositoryProvider();
    private AndroidAccounts() { }

    public static AccountRepository get(Context context) {
        Context application = context.getApplicationContext();
        if (application == null) throw new IllegalArgumentException("Application context is required");
        return SHARED.get(() -> new AccountRepository(new AndroidAccountStore(application), new AccountHttpTransport()));
    }
}
