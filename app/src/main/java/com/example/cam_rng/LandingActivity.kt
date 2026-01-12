package com.example.cam_rng

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.cam_rng.databinding.ActivityLandingBinding

class LandingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLandingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener {
            startActivity(Intent(this, CoinFlipActivity::class.java))
        }

        binding.luckyButton.setOnClickListener {
            startActivity(Intent(this, LuckyNumbersActivity::class.java))
        }
    }
}
