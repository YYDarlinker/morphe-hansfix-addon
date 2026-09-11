package io.github.yydarlinker.hansfix.diagnostics;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/** Optional boundary test: compile/run with the Android SDK android.jar on the classpath.
 * No Android methods are executed, no UI or timer is started; this is NOT a device test.
 * The core store is enabled reflectively so the fail-open playback boundary can be exercised.
 */
public final class FacadeBoundaryTest {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("boundary check failed");
    }
    private static final class Host {
        @Override public boolean equals(Object other) { throw new AssertionError("equals called"); }
        @Override public int hashCode() { throw new AssertionError("hashCode called"); }
        @Override public String toString() { throw new AssertionError("toString called"); }
    }
    private static final class ThrowingHeaders extends AbstractMap<Object, Object> {
        int accesses;
        final boolean error;
        ThrowingHeaders(boolean error) { this.error = error; }
        @Override public Set<Map.Entry<Object, Object>> entrySet() {
            accesses++;
            if (error) throw new AssertionError("synthetic" + "-" + "canary");
            throw new IllegalStateException("synthetic" + "-" + "canary");
        }
    }
    public static void main(String[] args) throws Exception {
        Class<?> holder = Class.forName(CaptionDiagnosticsRuntime.class.getName() + "$Holder");
        Field field = holder.getDeclaredField("STORE"); field.setAccessible(true);
        DiagnosticStore store = (DiagnosticStore) field.get(null);
        store.clear();
        check(!CaptionDiagnosticsRuntime.isRecording());
        ThrowingHeaders error = new ThrowingHeaders(true), exception = new ThrowingHeaders(false);
        Object callback = new Host(), builder = new Host(), request = new Host();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        CaptionDiagnosticsRuntime.onResponseHeaders(callback, 200, error);
        check(error.accesses == 0);
        CaptionDiagnosticsRuntime.onRequest(callback, "https://www.youtube.com/api/timedtext");
        CaptionDiagnosticsRuntime.beforeRead(request, buffer);
        check(store.identityCount() == 0);
        store.start();
        check(CaptionDiagnosticsRuntime.isRecording());
        CaptionDiagnosticsRuntime.onRequest(callback, "https://www.youtube.com/api/timedtext?lang=en");
        CaptionDiagnosticsRuntime.onBuilder(builder, callback);
        CaptionDiagnosticsRuntime.onHeader(builder, "Cookie", "short" + "-" + "value");
        CaptionDiagnosticsRuntime.onResponseHeaders(callback, 200, error);
        CaptionDiagnosticsRuntime.onResponseHeaders(callback, 200, exception);
        check(error.accesses == 1 && exception.accesses == 1);
        check(!store.report().contains("synthetic" + "-" + "canary"));
        CaptionDiagnosticsRuntime.onResponse(callback, 200, "text/xml");
        CaptionDiagnosticsRuntime.beforeRead(request, buffer); buffer.position(7);
        CaptionDiagnosticsRuntime.onRead(callback, request, buffer);
        CaptionDiagnosticsRuntime.onTerminal(callback, 0);
        CaptionDiagnosticsRuntime.onTerminal(callback, 1);
        check(store.report().contains("known_bytes=7 read_observations=1 bytes_complete=true"));
        CaptionDiagnosticsRuntime.onRequest(null, null);
        CaptionDiagnosticsRuntime.onBuilder(null, null);
        CaptionDiagnosticsRuntime.onHeader(null, null, null);
        CaptionDiagnosticsRuntime.onResponse(null, -1, null);
        CaptionDiagnosticsRuntime.onResponseHeaders(null, -1, null);
        CaptionDiagnosticsRuntime.beforeRead(null, null);
        CaptionDiagnosticsRuntime.onRead(null, null, null);
        CaptionDiagnosticsRuntime.onRedirect(null);
        CaptionDiagnosticsRuntime.onTerminal(null, -1);
        store.stop();
        String stopped = store.report();
        CaptionDiagnosticsRuntime.onResponseHeaders(callback, 503, error);
        check(error.accesses == 1 && store.report().equals(stopped));
        check(!CaptionDiagnosticsRuntime.isRecording() && store.identityCount() == 0);
        System.out.println("PASS FacadeBoundaryTest: " + checks + " checks (no Android UI execution)");
    }
}
