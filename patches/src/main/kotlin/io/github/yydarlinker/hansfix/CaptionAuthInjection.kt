package io.github.yydarlinker.hansfix

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val AUTH_RUNTIME = "Lio/github/yydarlinker/hansfix/captionauth/CaptionAuthRuntime;"
private const val OFFICIAL_CAPTION_COOKIES = "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"
private const val AUTH_BUILDER = "Lorg/chromium/net/UrlRequest\$Builder;"
private const val AUTH_CALLBACK = "Lorg/chromium/net/UrlRequest\$Callback;"
private const val AUTH_BUFFER = "Ljava/nio/ByteBuffer;"
private const val AUTH_STRING = "Ljava/lang/String;"

private fun acheck(ok: Boolean, message: String) {
    if (!ok) throw PatchException("Caption auth experiment: $message")
}

private fun <T> List<T>.aone(role: String): T {
    acheck(size == 1, "$role must match once (found $size)")
    return single()
}

private fun Method.aparams() = parameterTypes.map { it.toString() }
private fun MethodReference.aparams() = parameterTypes.map { it.toString() }
private fun MethodReference.aid() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Instruction.aref() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.aargs(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> throw PatchException("Caption auth experiment: unsupported invoke encoding")
}

private data class AuthSite(val method: Method, val index: Int, val call: Instruction)

/**
 * Internal research hook owned by Caption request diagnostics on the research branch.
 * It is deliberately not a separately publishable patch root.
 */
internal fun BytecodePatchContext.installAuthenticatedCaptionHeaders() {
    val officialPresent = getAllClassesWithString("api/timedtext")
        .any { it.type == OFFICIAL_CAPTION_COOKIES }
    acheck(officialPresent, "official Captions patch is required")

    val sites = mutableListOf<AuthSite>()
    classDefForEach { cls ->
        cls.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            if (method.aparams().size == 1 && method.returnType.startsWith("L") &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags) &&
                code.any { ins ->
                    ins.aref()?.let { ref ->
                        ref.definingClass == "Lorg/chromium/net/UploadDataProviders;" &&
                            ref.name == "create" && ref.aparams() == listOf(AUTH_BUFFER)
                    } == true
                }
            ) {
                code.forEachIndexed { index, ins ->
                    if (ins.aref()?.let { ref ->
                            ref.definingClass == "Lorg/chromium/net/CronetEngine;" &&
                                ref.name == "newUrlRequestBuilder" &&
                                ref.returnType == AUTH_BUILDER &&
                                ref.aparams() == listOf(
                                    AUTH_STRING,
                                    AUTH_CALLBACK,
                                    "Ljava/util/concurrent/Executor;",
                                )
                        } == true
                    ) sites += AuthSite(method, index, ins)
                }
            }
        }
    }

    val site = sites.aone("native caption network builder")
    val args = site.call.aargs()
    acheck(args.size == 4 && args.all { it < 16 }, "Cronet builder registers unsupported")
    val urlRegister = args[1]

    val currentCode = site.method.implementation!!.instructions.toList()
    acheck(currentCode.none { it.aref()?.definingClass == AUTH_RUNTIME }, "auth hook already installed")
    val result = currentCode.getOrNull(site.index + 1)
    acheck(result?.opcode == Opcode.MOVE_RESULT_OBJECT && result is OneRegisterInstruction,
        "Cronet builder result changed")
    val builderRegister = (result as OneRegisterInstruction).registerA
    acheck(builderRegister < 16, "builder register unsupported")

    val host = mutableClassDefBy(site.method.definingClass)
    val helperName = "patch_addAuthenticatedCaptionHeaders"
    acheck(host.methods.none { it.name == helperName }, "generated auth helper already exists")

    val helper = ImmutableMethod(
        host.type,
        helperName,
        listOf(
            ImmutableMethodParameter(AUTH_BUILDER, null, null),
            ImmutableMethodParameter(AUTH_STRING, null, null),
        ),
        "V",
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        emptySet(),
        null,
        MutableMethodImplementation(4),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                :try_start
                if-eqz p1, :done
                const-string v0, "tlang="
                invoke-virtual { p1, v0 }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v0
                if-eqz v0, :done

                # Preserve Morphe's own enable/disable gate.
                invoke-static { }, $OFFICIAL_CAPTION_COOKIES->getRequireCookies()Z
                move-result v0
                if-eqz v0, :done

                invoke-static { }, $OFFICIAL_CAPTION_COOKIES->getCookies()Ljava/lang/String;
                move-result-object v0
                invoke-static { v0 }, $AUTH_RUNTIME->authorizationForCookies(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :done

                const-string v1, "Authorization"
                invoke-virtual { p0, v1, v0 }, $AUTH_BUILDER->addHeader(Ljava/lang/String;Ljava/lang/String;)$AUTH_BUILDER

                const-string v1, "X-Origin"
                invoke-static { }, $AUTH_RUNTIME->origin()Ljava/lang/String;
                move-result-object v0
                invoke-virtual { p0, v1, v0 }, $AUTH_BUILDER->addHeader(Ljava/lang/String;Ljava/lang/String;)$AUTH_BUILDER

                const-string v1, "DNT"
                const-string v0, "1"
                invoke-virtual { p0, v1, v0 }, $AUTH_BUILDER->addHeader(Ljava/lang/String;Ljava/lang/String;)$AUTH_BUILDER
                :try_end
                :done
                return-void
                :error
                move-exception v0
                return-void
                .catch Ljava/lang/Throwable; { :try_start .. :try_end } :error
            """.trimIndent(),
        )
    }
    host.methods.add(helper)

    val mutableNetworkMethod = host.methods
        .filter { method ->
            method.definingClass == site.method.definingClass &&
                method.name == site.method.name &&
                method.parameterTypes.map { it.toString() } == site.method.aparams() &&
                method.returnType == site.method.returnType
        }
        .toList().aone("mutable caption network method")

    // installDiagnostics() has already installed onBuilder. Insert immediately before Morphe's
    // official patch_setUrlRequestHeaders call, so builder attribution exists before auth runs.
    val updated = mutableNetworkMethod.implementation!!.instructions.toList()
    val officialHeaderIndex = updated.mapIndexedNotNull { index, ins ->
        ins.aref()?.let { ref ->
            if (ref.name == "patch_setUrlRequestHeaders" &&
                ref.aparams() == listOf(AUTH_BUILDER) && ref.returnType == "V") index else null
        }
    }.aone("official Timed Text header helper call")

    mutableNetworkMethod.addInstruction(
        officialHeaderIndex,
        "invoke-static { v$builderRegister, v$urlRegister }, ${host.type}->$helperName($AUTH_BUILDER$AUTH_STRING)V",
    )
}
