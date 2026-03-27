package com.yourname.wordtone

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.*
import android.graphics.Typeface
import android.view.Gravity
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.IOException

class WordToneIME : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Cache — one API call stores ALL 8 tones × 3 suggestions
    private var cachedRewrites: Map<String, List<String>> = emptyMap()
    private var lastFetchedText: String = ""

    private var selectedTone = Constants.TONES[0]
    private var suggestionsContainer: LinearLayout? = null
    private var loadingText: TextView? = null
    private var emptyText: TextView? = null
    private var chipRow: LinearLayout? = null
    private var isCaps = false
    private var isSymbols = false
    private var qwertyKeyboard: Keyboard? = null
    private var symbolsKeyboard: Keyboard? = null
    private var emojiKeyboard: Keyboard? = null
    private var keyboardView: KeyboardView? = null
    private var currentMode = 0 // 0: QWERTY, 1: Symbols, 2: Emoji

    override fun onCreateInputView(): View {
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.symbols)
        emojiKeyboard = Keyboard(this, R.xml.emoji)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#131315"))
        }
        
        val panel = layoutInflater.inflate(R.layout.keyboard_panel, null) as LinearLayout
        
        val rewriteBtn = panel.findViewById<Button>(R.id.btn_rewrite)
        rewriteBtn.setOnClickListener { fetchAllTones() }

        chipRow = panel.findViewById(R.id.tone_container)
        loadingText = panel.findViewById(R.id.loading_text)
        emptyText = panel.findViewById(R.id.empty_text)
        suggestionsContainer = panel.findViewById(R.id.suggestions_container)

        Constants.TONES.forEach { tone ->
            val chip = layoutInflater.inflate(R.layout.tone_chip, chipRow, false) as TextView
            chip.text = tone.label
            chip.setOnClickListener {
                selectedTone = tone
                updateChipColors()
                showCachedTone(tone.value)
            }
            chipRow?.addView(chip)
        }
        updateChipColors()
        
        root.addView(panel)

        val kv = KeyboardView(this, null).apply {
            keyboard = qwertyKeyboard
            isPreviewEnabled = true
            setOnKeyboardActionListener(this@WordToneIME)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        keyboardView = kv
        root.addView(kv)
        return root
    }

    private fun updateChipColors() {
        val row = chipRow ?: return
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            chip.isSelected = (Constants.TONES[i].value == selectedTone.value)
        }
    }

    // ONE API call — fetches all 8 tones × 3 suggestions
    private fun fetchAllTones() {
        val text = getCurrentInputText()
        if (text.isBlank()) {
            Toast.makeText(this, "Type something first!", Toast.LENGTH_SHORT).show()
            return
        }

        // Same text → use cache, zero API calls
        if (text == lastFetchedText && cachedRewrites.isNotEmpty()) {
            showCachedTone(selectedTone.value)
            return
        }

        loadingText?.visibility = View.VISIBLE
        emptyText?.visibility = View.GONE
        suggestionsContainer?.removeAllViews()

        scope.launch {
            try {
                val response = RetrofitClient.apiService.rewriteAll(
                    RewriteAllRequest(text = text)
                )
                cachedRewrites = response.rewrites
                lastFetchedText = text
                loadingText?.visibility = View.GONE
                showCachedTone(selectedTone.value)

            } catch (e: HttpException) {
                loadingText?.visibility = View.GONE
                emptyText?.text = when (e.code()) {
                    429 -> "Quota exhausted. Try tomorrow."
                    404 -> "Server endpoint not found."
                    else -> "Server error (${e.code()})."
                }
                emptyText?.visibility = View.VISIBLE
            } catch (e: IOException) {
                loadingText?.visibility = View.GONE
                emptyText?.text = "No internet connection."
                emptyText?.visibility = View.VISIBLE
            } catch (e: Exception) {
                loadingText?.visibility = View.GONE
                emptyText?.text = "Error: ${e.message?.take(80)}"
                emptyText?.visibility = View.VISIBLE
            }
        }
    }

    // Show 3 suggestions for selected tone — instant, no network
    private fun showCachedTone(tone: String) {
        val container = suggestionsContainer ?: return
        container.removeAllViews()

        if (cachedRewrites.isEmpty()) {
            emptyText?.text = "Tap ReWrite to generate suggestions."
            emptyText?.visibility = View.VISIBLE
            return
        }

        emptyText?.visibility = View.GONE
        val variations = cachedRewrites[tone]

        if (variations.isNullOrEmpty()) {
            emptyText?.text = "No suggestions for this tone."
            emptyText?.visibility = View.VISIBLE
            return
        }

        variations.forEach { rewrite ->
            val suggestionView = layoutInflater.inflate(R.layout.suggestion_item, container, false) as LinearLayout
            val textView = suggestionView.findViewById<TextView>(R.id.suggestion_text)
            textView.text = rewrite
            suggestionView.setOnClickListener { injectText(rewrite) }
            container.addView(suggestionView)
        }
    }

    private fun getCurrentInputText(): String {
        return try {
            val ic = currentInputConnection ?: return ""
            val req = ExtractedTextRequest().apply { hintMaxChars = 1000 }
            ic.getExtractedText(req, 0)?.text?.toString()?.trim() ?: ""
        } catch (e: Exception) { "" }
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

    // ── KeyboardView listener ──
    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> ic.deleteSurroundingText(1, 0)
            Keyboard.KEYCODE_DONE -> ic.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_ENTER))
            Keyboard.KEYCODE_SHIFT -> {
                isCaps = !isCaps
                qwertyKeyboard?.isShifted = isCaps
                keyboardView?.invalidateAllKeys()
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                if (currentMode == 0) {
                    currentMode = 1
                    keyboardView?.keyboard = symbolsKeyboard
                } else {
                    currentMode = 0
                    keyboardView?.keyboard = qwertyKeyboard
                }
            }
            -10 -> {
                currentMode = 2
                keyboardView?.keyboard = emojiKeyboard
            }
            32 -> ic.commitText(" ", 1)
            else -> {
                val code = if (isCaps && primaryCode in 97..122) primaryCode - 32 else primaryCode
                ic.commitText(String(Character.toChars(code)), 1)
                if (isCaps) {
                    isCaps = false
                    qwertyKeyboard?.isShifted = false
                    keyboardView?.invalidateAllKeys()
                }
            }
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) { currentInputConnection?.commitText(text, 1) }
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        suggestionsContainer?.removeAllViews()
        loadingText?.visibility = View.GONE
        emptyText?.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
