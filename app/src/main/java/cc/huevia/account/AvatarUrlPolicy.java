package cc.huevia.account;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Public images only: the account bearer is never attached to an avatar request. */
public final class AvatarUrlPolicy {
    private static final URI ORIGIN = URI.create(AccountHttpTransport.BASE_URL);
    private static final Set<String> QQ = Set.of("qlogo.cn", "q.qlogo.cn", "q1.qlogo.cn", "q2.qlogo.cn",
            "q3.qlogo.cn", "q4.qlogo.cn", "thirdqq.qlogo.cn");
    private AvatarUrlPolicy() { }

    public static boolean allows(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (QQ.contains(host)) return true;
            return ORIGIN.getHost().equalsIgnoreCase(host) && uri.getRawQuery() == null
                    && uri.getRawPath().matches(java.util.regex.Pattern.quote(ORIGIN.getRawPath())
                    + "/avatars/(?i:[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\\.jpg");
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) { return false; }
    }
}
