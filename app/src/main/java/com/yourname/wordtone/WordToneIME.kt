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
    private var candidateView: View? = null
    private var suggestionsContainer: LinearLayout? = null
    private var loadingText: TextView? = null
    private var emptyText: TextView? = null
    private var selectedTone = Constants.TONES[0]

    override fun onCreateInputView(): View {
        // Return empty view — we use candidates view instead
        // This lets the system keyboard show underneath
        return View(this)
    }

    override fun onCreateCandidatesView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_panel, null)
        candidateView = view
        suggestionsContainer = view.findViewById(R.id.suggestions_container)
        loadingText = view.findViewById(R.id.loading_text)
        emptyText = view.findViewById(R.id.empty_text)

        buildToneChips(view)

        view.findViewById<Button>(R.id.btn_rewrite)?.setOnClickListener {
            triggerRewrite()
        }

        view.findViewById<Button>(R.id.btn_close)?.setOnClickListener {
            setCandidatesViewShown(false)
        }

        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setCandidatesViewShown(true)
    }

    private fun buildToneChips(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.tone_container) ?: return
        container.removeAllViews()
        Constants.TONES.forEach { tone ->
            val chip = TextView(this).apply {
                text = tone.label
                textSize = 12f
                isSelected = (tone.value == selectedTone.value)
                setPadding(32, 16, 32, 16)
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(if (tone.value == selectedTone.value) 0xFFFFFFFF.toInt() else 0xFFAEAEB2.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 12
                layoutParams = lp
                setOnClickListener {
                    selectedTone = tone
                    buildToneChips(candidateView ?: return@setOnClickListener)
                    triggerRewrite()
                }
            }
            container.addView(chip)
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
        emptyText?.visibility = View.GONE
        suggestionsContainer?.removeAllViews()

        scope.launch {
            try {
                val response = RetrofitClient.apiService.rewrite(
                    RewriteRequest(text = text, tone = tone)
                )
                loadingText?.visibility = View.GONE
                showSuggestions(response.variations)
            } catch (e: HttpException) {
                loadingText?.visibility = View.GONE
                emptyText?.text = when (e.code()) {
                    429 -> "Daily limit reached."
                    else -> "Server error. Try again."
                }
                emptyText?.visibility = View.VISIBLE
            } catch (e: IOException) {
                loadingText?.visibility = View.GONE
                emptyText?.text = "No internet."
                emptyText?.visibility = View.VISIBLE
            } catch (e: Exception) {
                loadingText?.visibility = View.GONE
                emptyText?.text = "Error. Try again."
                emptyText?.visibility = View.VISIBLE
            }
        }
    }

    private fun showSuggestions(variations: List<String>) {
        val container = suggestionsContainer ?: return
        variations.forEach { suggestion ->
            val item = layoutInflater.inflate(R.layout.suggestion_item, container, false)
            item.findViewById<TextView>(R.id.suggestion_text)?.text = suggestion
            item.setOnClickListener { injectText(suggestion) }
            container.addView(item)
        }
    }

    private fun injectText(newText: String) {
        try {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText(newText, 1)
            ic.endBatchEdit()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not insert text.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        suggestionsContainer?.removeAllViews()
        loadingText?.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
