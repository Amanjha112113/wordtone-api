package com.yourname.wordtone

import android.content.Context
import android.widget.Toast

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun String.truncate(maxLength: Int = 200): String {
    return if (this.length > maxLength) this.substring(0, maxLength) + "..." else this
}
