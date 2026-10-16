package com.nikonpreset.usbprobe.ptp;

/** A correctly framed PTP response whose status is not OK. */
public final class PtpResponseException extends PtpProtocolException {
    private final int responseCode;
    private final long transactionId;

    public PtpResponseException(int responseCode, long transactionId) {
        super("PTP " + PtpConstants.responseName(responseCode) + " ("
                + PtpConstants.hex16(responseCode) + "), transaction " + transactionId);
        this.responseCode = responseCode;
        this.transactionId = transactionId;
    }

    public int responseCode() { return responseCode; }
    public long transactionId() { return transactionId; }
}
