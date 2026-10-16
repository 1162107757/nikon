# Pure Java PTP core

This code is original, dependency-free Java 17. It models standard USB PTP containers and parses the standard GetDeviceInfo, GetStorageIDs and GetStorageInfo datasets. Android classes are excluded, so the protocol core can be tested without a phone or Android SDK.

## Public API

All classes use the package `com.nikonpreset.usbprobe.ptp`.

```java
byte[] command = PtpContainer.command(PtpConstants.GET_DEVICE_INFO, 0).toBytes();
PtpContainerReader reader = new PtpContainerReader(byteSource, 1024 * 1024);
PtpContainer first = reader.read();
byte[] dataset = PtpValidation.requireData(first, 0, PtpConstants.GET_DEVICE_INFO);
PtpValidation.requireOkResponse(reader.read(), 0);
PtpDeviceInfo info = PtpDeviceInfo.parse(dataset);
```

`PtpContainerReader` accepts either an `InputStream` or a `ByteSource` with `read(byte[], int offset, int length) throws IOException`. The latter returns positive byte counts or `-1` at EOF. It must retain any bytes beyond the requested length when adapting a USB bulk packet; timeouts must throw rather than return zero. A bulk packet can contain part of one container or several containers. The reader handles both cases when the adapter follows this contract.

The reader returns `null` only at EOF on a container boundary. A partial header or payload throws `EOFException`. Lengths, container types, parameter alignment, five-parameter command/response limits, three-parameter event limits and data-size limits are validated before allocation. Invalid framing terminates that stream; do not continue scanning for a new header after failure. Create a new transport/session instead.

`PtpContainer` factories encode `command`, `data`, `response` and `event`; getters are `type()`, `code()`, `transactionId()`, `payload()` and `parameters()`. Transaction IDs and parameters use `long` for unsigned 32-bit values. Byte and array getters return copies.

`PtpValidation.requireData(container, expectedTransactionId, expectedOperation)` returns the data payload. `requireOkResponse(container, expectedTransactionId)` returns void. A failed response throws `PtpResponseException`, with `responseCode()` and `transactionId()`. Messages distinguish `DeviceBusy` and `SessionAlreadyOpen`, among other standard response codes. Unexpected identities or types throw `PtpProtocolException`. Validate both the data container and its final response before treating the dataset as a successful transaction.

`PtpDeviceInfo.parse(byte[])` returns getters. Use `parseRedacted(byte[])` in diagnostics; it validates and skips the serial number without decoding it or retaining its value:

- `standardVersion()`, `vendorExtensionVersion()` and `functionalMode()` as `int`.
- `vendorExtensionId()` as unsigned `long` and `vendorExtensionDescription()` as `String`.
- `operationsSupported()`, `eventsSupported()`, `devicePropertiesSupported()`, `captureFormats()` and `imageFormats()` as `int[]`.
- `manufacturer()`, `model()`, `deviceVersion()` and `serialNumber()` as `String`.
- `supportsOperation(int)`, `supportsStandardObjectSend()` and `operationsAsHex()`.

Do not export the device serial number in diagnostics. `supportsStandardObjectSend()` means only that the standard operation pair `SendObjectInfo`/`SendObject` is advertised. It does **not** establish that a Nikon camera accepts NCP objects, supports the required card path or can register an imported Picture Control.

`PtpStorageIds.parse(byte[])` returns a `long[]` of unsigned storage IDs.

`PtpStorageInfo.parse(byte[])` returns `storageType()`, `fileSystemType()` and `accessCapability()` as `int`; `maxCapacity()` and `freeSpaceBytes()` as unsigned `BigInteger`; `freeSpaceImages()` as unsigned `long`; `description()` and `volumeLabel()` as strings. `readOnly()` returns `Optional<Boolean>`: `false` for access capability 0, `true` for 1 or 2, and empty for a reserved/vendor value. Standard writable-storage metadata is not evidence that arbitrary NCP objects are accepted.

PTP strings use one unsigned byte for the number of UTF-16 code units, including the terminator. Empty strings may use zero units or one null code unit. Malformed Unicode, missing terminators, embedded nulls, truncated datasets, extra trailing bytes and oversized array counts are rejected. Dataset arrays are capped at 65,536 elements and must fit inside the received payload.

`PtpObjectHandles.parse(byte[])` returns the unsigned `long[]` dataset from GetObjectHandles. It shares the bounds-checked UINT32 array parser.

`PtpObjectInfo.parse(byte[])` and `toBytes()` decode/encode all standard ObjectInfo fields, in order: storage ID, object format, protection status, compressed size, thumbnail format/size/width/height, image width/height/bit depth, parent object, association type/description, sequence number, filename, capture date, modification date and keywords. Getters use standard camelCase names; `compressedSize()` is unsigned UINT32 as a long, and `associationDescription()` is unsigned UINT32 as a long.

For standard-object experiments, `PtpObjectInfo.file(storageId, parentObject, filename, compressedSize)` uses UndefinedFile format `0x3000`; `folder(storageId, parentObject, filename)` uses Association format `0x3001` and GenericFolder association type 1. These factories accept only one filename component and reject traversal, slashes, control characters, FAT reserved characters and trailing spaces/dots. The parent object handle identifies the directory. A filename may contain valid Unicode and at most 254 UTF-16 units. The complete standard metadata can also be constructed with `new PtpObjectInfo.Builder()` and setters matching all getter names; builders are copied on `build()`.

The generic codec is not an operation authorization boundary. The read-only probe must allow only its known read commands plus OpenSession/CloseSession. A separate experimental upload flow can use standard SendObjectInfo/SendObject only after the user explicitly chooses to upload a file, validates the destination/size and checks existing objects to avoid overwrites. Do not send unknown vendor commands. A successful object upload plus a matching read-back hash establishes card-file transfer; the camera's menu must still be used to establish Picture Control loading support.

## Read-only directory survey

`com.nikonpreset.usbprobe.usb.PtpDirectorySurvey` is compiled directly in the JVM suite. It compares `GetObjectHandles(storage, 0, 0xFFFFFFFF)` with `GetObjectHandles(storage, 0x3001, 0)` and reads only `GetObjectInfo` metadata for the returned handles. When the all-association selector receives a validated unsupported-format/parameter response `0x2006` or `0x2014`, it adds one `GetObjectHandles(storage, 0, 0)` fallback. An unsupported root selector remains a separate unsupported result; it does not independently enable that fallback. The scanner never reads photo/file contents, creates objects, sends vendor commands or manages USB sessions.

Construct it with `new PtpDirectorySurvey(operations, storageId)` and call `scan()`. The owner supplies `Operations.getData(int operation, long... parameters)` and must already have opened the session. The owner also remains responsible for increasing transaction IDs, validating framing and matching response transaction IDs, USB timeouts and cancelling in-flight I/O. Only a fully validated negative PTP response may arrive as a `PtpResponseException`; the scanner cannot validate a transaction that its interface does not receive. Tests inject exceptions at that boundary and do not claim to verify the Android transport. The optional third constructor argument is a monotonic `Clock`, used by deterministic deadline tests.

`result()` retains the same partial result after an exception. It reports each query's selector, status, response code, raw handle count, completion/truncation, errors and object records with handle, filename, parent, storage, format and association type. Scheduled queries that were never reached remain `pending`. `Result.completed` means all scheduled queries finished; each `QueryResult.complete` independently states whether that view was fully checked. A selector that returns objects at unexpected parents or formats is recorded as `selector_mismatch`, preserves the returned fields and cannot claim a complete view. Seeing `NIKON/CUSTOMPC` requires the corresponding visible directory names and parent links. Not seeing them establishes only the camera's USB view, not their physical absence on the SD card or any write capability.

Each query retains counts but skips its metadata if it has more than 4,096 handles or more than 128 candidate metadata objects. Valid larger lists, including 65,537 handles, are recorded as truncated instead of being misreported as empty or corrupt solely due to their size. Invalid count/length pairs still fail. All queries share a 128-request metadata budget and cache; failed metadata requests count against that budget. A 60-second monotonic deadline and thread interruption are checked before and after operations and prevent the next request. These are boundary checks; in-flight USB cancellation/timeouts remain the owner's responsibility.

## Run tests

With JDK 17 or newer on PATH:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\run.ps1
```

Or specify an existing JDK:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\run.ps1 -JdkHome 'C:\path\to\jdk'
```

Tests use literal wire fixtures and synthetic datasets to cover fragmented/concatenated containers, Unicode including supplementary characters, unsigned integers, array allocation bounds, malformed lengths, truncated transfers, transport errors, transaction mismatches, negative responses, serial redaction, ObjectInfo field offsets and upload filename/size limits. Passing these tests does not verify a real Z6II, USB cable, Android permission behavior or NCP import.

The same script also runs these additional suites:

- `NcpFileTest` executes the production NCP structural validator against a synthetic 0100 envelope, every truncated prefix, counterfeit signatures, unsigned record lengths, absent/extra termination data, duplicate preset records and unsupported versions. These fixtures only establish parser behavior; they are not claimed to be camera-compatible presets.
- `NcpTransferSessionTest` executes the production transfer planner against a stateful simulated PTP camera. It covers ordinary-mode directory creation, alternate filename allocation, preflight operation/model/storage/size failures, object conflicts, session ownership, changed filenames before reservation, unexpected returned storage, pre-existing returned object handles and byte/hash/path verification failures. The multiple-slot regression cases audit operation codes, transaction IDs, storage parameters and the first-write order: a matching GetStorageInfo response `0x2013` permits checking another slot, while InvalidStorageID `0x2008`, DeviceBusy `0x2019`, a response from another transaction, and any command/upload-phase `0x2013` must stop. Physical-only IDs with a zero low word must never receive GetStorageInfo; their unmounted status is reported separately without inventing a `0x2013` response. All-unavailable slots must produce zero object writes.
- The same transfer suite exercises the explicitly requested `existing_directory_experiment` mode. The ordinary constructor still refuses access capability 2. The experiment can select access 2 only when the other storage guards pass and `NIKON/CUSTOMPC` is already visible as two exact-name standard GenericFolder associations. Missing, USB-invisible, incorrectly cased, duplicate or wrong-type directories produce a local `ExistingDirectoryCheck` refusal and no write requests; this is not a camera upload rejection or proof that the directory is absent on the card. The requested mode never creates directories, including when it selects an ordinary writable card. Tests cover ambiguous candidates, unique SD identification, writable-card priority, operation/model/storage/filesystem/space guards, occupied filenames, all 99 names occupied and a last-moment filename conflict. A successful simulated experiment still needs the normal location, byte and SHA-256 verification; it never verifies camera-menu import.
- Experimental write tests audit the exact parent handle and file metadata of the first standard `SendObjectInfo`. Before that request, `existingPathVerified` and `readOnlyExceptionAttempted` must be recorded. Simulated camera responses `0x200F` and `0x2020` to that request preserve the actual failed operation, transaction and storage context, cause no content transaction and are never retried. If the subsequent `SendObject` fails, the result retains accepted file metadata, the returned handle and the attempted-content state: a new incomplete object may already exist. It does not claim a zero-write failure, acknowledged content or a verified transfer. No guessed vendor operation is used.
- `StorageAssessmentTest` executes the production storage assessment helper against independent PTP StorageInfo datasets. It verifies hierarchical filesystem 2 and DCF filesystem 3 have ordinary eligibility only with writable access, and retains the logical-media, removable-media, filesystem and byte-reserve guards. Read-only-with-deletion access 2 remains ordinarily ineligible, including when it is separately marked `experimentCandidate` after an explicit opt-in. Access 1 and unknown access values can never become experiment candidates. Flat/unknown filesystems, unknown access/storage values, invalid/unmounted storage IDs and insufficient space remain excluded; multiple constraints retain every reason and all raw metadata. Transfer simulations also verify that local storage policy refusals are labeled `StorageEligibilityCheck`, preserve the successful GetStorageInfo evidence, never fabricate a failed PTP operation and produce zero directory/file writes.
- `PtpDirectorySurveyTest` executes the production directory scanner against independent object-handle datasets and synthetic object metadata. It covers a root view containing only DCIM while the all-association view exposes the full Nikon directory parent chain, both views containing only DCIM, unsupported selectors and the limited fallback, unmodified propagation of fatal busy/storage/metadata errors, partial record retention, invalid/duplicate handles, cross-storage objects, mismatched selector fields and raw Unicode metadata. It checks shared metadata caching, oversized 129/4,097/65,537-handle lists, the global 128-request budget, a monotonic deadline crossing during a response and thread interruption before or during a query. Its operations facade rejects every opcode except `GetObjectHandles` and `GetObjectInfo`; those checks verify the scanner's command boundary, not actual USB packets.

The transfer planner references the Android `UsbNcpTransfer.Result` and SHA-256 method. Pure JVM compilation uses a facade under `tests/stubs` with the same data fields and a JDK SHA-256 implementation. Production `NcpTransferSession`, `StorageAssessment`, `PtpDirectorySurvey` and `NcpFile` are compiled directly, without Android dependencies. These simulations do not exercise the actual Android outer classes, their lifecycle/JSON reports, USB driver, permission handling or USB packets; those require the Android build and device testing.

The 0.1.4 local run passed 3,934 checks: 186 PTP core, 68 NCP structure, 2,577 transfer simulation, 148 storage assessment and 955 read-only directory survey checks. These counts describe synthetic test execution, not real-camera compatibility, actual SD directory visibility or an established special write permission for `NIKON/CUSTOMPC`.

## Production UsbProbe host regression

The separate `run-probe.ps1` script executes the real production `UsbProbe.probe(...)`, including its private USB transport, directory-survey adapter, error wrapping and session cleanup. It compiles `UsbProbe.java`, the production PTP sources, `StorageAssessment.java` and `PtpDirectorySurvey.java` directly. It does not substitute the earlier transfer-result facade or replace the production probe with a helper.

```powershell
& '.\tests\run-probe.ps1' -JdkHome '.tooling\jdk\jdk-17.0.20.1+1' -Reproduce014
```

`-AndroidJar 'C:\path\to\android.jar'` can specify another existing Android platform jar. The default uses the project's ignored Android 35 SDK. Eight small classes under `tests/android-stubs` provide only the USB device/interface/endpoint/manager/connection, worker-thread Looper and monotonic SystemClock surfaces needed by this test. The platform jar supplies the remaining declarations; the test does not invoke its throwing `Stub!` methods or JSON serialization. These classes are isolated in `tests/.build/probe` and are not compiled into the app or the existing pure-Java test suite.

`UsbProbeHostTest` parses the actual command containers passed by the production code to `bulkTransfer`, records their operation, transaction and parameters, then supplies scripted PTP DATA and RESPONSE containers through the same production bulk-input and container-reader path. Tests run both combined containers and seven-byte fragments. The scripted connection rejects every opcode except the probe's seven read/session operations; it sends no upload, property write, vendor command or object-content read. It verifies the full ordinary and directory probe sequences, the skipped empty physical slot, interface/session ownership and cleanup, and both nullable and concrete storage contexts. Failure cases check original parameters/transaction/storage after cleanup, a separate cleanup error that cannot replace the primary failure, GetObjectInfo's survey storage context, validated unsupported-selector recovery and refusal to recover a response from the wrong transaction.

With `-Reproduce014`, the script first creates a generated copy of `UsbProbe.java` under `tests/.build/probe/repro` and restores only the 0.1.4 nullable nested conditional expression. The checked-in production file is never edited. That variant must fail through the actual `UsbProbe.probe` call with a `NullPointerException` originating in `UsbProbe$Transport.exchange`, before sending any USB command. The script then separately compiles and runs the current unmodified production source. The 0.1.5 verification reproduced the old failure with seven checks and passed 685 production-probe host checks. The prior 3,934 pure-Java checks were not rerun for this isolated nullable-context fix.

This host regression exercises production Java control flow and transport framing against a synthetic USB connection. It does not verify Android USB permission dialogs, actual Android class behavior, kernel/OTG drivers, JSON export, a physical Z6II or successful NCP import; those still require the APK and real-device testing.

## Picture Control read-only regression

The 0.1.6 suites add independent synthetic fixtures for the SDK-documented read datasets and execute the new production `PictureControlReadSession`, `UsbPictureControlProbe` and the real `PtpUsbTransport` in its dedicated read-only mode. The request fixtures distinguish zero parameters from a parameter whose value is zero: optional `GetVendorPropCodes` has no parameters, the standard capability request selects item 1, and data reads select Standard or C1–C9 with the current-data selector. They do not create a file-to-dataset conversion, infer a write command from a read command or install a custom slot.

The regular `run.ps1` script now compiles the production `picturecontrol` package and runs `PictureControlCodecTest`. Its 50 checks cover 36-byte and 614-byte returned data, exact capability size, curve structure, empty base state, unknown base/capability values, signed parameter sentinels, unsigned raw fields, ASCII/NUL registration-name structure and opaque padding. Input arrays, returned arrays and immutable collections are checked independently. Invalid lengths, an enabled curve without its required tail, unsupported curve flags, missing termination and non-ASCII name bytes are rejected. These are read-format checks, not proof of camera acceptance or a serializer for NCP files.

The separate host script also runs `UsbPictureControlProbeHostTest`. Its 453 checks include direct calls to the actual read-only transport dispatcher: data uploads, deletion/property/mode commands, unknown vendor operations, file/storage reads, incorrect parameter counts and incorrect selector values must fail before USB I/O. Ordinary transport mode does not acquire vendor-read permission. The production session is also exercised with in-memory operations to verify optional enumeration and session ownership without USB.

The real wrapper/transport success case runs the full device-info, owned-session, optional vendor-property enumeration, standard capability and Standard/C1–C9 data-read sequence over scripted USB containers, including seven-byte fragments, a 614-byte custom curve and an empty C1 slot. It verifies that no per-empty-slot capability request is invented and that raw item bytes remain protected by copies. Failure cases preserve successfully read items, distinguish received raw data from parsed data, retain the original failed item/parameters/transaction through a separate cleanup error, and stop on malformed capability/profile data. A semantic dataset rejection after a complete matching DATA+OK exchange preserves that known protocol boundary: it stops further Picture Control reads and still closes the tool's own session. Wrong data operation, wrong final-response transaction, an oversized data header, missing final response and a partial command write instead close the transport without sending any subsequent slot read or `CloseSession` on an unconfirmed phase.

Run the two suites with the existing commands:

```powershell
& '.\tests\run.ps1' -JdkHome '.tooling\jdk\jdk-17.0.20.1+1'
& '.\tests\run-probe.ps1' -JdkHome '.tooling\jdk\jdk-17.0.20.1+1'
```

The final 0.1.6 local run passed 3,984 pure-Java checks (the prior 3,934 plus 50 codec checks) and 1,138 host integration checks (the prior 685 plus 453 Picture Control checks), totaling 5,122 checks. The pure suite ran once after the new files were complete; the host suite ran again after the targeted semantic-parse cleanup fix and its new boundary assertions. The unchanged pure suite and the old 0.1.4 failure reconstruction were not repeated. The host build reuses the eight Android stubs and the existing unrelated upload-result facade only to satisfy the shared transport's compile-time dependency; the Picture Control wrapper, result model, planner, codecs and transport are all the actual production classes. JSON export, Android lifecycle/driver behavior and physical-camera results remain outside the host tests. Passing a read-only query test establishes neither NCP conversion nor a supported direct-write route.

## Camera preset copy to an empty slot

The 0.1.7 host script adds the production `PictureControlImportSession`, `UsbPictureControlImport` and `PtpUsbTransport.Mode.WRITE_EMPTY_PC` to its direct-source compilation. `UsbPictureControlImportHostTest` executes their real Java paths over scripted USB commands, data stages and responses. The public copy API requires different explicit source and target custom slots. The backend reads all nine slots, verifies that the fresh nonempty source bytes match the selected snapshot, checks the target is empty and reads that target again immediately before its one registration request. It then reads the target back and compares the complete bytes. No request selects the active preset, edits an occupied slot, changes camera mode, deletes a slot or writes a card file.

The tests contain only independently constructed synthetic wire bytes: a fixed ASCII test name, literal parameter values and an artificial linear LUT. No captured user preset, user curve or original NCP mapping is embedded in the test sources or fixtures. The expected data bytes are not generated by a production converter. There is no NCP converter in this version: the missing modern-field mapping remains unverified, and file-shaped input is rejected by the copy backend. Synthetic payload success in the scripted camera proves control flow and byte preservation, not physical-camera acceptance or appearance.

The 788 copy checks cover:

- Actual transport dispatch: only the explicit write mode permits `SetPicCtrlData(target, new=0)`, only once per connection. Read-only and ordinary modes reject that vendor write before USB I/O. Wrong operation, target, parameter count, edit flag, file/content/property/mode/delete command and unsupported query all remain blocked.
- Strict write-input checks independent of the permissive read decoder: known nonempty base/name and field ranges, printable terminated ASCII, control flags, curve length/header and rejected file-format bytes. This does not claim to validate an unimplemented NCP conversion.
- A full source-C1 to empty-C7 sequence, all-nine-slot inspection, the adjacent target recheck, exact outbound command and DATA bytes, fragmented reads, full readback and unchanged simulated C1–C6/C8–C9 contents.
- Initially occupied target, target occupied at recheck, changed or empty source, missing source and self-copy. Every such refusal sends zero registration commands or data stages.
- A rejected registration response with a possible simulated target change, a separate cleanup failure that cannot replace the original write context, and acknowledged data with a different readback. The report preserves attempted/acknowledged/verified distinctions, byte differences and possible changes. No case automatically retries, deletes or rolls back the target.
- Lost registration response, partial DATA write and wrong readback transaction. Those unconfirmed phases close USB without another PTP command. Fully returned semantic differences still permit closing the tool's own session. A final cancellation after verified readback keeps that evidence but reports unconfirmed session cleanup, `completed=false` and an `ImportException`; it sends no further command.

The two empty observations are not an atomic compare-and-set. The tests verify the implemented recheck and refusal boundaries; they do not eliminate a camera-side state change between the final read and registration, or establish how the real camera handles duplicate names.

Run all host regressions with `run-probe.ps1` as above. After an isolated copy-driver change, the same script can compile the current sources and run only the copy suite:

```powershell
& '.\tests\run-probe.ps1' -JdkHome '.tooling\jdk\jdk-17.0.20.1+1' -OnlyCopy
```

The effective 0.1.7 validation totals 5,910 checks: 3,984 unchanged pure-Java checks from the preceding version, 685 ordinary-probe host checks, 453 Picture Control read-only host checks and 788 copy host checks. This work ran the full host suite once, then only the copy suite after the targeted late-cancellation fix; it did not rerun the unchanged pure-Java suite. Physical USB writes, menu behavior, rendering/JSON export and actual preset results still require device verification.

## Explicit NCP compatibility adaptation

The 0.1.8 version adds `NcpPictureControlConverter` for one deliberately restricted legacy color-and-curve NCP subset. This is an explicit compatibility adaptation, not a claim that modern camera settings can be recovered as Nikon's native loader would choose them. The user supplies the missing middle-range sharpening and clarity values in quarter units. Quick sharpening, contrast and brightness use the disclosed fixed adaptation values; the supported known fields and complete curve are retained. Other formats, flags, encodings and unknown values are rejected instead of guessed.

`NcpCompatibilityFixtures` contains a hand-authored synthetic canonical NCP container, an independent expected camera dataset and an artificial linear LUT. It contains no user file or recipe. The expected bytes use literal mappings and chosen values; they are not generated by the production converter. `NcpPictureControlConverterTest` adds 77 pure-Java checks for:

- Exact output bytes and hashes, all five supported literal base mappings, signed quarter-unit scaling and explicit adaptation values.
- Choice limits and unsupported sentinels, strict record order/length/version/metadata, printable terminated names with zero padding and rejected unknown fields.
- Complete unchanged curve bytes, no byte swapping, sorting or resampling, permitted nonmonotonic samples and rejected header/size/range violations.
- Independent input/output arrays, immutable conversion state and a reviewable adaptation preview.

The public `UsbPictureControlImport.importNcpPreset(...)` entry accepts original NCP bytes and choices. It recomputes conversion inside the backend before opening USB, then uses the existing all-nine-slot inspection, double-empty guard, one-registration limit, readback comparison and cleanup behavior. It does not require a camera source slot and cannot pretend that one was verified. The host suite reuses the existing scripted camera with an independently expected registration payload rather than duplicating the already-covered wire failure cases.

The expanded copy/adaptation host suite has 1,004 checks. Its new cases verify the actual NCP entry's output bytes, choices, original-file and curve hashes, registered name and retained preview; pre-USB rejection of invalid choices or unsupported original metadata; refusal to overwrite an occupied target; and retained adaptation metadata when a registration response fails after data was sent. Existing-camera copies retain their separate purpose and cannot be marked as NCP imports. Successful exact registration sets `ncpRegistrationVerified`; `ncpImportVerified`, `conversionEquivalentToNative` and `photoEffectVerified` remain false, including after an otherwise successful registration. These distinctions are asserted against the actual production result fields.

The first 0.1.8 verification passed 6,203 checks: 4,061 pure-Java checks (the prior 3,984 plus 77 converter checks) and 2,142 host checks (685 ordinary-probe, 453 read-only Picture Control and 1,004 copy/adaptation checks). Both complete scripts ran once after the production APIs were stable. `run-probe.ps1 -OnlyCopy` now selects the shared copy/adaptation suite for a later isolated fix. Host success verifies the stated adaptation and registration control flow; it does not establish native equivalence or photographic appearance. Actual device registration and visual results require device testing.

## Protocol references

- [USB-IF Still Image Capture Device Definition 1.0 and Errata](https://www.usb.org/document-library/still-image-capture-device-definition-10-and-errata-16-mar-2007): USB PTP transport and container framing.
- [Android upstream libmtp PTP definitions](https://android.googlesource.com/platform/external/libmtp/+/refs/tags/android-10.0.0_r24/src/ptp.h): primary implementation reference for standard code values and dataset field types. No implementation source was copied.
- [Ricoh original GetDeviceInfo dataset documentation](https://github.com/ricohapi/theta-api-specs/blob/main/theta-usb-api/operation/get_device_info.md): standard dataset field order. This is a protocol-format reference, not a claim of Nikon feature compatibility.
- [libgphoto2 original PTP dataset implementation](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp-pack.c): checked standard ObjectInfo field offsets (52 fixed bytes) and counted string layout. The Java implementation was authored independently.
