# Auto-translated caption HTTP 429 research

Status: experimental research branch only. This is not a production fix.

## Problem reproduced

Repeated phone traces on YouTube 21.13.164 show a very specific failure mode:

- `/api/timedtext` requests with `tlang=...` oscillate between HTTP 200 and HTTP 429.
- Original-language caption requests (`tlang` absent) continue to return HTTP 200 while translations fail.
- The same exact request URL can return 429, later 200, and later 429 again.
- Failures persist across videos.
- The existing Morphe caption Cookie is still observed on failing requests.
- `Retry-After` is absent in captured 429 responses.
- The issue reproduces with and without HansFix, and with and without Subtitle Memory.

This rules out HansFix/Subtitle Memory as required causes and does not fit a simple broken URL or permanently expired-cookie model.

## Upstream evidence

### yt-dlp

`yt-dlp/yt-dlp#13831` documents the same translated-caption-only HTTP 429 pattern. Original/manual captions are not affected in the same way. A draft yt-dlp fix (`#15709`) tracks `VISITOR_INFO1_LIVE` session age and experimentally delays translated captions, but remains a draft and does not explain the full 200/429 oscillation seen on a long-lived session.

### ReVanced Extended / Morphe

ReVanced Extended introduced `Set Transcript Cookies` after observing Android translated Timed Text requests returning 429 and browser-style Cookie injection restoring responses. Morphe later ported this as `Set caption cookies`.

Morphe's built-in `Get caption cookies` currently fetches anonymous cookies from `https://www.youtube.com/sw.js` and keeps only:

- `YSC`
- `VISITOR_INFO1_LIVE`
- `VISITOR_PRIVACY_METADATA`
- `__Secure-ROLLOUT_TOKEN`

These are useful but are not a logged-in browser identity.

### PipePipe

Current PipePipe code contains the explicit comment `auto_translated subtitles have risk control`. For `tlang=` subtitle downloads it adds logged-in headers, including the full YouTube Cookie, a browser-style `SAPISIDHASH` Authorization header, `X-Origin: https://www.youtube.com`, and `DNT: 1`.

This is the closest currently deployed Android-client behavior found that targets translated-caption risk control specifically.

## Hypothesis under test

The intermittent Morphe behavior may be caused by translated Timed Text accepting anonymous caption visitor cookies only probabilistically / under a stricter server-side trust state. A full authenticated YouTube web session may provide a more stable identity.

This hypothesis is narrower and cheaper to test than immediately porting a BotGuard / PO-token generator.

## Research-branch behavior

No extra publishable patch root is added. The existing `Caption request diagnostics` patch owns the experiment on `research/caption-429-auth` only.

With Morphe's normal anonymous caption Cookie, behavior remains diagnostic-only because neither `SAPISID` nor `__Secure-3PAPISID` exists and no Authorization header can be generated.

If the already-configured Morphe caption Cookie contains a logged-in YouTube identity, translated URLs containing `tlang=` additionally receive:

- `Authorization: SAPISIDHASH ...`
- `X-Origin: https://www.youtube.com`
- `DNT: 1`

The hash is derived as:

`SAPISIDHASH <unix_seconds>_<sha1(unix_seconds + " " + sapisid + " https://www.youtube.com")>`

The hook is request-local: the exact URL and Cronet builder from the same `newUrlRequestBuilder` call are passed together. It does not introduce a new global URL/request association.

Any missing auth cookie or runtime failure is fail-closed and the ordinary Morphe caption path continues.

The diagnostic report adds only a non-secret global state line:

- `caption_auth_state=not_used`
- `caption_auth_state=missing_auth_cookie`
- `caption_auth_state=authorization_ready`
- `caption_auth_state=error`

No credential or authorization value is retained.

## Security warning

A full logged-in YouTube/Google Cookie header is an account credential. Treat it like a password/session token.

For research testing:

- strongly prefer a secondary/test Google account;
- never paste the Cookie into GitHub issues, diagnostics, chat logs, screenshots, or public build logs;
- do not enable general debug logging while copying credentials around unless you have audited what is logged;
- revoke the test session after the experiment if desired.

The diagnostics patch intentionally does not store Cookie or Authorization values.

## Controlled A/B plan

### Baseline A — anonymous Morphe caption cookies

1. Build the research branch with official Captions + `Caption request diagnostics`.
2. Enable `Set caption cookies` and use Morphe's normal `Get caption cookies` result.
3. Start diagnostics and reproduce the normal video-switching pattern.
4. Confirm the report says `caption_auth_state=missing_auth_cookie` after translated requests.
5. Capture the 200/429 sequence.

### Experiment B — authenticated browser cookies

1. Use a logged-in browser session (preferably a test account) and obtain the full request `Cookie` header for `www.youtube.com` without publishing it anywhere.
2. Paste that full Cookie string into Morphe's existing `Caption cookies` text preference.
3. Keep `Set caption cookies` enabled.
4. Restart YouTube because current Morphe caches the configured caption Cookie in a `static final` field at process initialization.
5. Use the same research build with official Captions + `Caption request diagnostics`.
6. Start diagnostics and confirm `caption_auth_state=authorization_ready` after a translated request.
7. Repeat the same switching pattern for substantially longer than the baseline failure interval.

Primary endpoint:

- whether `tlang` requests remain HTTP 200 instead of entering the observed 200/429 oscillation.

Secondary endpoints:

- whether original-language captions remain unaffected;
- whether any new non-429 error appears;
- whether behavior changes as the browser-auth session ages.

## Decision rule

If authenticated cookies eliminate or dramatically reduce translated-caption 429s across repeated sessions, the next engineering step is to build a safe in-app authenticated-cookie/session acquisition path and remove the manual paste requirement.

If authenticated cookies do not materially improve stability, do not keep adding ad-hoc headers. Move to the next research stage: caption-only WEB/MWEB Timed Text with correctly bound attestation/PO token, without replacing the Android player response pipeline.

## Why caption-only WEB/MWEB remains plausible

Morphe upstream research shows that replacing the Android player with a Web client is structurally difficult because Web player responses are JSON while Android YouTube's playback pipeline expects proto. Timed Text is different: it is already fetched as a separate text/XML/JSON resource. Therefore a caption-only Web/MWeb request path may avoid the player-response JSON-to-proto obstacle entirely. This remains a second-stage hypothesis, not yet implemented here.
