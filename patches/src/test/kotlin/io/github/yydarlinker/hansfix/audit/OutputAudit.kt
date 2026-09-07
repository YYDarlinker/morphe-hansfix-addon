package io.github.yydarlinker.hansfix.audit

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.AnnotationElement
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.UnknownInstruction
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.iface.value.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.google.gson.GsonBuilder
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Opt-in read-only APK DEX audit. Main: io.github.yydarlinker.hansfix.audit.OutputAuditKt
 * --apk <candidate.apk> [--baseline <official-only.apk>] --report <outside-repo-directory>
 * Run from this checkout/a child, or set -Dhansfix.audit.repo=<checkout>.
 * Exit 0: structure passed (diff may be SKIPPED); 1: check failure; 2: diff requires review;
 * 64: arguments/report boundary; 70: read/normalization/report error. No Gradle or session is run.
 *
 * Never serialize dexlib objects, code, literal values, paths, arguments or exceptions. Reports
 * contain only identifiers, counts, fixed classifications and SHA-256 hashes. Unexpected identifier
 * syntax is hashed. Canonical tokens and literal contents exist in memory only.
 *
 * Normalization v1 retains registers, operands, literal bits, symbolic references, payloads, ordered
 * exception handlers, access flags, annotations, field initial values and hidden API restrictions.
 * Pool indices resolve to semantic references; code-unit addresses resolve to instruction ordinals.
 * const-string/jumbo, goto width and invoke /range encoding are normalized. Debug items, sourceFile,
 * parameter names, DEX placement and ZIP metadata are ignored. NOPs/other encodings are retained
 * conservatively. This is not a general equivalence proof, an ART verifier or a device test.
 */
private const val OWN = "Lio/github/yydarlinker/hansfix/"
private const val RES = OWN + "R;"
private const val PATCH_TIME = "Lapp/morphe/extension/shared/checks/PatchInfo;->PATCH_TIME:J"
private const val RT = "Lio/github/yydarlinker/hansfix/HansFixRuntime;"
private const val OFF = "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"
private const val BRIDGE_NAME = "hansfixAddonGetCaptionToggle"
private const val STR = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val CONTEXT = "Landroid/content/Context;"
private val FLAG = OFF + "->SET_CAPTION_COOKIES:Z"
private val BRIDGE_ID = OFF + "->" + BRIDGE_NAME + "()Z"
private val ENABLED_ID = RT + "->isEnabled()Z"
private val REWRITE_ID = RT + "->rewriteTraditionalCaptionUrl(" + STR + ")" + STR
private val COOKIE_ID = OFF + "->setRequireCookies(" + STR + ")V"
private val WRAPPERS = setOf("labelForTrack", "hasNativeSimplifiedCaption", "applyCaptionMenuLabel", "autoTranslateMenuText")
private val OWN_METHODS = WRAPPERS + setOf("isEnabled", "rewriteTraditionalCaptionUrl", "captionMenuLabel", "rewriteForState", "labelForState")
private val DISTINCTIVE_METHODS = OWN_METHODS - "isEnabled"
private const val SOURCE_MARKER = "patches/src/test/kotlin/io/github/yydarlinker/hansfix/audit/OutputAudit.kt"
private val reportFiles = listOf("checks.json", "inventory.json", "diff.json", "summary.json", "error.json")
private class AuditError(val code: String) : RuntimeException()
private fun demand(ok: Boolean, code: String) { if (!ok) throw AuditError(code) }
private fun MethodReference.id() = definingClass + "->" + name + "(" + parameterTypes.joinToString("") + ")" + returnType
private fun FieldReference.id() = definingClass + "->" + name + ":" + type
private fun MethodReference.params() = parameterTypes.map { it.toString() }
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Method.code() = implementation?.instructions?.toList().orEmpty()
// Default dexlib class-data iterators intentionally skip duplicate members; audit the raw lists.
private fun ClassDef.rawMethods(): List<Method> = if (this is DexBackedClassDef)
    (getDirectMethods(false).asSequence() + getVirtualMethods(false).asSequence()).toList() else methods.toList()
private fun ClassDef.rawFields(): List<Field> = if (this is DexBackedClassDef)
    (getStaticFields(false).asSequence() + getInstanceFields(false).asSequence()).toList() else fields.toList()
private fun opTag(op: Opcode) = op.name.uppercase(java.util.Locale.ROOT).replace('-', '_').replace('/', '_')
private fun isStatic(i: Instruction?) = i?.opcode == Opcode.INVOKE_STATIC || i?.opcode == Opcode.INVOKE_STATIC_RANGE
private fun writeField(i: Instruction) = opTag(i.opcode).startsWith("IPUT") || opTag(i.opcode).startsWith("SPUT")
private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { i, b -> val v = b.toInt() and 255; chars[i * 2] = digits[v ushr 4]; chars[i * 2 + 1] = digits[v and 15] }
    return String(chars)
}
private fun sha(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(UTF_8)))
private fun fileHash(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(128 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return hex(md.digest())
}
private val safeIdentifier = Regex("[A-Za-z0-9_$/;\\[().:<>-]{1,2048}")
private fun identifier(value: String) = if (safeIdentifier.matches(value) &&
    !value.contains("http://", ignoreCase = true) && !value.contains("https://", ignoreCase = true)) value else "identifier-sha256:" + sha(value)
private fun callRegisters(i: Instruction?): List<Int>? = when (i) {
    is FiveRegisterInstruction -> listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
    is RegisterRangeInstruction -> (i.startRegister until i.startRegister + i.registerCount).toList()
    else -> null
}
private data class Options(val apk: Path, val baseline: Path?, val report: Path, val repo: Path, val addon: Path?)
private fun options(args: Array<String>): Options {
    demand(args.size % 2 == 0, "ARGUMENT_PAIRS_REQUIRED")
    val values = linkedMapOf<String, String>()
    for (pair in args.toList().chunked(2)) {
        demand(pair[0] in setOf("--apk", "--baseline", "--report", "--addon") && !values.containsKey(pair[0]), "INVALID_OR_DUPLICATE_OPTION")
        values[pair[0]] = pair[1]
    }
    demand(values.containsKey("--apk") && values.containsKey("--report"), "APK_AND_REPORT_REQUIRED")
    val repo = System.getProperty("hansfix.audit.repo")?.let { Path.of(it).toRealPath() }
        ?: generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve(SOURCE_MARKER)) }?.toRealPath()
        ?: throw AuditError("REPOSITORY_ROOT_NOT_RESOLVED")
    demand(Files.isRegularFile(repo.resolve(SOURCE_MARKER)), "INVALID_REPOSITORY_ROOT")
    val apk = Path.of(values.getValue("--apk")).toRealPath()
    val baseline = values["--baseline"]?.let { Path.of(it).toRealPath() }
    demand(Files.isRegularFile(apk) && (baseline == null || Files.isRegularFile(baseline)), "APK_NOT_REGULAR_FILE")
    demand(baseline == null || !Files.isSameFile(apk, baseline), "BASELINE_EQUALS_CANDIDATE")
    val requested = Path.of(values.getValue("--report")).toAbsolutePath().normalize()
    var ancestor = requested
    val suffix = ArrayList<String>()
    while (!Files.exists(ancestor, NOFOLLOW_LINKS)) {
        suffix += ancestor.fileName.toString()
        ancestor = ancestor.parent ?: throw AuditError("REPORT_ANCESTOR_NOT_FOUND")
    }
    var resolved = ancestor.toRealPath()
    for (part in suffix.asReversed()) resolved = resolved.resolve(part)
    demand(!resolved.startsWith(repo), "REPORT_MUST_BE_OUTSIDE_REPOSITORY")
    Files.createDirectories(resolved)
    resolved = resolved.toRealPath()
    demand(!resolved.startsWith(repo), "REPORT_RESOLVES_INSIDE_REPOSITORY")
    demand(Files.isDirectory(resolved), "REPORT_NOT_DIRECTORY")
    for (name in reportFiles) demand(!Files.exists(resolved.resolve(name), NOFOLLOW_LINKS), "REPORT_FILE_ALREADY_EXISTS")
    val addon = values["--addon"]?.let { Path.of(it).toRealPath() }
    demand(addon == null || Files.isRegularFile(addon), "ADDON_NOT_REGULAR_FILE")
    return Options(apk, baseline, resolved, repo, addon)
}
private val gson by lazy { GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create() }
private fun writeReport(o: Options, name: String, safeData: Any) {
    demand(name in reportFiles, "REPORT_NAME_NOT_ALLOWED")
    val real = o.report.toRealPath()
    demand(real == o.report && !real.startsWith(o.repo), "REPORT_BOUNDARY_CHANGED")
    // CREATE_NEW refuses existing files and symlinks. No replacement or other filesystem writes.
    Files.newBufferedWriter(real.resolve(name), UTF_8, CREATE_NEW, WRITE).use { gson.toJson(safeData, it) }
}
private data class Check(val name: String, val status: String, val count: Int? = null, val expected: Int? = null,
                         val classes: List<String> = emptyList(), val methods: List<String> = emptyList())
private class Checks {
    val rows = ArrayList<Check>()
    fun test(name: String, ok: Boolean, count: Int? = null, expected: Int? = null,
             classes: List<String> = emptyList(), methods: List<String> = emptyList()) {
        rows += Check(name, if (ok) "PASS" else "FAIL", count, expected, classes.map(::identifier), methods.map(::identifier))
    }
    fun skip(name: String) { rows += Check(name, "SKIPPED") }
    fun failed() = rows.count { it.status == "FAIL" }
}
private data class Loaded(val classes: Map<String, ClassDef>, val dexCount: Int, val classDefCount: Int,
                          val duplicates: List<String>, val duplicateZipEntries: Int, val nonStandardDexEntries: Int)
private fun loadApk(path: Path): Loaded {
    val classes = linkedMapOf<String, ClassDef>()
    val duplicates = ArrayList<String>()
    var count = 0; var dexCount = 0; var duplicateEntries = 0; var nonStandard = 0
    val seenEntries = HashSet<String>()
    ZipFile(path.toFile()).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.endsWith(".dex", ignoreCase = true)) continue
            if (!seenEntries.add(entry.name)) duplicateEntries++
            if (!Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex").matches(entry.name)) nonStandard++
            dexCount++
            // Every .dex, including embedded/nonstandard entries, is read; no disk extraction.
            val dex = zip.getInputStream(entry).use { raw ->
                val input = BufferedInputStream(raw)
                input.mark(8)
                val magic = input.readNBytes(8)
                input.reset()
                // Refuse a multi-logical-DEX (041+) container rather than auditing its first header
                // and claiming that every class was examined. Ordinary APK DEX 035..040 is supported.
                demand(magic.size == 8 && magic[0] == 100.toByte() && magic[1] == 101.toByte() &&
                    magic[2] == 120.toByte() && magic[3] == 10.toByte() && magic[7] == 0.toByte() &&
                    String(magic, 4, 3, UTF_8) in setOf("035", "036", "037", "038", "039", "040"), "UNSUPPORTED_DEX_HEADER_OR_CONTAINER")
                DexBackedDexFile.fromInputStream(null, input)
            }
            // Indexed class_def section preserves duplicates even for malformed input; do not deduplicate a Set.
            for (cls in dex.classSection) {
                count++
                if (classes.putIfAbsent(cls.type, cls) != null) duplicates += cls.type
            }
        }
    }
    demand(dexCount > 0, "NO_DEX_ENTRIES")
    return Loaded(classes, dexCount, count, duplicates, duplicateEntries, nonStandard)
}

/** Length-prefixed tokens prevent ambiguous hashes. Canonical text never reaches a report. */
private class Digest(private val omitStrings: Boolean = false) {
    private val md = MessageDigest.getInstance("SHA-256")
    fun token(value: String) {
        val b = value.toByteArray(UTF_8)
        md.update((b.size ushr 24).toByte()); md.update((b.size ushr 16).toByte())
        md.update((b.size ushr 8).toByte()); md.update(b.size.toByte()); md.update(b)
    }
    fun number(value: Number) = token(value.toString())
    fun text(value: String) = token(if (omitStrings) "<string-value-omitted>" else value)
    fun done() = hex(md.digest())
}
private fun reference(d: Digest, r: Reference) {
    when (r) {
        is StringReference -> { d.token("string"); d.text(r.string) }
        is TypeReference -> { d.token("type"); d.token(r.type) }
        is FieldReference -> { d.token("field"); d.token(r.id()) }
        is MethodReference -> { d.token("method"); d.token(r.id()) }
        is MethodProtoReference -> { d.token("proto"); d.number(r.parameterTypes.size); r.parameterTypes.forEach { d.token(it.toString()) }; d.token(r.returnType) }
        is MethodHandleReference -> { d.token("handle"); d.number(r.methodHandleType); reference(d, r.memberReference) }
        is CallSiteReference -> {
            // dexlib's synthetic call-site name is pool-dependent, not a semantic DEX operand.
            d.token("call-site"); reference(d, r.methodHandle); d.token(r.methodName); reference(d, r.methodProto)
            d.number(r.extraArguments.size); r.extraArguments.forEach { encoded(d, it) }
        }
        else -> throw AuditError("UNSUPPORTED_REFERENCE_KIND")
    }
}
private fun elements(d: Digest, values: Set<out AnnotationElement>) {
    d.number(values.size)
    values.sortedBy { it.name }.forEach { d.token(it.name); encoded(d, it.value) }
}
private fun encoded(d: Digest, v: EncodedValue?) {
    if (v == null) { d.token("absent"); return }
    d.number(v.valueType)
    when (v) {
        is ByteEncodedValue -> d.number(v.value)
        is ShortEncodedValue -> d.number(v.value)
        is CharEncodedValue -> d.number(v.value.code)
        is IntEncodedValue -> d.number(v.value)
        is LongEncodedValue -> d.number(v.value)
        is FloatEncodedValue -> d.number(java.lang.Float.floatToRawIntBits(v.value))
        is DoubleEncodedValue -> d.number(java.lang.Double.doubleToRawLongBits(v.value))
        is BooleanEncodedValue -> d.token(v.value.toString())
        is NullEncodedValue -> d.token("null")
        is StringEncodedValue -> d.text(v.value)
        is TypeEncodedValue -> d.token(v.value)
        is FieldEncodedValue -> reference(d, v.value)
        is EnumEncodedValue -> reference(d, v.value)
        is MethodEncodedValue -> reference(d, v.value)
        is MethodTypeEncodedValue -> reference(d, v.value)
        is MethodHandleEncodedValue -> reference(d, v.value)
        is ArrayEncodedValue -> { d.number(v.value.size); v.value.forEach { encoded(d, it) } }
        is AnnotationEncodedValue -> { d.token(v.type); elements(d, v.elements) }
        else -> throw AuditError("UNSUPPORTED_ENCODED_VALUE")
    }
}
private fun annotations(d: Digest, values: Set<out Annotation>) {
    d.number(values.size)
    values.sortedBy { it.type }.forEach { d.token(it.type); d.number(it.visibility); elements(d, it.elements) }
}
private fun classMetadata(cls: ClassDef, maskPatchTime: Boolean = false): String {
    val d = Digest()
    d.token(cls.type); d.number(cls.accessFlags); d.token(cls.superclass ?: "<none>")
    d.number(cls.interfaces.size); cls.interfaces.forEach(d::token)
    annotations(d, cls.annotations)
    val fields = cls.fields.sortedBy { it.id() }
    d.number(fields.size)
    fields.forEach { f ->
        d.token(f.id()); d.number(f.accessFlags)
        if (maskPatchTime && f.id() == PATCH_TIME) d.token("OFFICIAL_PATCH_TIME_INITIAL_VALUE") else encoded(d, f.initialValue)
        annotations(d, f.annotations)
        d.number(f.hiddenApiRestrictions.size); f.hiddenApiRestrictions.map { it.name }.sorted().forEach(d::token)
    }
    return d.done()
}
private class Layout(val code: List<Instruction>, private val skip: Set<Int> = emptySet()) {
    val addresses = IntArray(code.size + 1)
    val ordinal = IntArray(code.size + 1)
    private val addressIndex: Map<Int, Int>
    init {
        for (i in code.indices) {
            addresses[i + 1] = addresses[i] + code[i].codeUnits
            ordinal[i + 1] = ordinal[i] + if (i in skip) 0 else 1
        }
        addressIndex = addresses.withIndex().associate { it.value to it.index }
    }
    fun index(address: Int) = addressIndex[address] ?: throw AuditError("INVALID_INSTRUCTION_TARGET")
    fun target(address: Int) = ordinal[index(address)]
    fun incomingTargets(method: Method): Set<Int> {
        val result = HashSet<Int>()
        code.forEachIndexed { i, ins ->
            if (ins is OffsetInstruction) {
                val targetIndex = index(addresses[i] + ins.codeOffset)
                result += targetIndex
                val payload = code.getOrNull(targetIndex)
                if (payload is SwitchPayload) payload.switchElements.forEach { result += index(addresses[i] + it.offset) }
            }
        }
        method.implementation?.tryBlocks?.forEach { block -> block.exceptionHandlers.forEach { result += index(it.handlerCodeAddress) } }
        return result
    }
}
private data class Projection(val skip: Set<Int> = emptySet(), val summaries: Set<Int> = emptySet(), val model: String? = null)
private fun methodHash(method: Method, projection: Projection = Projection(), omitStrings: Boolean = false): String {
    val d = Digest(omitStrings)
    d.token(method.id()); d.number(method.accessFlags); annotations(d, method.annotations)
    d.number(method.parameters.size)
    method.parameters.forEach { d.token(it.type); annotations(d, it.annotations) }
    d.number(method.hiddenApiRestrictions.size); method.hiddenApiRestrictions.map { it.name }.sorted().forEach(d::token)
    val impl = method.implementation ?: run { d.token("no-implementation"); return d.done() }
    d.number(impl.registerCount)
    val code = method.code()
    val layout = Layout(code, projection.skip)
    val switchOrigins = HashMap<Int, MutableList<Int>>()
    code.forEachIndexed { i, ins ->
        if (ins.opcode == Opcode.PACKED_SWITCH || ins.opcode == Opcode.SPARSE_SWITCH) {
            val payload = layout.index(layout.addresses[i] + (ins as OffsetInstruction).codeOffset)
            switchOrigins.getOrPut(payload) { ArrayList() }.add(i)
        }
    }
    d.number(code.size - projection.skip.size)
    code.forEachIndexed { index, ins ->
        if (index in projection.skip) return@forEachIndexed
        demand(ins !is UnknownInstruction && ins !is FieldOffsetInstruction && ins !is InlineIndexInstruction &&
            ins !is VtableIndexInstruction && ins !is VerificationErrorInstruction, "UNSUPPORTED_OR_ODEX_INSTRUCTION")
        val replacement = index in projection.summaries
        val opcode = if (replacement) Opcode.INVOKE_VIRTUAL else ins.opcode
        val opName = when (opcode) {
            Opcode.CONST_STRING_JUMBO -> "CONST_STRING"
            Opcode.GOTO_16, Opcode.GOTO_32 -> "GOTO"
            else -> opTag(opcode).removeSuffix("_RANGE")
        }
        d.token(opName)
        val call = callRegisters(ins)
        if (call != null) { d.number(call.size); call.forEach(d::number) }
        else {
            if (ins is OneRegisterInstruction) d.number(ins.registerA)
            if (ins is TwoRegisterInstruction) d.number(ins.registerB)
            if (ins is ThreeRegisterInstruction) d.number(ins.registerC)
        }
        if (ins is WideLiteralInstruction) d.number(ins.wideLiteral)
        else if (ins is NarrowLiteralInstruction) d.number(ins.narrowLiteral)
        if (replacement) reference(d, ImmutableMethodReference(projection.model!!, "toString", emptyList<String>(), STR))
        else if (ins is ReferenceInstruction) reference(d, ins.reference)
        if (ins is DualReferenceInstruction) reference(d, ins.reference2)
        if (ins is OffsetInstruction) d.number(layout.target(layout.addresses[index] + ins.codeOffset))
        if (ins is ArrayPayload) {
            d.number(ins.elementWidth); d.number(ins.arrayElements.size); ins.arrayElements.forEach { d.number(it.toLong()) }
        }
        if (ins is SwitchPayload) {
            val origins = switchOrigins[index].orEmpty()
            demand(origins.isNotEmpty(), "ORPHAN_SWITCH_PAYLOAD")
            d.number(origins.size); d.number(ins.switchElements.size)
            for (origin in origins) {
                d.number(layout.ordinal[origin])
                for (element in ins.switchElements) {
                    d.number(element.key); d.number(layout.target(layout.addresses[origin] + element.offset))
                }
            }
        }
    }
    d.number(impl.tryBlocks.size)
    impl.tryBlocks.forEach { block ->
        d.number(layout.target(block.startCodeAddress)); d.number(layout.target(block.startCodeAddress + block.codeUnitCount))
        d.number(block.exceptionHandlers.size)
        block.exceptionHandlers.forEach { d.token(it.exceptionType ?: "<catch-all>"); d.number(layout.target(it.handlerCodeAddress)) }
    }
    return d.done()
}
private data class MethodRecord(val hash: String, val definition: Method)
private data class ClassRecord(val hash: String, val metadataHash: String, val methods: Map<String, MethodRecord>, val definition: ClassDef)
private fun inventory(apk: Loaded, checks: Checks, label: String): Map<String, ClassRecord> {
    val result = linkedMapOf<String, ClassRecord>()
    val duplicateMethods = ArrayList<String>(); val duplicateFields = ArrayList<String>()
    for ((type, cls) in apk.classes.toSortedMap()) {
        val methods = linkedMapOf<String, MethodRecord>()
        cls.rawMethods().sortedBy { it.id() }.forEach { m ->
            if (methods.putIfAbsent(m.id(), MethodRecord(methodHash(m), m)) != null) duplicateMethods += m.id()
        }
        cls.rawFields().groupingBy { it.id() }.eachCount().filterValues { it > 1 }.keys.forEach { duplicateFields += type }
        val metadata = classMetadata(cls)
        val d = Digest(); d.token(metadata); methods.forEach { (id, m) -> d.token(id); d.token(m.hash) }
        result[type] = ClassRecord(d.done(), metadata, methods, cls)
    }
    checks.test(label + "_UNIQUE_METHOD_DEFINITIONS", duplicateMethods.isEmpty(), duplicateMethods.size, 0, methods = duplicateMethods)
    checks.test(label + "_UNIQUE_FIELD_DEFINITIONS", duplicateFields.isEmpty(), duplicateFields.size, 0, classes = duplicateFields.distinct())
    return result
}
private data class Site(val method: Method, val index: Int, val instruction: Instruction)
private data class Scan(val rewrites: List<Site>, val applies: List<Site>, val summaries: List<Site>, val cookies: List<Site>,
                        val bridges: List<Site>, val leakedMethods: List<String>, val modelCandidates: List<String>)
private fun scan(apk: Loaded): Scan {
    val rewrite = ArrayList<Site>(); val apply = ArrayList<Site>(); val summary = ArrayList<Site>()
    val cookie = ArrayList<Site>(); val bridge = ArrayList<Site>(); val leaked = ArrayList<String>(); val models = HashSet<String>()
    for (cls in apk.classes.values) for (m in cls.methods) {
        // isEnabled is a common unrelated host method name; only distinctive addon API names leak.
        if ((m.name in DISTINCTIVE_METHODS && cls.type != RT) || (m.name == BRIDGE_NAME && cls.type != OFF)) leaked += m.id()
        m.implementation?.instructions?.forEachIndexed { index, ins ->
            val r = ins.ref()
            when {
                r?.definingClass == RT && r.name == "rewriteTraditionalCaptionUrl" -> rewrite += Site(m, index, ins)
                r?.definingClass == RT && r.name == "applyCaptionMenuLabel" -> apply += Site(m, index, ins)
                r?.definingClass == RT && r.name == "autoTranslateMenuText" -> summary += Site(m, index, ins)
                r?.definingClass == OFF && r.name == "setRequireCookies" -> cookie += Site(m, index, ins)
                r?.definingClass == OFF && r.name == BRIDGE_NAME -> bridge += Site(m, index, ins)
            }
            val string = ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string
            if (string == "AUTO_TRANSLATE_CAPTIONS_OPTION" && "Landroid/os/Parcelable;" in cls.interfaces && m.params().isEmpty() && m.returnType == "Z") models += cls.type
        }
    }
    return Scan(rewrite, apply, summary, cookie, bridge, leaked, models.sorted())
}
private fun checkContainer(apk: Loaded, checks: Checks, label: String) {
    checks.test(label + "_UNIQUE_CLASSDEFS_ALL_DEX", apk.duplicates.isEmpty(), apk.duplicates.size, 0, classes = apk.duplicates.distinct())
    checks.test(label + "_UNIQUE_DEX_ZIP_ENTRIES", apk.duplicateZipEntries == 0, apk.duplicateZipEntries, 0)
    checks.test(label + "_STANDARD_APPLICATION_DEX_ENTRIES", apk.nonStandardDexEntries == 0, apk.nonStandardDexEntries, 0)
}
private data class Structure(val scan: Scan, val model: String?, val item: String?, val title: String?, val projections: Map<String, Projection>)

private fun auditStructure(apk: Loaded, checks: Checks): Structure {
    checkContainer(apk, checks, "OUTPUT")
    val scan = scan(apk)
    val runtime = apk.classes[RT]
    checks.test("INDEPENDENT_PUBLIC_RUNTIME_PRESENT", runtime != null && AccessFlags.PUBLIC.isSet(runtime.accessFlags),
        if (runtime == null) 0 else 1, 1, classes = listOf(RT))
    val lookalikes = apk.classes.keys.filter { it.substringAfterLast('/') == "HansFixRuntime;" && it != RT }
    checks.test("RUNTIME_NAME_NOT_COPIED_TO_OTHER_NAMESPACE", lookalikes.isEmpty(), lookalikes.size, 0, classes = lookalikes)
    checks.test("DISTINCTIVE_ADDON_APIS_NOT_DEFINED_IN_OFFICIAL_OR_HOST_NAMESPACE", scan.leakedMethods.isEmpty(),
        scan.leakedMethods.size, 0, methods = scan.leakedMethods)
    val ownExtras = apk.classes.keys.filter { it.startsWith(OWN) && it != RT && !(it == RES && emptyResourceClass(apk.classes[it])) }
    checks.test("ONLY_REVIEWED_RUNTIME_AND_EMPTY_GENERATED_R", ownExtras.isEmpty(), ownExtras.size, 0, classes = ownExtras)
    fun exact(owner: ClassDef?, id: String) = owner?.methods?.filter { it.id() == id }?.singleOrNull()
    val enabled = exact(runtime, ENABLED_ID)
    val en = enabled?.code().orEmpty()
    val enabledOk = enabled != null && AccessFlags.PUBLIC.isSet(enabled.accessFlags) && AccessFlags.STATIC.isSet(enabled.accessFlags) &&
        enabled.implementation?.registerCount == 1 && en.size == 3 && isStatic(en[0]) && en[0].ref()?.id() == BRIDGE_ID &&
        callRegisters(en[0]) == emptyList<Int>() && en[1].opcode == Opcode.MOVE_RESULT && en[2].opcode == Opcode.RETURN &&
        (en[1] as? OneRegisterInstruction)?.registerA == 0 && (en[2] as? OneRegisterInstruction)?.registerA == 0 &&
        enabled.implementation!!.tryBlocks.isEmpty()
    checks.test("IS_ENABLED_EXACT_OFFICIAL_BRIDGE_CALL", enabledOk, methods = listOf(ENABLED_ID))
    val official = apk.classes[OFF]
    checks.test("OFFICIAL_BRIDGE_OWNER_PUBLIC", official != null && AccessFlags.PUBLIC.isSet(official.accessFlags))
    val flag = official?.fields?.filter { it.id() == FLAG }?.singleOrNull()
    checks.test("OFFICIAL_ORIGINAL_FLAG_PRIVATE_STATIC_BOOLEAN", flag != null && AccessFlags.PRIVATE.isSet(flag.accessFlags) && AccessFlags.STATIC.isSet(flag.accessFlags))
    val bridge = exact(official, BRIDGE_ID)
    val bc = bridge?.code().orEmpty()
    val bridgeOk = bridge != null && AccessFlags.PUBLIC.isSet(bridge.accessFlags) && AccessFlags.STATIC.isSet(bridge.accessFlags) &&
        bridge.implementation?.registerCount == 1 && bc.size == 2 && bc[0].opcode == Opcode.SGET_BOOLEAN && bc[0].field()?.id() == FLAG &&
        bc[1].opcode == Opcode.RETURN && (bc[0] as? OneRegisterInstruction)?.registerA == 0 && (bc[1] as? OneRegisterInstruction)?.registerA == 0 &&
        bridge.implementation!!.tryBlocks.isEmpty()
    checks.test("BRIDGE_EXACT_READ_ONLY_FLAG_GETTER", bridgeOk, methods = listOf(BRIDGE_ID))
    checks.test("BRIDGE_NO_EXTRA_OVERLOADS", official?.methods?.count { it.name == BRIDGE_NAME } == 1)
    checks.test("IS_ENABLED_NO_EXTRA_OVERLOADS", runtime?.methods?.count { it.name == "isEnabled" } == 1)
    checks.test("BRIDGE_ONLY_CALLED_BY_OWN_IS_ENABLED", scan.bridges.size == 1 && scan.bridges.singleOrNull()?.method?.id() == ENABLED_ID, scan.bridges.size, 1)
    checks.test("RUNTIME_PUBLIC_REWRITE_API_PRESENT", exact(runtime, REWRITE_ID)?.let {
        AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } == true)
    val ownFlagWrites = runtime?.methods?.filter { m -> m.code().any { writeField(it) && it.field()?.definingClass == OFF } }.orEmpty()
    checks.test("NO_RUNTIME_WRITES_TO_OFFICIAL_FIELDS", ownFlagWrites.isEmpty(), ownFlagWrites.size, 0, methods = ownFlagWrites.map { it.id() })
    checks.test("UNIQUE_DYNAMIC_MODEL", scan.modelCandidates.size == 1, scan.modelCandidates.size, 1, classes = scan.modelCandidates)
    val model = scan.modelCandidates.singleOrNull()
    val applyDefinition = runtime?.methods?.filter { it.name == "applyCaptionMenuLabel" }?.singleOrNull()
    val item = applyDefinition?.params()?.takeIf { it.size == 2 && it[1] == LIST }?.first()
    val hierarchy = linkedSetOf<String>()
    var current = item
    while (current != null && hierarchy.add(current)) current = apk.classes[current]?.superclass
    checks.test("MODEL_AND_MENU_ITEM_CLASSES_PUBLIC", model != null && item != null && listOf(model, item).all { type ->
        apk.classes[type]?.let { AccessFlags.PUBLIC.isSet(it.accessFlags) } == true
    })
    val titleCandidates = hierarchy.flatMap { type ->
        apk.classes[type]?.methods?.filter { it.name == "<init>" && it.params() == listOf(STR) }?.flatMap { m ->
            m.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.field() }.filter { it.definingClass == type && it.type == STR }
        }.orEmpty()
    }.distinctBy { it.id() }
    val title = titleCandidates.singleOrNull()
    val titleDef = title?.let { f -> apk.classes[f.definingClass]?.fields?.singleOrNull { it.id() == f.id() } }
    checks.test("UNIQUE_PUBLIC_MENU_TITLE_FIELD", title != null && titleDef != null && AccessFlags.PUBLIC.isSet(titleDef.accessFlags) &&
        !AccessFlags.STATIC.isSet(titleDef.accessFlags) && !AccessFlags.FINAL.isSet(titleDef.accessFlags) && title.definingClass != model,
        titleCandidates.size, 1)
    val trackFields = item?.let { apk.classes[it]?.fields?.filter { f ->
        f.type == model && !AccessFlags.STATIC.isSet(f.accessFlags) && AccessFlags.PUBLIC.isSet(f.accessFlags)
    } }.orEmpty()
    checks.test("UNIQUE_PUBLIC_ORIGINAL_TRACK_FIELD", trackFields.size == 1, trackFields.size, 1)
    val wrapperDefs = runtime?.methods?.filter { it.name in WRAPPERS }.orEmpty()
    val expectedWrappers = if (model != null && item != null) mapOf(
        (RT + "->labelForTrack(" + model + "Z)" + STR) to 7,
        (RT + "->hasNativeSimplifiedCaption(" + LIST + ")Z") to 5,
        (RT + "->applyCaptionMenuLabel(" + item + LIST + ")V") to 5,
        (RT + "->autoTranslateMenuText(" + model + ")" + STR) to 2
    ) else emptyMap()
    checks.test("TYPED_WRAPPER_SIGNATURES_AND_REGISTER_TOTALS", wrapperDefs.size == 4 && expectedWrappers.size == 4 && wrapperDefs.all { m ->
        expectedWrappers[m.id()] == m.implementation?.registerCount && AccessFlags.PUBLIC.isSet(m.accessFlags) && AccessFlags.STATIC.isSet(m.accessFlags)
    }, wrapperDefs.size, 4)
    val coreIds = setOf(
        RT + "-><init>()V", ENABLED_ID, REWRITE_ID,
        RT + "->captionMenuLabel(" + STR + "Z" + STR + STR + "Z)" + STR,
        RT + "->rewriteForState(" + STR + "Z)" + STR,
        RT + "->labelForState(" + STR + "Z" + STR + STR + "ZZ)" + STR
    )
    val runtimeIds = runtime?.methods?.map { it.id() }?.toSet().orEmpty()
    checks.test("RUNTIME_HAS_ONLY_REVIEWED_METHOD_SURFACE", expectedWrappers.size == 4 && runtimeIds == coreIds + expectedWrappers.keys)
    val unexpectedWrapperStores = wrapperDefs.filter { m -> m.code().any { opTag(it.opcode).startsWith("APUT") } }
    checks.test("WRAPPERS_NO_ARRAY_STORES", unexpectedWrapperStores.isEmpty(), unexpectedWrapperStores.size, 0,
        methods = unexpectedWrapperStores.map { it.id() })
    val writes = wrapperDefs.flatMap { m -> m.code().filter(::writeField).map { m to it } }
    checks.test("WRAPPERS_ONLY_ONE_MENU_TITLE_WRITE", title != null && writes.size == 1 && writes.all { (m, ins) ->
        m.name == "applyCaptionMenuLabel" && ins.opcode == Opcode.IPUT_OBJECT && ins.field()?.id() == title.id() &&
            (ins as? TwoRegisterInstruction)?.registerB == (m.implementation!!.registerCount - 2)
    }, writes.size, 1, methods = writes.map { it.first.id() })
    val modelWrites = runtime?.methods?.filter { m -> m.code().any { writeField(it) && it.field()?.definingClass == model } }.orEmpty()
    checks.test("OWN_RUNTIME_NO_DIRECT_MODEL_FIELD_WRITES", model != null && modelWrites.isEmpty(), modelWrites.size, 0, methods = modelWrites.map { it.id() })
    val wrapperFields = wrapperDefs.flatMap { it.code() }.mapNotNull { it.field() }
    val unsafeReads = wrapperFields.filter { f ->
        val definition = apk.classes[f.definingClass]?.fields?.singleOrNull { it.id() == f.id() }
        definition == null || !AccessFlags.PUBLIC.isSet(definition.accessFlags) || AccessFlags.STATIC.isSet(definition.accessFlags) ||
            apk.classes[f.definingClass]?.let { AccessFlags.PUBLIC.isSet(it.accessFlags) } != true
    }
    checks.test("WRAPPER_HOST_FIELDS_RESOLVE_AS_PUBLIC_INSTANCE_FIELDS", unsafeReads.isEmpty(), unsafeReads.size, 0)
    val unresolvedModelCalls = wrapperDefs.flatMap { it.code() }.mapNotNull { it.ref() }.filter { it.definingClass == model }.filter { ref ->
        val definition = model?.let { apk.classes[it] }?.methods?.singleOrNull { it.id() == ref.id() }
        definition == null || !AccessFlags.PUBLIC.isSet(definition.accessFlags) || AccessFlags.STATIC.isSet(definition.accessFlags)
    }
    checks.test("WRAPPER_MODEL_CALLS_RESOLVE_AS_PUBLIC_INSTANCE_METHODS", model != null && unresolvedModelCalls.isEmpty(),
        unresolvedModelCalls.size, 0, methods = unresolvedModelCalls.map { it.id() })
    val unresolvedOwnCalls = wrapperDefs.flatMap { it.code() }.mapNotNull { it.ref() }.filter { it.definingClass == RT && exact(runtime, it.id()) == null }
    checks.test("WRAPPER_OWN_CALLS_RESOLVE", unresolvedOwnCalls.isEmpty(), unresolvedOwnCalls.size, 0, methods = unresolvedOwnCalls.map { it.id() })
    val policyCalls = wrapperDefs.flatMap { it.code() }.filter { it.ref()?.definingClass == RT && it.ref()?.name == "captionMenuLabel" }
    checks.test("LABEL_POLICY_RANGE_WORDS_AND_SIGNATURE", policyCalls.size == 1 && policyCalls.all { i ->
        i.opcode == Opcode.INVOKE_STATIC_RANGE && callRegisters(i) == listOf(0, 1, 2, 3, 4) &&
            i.ref()?.id() == RT + "->captionMenuLabel(" + STR + "Z" + STR + STR + "Z)" + STR
    }, policyCalls.size, 1)
    val outOfBounds = wrapperDefs.filter { m ->
        val total = m.implementation?.registerCount ?: 0
        m.code().any { i ->
            val regs = callRegisters(i) ?: listOfNotNull((i as? OneRegisterInstruction)?.registerA,
                (i as? TwoRegisterInstruction)?.registerB, (i as? ThreeRegisterInstruction)?.registerC)
            regs.any { it < 0 || it >= total }
        }
    }
    checks.test("WRAPPER_REGISTER_REFERENCES_IN_BOUNDS", outOfBounds.isEmpty(), outOfBounds.size, 0, methods = outOfBounds.map { it.id() })

    val projections = linkedMapOf<String, Projection>()
    checks.test("NETWORK_REWRITE_CALL_COUNT", scan.rewrites.size == 1, scan.rewrites.size, 1, methods = scan.rewrites.map { it.method.id() })
    checks.test("OFFICIAL_COOKIE_CHECK_CALL_COUNT", scan.cookies.size == 1, scan.cookies.size, 1, methods = scan.cookies.map { it.method.id() })
    var networkOk = scan.rewrites.size == 1 && scan.cookies.size == 1
    for (site in scan.rewrites) {
        val code = site.method.code(); val i = site.index; val regs = callRegisters(site.instruction)
        val result = code.getOrNull(i + 1); val cookie = code.getOrNull(i + 2); val builder = code.getOrNull(i + 3)
        val targets = Layout(code).incomingTargets(site.method)
        val good = !site.method.definingClass.startsWith(OWN) && isStatic(site.instruction) && site.instruction.ref()?.id() == REWRITE_ID && regs?.size == 1 &&
            result?.opcode == Opcode.MOVE_RESULT_OBJECT && (result as? OneRegisterInstruction)?.registerA == regs[0] &&
            isStatic(cookie) && cookie?.ref()?.id() == COOKIE_ID && callRegisters(cookie) == regs &&
            builder?.ref()?.definingClass == "Lorg/chromium/net/CronetEngine;" && builder.ref()?.name == "newUrlRequestBuilder" &&
            callRegisters(builder)?.getOrNull(1) == regs[0] && (i + 1..i + 3).none { it in targets }
        networkOk = networkOk && good
        if (good) projections[site.method.id()] = Projection(skip = setOf(i, i + 1))
    }
    checks.test("NETWORK_ADJACENT_REWRITE_RESULT_COOKIE_BUILDER_SAME_REGISTER_NO_MID_ENTRY", networkOk)
    checks.test("UI_APPLY_CALL_COUNT", scan.applies.size == 4, scan.applies.size, 4, methods = scan.applies.map { it.method.id() })
    checks.test("UI_SUMMARY_CALL_COUNT", scan.summaries.size == 2, scan.summaries.size, 2, methods = scan.summaries.map { it.method.id() })
    val menuMethods = scan.applies.groupBy { it.method.id() }
    checks.test("TWO_MENUS_TWO_APPLIES_ONE_SUMMARY_EACH", menuMethods.size == 2 && menuMethods.all { (id, rows) ->
        rows.size == 2 && scan.summaries.count { it.method.id() == id } == 1 && rows.first().method.params().isEmpty() &&
            !AccessFlags.STATIC.isSet(rows.first().method.accessFlags) && rows.first().method.returnType == "Landroid/widget/ListAdapter;"
    } && scan.summaries.all { it.method.id() in menuMethods }, menuMethods.size, 2)
    var rowsOk = item != null && model != null && scan.applies.size == 4
    var summaryOk = item != null && model != null && scan.summaries.size == 2
    for ((id, rows) in menuMethods) {
        val method = rows.first().method; val code = method.code(); val targets = Layout(code).incomingTargets(method)
        val skip = HashSet<Int>(); val summaries = HashSet<Int>(); var menuOk = true
        for (site in rows) {
            val i = site.index; val ctor = code.getOrNull(i - 2); val load = code.getOrNull(i - 1)
            val args = callRegisters(site.instruction); val ctorArgs = callRegisters(ctor); val next = code.getOrNull(i + 1)
            val field = load?.field(); val scratch = args?.getOrNull(1)
            val good = isStatic(site.instruction) && site.instruction.ref()?.id() == RT + "->applyCaptionMenuLabel(" + item + LIST + ")V" && args?.size == 2 &&
                ctor?.opcode == Opcode.INVOKE_DIRECT && ctor.ref()?.definingClass == item && ctor.ref()?.name == "<init>" &&
                ctor.ref()?.params()?.take(2) == listOf(CONTEXT, model) && ctorArgs?.size == 4 && ctorArgs[0] == args[0] && ctorArgs[1] == scratch &&
                load?.opcode == Opcode.IGET_OBJECT && (load as? TwoRegisterInstruction)?.registerA == scratch &&
                (load as? TwoRegisterInstruction)?.registerB == (method.implementation!!.registerCount - 1) && field?.definingClass == method.definingClass &&
                field.type in setOf(LIST, "Ljava/util/ArrayList;") && scratch != args[0] && scratch != ctorArgs[2] &&
                next != null && next.opcode.setsRegister() && (next as? OneRegisterInstruction)?.registerA == scratch &&
                (next as? TwoRegisterInstruction)?.registerB != scratch && (next as? ThreeRegisterInstruction)?.registerC != scratch &&
                callRegisters(next) == null && (i - 1) !in targets && i !in targets
            rowsOk = rowsOk && good; menuOk = menuOk && good
            if (good) { skip += i - 1; skip += i }
        }
        for (site in scan.summaries.filter { it.method.id() == id }) {
            val i = site.index; val result = code.getOrNull(i + 1); val store = code.getOrNull(i + 2)
            val good = isStatic(site.instruction) && site.instruction.ref()?.id() == RT + "->autoTranslateMenuText(" + model + ")" + STR &&
                callRegisters(site.instruction)?.size == 1 && result?.opcode == Opcode.MOVE_RESULT_OBJECT && store?.opcode == Opcode.IPUT_OBJECT &&
                store.field()?.definingClass in hierarchy && store.field()?.definingClass != model && store.field()?.type == STR &&
                (store as? TwoRegisterInstruction)?.registerA == (result as? OneRegisterInstruction)?.registerA && (i + 1) !in targets
            summaryOk = summaryOk && good; menuOk = menuOk && good
            if (good) summaries += i
        }
        if (menuOk && skip.size == 4 && summaries.size == 1 && model != null) projections[id] = Projection(skip, summaries, model)
    }
    checks.test("UI_APPLY_CONSTRUCTOR_BACKING_LOAD_AND_DEAD_SCRATCH", rowsOk)
    checks.test("SUMMARY_RESULT_ONLY_STORED_IN_MENU_UI_HIERARCHY", summaryOk)
    return Structure(scan, model, item, title?.id(), projections)
}

private data class Change(val className: String, val methodSignature: String?, val beforeHash: String?, val afterHash: String?,
                          val classification: String, val reviewRequired: Boolean)
private data class Diff(val status: String, val baselineShapeValid: Boolean?, val modelCompared: Boolean, val modelUnchanged: Boolean?,
                        val unchangedClassCount: Int, val unchangedMethodCount: Int, val changes: List<Change>)
private fun compare(base: Map<String, ClassRecord>, out: Map<String, ClassRecord>, s: Structure, checks: Checks, baselineValid: Boolean): Diff {
    val changes = ArrayList<Change>(); var sameClasses = 0; var sameMethods = 0
    fun add(type: String, method: String?, before: String?, after: String?, kind: String, review: Boolean = true) {
        changes += Change(identifier(type), method?.let(::identifier), before, after, kind, review)
    }
    for (type in (base.keys + out.keys).sorted()) {
        val b = base[type]; val a = out[type]
        if (b == null || a == null) {
            val expectedNewRuntime = b == null && a != null && (type == RT || type == RES && emptyResourceClass(a.definition)) && baselineValid && checks.failed() == 0
            add(type, null, b?.hash, a?.hash,
                if (expectedNewRuntime) "ADDON_RUNTIME_ADDED_STRUCTURAL_CHECKS_PASSED" else if (b == null) "UNEXPECTED_CLASS_ADDED" else "CLASS_REMOVED", !expectedNewRuntime)
            (a ?: b)!!.methods.forEach { (id, m) -> add(type, id, if (a == null) m.hash else null, if (b == null) m.hash else null,
                if (expectedNewRuntime) "ADDON_RUNTIME_METHOD_ADDED" else if (b == null) "METHOD_IN_ADDED_CLASS" else "METHOD_IN_REMOVED_CLASS", !expectedNewRuntime) }
            continue
        }
        if (b.hash == a.hash) { sameClasses++; sameMethods += a.methods.size; continue }
        // Class-level change indexes member decisions below; it is NOT an approval of the class.
        add(type, null, b.hash, a.hash, "CLASS_CONTENT_CHANGED_SEE_MEMBER_ROWS", false)
        if (b.metadataHash != a.metadataHash) {
            val timeOnly = patchTimeOnly(b, a)
            add(type, null, b.metadataHash, a.metadataHash,
                if (timeOnly) "OFFICIAL_PATCH_TIME_INITIAL_VALUE_ONLY" else "CLASS_FIELDS_OR_ANNOTATIONS_OR_ACCESS_CHANGED", !timeOnly)
        }
        for (id in (b.methods.keys + a.methods.keys).sorted()) {
            val bm = b.methods[id]; val am = a.methods[id]
            if (bm?.hash == am?.hash) { sameMethods++; continue }
            if (bm == null || am == null) {
                val expectedBridge = bm == null && am != null && id == BRIDGE_ID && baselineValid &&
                    checks.rows.any { it.name == "BRIDGE_EXACT_READ_ONLY_FLAG_GETTER" && it.status == "PASS" }
                add(type, id, bm?.hash, am?.hash, if (expectedBridge) "EXACT_READ_ONLY_BRIDGE_ADDED" else if (bm == null) "UNEXPECTED_METHOD_ADDED" else "METHOD_REMOVED", !expectedBridge)
                continue
            }
            val projection = s.projections[id]
            // Undo ONLY the structurally validated two network instructions / four UI instructions
            // and one summary call, in memory. Everything else (including handlers/metadata) must match.
            val exact = baselineValid && projection != null && methodHash(am.definition, projection) == bm.hash
            val stringsOnly = !exact && methodHash(am.definition, omitStrings = true) == methodHash(bm.definition, omitStrings = true)
            val kind = when {
                exact && projection!!.summaries.isEmpty() -> "EXACT_NETWORK_INSERTION_ONLY"
                exact -> "EXACT_MENU_INSERTIONS_AND_SUMMARY_REPLACEMENT_ONLY"
                type == s.model -> "ORIGINAL_MODEL_METHOD_CHANGED"
                stringsOnly -> "STRING_VALUE_OR_ANNOTATION_PAYLOAD_CHANGE_REVIEW_REQUIRED"
                projection != null -> "HOOK_METHOD_HAS_ADDITIONAL_CHANGES"
                type.startsWith("Lapp/morphe/extension/") -> "OFFICIAL_EXTENSION_METHOD_CHANGED_REVIEW_REQUIRED"
                else -> "UNEXPLAINED_METHOD_CHANGE"
            }
            add(type, id, bm.hash, am.hash, kind, !exact)
        }
    }
    val model = s.model
    val compared = baselineValid && model != null && base.containsKey(model) && out.containsKey(model)
    val unchanged = if (compared && model != null) base.getValue(model).hash == out.getValue(model).hash else null
    checks.test("BASELINE_ORIGINAL_MODEL_NORMALIZED_CLASS_UNCHANGED", compared && unchanged == true, classes = listOfNotNull(model))
    val status = if (!baselineValid) "INVALID_BASELINE" else if (changes.any { it.reviewRequired }) "REVIEW_REQUIRED" else "NO_UNEXPLAINED_DEX_CHANGES"
    return Diff(status, baselineValid, compared, unchanged, sameClasses, sameMethods, changes)
}
private fun safeInventory(label: String, inv: Map<String, ClassRecord>): Map<String, Any> = mapOf(
    "label" to label,
    "classes" to inv.map { (type, c) -> mapOf("className" to identifier(type), "hash" to c.hash, "metadataHash" to c.metadataHash,
        "methods" to c.methods.map { (id, m) -> mapOf("methodSignature" to identifier(id), "hash" to m.hash) }) }
)

// AGP emits this exact empty resource namespace class even with no resources.
// It has no state and is not an extra official/runtime library implementation.
private fun emptyResourceClass(cls: ClassDef?): Boolean {
    if (cls == null || cls.type != RES || cls.superclass != "Ljava/lang/Object;" ||
        cls.accessFlags != (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) ||
        cls.rawFields().isNotEmpty() || cls.interfaces.isNotEmpty() || cls.annotations.isNotEmpty()) return false
    val m = cls.rawMethods().singleOrNull() ?: return false
    if (m.name != "<init>" || m.params().isNotEmpty() || m.returnType != "V" ||
        m.accessFlags != (AccessFlags.PRIVATE.value or AccessFlags.CONSTRUCTOR.value) ||
        m.annotations.isNotEmpty() || m.implementation?.registerCount != 1 || m.implementation!!.tryBlocks.isNotEmpty()) return false
    val code = m.code()
    return code.size == 2 && code[0].opcode == Opcode.INVOKE_DIRECT &&
        code[0].ref()?.id() == "Ljava/lang/Object;-><init>()V" && callRegisters(code[0]) == listOf(0) &&
        code[1].opcode == Opcode.RETURN_VOID
}
private fun patchTimeOnly(b: ClassRecord, a: ClassRecord): Boolean {
    if (b.definition.type != "Lapp/morphe/extension/shared/checks/PatchInfo;" || a.definition.type != b.definition.type) return false
    val bf = b.definition.rawFields().singleOrNull { it.id() == PATCH_TIME } ?: return false
    val af = a.definition.rawFields().singleOrNull { it.id() == PATCH_TIME } ?: return false
    val bv = (bf.initialValue as? LongEncodedValue)?.value ?: return false
    val av = (af.initialValue as? LongEncodedValue)?.value ?: return false
    return AccessFlags.STATIC.isSet(bf.accessFlags) && AccessFlags.STATIC.isSet(af.accessFlags) &&
        bv > 0 && av > 0 && bv != av && b.methods.mapValues { it.value.hash } == a.methods.mapValues { it.value.hash } &&
        classMetadata(b.definition, true) == classMetadata(a.definition, true)
}
private fun methodHeaderHash(m: Method): String {
    val d = Digest(); d.token(m.id()); d.number(m.accessFlags); annotations(d, m.annotations)
    d.number(m.parameters.size); m.parameters.forEach { d.token(it.type); annotations(d, it.annotations) }
    d.number(m.hiddenApiRestrictions.size); m.hiddenApiRestrictions.map { it.name }.sorted().forEach(d::token)
    return d.done()
}
private fun trustedRuntime(path: Path, output: Loaded, checks: Checks): Map<String, Any> {
    val beforeHash = fileHash(path)
    var mpeHash = ""
    val definitions = linkedMapOf<String, ClassDef>()
    ZipFile(path.toFile()).use { zip ->
        val entries = zip.entries().asSequence().filter { it.name == "extensions/hansfix-addon.mpe" }.toList()
        demand(entries.size == 1 && zip.getEntry("classes.dex") != null, "TRUSTED_ADDON_FORMAT")
        val bytes = zip.getInputStream(entries.single()).use { it.readBytes() }
        mpeHash = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val dex = DexBackedDexFile.fromInputStream(null, BufferedInputStream(bytes.inputStream()))
        for (cls in dex.classSection) demand(definitions.putIfAbsent(cls.type, cls) == null, "TRUSTED_ADDON_DUPLICATE_CLASS")
    }
    checks.test("TRUSTED_ADDON_RUNTIME_CLASS_SET", definitions.keys == setOf(RT, RES) && emptyResourceClass(definitions[RES]))
    val source = definitions[RT]; val target = output.classes[RT]
    checks.test("TRUSTED_RUNTIME_CLASS_METADATA_UNCHANGED", source != null && target != null && classMetadata(source) == classMetadata(target))
    val changed = mutableListOf<String>()
    if (source != null && target != null) {
        val sourceMethods = source.rawMethods().associateBy { it.id() }
        val targetMethods = target.rawMethods().associateBy { it.id() }
        checks.test("TRUSTED_RUNTIME_SOURCE_UNIQUE_METHODS", sourceMethods.size == source.rawMethods().size)
        for ((id, m) in sourceMethods) {
            val actual = targetMethods[id]
            if (actual == null || (if (id == ENABLED_ID) methodHeaderHash(m) != methodHeaderHash(actual) else methodHash(m) != methodHash(actual))) changed += id
        }
        checks.test("ALL_EXISTING_JAVA_RUNTIME_METHODS_MATCH_TRUSTED_ADDON_EXCEPT_ENABLED_BODY", changed.isEmpty(), changed.size, 0, methods = changed)
        checks.test("ONLY_TYPED_WRAPPERS_ADDED_TO_TRUSTED_RUNTIME",
            (targetMethods.keys - sourceMethods.keys).map { it.substringAfter("->").substringBefore('(') }.toSet() == WRAPPERS)
    } else checks.test("TRUSTED_RUNTIME_PRESENT_IN_BOTH", false)
    val sourceR = definitions[RES]; val targetR = output.classes[RES]
    checks.test("EMPTY_RESOURCE_CLASS_MATCHES_TRUSTED_ADDON", emptyResourceClass(sourceR) && emptyResourceClass(targetR) &&
        classMetadata(sourceR!!) == classMetadata(targetR!!) &&
        sourceR.rawMethods().map(::methodHash) == targetR.rawMethods().map(::methodHash))
    checks.test("TRUSTED_ADDON_UNCHANGED_DURING_AUDIT", fileHash(path) == beforeHash)
    return mapOf("sha256" to beforeHash, "mpeSha256" to mpeHash, "existingJavaBodiesUnchanged" to changed.isEmpty())
}

private fun runAudit(o: Options): Int {
    val checks = Checks()
    val outputSha = fileHash(o.apk); val baselineSha = o.baseline?.let(::fileHash)
    val output = loadApk(o.apk)
    val structure = auditStructure(output, checks)
    val trusted = o.addon?.let { trustedRuntime(it, output, checks) }
    if (o.addon == null) checks.skip("JAVA_BODIES_NOT_COMPARED_WITH_TRUSTED_ADDON")
    val outInventory = inventory(output, checks, "OUTPUT")
    var baseInventory: Map<String, ClassRecord>? = null
    var baseLoaded: Loaded? = null
    var diff = Diff("SKIPPED_NO_BASELINE", null, false, null, 0, 0, emptyList())
    if (o.baseline == null) {
        checks.skip("BASELINE_ORIGINAL_MODEL_NORMALIZED_CLASS_UNCHANGED")
        checks.skip("BASELINE_OFFICIAL_AND_HOST_DEX_UNCHANGED_EXCEPT_EXACT_EDITS")
    } else {
        val before = checks.failed()
        val baseline = loadApk(o.baseline); baseLoaded = baseline
        checkContainer(baseline, checks, "BASELINE")
        val bs = scan(baseline)
        val hasOfficial = baseline.classes[OFF]?.methods?.any { it.id() == COOKIE_ID } == true
        val clean = baseline.classes.keys.none { it.startsWith(OWN) } && bs.leakedMethods.isEmpty() && bs.bridges.isEmpty() &&
            bs.rewrites.isEmpty() && bs.applies.isEmpty() && bs.summaries.isEmpty() && baseline.classes[OFF]?.methods?.none { it.name == BRIDGE_NAME } == true
        checks.test("BASELINE_OFFICIAL_PRESENT_WITH_ONE_COOKIE_HOOK", hasOfficial && bs.cookies.size == 1, bs.cookies.size, 1)
        checks.test("BASELINE_HAS_NO_ADDON_MARKERS", clean)
        checks.test("BASELINE_CONTENT_DIFFERS_FROM_CANDIDATE", baselineSha != outputSha)
        baseInventory = inventory(baseline, checks, "BASELINE")
        val valid = checks.failed() == before
        diff = compare(baseInventory, outInventory, structure, checks, valid)
    }
    checks.test("CANDIDATE_UNCHANGED_DURING_AUDIT", fileHash(o.apk) == outputSha)
    if (o.baseline != null) checks.test("BASELINE_UNCHANGED_DURING_AUDIT", fileHash(o.baseline) == baselineSha)
    val reviewCount = diff.changes.count { it.reviewRequired }
    val failed = checks.failed()
    val status = when {
        failed > 0 -> "FAIL"
        reviewCount > 0 -> "REVIEW_REQUIRED"
        o.baseline == null -> "STRUCTURE_PASS_DIFF_SKIPPED"
        else -> "STRUCTURE_PASS_NO_UNEXPLAINED_DEX_CHANGES"
    }
    writeReport(o, "checks.json", mapOf("schemaVersion" to 1, "checks" to checks.rows))
    val inventories = ArrayList<Map<String, Any>>()
    inventories += safeInventory("OUTPUT", outInventory)
    baseInventory?.let { inventories += safeInventory("BASELINE", it) }
    writeReport(o, "inventory.json", mapOf("schemaVersion" to 1, "normalization" to "DEX_SEMANTIC_OPERANDS_V1", "inventories" to inventories))
    writeReport(o, "diff.json", mapOf("schemaVersion" to 1, "diff" to diff))
    // Summary is written LAST. Missing summary means incomplete audit, never success.
    writeReport(o, "summary.json", mapOf(
        "schemaVersion" to 1, "status" to status, "candidateSha256" to outputSha, "baselineSha256" to baselineSha,
        "candidateDexCount" to output.dexCount, "candidateClassDefCount" to output.classDefCount,
        "baselineDexCount" to baseLoaded?.dexCount, "baselineClassDefCount" to baseLoaded?.classDefCount,
        "trustedAddon" to trusted,
        "failedCheckCount" to failed, "reviewRequiredChangeCount" to reviewCount, "diffStatus" to diff.status,
        "originalModelCompared" to diff.modelCompared, "originalModelUnchanged" to diff.modelUnchanged,
        "modelClass" to structure.model?.let(::identifier), "menuItemClass" to structure.item?.let(::identifier),
        "menuTitleField" to structure.title?.let(::identifier),
        "limitations" to listOf(
            "STATIC_DEX_AUDIT_NOT_ART_VERIFICATION_OR_DEVICE_TEST",
            "NO_GRADLE_PATCHER_SESSION_OR_DECOMPILER_EXECUTED",
            "NO_BASELINE_MEANS_NO_ORIGINAL_MODEL_OR_OFFICIAL_CODE_COMPARISON",
            "BASELINE_OFFICIAL_ONLY_PROVENANCE_AND_VERSION_ARE_CALLER_RESPONSIBILITY",
            "NO_RESOURCE_MANIFEST_ASSET_NATIVE_LIBRARY_SIGNATURE_OR_COOKIE_BEHAVIOR_COMPARISON",
            "DEBUG_ITEMS_PARAMETER_NAMES_SOURCE_FILE_ZIP_LAYOUT_AND_DEX_PLACEMENT_IGNORED",
            "REGISTER_NUMBERS_NOPS_AND_NON_NORMALIZED_ENCODINGS_RETAINED_CONSERVATIVELY",
            "DEX_041_PLUS_MULTI_LOGICAL_CONTAINERS_AND_ODEX_CDEX_ARE_REJECTED",
            "CLASS_ANNOTATIONS_FIELD_VALUES_METHOD_ANNOTATIONS_AND_STRING_OPERANDS_ARE_HASHED_NOT_PRINTED",
            "ONLY_EXACT_OFFICIAL_PATCH_TIME_INITIAL_VALUE_DIFFERENCE_IS_CLASSIFIED_AS_BUILD_TIMESTAMP",
            "WRAPPER_FIELD_WRITE_CHECK_IS_DIRECT_BYTECODE_NOT_GENERAL_TRANSITIVE_SIDE_EFFECT_PROOF",
            if (o.addon == null) "NEW_RUNTIME_METHOD_BODIES_NOT_COMPARED_TO_TRUSTED_ADDON" else "EXISTING_JAVA_BODIES_MATCH_TRUSTED_MPE_EXCEPT_STRUCTURALLY_VERIFIED_ENABLED_BRIDGE"
        )
    ))
    println("OUTPUT_AUDIT status=" + status + " failed=" + failed + " review=" + reviewCount)
    return when { failed > 0 -> 1; reviewCount > 0 -> 2; else -> 0 }
}
fun main(args: Array<String>) {
    var o: Options? = null
    val exit = try {
        val resolved = options(args)
        o = resolved
        runAudit(resolved)
    } catch (e: Throwable) {
        // Messages/causes (including linkage/parser errors) may contain paths or dex code.
        val code = (e as? AuditError)?.code ?: "INPUT_OR_NORMALIZATION_OR_REPORT_IO_ERROR"
        o?.let { resolved ->
            try { writeReport(resolved, "error.json", mapOf("schemaVersion" to 1, "status" to "ERROR", "code" to code)) }
            catch (_: Throwable) { /* No secondary error contents may reach logs. */ }
        }
        println("OUTPUT_AUDIT status=ERROR code=" + code)
        if (o == null) 64 else 70
    }
    exitProcess(exit)
}
