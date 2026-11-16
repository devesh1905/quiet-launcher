package app.quiet.launcher.feature.receipt

/**
 * Code 39 barcodes. Each character is nine elements, bar first, three of them wide.
 * A full code is wrapped in the '*' start/stop character, with one narrow space between characters.
 */
object Code39 {
    private val PATTERNS: Map<Char, String> = mapOf(
        '0' to "nnnwwnwnn", '1' to "wnnwnnnnw", '2' to "nnwwnnnnw", '3' to "wnwwnnnnn",
        '4' to "nnnwwnnnw", '5' to "wnnwwnnnn", '6' to "nnwwwnnnn", '7' to "nnnwnnwnw",
        '8' to "wnnwnnwnn", '9' to "nnwwnnwnn",
        'A' to "wnnnnwnnw", 'B' to "nnwnnwnnw", 'C' to "wnwnnwnnn", 'D' to "nnnnwwnnw",
        'E' to "wnnnwwnnn", 'F' to "nnwnwwnnn", 'G' to "nnnnnwwnw", 'H' to "wnnnnwwnn",
        'I' to "nnwnnwwnn", 'J' to "nnnnwwwnn", 'K' to "wnnnnnnww", 'L' to "nnwnnnnww",
        'M' to "wnwnnnnwn", 'N' to "nnnnwnnww", 'O' to "wnnnwnnwn", 'P' to "nnwnwnnwn",
        'Q' to "nnnnnnwww", 'R' to "wnnnnnwwn", 'S' to "nnwnnnwwn", 'T' to "nnnnwnwwn",
        'U' to "wwnnnnnnw", 'V' to "nwwnnnnnw", 'W' to "wwwnnnnnn", 'X' to "nwnnwnnnw",
        'Y' to "wwnnwnnnn", 'Z' to "nwwnwnnnn", '-' to "nwnnnnwnw", '.' to "wwnnnnwnn",
        ' ' to "nwwnnnwnn", '*' to "nwnnwnwnn",
    )

    fun canEncode(text: String): Boolean = text.all { it != '*' && it in PATTERNS }

    fun pattern(c: Char): String = PATTERNS[c] ?: throw IllegalArgumentException("Code 39 has no '$c'")

    /**
     * All elements of [text] with start and stop, as 'n' / 'w'. Even indexes are bars, odd are spaces.
     * Throws for characters Code 39 cannot carry.
     */
    fun elements(text: String): String {
        require(canEncode(text)) { "Code 39 cannot encode \"$text\"" }
        return "*$text*".map { pattern(it) }.joinToString("n")
    }
}
