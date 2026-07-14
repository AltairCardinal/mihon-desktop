package mihon.domain.migration.models

enum class MigrationFlag(val flag: Int) {
    CHAPTER(0b00001),
    CATEGORY(0b00010),
    CUSTOM_COVER(0b01000),
    REMOVE_DOWNLOAD(0b10000),
    NOTES(0b100000),
    ;

    companion object {
        fun fromBit(bit: Int): Set<MigrationFlag> = entries.filterTo(mutableSetOf()) { bit and it.flag != 0 }

        fun toBit(flags: Set<MigrationFlag>): Int = flags.fold(0) { mask, flag -> mask or flag.flag }
    }
}
