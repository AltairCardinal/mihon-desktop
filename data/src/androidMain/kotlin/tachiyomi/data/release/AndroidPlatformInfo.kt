package tachiyomi.data.release

import android.os.Build

class AndroidPlatformInfo : PlatformInfo {
    override val preferredAbi: String?
        get() = Build.SUPPORTED_ABIS.firstOrNull()
}
