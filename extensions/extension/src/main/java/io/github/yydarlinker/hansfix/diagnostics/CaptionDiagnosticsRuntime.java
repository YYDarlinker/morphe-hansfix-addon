package io.github.yydarlinker.hansfix.diagnostics;

import android.os.Handler;
import android.os.Looper;
import java.nio.ByteBuffer;
import java.util.Map;

/** Android hook boundary. No host/Morphe/Cronet imports, logging, disk, or network access.
 * Every injected callback, including class initialization and clock failures, is fail-closed.
 */
public final class CaptionDiagnosticsRuntime {
    private CaptionDiagnosticsRuntime() {}

    private static final class Holder {
        static final DiagnosticStore STORE = new DiagnosticStore(new DiagnosticStore.Clock() {
            @Override public long nanoTime() { return System.nanoTime(); }
        });
    }

    // Created only by an explicit UI start, never by playback callbacks. At most one queued timer.
    private static Handler expiryHandler;
    private static Runnable expiry;
    private static final class Expiry implements Runnable {
        @Override public void run() {
            try {
                synchronized (CaptionDiagnosticsRuntime.class) {
                    if (Holder.STORE.isRecording() && expiryHandler != null) {
                        expiryHandler.postDelayed(this, 1000L);
                    }
                }
            } catch (Throwable ignored) { /* Diagnostics must never disrupt the host. */ }
        }
    }

    /** Bridge should guard BEFORE fetching response headers or other host metadata. */
    public static boolean isRecording() {
        try { return Holder.STORE.isRecording(); } catch (Throwable ignored) { return false; }
    }
    /** Fixed event kind only; no track, manager, language, URL or origin string accepted. */
    public static void onSelectionEvent(int kind) {
        try { Holder.STORE.onSelectionEvent(kind); } catch (Throwable ignored) { }
    }
    public static void onRequest(Object callback, String url) {
        try { Holder.STORE.onRequest(callback, url); } catch (Throwable ignored) { }
    }
    public static void onBuilder(Object builder, Object callback) {
        try { Holder.STORE.onBuilder(builder, callback); } catch (Throwable ignored) { }
    }
    public static void onHeader(Object builder, String name, String value) {
        try { Holder.STORE.onHeader(builder, name, value); } catch (Throwable ignored) { }
    }
    public static void onResponse(Object callback, int status, String mime) {
        try { Holder.STORE.onResponse(callback, status, mime); } catch (Throwable ignored) { }
    }
    public static void onResponseHeaders(Object callback, int status, Map<?, ?> headers) {
        try { Holder.STORE.onResponseHeaders(callback, status, headers); } catch (Throwable ignored) { }
    }
    public static void beforeRead(Object request, ByteBuffer buffer) {
        try { Holder.STORE.beforeRead(request, buffer); } catch (Throwable ignored) { }
    }
    public static void onRead(Object callback, Object request, ByteBuffer buffer) {
        try { Holder.STORE.onRead(callback, request, buffer); } catch (Throwable ignored) { }
    }
    /** kind: 0 success, 1 failed, 2 cancelled; no exception parameter is accepted. */
    public static void onTerminal(Object callback, int kind) {
        try { Holder.STORE.onTerminal(callback, kind); } catch (Throwable ignored) { }
    }
    public static void onRedirect(Object callback) {
        try { Holder.STORE.onRedirect(callback); } catch (Throwable ignored) { }
    }

    static synchronized void startRecording() {
        try {
            if (Holder.STORE.isRecording()) return;
            // Start only if the platform can also schedule automatic expiry.
            if (expiryHandler == null) expiryHandler = new Handler(Looper.getMainLooper());
            if (expiry == null) expiry = new Expiry();
            expiryHandler.removeCallbacks(expiry);
            Holder.STORE.start();
            if (!expiryHandler.postDelayed(expiry, 15L * 60L * 1000L)) Holder.STORE.stop();
        } catch (Throwable ignored) {
            try { Holder.STORE.stop(); } catch (Throwable ignoredAgain) { }
        }
    }
    static synchronized void stopRecording() {
        try { Holder.STORE.stop(); } catch (Throwable ignored) { }
        try { if (expiryHandler != null) expiryHandler.removeCallbacks(expiry); } catch (Throwable ignored) { }
    }
    static synchronized void clearReport() {
        try { Holder.STORE.clear(); } catch (Throwable ignored) { }
        try { if (expiryHandler != null) expiryHandler.removeCallbacks(expiry); } catch (Throwable ignored) { }
    }
    static String report() {
        try { return Holder.STORE.report(); }
        catch (Throwable ignored) { return "字幕诊断暂不可用（未保存异常信息）。"; }
    }
}
