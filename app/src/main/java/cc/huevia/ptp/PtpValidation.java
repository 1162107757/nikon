package cc.huevia.ptp;

/** Checks transaction identity, operation identity and response status before parsing data. */
public final class PtpValidation {
    private PtpValidation() {}

    public static byte[] requireData(PtpContainer container, long expectedTransactionId,
                                     int expectedOperation) throws PtpProtocolException {
        requireTransaction(container, expectedTransactionId);
        if (container.type() == PtpContainer.RESPONSE) {
            requireOkResponse(container, expectedTransactionId);
            throw new PtpProtocolException("Expected PTP data but received an OK response");
        }
        if (container.type() != PtpContainer.DATA) {
            throw new PtpProtocolException("Expected PTP data, received type " + container.type());
        }
        if (container.code() != expectedOperation) {
            throw new PtpProtocolException("PTP data operation mismatch: "
                    + PtpConstants.hex16(container.code()) + ", expected "
                    + PtpConstants.hex16(expectedOperation));
        }
        return container.payload();
    }

    public static void requireOkResponse(PtpContainer container, long expectedTransactionId)
            throws PtpProtocolException {
        requireTransaction(container, expectedTransactionId);
        if (container.type() != PtpContainer.RESPONSE) {
            throw new PtpProtocolException("Expected PTP response, received type " + container.type());
        }
        if (container.code() != PtpConstants.RESPONSE_OK) {
            throw new PtpResponseException(container.code(), container.transactionId());
        }
    }

    private static void requireTransaction(PtpContainer container, long expectedTransactionId)
            throws PtpProtocolException {
        PtpContainer.requireUInt32(expectedTransactionId, "expectedTransactionId");
        if (container == null) throw new PtpProtocolException("Missing PTP container");
        if (container.transactionId() != expectedTransactionId) {
            throw new PtpProtocolException("PTP transaction mismatch: " + container.transactionId()
                    + ", expected " + expectedTransactionId);
        }
    }
}
