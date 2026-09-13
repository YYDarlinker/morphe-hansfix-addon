package io.github.yydarlinker.hansfix

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val CUA_OFFICIAL = "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"
private const val CUA_DIAG = "Lio/github/yydarlinker/hansfix/diagnostics/CaptionDiagnosticsRuntime;"
private const val CUA_STR = "Ljava/lang/String;"
private const val CUA_BUF = "Ljava/nio/ByteBuffer;"
private const val CUA_CALLBACK = "Lorg/chromium/net/UrlRequest\$Callback;"
private const val CUA_BUILDER = "Lorg/chromium/net/UrlRequest\$Builder;"

private fun cuaCheck(ok: Boolean, message: String) {
    if (!ok) throw PatchException("Caption Web UA experiment: $message")
}

private fun <T> List<T>.cuaDone(role: String): T {
    cuaCheck(size == 1, "$role must match once (found $size)")
    return single()
}

private fun MethodReference.cuaId() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.cuaParams() = parameterTypes.map { it.toString() }

private fun Method.cuaCode() = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.cuaRef() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.cuaArgs(): List<Int> = when (this) {
    is FiveRegisterInstruction ->
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction ->
        (startRegister until startRegister + registerCount).toList()
    else -> throw PatchException("Caption Web UA experiment: unsupported invoke encoding")
}

private data class CuaSite(val method: Method, val index: Int, val call: Instruction)

@Suppress("unused")
val captionWebUaExperimentPatch = bytecodePatch(
    name = "Caption Web UA experiment",
    description = "Experimental A/B patch for YouTube 21.13.164. When Morphe Caption Cookies are active for a timedtext request, keeps every observed User-Agent write on that native caption request path aligned with Morphe's desktop Chrome UA instead of allowing a later Android UA write to win. Requires official Captions; select Caption request diagnostics as well when testing.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            name = "YouTube",
            packageName = "com.google.android.youtube",
            apkFileType = ApkFileType.APK_REQUIRED,
            appIconColor = 0xFF0033,
            signatures = setOf(
                "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
                "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
            ),
            targets = listOf(AppTarget(version = "21.13.164", minSdk = 28)),
        )
    )

    execute {
        cuaCheck(
            packageMetadata.packageName == "com.google.android.youtube",
            "requires original YouTube APK",
        )
    }

    // Resolve after all execute-stage mutations so this can coexist with the
    // official Captions patch and with our optional diagnostics instrumentation
    // regardless of bundle load order.
    finalize { installCaptionWebUaExperiment() }
}

private fun BytecodePatchContext.installCaptionWebUaExperiment() {
    val officialExtensionPresent = getAllClassesWithString("api/timedtext")
        .any { it.type == CUA_OFFICIAL }
    cuaCheck(
        officialExtensionPresent,
        "official Morphe caption-cookie runtime was not found; select official Captions in the same session",
    )

    // Use the same structural anchor as Caption request diagnostics: the native
    // caption request builder creates an UploadDataProvider from ByteBuffer and
    // a Cronet UrlRequest.Builder in the same public-final method.
    val builders = mutableListOf<CuaSite>()
    classDefForEach { cls ->
        cls.methods.forEach { method ->
            val code = method.cuaCode()
            if (
                method.cuaParams().size == 1 &&
                method.returnType.startsWith("L") &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                AccessFlags.FINAL.isSet(method.accessFlags) &&
                code.any { ins ->
                    ins.cuaRef()?.let { ref ->
                        ref.definingClass == "Lorg/chromium/net/UploadDataProviders;" &&
                            ref.name == "create" &&
                            ref.cuaParams() == listOf(CUA_BUF)
                    } == true
                }
            ) {
                code.forEachIndexed { index, ins ->
                    if (
                        ins.cuaRef()?.let { ref ->
                            ref.definingClass == "Lorg/chromium/net/CronetEngine;" &&
                                ref.name == "newUrlRequestBuilder" &&
                                ref.returnType == CUA_BUILDER &&
                                ref.cuaParams() == listOf(
                                    CUA_STR,
                                    CUA_CALLBACK,
                                    "Ljava/util/concurrent/Executor;",
                                )
                        } == true
                    ) {
                        builders += CuaSite(method, index, ins)
                    }
                }
            }
        }
    }

    val net = builders.cuaDone("native caption network builder")
    val netCode = net.method.cuaCode()

    // Methods called from the native caption builder that accept the Cronet
    // builder are the local header writers. This intentionally includes the
    // helper injected by Morphe's official Caption Cookies patch.
    val headerMethods = netCode.mapNotNull { it.cuaRef() }
        .filter { CUA_BUILDER in it.cuaParams() && it.definingClass != CUA_BUILDER }
        .distinctBy { it.cuaId() }
        .map { ref ->
            classDefBy(ref.definingClass).methods
                .filter { it.cuaId() == ref.cuaId() }
                .toList()
                .cuaDone("header writer")
        }

    fun isHeaderDispatch(ref: MethodReference): Boolean {
        val raw = ref.definingClass == CUA_BUILDER &&
            ref.name == "addHeader" &&
            ref.cuaParams() == listOf(CUA_STR, CUA_STR) &&
            ref.returnType == CUA_BUILDER
        val diagnosed = ref.definingClass == CUA_DIAG &&
            ref.name == "diagnosticsBridgeHeader" &&
            ref.cuaParams() == listOf(CUA_BUILDER, CUA_STR, CUA_STR) &&
            ref.returnType == CUA_BUILDER
        return raw || diagnosed
    }

    val headerSites = headerMethods.flatMap { method ->
        method.cuaCode().mapIndexedNotNull { index, ins ->
            if (ins.cuaRef()?.let(::isHeaderDispatch) == true) {
                CuaSite(method, index, ins)
            } else {
                null
            }
        }
    }

    cuaCheck(headerSites.size in 2..8, "header dispatch sites changed (found ${headerSites.size})")

    val hostClass = mutableClassDefBy(net.method.definingClass)
    val helperName = "patch_selectCaptionWebUaHeaderValue"
    cuaCheck(hostClass.methods.none { it.name == helperName }, "experiment helper already installed")

    val helper = ImmutableMethod(
        net.method.definingClass,
        helperName,
        listOf(
            ImmutableMethodParameter(CUA_STR, null, "name"),
            ImmutableMethodParameter(CUA_STR, null, "value"),
        ),
        CUA_STR,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        emptySet(),
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $CUA_OFFICIAL->getRequireCookies()Z
                move-result v0
                if-eqz v0, :done

                const-string v0, "User-Agent"
                invoke-virtual { v0, p0 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :done

                invoke-static { }, $CUA_OFFICIAL->getUserAgent()Ljava/lang/String;
                move-result-object p1

                :done
                return-object p1
            """.trimIndent(),
        )
    }
    hostClass.methods.add(helper)

    val helperDescriptor =
        "${net.method.definingClass}->$helperName($CUA_STR$CUA_STR)$CUA_STR"

    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).methods
        .filter { it.cuaId() == method.cuaId() }
        .toList()
        .cuaDone("mutable header writer")

    // Insert before every candidate header dispatch, in descending index order
    // per method so instruction positions remain stable. Cookie and all other
    // headers pass through unchanged; only User-Agent is conditionally replaced.
    headerSites.groupBy { it.method.cuaId() }.values.forEach { sites ->
        val method = mutable(sites.first().method)
        sites.sortedByDescending { it.index }.forEach { site ->
            val regs = site.call.cuaArgs()
            cuaCheck(regs.size == 3, "header dispatch arity changed")
            val nameReg = regs[1]
            val valueReg = regs[2]
            cuaCheck(nameReg < 16 && valueReg < 16, "header value registers exceed compact invoke range")
            method.addInstructions(
                site.index,
                """
                    invoke-static { v$nameReg, v$valueReg }, $helperDescriptor
                    move-result-object v$valueReg
                """.trimIndent(),
            )
        }
    }
}
