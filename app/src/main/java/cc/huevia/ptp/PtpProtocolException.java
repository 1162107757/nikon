package cc.huevia.ptp;

import java.io.IOException;

/** Malformed PTP data, an unexpected container, or a failed transaction. */
public class PtpProtocolException extends IOException {
    public PtpProtocolException(String message) {
        super(message);
    }

    public PtpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
