package mihon.desktop

object AppVersion {
    const val STAGE = 11
    const val FEATURE = 14
    const val BUILD = 75
}

val APP_VERSION: String =
    "0.${AppVersion.STAGE}.${AppVersion.FEATURE}.${AppVersion.BUILD}.${BuildInfo.GIT_HASH}"
