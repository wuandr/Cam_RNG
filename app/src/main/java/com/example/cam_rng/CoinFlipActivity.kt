package com.example.cam_rng

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.example.cam_rng.databinding.ActivityMainBinding
import com.example.cam_rng.databinding.ViewCameraSeedBinding

class CoinFlipActivity : BaseCameraSeedActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var seedBinding: ViewCameraSeedBinding

    override lateinit var statusText: TextView
    override val resultText: TextView
        get() = seedBinding.resultText
    override lateinit var previewImage: ImageView
    override lateinit var actionButton: Button
    override lateinit var saveButton: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seedBinding = binding.cameraSeed
        statusText = seedBinding.statusText
        previewImage = seedBinding.framePreview
        actionButton = seedBinding.actionButton
        saveButton = seedBinding.saveButton

        seedBinding.digitsRow.visibility = View.GONE
        seedBinding.resultText.visibility = View.GONE
        seedBinding.actionButton.setText(R.string.flip_button)

        initCameraSeedUi()
    }

    override fun buildSeedResult(seedBytes: ByteArray): SeedResult {
        val isHeads = SeededRng.coinFlip(seedBytes)
        val statusId = if (isHeads) R.string.status_heads else R.string.status_tails
        return SeedResult(
            statusId,
            galleryTitle = getString(R.string.gallery_title_coin_flip),
            galleryValue = getString(statusId)
        )
    }

    override fun renderSeedResult(result: SeedResult) {
        statusText.text = getString(result.statusResId)
    }
}
