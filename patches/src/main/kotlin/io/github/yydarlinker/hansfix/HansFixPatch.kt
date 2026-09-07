package io.github.yydarlinker.hansfix

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val RUNTIME = "Lio/github/yydarlinker/hansfix/HansFixRuntime;"
private const val OFFICIAL = "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"
private const val BRIDGE = "hansfixAddonGetCaptionToggle"
private const val AUTO_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION"
private const val AUTO_MENU = "AUTO_TRANSLATE_SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val CONTEXT = "Landroid/content/Context;"
private const val BUILDER = "Lorg/chromium/net/UrlRequest\$Builder;"
private val logger = Logger.getLogger("HansFixAddon")

private fun fail(message: String): Nothing = throw PatchException("HansFix: $message")
private fun ensure(value: Boolean, message: String) { if (!value) fail(message) }
private fun <T> List<T>.unique(role: String): T {
    ensure(size == 1, "$role must match exactly once (found $size). Unsupported input or conflicting patches.")
    return single()
}
private fun MethodReference.id() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun FieldReference.id() = "$definingClass->$name:$type"
private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.literal() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
private fun Method.hasString(value: String) = implementation?.instructions?.any { it.literal() == value } == true
private fun MethodReference.params() = parameterTypes.map { it.toString() }

private data class ModelBindings(
    val type: String,
    val language: FieldReference,
    val vssId: FieldReference,
    val display: FieldReference,
    val url: FieldReference,
    val translated: MethodReference,
)
private data class RowHook(val index: Int, val row: Int, val scratch: Int)
private data class MenuBindings(
    val owner: String,
    val method: MethodReference,
    val itemType: String,
    val backing: FieldReference,
    val rows: List<RowHook>,
    val summaryIndex: Int,
    val summaryRegister: Int,
)
private data class NetworkHook(val owner: String, val method: MethodReference, val index: Int, val urlRegister: Int)

@Suppress("unused")
val hansFixPatch = bytecodePatch(
    name = "HansFix - Simplified Chinese captions",
    description = "Use with official Captions in expert mode. Maps Traditional auto-translation to Simplified and changes UI labels only. YouTube 21.07.247 (1561056418).",
    default = false,
) {
    compatibleWith(Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0033,
        signatures = setOf(
            "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
            "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
        ),
        targets = listOf(AppTarget(version = "21.07.247", minSdk = 28)),
    ))
    // No cross-bundle Kotlin dependency and no copied official extension.
    extendWith("extensions/hansfix-addon.mpe")
    execute {
        ensure(packageMetadata.packageName == "com.google.android.youtube" &&
            packageMetadata.versionName == "21.07.247" && packageMetadata.versionCode == "1561056418",
            "requires original YouTube 21.07.247 / 1561056418; do not patch an installed Morphe APK again.")
        val runtime = classDefBy(RUNTIME)
        ensure(runtime.methods.none { it.name == "applyCaptionMenuLabel" }, "already applied or duplicate addon selected.")
    }
    // Every patch execute (and extension merge) has completed at this point.
    // All bindings are resolved again from the current context, not cached indices.
    finalize {
        installAddon()
    }
}

private fun BytecodePatchContext.installAddon() {
    val official = classDefByOrNull(OFFICIAL)
        ?: fail("official Captions is required in the SAME expert-mode operation; its extension is missing.")
    val toggle = official.fields.filter {
        it.name == "SET_CAPTION_COOKIES" && it.type == "Z" && AccessFlags.STATIC.isSet(it.accessFlags)
    }.toList().unique("official Cookie toggle cache")
    ensure(official.methods.none { it.name == BRIDGE }, "toggle bridge already exists; duplicate addon/input.")

    val network = findNetworkHook()
    val model = findModel()
    val menus = findMenus(model)
    ensure(menus.size == 2 && menus.sumOf { it.rows.size } == 4, "expected two subtitle menus and four row construction sites.")
    val itemType = menus.map { it.itemType }.distinct().unique("caption menu item type")
    val item = classDefBy(itemType)
    ensure(AccessFlags.PUBLIC.isSet(item.accessFlags), "menu item class must be public.")
    val trackField = item.fields.filter { it.type == model.type && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        .toList().unique("menu callback original-track field")
    val title = findTitleField(itemType)

    // Complete every structural check before changing any host bytecode.
    val runtime = mutableClassDefBy(RUNTIME)
    ensure(runtime.methods.none { it.name in setOf("labelForTrack", "applyCaptionMenuLabel", "hasNativeSimplifiedCaption", "autoTranslateMenuText") },
        "generated helpers already exist.")
    val isEnabled = runtime.methods.filter { it.name == "isEnabled" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        .toList().unique("runtime fail-closed toggle stub")
    ensure(runtime.methods.count { it.name == "rewriteTraditionalCaptionUrl" && it.params() == listOf(STRING) && it.returnType == STRING } == 1,
        "runtime rewrite API missing.")
    ensure(runtime.methods.count { it.name == "captionMenuLabel" && it.params() == listOf(STRING, "Z", STRING, STRING, "Z") && it.returnType == STRING } == 1,
        "runtime UI policy API missing.")

    mutableClassDefBy(OFFICIAL).addStatic(BRIDGE, emptyList(), "Z", 1,
        "sget-boolean v0, ${toggle.id()}\nreturn v0")
    runtime.methods.remove(isEnabled)
    runtime.addStatic("isEnabled", emptyList(), "Z", 1,
        "invoke-static {}, $OFFICIAL->$BRIDGE()Z\nmove-result v0\nreturn v0")
    installTypedUiHelpers(runtime, model, itemType, trackField, title)

    val request = mutableClassDefBy(network.owner).methods.filter { it.id() == network.method.id() }.toList().unique("mutable network method")
    request.addInstructions(network.index,
        "invoke-static {v${network.urlRegister}}, $RUNTIME->rewriteTraditionalCaptionUrl($STRING)$STRING\nmove-result-object v${network.urlRegister}")
    for (menu in menus) {
        val method = mutableClassDefBy(menu.owner).methods.filter { it.id() == menu.method.id() }.toList().unique("mutable menu method")
        val edits = menu.rows.map { it.index + 1 to it } + listOf(menu.summaryIndex to null)
        for ((index, row) in edits.sortedByDescending { it.first }) {
            if (row == null) {
                method.replaceInstruction(index,
                    "invoke-static {v${menu.summaryRegister}}, $RUNTIME->autoTranslateMenuText(${model.type})$STRING")
            } else {
                method.addInstructions(index,
                    "iget-object v${row.scratch}, p0, ${menu.backing.id()}\n" +
                    "invoke-static {v${row.row}, v${row.scratch}}, $RUNTIME->applyCaptionMenuLabel($itemType$LIST)V")
            }
        }
    }
    logger.info("HansFix verified structure: officialToggle=1, network=1, menuRows=4, summaries=2; UI-only, original tracks unchanged.")
}

private fun BytecodePatchContext.findNetworkHook(): NetworkHook {
    val matches = mutableListOf<NetworkHook>()
    classDefForEach { cls ->
        cls.methods.forEach { method ->
            if (method.implementation?.instructions?.any {
                it.methodRef()?.let { ref -> ref.definingClass == OFFICIAL && ref.name == "setRequireCookies" && ref.params() == listOf(STRING) } == true
            } != true) return@forEach
            val code = method.code()
            code.forEachIndexed { index, instruction ->
                val ref = instruction.methodRef() ?: return@forEachIndexed
                if (ref.definingClass != OFFICIAL || ref.name != "setRequireCookies" || ref.params() != listOf(STRING)) return@forEachIndexed
                val call = instruction as? FiveRegisterInstruction ?: fail("unsupported Cookie call encoding.")
                ensure(instruction.opcode == Opcode.INVOKE_STATIC && call.registerCount == 1, "Cookie call shape changed.")
                val next = code.getOrNull(index + 1)
                val builder = next?.methodRef()
                ensure(builder?.definingClass == "Lorg/chromium/net/CronetEngine;" && builder.name == "newUrlRequestBuilder" && builder.returnType == BUILDER,
                    "Cookie check must immediately precede the Cronet builder.")
                ensure(next is FiveRegisterInstruction && next.registerD == call.registerC, "Cookie and builder URL registers differ.")
                ensure(cls.methods.any { it.hasString("Cookie") && it.hasString("User-Agent") && it.code().any { ins ->
                    ins.methodRef()?.let { it.definingClass == OFFICIAL && it.name == "getCookies" } == true
                } }, "official Cookie request-header helper is missing.")
                ensure(code.none { it.methodRef()?.definingClass == RUNTIME }, "network hook already applied.")
                matches += NetworkHook(cls.type, method, index, call.registerC)
            }
        }
    }
    return matches.unique("official caption request hook (select official Captions)")
}

private fun BytecodePatchContext.findModel(): ModelBindings {
    val model = getAllClassesWithString(AUTO_OPTION).map { classDefBy(it.type) }.filter {
        "Landroid/os/Parcelable;" in it.interfaces && it.methods.any { m -> m.parameterTypes.isEmpty() && m.returnType == "Z" && m.hasString(AUTO_OPTION) }
    }.unique("caption track model")
    val autoCheck = model.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Z" && it.hasString(AUTO_OPTION) }.toList().unique("auto-translation sentinel check")
    ensure(AccessFlags.PUBLIC.isSet(model.accessFlags), "caption model class must be public.")
    val language = autoCheck.code().mapNotNull { it.fieldRef() }.filter { it.definingClass == model.type && it.type == STRING }
        .distinctBy { it.id() }.unique("track language code")
    val translated = model.methods.filter { m -> m.parameterTypes.isEmpty() && m.returnType == "Z" && m.hasString("t") && m.code().any {
        it.methodRef()?.let { ref -> ref.definingClass == STRING && ref.name == "startsWith" } == true
    } }.toList().unique("translated-track predicate")
    ensure(AccessFlags.PUBLIC.isSet(translated.accessFlags), "translated predicate must be public.")
    val vss = translated.code().mapNotNull { it.fieldRef() }.filter { it.definingClass == model.type && it.type == STRING }
        .distinctBy { it.id() }.unique("translation identity field")
    val text = model.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING }.toList().unique("track toString")
    val display = text.code().mapNotNull { it.fieldRef() }.filter { it.definingClass == model.type && it.type == "Ljava/lang/CharSequence;" }
        .distinctBy { it.id() }.unique("original track display field")
    val urls = mutableListOf<FieldReference>()
    getAllClassesWithString("&tlang=").map { classDefBy(it.type) }.forEach { cls ->
        cls.methods.filter { it.hasString("&tlang=") }.forEach { m ->
            val code = m.code()
            code.forEachIndexed { index, ins ->
                if (ins.literal() != "&tlang=") return@forEachIndexed
                val load = code.getOrNull(index - 2)
                val append = code.getOrNull(index - 1)
                val field = load?.fieldRef()
                if (load?.opcode == Opcode.IGET_OBJECT && field?.definingClass == model.type && field.type == STRING &&
                    append?.methodRef()?.let { it.definingClass == "Ljava/lang/StringBuilder;" && it.name == "append" && it.params() == listOf(STRING) } == true &&
                    load is OneRegisterInstruction && append is FiveRegisterInstruction && load.registerA == append.registerD) urls += field
            }
        }
    }
    val url = urls.distinctBy { it.id() }.unique("source caption URL used to build translated requests")
    for (field in listOf(language, vss, display, url)) {
        val definition = model.fields.first { it.name == field.name && it.type == field.type }
        ensure(AccessFlags.PUBLIC.isSet(definition.accessFlags), "model field access is not public; unsupported target.")
    }
    return ModelBindings(model.type, language, vss, display, url, translated)
}

private fun BytecodePatchContext.findMenus(model: ModelBindings): List<MenuBindings> {
    val matches = mutableListOf<MenuBindings>()
    getAllClassesWithString(AUTO_MENU).map { classDefBy(it.type) }.forEach { cls ->
        cls.methods.filter { it.returnType == "Landroid/widget/ListAdapter;" && it.parameterTypes.isEmpty() && it.hasString(AUTO_MENU) }.forEach { method ->
            val code = method.code()
            val rows = code.mapIndexedNotNull { index, ins ->
                val ref = ins.methodRef() ?: return@mapIndexedNotNull null
                if (ref.name != "<init>" || ref.params().size != 3 || ref.params().take(2) != listOf(CONTEXT, model.type)) return@mapIndexedNotNull null
                ensure(ins.opcode == Opcode.INVOKE_DIRECT && ins is FiveRegisterInstruction && ins.registerCount == 4,
                    "unsupported menu constructor encoding.")
                val call = ins as FiveRegisterInstruction
                val next = code.getOrNull(index + 1)
                ensure(next is OneRegisterInstruction && next.registerA == call.registerD && next.opcode.setsRegister(),
                    "menu Context register is still live; refusing unsafe register reuse.")
                ensure(call.registerC != call.registerD && call.registerE != call.registerD, "menu register alias collision.")
                Triple(ref.definingClass, index, RowHook(index, call.registerC, call.registerD))
            }
            if (rows.isEmpty()) return@forEach
            ensure(rows.size == 2, "each subtitle menu must have two row constructors.")
            val itemType = rows.map { it.first }.distinct().unique("menu item binding")
            val backing = code.mapNotNull { it.fieldRef() }.filter {
                it.definingClass == cls.type && it.type in setOf(LIST, "Ljava/util/ArrayList;")
            }.distinctBy { it.id() }.unique("menu backing List field")
            ensure(method.implementation!!.registerCount - 1 <= 15, "menu this register cannot encode iget-object safely.")
            val summaries = code.mapIndexedNotNull { index, ins ->
                val ref = ins.methodRef()
                if (ref?.definingClass == model.type && ref.name == "toString" && ref.params().isEmpty() && ref.returnType == STRING) index else null
            }
            val summary = summaries.unique("selected-translation summary in each menu")
            val call = code[summary] as? FiveRegisterInstruction ?: fail("unsupported summary call encoding.")
            ensure(call.registerCount == 1 && code.getOrNull(summary + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT,
                "summary return shape changed.")
            val store = code.getOrNull(summary + 2)
            ensure(store?.opcode == Opcode.IPUT_OBJECT && store.fieldRef()?.type == STRING && store is TwoRegisterInstruction &&
                store.registerA == (code[summary + 1] as OneRegisterInstruction).registerA,
                "summary text is not stored in a UI String field.")
            val uiOwners = mutableSetOf<String>()
            var uiClass: ClassDef? = classDefBy(itemType)
            repeat(4) {
                uiClass?.let { current ->
                    uiOwners += current.type
                    uiClass = current.superclass?.let { classDefByOrNull(it) }
                }
            }
            ensure(store!!.fieldRef()!!.definingClass in uiOwners,
                "summary destination must belong to the menu UI hierarchy, not a shared model.")
            ensure(code.none { it.methodRef()?.definingClass == RUNTIME }, "menu hook already exists.")
            matches += MenuBindings(cls.type, method, itemType, backing, rows.map { it.third }, summary, call.registerC)
        }
    }
    ensure(matches.size == 2, "two automatic-translation subtitle menus required (found ${matches.size}).")
    return matches
}

private fun BytecodePatchContext.findTitleField(itemType: String): FieldReference {
    var cls: ClassDef? = classDefBy(itemType)
    repeat(4) {
        val current = cls ?: fail("menu title superclass not found.")
        for (ctor in current.methods.filter { it.name == "<init>" && it.params() == listOf(STRING) }) {
            val fields = ctor.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.fieldRef() }
                .filter { it.definingClass == current.type && it.type == STRING }.distinctBy { it.id() }
            if (fields.size == 1) {
                val definition = current.fields.first { it.name == fields[0].name && it.type == STRING }
                ensure(AccessFlags.PUBLIC.isSet(definition.accessFlags), "UI title field must be public.")
                return fields[0]
            }
        }
        cls = current.superclass?.let { classDefByOrNull(it) }
    }
    fail("could not bind the menu UI title field; no global model rewrite allowed.")
}

private fun MutableClass.addStatic(name: String, parameters: List<String>, result: String, registers: Int, body: String) {
    ensure(methods.none { it.name == name && it.params() == parameters }, "duplicate generated method $name.")
    val method = ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(registers)).toMutable()
    method.addInstructionsWithLabels(0, body.trimIndent())
    methods.add(method)
}

private fun installTypedUiHelpers(runtime: MutableClass, model: ModelBindings, item: String, track: FieldReference, title: FieldReference) {
    val type = model.type
    runtime.addStatic("labelForTrack", listOf(type, "Z"), STRING, 7, """
        if-nez p0, :read_label
        const/4 v3, 0x0
        return-object v3
        :read_label
        invoke-virtual {p0}, $type->toString()$STRING
        move-result-object v3
        iget-object v0, p0, ${model.display.id()}
        if-eqz v0, :original_label
        iget-object v0, p0, ${model.vssId.id()}
        if-eqz v0, :original_label
        invoke-virtual {p0}, ${model.translated.id()}
        move-result v1
        iget-object v0, p0, ${model.language.id()}
        iget-object v2, p0, ${model.url.id()}
        move v4, p1
        invoke-static/range {v0 .. v4}, $RUNTIME->captionMenuLabel(${STRING}Z${STRING}${STRING}Z)$STRING
        move-result-object v3
        :original_label
        return-object v3
    """)
    runtime.addStatic("hasNativeSimplifiedCaption", listOf(LIST), "Z", 5, """
        const/4 v0, 0x0
        if-eqz p0, :done
        invoke-interface {p0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
        move-result-object p0
        :loop
        invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z
        move-result v1
        if-eqz v1, :done
        invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v1
        check-cast v1, $type
        if-eqz v1, :loop
        const-string v2, "zh-Hans"
        iget-object v3, v1, ${model.language.id()}
        invoke-virtual {v2, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v2
        if-eqz v2, :loop
        iget-object v2, v1, ${model.vssId.id()}
        if-eqz v2, :loop
        invoke-virtual {v1}, ${model.translated.id()}
        move-result v2
        if-eqz v2, :loop
        const/4 v0, 0x1
        :done
        return v0
    """)
    runtime.addStatic("applyCaptionMenuLabel", listOf(item, LIST), "V", 5, """
        invoke-static {}, $RUNTIME->isEnabled()Z
        move-result v0
        if-eqz v0, :done
        if-eqz p0, :done
        iget-object v0, p0, ${track.id()}
        if-eqz v0, :done
        const-string v1, "zh-Hant"
        iget-object v2, v0, ${model.language.id()}
        invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v1
        if-eqz v1, :done
        invoke-static {p1}, $RUNTIME->hasNativeSimplifiedCaption($LIST)Z
        move-result v1
        invoke-static {v0, v1}, $RUNTIME->labelForTrack(${type}Z)$STRING
        move-result-object v0
        if-eqz v0, :done
        iput-object v0, p0, ${title.id()}
        :done
        return-void
    """)
    runtime.addStatic("autoTranslateMenuText", listOf(type), STRING, 2, """
        const/4 v0, 0x0
        invoke-static {p0, v0}, $RUNTIME->labelForTrack(${type}Z)$STRING
        move-result-object v0
        return-object v0
    """)
}
