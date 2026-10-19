package com.nikonpreset.usbprobe.account;

/** One lifecycle owner for every account screen in a process; no Activity or executor is retained. */
final class AccountRepositoryProvider {
    interface Factory { AccountRepository create(); }
    private AccountRepository repository;

    synchronized AccountRepository get(Factory factory) {
        if (repository == null) repository = factory.create();
        return repository;
    }
}
