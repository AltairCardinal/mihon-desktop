package android.text

/** Minimal Desktop marker matching the Android framework Spanned API type. */
interface Spanned : CharSequence

/** Mutable-markup verifier contract used by extension preference text. */
interface Spannable : Spanned

/** In-memory text implementation; Desktop does not render Android spans. */
class SpannableString(
    private val value: String,
) : Spannable {
    override val length: Int
        get() = value.length

    override fun get(index: Int): Char = value[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        value.subSequence(startIndex, endIndex)

    override fun toString(): String = value
}
