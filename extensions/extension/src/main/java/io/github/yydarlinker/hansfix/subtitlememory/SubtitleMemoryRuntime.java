package io.github.yydarlinker.hansfix.subtitlememory;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import io.github.yydarlinker.hansfix.HansFixRuntime;

/** Global language memory. Native track objects are resolved afresh for every video. */
public final class SubtitleMemoryRuntime {
    private static final String STORE = "yydarlinker_subtitle_memory";
    private static final String LANGUAGE = "language";
    private SubtitleMemoryRuntime() {}

    /** Called only by YouTube's preference-writing selection path, not automatic application. */
    public static void onSelection(Object manager, Object track, Object origin) {
        if (manager == null || track == null || !(origin instanceof Enum<?>)
                || !"PREFERRED_TRACK".equals(((Enum<?>) origin).name())) return;
        String selected = effectiveLanguage(track);
        if (!isLanguage(selected)) return; // Closing CC does not erase the remembered language.
        Context context = context(manager);
        if (context != null) preferences(context).edit().putString(LANGUAGE, selected).apply();
    }

    /** No polling or delayed work: called after the current video's native tracks are ready. */
    public static Object resolve(Object manager) {
        if (manager == null) return null;
        Context context = context(manager);
        if (context == null) return null;
        String wanted = preferences(context).getString(LANGUAGE, null);
        if (!isLanguage(wanted)) return null;
        Object match = find(nativeTracks(manager), wanted);
        return match != null ? match : find(translatedTracks(manager), wanted);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static Object find(List<?> tracks, String wanted) {
        if (tracks != null) for (Object track : tracks) {
            if (track != null && wanted.equals(effectiveLanguage(track))) return track;
        }
        return null;
    }

    private static String effectiveLanguage(Object track) {
        String code = language(track);
        // HansFix maps a translated zh-Hant request to zh-Hans. Native Traditional stays Traditional.
        if ("zh-Hant".equals(code)) {
            String original = url(track);
            if (original != null && !original.equals(HansFixRuntime.rewriteTraditionalCaptionUrl(original))) {
                return "zh-Hans";
            }
        }
        return code;
    }

    private static boolean isLanguage(String code) {
        return code != null && !code.isEmpty()
                && !"DISABLE_CAPTIONS_OPTION".equals(code)
                && !"AUTO_TRANSLATE_CAPTIONS_OPTION".equals(code);
    }

    // Replaced with typed DEX accessors after structural validation; no reflection or host names in Java.
    private static Context context(Object manager) { return null; }
    private static String language(Object track) { return null; }
    private static String url(Object track) { return null; }
    private static List<?> nativeTracks(Object manager) { return null; }
    private static List<?> translatedTracks(Object manager) { return null; }
}

