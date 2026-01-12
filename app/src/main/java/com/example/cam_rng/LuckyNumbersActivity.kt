package com.example.cam_rng

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.example.cam_rng.databinding.ActivityLuckyNumbersBinding
import com.example.cam_rng.databinding.ViewCameraSeedBinding

class LuckyNumbersActivity : BaseCameraSeedActivity() {
    private lateinit var binding: ActivityLuckyNumbersBinding
    private lateinit var seedBinding: ViewCameraSeedBinding

    override lateinit var statusText: TextView
    override lateinit var previewImage: ImageView
    override lateinit var actionButton: Button
    override lateinit var saveButton: Button
    private var pendingLength = 0
    private val maxLuckyDigits by lazy { resources.getInteger(R.integer.max_lucky_digits) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLuckyNumbersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seedBinding = binding.cameraSeed
        statusText = seedBinding.statusText
        previewImage = seedBinding.framePreview
        actionButton = seedBinding.actionButton
        saveButton = seedBinding.saveButton

        seedBinding.digitsRow.visibility = View.VISIBLE
        seedBinding.resultText.visibility = View.VISIBLE
        seedBinding.actionButton.setText(R.string.lucky_numbers_generate)

        initCameraSeedUi()
        resetUi()
    }

    override fun prepareCapture(): Boolean {
        val length = parseLengthInput()
        if (length == null) {
            setStatus(R.string.status_invalid_digits, maxLuckyDigits)
            resetUi()
            return false
        }
        pendingLength = length
        resetUi()
        return true
    }

    override fun buildSeedResult(seedBytes: ByteArray): SeedResult {
        val luckyNumbers = SeededRng.luckyDigits(seedBytes, pendingLength)
        return SeedResult(R.string.status_lucky_numbers, luckyNumbers)
    }

    override fun renderSeedResult(result: SeedResult) {
        statusText.text = getString(result.statusResId)
        seedBinding.resultText.text = result.resultText ?: ""
    }

    override fun resetUi() {
        seedBinding.resultText.text = ""
    }

    private fun parseLengthInput(): Int? {
        val text = seedBinding.digitsInput.text?.toString()?.trim().orEmpty()
        val length = text.toIntOrNull() ?: return null
        if (length < 1 || length > maxLuckyDigits) {
            return null
        }
        return length
    }
}
