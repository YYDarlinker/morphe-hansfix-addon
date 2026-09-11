package io.github.yydarlinker.hansfix;

/** Standalone caption compatibility logic. No host classes or settings are accessed here. */
public final class HansFixRuntime {
    private static final String HOST = "^https://(?:www\\.|m\\.)?youtube\\.com/api/timedtext\\?[^#]*$";
    private static final String TARGET = "([?&]tlang=)zh-Hant(?=&|$)";

    private HansFixRuntime() {}

    /**
     * False when only the shared extension is installed by subtitle memory.
     * The HansFix patch replaces this DEX method with true, independently of Cookie settings.
     */
    public static boolean isEnabled() {
        return false;
    }

    public static String rewriteTraditionalCaptionUrl(String url) {
        return rewriteForState(url, isEnabled());
    }

    public static String captionMenuLabel(String languageCode, boolean translated,
            String url, String originalLabel, boolean hasNativeHans) {
        return labelForState(languageCode, translated, url, originalLabel,
                hasNativeHans, isEnabled());
    }

    static String rewriteForState(String url, boolean enabled) {
        if (!enabled || url == null || !url.matches(HOST)) {
            return url;
        }
        return url.replaceAll(TARGET, "$1zh-Hans");
    }

    static String labelForState(String languageCode, boolean translated,
            String url, String originalLabel, boolean hasNativeHans, boolean enabled) {
        if (!enabled || !translated || !"zh-Hant".equals(languageCode)
                || originalLabel == null || url == null
                || url.equals(rewriteForState(url, enabled))) {
            return originalLabel;
        }
        return hasNativeHans ? "中文（简体，兼容入口）" : "中文（简体）";
    }
}
