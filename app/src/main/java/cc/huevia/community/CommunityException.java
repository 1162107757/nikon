package cc.huevia.community;

import java.io.IOException;

public final class CommunityException extends IOException {
    public final String code; public final int status;
    public CommunityException(String code, int status, String message) { super(message); this.code = code; this.status = status; }
}
