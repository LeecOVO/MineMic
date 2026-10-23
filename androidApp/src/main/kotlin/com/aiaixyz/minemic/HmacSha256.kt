package com.aiaixyz.minemic

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Capture and reference workers share this signer; Mac itself is not thread-safe. */
internal class HmacSha256 {
    private var mac: Mac? = null
    private var secret: ByteArray? = null

    @Synchronized fun authenticate(body: ByteArray, key: ByteArray): ByteArray {
        val signer = mac ?: Mac.getInstance("HmacSHA256").also { mac = it }
        if (secret?.contentEquals(key) != true) {
            signer.init(SecretKeySpec(key, "HmacSHA256"))
            secret?.fill(0)
            secret = key.copyOf()
        }
        // doFinal restores the initialized state, retaining the same key.
        return signer.doFinal(body)
    }

    @Synchronized fun clear() {
        secret?.fill(0)
        secret = null
        mac = null
    }
}
