package mihon.desktop

object AppVersion {
    const val STAGE = 7
    const val FEATURE = 0
}

val APP_VERSION: String = "0.${AppVersion.STAGE}.${AppVersion.FEATURE}.${BuildInfo.GIT_HASH}"
