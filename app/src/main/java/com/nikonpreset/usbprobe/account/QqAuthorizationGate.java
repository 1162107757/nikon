package com.nikonpreset.usbprobe.account;

/** Main-thread state only. QQ uses one fixed request code, so abandoned attempts must drain first. */
final class QqAuthorizationGate {
    private long sequence, pending;
    private boolean accepting;

    long begin() {
        if (pending != 0) return 0;
        pending = ++sequence;
        accepting = true;
        return pending;
    }
    boolean isCurrent(long attempt) { return attempt != 0 && pending == attempt; }
    void abandon(long attempt) { if (isCurrent(attempt)) accepting = false; }
    /** Call only after clearing this provider attempt's temporary SDK session/cache. */
    void complete(long attempt) {
        if (isCurrent(attempt)) { pending = 0; accepting = false; }
    }
    /** A late result for an abandoned Activity must be consumed without giving it to another listener. */
    boolean canAcceptResult(long ownerAttempt) { return accepting && isCurrent(ownerAttempt); }
    long discardableResult(long receiverAttempt) {
        return pending != 0 && (!accepting || pending == receiverAttempt) ? pending : 0;
    }
    long pendingAttempt() { return pending; }
}
