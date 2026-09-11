package io.github.yydarlinker.hansfix

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val DIAG = "Lio/github/yydarlinker/hansfix/diagnostics/CaptionDiagnosticsRuntime;"
private const val DOBJ = "Ljava/lang/Object;"
private const val DSTR = "Ljava/lang/String;"
private const val DBUF = "Ljava/nio/ByteBuffer;"
private const val DREQ = "Lorg/chromium/net/UrlRequest;"
private const val DINFO = "Lorg/chromium/net/UrlResponseInfo;"
private const val DCALLBACK = "Lorg/chromium/net/UrlRequest\$Callback;"
private const val DBUILDER = "Lorg/chromium/net/UrlRequest\$Builder;"
private const val PREFS = "morphe_addon_prefs.xml"
private const val PREF_CLASS = "io.github.yydarlinker.hansfix.diagnostics.CaptionDiagnosticsPreference"
private fun dcheck(ok: Boolean, message: String) { if (!ok) throw PatchException("Caption diagnostics: $message") }
private fun <T> List<T>.done(role: String): T { dcheck(size == 1, "$role must match once (found $size)"); return single() }
private fun MethodReference.did() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.dp() = parameterTypes.map { it.toString() }
private fun Method.dc() = implementation?.instructions?.toList() ?: emptyList()
private fun Instruction.dr() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.dargs(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> throw PatchException("Caption diagnostics: unsupported invoke encoding")
}
private fun Instruction.dstatic(target: String): String = when (this) {
    is FiveRegisterInstruction -> "invoke-static {${dargs().joinToString(", ") { "v$it" }}}, $target"
    is RegisterRangeInstruction -> "invoke-static/range {v$startRegister .. v${startRegister + registerCount - 1}}, $target"
    else -> throw PatchException("Caption diagnostics: unsupported invoke encoding")
}

/** Official v1.42+ add-on declaration protocol; no cross-bundle preference objects/classes. */
private val diagnosticsPreferences = resourcePatch {
    execute {
        val file = get(PREFS)
        if (!file.exists()) file.writeText("""<?xml version="1.0" encoding="utf-8"?><morphe-add-on-preferences xmlns:android="http://schemas.android.com/apk/res/android"/>""")
        document(PREFS).use { doc ->
            val root = doc.documentElement
            dcheck(root.tagName == "morphe-add-on-preferences", "invalid existing add-on preference declaration")
            dcheck(doc.getElementsByTagName(PREF_CLASS).length == 0, "diagnostics preference already declared")
            val screen = doc.createElement("screen")
            val pref = doc.createElement(PREF_CLASS)
            val ns = "http://schemas.android.com/apk/res/android"
            pref.setAttributeNS(ns, "android:key", "yydarlinker_caption_diagnostics")
            pref.setAttributeNS(ns, "android:title", "字幕诊断（Caption diagnostics）")
            pref.setAttributeNS(ns, "android:summary", "手动开始、查看、复制和清空；不记录字幕正文或凭证")
            pref.setAttributeNS(ns, "android:persistent", "false")
            screen.appendChild(pref)
            root.appendChild(screen)
        }
    }
}

@Suppress("unused")
val captionDiagnosticsPatch = bytecodePatch(
    name = "Caption request diagnostics",
    description = "Opt-in, memory-only caption request diagnostics with a Morphe settings viewer/copy/clear entry. Does not fix, retry, or change captions. Requires official Captions and settings add-on support (1.42.0+). Structure checked on YouTube 21.13.164; phone verification pending.",
    default = false,
) {
    compatibleWith(Compatibility(name = "YouTube", packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK_REQUIRED, appIconColor = 0xFF0033,
        signatures = setOf("5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6", "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"),
        targets = listOf(AppTarget(version = null, minSdk = 28))))
    dependsOn(captionAddonExtensionPatch, diagnosticsPreferences)
    execute {
        dcheck(packageMetadata.packageName == "com.google.android.youtube", "requires original YouTube APK")
    }
    finalize { installDiagnostics() }
}

private data class DSite(val method: Method, val index: Int, val call: Instruction)
private fun BytecodePatchContext.installDiagnostics() {
    // Check the official selected extension without assuming a bundle load order.
    val official = getAllClassesWithString("api/timedtext").any { it.type == "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;" }
    dcheck(official, "select official Captions in the same session; official settings support 1.42.0+ is required")
    val sites = mutableListOf<DSite>()
    classDefForEach { cls -> cls.methods.forEach { method ->
        val code = method.dc()
        if (method.dp().size == 1 && method.returnType.startsWith("L") &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags) &&
            code.any { it.dr()?.let { r -> r.definingClass == "Lorg/chromium/net/UploadDataProviders;" && r.name == "create" && r.dp() == listOf(DBUF) } == true }) {
            code.forEachIndexed { index, ins ->
                if (ins.dr()?.let { r -> r.definingClass == "Lorg/chromium/net/CronetEngine;" && r.name == "newUrlRequestBuilder" && r.returnType == DBUILDER && r.dp() == listOf(DSTR, DCALLBACK, "Ljava/util/concurrent/Executor;") } == true)
                    sites += DSite(method, index, ins)
            }
        }
    } }
    val net = sites.done("native caption network builder")
    val args = net.call.dargs()
    dcheck(args.size == 4 && args.all { it < 16 }, "builder registers unsupported")
    val code = net.method.dc()
    dcheck(code.none { it.dr()?.definingClass == DIAG }, "diagnostics already installed")
    val result = code.getOrNull(net.index + 1)
    dcheck(result?.opcode == Opcode.MOVE_RESULT_OBJECT && result is OneRegisterInstruction, "builder result changed")
    val builderReg = (result as OneRegisterInstruction).registerA
    dcheck(builderReg < 16 && builderReg != args[2], "builder result overwrites callback")
    val constructors = code.take(net.index).filter { ins ->
        ins.opcode == Opcode.INVOKE_DIRECT && ins.dr()?.name == "<init>" &&
            ins.dargs().firstOrNull() == args[2] && classDefBy(ins.dr()!!.definingClass).superclass == DCALLBACK
    }
    val callbackType = constructors.done("request callback constructor").dr()!!.definingClass
    val callback = classDefBy(callbackType)
    fun event(name: String, params: List<String>) = callback.methods.filter { it.name == name && it.dp() == params && it.returnType == "V" && it.implementation != null }.toList().done(name)
    val response = event("onResponseStarted", listOf(DREQ, DINFO))
    val read = event("onReadCompleted", listOf(DREQ, DINFO, DBUF))
    val success = event("onSucceeded", listOf(DREQ, DINFO))
    val failed = event("onFailed", listOf(DREQ, DINFO, "Lorg/chromium/net/CronetException;"))
    val cancelled = event("onCanceled", listOf(DREQ, DINFO))
    val redirect = event("onRedirectReceived", listOf(DREQ, DINFO, DSTR))
    // Only the callback's InputStream adapter: do not instrument unrelated video traffic/readers.
    val readers = callback.fields.map { it.type }.distinct().filter { it.startsWith("L") && it !in setOf(DOBJ, "Ljava/io/IOException;") }
        .map { classDefBy(it) }.filter { it.superclass == "Ljava/io/InputStream;" && it.fields.any { f -> f.type == DREQ } && it.fields.any { f -> f.type == callbackType } }
    val reader = readers.done("callback input stream")
    val reads = reader.methods.flatMap { method -> method.dc().mapIndexedNotNull { index, ins ->
        if (ins.dr()?.let { it.definingClass == DREQ && it.name == "read" && it.dp() == listOf(DBUF) && it.returnType == "V" } == true) DSite(method, index, ins) else null
    } }
    dcheck(reads.size == 1, "expected one observed read dispatch")
    val headerMethods = code.mapNotNull { it.dr() }.filter { DBUILDER in it.dp() && it.definingClass != DBUILDER }
        .distinctBy { it.did() }.map { ref -> classDefBy(ref.definingClass).methods.filter { it.did() == ref.did() }.toList().done("header writer") }
    val headers = headerMethods.flatMap { method -> method.dc().mapIndexedNotNull { index, ins ->
        if (ins.dr()?.let { it.definingClass == DBUILDER && it.name == "addHeader" && it.dp() == listOf(DSTR, DSTR) && it.returnType == DBUILDER } == true) DSite(method, index, ins) else null
    } }
    dcheck(headers.size >= 2 && headers.size <= 8, "header observation sites changed")
    val runtime = mutableClassDefBy(DIAG)
    dcheck(runtime.methods.none { it.name.startsWith("diagnosticsBridge") }, "generated bridge exists")
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).methods.filter { it.did() == method.did() }.toList().done("mutable method")
    fun bridge(name: String, params: List<String>, returns: String, registers: Int, body: String) {
        val method = ImmutableMethod(DIAG, name, params.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), null, MutableMethodImplementation(registers)).toMutable()
        method.addInstructionsWithLabels(0, body.trimIndent())
        runtime.methods.add(method)
    }
    // No raw response URL, headers, error text, or body is exported. Runtime allowlists metadata.
    bridge("diagnosticsBridgeResponse", listOf(DOBJ, DREQ, DINFO), "V", 5, """
        :try_start
        invoke-static {}, $DIAG->isRecording()Z
        move-result v0
        if-eqz v0, :done
        if-eqz p2, :done
        invoke-virtual {p2}, $DINFO->getHttpStatusCode()I
        move-result v0
        invoke-virtual {p2}, $DINFO->getAllHeaders()Ljava/util/Map;
        move-result-object v1
        invoke-static {p0, v0, v1}, $DIAG->onResponseHeaders(${DOBJ}ILjava/util/Map;)V
        :try_end
        :done
        return-void
        :error
        move-exception v0
        return-void
        .catch Ljava/lang/Throwable; {:try_start .. :try_end} :error
    """)
    bridge("diagnosticsBridgeRead", listOf(DOBJ, DOBJ, DOBJ, DBUF), "V", 4,
        "invoke-static {p0, p1, p3}, $DIAG->onRead($DOBJ$DOBJ$DBUF)V\nreturn-void")
    for ((name, kind) in listOf("Success" to 0, "Failed" to 1, "Cancelled" to 2)) {
        bridge("diagnosticsBridge$name", listOf(DOBJ), "V", 2,
            "const/4 v0, $kind\ninvoke-static {p0, v0}, $DIAG->onTerminal(${DOBJ}I)V\nreturn-void")
    }
    bridge("diagnosticsBridgeHeader", listOf(DBUILDER, DSTR, DSTR), DBUILDER, 3, """
        invoke-static {p0, p1, p2}, $DIAG->onHeader($DOBJ$DSTR$DSTR)V
        invoke-virtual {p0, p1, p2}, $DBUILDER->addHeader($DSTR$DSTR)$DBUILDER
        move-result-object p0
        return-object p0
    """)
    for (site in headers) mutable(site.method).replaceInstruction(site.index,
        site.call.dstatic("$DIAG->diagnosticsBridgeHeader($DBUILDER$DSTR$DSTR)$DBUILDER"))
    for (site in reads) {
        val regs = site.call.dargs()
        dcheck(regs.size == 2, "read dispatch arity changed")
        mutable(site.method).addInstructions(site.index, site.call.dstatic("$DIAG->beforeRead($DOBJ$DBUF)V"))
    }
    mutable(response).addInstructions(0, "invoke-static/range {p0 .. p2}, $DIAG->diagnosticsBridgeResponse($DOBJ$DREQ$DINFO)V")
    mutable(read).addInstructions(0, "invoke-static/range {p0 .. p3}, $DIAG->diagnosticsBridgeRead($DOBJ$DOBJ$DOBJ$DBUF)V")
    for ((method, name) in listOf(success to "Success", failed to "Failed", cancelled to "Cancelled"))
        mutable(method).addInstructions(0, "invoke-static/range {p0 .. p0}, $DIAG->diagnosticsBridge$name($DOBJ)V")
    mutable(redirect).addInstructions(0, "invoke-static/range {p0 .. p0}, $DIAG->onRedirect($DOBJ)V")
    // Resolve in finalize after all execute mutations; insert later index first.
    mutable(net.method).apply {
        addInstructions(net.index + 2, "invoke-static {v$builderReg, v${args[2]}}, $DIAG->onBuilder($DOBJ$DOBJ)V")
        addInstructions(net.index, "invoke-static {v${args[2]}, v${args[1]}}, $DIAG->onRequest($DOBJ$DSTR)V")
    }
}
