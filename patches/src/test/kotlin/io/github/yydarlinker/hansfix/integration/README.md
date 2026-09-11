# Real dual-bundle JVM integration harness

## Scope / evidence boundary

This is a test-runtime adapter, not a production patch and not an Android Manager emulator.
It loads the **real release mpp** and the **separately built addon mpp**. There are no synthetic
patches, copied official patch objects, production-class fallback or merged bundle loaders.
Manager Android uses `loadPatchesFromDex`; this JVM adapter deliberately uses the corresponding
`loadPatchesFromJar` for each bundle separately, preserving the per-source loader boundary.
A successful `compileTestKotlin` proves API compilation only. Integration acceptance additionally
requires a normal mode to finish and emit `UNSIGNED_APK_VERIFIED`, and separate content review of
the output APK by the coordinating agent. No playback, installation or device runtime proof is claimed.

## Fixed sources inspected

- Patcher: `C:/Work/Morphe/source-research/patcher`, tag `v1.12.0`,
  commit `ac0d688eaacb7ece80b65ebf719b252f69455783`.
  - `src/main/kotlin/app/morphe/patcher/patch/Patch.kt`: `loadPatchesFromJar(Set<File>)`,
    `PatchLoader.byPatchesFile`, parent-first `URLClassLoader`, `Patch.default`,
    `dependencies`, `compatibility`, `availability`, `PatchResult.patch/exception`.
  - `Patcher.kt`: constructor `Patcher(PatcherConfig)`, `+= Set<Patch<*>>`, `invoke(): Flow`,
    `get(): PatcherResult`. Dependencies execute recursively. Root execution is sorted by name;
    finalize runs in reverse execution order. `get()` compiles dex and resources.
  - `PatcherConfig.kt`: `apkFile`, `temporaryFilesPath`, `fileWorkspacePath`;
    default resource engine is Arsclib and bytecode mode is `STRIP_FAST`.
  - `apk/ApkUtils.kt`: `PatcherResult.applyTo(File)` requires a **copy of the original APK**;
    the result is a delta, not a standalone archive. Called before closing Patcher.
- Manager research: current task's `work/manager-source-research/source/MorpheApp-morphe-manager-1fd754e`.
  `revision.json` records stable `v1.29.0` at `c880b625c06ed4d31ca872141d55355c8d334e4f` and
  researched commit `1fd754eb690d967053fccdccb44e075e9aeb72f9` (only app-release.json differs).
  - `app/src/main/java/app/morphe/manager/patcher/patch/PatchBundle.kt`: one loader per bundle.
  - `patcher/runtime/CoroutineRuntime.kt`: select per bundle, flatten selected real objects.
  - `patcher/Session.kt`: one Patcher instance, `+=`, collect, `get`, input copy, `applyTo`.
  - `patcher/patch/PatchInfo.kt`: compatible version selection and default availability.
- Official source preparation: `C:/Work/Morphe/hansfix-source-prep/patches/src/main/kotlin/app/morphe/patches/youtube/layout/captions/CaptionsPatch.kt`.
  `captionsPatch` has exact name `Captions` and `dependsOn(autoCaptionsPatch,
  captionCookiesPatch, transcriptPatch)`. The harness does not compile this source or import
  its symbols; `selectCaptions` selects the root from the supplied release and Patcher resolves
  its actual transitive dependencies. Named/unnamed closure entries and counts are logged.

## Modes

| Mode | Bundle loading / selected input order | Official root selection | Expected result |
|---|---|---|---|
| minimal | official, addon | exact `Captions` plus its actual dependencies | unsigned APK |
| defaults | official, addon | compatible default official patches, including Captions | unsigned APK |
| reverse | addon, official | same as minimal | unsigned APK |
| addon-only | addon only (official file only hash-checked) | none | unsigned APK; no official extension loaded |
| official-only | official, addon loaded; only official selected | exact `Captions` plus dependencies | baseline unsigned APK |
| official-defaults | official, addon loaded; only official selected | same compatible defaults as defaults | baseline unsigned APK |

`official-only` is the baseline for `minimal`; `official-defaults` is the baseline for `defaults`.
Both baselines load the addon for the same loader/shadowing checks, but never select it or execute
its patch/extension merge. They use the identical get/copy/applyTo/unsigned-output validation path.
Compare output semantics separately; successful packaging alone does not prove a bounded diff.

`reverse` changes **loader and selected-input order**, not Patcher's internal name sort.
Defaults uses STANDARD installer availability (`ENABLED` / `REQUIRED`, falling back to
`patch.default`) and the APK's primary ABI, with stable compatibility for YouTube `21.07.247`.
Version-code constraints are checked against the APK metadata when declared. No patch options
are overridden. In particular, no caption cookies, auth values or URLs are supplied.

Since 2026-09-11, addon-only is a positive HansFix independence check: HansFix must apply and emit
an unsigned APK without loading official patches. Every patch error fails the session. This replaces
the first release's negative official-dependency test; no expected-failure override is accepted.
Subtitle memory is selected in the official combined modes, not in addon-only.

## Compile (one Gradle process at a time)

```powershell
Set-Location 'C:\Work\Morphe\hansfix-addon'
$env:JAVA_HOME = 'C:\Work\Morphe\toolchains\jdk-21.0.12.1+1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :patches:compileTestKotlin --no-daemon --no-parallel --max-workers=1 --console=plain
```

The existing Gradle plugin may resolve main/extension compilation dependencies while compiling
`testClasses`; the harness adds no production build, artifact or publishing task changes.
The coordinator separately authorized the about name/description update on 2026-09-07.
All extra runtime dependencies are `testImplementation`, with Patcher strictly pinned to 1.12.0.
The opt-in JavaExec uses `sourceSets.test.runtimeClasspath - sourceSets.main.output`: exclusion
is essential because the real Patcher JAR loader is parent-first. The supplied mpp must not be
added to the JVM classpath. The harness also fails if bundle patch classes are parent-visible.

## Run after addon build is available and coordinated

Do not run modes in parallel. Use a fresh/empty mode directory. Never delete an existing run
implicitly; preserve it for review and choose a new empty output directory if rerunning.
The shared parent directory contains `.hansfix-integration.lock` to reject concurrent sessions.
Each JavaExec JVM has a maximum heap of 6 GiB and executes exactly one session.

```powershell
# Replace this placeholder with the actual separately built addon .mpp. Never pass a class dir.
$addonMpp = '<absolute path to built addon.mpp>'
$mode = 'minimal' # also official-only, defaults, official-defaults, reverse, addon-only; strictly sequential
.\gradlew.bat :patches:runIntegration --no-daemon --no-parallel --max-workers=1 --console=plain `
  "-Pintegration.mode=$mode" `
  '-Pintegration.input=C:\Work\Morphe\addon-validation\input-original.apk' `
  '-Pintegration.official=C:\Work\Morphe\addon-validation\official\patches-1.41.0.mpp' `
  "-Pintegration.addon=$addonMpp" `
  "-Pintegration.outputDir=C:\Work\Morphe\addon-validation\runs\$mode"
```

All run outputs, scratch directories and the allowlisted `integration.log` stay outside the
repository. The input APK is read-only and is rehashed before success. Defaults for input SHA-256
and official SHA-256 are the coordinating agent's verified fixtures:

- Input: `afed0724c7cbdec08626573f5e0c405db76e11fe9bfdafbc3884690a766666db`.
- Official 1.41.0: `679a86aaaa50572e9c0852fd990e937edfd9b616ecf5d350048458598ec785df`.

Both digests were independently read and matched on 2026-09-07 before harness compilation.
For an intentionally changed fixture, optional `integration.inputSha` and
`integration.officialSha` must contain the newly verified 64-digit digests. An APK from a different
package/version is still rejected. Inputs/outputs cannot overwrite one another.

JavaExec `--args` is an alternative to Gradle properties (do not mix duplicate arguments):
`--mode`, `--input`, `--official`, `--addon`, `--output-dir`,
`--input-sha`, `--official-sha`, each followed by one value. For direct `java -cp` execution,
use the same test-only classpath and also pass `--repo C:\Work\Morphe\hansfix-addon`.

## APK output and privacy

1. Collect every selected patch result; any unexpected error blocks packaging.
2. Call `get()` inside the Patcher lifetime, copy input to run scratch, apply result to the copy.
3. Build a **new** unsigned ZIP with apkzlib, excluding v1 signature entries. A new ZIP does
   not retain the original v2/v3 APK signing block. Realign stored libraries to 4096 bytes and
   other entries to 4 bytes, matching Patcher 1.12.0's packaging settings.
4. Validate duplicate entries, full-entry CRC/size, manifest/resources/classes presence,
   dex headers, absence of v1 entries and v2/v3 signing-block footer. Only then move it to
   `<output-dir>/unsigned.apk` and report `UNSIGNED_APK_VERIFIED`.
5. Keep scratch locally for review; there is no cleanup of prior runs or caller files.

JUL is reset and direct stdout/stderr are suppressed before either bundle is loaded. Only patch
names, fixed statuses, numeric counts and local output file paths are emitted to the console/log.
For the selected addon result only, `SafeAddonDiagnostics.kt` permits a reviewed `HansFix:`
gate reason: exact source-defined text/roles/helpers and numeric counts, printable ASCII, at most
300 characters, no HTTP(S) URL or value assignment. Unknown messages are withheld; no truncation.
Only the bounded cause chain is inspected; wrapper stack traces and raw exceptions stay suppressed.
Other exception text, stack traces, APK contents, request/response bodies or sensitive URLs are
logged. No cookies, private keys or screenshots are read. No APK upload, signing, installation, Git push
or GitHub artifact operation exists in this harness. Gradle's own build diagnostics are separate
from the harness log and should not be enabled with `--debug`, `--info` or build scans.
