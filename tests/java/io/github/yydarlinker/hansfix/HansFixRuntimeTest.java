package io.github.yydarlinker.hansfix;

import java.util.List;
import java.util.Objects;

/** JDK-only executable tests: literal fixtures and decision-table expectations, no regex oracle. */
public final class HansFixRuntimeTest {
    private static final String BASE = "https://www.youtube.com/api/timedtext?";
    private static int checks;

    private static void equal(Object expected, Object actual, String context) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(context + ": expected <" + expected + "> but got <" + actual + ">");
        }
        checks++;
    }

    private static void same(Object expected, Object actual, String context) {
        if (expected != actual) throw new AssertionError(context + ": original reference changed");
        checks++;
    }

    private static void url(String input, boolean enabled, String expected) {
        equal(expected, HansFixRuntime.rewriteForState(input, enabled), "URL fixture");
    }

    // The exact 21 synthetic input/state/expected cases from the accepted CaptionUrlTest.
    private static void legacyUrls() {
        int start = checks;
        url(BASE + "v=example&tlang=zh-Hant", true, BASE + "v=example&tlang=zh-Hans");
        url(BASE + "tlang=zh-Hant&lang=en", true, BASE + "tlang=zh-Hans&lang=en");
        String tail = "&signature=ABCD%2B123%3D&pot=xyz%2F%2B%3D&fmt=json3&expire=12345";
        url(BASE + "lang=en&tlang=zh-Hant" + tail, true, BASE + "lang=en&tlang=zh-Hans" + tail);
        for (String host : List.of("youtube.com", "m.youtube.com")) {
            url("https://" + host + "/api/timedtext?tlang=zh-Hant", true,
                    "https://" + host + "/api/timedtext?tlang=zh-Hans");
        }
        for (String input : List.of(BASE + "lang=en", BASE + "lang=zh-Hant",
                BASE + "lang=en&tlang=zh-Hans", BASE + "lang=en&tlang=ja",
                BASE + "tlang=zh-Hant-TW", BASE + "not_tlang=zh-Hant",
                BASE + "x=abc%26tlang%3Dzh-Hant", BASE + "v=a#tlang=zh-Hant",
                "https://example.com/api/timedtext?tlang=zh-Hant",
                "https://www.youtube.com.evil.example/api/timedtext?tlang=zh-Hant",
                "https://www.youtube.com/watch?tlang=zh-Hant",
                "https://www.youtube.com/youtubei/v1/player?tlang=zh-Hant",
                "http://www.youtube.com/api/timedtext?tlang=zh-Hant")) {
            url(input, true, input);
        }
        url(BASE + "tlang=zh-Hant", false, BASE + "tlang=zh-Hant");
        url(null, true, null);
        url("", true, "");
        if (checks - start != 21) throw new AssertionError("Legacy fixture count drift");
    }

    private record UrlCase(String input, String output, boolean changes) {}

    private static List<UrlCase> boundaries() {
        return List.of(
            new UrlCase(BASE + "tlang=zh-Hant", BASE + "tlang=zh-Hans", true),
            new UrlCase(BASE + "tlang=zh-Hant&tlang=zh-Hant&lang=zh-Hant",
                    BASE + "tlang=zh-Hans&tlang=zh-Hans&lang=zh-Hant", true),
            new UrlCase(BASE + "tlang=zh-Hant&&x=&tlang=zh-Hans",
                    BASE + "tlang=zh-Hans&&x=&tlang=zh-Hans", true),
            // Preserve the old regex's literal question-mark boundary, not URL-parser semantics.
            new UrlCase(BASE + "x=?tlang=zh-Hant", BASE + "x=?tlang=zh-Hans", true),
            new UrlCase(BASE + "tlang=zh-Hant&x=%23fragment", BASE + "tlang=zh-Hans&x=%23fragment", true),
            new UrlCase(BASE + "tlang=zh-Hant#", BASE + "tlang=zh-Hant#", false),
            new UrlCase(BASE + "tlang=zh-Hant%26x=1", BASE + "tlang=zh-Hant%26x=1", false),
            new UrlCase(BASE + "tlang=zh%2DHant", BASE + "tlang=zh%2DHant", false),
            new UrlCase(BASE + "TLANG=zh-Hant", BASE + "TLANG=zh-Hant", false),
            new UrlCase(BASE + "tlang=zh-hant", BASE + "tlang=zh-hant", false),
            new UrlCase(BASE + "tlang=zh-Hant ", BASE + "tlang=zh-Hant ", false),
            new UrlCase(BASE + "tlang=zh-Hant\n", BASE + "tlang=zh-Hans\n", true),
            new UrlCase(BASE + "x=\n&tlang=zh-Hant", BASE + "x=\n&tlang=zh-Hans", true),
            new UrlCase("https://www.youtube.com:443/api/timedtext?tlang=zh-Hant",
                    "https://www.youtube.com:443/api/timedtext?tlang=zh-Hant", false),
            new UrlCase("https://WWW.youtube.com/api/timedtext?tlang=zh-Hant",
                    "https://WWW.youtube.com/api/timedtext?tlang=zh-Hant", false),
            new UrlCase("https://youtube.com/api/timedtext/?tlang=zh-Hant",
                    "https://youtube.com/api/timedtext/?tlang=zh-Hant", false),
            new UrlCase(null, null, false), new UrlCase("", "", false));
    }

    private static void boundaryUrls() {
        for (UrlCase fixture : boundaries()) {
            url(fixture.input(), true, fixture.output());
            same(fixture.input(), HansFixRuntime.rewriteForState(fixture.input(), false), "Disabled URL");
        }
    }

    private static void labelMatrix() {
        String[] codes = {"zh-Hant", "zh-Hans", "zh-hant", "zh-Hant-TW", " zh-Hant", "zh-Hant ", "en", "", null};
        String[] labels = {new String("中文（繁體）"), new String("Original label"), "", null};
        boolean[] states = {false, true};
        for (boolean enabled : states) for (boolean translated : states)
        for (boolean nativeHans : states) for (String code : codes)
        for (String original : labels) for (UrlCase fixture : boundaries()) {
            // Decision table uses fixture metadata, never the runtime rewrite as its oracle.
            boolean relabel = enabled && translated && "zh-Hant".equals(code)
                    && original != null && fixture.changes();
            String expected = relabel
                    ? (nativeHans ? "中文（简体，兼容入口）" : "中文（简体）") : original;
            String actual = HansFixRuntime.labelForState(code, translated, fixture.input(),
                    original, nativeHans, enabled);
            equal(expected, actual, "Label decision table");
            if (!relabel) same(original, actual, "Unchanged label");
        }
    }

    private static void preservationAndDefault() {
        String originalUrl = new String(BASE + "lang=zh-Hant&tlang=zh-Hant&kind=asr&x=1&x=2");
        String originalLabel = new String("中文（繁體）");
        String code = new String("zh-Hant");
        String urlSnapshot = new String(originalUrl);
        String labelSnapshot = new String(originalLabel);
        String codeSnapshot = new String(code);
        boolean translated = true;
        boolean nativeHans = true;
        equal(BASE + "lang=zh-Hant&tlang=zh-Hans&kind=asr&x=1&x=2",
                HansFixRuntime.rewriteForState(originalUrl, true), "Only target changed");
        equal("中文（简体，兼容入口）", HansFixRuntime.labelForState(code, translated,
                originalUrl, originalLabel, nativeHans, true), "Enabled label");
        equal(urlSnapshot, originalUrl, "Original URL content");
        equal(labelSnapshot, originalLabel, "Original label content");
        equal(codeSnapshot, code, "Original code content");
        equal(true, translated, "Translated flag");
        equal(true, nativeHans, "Native Hans flag");
        // An enabled pure-function call must never install global state or enable public APIs.
        for (int i = 0; i < 3; i++) {
            equal(false, HansFixRuntime.isEnabled(), "Missing bridge fails closed");
            same(originalUrl, HansFixRuntime.rewriteTraditionalCaptionUrl(originalUrl), "Public URL default");
            same(originalLabel, HansFixRuntime.captionMenuLabel(code, translated, originalUrl,
                    originalLabel, nativeHans), "Public label default");
        }
        same(null, HansFixRuntime.rewriteTraditionalCaptionUrl(null), "Public null URL");
        same(null, HansFixRuntime.captionMenuLabel(null, false, null, null, false), "Public null label");
    }

    public static void main(String[] args) {
        legacyUrls();
        boundaryUrls();
        labelMatrix();
        preservationAndDefault();
        System.out.println("PASS: " + checks + " assertions (21 legacy URLs; boundary, label matrix, preservation, fail-closed)");
    }
}
