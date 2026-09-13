package io.github.yydarlinker.hansfix

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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

private const val AUTH_RUNTIME = "Lio/github/yydarlinker/hansfix/captionauth/CaptionAuthRuntime;"
private const val OFFICIAL_CAPTION_COOKIES = "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"
private const val BUILDER = "Lorg/chromium/net/UrlRequest\$Builder;"
private const val CALLBACK = "Lorg/chromium/net/UrlRequest\$Callback;"
private const val BUFFER = "Ljava/nio/ByteBuffer;"
private const val STRING = "Ljava/lang/String;"

private fun acCheck(ok: Boolean, message: String) {
    if (!ok) throw PatchException("Caption authenticated cookies: $message")
}

private fun <T> List<T>.acOne(role: String): T {
    acCheck(size == 1, "$role must match once (found $size)")
    return single()
}

private fun Method.acParams() = parameterTypes.map { it.toString() }
private fun MethodReference.acParams() = parameterTypes.map { it.toString() }
private fun MethodReference.acId() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Instruction.acRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.acArgs(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> throw PatchException("Caption authenticated cookies: unsupported invoke encoding")
}

private data class AuthSite(val method: Method, val index: Int, val call: Instruction)

/**
 * Research-only A/B patch for the translated-caption HTTP 429 issue.
 *
 * It does not fetch, persist, or expose account cookies. Instead it reuses the Cookie string
 * already configured in Morphe's official Captions patch. When that string contains SAPISID or
 * __Secure-3PAPISID, translated /api/timedtext requests receive browser-style SAPISIDHASH,
 * X-Origin and DNT headers in addition to Morphe's existing Cookie/User-Agent headers.
 */
@Suppress("unused")
val captionAuthenticatedCookiesPatch = bytecodePatch(
    name = "Caption authenticated cookies (experimental)",
    description = "Research patch for auto-translated caption 429s. For tlang timedtext requests, adds authenticated web headers derived from the full logged-in Cookie string already configured in Morphe. Does not store or log cookie values. Requires official Captions with Set caption cookies enabled. Experimental; default off.",
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
            targets = listOf(AppTarget(version = null, minSdk = 28)),
        )
    )
    dependsOn(captionAddonExtensionPatch)
    execute {
        acCheck(packageMetadata.packageName == "com.google.android.youtube", "requires original YouTube APK")
    }
    finalize { installAuthenticatedCaptionHeaders() }
}

private fun BytecodePatchContext.installAuthenticatedCaptionHeaders() {
    // The official Captions patch must have merged its extension class and Timed Text hook first.
    val officialPresent = getAllClassesWithString("api/timedtext")
        .any { it.type == OFFICIAL_CAPTION_COOKIES }
    acCheck(officialPresent, "select official Captions in the same patch session")

    val sites = mutableListOf<AuthSite>()
    classDefForEach { cls ->
        cls.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            if (method.acParams().size == 1 && method.returnType.startsWith("L") &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags) &&
                code.any { ins ->
                    ins.acRef()?.let { ref ->
                        ref.definingClass == "Lorg/chromium/net/UploadDataProviders;" &&
                            ref.name == "create" && ref.acParams() == listOf(BUFFER)
                    } == true
                }
            ) {
                code.forEachIndexed { index, ins ->
                    if (ins.acRef()?.let { ref ->
                            ref.definingClass == "Lorg/chromium/net/CronetEngine;" &&
                                ref.name == "newUrlRequestBuilder" &&
                                ref.returnType == BUILDER &&
                                ref.acParams() == listOf(STRING, CALLBACK, "Ljava/util/concurrent/Executor;")
                        } == true
                    ) {
                        sites += AuthSite(method, index, ins)
                    }
                }
            }
        }
    }

    val site = sites.acOne("native caption network builder")
    val callArgs = site.call.acArgs()
    acCheck(callArgs.size == 4, "unexpected Cronet builder arity")
    val urlRegister = callArgs[1]
    acCheck(urlRegister < 16, "URL register unsupported")

    val code = site.method.implementation!!.instructions.toList()
    acCheck(code.none { it.acRef()?.definingClass == AUTH_RUNTIME }, "authenticated caption hook already installed")
    val moveResult = code.getOrNull(site.index + 1)
    acCheck(moveResult?.opcode == Opcode.MOVE_RESULT_OBJECT && moveResult is OneRegisterInstruction,
        "Cronet builder result changed")
    val builderRegister = (moveResult as OneRegisterInstruction).registerA
    acCheck(builderRegister < 16, "builder register unsupported")

    // Assert that Morphe's official Cookie/User-Agent helper is already present in the same path.
    val officialHelperCalls = code.filter { ins ->
        ins.acRef()?.let { ref ->
            ref.name == "patch_setUrlRequestHeaders" &&
                ref.acParams() == listOf(BUILDER) && ref.returnType == "V"
        } == true
    }
    acCheck(officialHelperCalls.size == 1, "official Timed Text header helper not found")

    val host = mutableClassDefBy(site.method.definingClass)
    val helperName = "patch_addAuthenticatedCaptionHeaders"
    acCheck(host.methods.none { it.name == helperName }, "generated helper already exists")

    val helper = ImmutableMethod(
        host.type,
        helperName,
        listOf(
            ImmutableMethodParameter(BUILDER, null, null),
            ImmutableMethodParameter(STRING, null, null),
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

                # Respect Morphe's official Set caption cookies switch/path.
                invoke-static { }, $OFFICIAL_CAPTION_COOKIES->getRequireCookies()Z
                move-result v0
                if-eqz v0, :done

                invoke-static { }, $OFFICIAL_CAPTION_COOKIES->getCookies()Ljava/lang/String;
                move-result-object v0
                invoke-static { v0 }, $AUTH_RUNTIME->authorizationForCookies(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :done

                const-string v1, "Authorization"
                invoke-virtual { p0, v1, v0 }, $BUILDER->addHeader(Ljava/lang/String;Ljava/lang/String;)$BUILDER

                const-string v1, "X-Origin"
                invoke-static { }, $AUTH_RUNTIME->origin()Ljava/lang/String;
                move-result-object v0
                invoke-virtual { p0, v1, v0 }, $BUILDER->addHeader(Ljava/lang/String;Ljava/lang/String;)$BUILDER

                const-string v1, "DNT"
                const-string v0, "1"
                invoke-virtual { p0, v1, v0 }, $BUILDER->addHeader(Ljava/lang/String;Ljava/lang/String;)$BUILDER
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
        .filter { it.acId() == (site.method as MethodReference).acId() }
        .toList().acOne("mutable caption network method")

    // Immediately after newUrlRequestBuilder's move-result; all data stays request-local.
    mutableNetworkMethod.addInstruction(
        site.index + 2,
        "invoke-static { v$builderRegister, v$urlRegister }, ${host.type}->$helperName($BUILDER$STRING)V",
    )
}
