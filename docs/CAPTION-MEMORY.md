# Remember subtitle language

## Contract

Independent patch in the existing YYDarlinker HansFix Addon source. When selected, it is always active: no runtime feature switch, no per-channel settings. Remember the last manually selected subtitle language globally and enable captions for each new video. Manual CC off affects the current video; it must not erase the language preference. Before a first manual selection, use YouTube's default caption choice. An unavailable language must not overwrite the saved preference. No caption URL, token, track ID or object may be persisted across videos.

HansFix and subtitle memory remain separately selectable. A shared internal extension dependency merges only this addon's runtime once; it does not copy official extensions or establish a cross-bundle Kotlin dependency. The same custom source is used alongside the official source in one Manager expert-mode operation.

## Compatibility policy

No exact version-number gate. Check Google YouTube package/signatures and actual bytecode structures; refuse ambiguous or missing hooks. Allow users to try newer originals when the structures still match. Official patch compatibility restrictions still apply independently. Current implementation target: YouTube 21.13.164 / 1561063732, official Morphe Patches 1.42.0, Manager 1.29.0 / Patcher 1.12.0. New behavior requires user device acceptance; old HansFix acceptance does not prove it.

## Validation scope

Build the bundle, run small policy tests where useful, and perform one final same-session official + both-addon integration check. Do not generate full DEX inventory reports or repeat broad combinations by default. User device checks: select translated Simplified Chinese, open a second video, restart the app, temporarily disable CC, and check an unsubtitled video. Confirm language, automatic enable and continued HansFix behavior.

## Native implementation

On the inspected 21.13.164 input, the preference-writing selector is found by its `setSubtitleTrack name:%s languageCode:%s ...` log, not by a hardcoded obfuscated class. Only the native enum origin `PREFERRED_TRACK` records memory; default and composite-video changes do not overwrite the user's language. Null/OFF and menu placeholders are ignored.

The patch resolves the current manager's model, native list, translated list, language and URL through unique structural matches and generated typed DEX accessors. On a model-ready initialization path it calls the current default selector, then enters YouTube's existing enabled event path if a track exists. The default selector first tries the remembered language in native and then translated tracks, otherwise continues its original logic. This preserves the original caption event and listener notification behavior without polling or delayed clicks.

Only actual HansFix URL mapping makes translated zh-Hant count as zh-Hans. With HansFix unselected, that mapping is inactive. Starting with the 2026-09-11 update, selected HansFix is independent of official Cookie settings. No global track field is rewritten.
