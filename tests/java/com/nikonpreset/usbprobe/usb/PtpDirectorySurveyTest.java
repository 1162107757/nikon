package com.nikonpreset.usbprobe.usb;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the production metadata-only scanner without Android, a camera or USB. */
public final class PtpDirectorySurveyTest {
    private static final long STORAGE = 0x00020001;
    private static final long ROOT = 0xffffffffL;
    private static final int HANDLES = 0x1007;
    private static final int INFO = 0x1008;
    private static int checks;

    public static void main(String[] args) throws Exception {
        allAssociationSelectorCanRevealRootHiddenDirectories();
        completeDcimOnlyViewsAreEvidenceOfVisibilityOnly();
        unsupportedSelectorsKeepTheirResponseAndLimitedFallback();
        otherResponsesAndMetadataErrorsStopWithoutNextQuery();
        rawMetadataFieldsAndSharedCacheArePreserved();
        invalidAndDuplicateIdentitiesStop();
        wrongSelectorFieldsAreReportedWithoutInventingSuccess();
        handleAndMetadataBudgetsAreExplicit();
        failureRetainsPartialRecords();
        deadlineStopsBeforeAnotherCommand();
        interruptionStopsBeforeAnotherCommand();
        System.out.println("PASS: " + checks + " read-only directory survey checks");
    }

    private static void allAssociationSelectorCanRevealRootHiddenDirectories() throws Exception {
        Fake camera = new Fake();
        camera.root(1);
        camera.associations(1, 2, 3);
        camera.object(1, folder(0, "DCIM"));
        camera.object(2, folder(0, "NIKON"));
        camera.object(3, folder(2, "CUSTOMPC"));
        PtpDirectorySurvey scanner = camera.scanner();
        PtpDirectorySurvey.Result result = scanner.scan();
        equal(result, scanner.result());
        equal(STORAGE, result.storageId);
        equal(2, result.queries.size());
        assertQuery(result.queries.get(0), "root", 0, ROOT, 1);
        assertQuery(result.queries.get(1), "all_associations", 0x3001, 0, 3);
        check(result.completed && result.error.isEmpty(), "Both directory views completed normally");
        check(result.nikonRootVisible && result.customPcPathVisible,
                "The all-association view can establish the visible NIKON/CUSTOMPC parent links");
        equal(1, result.additionalRootDirectories.size());
        equal("NIKON", result.additionalRootDirectories.get(0).name);
        equal(2L, result.additionalRootDirectories.get(0).handle);
        equal(3, result.metadataRequests);
        equal(1, result.queries.get(1).cacheHitCount);
        equal(2, result.queries.get(1).metadataReadCount);
        assertReadOnly(camera);
    }

    private static void completeDcimOnlyViewsAreEvidenceOfVisibilityOnly() throws Exception {
        Fake camera = new Fake();
        camera.root(1);
        camera.associations(1);
        camera.object(1, folder(0, "DCIM"));
        PtpDirectorySurvey.Result result = camera.scanner().scan();
        check(result.completed && result.error.isEmpty(), "A completed limited view is a valid diagnostic result");
        check(!result.nikonRootVisible && !result.customPcPathVisible,
                "Absent USB records are represented as visibility flags, not a physical-filesystem conclusion");
        equal(0, result.additionalRootDirectories.size());
        equal(2, result.queries.size());
        for (PtpDirectorySurvey.QueryResult query : result.queries) {
            equal("complete", query.status);
            check(query.complete && !query.truncated, "The limited directory views retain their complete status");
            equal(1, query.objects.size());
            equal("DCIM", query.objects.get(0).name);
        }
        equal(1, result.metadataRequests);
        equal(0, camera.selectorCalls(0, 0));
        assertReadOnly(camera);
    }

    private static void unsupportedSelectorsKeepTheirResponseAndLimitedFallback() throws Exception {
        for (int code : new int[] {0x2006, 0x2014}) {
            Fake camera = new Fake();
            camera.root(1);
            camera.associationFailure = new PtpResponseException(code, 71);
            camera.fallback(1, 2, 3);
            camera.object(1, folder(0, "DCIM"));
            camera.object(2, folder(0, "NIKON"));
            camera.object(3, folder(2, "CUSTOMPC"));
            PtpDirectorySurvey.Result result = camera.scanner().scan();
            equal(3, result.queries.size());
            PtpDirectorySurvey.QueryResult unsupported = result.queries.get(1);
            equal("all_associations", unsupported.label);
            equal("unsupported", unsupported.status);
            equal(code, unsupported.responseCode);
            check(!unsupported.complete && !unsupported.error.isEmpty(),
                    "Unsupported is retained as a camera response, never a successful empty list");
            assertQuery(result.queries.get(2), "all_objects_fallback", 0, 0, 3);
            check(result.completed && result.customPcPathVisible,
                    "The one bounded fallback can provide visibility evidence after a supported recovery");
            equal(1, camera.selectorCalls(0, 0));
            equal(3, result.metadataRequests);
            assertReadOnly(camera);

            Fake rootUnsupported = new Fake();
            rootUnsupported.rootFailure = new PtpResponseException(code, 99);
            rootUnsupported.associations();
            PtpDirectorySurvey.Result rootResult = rootUnsupported.scanner().scan();
            equal(2, rootResult.queries.size());
            equal("unsupported", rootResult.queries.get(0).status);
            equal(code, rootResult.queries.get(0).responseCode);
            equal("complete", rootResult.queries.get(1).status);
            equal(0, rootUnsupported.selectorCalls(0, 0));
            assertReadOnly(rootUnsupported);
        }
    }

    private static void otherResponsesAndMetadataErrorsStopWithoutNextQuery() throws Exception {
        for (int code : new int[] {0x2002, 0x2008, 0x2013, 0x2019}) {
            for (boolean atRoot : new boolean[] {true, false}) {
                Fake camera = new Fake();
                camera.root();
                IOException failure = new PtpResponseException(code, 321);
                if (atRoot) camera.rootFailure = failure;
                else camera.associationFailure = failure;
                PtpDirectorySurvey scanner = camera.scanner();
                expectSameFailure(failure, scanner::scan);
                PtpDirectorySurvey.Result result = scanner.result();
                check(!result.completed && !result.error.isEmpty(), "A failed list operation stays failed");
                equal(2, result.queries.size());
                PtpDirectorySurvey.QueryResult query = result.queries.get(atRoot ? 0 : 1);
                equal("error", query.status);
                equal(code, query.responseCode);
                equal(HANDLES, query.failedOperation);
                check(!query.complete && !query.error.isEmpty(), "A fatal query is not reduced to an empty directory");
                equal(0, camera.selectorCalls(0, 0));
                equal(atRoot ? 1 : 2, camera.calls.size());
                if (atRoot) equal("pending", result.queries.get(1).status);
                assertReadOnly(camera);
            }
        }
        for (int code : new int[] {0x2006, 0x2014}) {
            Fake camera = new Fake();
            camera.root(11);
            IOException failure = new PtpResponseException(code, 654);
            camera.objectFailures.put(11L, failure);
            PtpDirectorySurvey scanner = camera.scanner();
            expectSameFailure(failure, scanner::scan);
            equal(2, scanner.result().queries.size());
            PtpDirectorySurvey.QueryResult query = scanner.result().queries.get(0);
            equal("error", query.status);
            equal(code, query.responseCode);
            equal(INFO, query.failedOperation);
            equal(11L, query.failedHandle);
            equal(1, scanner.result().metadataRequests);
            equal(0, camera.selectorCalls(0x3001, 0));
            equal("pending", scanner.result().queries.get(1).status);
            assertReadOnly(camera);
        }
    }

    private static void rawMetadataFieldsAndSharedCacheArePreserved() throws Exception {
        Fake camera = new Fake();
        camera.root(44);
        camera.associations(44, 55);
        camera.object(44, new PtpObjectInfo.Builder().storageId(STORAGE).parentObject(ROOT)
                .filename("目录📷").objectFormat(0x3001).associationType(7).build());
        camera.object(55, folder(44, "CUSTOMPC"));
        PtpDirectorySurvey.Result result = camera.scanner().scan();
        PtpDirectorySurvey.ObjectRecord record = result.queries.get(0).objects.get(0);
        equal(44L, record.handle);
        equal(STORAGE, record.storageId);
        equal(ROOT, record.parentObject);
        equal("目录📷", record.name);
        equal(0x3001, record.objectFormat);
        equal(7, record.associationType);
        equal(2, result.metadataRequests);
        equal(1, result.queries.get(1).cacheHitCount);
        equal(1, camera.metadataCalls(44));
        equal(1, camera.metadataCalls(55));
        check(!result.nikonRootVisible && !result.customPcPathVisible,
                "Unrelated names or parent chains do not become the known Nikon preset path");
        assertReadOnly(camera);
    }

    private static void invalidAndDuplicateIdentitiesStop() throws Exception {
        for (long[] handles : new long[][] {{0}, {ROOT}, {7, 7}}) {
            Fake camera = new Fake();
            camera.root(handles);
            camera.object(7, folder(0, "DCIM"));
            PtpDirectorySurvey scanner = camera.scanner();
            expectFailure(scanner::scan);
            check(!scanner.result().completed && !scanner.result().error.isEmpty(),
                    "Invalid or duplicate handles fail with retained diagnostics");
            equal(2, scanner.result().queries.size());
            equal("error", scanner.result().queries.get(0).status);
            equal(0, camera.selectorCalls(0x3001, 0));
            check(scanner.result().metadataRequests <= 1, "No repeated metadata request is made for a duplicate handle");
            assertReadOnly(camera);
        }
        Fake crossStorage = new Fake();
        crossStorage.root(7);
        crossStorage.object(7, PtpObjectInfo.folder(STORAGE + 1, 0, "NIKON"));
        PtpDirectorySurvey scanner = crossStorage.scanner();
        expectFailure(scanner::scan);
        check(!scanner.result().completed && !scanner.result().nikonRootVisible,
                "Objects from another card cannot satisfy path visibility");
        equal(1, scanner.result().metadataRequests);
        equal(0, crossStorage.selectorCalls(0x3001, 0));
        assertReadOnly(crossStorage);

        Fake malformed = new Fake();
        malformed.rootDataset = handlesDataset(7);
        malformed.rootDataset[0] = 2; // Count advertises a second UINT32 that is absent.
        PtpDirectorySurvey malformedScanner = malformed.scanner();
        expectFailure(malformedScanner::scan);
        equal(1, malformed.calls.size());
        equal(0, malformedScanner.result().metadataRequests);
        equal("error", malformedScanner.result().queries.get(0).status);
    }

    private static void wrongSelectorFieldsAreReportedWithoutInventingSuccess() throws Exception {
        Fake camera = new Fake();
        camera.root(1);
        camera.associations(2);
        camera.object(1, folder(123, "NIKON"));
        camera.object(2, PtpObjectInfo.file(STORAGE, 0, "PICCON01.NCP", 638));
        PtpDirectorySurvey.Result result = camera.scanner().scan();
        equal(2, result.queries.size());
        for (PtpDirectorySurvey.QueryResult query : result.queries) {
            equal("selector_mismatch", query.status);
            check(!query.selectorHonored && !query.complete && !query.issues.isEmpty(),
                    "Unexpected parent/format is evidence that a selector was not honored");
            equal(1, query.objects.size());
        }
        equal(123L, result.queries.get(0).objects.get(0).parentObject);
        equal(0x3000, result.queries.get(1).objects.get(0).objectFormat);
        check(!result.nikonRootVisible && !result.customPcPathVisible,
                "A folder at an unrelated parent and a file are not the preset directory path");
        equal(0, camera.selectorCalls(0, 0));
        assertReadOnly(camera);
    }

    private static void handleAndMetadataBudgetsAreExplicit() throws Exception {
        equal(4096, PtpDirectorySurvey.MAX_HANDLES);
        equal(128, PtpDirectorySurvey.MAX_METADATA);
        equal(60000L, PtpDirectorySurvey.DEADLINE_MILLIS);
        for (int count : new int[] {PtpDirectorySurvey.MAX_METADATA + 1, PtpDirectorySurvey.MAX_HANDLES + 1, 65537}) {
            Fake camera = new Fake();
            camera.root(range(1, count));
            camera.associations();
            PtpDirectorySurvey.Result result = camera.scanner().scan();
            PtpDirectorySurvey.QueryResult query = result.queries.get(0);
            equal(count, query.handleCount);
            check(query.handlesRead && query.truncated && !query.complete,
                    "An oversized query is explicitly truncated before any of its metadata requests");
            equal("truncated", query.status);
            equal(0, query.objects.size());
            equal(0, query.metadataReadCount);
            equal(0, result.metadataRequests);
            equal(2, camera.calls.size());
            assertReadOnly(camera);
        }

        Fake budget = new Fake();
        budget.root(range(1, 80));
        budget.associations(range(81, 80));
        for (int i = 1; i <= 160; i++) budget.object(i, folder(0, "FOLDER" + i));
        PtpDirectorySurvey.Result result = budget.scanner().scan();
        equal(PtpDirectorySurvey.MAX_METADATA, result.metadataRequests);
        equal(80, result.queries.get(0).metadataReadCount);
        equal(48, result.queries.get(1).metadataReadCount);
        equal(80, result.queries.get(1).handleCount);
        equal(48, result.queries.get(1).objects.size());
        check(result.queries.get(1).truncated && !result.queries.get(1).complete,
                "The second selector shares the global 128-request metadata budget");
        equal("truncated", result.queries.get(1).status);
        equal(130, budget.calls.size());
        assertReadOnly(budget);
    }

    private static void failureRetainsPartialRecords() throws Exception {
        Fake camera = new Fake();
        camera.root(1, 2);
        camera.object(1, folder(0, "DCIM"));
        IOException failure = new IOException("simulated metadata transport failure");
        camera.objectFailures.put(2L, failure);
        PtpDirectorySurvey scanner = camera.scanner();
        expectSameFailure(failure, scanner::scan);
        PtpDirectorySurvey.Result result = scanner.result();
        equal(2, result.metadataRequests);
        equal(2, result.queries.size());
        PtpDirectorySurvey.QueryResult query = result.queries.get(0);
        check(query.handlesRead && !query.complete, "A metadata failure retains the successful handles phase");
        equal(2, query.handleCount);
        equal(1, query.objects.size());
        equal("DCIM", query.objects.get(0).name);
        equal(INFO, query.failedOperation);
        equal(2L, query.failedHandle);
        equal(-1, query.responseCode);
        check(!result.error.isEmpty() && !query.error.isEmpty(), "Partial results retain the actual transport failure");
        equal(0, camera.selectorCalls(0x3001, 0));
        assertReadOnly(camera);
    }

    private static void deadlineStopsBeforeAnotherCommand() throws Exception {
        Fake camera = new Fake();
        camera.root(1);
        camera.object(1, folder(0, "DCIM"));
        camera.advanceAfterCall = 1;
        camera.advanceToMillis = PtpDirectorySurvey.DEADLINE_MILLIS + 1;
        PtpDirectorySurvey scanner = camera.scanner();
        expectFailure(scanner::scan);
        PtpDirectorySurvey.Result result = scanner.result();
        check(result.deadlineExceeded && !result.completed && !result.error.isEmpty(),
                "A bounded monotonic-time deadline is recorded as a stopped scan");
        check(result.elapsedMillis >= PtpDirectorySurvey.DEADLINE_MILLIS,
                "Elapsed time retains the observed monotonic deadline crossing");
        equal(1, camera.calls.size());
        equal(0, result.metadataRequests);
        assertReadOnly(camera);
    }

    private static void interruptionStopsBeforeAnotherCommand() throws Exception {
        Fake preInterrupted = new Fake();
        PtpDirectorySurvey scanner = preInterrupted.scanner();
        Thread.currentThread().interrupt();
        try {
            expectFailure(scanner::scan);
            check(scanner.result().interrupted && !scanner.result().completed,
                    "A pre-existing interrupt cancels the scan before USB requests");
            equal(0, preInterrupted.calls.size());
        } finally {
            Thread.interrupted();
        }
        Fake duringQuery = new Fake();
        duringQuery.root(1);
        duringQuery.object(1, folder(0, "DCIM"));
        duringQuery.interruptAfterCall = 1;
        PtpDirectorySurvey duringScanner = duringQuery.scanner();
        try {
            expectFailure(duringScanner::scan);
            check(duringScanner.result().interrupted && !duringScanner.result().completed,
                    "An interrupt raised during a response stops before the next metadata request");
            equal(1, duringQuery.calls.size());
            equal(0, duringScanner.result().metadataRequests);
        } finally {
            Thread.interrupted();
        }
        assertReadOnly(duringQuery);
    }

    private static void assertQuery(PtpDirectorySurvey.QueryResult query, String label,
                                     int format, long parent, int count) {
        equal(label, query.label);
        equal(format, query.formatCode);
        equal(parent, query.parentHandle);
        equal(count, query.handleCount);
        equal("complete", query.status);
        check(query.handlesRead && query.complete && !query.truncated && query.selectorHonored,
                "A successful selector retains all completion evidence");
        equal(count, query.objects.size());
    }

    private static void assertReadOnly(Fake camera) {
        for (Call call : camera.calls) {
            check(call.operation == HANDLES || call.operation == INFO,
                    "Only GetObjectHandles and GetObjectInfo may leave the directory scanner");
            if (call.operation == HANDLES) {
                equal(3, call.parameters.length);
                equal(STORAGE, call.parameters[0]);
            } else equal(1, call.parameters.length);
        }
    }

    private static PtpObjectInfo folder(long parent, String name) {
        return PtpObjectInfo.folder(STORAGE, parent, name);
    }

    private static long[] range(int first, int count) {
        long[] handles = new long[count];
        for (int i = 0; i < count; i++) handles[i] = first + i;
        return handles;
    }

    private static byte[] handlesDataset(long... handles) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        u32(output, handles.length);
        for (long handle : handles) u32(output, handle);
        return output.toByteArray();
    }

    private static void u32(ByteArrayOutputStream output, long value) {
        for (int i = 0; i < 4; i++) output.write((int) (value >>> (8 * i)));
    }

    private static final class Fake implements PtpDirectorySurvey.Operations, PtpDirectorySurvey.Clock {
        final Map<Long, byte[]> objects = new LinkedHashMap<>();
        final Map<Long, IOException> objectFailures = new LinkedHashMap<>();
        final List<Call> calls = new ArrayList<>();
        byte[] rootDataset = handlesDataset();
        byte[] associationDataset = handlesDataset();
        byte[] fallbackDataset = handlesDataset();
        IOException rootFailure;
        IOException associationFailure;
        IOException fallbackFailure;
        int advanceAfterCall = -1;
        long advanceToMillis;
        int interruptAfterCall = -1;
        long now;

        PtpDirectorySurvey scanner() { return new PtpDirectorySurvey(this, STORAGE, this); }
        void root(long... handles) { rootDataset = handlesDataset(handles); }
        void associations(long... handles) { associationDataset = handlesDataset(handles); }
        void fallback(long... handles) { fallbackDataset = handlesDataset(handles); }
        void object(long handle, PtpObjectInfo info) { objects.put(handle, info.toBytes()); }
        @Override public long elapsedRealtimeMillis() { return now; }

        int selectorCalls(long format, long parent) {
            int count = 0;
            for (Call call : calls) if (call.operation == HANDLES && call.parameters[1] == format
                    && call.parameters[2] == parent) count++;
            return count;
        }

        int metadataCalls(long handle) {
            int count = 0;
            for (Call call : calls) if (call.operation == INFO && call.parameters[0] == handle) count++;
            return count;
        }

        @Override public byte[] getData(int operation, long... parameters) throws IOException {
            calls.add(new Call(operation, parameters));
            byte[] result;
            if (operation == HANDLES) {
                equal(3, parameters.length);
                equal(STORAGE, parameters[0]);
                if (parameters[1] == 0 && parameters[2] == ROOT) {
                    if (rootFailure != null) throw rootFailure;
                    result = rootDataset;
                } else if (parameters[1] == 0x3001 && parameters[2] == 0) {
                    if (associationFailure != null) throw associationFailure;
                    result = associationDataset;
                } else if (parameters[1] == 0 && parameters[2] == 0) {
                    if (fallbackFailure != null) throw fallbackFailure;
                    result = fallbackDataset;
                } else throw new AssertionError("Unexpected handles selector " + Arrays.toString(parameters));
            } else if (operation == INFO) {
                equal(1, parameters.length);
                if (objectFailures.containsKey(parameters[0])) throw objectFailures.get(parameters[0]);
                result = objects.get(parameters[0]);
                if (result == null) throw new AssertionError("Unexpected metadata handle " + parameters[0]);
            } else throw new AssertionError("Scanner attempted a content, session or write command " + operation);
            if (calls.size() == advanceAfterCall) now = advanceToMillis;
            if (calls.size() == interruptAfterCall) Thread.currentThread().interrupt();
            return result.clone();
        }
    }

    private static final class Call {
        final int operation;
        final long[] parameters;
        Call(int operation, long[] parameters) {
            this.operation = operation;
            this.parameters = parameters.clone();
        }
    }

    private interface Action { Object run() throws Exception; }

    private static void expectFailure(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected a stopped directory survey");
    }

    private static void expectSameFailure(IOException failure, Action action) throws Exception {
        try { action.run(); }
        catch (IOException actual) {
            check(actual == failure, "The original camera/transport failure is propagated without replacement");
            return;
        }
        throw new AssertionError("Expected the original directory-query failure");
    }

    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
