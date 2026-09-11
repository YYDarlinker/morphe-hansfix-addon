package io.github.yydarlinker.hansfix.diagnostics;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** No Android dependencies; run with javac and java. Assertions are always enabled internally. */
public final class DiagnosticStoreTest {
    private static final String URL = "https://www.youtube.com/api/timedtext";
    private static int checks;
    private static final class Clock implements DiagnosticStore.Clock {
        long now;
        @Override public long nanoTime() { return now; }
        void ms(long ms) { now += ms * 1000000L; }
    }
    private static final class Host {
        @Override public boolean equals(Object other) { throw new AssertionError("host equals"); }
        @Override public int hashCode() { throw new AssertionError("host hashCode"); }
        @Override public String toString() { throw new AssertionError("host toString"); }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void contains(String text, String expected) {
        check(text.contains(expected), "missing fixed field: " + expected);
    }
    private static DiagnosticStore fresh(Clock c) {
        DiagnosticStore s = new DiagnosticStore(c);
        s.start();
        return s;
    }
    public static void main(String[] args) throws Exception {
        lifecycle();
        strictUrls();
        privacy();
        grouping();
        groupingUnknowns();
        groupingLifecycleAndBounds();
        selectionEvents();
        responseHeaders();
        bytes();
        boundsAndIdentity();
        terminalAndRedirect();
        concurrency();
        System.out.println("PASS DiagnosticStoreTest: " + checks + " checks");
    }

    private static void lifecycle() {
        Clock c = new Clock();
        DiagnosticStore s = new DiagnosticStore(c);
        Object cb = new Host(), request = new Host();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        check(!s.isRecording(), "default off");
        s.onRequest(cb, URL); s.beforeRead(request, buffer);
        contains(s.report(), "retained=0");
        s.start();
        String firstSession = s.report().split(" session=")[1].split(" ")[0];
        c.ms(12); s.onRequest(cb, URL); s.beforeRead(request, buffer);
        check(s.identityCount() > 0, "active identities");
        c.ms(20); s.stop();
        contains(s.report(), "start_ms=12 end_ms=32 terminal=incomplete");
        check(s.identityCount() == 0, "stop releases identities");
        String stopped = s.report();
        s.onTerminal(cb, 0); s.onRead(cb, request, buffer); s.onRequest(new Host(), URL);
        check(stopped.equals(s.report()), "off preserves report");
        s.start();
        check(!s.report().contains(firstSession), "new random session");
        s.onRequest(cb, URL);
        c.ms(899999); s.start();
        check(s.isRecording(), "repeated start no reset");
        c.ms(1); check(!s.isRecording(), "expires exactly 15 minutes");
        contains(s.report(), "stop_reason=expired");
        contains(s.report(), "end_ms=900000 terminal=incomplete");
        check(s.identityCount() == 0, "expiry releases all identities");
        s.clear(); check(!s.isRecording(), "clear stops");
        contains(s.report(), "retained=0"); contains(s.report(), "session=none");
        s.start(); s.onRequest(cb, URL); s.onTerminal(cb, 0); s.stop();
        contains(s.report(), "terminal=success");
    }

    private static void strictUrls() {
        String[] invalid = {
            "http://www.youtube.com/api/timedtext", "https://youtube.com/api/timedtext",
            "https://www.youtube.com.evil/api/timedtext", "https://evil/www.youtube.com/api/timedtext",
            "https://user@www.youtube.com/api/timedtext", "https://www.youtube.com:443/api/timedtext",
            URL + "/", URL + "evil", URL + "#fragment", URL + "?lang=%",
            "https://www.youtube.com/API/timedtext", "https://www.youtube.com/api/%74imedtext",
            "https://www.youtube.com/x/../api/timedtext", "https://www.youtube.com\\evil/api/timedtext",
            "https://www.youtube.com./api/timedtext", " " + URL, null,
            URL + "?v=" + String.join("", Collections.nCopies(8200, "x")),
            URL + "?" + String.join("&", Collections.nCopies(129, "v=x"))
        };
        DiagnosticStore s = fresh(new Clock());
        for (String url : invalid) s.onRequest(new Host(), url);
        contains(s.report(), "retained=0");
        s.onRequest(new Host(), URL);
        s.onRequest(new Host(), "https://m.youtube.com/api/timedtext?lang=en");
        s.onRequest(new Host(), "HTTPS://WWW.YOUTUBE.COM/api/timedtext");
        contains(s.report(), "retained=3");
        for (String fmt : new String[] {"json3", "srv1", "srv2", "srv3", "vtt", "srt", "ttml"}) {
            s.onRequest(new Host(), URL + "?fmt=" + fmt);
            contains(s.report(), "fmt=" + fmt);
        }
        s.onRequest(new Host(), URL + "?lang=%65n&tlang=zh-Hans&c=ANDROID&%70ot=");
        contains(s.report(), "lang=en tlang=zh-hans fmt=absent c=ANDROID pot_present=true");
        s.onRequest(new Host(), URL + "?lang=en&lang=fr&fmt=json3&fmt=vtt&c=WEB&c=IOS");
        contains(s.report(), "lang=ambiguous tlang=absent fmt=ambiguous c=ambiguous");
    }

    private static void privacy() throws Exception {
        // Short pieces deliberately avoid resembling secrets to repository safety scanners.
        String marker = "private" + "-" + "canary";
        String alphanumeric = "private" + "Canary" + "123456789";
        DiagnosticStore s = fresh(new Clock());
        Object cb = new Host(), builder = new Host(), request = new Host();
        s.onRequest(cb, URL + "?lang=" + alphanumeric + "&tlang=en-" + marker
                + "&fmt=" + marker + "&c=" + alphanumeric + "&pot=" + marker
                + "&v=" + marker + "&title=" + marker + "&signature=" + marker);
        s.onBuilder(builder, cb);
        s.onHeader(builder, "Cookie", marker);
        s.onHeader(builder, "cookie", "");
        s.onHeader(builder, "COOKIE", marker);
        s.onHeader(builder, "User-Agent", "Mozilla Android " + marker);
        s.onHeader(builder, "User-Agent", "Mozilla Windows NT " + marker);
        s.onHeader(builder, "Authorization", marker);
        s.onHeader(builder, "Content-Length", "999999");
        s.onResponse(cb, 200, "application/json; private=" + marker);
        byte[] data = marker.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(64);
        s.beforeRead(request, b); b.put(data); s.onRead(cb, request, b); s.onTerminal(cb, 0);
        String report = s.report();
        check(!report.contains(marker) && !report.contains(alphanumeric), "no raw data in export");
        check(!report.contains(URL) && !report.contains("signature="), "no full URL or unknown query");
        contains(report, "lang=other tlang=other fmt=other c=other pot_present=true");
        contains(report, "cookie_last=nonempty cookie_observations=3 ua_last=desktop ua_observations=2");
        contains(report, "mime=application/json");
        contains(report, "known_bytes=" + data.length);
        check(!report.contains("999999"), "ignore Content-Length");
        scanRetained(s, marker, alphanumeric, new IdentityHashMap<Object, Boolean>());
        for (String bad : new String[] {"en%0Aprivate", "en%00", "en-US-private", "%E2%80%AEen", "a1", "english", "abc123"}) {
            DiagnosticStore one = fresh(new Clock());
            one.onRequest(new Host(), URL + "?lang=" + bad);
            contains(one.report(), "lang=other");
        }
    }

    private static void scanRetained(Object object, String marker, String second,
            IdentityHashMap<Object, Boolean> visited) throws Exception {
        if (object == null || visited.put(object, Boolean.TRUE) != null) return;
        if (object instanceof String) {
            check(!((String) object).contains(marker) && !((String) object).contains(second), "retained string privacy");
            return;
        }
        if (object instanceof byte[]) {
            String bytes = new String((byte[]) object, StandardCharsets.UTF_8);
            check(!bytes.contains(marker) && !bytes.contains(second), "retained byte privacy");
            return;
        }
        if (object instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> item : ((Map<?, ?>) object).entrySet()) {
                scanRetained(item.getKey(), marker, second, visited);
                scanRetained(item.getValue(), marker, second, visited);
            }
            return;
        }
        if (object instanceof WeakReference<?>) return; // Identities only, never dereference host data.
        if (object instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) object) scanRetained(item, marker, second, visited);
            return;
        }
        if (!object.getClass().getName().startsWith(DiagnosticStore.class.getName())) return;
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            scanRetained(field.get(object), marker, second, visited);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static String requestBlock(DiagnosticStore store, long sequence) {
        String report = store.report();
        String start = "request=" + sequence + " ";
        int index = report.indexOf(start);
        check(index >= 0, "request retained: " + sequence);
        int end = report.indexOf("\nrequest=", index);
        int timeline = report.indexOf("\nselection_timeline", index);
        if (end < 0 || timeline < end) end = timeline;
        return report.substring(index, end < 0 ? report.length() : end);
    }

    private static long groupId(DiagnosticStore store, long sequence, String kind) {
        String text = requestBlock(store, sequence).split(kind + "_group=")[1].split("[ \n]")[0];
        return "unknown".equals(text) ? -1 : Long.parseLong(text);
    }

    private static String identityUrl() {
        return URL + "?v=aB3_dE6-fG9&lang=en&tlang=zh-Hans&fmt=json3&kind=asr&name=Original";
    }

    private static void grouping() throws Exception {
        DiagnosticStore s = fresh(new Clock());
        String base = identityUrl();
        s.onRequest(new Host(), base + "&signature=first");
        s.onRequest(new Host(), base + "&signature=second");
        s.onRequest(new Host(), base.replace("zh-Hans", "zh-Hant") + "&signature=second");
        s.onRequest(new Host(), base + "&signature=first");
        check(groupId(s, 1, "video") == groupId(s, 2, "video"), "signatures keep video identity");
        check(groupId(s, 1, "track") == groupId(s, 2, "track"), "signatures keep track identity");
        check(groupId(s, 1, "request") != groupId(s, 2, "request"), "different signing URLs differ");
        check(groupId(s, 1, "video") == groupId(s, 3, "video"), "target change keeps video");
        check(groupId(s, 1, "track") != groupId(s, 3, "track"), "target change changes track");
        check(groupId(s, 1, "request") == groupId(s, 4, "request"), "exact URL repeats share request group");
        check(groupId(s, 1, "video") != groupId(s, 1, "track"), "group domains separate");
        s.onRequest(new Host(), base.replace("aB3_dE6-fG9", "zY8_xW5-vU2"));
        check(groupId(s, 1, "video") != groupId(s, 5, "video"), "different video differs");
        check(groupId(s, 1, "track") != groupId(s, 5, "track"), "video is part of track");
        String[] variants = {
            base.replace("lang=en", "lang=EN"), base.replace("lang=en", "lang=zz-one"),
            base.replace("lang=en", "lang=zz-two"), base.replace("fmt=json3", "fmt=unknown-one"),
            base.replace("fmt=json3", "fmt=unknown-two"), base.replace("kind=asr", "kind=ASR"),
            base.replace("name=Original", "name=original"), base.replace("name=Original", "name="),
            base.replace("&name=Original", ""), base.replace("name=Original", "name=%2541"),
            base.replace("name=Original", "name=%41"), base.replace("name=Original", "name=%C3%A9"),
            base.replace("name=Original", "name=e%CC%81"), base.replace("&tlang=zh-Hans", ""),
            base.replace("tlang=zh-Hans", "tlang="), base.replace("&fmt=json3", ""),
            base.replace("fmt=json3", "fmt="), base.replace("&kind=asr", ""), base.replace("kind=asr", "kind=")
        };
        List<Long> ids = new ArrayList<Long>();
        ids.add(groupId(s, 1, "track"));
        for (int i = 0; i < variants.length; i++) {
            s.onRequest(new Host(), variants[i]);
            long id = groupId(s, 6 + i, "track");
            check(id > 0 && !ids.contains(id), "identity distinctions preserved: " + i);
            ids.add(id);
        }
        long seq = 5 + variants.length;
        s.onRequest(new Host(), base.replace("name=Original", "name=A+B"));
        s.onRequest(new Host(), base.replace("name=Original", "name=A%20B"));
        check(groupId(s, seq + 1, "track") == groupId(s, seq + 2, "track"), "query space equivalents");
        check(groupId(s, seq + 1, "request") != groupId(s, seq + 2, "request"), "request uses exact raw encoding");
        s.onRequest(new Host(), base.replace("v=a", "%76=%61"));
        check(groupId(s, 1, "video") == groupId(s, seq + 3, "video"), "encoded v decoded once");
        check(groupId(s, 1, "track") == groupId(s, seq + 3, "track"), "equivalent identity encoding");
        String marker = "hidden" + "Track" + "Canary";
        s.onRequest(new Host(), base.replace("name=Original", "name=" + marker) + "&signature=" + marker);
        scanRetained(s, marker, "aB3_dE6-fG9", new IdentityHashMap<Object, Boolean>());
        String report = s.report();
        check(!report.contains(marker) && !report.contains("aB3_dE6-fG9") && !report.contains(base), "grouping exports no raw identity");
        contains(report, "HMAC-SHA256"); contains(report, "不同签名 URL 不同组");
    }

    private static void groupingUnknowns() {
        String[] invalidVideo = {
            "", "?lang=en", "?v=", "?v=short&lang=en", "?v=aB3_dE6-fG9&v=aB3_dE6-fG9&lang=en",
            "?v=aB3_dE6-fG9&%76=zY8_xW5-vU2&lang=en", "?v=aB3_dE6-fG%39%00&lang=en",
            "?v=aB3_dE6-fG%FF&lang=en", "?v=aB3_dE6+fG9&lang=en", "?v=aB3_dE6-fG99&lang=en"
        };
        for (String query : invalidVideo) {
            DiagnosticStore s = fresh(new Clock()); s.onRequest(new Host(), URL + query);
            check(groupId(s, 1, "video") == -1, "invalid/missing/duplicate v unknown");
            check(groupId(s, 1, "track") == -1, "unknown video cannot form track group");
            check(groupId(s, 1, "request") > 0, "exact request group independent of video validity");
        }
        String base = identityUrl();
        String[] invalidTrack = {
            base.replace("&lang=en", ""), base.replace("lang=en", "lang="), base + "&lang=en",
            base + "&%6cang=en", base + "&tlang=zh-Hans", base + "&fmt=json3", base + "&kind=asr",
            base + "&name=Original", base.replace("name=Original", "name=%FF"),
            base.replace("name=Original", "name=%C0%AF"), base.replace("name=Original", "name=%00"),
            base.replace("name=Original", "name=" + String.join("", Collections.nCopies(257, "x")))
        };
        for (String raw : invalidTrack) {
            DiagnosticStore s = fresh(new Clock()); s.onRequest(new Host(), raw);
            check(groupId(s, 1, "video") > 0, "invalid track does not destroy valid video");
            check(groupId(s, 1, "track") == -1, "invalid/missing/duplicate track fields unknown");
        }
        DiagnosticStore bounded = fresh(new Clock());
        bounded.onRequest(new Host(), base.replace("name=Original", "name=" + String.join("", Collections.nCopies(256, "%41"))));
        check(groupId(bounded, 1, "track") > 0, "exact decode bound accepted");
        bounded.onRequest(new Host(), base.replace("name=Original", "name=" + String.join("", Collections.nCopies(257, "%41"))));
        check(groupId(bounded, 2, "track") == -1, "encoded over bound unknown, never truncated");
        bounded.onRequest(new Host(), base + "&%FF=unknown");
        check(groupId(bounded, 3, "video") == -1 && groupId(bounded, 3, "track") == -1, "invalid key conservatively unknown");
        bounded.onRequest(new Host(), base + "&signature=" + (char) 0xd800);
        // URI implementations may reject this earlier; if retained, never hash UTF-8 replacement bytes.
        if (bounded.report().contains("request=4 ")) check(groupId(bounded, 4, "request") == -1, "malformed Unicode raw URL not merged");
    }

    private static void zeroed(byte[] bytes, String message) {
        for (byte value : bytes) if (value != 0) { check(false, message); return; }
        check(true, message);
    }

    private static List<byte[]> digests(DiagnosticStore s) throws Exception {
        List<byte[]> result = new ArrayList<byte[]>();
        for (Object group : (List<?>) field(s, "groups")) result.add((byte[]) field(group, "digest"));
        return result;
    }

    private static void groupingLifecycleAndBounds() throws Exception {
        for (int operation = 0; operation < 3; operation++) {
            Clock c = new Clock(); DiagnosticStore s = fresh(c); s.onRequest(new Host(), identityUrl());
            byte[] key = (byte[]) field(s, "groupingSecret"); byte[] keyCopy = key.clone();
            List<byte[]> hashes = digests(s); byte[] previousDigest = hashes.get(0).clone();
            long video = groupId(s, 1, "video"), track = groupId(s, 1, "track"), request = groupId(s, 1, "request");
            s.start(); check(field(s, "groupingSecret") == key, "active start preserves salt");
            if (operation == 0) s.stop();
            else if (operation == 1) s.clear();
            else { c.ms(900000); s.isRecording(); }
            zeroed(key, "session secret wiped");
            for (byte[] digest : hashes) zeroed(digest, "mapped digest wiped");
            check(field(s, "groupingSecret") == null && digests(s).isEmpty(), "secret and map references released");
            if (operation != 1) {
                check(video == groupId(s, 1, "video") && track == groupId(s, 1, "track")
                        && request == groupId(s, 1, "request"), "displayed numbers retained after stop/expiry");
            } else contains(s.report(), "retained=0");
            s.start(); s.onRequest(new Host(), identityUrl());
            check(!Arrays.equals(keyCopy, (byte[]) field(s, "groupingSecret")), "fresh random secret each recording");
            check(!Arrays.equals(previousDigest, digests(s).get(0)), "same identity different session HMAC");
            check(groupId(s, 1, "video") == 1, "numbers session-local, not cross-session identities");
        }
        DiagnosticStore s = fresh(new Clock()); s.onRequest(new Host(), identityUrl());
        List<byte[]> oldest = digests(s);
        long firstVideo = groupId(s, 1, "video"), firstRequest = groupId(s, 1, "request");
        long previousRequest = firstRequest;
        for (int i = 1; i <= 85; i++) {
            s.onRequest(new Host(), URL + "?v=V" + String.format(java.util.Locale.ROOT, "%010d", i) + "&lang=en");
            check(digests(s).size() <= 240, "all group namespaces share one 240-entry bound");
            long current = groupId(s, i + 1, "request");
            check(current > previousRequest, "new IDs increase through eviction");
            previousRequest = current;
        }
        check(digests(s).size() == 240, "group map fills but does not exceed cap");
        for (byte[] digest : oldest) zeroed(digest, "evicted digests wiped");
        s.onRequest(new Host(), identityUrl());
        check(groupId(s, 87, "video") > previousRequest && groupId(s, 87, "video") != firstVideo,
                "evicted identity gets new ID, never recycled");
        check(groupId(s, 87, "request") > previousRequest, "request numbers not reused");
        s.stop(); check(digests(s).isEmpty(), "full map destroyed on stop");
    }

    private static void selectionEvents() throws Exception {
        Clock c = new Clock(); DiagnosticStore s = new DiagnosticStore(c);
        s.onSelectionEvent(0); contains(s.report(), "selection_retained=0");
        c.ms(5000); s.start();
        check(Modifier.isSynchronized(DiagnosticStore.class.getMethod("onSelectionEvent", int.class).getModifiers()),
                "selection event entrypoint synchronized");
        s.onSelectionEvent(-1); s.onSelectionEvent(3); s.onSelectionEvent(Integer.MAX_VALUE);
        contains(s.report(), "selection_retained=0");
        c.ms(7); s.onSelectionEvent(0); c.ms(9); s.onSelectionEvent(1); s.onSelectionEvent(2);
        contains(s.report(), "selection_event=1 time_ms=7 kind=0");
        contains(s.report(), "selection_event=2 time_ms=16 kind=1");
        contains(s.report(), "selection_event=3 time_ms=16 kind=2");
        contains(s.report(), "0=preferred_path"); contains(s.report(), "separate; no causal link to requests");
        contains(s.report(), "没有新增宿主选择钩子");
        for (int i = 0; i < 100; i++) { c.ms(1); s.onSelectionEvent(i % 3); }
        contains(s.report(), "selection_capacity=80 selection_retained=80 selection_evicted=23");
        check(!s.report().contains("selection_event=23 "), "old events evicted");
        contains(s.report(), "selection_event=24 time_ms=37 kind=2");
        contains(s.report(), "selection_event=103 time_ms=116 kind=0");
        for (Object event : (List<?>) field(s, "selectionEvents")) {
            for (Field field : event.getClass().getDeclaredFields()) check(field.getType().isPrimitive(), "events retain primitive fields only");
        }
        check(s.identityCount() == 0, "selection events introduce no host association");
        s.stop(); String stopped = s.report(); s.onSelectionEvent(0);
        check(stopped.equals(s.report()), "stopped events preserve report");
        s.clear(); contains(s.report(), "selection_retained=0 selection_evicted=0");
        s.start(); s.onSelectionEvent(2); contains(s.report(), "selection_event=1 time_ms=0 kind=2");
        s.stop(); s.start(); contains(s.report(), "selection_retained=0");
        c.ms(899999); s.onSelectionEvent(0); c.ms(1); s.onSelectionEvent(1);
        contains(s.report(), "selection_event=1 time_ms=899999 kind=0");
        contains(s.report(), "selection_retained=1"); contains(s.report(), "stop_reason=expired");
        s.clear(); contains(s.report(), "selection_retained=0");
    }

    private static void responseHeaders() {
        DiagnosticStore s = fresh(new Clock()); Object cb = new Host(); s.onRequest(cb, URL);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("Content-Type", Arrays.asList("text/plain", "application/json; charset=utf-8"));
        map.put("Retry-After", Arrays.asList("2", "120"));
        map.put("Set-Cookie", new Host());
        s.onResponseHeaders(cb, 429, map);
        contains(s.report(), "status=429 mime=application/json");
        contains(s.report(), "retry_after_seconds=120 mime_observations=2 retry_observations=2");
        for (String value : new String[] {"-1", "+2", "1.5", " 2", "99999999999", "86401", "Fri, 11 Sep 2026 10:00:00 GMT", "NaN"}) {
            map.put("Retry-After", Collections.singletonList(value));
            s.onResponseHeaders(cb, 503, map); contains(s.report(), "retry_after_seconds=unknown");
        }
        map.put("Retry-After", Collections.singletonList("86400"));
        s.onResponseHeaders(cb, 503, map); contains(s.report(), "retry_after_seconds=86400");
        map.put("Retry-After", Collections.singletonList("0"));
        s.onResponseHeaders(cb, 200, map); contains(s.report(), "retry_after_seconds=0");
        map.put("Content-Type", Collections.nCopies(9, "text/xml"));
        s.onResponseHeaders(cb, 200, map);
        contains(s.report(), "header_scan_truncated=true"); contains(s.report(), "mime=unknown");
        map.clear();
        for (int i = 0; i < 65; i++) map.put("unknown-" + i, new Host());
        s.onResponseHeaders(cb, 999, map);
        contains(s.report(), "status=unknown"); contains(s.report(), "header_scan_truncated=true");
        s.onResponseHeaders(cb, 200, null); contains(s.report(), "mime=unobserved");
        s.stop();
        s.onResponseHeaders(cb, 200, new LinkedHashMap<Object, Object>() {
            @Override public java.util.Set<Map.Entry<Object, Object>> entrySet() {
                throw new AssertionError("off path accessed map");
            }
        });
    }

    private static void bytes() {
        DiagnosticStore s = fresh(new Clock()); Object cb = new Host(), req = new Host();
        s.onRequest(cb, URL); ByteBuffer b = ByteBuffer.allocate(32); b.position(5);
        s.beforeRead(req, b); b.position(12); s.onRead(cb, req, b);
        check(b.position() == 12 && b.limit() == 32, "buffer untouched");
        b.clear(); s.beforeRead(req, b); b.position(4); s.onRead(cb, req, b);
        s.onTerminal(cb, 0); contains(s.report(), "known_bytes=11 read_observations=2 bytes_complete=true");
        for (int scenario = 0; scenario < 7; scenario++) {
            DiagnosticStore one = fresh(new Clock()); Object c = new Host(), r = new Host();
            one.onRequest(c, URL); ByteBuffer data = ByteBuffer.allocate(16); data.position(4);
            if (scenario != 0) one.beforeRead(r, data);
            if (scenario == 1) data.position(2); // Negative delta.
            if (scenario == 2) data.flip(); // Host already flipped buffer.
            if (scenario == 3) one.beforeRead(r, data); // Ambiguous outstanding read.
            if (scenario == 4) data = ByteBuffer.allocate(16); // Wrong buffer identity.
            if (scenario == 5) r = new Host(); // Wrong request identity.
            if (scenario == 6) data.limit(8); // Changed limit.
            one.onRead(c, r, data); one.onTerminal(c, 0);
            contains(one.report(), "known_bytes=0 read_observations=1 bytes_complete=false");
        }
        DiagnosticStore dup = fresh(new Clock()); Object c = new Host(), r = new Host();
        dup.onRequest(c, URL); b.clear(); dup.beforeRead(r, b); b.position(3);
        dup.onRead(c, r, b); dup.onRead(c, r, b); dup.onTerminal(c, 0);
        contains(dup.report(), "known_bytes=3 read_observations=2 bytes_complete=false");
        DiagnosticStore direct = fresh(new Clock()); Object dc = new Host(), dr = new Host();
        direct.onRequest(dc, URL); ByteBuffer db = ByteBuffer.allocateDirect(16);
        direct.beforeRead(dr, db); db.position(10); direct.onRead(dc, dr, db); direct.onTerminal(dc, 0);
        contains(direct.report(), "known_bytes=10 read_observations=1 bytes_complete=true");
    }

    private static void boundsAndIdentity() {
        DiagnosticStore s = fresh(new Clock()); List<Object> keep = new ArrayList<Object>();
        for (int i = 0; i < 400; i++) {
            Object cb = new Host(), builder = new Host(); keep.add(cb); keep.add(builder);
            s.onRequest(cb, URL); s.onBuilder(builder, cb);
        }
        contains(s.report(), "retained=80 evicted=320");
        check(!s.report().contains("request=1 "), "oldest evicted");
        check(s.identityCount() <= 80 * 3, "entry identity bound");
        List<ByteBuffer> buffers = new ArrayList<ByteBuffer>();
        List<Object> requests = new ArrayList<Object>();
        for (int i = 0; i < 200; i++) {
            Object req = new Host(); ByteBuffer b = ByteBuffer.allocate(1);
            requests.add(req); buffers.add(b); s.beforeRead(req, b);
        }
        check(s.baselineCount() == 80, "baseline cap");
        check(s.identityCount() <= 80 * 5, "total identity slots bounded");
        s.stop(); check(s.identityCount() == 0, "all identity slots released");
        DiagnosticStore reused = fresh(new Clock()); Object cb = new Host(), b = new Host();
        reused.onRequest(cb, URL); reused.onBuilder(b, cb); reused.onRequest(cb, URL);
        reused.onHeader(b, "Cookie", "x"); reused.onTerminal(cb, 0);
        contains(reused.report(), "terminal=incomplete"); contains(reused.report(), "ambiguous_callback=true");
        contains(reused.report(), "cookie_observations=0");
    }

    private static void terminalAndRedirect() {
        for (int kind = 0; kind < 3; kind++) {
            Clock c = new Clock(); DiagnosticStore s = fresh(c); Object cb = new Host();
            s.onRequest(cb, URL); c.ms(3); s.onTerminal(cb, kind);
            String report = s.report(); s.onTerminal(cb, (kind + 1) % 3); s.onResponse(cb, 599, "text/plain");
            check(report.equals(s.report()), "terminal idempotent");
            contains(report, "terminal=" + new String[] {"success", "failed", "cancelled"}[kind]);
            check(s.identityCount() == 0, "terminal identity release");
        }
        DiagnosticStore s = fresh(new Clock()); Object cb = new Host(), req = new Host(), builder = new Host();
        s.onRequest(cb, URL); s.onBuilder(builder, cb); s.onRedirect(cb);
        s.onResponse(cb, 200, "text/plain"); s.onResponseHeaders(cb, 200, Collections.singletonMap("Content-Type", "text/plain"));
        s.onHeader(builder, "Cookie", "x"); ByteBuffer b = ByteBuffer.allocate(16);
        s.beforeRead(req, b); b.position(8); s.onRead(cb, req, b); s.onTerminal(cb, 0);
        contains(s.report(), "redirects=1"); contains(s.report(), "status=unknown");
        contains(s.report(), "known_bytes=0 read_observations=0 bytes_complete=false");
        contains(s.report(), "cookie_observations=0");
    }

    private static void concurrency() throws Exception {
        final DiagnosticStore s = new DiagnosticStore(new DiagnosticStore.Clock() {
            @Override public long nanoTime() { return System.nanoTime(); }
        });
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            final int role = i;
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        go.await();
                        for (int n = 0; n < 500; n++) {
                            if (role == 0) { s.start(); if (n % 2 == 0) s.stop(); else s.clear(); }
                            else if (role == 1) { s.report(); s.isRecording(); s.onSelectionEvent(n % 4); }
                            else {
                                Object cb = new Host(), req = new Host(), builder = new Host();
                                ByteBuffer b = ByteBuffer.allocate(8);
                                s.onRequest(cb, URL); s.onBuilder(builder, cb); s.onHeader(builder, "Cookie", "x");
                                s.onResponseHeaders(cb, 200, Collections.singletonMap("Content-Type", Arrays.asList("text/xml")));
                                s.beforeRead(req, b); b.position(4); s.onRead(cb, req, b); s.onTerminal(cb, 0);
                            }
                        }
                    } catch (Throwable t) { failure.compareAndSet(null, t); }
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) { thread.join(20000L); check(!thread.isAlive(), "no deadlock"); }
        check(failure.get() == null, "concurrency failure");
        check(s.identityCount() <= 80 * 5, "concurrent bounds");
        s.clear(); check(!s.isRecording() && s.identityCount() == 0, "concurrent final clear");
    }
}
