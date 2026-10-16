package com.nikonpreset.usbprobe.ptp;

/** Standard PTP operation and response codes used by the read-only probe. */
public final class PtpConstants {
    public static final int GET_DEVICE_INFO = 0x1001;
    public static final int OPEN_SESSION = 0x1002;
    public static final int CLOSE_SESSION = 0x1003;
    public static final int GET_STORAGE_IDS = 0x1004;
    public static final int GET_STORAGE_INFO = 0x1005;
    public static final int GET_OBJECT_HANDLES = 0x1007;
    public static final int GET_OBJECT_INFO = 0x1008;
    public static final int GET_OBJECT = 0x1009;
    public static final int SEND_OBJECT_INFO = 0x100c;
    public static final int SEND_OBJECT = 0x100d;
    public static final int FORMAT_UNDEFINED_FILE = 0x3000;
    public static final int FORMAT_ASSOCIATION = 0x3001;
    public static final int ASSOCIATION_GENERIC_FOLDER = 1;
    public static final int RESPONSE_OK = 0x2001;
    public static final int RESPONSE_DEVICE_BUSY = 0x2019;
    public static final int RESPONSE_SESSION_ALREADY_OPEN = 0x201e;

    private PtpConstants() {}

    public static String hex16(int value) {
        return String.format(java.util.Locale.ROOT, "0x%04X", value & 0xffff);
    }

    public static String responseName(int code) {
        switch (code) {
            case 0x2001: return "OK";
            case 0x2002: return "GeneralError";
            case 0x2003: return "SessionNotOpen";
            case 0x2004: return "InvalidTransactionID";
            case 0x2005: return "OperationNotSupported";
            case 0x2006: return "ParameterNotSupported";
            case 0x2007: return "IncompleteTransfer";
            case 0x2008: return "InvalidStorageID";
            case 0x2009: return "InvalidObjectHandle";
            case 0x200a: return "DevicePropNotSupported";
            case 0x200b: return "InvalidObjectFormatCode";
            case 0x200c: return "StoreFull";
            case 0x200d: return "ObjectWriteProtected";
            case 0x200e: return "StoreReadOnly";
            case 0x200f: return "AccessDenied";
            case 0x2010: return "NoThumbnailPresent";
            case 0x2011: return "SelfTestFailed";
            case 0x2012: return "PartialDeletion";
            case 0x2013: return "StoreNotAvailable";
            case 0x2014: return "SpecificationByFormatUnsupported";
            case 0x2015: return "NoValidObjectInfo";
            case 0x2016: return "InvalidCodeFormat";
            case 0x2017: return "UnknownVendorCode";
            case 0x2018: return "CaptureAlreadyTerminated";
            case 0x2019: return "DeviceBusy";
            case 0x201a: return "InvalidParentObject";
            case 0x201b: return "InvalidDevicePropFormat";
            case 0x201c: return "InvalidDevicePropValue";
            case 0x201d: return "InvalidParameter";
            case 0x201e: return "SessionAlreadyOpen";
            case 0x201f: return "TransactionCanceled";
            case 0x2020: return "SpecificationOfDestinationUnsupported";
            default: return "UnknownResponse";
        }
    }
}
