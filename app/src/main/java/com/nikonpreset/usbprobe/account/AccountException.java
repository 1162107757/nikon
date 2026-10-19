package com.nikonpreset.usbprobe.account;

import java.io.IOException;

/** Safe, user-facing account failure. Messages never include a server response or credentials. */
public final class AccountException extends IOException {
    public final String code;
    public final int status;

    public AccountException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
