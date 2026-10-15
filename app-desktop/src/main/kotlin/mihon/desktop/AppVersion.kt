package mihon.desktop

object AppVersion {
    const val STAGE = 11
    const val FEATURE = 20
    const val BUILD = 6
}

val APP_VERSION: String =
    "0.${AppVersion.STAGE}.${AppVersion.FEATURE}.${AppVersion.BUILD}.${BuildInfo.GIT_HASH}"
