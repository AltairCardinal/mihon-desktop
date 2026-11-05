package fixture.extension

import eu.kanade.tachiyomi.source.Source
import java.io.File

object InstalledReloadAbiProbe {
    val locations = java.util.concurrent.CopyOnWriteArrayList<String>()
}

class InstalledReloadAbiSource : Source {
    init {
        val artifact = File(javaClass.protectionDomain.codeSource.location.toURI())
        InstalledReloadAbiProbe.locations += artifact.name
        if (artifact.name == "fixture.extension.jar") throw NoSuchMethodError("future installed constructor API")
    }
    override val id = 7002L
    override val name = "Installed ABI failure"
    override val lang = "en"
}

class HealthyReloadSource : Source {
    override val id = 7002L
    override val name = "Healthy replacement"
    override val lang = "en"
}
