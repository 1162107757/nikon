package cc.huevia.account;

/** Pure state regression: no Android stubs, provider credentials, SDK replacement or network. */
public final class QqAuthorizationGateTest {
    private static int checks;
    public static void main(String[] args) {
        cancellationBlocksNewAttemptUntilOldResultDrains();
        providerOutcomesDrainTheirOwnAbandonedAttempt();
        launchFailuresReleaseWithoutWaitingForAResult();
        staleCloseAndCallbackDoNotChangeNewAttempt();
        recreationDiscardsAbandonedResult();
        System.out.println("QqAuthorizationGateTest: " + checks + " checks passed");
    }
    private static void cancellationBlocksNewAttemptUntilOldResultDrains() {
        QqAuthorizationGate gate = new QqAuthorizationGate();
        long a = gate.begin();
        check(a > 0 && gate.canAcceptResult(a), "A starts");
        check(gate.begin() == 0, "duplicate start blocked");
        gate.abandon(a);
        check(!gate.canAcceptResult(a), "cancelled A result cannot be delivered");
        check(gate.begin() == 0, "B blocked while A result can still arrive");
        long discard = gate.discardableResult(a);
        check(discard == a, "late A result identified for discard");
        gate.complete(discard); // Controller clears temporary SDK session before releasing this boundary.
        long b = gate.begin();
        check(b > a && gate.canAcceptResult(b), "B starts after late A result is discarded");
    }
    private static void providerOutcomesDrainTheirOwnAbandonedAttempt() {
        for (String outcome : new String[] {"onComplete", "onCancel", "onError"}) {
            QqAuthorizationGate gate = new QqAuthorizationGate();
            long a = gate.begin(); gate.abandon(a);
            check(gate.begin() == 0, outcome + " pending blocks new attempt");
            gate.complete(a); // Each real SDK listener path calls finishProvider for its captured attempt.
            check(gate.begin() > a, outcome + " releases abandoned boundary");
        }
    }
    private static void launchFailuresReleaseWithoutWaitingForAResult() {
        for (String failure : new String[] {"negative SDK return", "SDK throws before launch", "initialization fails"}) {
            QqAuthorizationGate gate = new QqAuthorizationGate();
            long a = gate.begin(); gate.complete(a);
            check(gate.begin() > a, failure + " must allow retry");
        }
    }
    private static void staleCloseAndCallbackDoNotChangeNewAttempt() {
        QqAuthorizationGate gate = new QqAuthorizationGate();
        long a = gate.begin(); gate.complete(a);
        long b = gate.begin();
        gate.abandon(a); // Old Activity closes after its provider outcome.
        check(gate.canAcceptResult(b), "old close does not abandon B");
        gate.complete(a); // An old duplicate callback may not unlock B.
        check(gate.begin() == 0 && gate.canAcceptResult(b), "old callback does not release B");
        check(gate.discardableResult(a) == 0, "old Activity result cannot drain active B");
        gate.complete(b);
        check(gate.begin() > b, "B's own completion releases B");
    }
    private static void recreationDiscardsAbandonedResult() {
        QqAuthorizationGate gate = new QqAuthorizationGate();
        long a = gate.begin(); gate.abandon(a);
        check(gate.discardableResult(0) == a, "recreated Activity can discard abandoned result");
        check(!gate.canAcceptResult(0), "recreated Activity never delivers old authorization");
        gate.complete(gate.discardableResult(0));
        check(gate.begin() > a, "discard after recreation permits fresh login");
        QqAuthorizationGate active = new QqAuthorizationGate();
        active.begin();
        check(active.discardableResult(0) == 0, "unrelated Activity cannot drain an active attempt");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
