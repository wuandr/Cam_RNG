package com.example.cam_rng

import java.security.MessageDigest
import java.security.SecureRandom

object SeededRng {
    private fun randomFromSeed(seedBytes: ByteArray): SecureRandom {
        val digest = MessageDigest.getInstance("SHA-256").digest(seedBytes)
        return SecureRandom().apply {
            setSeed(digest)
        }
    }

    fun coinFlip(seedBytes: ByteArray): Boolean {
        return randomFromSeed(seedBytes).nextBoolean()
    }

    fun luckyDigits(seedBytes: ByteArray, length: Int): String {
        val random = randomFromSeed(seedBytes)
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(random.nextInt(10))
        }
        return builder.toString()
    }
}
