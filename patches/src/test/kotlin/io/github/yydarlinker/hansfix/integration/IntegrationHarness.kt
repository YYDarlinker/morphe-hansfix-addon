package io.github.yydarlinker.hansfix.integration

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.patch.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.jar.JarFile
import java.util.logging.LogManager
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private const val ADDON = "HansFix - Simplified Chinese captions"
private const val YOUTUBE = "com.google.android.youtube"
private const val VERSION = "21.07.247"
private const val INPUT_SHA = "afed0724c7cbdec08626573f5e0c405db76e11fe9bfdafbc3884690a766666db"
private const val OFFICIAL_SHA = "679a86aaaa50572e9c0852fd990e937edfd9b616ecf5d350048458598ec785df"

/** Only reviewed addon gate reasons may supplement statuses; never raw exceptions or library logs. */
private class Audit(private val console: PrintStream) : AutoCloseable {
    private var file: PrintStream? = null
    fun attach(path: File) { file = PrintStream(Files.newOutputStream(path.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW), true, Charsets.UTF_8) }
    fun status(value: String) = emit("status=$value")
    fun count(value: Int) = emit("count=$value")
    fun addonGate(error: Throwable) {
        val reason = safeAddonGateReason(error)
        if (reason == null) status("ADDON_GATE_REASON_WITHHELD") else emit("reason=$reason")
    }
    fun patch(patch: Patch<*>, status: String) {
        // Permit patch names only, never their descriptions/options/exception messages.
        val name = patch.name?.take(160)?.replace(Regex("[^\\p{L}\\p{N} ._()+'-]"), "_") ?: "Unnamed dependency"
        emit("patch=$name status=$status")
    }
    fun output(path: File) = emit("output=${path.canonicalPath}")
    private fun emit(line: String) { console.println(line); file?.println(line) }
    override fun close() { file?.close() }
}

private fun gate(condition: Boolean, status: String) { if (!condition) throw Gate(status) }
private class Gate(val status: String) : RuntimeException()

private data class Arguments(
    val mode: String, val input: File, val official: File, val addon: File,
    val output: File, val repo: File,
    val inputSha: String, val officialSha: String,
) {
    companion object {
        fun parse(args: Array<String>): Arguments {
            val values = linkedMapOf<String, String>()
            gate(args.size % 2 == 0, "FAILED_ARGUMENTS")
            args.toList().chunked(2).forEach { (key, value) ->
                gate(key.startsWith("--") && values.put(key.removePrefix("--"), value) == null, "FAILED_ARGUMENTS")
            }
            val keys = setOf("mode", "input", "official", "addon", "output-dir", "repo", "input-sha", "official-sha")
            gate(values.keys.all { it in keys }, "FAILED_ARGUMENTS")
            fun required(key: String) = values[key]?.takeIf { it.isNotBlank() } ?: throw Gate("FAILED_ARGUMENTS")
            val mode = values["mode"] ?: "minimal"
            gate(mode in setOf("minimal", "defaults", "reverse", "addon-only", "official-only", "official-defaults"), "FAILED_MODE")
            return Arguments(mode, File(required("input")), File(required("official")), File(required("addon")),
                File(required("output-dir")), File(System.getProperty("hansfix.integration.repo") ?: required("repo")),
                values["input-sha"] ?: INPUT_SHA, values["official-sha"] ?: OFFICIAL_SHA)
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun Arguments.validate() {
    val root = repo.toPath().toRealPath()
    listOf(input, official, addon).forEach { gate(it.isFile, "FAILED_MISSING_INPUT") }
    gate(!input.toPath().toRealPath().startsWith(root), "FAILED_INPUT_INSIDE_REPO")
    gate(input.canonicalFile != official.canonicalFile && input.canonicalFile != addon.canonicalFile &&
        official.canonicalFile != addon.canonicalFile, "FAILED_DISTINCT_INPUTS")
    gate(!output.canonicalFile.toPath().startsWith(root), "FAILED_OUTPUT_INSIDE_REPO")
    // A fresh mode directory avoids stale-success artifacts and overwriting previous evidence.
    gate(!output.exists() || output.isDirectory && output.list().orEmpty().isEmpty(), "FAILED_OUTPUT_NOT_EMPTY")
    gate(inputSha.matches(Regex("[a-fA-F0-9]{64}")) && officialSha.matches(Regex("[a-fA-F0-9]{64}")), "FAILED_HASH_ARGUMENT")
    gate(sha256(input).equals(inputSha, true), "FAILED_INPUT_HASH")
    gate(sha256(official).equals(officialSha, true), "FAILED_OFFICIAL_HASH")
}

private fun loadBundle(file: File): Set<Patch<*>> {
    gate(file.extension.equals("mpp", true), "FAILED_BUNDLE_EXTENSION")
    JarFile(file).use { jar ->
        val names = jar.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        gate(names.any { it.endsWith(".class") } && names.any { it.endsWith(".dex") }, "FAILED_NOT_DUAL_FORMAT_MPP")
        // The official patch namespace and addon namespace must not resolve from the parent classpath.
        // URLClassLoader is parent-first; allowing main output here would silently test local code.
        names.filter { it.endsWith(".class") && (it.startsWith("app/morphe/patches/") || it.startsWith("io/github/yydarlinker/hansfix/")) }
            .forEach { gate(ClassLoader.getSystemResource(it) == null, "FAILED_BUNDLE_PARENT_SHADOWING") }
    }
    // Deliberately one call per bundle, NOT loadPatchesFromJar(setOf(official, addon)).
    val loaded = loadPatchesFromJar(setOf(file)).byPatchesFile
    gate(loaded.size == 1 && loaded.containsKey(file), "FAILED_BUNDLE_MAPPING")
    return loaded.getValue(file).also { gate(it.isNotEmpty(), "FAILED_EMPTY_BUNDLE") }
}

/** Select the official named root. Its real autoCaptions/captionCookies/transcript dependencies
 * remain the original objects from the official loader, recursively executed by Patcher +=. */
private fun selectCaptions(official: Set<Patch<*>>): Patch<*> =
    official.filter { it.name == "Captions" }.singleOrNull()?.also {
        gate(it.dependencies.isNotEmpty(), "FAILED_CAPTIONS_DEPENDENCIES_MISSING")
    } ?: throw Gate("FAILED_CAPTIONS_SELECTION")

private fun compatible(patch: Patch<*>, metadata: PackageMetadata): Boolean =
    patch.compatibility?.any { pkg ->
        pkg.packageName == null || pkg.packageName == metadata.packageName && pkg.targets.any { target ->
            !target.isExperimental && (target.version == null || target.version == metadata.versionName) &&
                (target.versionCodes?.let { it.isEmpty() || metadata.versionCode.toIntOrNull() in it.values } ?: true)
        }
    } ?: true

private fun defaultSelected(patch: Patch<*>, architecture: ApkArchitecture): Boolean =
    patch.availability?.resolve(InstallerType.STANDARD, architecture)?.let {
        it == PatchAvailability.ENABLED || it == PatchAvailability.REQUIRED
    } ?: patch.default

private fun architecture(input: File): ApkArchitecture = ZipFile(input).use { zip ->
    val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.map { it.split('/')[1] }.toSet()
    when {
        abis.containsAll(setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) -> ApkArchitecture.UNIVERSAL
        "arm64-v8a" in abis -> ApkArchitecture.ARM64_V8A
        "armeabi-v7a" in abis -> ApkArchitecture.ARMEABI_V7A
        "x86_64" in abis -> ApkArchitecture.X86_64
        "x86" in abis -> ApkArchitecture.X86
        else -> ApkArchitecture.UNIVERSAL
    }
}

private fun closure(roots: Collection<Patch<*>>): List<Patch<*>> {
    val visited = Collections.newSetFromMap(IdentityHashMap<Patch<*>, Boolean>())
    val visiting = Collections.newSetFromMap(IdentityHashMap<Patch<*>, Boolean>())
    val result = mutableListOf<Patch<*>>()
    fun visit(patch: Patch<*>) {
        if (patch in visited) return
        gate(visiting.add(patch), "FAILED_DEPENDENCY_CYCLE")
        patch.dependencies.forEach(::visit)
        visiting.remove(patch); visited.add(patch); result += patch
    }
    roots.forEach(::visit)
    return result
}

private fun execute(args: Arguments, audit: Audit) {
    args.validate()
    // The lock lives in the shared runs directory, not a per-mode directory. One process, one
    // Patcher session, one invocation. Never launch different modes in parallel.
    val runsRoot = args.output.canonicalFile.parentFile
    runsRoot.mkdirs()
    FileChannel.open(runsRoot.resolve(".hansfix-integration.lock").toPath(), CREATE, WRITE).use { channel ->
        val lock = channel.tryLock() ?: throw Gate("FAILED_SESSION_ALREADY_RUNNING")
        lock.use {
            args.output.mkdirs()
            audit.attach(args.output.resolve("integration.log"))
            audit.status("INPUT_HASH_VERIFIED"); audit.status("OFFICIAL_HASH_VERIFIED")
            val official: Set<Patch<*>>
            val addonBundle: Set<Patch<*>>
            if (args.mode == "reverse") {
                addonBundle = loadBundle(args.addon); audit.status("ADDON_LOADED"); audit.count(addonBundle.size)
                official = loadBundle(args.official); audit.status("OFFICIAL_LOADED"); audit.count(official.size)
            } else {
                // Independent mode does not load the official bundle at all.
                // Its file is only hash-checked for reproducibility of the input arguments.
                official = if (args.mode == "addon-only") emptySet() else loadBundle(args.official).also {
                    audit.status("OFFICIAL_LOADED"); audit.count(it.size)
                }
                addonBundle = loadBundle(args.addon); audit.status("ADDON_LOADED"); audit.count(addonBundle.size)
            }
            val addon = addonBundle.singleOrNull { it.name == ADDON } ?: throw Gate("FAILED_ADDON_SELECTION")
            val memory = addonBundle.singleOrNull { it.name == "Remember subtitle language" }
            // Independence is the HansFix contract. Memory is verified in the official combination.
            val addons = if (args.mode == "addon-only") listOf(addon) else listOfNotNull(addon, memory)
            val scratch = Files.createTempDirectory(args.output.toPath(), "session-").toFile()
            // Patcher deletes its temporaryFilesPath during initialization. It only receives a
            // fresh child of the newly-created scratch directory, never caller-supplied input paths.
            Patcher(PatcherConfig(apkFile = args.input, temporaryFilesPath = scratch.resolve("patcher"),
                fileWorkspacePath = scratch.resolve("workspace"))).use { patcher ->
                val metadata = patcher.context.packageMetadata
                gate(metadata.packageName == YOUTUBE && metadata.versionName in setOf(VERSION, "21.13.164"), "FAILED_APK_TARGET")
                audit.status("APK_TARGET_VERIFIED")
                val officialBaseline = args.mode == "official-only" || args.mode == "official-defaults"
                if (!officialBaseline) gate(addons.all { compatible(it, metadata) }, "FAILED_ADDON_COMPATIBILITY")
                val captions = if (args.mode == "addon-only") null else selectCaptions(official)
                val architecture = architecture(args.input)
                val officialSelected = when (args.mode) {
                    "addon-only" -> emptyList()
                    "defaults", "official-defaults" -> official.filter { compatible(it, metadata) && defaultSelected(it, architecture) }.sortedBy { it.name }
                    else -> listOf(captions!!)
                }
                gate(captions == null || captions in officialSelected, "FAILED_CAPTIONS_NOT_SELECTED")
                val selected = when {
                    officialBaseline -> officialSelected // Loaded addon is deliberately never selected.
                    args.mode == "reverse" -> addons + officialSelected
                    else -> officialSelected + addons
                }
                gate(!officialBaseline || addon !in selected, "FAILED_BASELINE_ADDON_SELECTED")
                val dependencies = closure(selected)
                gate(dependencies.all { compatible(it, metadata) }, "FAILED_DEPENDENCY_COMPATIBILITY")
                selected.forEach { audit.patch(it, "SELECTED") }
                audit.status("SELECTED_ROOT_COUNT"); audit.count(selected.size)
                dependencies.forEach { audit.patch(it, "DEPENDENCY_CLOSURE") }
                audit.status("DEPENDENCY_CLOSURE_COUNT"); audit.count(dependencies.size)
                gate(officialBaseline || officialSelected.isEmpty() || closure(officialSelected).none { it in closure(listOf(addon)) }, "FAILED_SHARED_BUNDLE_PATCH_OBJECTS")
                patcher += selected.toCollection(linkedSetOf())
                audit.status("SINGLE_SESSION_EXECUTING")
                var failed = false
                val succeeded = Collections.newSetFromMap(IdentityHashMap<Patch<*>, Boolean>())
                runBlocking {
                    patcher().collect { result ->
                        val error = result.exception
                        if (error == null) { succeeded.add(result.patch); audit.patch(result.patch, "SUCCEEDED") }
                        else {
                            failed = true
                            audit.patch(result.patch, "FAILED")
                            if (result.patch in addons) audit.addonGate(error)
                            throw Gate("FAILED_PATCH_EXECUTION")
                        }
                    }
                }
                gate(!failed && selected.all { it in succeeded }, "FAILED_INCOMPLETE_PATCH_RESULTS")
                audit.status("COMPILING_APK")
                val result = patcher.get()
                val patched = scratch.resolve("patched-copy.apk")
                Files.copy(args.input.toPath(), patched.toPath())
                result.applyTo(patched) // get/applyTo both occur before closing the Patcher.
                audit.status("ASSEMBLING_UNSIGNED_APK")
                val pending = scratch.resolve("unsigned-pending.apk")
                writeUnsignedApk(patched, pending)
                val counts = verifyUnsignedApk(pending)
                gate(sha256(args.input).equals(args.inputSha, true), "FAILED_INPUT_CHANGED")
                val output = args.output.resolve("unsigned.apk")
                Files.move(pending.toPath(), output.toPath())
                audit.status("UNSIGNED_APK_VERIFIED"); audit.count(counts)
                audit.output(output)
            }
            audit.status("COMPLETED")
        }
    }
}

fun main(args: Array<String>) {
    val console = System.out
    // Suppress library JUL and direct stdout/stderr BEFORE loading either bundle. No raw logs
    // or exception chains are stored. All observable diagnostics pass through the allowlisted sink.
    System.setOut(PrintStream(OutputStream.nullOutputStream()))
    System.setErr(PrintStream(OutputStream.nullOutputStream()))
    LogManager.getLogManager().reset()
    val audit = Audit(console)
    var code = 0
    try { execute(Arguments.parse(args), audit) }
    catch (failure: Gate) { audit.status(failure.status); code = 1 }
    catch (_: Throwable) { audit.status("FAILED_RUNTIME"); code = 1 }
    finally { audit.close() }
    exitProcess(code)
}
