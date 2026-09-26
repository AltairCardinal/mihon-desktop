package mihon.domain.reader

/** Physical viewport slot for a portrait page that is alone in a dual-page display unit. */
enum class ReaderPortraitSingleSlot { FULL, LEFT, RIGHT }

/** Pairing determines the units; this rule only places an already independent portrait page. */
fun portraitSinglePageSlot(
    direction: ReaderDirection,
    groupIndex: Int,
    groupCount: Int,
): ReaderPortraitSingleSlot {
    require(direction != ReaderDirection.VERTICAL) { "A dual-page slot requires a horizontal direction" }
    require(groupCount > 0 && groupIndex in 0 until groupCount)
    if (groupCount == 1) return ReaderPortraitSingleSlot.FULL
    val isTrailing = groupIndex == groupCount - 1
    val onRight = (direction == ReaderDirection.RTL) == isTrailing
    return if (onRight) ReaderPortraitSingleSlot.RIGHT else ReaderPortraitSingleSlot.LEFT
}
