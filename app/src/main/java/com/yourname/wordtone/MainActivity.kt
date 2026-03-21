package com.yourname.wordtone

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<MaterialTextView>(R.id.status_text)
        val enableBtn = findViewById<MaterialButton>(R.id.btn_enable_keyboard)
        val selectBtn = findViewById<MaterialButton>(R.id.btn_select_keyboard)

        enableBtn.setOnClickListener {
            // Opens Android keyboard settings
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        selectBtn.setOnClickListener {
            // Shows the keyboard picker dialog
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Check if keyboard is already enabled
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any {
            it.packageName == packageName
        }

        if (isEnabled) {
            statusText.text = "Keyboard is enabled! Now select it as active."
            enableBtn.text = "Keyboard already enabled"
            enableBtn.isEnabled = false
        } else {
            statusText.text = "Step 1: Enable the Word Tone keyboard in Settings."
        }
    }
}
