package io.github.yydarlinker.hansfix.diagnostics;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Memory-only, bounded, platform-independent diagnostic state. Never reads buffer contents.
 * All transitions, including expiry and exports, are serialized on this store's monitor.
 * Host objects are compared ONLY by identity, held weakly, and never stringified.
 */
public final class DiagnosticStore {
    public static final int MAX_REQUESTS = 80;
    public static final int MAX_BASELINES = 80;
    public static final long RECORDING_NANOS = 15L * 60L * 1000000000L;
    private static final int MAX_URL = 8192;
    private static final int MAX_HEADERS = 1000000;
    public interface Clock { long nanoTime(); }

    private final Clock clock;
    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private final ArrayList<Baseline> baselines = new ArrayList<Baseline>();
    private boolean recording;
    private long started;
    private long sequence;
    private long evicted;
    private String session = "none";
    private String stopReason = "not_started";

    public DiagnosticStore(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock required");
        this.clock = clock;
    }

    /** Starting while active is a no-op. A new recording replaces the previous report. */
    public synchronized void start() {
        if (active()) return;
        reset();
        session = UUID.randomUUID().toString();
        started = clock.nanoTime();
        stopReason = "none";
        recording = true;
    }

    /** Preserve sanitized records, but release ALL live identity associations. */
    public synchronized void stop() {
        if (active()) finish("user", elapsed());
    }

    /** Clear also stops recording; an explicit new start is needed. */
    public synchronized void clear() {
        reset();
        stopReason = "cleared";
    }

    public synchronized boolean isRecording() { return active(); }

    private void reset() {
        recording = false;
        for (Entry e : entries) release(e);
        entries.clear();
        baselines.clear();
        sequence = 0;
        evicted = 0;
        started = 0;
        session = "none";
    }

    private long elapsed() {
        return Math.max(0L, clock.nanoTime() - started);
    }

    private boolean active() {
        if (recording && elapsed() >= RECORDING_NANOS) finish("expired", RECORDING_NANOS);
        return recording;
    }

    private void finish(String reason, long end) {
        recording = false;
        stopReason = reason;
        for (Entry e : entries) {
            if ("pending".equals(e.terminal)) {
                e.terminal = "incomplete";
                e.endMs = end / 1000000L;
            }
            release(e);
        }
        baselines.clear();
    }

    public synchronized void onRequest(Object callback, String url) {
        if (!active() || callback == null) return;
        Entry old = callbackEntry(callback);
        if (old != null) {
            // A shared callback cannot safely identify overlapping requests: quarantine it.
            old.terminal = "incomplete";
            old.endMs = elapsed() / 1000000L;
            old.ambiguousCallback = true;
            old.bytesUnknown = true;
            old.builder = null;
            old.request = null;
            baselines.clear();
            return;
        }
        SafeUrl safe = parse(url);
        if (safe == null) return;
        if (entries.size() == MAX_REQUESTS) {
            Entry removed = entries.remove(0);
            release(removed);
            // Unbound first-read baselines cannot be attributed to an evicted entry.
            baselines.clear();
            evicted++;
        }
        entries.add(new Entry(++sequence, elapsed() / 1000000L, callback, safe));
    }

    public synchronized void onBuilder(Object builder, Object callback) {
        if (!active() || builder == null) return;
        Entry e = liveEntry(callback);
        if (e == null || e.redirects != 0) return;
        // Reused builder identities cannot remain associated with multiple entries.
        for (Entry other : entries) {
            if (same(other.builder, builder)) other.builder = null;
        }
        e.builder = new WeakReference<Object>(builder);
    }

    public synchronized void onHeader(Object builder, String name, String value) {
        if (!active() || builder == null || name == null || name.length() > 32) return;
        for (Entry e : entries) {
            if (!same(e.builder, builder) || !"pending".equals(e.terminal)) continue;
            if ("Cookie".equalsIgnoreCase(name)) {
                e.cookieObservations = increment(e.cookieObservations);
                e.cookie = value != null && !value.isEmpty() ? "nonempty" : "empty";
            } else if ("User-Agent".equalsIgnoreCase(name)) {
                e.uaObservations = increment(e.uaObservations);
                e.ua = classifyUserAgent(value);
            }
            return;
        }
    }

    public synchronized void onResponse(Object callback, int status, String mime) {
        if (!active()) return;
        Entry e = liveEntry(callback);
        if (e == null || e.redirects != 0) return;
        e.responseObservations = increment(e.responseObservations);
        e.status = status >= 100 && status <= 599 ? status : -1;
        e.mime = classifyMime(mime);
    }

    /** Bounded inspection of platform response headers. Unknown keys/values are never retained.
     * Content-Type and numeric Retry-After use last observed values, including duplicates.
     * HTTP dates are deliberately not parsed; values above one day are unknown, not clamped.
     */
    public synchronized void onResponseHeaders(Object callback, int status, Map<?, ?> headers) {
        if (!active() || liveEntry(callback) == null) return;
        String mime = "unobserved";
        int retry = -1, mimeCount = 0, retryCount = 0, fields = 0;
        boolean truncated = false;
        if (headers != null) {
            for (Map.Entry<?, ?> header : headers.entrySet()) {
                if (++fields > 64) { truncated = true; break; }
                Object key = header.getKey();
                if (!(key instanceof String) || ((String) key).length() > 32) continue;
                boolean contentType = "Content-Type".equalsIgnoreCase((String) key);
                boolean retryAfter = "Retry-After".equalsIgnoreCase((String) key);
                if (!contentType && !retryAfter) continue;
                Object raw = header.getValue();
                List<?> list = raw instanceof List<?> ? (List<?>) raw : null;
                int count = list == null ? 1 : list.size();
                if (count > 8) truncated = true;
                for (int i = 0; i < Math.min(8, count); i++) {
                    Object item = list == null ? raw : list.get(i);
                    String value = item instanceof String ? (String) item : null;
                    if (contentType) { mime = classifyMime(value); mimeCount++; }
                    else { retry = retrySeconds(value); retryCount++; }
                }
            }
        }
        Entry e = liveEntry(callback);
        if (!active() || e == null || e.redirects != 0) return;
        e.status = status >= 100 && status <= 599 ? status : -1;
        e.responseObservations = increment(e.responseObservations);
        e.mime = truncated ? "unknown" : mime;
        e.retryAfterSeconds = truncated ? -1 : retry;
        e.mimeObservations = mimeCount;
        e.retryObservations = retryCount;
        e.headerScanTruncated = truncated;
    }

    private static int retrySeconds(String value) {
        if (value == null || value.length() == 0 || value.length() > 5) return -1;
        int number = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return -1;
            number = number * 10 + c - '0';
        }
        return number <= 86400 ? number : -1;
    }
    /** Inject immediately before the actual read invocation, not after it. */
    public synchronized void beforeRead(Object request, ByteBuffer buffer) {
        if (!active() || request == null || buffer == null || !hasLiveEntries()) return;
        boolean duplicate = false;
        for (int i = baselines.size() - 1; i >= 0; i--) {
            Baseline b = baselines.get(i);
            if (b.request.get() == null || b.buffer.get() == null) baselines.remove(i);
            else if (same(b.request, request) && same(b.buffer, buffer)) {
                duplicate = true;
                baselines.remove(i);
            }
        }
        if (baselines.size() == MAX_BASELINES) baselines.remove(0);
        baselines.add(new Baseline(request, buffer, duplicate));
    }

    /** Inject at callback entry, before the host flips, clears or otherwise mutates buffer. */
    public synchronized void onRead(Object callback, Object request, ByteBuffer buffer) {
        if (!active()) return;
        Baseline baseline = null;
        for (int i = baselines.size() - 1; i >= 0; i--) {
            Baseline b = baselines.get(i);
            if (same(b.request, request) && same(b.buffer, buffer)) {
                baseline = b;
                baselines.remove(i);
                break;
            }
        }
        Entry e = liveEntry(callback);
        if (e == null || e.redirects != 0) return;
        e.readObservations = increment(e.readObservations);
        if (request == null || buffer == null) { e.bytesUnknown = true; return; }
        if (e.request != null && !same(e.request, request)) {
            e.bytesUnknown = true;
            return;
        }
        for (Entry other : entries) {
            if (other != e && same(other.request, request)) {
                other.bytesUnknown = true;
                e.bytesUnknown = true;
                return;
            }
        }
        e.request = new WeakReference<Object>(request);
        if (baseline == null || baseline.ambiguous || buffer.limit() != baseline.limit) {
            e.bytesUnknown = true;
            return;
        }
        int delta = buffer.position() - baseline.position;
        if (delta < 0 || delta > baseline.limit - baseline.position) {
            e.bytesUnknown = true;
            return;
        }
        if (Long.MAX_VALUE - e.knownBytes < delta) {
            e.bytesUnknown = true;
            return;
        }
        e.knownBytes += delta;
    }

    public synchronized void onTerminal(Object callback, int kind) {
        if (!active()) return;
        Entry e = liveEntry(callback);
        if (e == null) return; // Duplicate terminals must not modify the first outcome.
        e.terminal = kind == 0 ? "success" : kind == 1 ? "failed" : kind == 2 ? "cancelled" : "incomplete";
        e.endMs = elapsed() / 1000000L;
        release(e);
    }

    public synchronized void onRedirect(Object callback) {
        if (!active()) return;
        Entry e = liveEntry(callback);
        if (e == null) return;
        e.redirects = increment(e.redirects);
        e.bytesUnknown = true;
        e.builder = null;
        // No redirect URL is supplied: subsequent responses cannot be verified as timedtext.
        removeBaselines(e.request);
        e.request = null;
    }

    private void removeBaselines(WeakReference<Object> request) {
        if (request == null) return;
        Object key = request.get();
        for (int i = baselines.size() - 1; i >= 0; i--) {
            if (key == null || same(baselines.get(i).request, key)) baselines.remove(i);
        }
    }

    private void release(Entry e) {
        removeBaselines(e.request);
        e.callback = null;
        e.builder = null;
        e.request = null;
    }

    private Entry callbackEntry(Object callback) {
        if (callback == null) return null;
        for (Entry e : entries) if (same(e.callback, callback)) return e;
        return null;
    }

    private Entry liveEntry(Object callback) {
        Entry e = callbackEntry(callback);
        return e != null && !e.ambiguousCallback && "pending".equals(e.terminal) ? e : null;
    }

    private boolean hasLiveEntries() {
        for (Entry e : entries) if ("pending".equals(e.terminal) && e.redirects == 0) return true;
        return false;
    }

    private static boolean same(WeakReference<?> ref, Object object) {
        return object != null && ref != null && ref.get() == object;
    }

    private static int increment(int n) { return n < MAX_HEADERS ? n + 1 : n; }

    /** All strings appended here are fixed labels, finite classifications or random session IDs. */
    public synchronized String report() {
        active();
        StringBuilder out = new StringBuilder(4096);
        out.append("字幕诊断（仅内存）\nrecording=").append(recording ? "on" : "off")
                .append(" session=").append(session).append(" stop_reason=").append(stopReason)
                .append("\ncapacity=").append(MAX_REQUESTS).append(" retained=").append(entries.size())
                .append(" evicted=").append(evicted).append(" expires_after_minutes=15\n")
                .append("时间为本次记录开始后的相对毫秒，不记录日历时间；序号仅在随机会话内有效。\n")
                .append("仅匹配 HTTPS www.youtube.com / m.youtube.com 的 /api/timedtext。\n")
                .append("不保存 URL、视频标识/标题、完整查询、Cookie/令牌值、正文/片段或异常。\n")
                .append("参数仅保留有限白名单分类；other=未识别，ambiguous=重复参数。\n")
                .append("Cookie 与 UA 是构建器钩子的最后一次观察（计数可含重复，最多1000000），不证明实际发出。\n")
                .append("pot_present 仅表示参数出现，不代表令牌有效；不解析字幕，不统计字幕条数。\n")
                .append("known_bytes 仅累计配对读取的 position 增量，不使用 Content-Length，不表示网络线速字节。\n")
                .append("bytes_complete=false 表示缺基线/位置异常/重定向等；零读取不证明响应为空。\n")
                .append("重定向后不再统计响应和读取（目标未知）；停止/到期标记未结束请求 incomplete。\n")
                .append("响应头最多检查64项，每项最多8值；Retry-After只接受0..86400整数秒，不解析日期。\n")
                .append("清空同时停止；新开始替换旧报告；仅主动点击复制才写入系统剪贴板。\n\n");
        for (Entry e : entries) {
            out.append("request=").append(e.sequence).append(" start_ms=").append(e.startMs)
                    .append(" end_ms=").append(e.endMs < 0 ? "pending" : Long.toString(e.endMs))
                    .append(" terminal=").append(e.terminal).append("\n  lang=").append(e.url.lang)
                    .append(" tlang=").append(e.url.tlang).append(" fmt=").append(e.url.fmt)
                    .append(" c=").append(e.url.client).append(" pot_present=").append(e.url.pot)
                    .append("\n  status=").append(e.status < 0 ? "unknown" : Integer.toString(e.status))
                    .append(" mime=").append(e.mime).append(" response_observations=").append(e.responseObservations)
                    .append(" retry_after_seconds=").append(e.retryAfterSeconds < 0 ? "unknown" : Integer.toString(e.retryAfterSeconds))
                    .append(" mime_observations=").append(e.mimeObservations).append(" retry_observations=").append(e.retryObservations)
                    .append(" header_scan_truncated=").append(e.headerScanTruncated)
                    .append(" redirects=").append(e.redirects).append(" ambiguous_callback=").append(e.ambiguousCallback)
                    .append("\n  cookie_last=").append(e.cookie).append(" cookie_observations=").append(e.cookieObservations)
                    .append(" ua_last=").append(e.ua).append(" ua_observations=").append(e.uaObservations)
                    .append("\n  known_bytes=").append(e.knownBytes).append(" read_observations=").append(e.readObservations)
                    .append(" bytes_complete=").append(!e.bytesUnknown && e.readObservations > 0 && "success".equals(e.terminal)).append('\n');
        }
        return out.toString();
    }

    // Package-private counts let standalone tests verify bounds without exposing host references.
    synchronized int baselineCount() { active(); return baselines.size(); }
    synchronized int identityCount() {
        active();
        int count = baselines.size() * 2;
        for (Entry e : entries) {
            if (e.callback != null) count++;
            if (e.builder != null) count++;
            if (e.request != null) count++;
        }
        return count;
    }

    private static final class Entry {
        final long sequence, startMs;
        final SafeUrl url;
        WeakReference<Object> callback, builder, request;
        long endMs = -1, knownBytes;
        int status = -1, redirects, cookieObservations, uaObservations, responseObservations, readObservations;
        int retryAfterSeconds = -1, mimeObservations, retryObservations;
        boolean headerScanTruncated;
        boolean bytesUnknown, ambiguousCallback;
        String terminal = "pending", cookie = "unobserved", ua = "unobserved", mime = "unobserved";
        Entry(long sequence, long startMs, Object callback, SafeUrl url) {
            this.sequence = sequence;
            this.startMs = startMs;
            this.callback = new WeakReference<Object>(callback);
            this.url = url;
        }
    }

    private static final class Baseline {
        final WeakReference<Object> request;
        final WeakReference<ByteBuffer> buffer;
        final int position, limit;
        final boolean ambiguous;
        Baseline(Object request, ByteBuffer buffer, boolean ambiguous) {
            this.request = new WeakReference<Object>(request);
            this.buffer = new WeakReference<ByteBuffer>(buffer);
            this.position = buffer.position();
            this.limit = buffer.limit();
            this.ambiguous = ambiguous;
        }
    }

    private static final class SafeUrl {
        String lang = "absent", tlang = "absent", fmt = "absent", client = "absent";
        boolean pot;
    }

    private static SafeUrl parse(String raw) {
        if (raw == null || raw.length() > MAX_URL) return null;
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !("www.youtube.com".equalsIgnoreCase(uri.getRawAuthority())
                    || "m.youtube.com".equalsIgnoreCase(uri.getRawAuthority()))
                    || !"/api/timedtext".equals(uri.getRawPath()) || uri.getRawFragment() != null) return null;
            SafeUrl result = new SafeUrl();
            String query = uri.getRawQuery();
            if (query == null) return result;
            int start = 0, count = 0;
            while (start <= query.length()) {
                if (++count > 128) return null;
                int end = query.indexOf('&', start);
                if (end < 0) end = query.length();
                int equal = query.indexOf('=', start);
                if (equal < 0 || equal > end) equal = end;
                String key = decode(query.substring(start, equal), 32);
                if ("pot".equals(key)) result.pot = true; // NEVER decode or retain its value.
                else if ("lang".equals(key) || "tlang".equals(key) || "fmt".equals(key) || "c".equals(key)) {
                    String value = decode(query.substring(Math.min(equal + 1, end), end), 96);
                    if ("lang".equals(key)) result.lang = next(result.lang, language(value));
                    else if ("tlang".equals(key)) result.tlang = next(result.tlang, language(value));
                    else if ("fmt".equals(key)) result.fmt = next(result.fmt, allowed(value, "json3 srv1 srv2 srv3 ttml vtt srt"));
                    else result.client = next(result.client, allowed(value,
                            "WEB MWEB ANDROID IOS TVHTML5 WEB_EMBEDDED_PLAYER ANDROID_VR WEB_REMIX ANDROID_MUSIC IOS_MUSIC"));
                }
                if (end == query.length()) break;
                start = end + 1;
            }
            return result;
        } catch (Exception ignored) { return null; }
    }

    private static String decode(String value, int max) throws Exception {
        if (value.length() > max) return "";
        return URLDecoder.decode(value, "UTF-8");
    }
    private static String next(String previous, String value) {
        return "absent".equals(previous) ? value : "ambiguous";
    }
    private static String allowed(String value, String choices) {
        if (value == null || value.isEmpty() || value.length() > 32) return "other";
        // Return a trusted constant, never a caller-supplied string or truncated substring.
        for (String choice : choices.split(" ")) if (choice.equals(value)) return choice;
        return "other";
    }
    private static String language(String value) {
        if (value == null || value.length() > 16) return "other";
        String lower = value.toLowerCase(Locale.ROOT);
        String common = allowed(lower, "af am ar as az be bg bn bo bs ca cs cy da de el en eo es et eu fa fi fil fo fr ga gl gu ha he hi hr hu hy id ig is it iw ja jv ka kk km kn ko ku ky la lb lo lt lv mg mi mk ml mn mr ms mt my ne nl no or pa pl ps pt ro ru sd si sk sl sm sn so sq sr st su sv sw ta te tg th tk tr uk ur uz vi xh yi yo zu");
        if (!"other".equals(common)) return common;
        return allowed(lower, "zh zh-hans zh-hant zh-cn zh-tw zh-hk en-us en-gb es-419 es-es es-mx pt-br pt-pt fr-ca sr-latn");
    }
    private static String classifyUserAgent(String value) {
        if (value == null || value.length() > 2048) return "other";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("android")) return "android";
        if (lower.contains("windows nt") || lower.contains("macintosh") || lower.contains("x11")) return "desktop";
        return "other";
    }
    private static String classifyMime(String value) {
        if (value == null || value.length() > 128) return "other";
        int separator = value.indexOf(';');
        String type = (separator < 0 ? value : value.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
        return allowed(type, "application/json text/xml application/xml text/vtt application/ttml+xml text/plain application/octet-stream");
    }
}
