package com.yourname.wordtone

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.*
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.IOException

class WordToneIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var rootView: View? = null
    private var suggestionsContainer: LinearLayout? = null
    private var loadingText: TextView? = null
    private var emptyText: TextView? = null
    private var toneContainer: LinearLayout? = null
    private var selectedTone = Constants.TONES[0]

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_panel, null)
        rootView = view

        suggestionsContainer = view.findViewById(R.id.suggestions_container)
        loadingText = view.findViewById(R.id.loading_text)
        emptyText = view.findViewById(R.id.empty_text)
        toneContainer = view.findViewById(R.id.tone_container)

        buildToneChips()

        view.findViewById<Button>(R.id.btn_rewrite)?.setOnClickListener {
            triggerRewrite()
        }

        view.findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
            requestHideSelf(0)
        }

        return view
    }

    private fun buildToneChips() {
        toneContainer?.removeAllViews()
        Constants.TONES.forEach { tone ->
            val chip = TextView(this).apply {
                text = tone.label
                textSize = 12f
                setPadding(24, 8, 24, 8)
                isSelected = (tone.value == selectedTone.value)
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener {
                    selectedTone = tone
                    buildToneChips()
                    triggerRewrite()
                }
            }
            toneContainer?.addView(chip)
        }
    }

    private fun getCurrentInputText(): String {
        return try {
            val ic = currentInputConnection ?: return ""
            val req = ExtractedTextRequest().apply { hintMaxChars = 1000 }
            ic.getExtractedText(req, 0)?.text?.toString()?.trim() ?: ""
        } catch (e: Exception) { "" }
    }

    private fun triggerRewrite() {
        val text = getCurrentInputText()
        if (text.isBlank()) {
            Toast.makeText(this, "Type something first!", Toast.LENGTH_SHORT).show()
            return
        }
        fetchRewrites(text, selectedTone.value)
    }

    private fun fetchRewrites(text: String, tone: String) {
        loadingText?.visibility = View.VISIBLE
        suggestionsContainer?.removeAllViews()
        emptyText?.visibility = View.GONE

        scope.launch {
            try {
                val response = RetrofitClient.apiService.rewrite(
                    RewriteRequest(text = text, tone = tone)
                )
                loadingText?.visibility = View.GONE
                showSuggestions(response.variations)
            } catch (e: HttpException) {
                loadingText?.visibility = View.GONE
                val msg = when (e.code()) {
                    429 -> "Daily limit reached."
                    else -> "Server error ${e.code()}"
                }
                showEmpty(msg)
            } catch (e: IOException) {
                loadingText?.visibility = View.GONE
                showEmpty("No internet connection.")
            } catch (e: Exception) {
                loadingText?.visibility = View.GONE
                showEmpty("Error: ${e.message}")
            }
        }
    }

    private fun showSuggestions(variations: List<String>) {
        emptyText?.visibility = View.GONE
        variations.forEach { suggestion ->
            val item = layoutInflater.inflate(
                R.layout.suggestion_item, suggestionsContainer, false
            )
            item.findViewById<TextView>(R.id.suggestion_text).text = suggestion
            item.setOnClickListener { injectText(suggestion) }
            suggestionsContainer?.addView(item)
        }
    }

    private fun injectText(newText: String) {
        try {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText(newText, 1)
            ic.endBatchEdit()
            requestHideSelf(0)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not insert text", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
