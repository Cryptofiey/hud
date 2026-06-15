fun main() {
    fun testRegex(rawText: String) {
        var safeText = rawText.uppercase(java.util.Locale.US).trim()
        safeText = safeText.replace("POT", " ").replace("ПОТ", " ")
        safeText = safeText.replace("BB", " ").replace("ББ", " ")
        
        safeText = safeText.replace(Regex("\\b\\d+[.,]\\d+\\b"), " ")
        safeText = safeText.replace(Regex("\\b\\d{3,}\\b"), " ") // 100+
        safeText = safeText.replace(Regex("\\b(1[1-9]|[2-9]\\d)\\b"), " ") // 11-99
        safeText = safeText.replace(Regex("\\b[01]\\b"), " ") // Standalone 0 or 1
        
        println("Input: '$rawText' -> safeText: '$safeText'")
    }

    testRegex("44")
    testRegex("4♠ 4♣")
    testRegex("4♠4♣")
    testRegex("4 4")
    testRegex("K♠ 7♥")
    testRegex("10♠ 4♠")
    testRegex("99")
    testRegex("55")
    testRegex("J J")
    testRegex("A A")
}
