package com.nikonpreset.usbprobe.ui;

public final class AlbumPagingTest {
    private static int checks;

    public static void main(String[] args) {
        repeatedScrollCallbacksShareOneRequest();
        errorsPauseUntilExplicitRetry();
        emptyFilteredPagesCanContinue();
        nonAdvancingPagesPause();
        staleCompletionsCannotMutateNewRequests();
        exhaustedSnapshotsStop();
        System.out.println("PASS: " + checks + " album pagination checks");
    }

    private static void repeatedScrollCallbacksShareOneRequest() {
        AlbumPaging paging = new AlbumPaging();
        paging.reset(true);
        AlbumPaging.Request request = paging.begin();
        check(request != null && request.offset() == 0, "first page starts at zero");
        for (int i = 0; i < 20; i++) check(paging.begin() == null, "scroll does not duplicate active request");
        check(paging.isLoading(), "request remains active");
        equal(AlbumPaging.Result.ADVANCED, paging.succeed(request, 24, true));
        check(!paging.isLoading() && paging.cursor() == 24, "successful page advances cursor");
        AlbumPaging.Request next = paging.begin();
        check(next != null && next.offset() == 24, "next request captures new cursor");
        equal(AlbumPaging.Result.STALE, paging.succeed(request, 48, true));
        check(paging.isLoading() && paging.cursor() == 24, "duplicate completion does not finish next request");
    }

    private static void errorsPauseUntilExplicitRetry() {
        AlbumPaging paging = new AlbumPaging();
        paging.reset(true);
        AlbumPaging.Request request = paging.begin();
        check(paging.fail(request), "current failure is accepted");
        check(paging.isPaused() && !paging.isLoading() && paging.cursor() == 0, "failure preserves cursor");
        for (int i = 0; i < 20; i++) check(paging.begin() == null, "error never causes an automatic retry loop");
        paging.retry();
        AlbumPaging.Request retry = paging.begin();
        check(retry != null && retry.offset() == request.offset(), "explicit retry repeats failed cursor");
        check(!paging.fail(request), "old failure cannot cancel retry");
        check(paging.isLoading(), "retry remains active after stale failure");
        equal(AlbumPaging.Result.ADVANCED, paging.succeed(retry, 24, true));
        check(!paging.isPaused(), "successful retry resumes pagination");
    }

    private static void emptyFilteredPagesCanContinue() {
        AlbumPaging paging = new AlbumPaging();
        paging.reset(true);
        // No item count enters this decision: RAW-only pages still scan forwards.
        for (int nextOffset : new int[] {24, 48, 72}) {
            AlbumPaging.Request request = paging.begin();
            check(request != null, "filtered empty page does not stop scanning");
            equal(AlbumPaging.Result.ADVANCED, paging.succeed(request, nextOffset, true));
        }
        check(paging.cursor() == 72 && paging.hasMore(), "snapshot cursor follows handles, not visible JPEG count");
    }

    private static void nonAdvancingPagesPause() {
        for (int offset : new int[] {-1, 0}) {
            AlbumPaging paging = new AlbumPaging();
            paging.reset(true);
            equal(AlbumPaging.Result.STALLED, paging.succeed(paging.begin(), offset, true));
            check(paging.cursor() == 0 && paging.isPaused() && paging.begin() == null, "nonadvancing page cannot loop");
        }
        AlbumPaging paging = new AlbumPaging();
        paging.reset(true);
        equal(AlbumPaging.Result.ADVANCED, paging.succeed(paging.begin(), 24, true));
        equal(AlbumPaging.Result.STALLED, paging.succeed(paging.begin(), 12, false));
        check(paging.cursor() == 24 && paging.hasMore(), "regression never overwrites cursor or silently exhausts");
        paging.retry();
        equal(AlbumPaging.Result.EXHAUSTED, paging.succeed(paging.begin(), 24, false));
        check(!paging.hasMore() && !paging.isPaused(), "terminal empty page is valid without advancing");
    }

    private static void staleCompletionsCannotMutateNewRequests() {
        AlbumPaging paging = new AlbumPaging();
        paging.reset(true);
        AlbumPaging.Request old = paging.begin();
        paging.reset(true);
        AlbumPaging.Request current = paging.begin();
        equal(AlbumPaging.Result.STALE, paging.succeed(old, 999, false));
        check(!paging.fail(old), "previous snapshot failure is ignored even at same offset");
        equal(AlbumPaging.Result.STALE, paging.succeed(null, 999, false));
        check(!paging.fail(null), "null failure does not match an active request");
        check(paging.cursor() == 0 && paging.hasMore() && paging.isLoading(), "stale callbacks leave current snapshot intact");
        equal(AlbumPaging.Result.EXHAUSTED, paging.succeed(current, 4, false));
    }

    private static void exhaustedSnapshotsStop() {
        AlbumPaging paging = new AlbumPaging();
        check(paging.begin() == null, "uninitialized snapshot does not load");
        paging.reset(false);
        check(paging.begin() == null, "empty snapshot does not load");
        paging.reset(true);
        equal(AlbumPaging.Result.EXHAUSTED, paging.succeed(paging.begin(), 24, false));
        for (int i = 0; i < 20; i++) check(paging.begin() == null, "exhaustion survives scroll callbacks");
        paging.retry();
        check(paging.begin() == null, "retry cannot reopen an exhausted snapshot");
        paging.reset(true);
        check(paging.begin() != null && paging.cursor() == 0, "new snapshot can start again");
    }

    private static void equal(AlbumPaging.Result expected, AlbumPaging.Result actual) {
        check(expected == actual, "expected " + expected + ", got " + actual);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
