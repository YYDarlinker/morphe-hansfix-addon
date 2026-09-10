package io.github.yydarlinker.hansfix

import app.morphe.patcher.patch.bytecodePatch

/** One extension merge shared by the independently selectable patches in this bundle. */
internal val captionAddonExtensionPatch = bytecodePatch(description = "Shared caption addon runtime") {
    extendWith("extensions/hansfix-addon.mpe")
}
