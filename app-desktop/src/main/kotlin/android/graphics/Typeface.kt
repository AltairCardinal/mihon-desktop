package android.graphics

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface as SkiaTypeface

class Typeface private constructor(internal val native: SkiaTypeface) {
    companion object {
        @JvmField
        val DEFAULT = Typeface(
            FontMgr.default.run {
                require(familiesCount > 0) { "No system fonts available" }
                requireNotNull(matchFamilyStyle(getFamilyName(0), FontStyle.NORMAL))
            },
        )
    }
}
