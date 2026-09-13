package io.github.yydarlinker.hansfix.captionauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Builds the browser-style SAPISIDHASH authorization used by authenticated YouTube web
 * requests. This class never stores, logs, exports, or otherwise retains cookie values.
 */
public final class CaptionAuthRuntime {
    private static final String ORIGIN = "https://www.youtube.com";
    private static volatile String diagnosticState = "not_used";

    private CaptionAuthRuntime() {}

    public static String origin() {
        return ORIGIN;
    }

    /** Non-secret process-local research state for the diagnostics report. */
    public static String diagnosticState() {
        return diagnosticState;
    }

    /**
     * Returns a SAPISIDHASH header value, or null when the supplied Cookie header does not
     * contain a usable authenticated YouTube identity. All failures are fail-closed.
     */
    public static String authorizationForCookies(String cookieHeader) {
        try {
            String value = authorizationForCookiesAt(cookieHeader, System.currentTimeMillis() / 1000L);
            diagnosticState = value == null ? "missing_auth_cookie" : "authorization_ready";
            return value;
        } catch (Throwable ignored) {
            diagnosticState = "error";
            return null;
        }
    }

    // Package-private deterministic entry point for the standalone runtime test.
    static String authorizationForCookiesAt(String cookieHeader, long epochSeconds) {
        if (cookieHeader == null || cookieHeader.isEmpty() || epochSeconds < 0) return null;

        String sapisid = null;
        String secure3papisid = null;
        String[] entries = cookieHeader.split(";");
        for (String raw : entries) {
            if (raw == null) continue;
            String entry = raw.trim();
            int equals = entry.indexOf('=');
            if (equals <= 0) continue;
            String name = entry.substring(0, equals).trim();
            String value = entry.substring(equals + 1).trim();
            if (value.isEmpty()) continue;
            if ("SAPISID".equals(name)) {
                sapisid = value;
            } else if ("__Secure-3PAPISID".equals(name)) {
                secure3papisid = value;
            }
        }

        if (sapisid == null) sapisid = secure3papisid;
        if (sapisid == null) return null;

        try {
            String input = epochSeconds + " " + sapisid + " " + ORIGIN;
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int value = b & 0xff;
                if (value < 0x10) hex.append('0');
                hex.append(Integer.toHexString(value));
            }
            return "SAPISIDHASH " + epochSeconds + "_" + hex;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
