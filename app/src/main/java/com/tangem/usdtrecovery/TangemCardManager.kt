package com.tangem.usdtrecovery

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.jce.ECNamedCurveTable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Tangem Note Card Manager
 * Handles NFC communication with Tangem Note cards using APDU commands
 * Based on Tangem SDK CommandApdu.kt implementation
 */
class TangemCardManager {

    data class CardInfo(
        val cardId: String,
        val publicKey: ByteArray,
        val walletAddress: String,
        val curve: String,
        val firmwareVersion: String = "",
        val rawPublicKeyHex: String = ""
    )

    data class SignResult(
        val signature: ByteArray
    )

    companion object {
        private const val TAG = "TangemCardManager"
        
        // CLA byte for Tangem commands
        private const val ISO_CLA: Int = 0x00

        // Instruction codes
        private const val INS_READ: Int = 0xF2
        private const val INS_SIGN: Int = 0xFB

        // TLV Tags
        private const val TAG_CARD_ID: Int = 0x01
        private const val TAG_PIN: Int = 0x10
        private const val TAG_PIN2: Int = 0x11
        private const val TAG_TRANSACTION_OUT_HASH: Int = 0x50
        private const val TAG_TRANSACTION_OUT_HASH_SIZE: Int = 0x51
        private const val TAG_WALLET_PUBLIC_KEY: Int = 0x60
        private const val TAG_WALLET_SIGNATURE: Int = 0x61
        private const val TAG_FIRMWARE: Int = 0x80
        private const val TAG_CARD_PUBLIC_KEY: Int = 0x02
        private const val TAG_WALLET_PUBLIC_KEY_ALT: Int = 0x62
        private const val TAG_REMAINING_PAUSE: Int = 0x1C  // Remaining pause time

        // Tangem Wallet AID
        private val TANGEM_WALLET_AID = byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x08, 0x12, 0x01, 0x02, 0x08
        )

        // Default PIN1: SHA256("000000")
        private val DEFAULT_PIN: ByteArray by lazy {
            MessageDigest.getInstance("SHA-256").digest("000000".toByteArray(Charsets.UTF_8))
        }

        // Default PIN2: SHA256("000") - Note: different from PIN1!
        private val DEFAULT_PIN2: ByteArray by lazy {
            MessageDigest.getInstance("SHA-256").digest("000".toByteArray(Charsets.UTF_8))
        }

        // Status words
        private const val SW_SUCCESS = 0x9000
        private const val SW_NEED_PAUSE = 0x9789  // Card needs pause, retry the command
        
        // Maximum retries for SW_NEED_PAUSE
        private const val MAX_PAUSE_RETRIES = 30
        private const val PAUSE_DELAY_MS = 1000L
    }

    private var workingPin: ByteArray = DEFAULT_PIN
    private var storedCardId: ByteArray? = null

    /**
     * Read card information including public key
     */
    fun readCard(tag: Tag): Result<CardInfo> {
        return try {
            val isoDep = IsoDep.get(tag) ?: return Result.failure(Exception("IsoDep not supported"))
            isoDep.timeout = 30000
            
            Log.d(TAG, "Connecting to card...")
            isoDep.connect()
            Log.d(TAG, "Connected. MaxTransceiveLength: ${isoDep.maxTransceiveLength}, ExtendedLengthApduSupported: ${isoDep.isExtendedLengthApduSupported}")

            try {
                Log.d(TAG, "Selecting Tangem applet...")
                val selectResponse = selectTangemApplet(isoDep)
                val selectSw = getStatusWord(selectResponse)
                Log.d(TAG, "SELECT response SW: ${selectSw.toString(16)}")
                
                if (!isSuccess(selectResponse)) {
                    return Result.failure(Exception("Failed to select Tangem applet: ${selectSw.toString(16)}"))
                }

                val tlvData = buildPinOnlyTlv()
                Log.d(TAG, "TLV data (PIN1 only): ${tlvData.toHexString()}")
                
                val commands = listOf(
                    "SDK Extended (no Le)" to buildSdkExtendedApdu(tlvData),
                    "Short (no Le)" to buildShortApduNoLe(tlvData),
                    "Short (with Le)" to buildShortApduWithLe(tlvData),
                    "Extended (with Le)" to buildExtendedApduWithLe(tlvData)
                )

                var lastError = ""
                for ((name, command) in commands) {
                    Log.d(TAG, "Trying $name: ${command.toHexString()}")
                    
                    try {
                        val response = isoDep.transceive(command)
                        val sw = getStatusWord(response)
                        Log.d(TAG, "$name response length: ${response.size}, SW: ${sw.toString(16)}")

                        if (isSuccess(response)) {
                            val cardInfo = parseReadResponse(response)
                            Log.d(TAG, "Card read successful. Address: ${cardInfo.walletAddress}")
                            return Result.success(cardInfo)
                        }
                        lastError = "$name: SW=${sw.toString(16)}"
                    } catch (e: Exception) {
                        Log.e(TAG, "Error with $name: ${e.message}")
                        lastError = "$name: ${e.message}"
                    }
                }

                Result.failure(Exception("Failed to read card: $lastError"))
            } finally {
                isoDep.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading card", e)
            Result.failure(e)
        }
    }

    private fun buildPinOnlyTlv(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(TAG_PIN)
        output.write(DEFAULT_PIN.size)
        output.write(DEFAULT_PIN)
        return output.toByteArray()
    }

    private fun buildSdkExtendedApdu(tlvData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ISO_CLA)
        output.write(INS_READ)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write((tlvData.size shr 8) and 0xFF)
        output.write(tlvData.size and 0xFF)
        output.write(tlvData)
        return output.toByteArray()
    }

    private fun buildShortApduNoLe(tlvData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ISO_CLA)
        output.write(INS_READ)
        output.write(0x00)
        output.write(0x00)
        output.write(tlvData.size)
        output.write(tlvData)
        return output.toByteArray()
    }

    private fun buildShortApduWithLe(tlvData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ISO_CLA)
        output.write(INS_READ)
        output.write(0x00)
        output.write(0x00)
        output.write(tlvData.size)
        output.write(tlvData)
        output.write(0x00)
        return output.toByteArray()
    }

    private fun buildExtendedApduWithLe(tlvData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ISO_CLA)
        output.write(INS_READ)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write((tlvData.size shr 8) and 0xFF)
        output.write(tlvData.size and 0xFF)
        output.write(tlvData)
        output.write(0x00)
        output.write(0x00)
        return output.toByteArray()
    }

    /**
     * Sign a hash using the card
     * Handles SW_NEED_PAUSE (0x9789) by retrying the command
     */
    fun signHash(tag: Tag, hash: ByteArray, cardId: ByteArray? = null): Result<SignResult> {
        return try {
            val isoDep = IsoDep.get(tag) ?: return Result.failure(Exception("IsoDep not supported"))
            isoDep.timeout = 120000  // Increased timeout for signing with retries
            
            Log.d(TAG, "Connecting to card for signing...")
            isoDep.connect()

            try {
                Log.d(TAG, "Selecting Tangem applet for signing...")
                val selectResponse = selectTangemApplet(isoDep)
                if (!isSuccess(selectResponse)) {
                    return Result.failure(Exception("Failed to select Tangem applet: ${getStatusWord(selectResponse).toString(16)}"))
                }

                val cid = cardId ?: storedCardId ?: return Result.failure(Exception("Card ID not available. Please read the card first."))

                Log.d(TAG, "Signing hash: ${hash.toHexString()}")
                val signCommand = buildSignCommand(hash, cid)
                Log.d(TAG, "SIGN command: ${signCommand.toHexString()}")
                
                // Retry loop for SW_NEED_PAUSE
                var retryCount = 0
                while (retryCount < MAX_PAUSE_RETRIES) {
                    val signResponse = isoDep.transceive(signCommand)
                    val sw = getStatusWord(signResponse)
                    Log.d(TAG, "SIGN response (attempt ${retryCount + 1}): length=${signResponse.size}, SW=${sw.toString(16)}")

                    when (sw) {
                        SW_SUCCESS -> {
                            // Success! Parse and return the signature
                            val signature = parseSignResponse(signResponse)
                            Log.d(TAG, "Signature obtained: ${signature.toHexString()}")
                            return Result.success(SignResult(signature))
                        }
                        SW_NEED_PAUSE -> {
                            // Card needs more time, parse remaining time and wait
                            val remainingTime = parseRemainingPauseTime(signResponse)
                            Log.d(TAG, "SW_NEED_PAUSE received. Remaining time: ${remainingTime}ms. Retrying...")
                            
                            // Wait before retrying
                            Thread.sleep(PAUSE_DELAY_MS)
                            retryCount++
                        }
                        else -> {
                            // Other error
                            return Result.failure(Exception("Failed to sign: SW=${sw.toString(16)}"))
                        }
                    }
                }
                
                Result.failure(Exception("Signing timed out after $MAX_PAUSE_RETRIES retries"))
            } finally {
                isoDep.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error signing", e)
            Result.failure(e)
        }
    }

    /**
     * Parse remaining pause time from SW_NEED_PAUSE response
     */
    private fun parseRemainingPauseTime(response: ByteArray): Int {
        if (response.size <= 2) return 0
        
        val data = response.copyOfRange(0, response.size - 2)
        val tlvMap = parseTlv(data)
        
        val remainingPause = tlvMap[TAG_REMAINING_PAUSE]
        if (remainingPause != null && remainingPause.isNotEmpty()) {
            // Remaining pause time in milliseconds or seconds
            var time = 0
            for (b in remainingPause) {
                time = (time shl 8) or (b.toInt() and 0xFF)
            }
            return time
        }
        return 1000  // Default 1 second
    }

    private fun selectTangemApplet(isoDep: IsoDep): ByteArray {
        val selectCommand = ByteArrayOutputStream().apply {
            write(0x00)
            write(0xA4)
            write(0x04)
            write(0x00)
            write(TANGEM_WALLET_AID.size)
            write(TANGEM_WALLET_AID)
            write(0x00)
        }.toByteArray()

        Log.d(TAG, "SELECT APDU: ${selectCommand.toHexString()}")
        return isoDep.transceive(selectCommand)
    }

    private fun buildSignCommand(hash: ByteArray, cardId: ByteArray): ByteArray {
        val tlvData = ByteArrayOutputStream().apply {
            write(TAG_CARD_ID)
            write(cardId.size)
            write(cardId)
            
            write(TAG_TRANSACTION_OUT_HASH)
            write(hash.size)
            write(hash)
            
            write(TAG_TRANSACTION_OUT_HASH_SIZE)
            write(0x01)  // Length of the value (1 byte)
            write(0x20)  // Hash size = 32 bytes (SHA256)
            
            write(TAG_PIN)
            write(workingPin.size)
            write(workingPin)
            
            write(TAG_PIN2)
            write(DEFAULT_PIN2.size)
            write(DEFAULT_PIN2)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(ISO_CLA)
            write(INS_SIGN)
            write(0x00)
            write(0x00)
            write(0x00)
            write((tlvData.size shr 8) and 0xFF)
            write(tlvData.size and 0xFF)
            write(tlvData)
        }.toByteArray()
    }

    private fun parseReadResponse(response: ByteArray): CardInfo {
        val data = response.copyOfRange(0, response.size - 2)
        val tlvMap = parseTlv(data)
        
        Log.d(TAG, "=== TLV Tags in Response ===")
        for ((tag, value) in tlvMap) {
            Log.d(TAG, "Tag 0x${tag.toString(16)}: ${value.size} bytes = ${value.toHexString()}")
        }
        Log.d(TAG, "============================")

        val cardIdBytes = tlvMap[TAG_CARD_ID] ?: throw Exception("Card ID not found in response")
        storedCardId = cardIdBytes
        val cardId = cardIdBytes.toHexString()

        var publicKey = tlvMap[TAG_WALLET_PUBLIC_KEY]
        if (publicKey == null) {
            publicKey = tlvMap[TAG_WALLET_PUBLIC_KEY_ALT]
            if (publicKey != null) {
                Log.d(TAG, "Found public key in alternative tag 0x62")
            }
        }
        if (publicKey == null) {
            publicKey = tlvMap[TAG_CARD_PUBLIC_KEY]
            if (publicKey != null) {
                Log.d(TAG, "Found public key in card public key tag 0x02")
            }
        }
        
        if (publicKey == null) {
            throw Exception("Public key not found in response. Available tags: ${tlvMap.keys.map { "0x${it.toString(16)}" }}")
        }

        Log.d(TAG, "Raw public key (${publicKey.size} bytes): ${publicKey.toHexString()}")
        
        val walletAddress = publicKeyToAddress(publicKey)
        Log.d(TAG, "Derived wallet address: $walletAddress")

        val firmwareBytes = tlvMap[TAG_FIRMWARE]
        val firmware = firmwareBytes?.let { String(it, Charsets.UTF_8) } ?: ""

        return CardInfo(
            cardId = cardId,
            publicKey = publicKey,
            walletAddress = walletAddress,
            curve = "secp256k1",
            firmwareVersion = firmware,
            rawPublicKeyHex = publicKey.toHexString()
        )
    }

    private fun parseSignResponse(response: ByteArray): ByteArray {
        val data = response.copyOfRange(0, response.size - 2)
        val tlvMap = parseTlv(data)
        
        Log.d(TAG, "=== SIGN Response TLV Tags ===")
        for ((tag, value) in tlvMap) {
            Log.d(TAG, "Tag 0x${tag.toString(16)}: ${value.size} bytes = ${value.toHexString()}")
        }
        Log.d(TAG, "==============================")
        
        return tlvMap[TAG_WALLET_SIGNATURE] ?: throw Exception("Signature not found in response")
    }

    private fun parseTlv(data: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0

        while (offset < data.size) {
            if (offset + 2 > data.size) break

            val tag = data[offset].toInt() and 0xFF
            offset++

            var length = data[offset].toInt() and 0xFF
            offset++

            if (length == 0xFF && offset + 2 <= data.size) {
                length = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
            }

            if (offset + length > data.size) break

            val value = data.copyOfRange(offset, offset + length)
            result[tag] = value
            offset += length
        }

        return result
    }

    private fun publicKeyToAddress(publicKey: ByteArray): String {
        Log.d(TAG, "Converting public key to address. Key size: ${publicKey.size}")
        
        val uncompressedKey: ByteArray = when {
            publicKey.size == 65 && publicKey[0] == 0x04.toByte() -> {
                Log.d(TAG, "Public key is uncompressed (65 bytes with 04 prefix)")
                publicKey.copyOfRange(1, 65)
            }
            publicKey.size == 64 -> {
                Log.d(TAG, "Public key is 64 bytes (X + Y without prefix)")
                publicKey
            }
            publicKey.size == 33 && (publicKey[0] == 0x02.toByte() || publicKey[0] == 0x03.toByte()) -> {
                Log.d(TAG, "Public key is compressed (33 bytes), decompressing...")
                decompressPublicKey(publicKey)
            }
            publicKey.size == 32 -> {
                Log.w(TAG, "Public key is only 32 bytes (X coordinate only?), cannot derive Y")
                publicKey
            }
            else -> {
                Log.w(TAG, "Unknown public key format: ${publicKey.size} bytes")
                publicKey
            }
        }

        Log.d(TAG, "Key to hash (${uncompressedKey.size} bytes): ${uncompressedKey.toHexString()}")
        
        val keccak = Keccak.Digest256()
        val hash = keccak.digest(uncompressedKey)
        val addressBytes = hash.copyOfRange(12, 32)
        val address = "0x" + addressBytes.toHexString()
        
        Log.d(TAG, "Keccak256 hash: ${hash.toHexString()}")
        Log.d(TAG, "Final address: $address")
        
        return address
    }

    private fun decompressPublicKey(compressed: ByteArray): ByteArray {
        try {
            val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
            val point: ECPoint = spec.curve.decodePoint(compressed)
            val uncompressed = point.getEncoded(false)
            return uncompressed.copyOfRange(1, 65)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress public key: ${e.message}")
            return compressed
        }
    }

    private fun getStatusWord(response: ByteArray): Int {
        if (response.size < 2) return 0
        return ((response[response.size - 2].toInt() and 0xFF) shl 8) or
                (response[response.size - 1].toInt() and 0xFF)
    }

    private fun isSuccess(response: ByteArray): Boolean {
        return getStatusWord(response) == SW_SUCCESS
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
