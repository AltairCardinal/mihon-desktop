package ec.registration

import eu.kanade.tachiyomi.source.Source

/** Packaged into a real child-loaded extension; host controls only these fixture switches. */
class PoisonableRegisteredSource : Source {
    @JvmField var broken = false

    @JvmField var reads = 0
    override val id: Long get() {
        reads++
        if (broken) throw NoSuchMethodError("future registered source id")
        return 7013L
    }
    override val name = "Registered source fixture"
    override val lang = "en"
    override fun equals(other: Any?) = other is Source
    override fun hashCode() = 1
}
