package mihon.desktop.extension

import java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE
import java.util.Collections
import java.util.WeakHashMap

/**
 * Associates a loaded extension classloader with its installed package identity.
 *
 * The weak keys follow the extension runtime lifecycle without retaining replaced JARs. Network
 * client resolution uses the declaring classes on the current call stack, so constructor-time and
 * lazy `NetworkHelper.client` access both resolve to the owning extension.
 */
class DesktopExtensionNetworkContext internal constructor() {
    private val packagesByClassLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, String>())
    private val stackWalker = StackWalker.getInstance(RETAIN_CLASS_REFERENCE)

    internal fun register(classLoader: ClassLoader, packageName: String) {
        packagesByClassLoader[classLoader] = packageName
    }

    internal fun currentPackage(): String? = runCatching {
        stackWalker.walk { frames ->
            frames
                .map { it.declaringClass.classLoader }
                .filter { it != null }
                .map { classLoader -> packagesByClassLoader[classLoader] }
                .filter { it != null }
                .map { it!! }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()
}
