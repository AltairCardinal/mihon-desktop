package tachiyomi.data.release

import android.os.Build
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType

class AndroidPlatformInfo : PlatformInfo {
    override val releaseOs = ReleaseOs.ANDROID
    override val releasePackageType = ReleasePackageType.APK
    override val preferredAbi: String?
        get() = Build.SUPPORTED_ABIS.firstOrNull()
}
