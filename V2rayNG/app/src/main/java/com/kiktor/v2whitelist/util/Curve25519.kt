package com.kiktor.v2whitelist.util

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom

object Curve25519 {
    private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val P_MINUS_2 = P.subtract(BigInteger.valueOf(2))

    data class KeyPair(val privateKeyBase64: String, val publicKeyBase64: String)

    fun generateKeyPair(): KeyPair {
        val random = SecureRandom()
        val privBytes = ByteArray(32)
        random.nextBytes(privBytes)
        // Clamp private key according to RFC 7748
        privBytes[0] = (privBytes[0].toInt() and 248).toByte()
        privBytes[31] = ((privBytes[31].toInt() and 127) or 64).toByte()

        val pubBytes = scalarMultBase(privBytes)
        val privB64 = Base64.encodeToString(privBytes, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
        return KeyPair(privB64, pubB64)
    }

    fun scalarMultBase(kBytes: ByteArray): ByteArray {
        return scalarMult(kBytes, BigInteger.valueOf(9))
    }

    private fun scalarMult(kBytes: ByteArray, u: BigInteger): ByteArray {
        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val byteIndex = t / 8
            val bitIndex = t % 8
            val kt = (kBytes[byteIndex].toInt() ushr bitIndex) and 1
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = (x2.add(z2)).mod(P)
            val aa = (a.multiply(a)).mod(P)
            val b = (x2.subtract(z2)).mod(P)
            val bb = (b.multiply(b)).mod(P)
            val e = (aa.subtract(bb)).mod(P)
            val c = (x3.add(z3)).mod(P)
            val d = (x3.subtract(z3)).mod(P)
            val da = (d.multiply(a)).mod(P)
            val cb = (c.multiply(b)).mod(P)
            x3 = (da.add(cb)).pow(2).mod(P)
            z3 = (x1.multiply((da.subtract(cb)).pow(2))).mod(P)
            x2 = (aa.multiply(bb)).mod(P)
            z2 = (e.multiply(aa.add(A24.multiply(e)))).mod(P)
        }

        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        val result = (x2.multiply(z2.modPow(P_MINUS_2, P))).mod(P)
        val resBytes = ByteArray(32)
        val bigBytes = result.toByteArray()
        for (i in 0 until 32) {
            val srcIdx = bigBytes.size - 1 - i
            resBytes[i] = if (srcIdx >= 0) bigBytes[srcIdx] else 0
        }
        return resBytes
    }
}
