package mihon.desktop.test

import mihon.desktop.platform.DesktopPlatformPaths
import java.nio.file.Path

internal object TestArtifactPaths {

    fun defaultScreenshotDir(): Path {
        return DesktopPlatformPaths.current().testScreenshotsDir.toPath()
    }
}
