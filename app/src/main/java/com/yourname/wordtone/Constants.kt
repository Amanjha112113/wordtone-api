package com.yourname.wordtone

object Constants {
    const val BASE_URL = "https://wordtone-api-production.up.railway.app/"  // ← add /

    val TONES = listOf(
        ToneItem("ReWrite", "casual"),
        ToneItem("Professional", "professional"),
        ToneItem("Casual", "casual"),
        ToneItem("Polite", "polite"),
        ToneItem("Romantic", "romantic"),
        ToneItem("Gen-Z", "gen-z"),
        ToneItem("Witty", "witty"),
        ToneItem("Formal", "formal")
    )
}

data class ToneItem(val label: String, val value: String)