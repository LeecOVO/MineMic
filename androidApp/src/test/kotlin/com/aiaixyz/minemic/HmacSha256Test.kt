package com.aiaixyz.minemic

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacSha256Test {
    private fun reference(body: ByteArray, key: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(body)
    }

    @Test fun matchesKnownVectorAndResetsBetweenMessages() {
        val signer = HmacSha256()
        val key = ByteArray(20) { 0x0b }
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        repeat(20) {
            assertArrayEquals(expected, signer.authenticate("Hi There".toByteArray(), key))
            assertArrayEquals(reference(ByteArray(992), key), signer.authenticate(ByteArray(992), key))
        }
        signer.clear()
        assertArrayEquals(expected, signer.authenticate("Hi There".toByteArray(), key))
    }

    @Test fun keyChangesIncludingMutationNeverReuseOldAuthentication() {
        val signer = HmacSha256()
        val body = ByteArray(1008) { it.toByte() }
        val key = ByteArray(16) { 1 }
        assertArrayEquals(reference(body, key), signer.authenticate(body, key))
        key.fill(2)
        assertArrayEquals(reference(body, key), signer.authenticate(body, key))
        assertArrayEquals(ByteArray(16) { 2 }, key)
        signer.clear()
        assertArrayEquals(ByteArray(16) { 2 }, key)
    }

    @Test fun concurrentCaptureReferenceAndCleanupKeepSignaturesCorrect() {
        val signer = HmacSha256()
        val pool = Executors.newFixedThreadPool(3)
        try {
            pool.invokeAll((0..<3).map { worker -> Callable {
                val key = ByteArray(16) { worker.toByte() }
                val body = ByteArray(if (worker == 0) 992 else 1008) { (it + worker).toByte() }
                val expected = reference(body, key)
                repeat(200) {
                    assertArrayEquals(expected, signer.authenticate(body, key))
                    if (worker == 2 && it % 20 == 0) signer.clear()
                }
            } }).forEach { it.get() }
        } finally { pool.shutdownNow(); signer.clear() }
    }
}
