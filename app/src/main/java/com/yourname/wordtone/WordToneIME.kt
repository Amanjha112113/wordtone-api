package com.yourname.wordtone

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.*
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.IOException

class WordToneIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suggestionsContainer: LinearLayout? = null
    private var loadingText: TextView? = null
    private var emptyText: TextView? = null
    private var selectedTone = Constants.TONES[0]

    override fun onCreateInputView(): View {
        // Build entire UI programmatically — no XML inflation
        // This avoids ALL theme attribute issues in IME context
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C1E.toInt())
            setPadding(0, 20, 0, 24)
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 0, 20, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Word Tone"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val rewriteBtn = Button(this).apply {
            text = "ReWrite"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF6C5CE7.toInt())
            setPadding(28, 0, 28, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 80)
            setOnClickListener { triggerRewrite() }
        }

        val closeBtn = Button(this).apply {
            text = "X"
            textSize = 11f
            setTextColor(0xFFAEAEB2.toInt())
            setBackgroundColor(0xFF3A3A3C.toInt())
            layoutParams = LinearLayout.LayoutParams(80, 80).also {
                it.marginStart = 8
            }
            setOnClickListener { requestHideSelf(0) }
        }

        header.addView(title)
        header.addView(rewriteBtn)
        header.addView(closeBtn)
        root.addView(header)

        // Tone chips scroll
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 12
            }
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 20, 0)
        }

        Constants.TONES.forEach { tone ->
            val chip = TextView(this).apply {
                text = tone.label
                textSize = 12f
                setPadding(28, 14, 28, 14)
                setBackgroundColor(
                    if (tone.value == selectedTone.value) 0xFF6C5CE7.toInt()
                    else 0xFF3A3A3C.toInt()
                )
                setTextColor(
                    if (tone.value == selectedTone.value) 0xFFFFFFFF.toInt()
                    else 0xFFAEAEB2.toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.marginEnd = 12
                }
                setOnClickListener {
                    selectedTone = tone
                    // Rebuild chips
                    for (i in 0 until chipRow.childCount) {
                        val c = chipRow.getChildAt(i) as? TextView ?: continue
                        val t = Constants.TONES[i]
                        c.setBackgroundColor(
                            if (t.value == selectedTone.value) 0xFF6C5CE7.toInt()
                            else 0xFF3A3A3C.toInt()
                        )
                        c.setTextColor(
                            if (t.value == selectedTone.value) 0xFFFFFFFF.toInt()
                            else 0xFFAEAEB2.toInt()
                        )
                    }
                    triggerRewrite()
                }
            }
            chipRow.addView(chip)
        }
        scroll.addView(chipRow)
        root.addView(scroll)

        // Loading text
        val loading = TextView(this).apply {
            text = "Rewriting with AI..."
            textSize = 12f
            setTextColor(0xFFAEAEB2.toInt())
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 12
            }
        }
        loadingText = loading
        root.addView(loading)

        // Empty/error text
        val empty = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFAEAEB2.toInt())
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 12
            }
        }
        emptyText = empty
        root.addView(empty)

        // Suggestions container
        val suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 0)
        }
        suggestionsContainer = suggestions
        root.addView(suggestions)

        return root
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
                    429 -> "Daily limit reached. Try tomorrow."
                    else -> "Server error. Try again."
                }
                emptyText?.visibility = View.VISIBLE
            } catch (e: IOException) {
                loadingText?.visibility = View.GONE
                emptyText?.text = "No internet connection."
                emptyText?.visibility = View.VISIBLE
            } catch (e: Exception) {
                loadingText?.visibility = View.GONE
                emptyText?.text = "Error: ${e.message}"
                emptyText?.visibility = View.VISIBLE
            }
        }
    }

    private fun showSuggestions(variations: List<String>) {
        val container = suggestionsContainer ?: return
        variations.forEach { suggestion ->
            val item = TextView(this).apply {
                text = suggestion
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF2C2C2E.toInt())
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.bottomMargin = 8
                }
                setOnClickListener { injectText(suggestion) }
            }
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
            requestHideSelf(0)
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
