package android.os

/**
 * Desktop stub for android.os.Build.
 * Extensions that gate features on SDK version will see API 28 (Android 9 / Pie).
 */
object Build {
    const val MANUFACTURER = "Desktop"
    const val MODEL = "Mihon Desktop"
    const val BRAND = "mihon"
    const val DEVICE = "desktop"
    const val PRODUCT = "mihon_desktop"

    object VERSION {
        const val SDK_INT = 28
        const val RELEASE = "9"
        const val CODENAME = "REL"
    }
}
