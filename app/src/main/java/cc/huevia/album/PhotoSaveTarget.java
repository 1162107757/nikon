package cc.huevia.album;

/** Destination captured when a receive or export task is accepted. */
public enum PhotoSaveTarget {
    APP_ONLY,
    PHONE_ALBUM;

    public String label() { return this == APP_ONLY ? "仅保留在应用" : "同时保存到手机相册"; }
}
