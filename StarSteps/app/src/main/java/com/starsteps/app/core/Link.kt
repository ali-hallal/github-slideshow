package com.starsteps.app.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Two parents' phones talking over the home Wi-Fi, with no server in between.
 *
 * **Linking** (once): the phones agree on a secret by Diffie-Hellman and each shows a 6-digit
 * code made from it. The codes match only if no one stepped in between; once the parent with the
 * data confirms, that phone sends the family's key and data, sealed with the agreed secret.
 *
 * **Syncing** (whenever both are open): each sends its version of the family, sealed with the
 * family's key (AES-GCM: unreadable, and refused if altered, without the key); the other merges
 * it with its own ([Merge]) and sends the result back.
 */
object Link {
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
    fun newFamilyId(): String = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** A short, public tag of the family (to find its other phone on the network without saying who it is). */
    fun tag(familyId: String): String = sha256(("starsteps-family:" + familyId).toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    /** Throws if the key is wrong or the data was altered. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed.copyOfRange(12, sealed.size))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    // ------------------------------------------------------------------ frames on a socket

    private const val MAX = 32 shl 20

    fun send(s: Socket, kind: Char, body: ByteArray = ByteArray(0)) {
        val out = DataOutputStream(s.getOutputStream())
        out.writeByte(kind.code)
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    fun receive(s: Socket): Pair<Char, ByteArray> {
        val inp = DataInputStream(s.getInputStream())
        val kind = inp.readUnsignedByte().toChar()
        val n = inp.readInt()
        require(n in 0..MAX) { "frame too large" }
        val body = ByteArray(n)
        inp.readFully(body)
        return kind to body
    }

    // ------------------------------------------------------------------ linking

    /** Each side's half of the key agreement. */
    class Handshake {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public: ByteArray get() = pair.public.encoded

        /** The shared secret with the other side's [other] public key: (the code both phones show, the sealing key). */
        fun agree(other: ByteArray, mine: ByteArray, theirsFirst: Boolean): Pair<String, ByteArray> {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(pair.private)
            ka.doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(other)), true)
            val secret = ka.generateSecret()
            // Both sides hash the two public keys in the same order: the joining phone's first.
            val transcript = if (theirsFirst) other + mine else mine + other
            val h = sha256(secret + transcript)
            val n = ((h[0].toInt() and 0xFF) shl 24 or ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL
            val code = "%06d".format(n % 1_000_000)
            val key = sha256("starsteps-link".toByteArray() + secret + transcript)
            return code to key
        }
    }

    /** What the joining phone receives. */
    class Joined(val familyId: String, val key: ByteArray, val familyJson: String)

    /**
     * The phone with the data, on a connection from the joining phone: [confirm] shows the
     * code and returns whether the parent says the two codes match (it may wait for them).
     */
    fun hostPairing(s: Socket, familyId: String, key: ByteArray, familyJson: () -> String, confirm: (String) -> Boolean,
                    first: Pair<Char, ByteArray> = receive(s)): Boolean {
        val (k, theirs) = first
        require(k == 'H') { "not a link request" }
        val hs = Handshake()
        send(s, 'H', hs.public)
        val (code, sealKey) = hs.agree(theirs, hs.public, theirsFirst = true)
        if (!confirm(code)) { send(s, 'N'); return false }
        val payload = Json.write(mapOf("family" to familyId, "key" to key.joinToString("") { "%02x".format(it) }, "data" to familyJson()))
        send(s, 'J', seal(sealKey, payload.toByteArray()))
        return true
    }

    /**
     * The joining phone: [showCode] is told the code as soon as it is known; null if the other
     * parent said the codes don't match.
     */
    fun joinPairing(s: Socket, showCode: (String) -> Unit): Joined? {
        val hs = Handshake()
        send(s, 'H', hs.public)
        val (k, theirs) = receive(s)
        require(k == 'H') { "not a StarSteps phone" }
        val (code, sealKey) = hs.agree(theirs, hs.public, theirsFirst = false)
        showCode(code)
        val (k2, body) = receive(s)
        if (k2 != 'J') return null
        @Suppress("UNCHECKED_CAST")
        val m = Json.read(String(open(sealKey, body))) as Map<String, Any?>
        val hex = m["key"] as String
        return Joined(m["family"] as String, ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, m["data"] as String)
    }

    // ------------------------------------------------------------------ syncing

    /** The phone that called: sends its family, gets back the merged one. */
    fun syncAsClient(s: Socket, familyId: String, key: ByteArray, mineJson: String): String {
        send(s, 'S', tag(familyId).toByteArray())
        send(s, 'D', seal(key, mineJson.toByteArray()))
        val (k, body) = receive(s)
        require(k == 'D') { "refused" }
        return String(open(key, body))
    }

    /** The phone called: [merge] takes the caller's family (text) and returns the merged one. */
    fun syncAsServer(s: Socket, familyId: String, key: ByteArray, merge: (String) -> String, first: Pair<Char, ByteArray> = receive(s)) {
        val (k, tagBytes) = first
        require(k == 'S' && String(tagBytes) == tag(familyId)) { "another family" }
        val (k2, body) = receive(s)
        require(k2 == 'D')
        val theirs = String(open(key, body))
        send(s, 'D', seal(key, merge(theirs).toByteArray()))
    }
}
