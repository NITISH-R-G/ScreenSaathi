package com.screensaathi

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The controlled screen ScreenSaathi guides the user through. Ordinary Android
 * views with stable resource ids — the accessibility layer reads these live, so
 * the highlight lands on the real field, not a hardcoded rectangle.
 */
class DemoTaskActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo_task)

        val amount = findViewById<EditText>(R.id.amount_field)
        val account = findViewById<EditText>(R.id.account_field)
        val submit = findViewById<Button>(R.id.submit_button)
        val status = findViewById<TextView>(R.id.status_line)

        submit.setOnClickListener {
            if (amount.text.isNullOrBlank() || account.text.isNullOrBlank()) {
                status.text = "Please fill both fields first."
                status.setTextColor(0xFFB00020.toInt())
            } else {
                status.text = "✓ Paid ₹${amount.text} for account ${account.text}"
                status.setTextColor(0xFF1B8A5A.toInt())
            }
        }
    }
}
