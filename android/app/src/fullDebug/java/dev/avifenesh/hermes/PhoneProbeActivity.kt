package dev.avifenesh.hermes

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Debug-only device integration fixture. No production alarm or user data is touched. */
class PhoneProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        layout.addView(TextView(this).apply { text = "Phone control probe"; contentDescription = text; textSize = 22f })
        layout.addView(Switch(this).apply {
            text = "OEM probe switch"; isChecked = false; id = android.view.View.generateViewId()
            accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                override fun performAccessibilityAction(host: android.view.View, action: Int, arguments: Bundle?): Boolean {
                    // Android's interaction controller invokes the delegate directly.
                    return if (action == AccessibilityNodeInfo.ACTION_CLICK) true else super.performAccessibilityAction(host, action, arguments)
                }
            }
        })
        layout.addView(Switch(this).apply { text = "Normal probe switch"; isChecked = false; id = android.view.View.generateViewId() })
        val editor = EditText(this).apply { hint = "Probe text"; id = android.view.View.generateViewId() }
        layout.addView(editor)
        layout.addView(Button(this).apply {
            text = "Show keyboard"; setAllCaps(false)
            setOnClickListener {
                editor.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        })
        setContentView(layout)
    }
}
