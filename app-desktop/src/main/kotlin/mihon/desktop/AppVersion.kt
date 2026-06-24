package mihon.desktop

object AppVersion {
    const val STAGE = 11
    const val FEATURE = 11
}

val APP_VERSION: String = "0.${AppVersion.STAGE}.${AppVersion.FEATURE}.${BuildInfo.GIT_HASH}"
