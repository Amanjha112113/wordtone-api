package com.yourname.wordtone

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.*
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.IOException

class WordToneIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var rootView: View
    private lateinit var toneScrollView: HorizontalScrollView
    private lateinit var toneContainer: LinearLayout
    private lateinit var suggestionsContainer: LinearLayout
    private lateinit var loadingBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var emptyText: TextView

    private var selectedTone = Constants.TONES[0]

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_panel, null)

        toneScrollView = rootView.findViewById(R.id.tone_scroll)
        toneContainer = rootView.findViewById(R.id.tone_container)
        suggestionsContainer = rootView.findViewById(R.id.suggestions_container)
        loadingBar = rootView.findViewById(R.id.loading_bar)
        loadingText = rootView.findViewById(R.id.loading_text)
        emptyText = rootView.findViewById(R.id.empty_text)

        buildToneChips()

        rootView.findViewById<Button>(R.id.btn_rewrite).setOnClickListener {
            triggerRewrite()
        }

        rootView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
            requestHideSelf(0)
        }

        return rootView
    }

    private fun buildToneChips() {
        toneContainer.removeAllViews()
        Constants.TONES.forEach { tone ->
            val chip = layoutInflater.inflate(R.layout.tone_chip, toneContainer, false) as TextView
            chip.text = tone.label
            chip.isSelected = (tone.value == selectedTone.value)
            chip.setOnClickListener {
                selectedTone = tone
                buildToneChips()   // re-render to update selected state
                triggerRewrite()
            }
            toneContainer.addView(chip)
        }
    }

    private fun getCurrentInputText(): String {
        val ic = currentInputConnection ?: return ""
        val req = ExtractedTextRequest().apply { hintMaxChars = 1000 }
        return ic.getExtractedText(req, 0)?.text?.toString()?.trim() ?: ""
    }

    private fun triggerRewrite() {
        val text = getCurrentInputText()
        if (text.isBlank()) {
            toast("Type a message first!")
            return
        }
        if (text.length < 3) {
            toast("Message too short to rewrite.")
            return
        }
        fetchRewrites(text, selectedTone.value)
    }

    private fun fetchRewrites(text: String, tone: String) {
        setLoading(true)
        suggestionsContainer.removeAllViews()

        scope.launch {
            try {
                val response = RetrofitClient.apiService.rewrite(
                    RewriteRequest(text = text.truncate(500), tone = tone)
                )
                setLoading(false)
                if (response.variations.isEmpty()) {
                    showEmpty("No suggestions returned. Try again.")
                } else {
                    showSuggestions(response.variations)
                }
            } catch (e: HttpException) {
                setLoading(false)
                when (e.code()) {
                    429 -> showEmpty("Daily limit reached. Try again tomorrow.")
                    500 -> showEmpty("Server error. Try again in a moment.")
                    else -> showEmpty("Error ${e.code()}. Check your connection.")
                }
            } catch (e: IOException) {
                setLoading(false)
                showEmpty("No internet connection.")
            } catch (e: Exception) {
                setLoading(false)
                showEmpty("Something went wrong. Try again.")
            }
        }
    }

    private fun showSuggestions(variations: List<String>) {
        emptyText.visibility = View.GONE
        variations.forEach { suggestion ->
            val item = layoutInflater.inflate(
                R.layout.suggestion_item, suggestionsContainer, false
            )
            item.findViewById<TextView>(R.id.suggestion_text).text = suggestion
            item.setOnClickListener { injectText(suggestion) }
            suggestionsContainer.addView(item)
        }
    }

    private fun injectText(newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
        requestHideSelf(0)
    }

    private fun setLoading(isLoading: Boolean) {
        loadingBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        loadingText.visibility = if (isLoading) View.VISIBLE else View.GONE
        emptyText.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        suggestionsContainer.removeAllViews()
        setLoading(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
