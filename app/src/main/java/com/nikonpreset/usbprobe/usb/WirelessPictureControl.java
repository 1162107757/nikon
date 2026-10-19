package com.nikonpreset.usbprobe.usb;

import android.os.Looper;

import com.nikonpreset.usbprobe.album.PtpIpClient;
import com.nikonpreset.usbprobe.ncp.NcpPictureControlConverter;
import com.nikonpreset.usbprobe.ptp.PtpConstants;
import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-thread PTP/IP adapter for the same guarded Picture Control flows as USB. */
public final class WirelessPictureControl implements AutoCloseable {
    private static final String CLIENT_NAME = "Nikon Preset";
    private final PtpIpClient.SocketConnector connector;
    private final Object lifecycle = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile PtpIpClient active;
    private PictureControlReadSession preparedSession;
    private UsbPictureControlProbe.Result preparedResult;
    private volatile boolean closed;
    private volatile String cancellationCloseError = "";

    public WirelessPictureControl(PtpIpClient.SocketConnector connector) {
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    /** Opens its own PTP session, reads Standard/C1–C9, and confirms session cleanup. */
    public UsbPictureControlProbe.Result probe(String host, int port, byte[] guid) throws Exception {
        return readSnapshot(host, port, guid, false, false, null, false);
    }

    /** Keeps this instance's own session open while the user confirms an import. */
    public UsbPictureControlProbe.Result prepareImport(String host, int port, byte[] guid) throws Exception {
        return readSnapshot(host, port, guid, true, true, null, false);
    }

    /** Settings can retain read capability even when the camera does not advertise registration. */
    public UsbPictureControlProbe.Result prepareReadOnly(String host, int port, byte[] guid) throws Exception {
        return readSnapshot(host, port, guid, true, false, null, false);
    }

    /** Consumes one app-owned album handoff; no socket, handshake or OpenSession is repeated. */
    public UsbPictureControlProbe.Result prepareFromHandoff(PtpIpClient transferred, String host, int port,
                                                            boolean requireWrite) throws Exception {
        if (transferred == null) throw new IllegalArgumentException("缺少相册会话移交。");
        return readSnapshot(host, port, null, true, requireWrite, transferred, false);
    }

    /** Replaces the previous snapshot with a full fresh read on the same retained session. */
    public UsbPictureControlProbe.Result refreshPreparedSnapshot(boolean requireWrite) throws Exception {
        return readSnapshot("", 0, null, true, requireWrite, null, true);
    }

    private UsbPictureControlProbe.Result readSnapshot(String host, int port, byte[] guid,
            boolean prepare, boolean requireWrite, PtpIpClient handoff, boolean refresh) throws Exception {
        begin(refresh);
        UsbPictureControlProbe.Result result = new UsbPictureControlProbe.Result();
        result.transport = "wifi";
        result.wirelessHost = host == null ? "" : host.trim();
        result.wirelessPort = port;
        PtpIpClient client = null;
        PictureControlReadSession session = null;
        UsbPictureControlProbe.ProbeException failure = null;
        boolean retained = false;
        try {
            if (refresh) {
                result.phase = "PreparedWirelessRefresh";
                synchronized (lifecycle) {
                    client = active;
                    session = preparedSession;
                    if (preparedResult != null) {
                        result.wirelessHost = preparedResult.wirelessHost;
                        result.wirelessPort = preparedResult.wirelessPort;
                    }
                    checkCancelled();
                    if (!isPrepared()) throw new IOException("原无线会话已失效，请重新连接；本次未自动重连。");
                    long next = session.transferOwnedSession();
                    preparedResult.sessionRetained = false;
                    session = new PictureControlReadSession(new Operations(client, result.commandsSent), result, next);
                }
                result.diagnostics.add("沿用本工具持有的无线会话重新读取完整快照，未新建 socket、握手或打开会话。");
            } else if (handoff != null) {
                result.phase = "AlbumSessionHandoff";
                checkCancelled();
                if (!result.wirelessHost.equals(handoff.connectedHost()) || port != handoff.connectedPort()) {
                    throw new IOException("移交会话的相机地址不一致，未接管或重新连接。");
                }
                long next = handoff.claimTransferredSession();
                // Only a successful one-time claim gives this adapter ownership.
                client = handoff;
                session = new PictureControlReadSession(new Operations(client, result.commandsSent), result, next);
                installOwnedClient(client);
                result.diagnostics.add("从本 App 相册一次性移交已打开的无线会话与双 socket；旧相册句柄已失效，事务号延续，未重新握手或打开会话。");
            } else {
                result.phase = "WirelessConnection";
                checkCancelled();
                client = installClient();
                client.connectForPictureControl(host, port, copyGuid(guid), CLIENT_NAME);
                session = new PictureControlReadSession(new Operations(client, result.commandsSent), result);
            }
            session.run();
            if (prepare) {
                boolean writeSupported = false;
                for (int operation : result.operations) writeSupported |= operation == 0x90cd;
                if (requireWrite && !writeSupported) {
                    result.phase = "RequiredOperationsCheck";
                    throw new IOException("相机未声明支持 SetPicCtrlData (0x90CD)，未发送写入请求。");
                }
                synchronized (lifecycle) {
                    checkCancelled();
                    if (!client.isOpen() || client.isProtocolUnconfirmed()) {
                        throw new IOException("读取后无线连接已关闭，请重新读取槽位。");
                    }
                    preparedSession = session;
                    preparedResult = result;
                    result.sessionRetained = true;
                    retained = true;
                }
                result.diagnostics.add(requireWrite
                        ? "本工具持有的无线会话保持连接，供确认后在同一会话内重新检查并登记；未发送写入请求。"
                        : "只读检测完成并保持本工具持有的无线会话；进入导入时须重新读取能力与槽位，本次未发送写入请求。");
            }
        } catch (Exception error) {
            if (handoff != null && client == null && !handoff.closeReason().isEmpty()) {
                result.diagnostics.add("无线会话移交前的关闭原因：" + handoff.closeReason());
            }
            failure = probeException(error, result);
        } finally {
            try {
                if (client != null && !retained) {
                    if (session != null && session.requiresProtocolAbort() && !client.isClosed()) {
                        client.abortProtocol(new IOException("无线优化校准读取的 PTP 阶段未确认。"));
                    }
                    if (session != null && session.ownsSession()) {
                        if (canCleanup(client)) {
                            try { session.closeSession(); }
                            catch (Exception error) {
                                result.cleanupError = readableError(error);
                                Integer code = responseCode(error);
                                if (code != null) result.cleanupResponseCode = code;
                                result.diagnostics.add("CloseSession 未获确认：" + result.cleanupError);
                                if (failure == null) {
                                    result.failurePhase = "SessionCleanup";
                                    if (code != null) {
                                        result.failedOperation = result.cleanupOperation;
                                        result.failedTransactionId = result.cleanupTransactionId;
                                        result.failedParameters = new long[0];
                                        result.failedItemCode = -1;
                                    }
                                    failure = new UsbPictureControlProbe.ProbeException(
                                            "读取已结束，但无线会话关闭未获确认。" + result.cleanupError,
                                            code, result, error);
                                } else failure.addSuppressed(error);
                            }
                        } else {
                            result.cleanupError = "无线连接关闭或取消后，未确认自有会话关闭。";
                            result.diagnostics.add("连接已取消、关闭或协议阶段未确认，未发送 CloseSession。");
                            if (failure == null) {
                                result.failurePhase = "SessionCleanupCancelled";
                                failure = new UsbPictureControlProbe.ProbeException(
                                        result.cleanupError, null, result, null);
                            }
                        }
                    }
                    result.protocolPhaseUnconfirmed |= client.isProtocolUnconfirmed();
                    if (result.protocolPhaseUnconfirmed) {
                        result.diagnostics.add("PTP 阶段未确认，已关闭无线连接，未发送后续相机命令。");
                    }
                }
            } finally {
                try {
                    RuntimeException closeError = retained ? null : closeClient(client, result.diagnostics);
                    if (closeError != null) {
                        if (failure == null) {
                            result.failurePhase = "WirelessCleanup";
                            failure = new UsbPictureControlProbe.ProbeException(
                                    "读取已结束，但无线连接释放未获确认。", null, result, closeError);
                        } else failure.addSuppressed(closeError);
                    }
                }
                finally {
                    if (!retained) clearPrepared();
                    finish(retained);
                }
            }
        }
        result.completed = failure == null && result.capabilityParsed
                && result.items.size() == 10 && (retained || result.sessionClosed);
        if (failure != null) {
            result.error = failure.getMessage();
            throw failure;
        }
        return result;
    }

    /** True only while the snapshot still belongs to this live, owned PTP session. */
    public boolean isPrepared() {
        synchronized (lifecycle) {
            return !closed && preparedSession != null && preparedSession.ownsSession()
                    && active != null && active.isOpen() && !active.isProtocolUnconfirmed();
        }
    }

    /** Retained state can still need cleanup/reporting after its physical connection has failed. */
    public boolean hasPreparedSession() {
        synchronized (lifecycle) {
            return preparedSession != null && preparedResult != null;
        }
    }

    /** Transfers only an idle healthy prepared owner; the returned album handoff belongs to the caller. */
    public PtpIpClient transferPreparedToAlbum() throws Exception {
        begin(true);
        try {
            synchronized (lifecycle) {
                checkCancelled();
                if (!isPrepared()) throw new IOException("当前无线会话不可移交给相册；未重新连接。");
                long next = preparedSession.ownedNextTransaction();
                PtpIpClient transferred = active.transferToAlbum(next);
                preparedSession.transferOwnedSession();
                preparedResult.sessionRetained = false;
                preparedResult.diagnostics.add("自有无线会话已一次性移交只读相册；双 socket、事件读取和事务序列延续，未关闭或重新连接。");
                preparedSession = null; preparedResult = null; active = null;
                // This wrapper can never reconnect, read, register or close the new owner.
                closed = true;
                return transferred;
            }
        } finally { finish(true); }
    }

    /** Only these complete error responses may preserve a healthy read-only heartbeat session. */
    public static boolean isRecoverableKeepAliveResponse(Integer responseCode) {
        return Integer.valueOf(0x2002).equals(responseCode) || Integer.valueOf(0x2019).equals(responseCode);
    }

    /** One read-only keepalive; matched temporary refusals preserve the owner for a later tick. */
    public void keepPreparedAlive() throws Exception {
        begin(true);
        PtpIpClient client;
        PictureControlReadSession session;
        UsbPictureControlProbe.Result result;
        synchronized (lifecycle) {
            client = active;
            session = preparedSession;
            result = preparedResult == null ? new UsbPictureControlProbe.Result() : preparedResult;
        }
        UsbPictureControlProbe.ProbeException failure = null;
        boolean retainedRefusal = false;
        try {
            result.phase = "PreparedWirelessKeepAlive";
            checkCancelled();
            if (!isPrepared()) throw new IOException("待确认的无线连接已失效，请重新读取槽位；本次未重新连接。");
            session.keepAlive();
        } catch (Exception error) {
            failure = probeException(error, result);
            synchronized (lifecycle) {
                retainedRefusal = error instanceof PtpResponseException
                        && isRecoverableKeepAliveResponse(((PtpResponseException) error).responseCode())
                        && ((PtpResponseException) error).transactionId() == result.lastTransactionId
                        && !closed && !Thread.currentThread().isInterrupted()
                        && active == client && preparedSession == session && preparedResult == result
                        && client != null && client.isOpen() && !client.isProtocolUnconfirmed()
                        && session != null && session.ownsSession() && !session.requiresProtocolAbort()
                        && !result.protocolPhaseUnconfirmed;
                if (retainedRefusal) {
                    int code = ((PtpResponseException) error).responseCode();
                    result.diagnostics.add("只读保活收到匹配事务的 " + PtpConstants.responseName(code)
                            + " (" + PtpConstants.hex16(code) + ")；保留自有会话，等待下一次只读保活，未重连或写入。");
                    result.error = failure.getMessage();
                }
            }
        } finally {
            if (failure == null || retainedRefusal) finish(true);
            else {
                failure = releasePreparedState(client, session, result, failure);
                result.error = failure.getMessage();
            }
        }
        if (failure != null) throw failure;
    }

    /** Worker-thread cancellation cleanup. No write, reconnect or foreign-session takeover is possible. */
    public UsbPictureControlProbe.Result releasePrepared() throws Exception {
        begin(true);
        PtpIpClient client;
        PictureControlReadSession session;
        UsbPictureControlProbe.Result result;
        synchronized (lifecycle) {
            client = active;
            session = preparedSession;
            result = preparedResult == null ? new UsbPictureControlProbe.Result() : preparedResult;
        }
        UsbPictureControlProbe.ProbeException failure = releasePreparedState(client, session, result, null);
        if (failure != null) {
            result.error = failure.getMessage();
            throw failure;
        }
        return result;
    }

    private UsbPictureControlProbe.ProbeException releasePreparedState(PtpIpClient client,
            PictureControlReadSession session, UsbPictureControlProbe.Result result,
            UsbPictureControlProbe.ProbeException failure) {
        try {
            if (client != null) {
                if (session != null && session.requiresProtocolAbort() && !client.isClosed()) {
                    client.abortProtocol(new IOException("无线优化校准读取的 PTP 阶段未确认。"));
                }
                if (session != null && session.ownsSession()) {
                    if (canCleanup(client)) {
                        try { session.closeSession(); }
                        catch (Exception error) {
                            result.cleanupError = readableError(error);
                            Integer code = responseCode(error);
                            if (code != null) result.cleanupResponseCode = code;
                            result.diagnostics.add("CloseSession 未获确认：" + result.cleanupError);
                            if (failure == null) {
                                result.failurePhase = "SessionCleanup";
                                if (code != null) {
                                    result.failedOperation = result.cleanupOperation;
                                    result.failedTransactionId = result.cleanupTransactionId;
                                    result.failedParameters = new long[0];
                                    result.failedItemCode = -1;
                                }
                                failure = new UsbPictureControlProbe.ProbeException(
                                        "无线会话关闭未获确认。" + result.cleanupError, code, result, error);
                            } else failure.addSuppressed(error);
                        }
                    } else {
                        result.cleanupError = "无线连接关闭或取消后，未确认自有会话关闭。";
                        result.diagnostics.add("连接已取消、关闭或协议阶段未确认，未发送 CloseSession。");
                        if (failure == null) {
                            result.failurePhase = "SessionCleanupCancelled";
                            failure = new UsbPictureControlProbe.ProbeException(result.cleanupError, null, result, null);
                        }
                    }
                }
                result.protocolPhaseUnconfirmed |= client.isProtocolUnconfirmed();
            }
        } finally {
            try {
                RuntimeException closeError = closeClient(client, result.diagnostics);
                if (closeError != null) {
                    if (failure == null) {
                        result.failurePhase = "WirelessCleanup";
                        failure = new UsbPictureControlProbe.ProbeException(
                                "无线连接释放未获确认。", null, result, closeError);
                    } else failure.addSuppressed(closeError);
                }
            } finally {
                result.sessionRetained = false;
                result.completed = failure == null && result.capabilityParsed
                        && result.items.size() == 10 && result.sessionClosed;
                clearPrepared();
                finish();
            }
        }
        return failure;
    }

    /** Converts a private copy of the original NCP, then registers it once into a rechecked empty slot. */
    public UsbPictureControlImport.Result importNcpPreset(String host, int port, byte[] guid,
                                                          byte[] originalNcp, int middleQuarter,
                                                          int clarityQuarter, int targetItem) throws Exception {
        return importNcp(false, false, host, port, guid, originalNcp, middleQuarter, clarityQuarter, targetItem);
    }

    public UsbPictureControlImport.Result importNcpPreset(String host, int port, byte[] guid,
            byte[] originalNcp, int middleQuarter, int clarityQuarter, int targetItem,
            PictureControlImportSession.OverwriteConfirmation overwrite) throws Exception {
        return importNcp(false, false, host, port, guid, originalNcp, middleQuarter, clarityQuarter, targetItem, overwrite);
    }

    /** Continues only the owned connection retained by prepareImport; never reconnects. */
    public UsbPictureControlImport.Result importPreparedNcpPreset(byte[] originalNcp, int middleQuarter,
                                                                  int clarityQuarter, int targetItem) throws Exception {
        return importNcp(true, false, null, 0, null, originalNcp, middleQuarter, clarityQuarter, targetItem);
    }

    public UsbPictureControlImport.Result importPreparedNcpPreset(byte[] originalNcp, int middleQuarter,
            int clarityQuarter, int targetItem,
            PictureControlImportSession.OverwriteConfirmation overwrite) throws Exception {
        return importNcp(true, false, null, 0, null, originalNcp, middleQuarter, clarityQuarter, targetItem, overwrite);
    }

    /** Retains a separately owned read-only continuation only after exact verified registration. */
    public UsbPictureControlImport.Result importPreparedNcpPresetKeepingConnection(byte[] originalNcp,
            int middleQuarter, int clarityQuarter, int targetItem) throws Exception {
        return importNcp(true, true, null, 0, null, originalNcp, middleQuarter, clarityQuarter, targetItem);
    }

    /** Retains the connection only after exact replacement of the explicitly confirmed occupied slot. */
    public UsbPictureControlImport.Result importPreparedNcpPresetKeepingConnection(byte[] originalNcp,
            int middleQuarter, int clarityQuarter, int targetItem,
            PictureControlImportSession.OverwriteConfirmation overwrite) throws Exception {
        return importNcp(true, true, null, 0, null, originalNcp, middleQuarter, clarityQuarter, targetItem, overwrite);
    }

    private UsbPictureControlImport.Result importNcp(boolean prepared, boolean keepConnection,
                                                      String host, int port, byte[] guid,
                                                      byte[] originalNcp, int middleQuarter,
                                                      int clarityQuarter, int targetItem) throws Exception {
        return importNcp(prepared, keepConnection, host, port, guid, originalNcp,
                middleQuarter, clarityQuarter, targetItem, null);
    }

    private UsbPictureControlImport.Result importNcp(boolean prepared, boolean keepConnection,
            String host, int port, byte[] guid, byte[] originalNcp, int middleQuarter,
            int clarityQuarter, int targetItem,
            PictureControlImportSession.OverwriteConfirmation overwrite) throws Exception {
        begin(prepared);
        UsbPictureControlImport.Result result = new UsbPictureControlImport.Result();
        result.transport = "wifi";
        result.wirelessHost = host == null ? "" : host.trim();
        result.wirelessPort = port;
        result.sourceItem = -1;
        result.sourceLabel = PictureControlImportSession.label(-1);
        result.targetItem = targetItem;
        result.targetLabel = PictureControlImportSession.label(targetItem);
        if (overwrite != null) overwrite.recordInto(result);
        result.ncpMode = true;
        result.modePurpose = "ncp_compatibility_import";
        result.type = "ncp_compatibility_adaptation";
        result.adaptationMiddleQuarter = middleQuarter;
        result.adaptationClarityQuarter = clarityQuarter;
        PtpIpClient client = null;
        PictureControlImportSession session = null;
        PictureControlReadSession retainedReader = null;
        UsbPictureControlImport.ImportException failure = null;
        boolean retained = false;
        try {
            if (prepared) {
                result.phase = "PreparedWirelessSession";
                synchronized (lifecycle) {
                    client = active;
                    retainedReader = preparedSession;
                    if (preparedResult != null) {
                        result.wirelessHost = preparedResult.wirelessHost;
                        result.wirelessPort = preparedResult.wirelessPort;
                    }
                    checkCancelled();
                    if (!isPrepared()) throw new IOException("待确认的无线连接已失效，请重新读取槽位；本次未重新连接。");
                }
            }
            result.phase = "RequestedDataValidation";
            // The hash, conversion, preview and sent bytes all derive from this private source copy.
            byte[] source = originalNcp == null ? null : originalNcp.clone();
            result.sourceNcpSize = source == null ? 0 : source.length;
            if (source != null) result.sourceNcpSha256 = UsbPictureControlProbe.sha256(source);
            checkCancelled();
            if (targetItem < 201 || targetItem > 209) throw new IOException("必须明确选择 C1–C9 目标槽。");
            if (overwrite != null) overwrite.requireTargetMatch(targetItem);
            result.phase = "NcpCompatibilityAdaptation";
            NcpPictureControlConverter.Conversion conversion = NcpPictureControlConverter.convert(
                    source, middleQuarter, clarityQuarter);
            result.sourceNcpName = conversion.registrationName();
            result.sourceCurveSha256 = conversion.curveSha256();
            result.basePictureControl = conversion.basePictureControl();
            result.registrationName = conversion.registrationName();
            result.adaptationPreview = conversion.toDisplayText();
            result.adaptationPrepared = true;
            result.phase = "RequestedDataValidation";
            byte[] requested = PictureControlImportSession.validateConvertedData(conversion.dataBytes()).rawBytes();
            result.convertedRawBytes = requested.clone();
            result.convertedSha256 = UsbPictureControlProbe.sha256(requested);
            result.phase = "WirelessConnection";
            if (prepared) {
                session = new PictureControlImportSession(new Operations(client, result.commandsSent), result,
                        requested, targetItem, retainedReader.transferOwnedSession(), overwrite);
                result.diagnostics.add("沿用确认前本工具创建的无线会话，重新检查设备、能力和 C1–C9；未断开后重连。");
            } else {
                client = installClient();
                client.connectForPictureControl(host, port, copyGuid(guid), CLIENT_NAME);
                session = new PictureControlImportSession(new Operations(client, result.commandsSent), result,
                        requested, targetItem, overwrite);
            }
            session.run();
            if (keepConnection) {
                result.phase = "RetainVerifiedWirelessSession";
                checkCancelled();
                UsbPictureControlProbe.Result snapshot = snapshotAfterVerifiedImport(result, session);
                long next = session.transferAfterVerifiedRegistration();
                // The transport independently requires the ACK and exact target
                // readback before granting a new once-only registration facade.
                client = client.transferAfterVerifiedPictureControlImport(next);
                long claimed = client.claimTransferredSession();
                if (claimed != next) throw new IOException("登记后的无线事务序列不一致。");
                PictureControlReadSession continuation = PictureControlReadSession.fromVerifiedImportSnapshot(
                        new Operations(client, snapshot.commandsSent), snapshot, claimed);
                synchronized (lifecycle) {
                    checkCancelled();
                    if (!client.isOpen() || client.isProtocolUnconfirmed()) {
                        throw new IOException("登记已核对，但保留无线连接时相机会话已断开。");
                    }
                    if (preparedResult != null) preparedResult.sessionRetained = false;
                    active = client;
                    preparedSession = continuation;
                    preparedResult = snapshot;
                    result.sessionRetainedAfterImport = true;
                    retained = true;
                }
                result.phase = "RegistrationVerifiedConnectionRetained";
                result.diagnostics.add("登记已精确读回；无线会话与双 socket 移回独立只读 owner 并保持连接，未发送 CloseSession。"
                        + "后续导入必须由用户再次明确确认，并重新检查能力、全部槽位及目标状态。");
            }
        } catch (Exception error) {
            failure = importException(error, result);
        } finally {
            try {
                if (client != null && !retained) {
                    if (session == null && retainedReader != null && retainedReader.ownsSession()) {
                        // Validation can fail before handoff. The retained reader
                        // still owns cleanup; never leave its session abandoned.
                        result.sessionOpened = true;
                        if (canCleanup(client)) {
                            try {
                                retainedReader.closeSession();
                                result.sessionClosed = true;
                            } catch (Exception error) {
                                result.cleanupError = readableError(error);
                                Integer code = responseCode(error);
                                if (code != null) result.cleanupResponseCode = code;
                                result.diagnostics.add("CloseSession 未获确认：" + result.cleanupError);
                                if (failure != null) failure.addSuppressed(error);
                            } finally {
                                if (preparedResult != null) {
                                    result.cleanupOperation = preparedResult.cleanupOperation;
                                    result.cleanupTransactionId = preparedResult.cleanupTransactionId;
                                }
                            }
                        } else {
                            result.cleanupError = "无线连接关闭或取消后，未确认自有会话关闭。";
                            result.diagnostics.add("连接已取消、关闭或协议阶段未确认，未发送 CloseSession。");
                        }
                    }
                    if (session != null && session.requiresProtocolAbort() && !client.isClosed()) {
                        client.abortProtocol(new IOException("无线优化校准登记的 PTP 阶段未确认。"));
                    }
                    if (session != null && session.ownsSession()) {
                        if (canCleanup(client)) {
                            try { session.closeSession(); }
                            catch (Exception error) {
                                result.cleanupError = readableError(error);
                                Integer code = responseCode(error);
                                if (code != null) result.cleanupResponseCode = code;
                                result.diagnostics.add("CloseSession 未获确认：" + result.cleanupError);
                                if (failure == null) {
                                    result.failurePhase = "SessionCleanup";
                                    if (code != null) {
                                        result.failedOperation = result.cleanupOperation;
                                        result.failedTransactionId = result.cleanupTransactionId;
                                        result.failedParameters = new long[0];
                                        result.failedItemCode = -1;
                                    }
                                    failure = new UsbPictureControlImport.ImportException(
                                            "目标槽已读回核对，但无线会话关闭未获确认。请先重新只读扫描，勿重复写入。",
                                            code, result, error);
                                } else failure.addSuppressed(error);
                            }
                        } else {
                            result.cleanupError = "无线连接关闭或取消后，未确认自有会话关闭。";
                            result.diagnostics.add("连接已取消、关闭或协议阶段未确认，未发送 CloseSession。");
                            if (failure == null) {
                                result.failurePhase = "SessionCleanupCancelled";
                                failure = new UsbPictureControlImport.ImportException(
                                        "目标槽已读回核对，但无线会话关闭未获确认。请先重新只读扫描，勿重复写入。",
                                        null, result, null);
                            }
                        }
                    }
                    result.protocolPhaseUnconfirmed |= client.isProtocolUnconfirmed();
                    if (result.protocolPhaseUnconfirmed) {
                        result.diagnostics.add("PTP 阶段未确认，已关闭无线连接，未发送后续相机命令。");
                    }
                }
            } finally {
                try {
                    RuntimeException closeError = retained ? null : closeClient(client, result.diagnostics);
                    if (closeError != null) {
                        if (failure == null) {
                            result.failurePhase = "WirelessCleanup";
                            failure = new UsbPictureControlImport.ImportException(
                                    "无线连接释放未获确认，请先重新只读扫描，勿重复写入。", null, result, closeError);
                        } else failure.addSuppressed(closeError);
                    }
                }
                finally {
                    if (prepared && !retained) {
                        synchronized (lifecycle) {
                            if (preparedResult != null) {
                                preparedResult.sessionClosed = result.sessionClosed;
                                preparedResult.cleanupOperation = result.cleanupOperation;
                                preparedResult.cleanupTransactionId = result.cleanupTransactionId;
                                preparedResult.cleanupResponseCode = result.cleanupResponseCode;
                                preparedResult.cleanupError = result.cleanupError;
                                preparedResult.protocolPhaseUnconfirmed |= result.protocolPhaseUnconfirmed;
                            }
                        }
                        clearPrepared();
                    }
                    finish(retained);
                }
            }
        }
        result.completed = failure == null && result.readbackVerified
                && (result.sessionClosed || (retained && result.sessionRetainedAfterImport));
        result.ncpRegistrationVerified = result.completed && result.readbackVerified;
        if (failure != null) {
            result.error = failure.getMessage();
            throw failure;
        }
        return result;
    }

    /** New mutable connection facts never alias the completed import's historical result. */
    private UsbPictureControlProbe.Result snapshotAfterVerifiedImport(UsbPictureControlImport.Result result,
            PictureControlImportSession session) throws IOException {
        if (!result.writeResponseOk || !result.readbackVerified || result.readback == null
                || result.initialSlots.size() != 9) {
            throw new IOException("登记后缺少完整槽位检查或精确读回证据，不能保留会话。");
        }
        UsbPictureControlProbe.Result before;
        synchronized (lifecycle) { before = preparedResult; }
        if (before == null) throw new IOException("登记前的自有会话快照已失效。");
        UsbPictureControlProbe.Result snapshot = new UsbPictureControlProbe.Result();
        snapshot.transport = "wifi";
        snapshot.wirelessHost = result.wirelessHost; snapshot.wirelessPort = result.wirelessPort;
        snapshot.manufacturer = result.manufacturer; snapshot.model = result.model; snapshot.firmware = result.firmware;
        snapshot.operations = result.operations.clone(); snapshot.deviceInfoRead = true;
        snapshot.vendorProperties = before.vendorProperties.clone();
        snapshot.vendorPropertiesRead = before.vendorPropertiesRead;
        snapshot.standardCapabilityRawBytes = session.verifiedCapabilityBytes();
        snapshot.standardCapabilitySha256 = UsbPictureControlProbe.sha256(snapshot.standardCapabilityRawBytes);
        snapshot.capabilityRead = true; snapshot.capabilityParsed = true;
        UsbPictureControlProbe.ItemRecord standard = null;
        for (UsbPictureControlProbe.ItemRecord item : before.items) if (item.itemCode == 1) standard = item;
        if (standard == null) throw new IOException("登记前的 Standard 读取证据缺失。");
        snapshot.items.add(copyParsedItem(standard));
        boolean[] seen = new boolean[9];
        for (UsbPictureControlProbe.ItemRecord item : result.initialSlots) {
            if (item.itemCode < 201 || item.itemCode > 209 || seen[item.itemCode - 201]) {
                throw new IOException("登记前九个槽位的读取证据不完整。");
            }
            seen[item.itemCode - 201] = true;
            snapshot.items.add(copyParsedItem(item.itemCode == result.targetItem ? result.readback : item));
        }
        snapshot.sessionOpened = true; snapshot.sessionRetained = true; snapshot.completed = true;
        snapshot.phase = "ReadComplete";
        snapshot.diagnostics.add("登记后持有的只读状态：九槽来自本次登记前完整检查，目标由本次精确读回更新；"
                + "Standard 与可选厂商属性沿用登记前快照。下一次导入仍会完整刷新，不使用这些缓存作为写入依据。");
        return snapshot;
    }

    private static UsbPictureControlProbe.ItemRecord copyParsedItem(UsbPictureControlProbe.ItemRecord source)
            throws IOException {
        byte[] bytes = source.rawBytes();
        com.nikonpreset.usbprobe.picturecontrol.PictureControlData data =
                com.nikonpreset.usbprobe.picturecontrol.PictureControlData.parse(bytes);
        UsbPictureControlProbe.ItemRecord copy = new UsbPictureControlProbe.ItemRecord(source.itemCode, bytes);
        copy.basePictureControl = data.basePictureControl(); copy.registrationName = data.registrationName();
        copy.customCurveEnabled = data.customCurveEnabled(); copy.empty = source.itemCode >= 201 && data.basePictureControl() == 0;
        copy.parsed = true;
        return copy;
    }

    private void begin() {
        begin(false);
    }

    private void begin(boolean allowPrepared) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("无线优化校准操作必须在后台线程运行。");
        }
        synchronized (lifecycle) {
            if (!running.compareAndSet(false, true)) throw new IllegalStateException("此无线优化校准实例正在运行。");
            if (!allowPrepared && preparedSession != null) {
                running.set(false);
                throw new IllegalStateException("此无线优化校准实例正保持待确认会话，请先完成或释放该会话。");
            }
        }
    }

    private PtpIpClient installClient() throws IOException {
        PtpIpClient client = PtpIpClient.forPictureControl(connector);
        synchronized (lifecycle) {
            if (closed || Thread.currentThread().isInterrupted()) {
                client.close();
                throw new IOException("无线优化校准操作已取消。");
            }
            active = client;
        }
        return client;
    }

    private void installOwnedClient(PtpIpClient client) throws IOException {
        synchronized (lifecycle) {
            checkCancelled();
            if (!client.isOpen() || client.isProtocolUnconfirmed()) {
                throw new IOException("移交后的相机会话已断开，未重新连接。");
            }
            active = client;
        }
    }

    private void checkCancelled() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("无线优化校准操作已取消。");
    }

    private boolean canCleanup(PtpIpClient client) {
        return !closed && !Thread.currentThread().isInterrupted()
                && !client.isClosed() && !client.isProtocolUnconfirmed();
    }

    private RuntimeException closeClient(PtpIpClient client, List<String> diagnostics) {
        RuntimeException failure = null;
        if (client != null) {
            try { client.close(); }
            catch (RuntimeException error) {
                diagnostics.add("无线连接释放异常：" + readableError(error));
                failure = error;
            }
            String reason = client.closeReason();
            if (!reason.isEmpty()) diagnostics.add("无线连接关闭原因：" + reason);
        }
        if (!cancellationCloseError.isEmpty()) diagnostics.add(cancellationCloseError);
        return failure;
    }

    private void finish() {
        finish(false);
    }

    private void finish(boolean retainClient) {
        synchronized (lifecycle) {
            if (!retainClient) active = null;
            running.set(false);
        }
    }

    private void clearPrepared() {
        synchronized (lifecycle) {
            if (preparedResult != null) preparedResult.sessionRetained = false;
            preparedResult = null;
            preparedSession = null;
        }
    }

    /** Closes sockets immediately; no camera command or retry is issued from the cancelling thread. */
    @Override public void close() {
        PtpIpClient client;
        synchronized (lifecycle) { closed = true; client = active; }
        if (client != null) {
            try { client.close(); }
            catch (RuntimeException error) { cancellationCloseError = "取消时无线连接释放异常：" + readableError(error); }
        }
    }

    private static byte[] copyGuid(byte[] guid) { return guid == null ? null : guid.clone(); }

    private static UsbPictureControlProbe.ProbeException probeException(
            Exception error, UsbPictureControlProbe.Result result) {
        if (result.failurePhase.isEmpty()) result.failurePhase = result.phase;
        return new UsbPictureControlProbe.ProbeException(context(readableError(error), result.failurePhase,
                result.lastOperation, result.lastTransactionId, result.lastParameters, result.lastItemCode),
                responseCode(error), result, error);
    }

    private static UsbPictureControlImport.ImportException importException(
            Exception error, UsbPictureControlImport.Result result) {
        if (result.failurePhase.isEmpty()) result.failurePhase = result.phase;
        String message = context(readableError(error), result.failurePhase, result.lastOperation,
                result.lastTransactionId, result.lastParameters, result.lastItemCode);
        message += result.writeAttempted
                ? "\n目标槽可能已登记；请先重新只读扫描，勿重复写入。本工具未删除、自动还原或重试。"
                : "\n未发送优化校准登记请求。";
        return new UsbPictureControlImport.ImportException(message, responseCode(error), result, error);
    }

    private static String context(String error, String phase, String operation, long transaction,
                                  long[] parameters, int item) {
        StringBuilder text = new StringBuilder(error).append("\n失败阶段：").append(phase);
        if (!operation.isEmpty()) {
            text.append("\nPTP 上下文：").append(operation).append(", transaction=")
                    .append(transaction).append(", parameters=").append(Arrays.toString(parameters));
            if (item >= 0) text.append(", item=").append(item);
        }
        return text.toString();
    }

    private static Integer responseCode(Exception error) {
        return error instanceof PtpResponseException ? ((PtpResponseException) error).responseCode() : null;
    }

    private static String readableError(Exception error) {
        if (error instanceof PtpResponseException) {
            int code = ((PtpResponseException) error).responseCode();
            if (code == 0x201e) return "相机已有 PTP 会话；本工具未接管或关闭该会话。";
            return "相机返回 PTP 错误 " + PtpConstants.hex16(code) + " / " + PtpConstants.responseName(code) + "。";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class Operations implements PictureControlReadSession.Operations,
            PictureControlImportSession.Operations {
        private final PtpIpClient client;
        private final List<String> commands;
        Operations(PtpIpClient client, List<String> commands) { this.client = client; this.commands = commands; }
        @Override public byte[] getData(int operation, long transaction, long... parameters) throws IOException {
            record(operation, transaction, parameters);
            return client.getPictureControlData(operation, transaction, parameters);
        }
        @Override public PtpContainer command(int operation, long transaction, long... parameters) throws IOException {
            record(operation, transaction, parameters);
            return client.pictureControlCommand(operation, transaction, parameters);
        }
        @Override public PtpContainer sendData(int operation, long transaction, byte[] bytes,
                                                long... parameters) throws IOException {
            record(operation, transaction, parameters);
            return client.sendPictureControlData(operation, transaction, bytes, parameters);
        }
        private void record(int operation, long transaction, long[] parameters) {
            commands.add(PtpConstants.hex16(operation) + " transaction=" + transaction
                    + " parameters=" + Arrays.toString(parameters));
        }
    }
}
