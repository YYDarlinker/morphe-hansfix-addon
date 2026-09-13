package io.github.yydarlinker.hansfix.captionauth;

/** Standalone Java regression test; no Android/JUnit dependency. */
public final class CaptionAuthRuntimeTest {
    private CaptionAuthRuntimeTest() {}

    public static void main(String[] args) {
        assertEquals(
                "SAPISIDHASH 1700000000_9e5071f149fc514366f78b22d1a169786d40ed32",
                CaptionAuthRuntime.authorizationForCookiesAt(
                        "YSC=x; SAPISID=abc123; VISITOR_INFO1_LIVE=y",
                        1700000000L
                ),
                "SAPISID header"
        );

        String fallback = CaptionAuthRuntime.authorizationForCookiesAt(
                "foo=bar; __Secure-3PAPISID=abc123; baz=qux",
                1700000000L
        );
        assertEquals(
                "SAPISIDHASH 1700000000_9e5071f149fc514366f78b22d1a169786d40ed32",
                fallback,
                "__Secure-3PAPISID fallback"
        );

        String prefersSapisid = CaptionAuthRuntime.authorizationForCookiesAt(
                "SAPISID=abc123; __Secure-3PAPISID=different",
                1700000000L
        );
        assertEquals(
                "SAPISIDHASH 1700000000_9e5071f149fc514366f78b22d1a169786d40ed32",
                prefersSapisid,
                "SAPISID precedence"
        );

        assertNull(
                CaptionAuthRuntime.authorizationForCookiesAt(
                        "YSC=x; VISITOR_INFO1_LIVE=y; __Secure-ROLLOUT_TOKEN=z",
                        1700000000L
                ),
                "anonymous caption cookies must not produce authorization"
        );
        assertNull(CaptionAuthRuntime.authorizationForCookiesAt(null, 1700000000L), "null cookie");
        assertNull(CaptionAuthRuntime.authorizationForCookiesAt("SAPISID=", 1700000000L), "empty SAPISID");
        assertNull(CaptionAuthRuntime.authorizationForCookiesAt("SAPISID=abc123", -1L), "negative timestamp");

        // Cookie values are split on the first '=' only.
        String withEquals = CaptionAuthRuntime.authorizationForCookiesAt(
                "SAPISID=abc=123",
                1700000000L
        );
        if (withEquals == null || !withEquals.startsWith("SAPISIDHASH 1700000000_")) {
            throw new AssertionError("cookie value containing '=' was not accepted");
        }

        assertEquals("https://www.youtube.com", CaptionAuthRuntime.origin(), "origin");
        System.out.println("CaptionAuthRuntimeTest: PASS");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertNull(Object value, String label) {
        if (value != null) throw new AssertionError(label + ": expected null but got " + value);
    }
}
