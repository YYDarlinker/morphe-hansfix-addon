package io.github.yydarlinker.hansfix

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val MEMORY = "Lio/github/yydarlinker/hansfix/subtitlememory/SubtitleMemoryRuntime;"
private const val OBJ = "Ljava/lang/Object;"
private const val STR = "Ljava/lang/String;"
private const val CTX = "Landroid/content/Context;"
private const val TRACKS = "Ljava/util/List;"
private const val SELECT_LOG = "setSubtitleTrack name:%s languageCode:%s languageName:%s format:%d trackName:%s vssid:%s videoid:%s"
private fun requireMemory(ok: Boolean, message: String) { if (!ok) throw PatchException("Subtitle memory: $message") }
private fun <T> List<T>.singleMemory(role: String): T {
    requireMemory(size == 1, "$role must match once (found $size); unsupported structure or conflicting patches.")
    return single()
}
private fun Instruction.mref() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.fref() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.sref() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
private fun Method.containsText(s: String) = instructions().any { it.sref() == s }
private fun MethodReference.memoryId() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun FieldReference.memoryId() = "$definingClass->$name:$type"

@Suppress("unused")
val subtitleMemoryPatch = bytecodePatch(
    name = "Remember subtitle language",
    description = "Always remembers the last selected global subtitle language and opens matching captions on new videos. Includes auto-translation and HansFix support. No settings switch. Verified structure: YouTube 21.13.164; other versions require matching structures.",
    default = true,
) {
    compatibleWith(Compatibility(
        name = "YouTube", packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK_REQUIRED, appIconColor = 0xFF0033,
        signatures = setOf(
            "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
            "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
        ),
        targets = listOf(AppTarget(version = null, minSdk = 28)),
    ))
    dependsOn(captionAddonExtensionPatch)
    execute {
        requireMemory(packageMetadata.packageName == "com.google.android.youtube", "requires original YouTube input.")
    }
    finalize { installSubtitleMemory() }
}

private fun BytecodePatchContext.installSubtitleMemory() {
    requireMemory(classDefByOrNull("Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;") != null,
        "select official Captions in the SAME expert-mode operation.")
    val setter = getAllClassesWithString(SELECT_LOG).flatMap { classDefBy(it.type).methods.toList() }
        .filter { it.containsText(SELECT_LOG) }.singleMemory("native preference-writing selector")
    val owner = classDefBy(setter.definingClass)
    requireMemory(setter.returnType == "V" && setter.parameterTypes.size == 3 && setter.parameterTypes.last() == "I",
        "unexpected selection signature.")
    val originType = classDefBy(setter.parameterTypes[1].toString())
    requireMemory(originType.superclass == "Ljava/lang/Enum;" && originType.methods.any { it.containsText("PREFERRED_TRACK") },
        "native manual-selection origin changed.")
    val track = classDefBy(setter.parameterTypes.first().toString())
    requireMemory("Landroid/os/Parcelable;" in track.interfaces, "caption track must be Parcelable.")
    val sentinel = track.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Z" && it.containsText("AUTO_TRANSLATE_CAPTIONS_OPTION") }
        .toList().singleMemory("track sentinel predicate")
    val language = sentinel.instructions().mapNotNull { it.fref() }.filter { it.definingClass == track.type && it.type == STR }
        .distinctBy { it.memoryId() }.singleMemory("track language")
    val defaultTrack = owner.methods.filter { it.parameterTypes.isEmpty() && it.returnType == track.type }
        .toList().singleMemory("native default track selector")
    val modelField = defaultTrack.instructions().firstOrNull()?.fref()
        ?: throw PatchException("Subtitle memory: missing track model field.")
    requireMemory(modelField.definingClass == owner.type, "track model must belong to manager.")
    val model = classDefBy(modelField.type)
    val translated = model.methods.filter { it.parameterTypes.isEmpty() && it.returnType == TRACKS && it.containsText("&tlang=") }
        .toList().singleMemory("native translation list")
    val native = model.methods.filter { it.parameterTypes.isEmpty() && it.returnType == TRACKS && it.name != translated.name }
        .toList().singleMemory("native caption list")
    val translatedCode = translated.instructions()
    val url = translatedCode.mapIndexedNotNull { i, ins ->
        if (ins.sref() != "&tlang=") null else translatedCode.getOrNull(i - 2)?.fref()
    }.filter { it.definingClass == track.type && it.type == STR }.distinctBy { it.memoryId() }
        .singleMemory("translation source URL")
    val contextField = owner.fields.filter { it.type == CTX }.toList().singleMemory("manager context")
    val initializer = owner.methods.filter { m -> m.returnType == "V" && m.parameterTypes.size == 2 &&
        m.instructions().any { it.opcode == Opcode.IPUT_OBJECT && it.fref()?.memoryId() == modelField.memoryId() } &&
        m.instructions().any { it.mref()?.memoryId() == defaultTrack.memoryId() }
    }.toList().singleMemory("current-video track initialization")
    val code = initializer.instructions()
    val defaultIndex = code.indices.filter { code[it].mref()?.memoryId() == defaultTrack.memoryId() }.singleMemory("automatic default selection call")
    val enabledIndex = defaultIndex - 1
    requireMemory(code.getOrNull(enabledIndex)?.opcode == Opcode.NEW_INSTANCE, "native enabled event allocation changed.")
    val insertionIndex = (0 until enabledIndex).filter { i ->
        code[i].opcode == Opcode.IGET_OBJECT && code[i].fref()?.memoryId() == modelField.memoryId() &&
        code.getOrNull(i + 1)?.opcode == Opcode.IF_NEZ &&
        code.getOrNull(i + 2)?.opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) &&
        code.getOrNull(i + 3)?.opcode == Opcode.IGET_BOOLEAN
    }.singleMemory("native auto-enable decision") + 3
    val nullInstruction = code.firstOrNull() as? OneRegisterInstruction
    requireMemory(code.firstOrNull()?.opcode == Opcode.CONST_4 && nullInstruction != null &&
        code.getOrNull(1)?.opcode == Opcode.IPUT_OBJECT && code[1].fref()?.memoryId() == modelField.memoryId(),
        "initializer null-register contract changed.")
    val scratch = nullInstruction!!.registerA
    requireMemory((code[1] as TwoRegisterInstruction).registerA == scratch, "initializer null register mismatch.")
    requireMemory((code.first() as? WideLiteralInstruction)?.wideLiteral == 0L && scratch <= 15,
        "initializer scratch must be a null local.")
    for (method in listOf(setter, defaultTrack, initializer)) {
        requireMemory(!AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
            method.implementation!!.registerCount <= 16 &&
            method.implementation!!.registerCount > method.parameterTypes.size + 1,
            "host hook register encoding changed.")
    }
    val offsets = code.runningFold(0) { n, ins -> n + ins.codeUnits }
    val branchIndex = insertionIndex - 2
    val branch = code[branchIndex] as? OffsetInstruction
    requireMemory(branch != null && offsets[branchIndex] + branch.codeOffset == offsets[insertionIndex],
        "model-ready branch target changed.")
    val originalCall = code[defaultIndex] as? FiveRegisterInstruction
    val thisRegister = initializer.implementation!!.registerCount - 3
    requireMemory(code[defaultIndex].opcode == Opcode.INVOKE_VIRTUAL && originalCall?.registerCount == 1 &&
        originalCall.registerC == thisRegister && scratch < thisRegister &&
        code.getOrNull(defaultIndex + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
        code.getOrNull(defaultIndex + 2)?.opcode == Opcode.SGET_OBJECT &&
        (code[defaultIndex + 2] as? OneRegisterInstruction)?.registerA == scratch,
        "enabled branch data flow changed.")
    // Accessors cross the addon/host boundary only through validated public members.
    for (cls in listOf(owner, model, track)) requireMemory(AccessFlags.PUBLIC.isSet(cls.accessFlags), "host class is not public.")
    for (field in listOf(modelField, contextField, language, url)) {
        val definition = classDefBy(field.definingClass).fields.first { it.name == field.name && it.type == field.type }
        requireMemory(AccessFlags.PUBLIC.isSet(definition.accessFlags), "host field is not public.")
    }
    for (method in listOf(native, translated)) requireMemory(AccessFlags.PUBLIC.isSet(method.accessFlags), "track list method is not public.")
    val runtime = mutableClassDefBy(MEMORY)
    val methodNames = listOf("context", "language", "url", "nativeTracks", "translatedTracks")
    requireMemory(runtime.methods.none { m -> m.instructions().any { it.opcode == Opcode.CHECK_CAST &&
        (it as? ReferenceInstruction)?.reference.toString() in listOf(owner.type, track.type) } }, "patch already applied.")
    val stubs = methodNames.associateWith { name -> runtime.methods.filter { it.name == name && it.parameterTypes.toList() == listOf(OBJ) }
        .toList().singleMemory("runtime accessor $name") }
    fun bind(name: String, body: String) {
        val stub = stubs.getValue(name)
        val replacement = ImmutableMethod(stub.definingClass, stub.name, stub.parameters, stub.returnType,
            stub.accessFlags, stub.annotations, null, MutableMethodImplementation(2)).toMutable()
        replacement.addInstructionsWithLabels(0, body.trimIndent())
        runtime.methods.remove(stub)
        runtime.methods.add(replacement)
    }
    bind("context", """
        check-cast p0, ${owner.type}
        iget-object v0, p0, ${contextField.memoryId()}
        return-object v0
    """)
    bind("language", """
        check-cast p0, ${track.type}
        iget-object v0, p0, ${language.memoryId()}
        return-object v0
    """)
    bind("url", """
        check-cast p0, ${track.type}
        iget-object v0, p0, ${url.memoryId()}
        return-object v0
    """)
    fun listBody(method: Method) = """
        check-cast p0, ${owner.type}
        iget-object v0, p0, ${modelField.memoryId()}
        if-eqz v0, :done
        invoke-virtual {v0}, ${method.memoryId()}
        move-result-object v0
        :done
        return-object v0
    """
    bind("nativeTracks", listBody(native))
    bind("translatedTracks", listBody(translated))
    fun mutable(method: Method): MutableMethod = mutableClassDefBy(method.definingClass).methods
        .filter { it.memoryId() == method.memoryId() }.toList().singleMemory("mutable method")
    mutable(setter).addInstructions(0, "invoke-static {p0, p1, p2}, $MEMORY->onSelection($OBJ$OBJ$OBJ)V")
    mutable(defaultTrack).apply {
        requireMemory(implementation!!.registerCount >= 2, "default selector has no local register.")
        addInstructionsWithLabels(0, """
            invoke-static {p0}, $MEMORY->resolve($OBJ)$OBJ
            move-result-object v0
            if-eqz v0, :native_default
            check-cast v0, ${track.type}
            return-object v0
        """.trimIndent(), ExternalLabel("native_default", implementation!!.instructions.first()))
    }
    mutable(initializer).apply {
        val enabledInstruction = implementation!!.instructions[enabledIndex]
        addInstructionsWithLabels(insertionIndex, """
            invoke-virtual {p0}, ${defaultTrack.memoryId()}
            move-result-object v$scratch
            if-nez v$scratch, :remembered_captions
            const/4 v$scratch, 0x0
        """.trimIndent(), ExternalLabel("remembered_captions", enabledInstruction))
        // dexlib keeps existing labels attached to the old instruction. Retarget the
        // model-ready edge explicitly so it enters the new hook instead of skipping it.
        val readyRegister = (code[branchIndex] as OneRegisterInstruction).registerA
        implementation!!.replaceInstruction(branchIndex, BuilderInstruction21t(
            Opcode.IF_NEZ, readyRegister, implementation!!.newLabelForIndex(insertionIndex)))
        requireMemory((implementation!!.instructions[branchIndex] as BuilderInstruction21t)
            .target.location.index == insertionIndex, "model-ready edge skipped the hook.")
    }
}
