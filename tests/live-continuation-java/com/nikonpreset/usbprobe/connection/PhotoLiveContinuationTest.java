package com.nikonpreset.usbprobe.connection;

public final class PhotoLiveContinuationTest {
    private static int checks;

    public static void main(String[] args) {
        requiresExplicitConnectionAttempt();
        failedConnectionCanRetryWithoutConsumption();
        successIsConsumedOnce();
        cancellationInvalidatesQueuedWork();
        replacementIntentCannotUseOldAuthorization();
        attemptsWithoutIntentDoNothing();
        System.out.println("PASS: " + checks + " photo-live continuation checks");
    }

    private static void requiresExplicitConnectionAttempt() {
        PhotoLiveContinuation intent = new PhotoLiveContinuation();
        long token = intent.request();
        check(!intent.consume(token) && intent.matches(token),
                "Opening connection settings alone cannot consume or discard the live intent");
        check(!intent.isConnectionAccepted() && intent.revision() == token,
                "An unaccepted continuation keeps its token for a later explicit connect");
        intent.connectionAttempted();
        check(intent.consume(token), "Explicitly attempting connection makes the same intent eligible");
    }

    private static void failedConnectionCanRetryWithoutConsumption() {
        PhotoLiveContinuation intent = new PhotoLiveContinuation();
        long token = intent.request();
        intent.connectionAttempted();
        // A failed connect reports its error without calling consume().
        check(intent.matches(token) && intent.isConnectionAccepted(),
                "A failed attempt leaves the accepted live intent available for retry");
        intent.connectionAttempted();
        check(intent.revision() == token && intent.consume(token),
                "Retrying connection does not replace the pending intent or lose its token");
    }

    private static void successIsConsumedOnce() {
        PhotoLiveContinuation intent = new PhotoLiveContinuation();
        long token = intent.request();
        intent.connectionAttempted();
        check(intent.consume(token), "A successful eligible continuation can claim the intent");
        check(!intent.consume(token) && !intent.matches(token),
                "A duplicate success callback cannot claim the same intent twice");
        check(!intent.isPending() && !intent.isConnectionAccepted() && intent.revision() > token,
                "Consumption clears connection authorization and invalidates queued work");
        intent.connectionAttempted();
        check(!intent.consume(intent.revision()), "A late connection callback cannot rearm a consumed intent");
    }

    private static void cancellationInvalidatesQueuedWork() {
        PhotoLiveContinuation intent = new PhotoLiveContinuation();
        long queuedToken = intent.request();
        intent.connectionAttempted();
        intent.cancel();
        check(!intent.matches(queuedToken) && !intent.consume(queuedToken),
                "Cancelling after a connect attempt invalidates its queued continuation");
        check(!intent.isPending() && !intent.isConnectionAccepted() && intent.revision() > queuedToken,
                "Cancellation clears the request and its previous authorization together");
        long newToken = intent.request();
        intent.connectionAttempted();
        check(!intent.consume(queuedToken) && intent.matches(newToken),
                "Old work delivered after a new request cannot consume that new request");
        check(intent.consume(newToken), "The new request remains consumable by its own continuation");
    }

    private static void replacementIntentCannotUseOldAuthorization() {
        PhotoLiveContinuation intent = new PhotoLiveContinuation();
        long oldToken = intent.request();
        intent.connectionAttempted();
        long replacement = intent.request();
        check(replacement > oldToken && !intent.consume(oldToken),
                "Requesting live again supersedes already-queued work from the old request");
        check(!intent.consume(replacement) && intent.matches(replacement),
                "The old request's accepted connection does not authorize its replacement");
        intent.connectionAttempted();
        check(intent.consume(replacement), "An explicit attempt for the replacement authorizes only its token");
    }

    private static void attemptsWithoutIntentDoNothing() {
        PhotoLiveContinuation intent = new PhotoLiveContinuation();
        long initial = intent.revision();
        intent.connectionAttempted();
        check(!intent.isPending() && !intent.isConnectionAccepted() && intent.revision() == initial
                        && !intent.consume(initial),
                "An ordinary camera connection cannot create a live continuation");
        intent.cancel();
        long cancelled = intent.revision();
        intent.connectionAttempted();
        check(cancelled > initial && !intent.matches(cancelled) && !intent.isConnectionAccepted()
                        && intent.revision() == cancelled && !intent.consume(cancelled),
                "Cancelling an idle intent invalidates tokens and a late attempt cannot restore it");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
