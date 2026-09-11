package io.github.yydarlinker.hansfix.integration

import java.util.Collections
import java.util.IdentityHashMap

/** Explicit diagnostic vocabulary reviewed against HansFixPatch.kt. Unknown/new gates fail closed.
 * Only fixed source text, known role/helper names and numeric match counts may reach the audit.
 * Do not replace these allowlists with a generic prefix/ASCII check: cookies are also ASCII. */
private val fixedAddonGates = setOf(
    "unsupported caption request encoding.",
    "caption request builder shape changed.",
    "official caption URL registers differ.",
    "unsupported caption display call encoding.",
    "caption display call shape changed.",
    "caption display serialization shape changed.",
    "caption selection display field missing.",
    "caption display must feed a local descriptor builder.",
    "caption descriptor display store missing.",
    "settings caption producers and selection comparison must agree.",
    "additional caption summary displays missing.",
    "requires original YouTube 21.07.247 / 1561056418; do not patch an installed Morphe APK again.",
    "already applied or duplicate addon selected.",
    "official Captions is required in the SAME expert-mode operation; its extension is missing.",
    "toggle bridge already exists; duplicate addon/input.",
    "expected two subtitle menus and four row construction sites.",
    "generated helpers already exist.",
    "runtime rewrite API missing.",
    "runtime UI policy API missing.",
    "unsupported Cookie call encoding.",
    "Cookie call shape changed.",
    "Cookie check must immediately precede the Cronet builder.",
    "Cookie and builder URL registers differ.",
    "official Cookie request-header helper is missing.",
    "network hook already applied.",
    "model field access is not public; unsupported target.",
    "unsupported menu constructor encoding.",
    "menu Context register is still live; refusing unsafe register reuse.",
    "menu register alias collision.",
    "each subtitle menu must have two row constructors.",
    "menu this register cannot encode iget-object safely.",
    "unsupported summary call encoding.",
    "summary return shape changed.",
    "summary text is not stored in a UI String field.",
    "menu hook already exists.",
    "menu title superclass not found.",
    "UI title field must be public.",
    "could not bind the menu UI title field; no global model rewrite allowed.",
)

private val addonGateRoles = setOf(
    "native caption request hook", "caption display null normalizer", "settings caption descriptor display field",
    "auto-translation sentinel check", "caption menu item type", "caption track model",
    "menu backing List field", "menu callback original-track field", "menu item binding",
    "mutable menu method", "mutable network method",
    "official caption request hook (select official Captions)", "official Cookie toggle cache",
    "original track display field", "runtime fail-closed toggle stub",
    "selected-translation summary in each menu", "source caption URL used to build translated requests",
    "track language code", "track toString", "translated-track predicate", "translation identity field",
)
private val addonHelperNames = setOf(
    "hansfixAddonGetCaptionToggle", "isEnabled", "labelForTrack", "hasNativeSimplifiedCaption",
    "applyCaptionMenuLabel", "autoTranslateMenuText",
)
private val uniqueGate = Regex("(.+) must match exactly once \\(found ([0-9]{1,10})\\)\\. Unsupported input or conflicting patches\\.")
private val menuCountGate = Regex("two automatic-translation subtitle menus required \\(found [0-9]{1,10}\\)\\.")
private val duplicateGate = Regex("duplicate generated method ([A-Za-z]+)\\.")

/** Reject the entire message, rather than truncating a safe-looking prefix of unsafe content. */
internal fun safeAddonGateMessage(message: String?): String? {
    if (message == null || !message.startsWith("HansFix: ") || message.length > 300 ||
        message.any { it !in ' '..'~' } || message.contains("http://", ignoreCase = true) ||
        message.contains("https://", ignoreCase = true) || '=' in message) return null
    val body = message.removePrefix("HansFix: ")
    val allowed = body in fixedAddonGates ||
        uniqueGate.matchEntire(body)?.groupValues?.get(1) in addonGateRoles ||
        menuCountGate.matches(body) ||
        duplicateGate.matchEntire(body)?.groupValues?.get(1) in addonHelperNames
    return message.takeIf { allowed }
}

/** Call only for a PatchResult whose patch is identical to the selected addon object.
 * Patcher may wrap its finalize failure in a message containing a stack trace: skip that wrapper
 * and inspect causes, never split or print it. Bound traversal and guard against cyclic causes. */
internal fun safeAddonGateReason(error: Throwable): String? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    repeat(32) {
        val cause = current ?: return null
        if (!seen.add(cause)) return null
        safeAddonGateMessage(cause.message)?.let { return it }
        current = cause.cause
    }
    return null
}
