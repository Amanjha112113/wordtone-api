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

    // Cache — fetched once, reused for all tone switches
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
    private var keyboardView: KeyboardView? = null

    override fun onCreateInputView(): View {
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.symbols)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C1E.toInt())
        }
        root.addView(buildPanel())

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

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C1E.toInt())
            setPadding(0, 14, 0, 10)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 16, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Word Tone"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rewriteBtn = Button(this).apply {
            text = "ReWrite"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF6C5CE7.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 72)
            // ONE API call — fetches all tones at once
            setOnClickListener { fetchAllTones() }
        }
        header.addView(title)
        header.addView(rewriteBtn)
        panel.addView(header)

        // Tone chips — switching is LOCAL, no API call
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = 8 }
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 0, 16, 0)
        }
        chipRow = chips

        Constants.TONES.forEach { tone ->
            val chip = TextView(this).apply {
                text = tone.label
                textSize = 11f
                setPadding(20, 10, 20, 10)
                setBackgroundColor(
                    if (tone.value == selectedTone.value) 0xFF6C5CE7.toInt()
                    else 0xFF3A3A3C.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = 8 }
                setOnClickListener {
                    selectedTone = tone
                    updateChipColors()
                    // Show cached result instantly — NO API call
                    showCachedTone(tone.value)
                }
            }
            chips.addView(chip)
        }
        scroll.addView(chips)
        panel.addView(scroll)

        // Loading
        val loading = TextView(this).apply {
            text = "Rewriting all tones..."
            textSize = 11f
            setTextColor(0xFFAEAEB2.toInt())
            visibility = View.GONE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = 6 }
        }
        loadingText = loading
        panel.addView(loading)

        // Error
        val empty = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFFF6B6B.toInt())
            visibility = View.GONE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = 6 }
        }
        emptyText = empty
        panel.addView(empty)

        // Suggestions
        val sugg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 6, 12, 0)
        }
        suggestionsContainer = sugg
        panel.addView(sugg)

        return panel
    }

    private fun updateChipColors() {
        val row = chipRow ?: return
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            chip.setBackgroundColor(
                if (Constants.TONES[i].value == selectedTone.value) 0xFF6C5CE7.toInt()
                else 0xFF3A3A3C.toInt())
        }
    }

    // Called when user taps ReWrite — ONE API call for ALL tones
    private fun fetchAllTones() {
        val text = getCurrentInputText()
        if (text.isBlank()) {
            Toast.makeText(this, "Type something first!", Toast.LENGTH_SHORT).show()
            return
        }

        // If same text → show cached instantly, no API call
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
                emptyText?.text = if (e.code() == 429) "Daily quota exhausted." else "Server error."
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

    // Show cached result for selected tone — instant, no API call
    private fun showCachedTone(tone: String) {
        val container = suggestionsContainer ?: return
        container.removeAllViews()

        if (cachedRewrites.isEmpty()) {
            emptyText?.text = "Tap ReWrite first."
            emptyText?.visibility = View.VISIBLE
            return
        }

        emptyText?.visibility = View.GONE
        val variations = cachedRewrites[tone] ?: return

        variations.forEach { rewrite ->
            val item = TextView(this).apply {
                text = rewrite
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF3A3A3C.toInt())
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.bottomMargin = 6
                }
                setOnClickListener { injectText(rewrite) }
            }
            container.addView(item)
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
                isSymbols = !isSymbols
                keyboardView?.keyboard = if (isSymbols) symbolsKeyboard else qwertyKeyboard
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
        // Keep cache — don't clear it
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
