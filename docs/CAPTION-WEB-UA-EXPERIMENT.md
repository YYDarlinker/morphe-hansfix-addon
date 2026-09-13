# Caption Web UA experiment

This patch is an **A/B diagnostic experiment**, not a claimed fix.

## Hypothesis

Morphe's official Caption Cookies patch injects both the configured caption Cookie and a desktop Chrome `User-Agent` into YouTube `api/timedtext` requests. On YouTube 21.13.164, the native request path can write an Android `User-Agent` later. The last header value therefore becomes Android even though the request carries browser-derived caption cookies.

The experiment tests whether keeping the final User-Agent aligned with Morphe's desktop Chrome identity reduces the translated-caption (`tlang`) HTTP 429 failures seen after repeated video switching.

## What the patch changes

Patch name: `Caption Web UA experiment`

Supported target for this experiment: YouTube `21.13.164`.

The patch locates the same native Cronet caption request builder used by `Caption request diagnostics`. It observes local header-writer methods on that request path and inserts a value selector immediately before each `UrlRequest.Builder.addHeader(...)` dispatch.

When Morphe's `CaptionCookiesPatch.getRequireCookies()` is true:

- `User-Agent` values are replaced with Morphe's own desktop Chrome User-Agent returned by `CaptionCookiesPatch.getUserAgent()`;
- `Cookie` is not changed;
- all other headers are passed through unchanged;
- request URL, `tlang`, body, response handling, retries and PoToken behavior are not changed.

When `getRequireCookies()` is false, every header value is passed through unchanged.

The implementation also recognizes the diagnostics bridge form of `addHeader`, so it is intended to coexist with `Caption request diagnostics` regardless of finalizer order.

## Required build selections

For the intended A/B test, select:

1. official Morphe `Captions`;
2. `Caption request diagnostics`;
3. `Caption Web UA experiment`;
4. HansFix only if you normally need the Simplified-Chinese mapping; it is not required by this experiment.

In YouTube settings, enable Morphe's caption-cookie option and use the same caption cookies that reproduce the baseline behavior.

## Expected diagnostic signature

Baseline without this experiment typically showed two User-Agent observations with the final one classified as Android:

```text
ua_observations=2
ua_last=android
```

With the experiment active, the useful confirmation is:

```text
ua_observations=2
ua_last=chrome
```

The exact observation count may change if upstream Morphe changes its header implementation. The critical result is that the last observed User-Agent on translated timedtext requests is Chrome.

## Test protocol

Use the same network, account/session, caption cookies and video-switching pattern as the baseline run.

1. Start diagnostics before selecting translated subtitles.
2. Open a video with source captions and choose Chinese (Simplified) auto-translation.
3. Switch among several videos in the same way that previously triggered 429.
4. After failure or after a comparable number of switches, copy the diagnostics log.
5. Compare translated `tlang=zh-hans` requests against the baseline.

Primary outcomes:

- **`ua_last=chrome` and translated requests remain 200 materially longer or stop entering the 429 state:** supports the mixed Cookie/UA identity hypothesis.
- **`ua_last=chrome` but translated requests still enter the same 429 state:** substantially weakens User-Agent mismatch as the main cause; next work should focus on visitor/account/attestation identity and translated-timedtext anti-abuse state.
- **`ua_last=android`:** the experiment did not reach the final native UA write and the hook must be re-audited before drawing any server-side conclusion.

Original captions (`tlang=absent`) should remain a control. A regression in original-caption loading is a reason to stop the test and remove the experiment patch.

## Scope and limitations

This patch deliberately does **not**:

- alter or refresh caption cookies;
- add authentication headers;
- add or mint PoTokens;
- change `tlang` or caption URLs;
- suppress duplicate timedtext requests;
- add retry/backoff logic;
- claim that HTTP 429 is conventional quota exhaustion.

The experiment is intentionally restricted to YouTube 21.13.164 until the behavior is verified on-device.
