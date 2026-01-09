package com.tangem.usdtrecovery

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.bouncycastle.math.ec.ECPoint
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.TimeUnit

class EthereumHelper {

    companion object {
        private const val TAG = "EthereumHelper"
        const val CHAIN_ID = 1L

        private val RPC_URLS = listOf(
            "https://eth.llamarpc.com",
            "https://rpc.ankr.com/eth",
            "https://ethereum.publicnode.com"
        )
        
        // secp256k1 curve order (n)
        private val SECP256K1_N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        private val SECP256K1_HALF_N = SECP256K1_N.shiftRight(1)
        
        // Popular ERC-20 tokens on Ethereum Mainnet
        val PRESET_TOKENS = mapOf(
            "USDT" to TokenInfo("0xdAC17F958D2ee523a2206206994597C13D831ec7", "Tether USD", "USDT", 6),
            "USDC" to TokenInfo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USD Coin", "USDC", 6),
            "DAI" to TokenInfo("0x6B175474E89094C44Da98b954EescdeCB5BE3d823", "Dai Stablecoin", "DAI", 18),
            "WETH" to TokenInfo("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "Wrapped Ether", "WETH", 18),
            "LINK" to TokenInfo("0x514910771AF9Ca656af840dff83E8264EcF986CA", "Chainlink", "LINK", 18),
            "UNI" to TokenInfo("0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", "Uniswap", "UNI", 18)
        )
    }
    
    data class TokenInfo(
        val contractAddress: String,
        val name: String,
        val symbol: String,
        val decimals: Int
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentRpcIndex = 0

    data class TransactionData(
        val nonce: BigInteger,
        val gasPrice: BigInteger,
        val gasLimit: BigInteger,
        val to: String,
        val value: BigInteger,
        val data: ByteArray
    )

    fun getEthBalance(address: String): Result<BigInteger> {
        return try {
            val requestJson = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "eth_getBalance")
                put("params", JSONArray().apply {
                    put(address)
                    put("latest")
                })
                put("id", 1)
            }

            val response = makeRpcCall(requestJson)
            val resultHex = response.getString("result")
            Result.success(BigInteger(resultHex.removePrefix("0x"), 16))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get ERC-20 token balance for any token
     */
    fun getTokenBalance(address: String, tokenContract: String): Result<BigInteger> {
        return try {
            val paddedAddress = address.removePrefix("0x").padStart(64, '0')
            val data = "0x70a08231$paddedAddress"

            val requestJson = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "eth_call")
                put("params", JSONArray().apply {
                    put(JSONObject().apply {
                        put("to", tokenContract)
                        put("data", data)
                    })
                    put("latest")
                })
                put("id", 1)
            }

            val response = makeRpcCall(requestJson)
            val resultHex = response.getString("result")

            if (resultHex == "0x" || resultHex.isEmpty()) {
                Result.success(BigInteger.ZERO)
            } else {
                Result.success(BigInteger(resultHex.removePrefix("0x"), 16))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Get token info (name, symbol, decimals) from contract
     */
    fun getTokenInfo(tokenContract: String): Result<TokenInfo> {
        return try {
            val name = getTokenName(tokenContract)
            val symbol = getTokenSymbol(tokenContract)
            val decimals = getTokenDecimals(tokenContract)
            
            Result.success(TokenInfo(tokenContract, name, symbol, decimals))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting token info", e)
            Result.failure(e)
        }
    }
    
    private fun getTokenName(tokenContract: String): String {
        return try {
            val data = "0x06fdde03" // name()
            val result = callContract(tokenContract, data)
            decodeString(result)
        } catch (e: Exception) {
            "Unknown Token"
        }
    }
    
    private fun getTokenSymbol(tokenContract: String): String {
        return try {
            val data = "0x95d89b41" // symbol()
            val result = callContract(tokenContract, data)
            decodeString(result)
        } catch (e: Exception) {
            "???"
        }
    }
    
    private fun getTokenDecimals(tokenContract: String): Int {
        return try {
            val data = "0x313ce567" // decimals()
            val result = callContract(tokenContract, data)
            if (result == "0x" || result.isEmpty()) {
                18 // Default to 18 decimals
            } else {
                BigInteger(result.removePrefix("0x"), 16).toInt()
            }
        } catch (e: Exception) {
            18 // Default to 18 decimals
        }
    }
    
    private fun callContract(contractAddress: String, data: String): String {
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "eth_call")
            put("params", JSONArray().apply {
                put(JSONObject().apply {
                    put("to", contractAddress)
                    put("data", data)
                })
                put("latest")
            })
            put("id", 1)
        }
        
        val response = makeRpcCall(requestJson)
        return response.getString("result")
    }
    
    private fun decodeString(hexData: String): String {
        if (hexData == "0x" || hexData.length < 130) {
            return "Unknown"
        }
        
        try {
            val data = hexData.removePrefix("0x")
            // Skip offset (32 bytes) and length (32 bytes), then read string
            val offset = BigInteger(data.substring(0, 64), 16).toInt() * 2
            val length = BigInteger(data.substring(offset, offset + 64), 16).toInt()
            val stringHex = data.substring(offset + 64, offset + 64 + length * 2)
            return stringHex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (e: Exception) {
            return "Unknown"
        }
    }

    /**
     * Create ERC-20 transfer transaction for any token
     */
    fun createTokenTransferTransaction(
        fromAddress: String,
        toAddress: String,
        amount: BigInteger,
        tokenContract: String
    ): Result<Pair<TransactionData, ByteArray>> {
        return try {
            val nonce = getNonce(fromAddress)
            val gasPrice = getGasPrice()
            val transferData = buildTransferData(toAddress, amount)
            val gasLimit = estimateGas(fromAddress, tokenContract, transferData)

            Log.d(TAG, "Transaction params: nonce=$nonce, gasPrice=$gasPrice, gasLimit=$gasLimit")

            val txData = TransactionData(
                nonce = nonce,
                gasPrice = gasPrice,
                gasLimit = gasLimit,
                to = tokenContract,
                value = BigInteger.ZERO,
                data = transferData
            )

            val txHash = encodeTransactionForSigning(txData)
            Log.d(TAG, "Transaction hash for signing: ${txHash.toHexString()}")
            Result.success(Pair(txData, txHash))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating transaction", e)
            Result.failure(e)
        }
    }

    fun broadcastTransaction(
        txData: TransactionData,
        signature: ByteArray,
        publicKey: ByteArray
    ): Result<String> {
        return try {
            Log.d(TAG, "=== Broadcasting Transaction ===")
            Log.d(TAG, "Raw signature (${signature.size} bytes): ${signature.toHexString()}")
            Log.d(TAG, "Public key (${publicKey.size} bytes): ${publicKey.toHexString()}")
            
            // Parse signature - handle both DER and raw formats
            val (r, s) = parseSignature(signature)
            Log.d(TAG, "Parsed r: ${r.toString(16)}")
            Log.d(TAG, "Parsed s: ${s.toString(16)}")
            
            // Normalize s to low-S form (required by Ethereum)
            val normalizedS = if (s > SECP256K1_HALF_N) {
                Log.d(TAG, "Normalizing s to low-S form")
                SECP256K1_N.subtract(s)
            } else {
                s
            }
            Log.d(TAG, "Normalized s: ${normalizedS.toString(16)}")
            
            val txHash = encodeTransactionForSigning(txData)
            Log.d(TAG, "Transaction hash: ${txHash.toHexString()}")
            
            val recoveryId = findRecoveryId(txHash, r, normalizedS, publicKey)
            Log.d(TAG, "Recovery ID: $recoveryId")
            
            val signedTx = createSignedTransaction(txData, r, normalizedS, recoveryId)
            Log.d(TAG, "Signed transaction: ${signedTx.toHexString()}")
            
            val txHashResult = sendRawTransaction(signedTx)
            Log.d(TAG, "Transaction hash result: $txHashResult")
            Result.success(txHashResult)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting transaction", e)
            Result.failure(e)
        }
    }
    
    private fun parseSignature(signature: ByteArray): Pair<BigInteger, BigInteger> {
        Log.d(TAG, "Parsing signature of ${signature.size} bytes")
        
        if (signature.isNotEmpty() && signature[0] == 0x30.toByte()) {
            Log.d(TAG, "Detected DER format signature")
            return parseDerSignature(signature)
        }
        
        if (signature.size == 64) {
            Log.d(TAG, "Detected raw 64-byte signature")
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            return Pair(r, s)
        }
        
        if (signature.size >= 64) {
            Log.d(TAG, "Signature is ${signature.size} bytes, trying to extract r and s")
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            return Pair(r, s)
        }
        
        throw Exception("Unknown signature format: ${signature.size} bytes")
    }
    
    private fun parseDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var offset = 0
        
        if (der[offset++] != 0x30.toByte()) {
            throw Exception("Invalid DER signature: missing sequence tag")
        }
        
        val seqLen = der[offset++].toInt() and 0xFF
        if (seqLen and 0x80 != 0) {
            val lenBytes = seqLen and 0x7F
            offset += lenBytes
        }
        
        if (der[offset++] != 0x02.toByte()) {
            throw Exception("Invalid DER signature: missing r integer tag")
        }
        val rLen = der[offset++].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        
        if (der[offset++] != 0x02.toByte()) {
            throw Exception("Invalid DER signature: missing s integer tag")
        }
        val sLen = der[offset++].toInt() and 0xFF
        val sBytes = der.copyOfRange(offset, offset + sLen)
        
        val r = BigInteger(1, rBytes)
        val s = BigInteger(1, sBytes)
        
        return Pair(r, s)
    }

    fun encodeTransactionForSigning(tx: TransactionData): ByteArray {
        val rlpEncoded = rlpEncode(
            listOf(
                rlpEncodeInteger(tx.nonce),
                rlpEncodeInteger(tx.gasPrice),
                rlpEncodeInteger(tx.gasLimit),
                tx.to.removePrefix("0x").hexToByteArray(),
                rlpEncodeInteger(tx.value),
                tx.data,
                rlpEncodeInteger(BigInteger.valueOf(CHAIN_ID)),
                byteArrayOf(),
                byteArrayOf()
            )
        )

        val keccak = Keccak.Digest256()
        return keccak.digest(rlpEncoded)
    }

    fun findRecoveryId(messageHash: ByteArray, r: BigInteger, s: BigInteger, publicKey: ByteArray): Int {
        val curveParams: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
        val curve = ECDomainParameters(
            curveParams.curve,
            curveParams.g,
            curveParams.n,
            curveParams.h
        )

        val uncompressedPubKey = if (publicKey.size == 33) {
            val point = curve.curve.decodePoint(publicKey)
            point.getEncoded(false)
        } else {
            publicKey
        }

        for (recoveryId in 0..3) {
            try {
                val recoveredKey = recoverPublicKey(messageHash, r, s, recoveryId, curve)
                if (recoveredKey != null) {
                    val recoveredEncoded = recoveredKey.getEncoded(false)
                    if (recoveredEncoded.contentEquals(uncompressedPubKey)) {
                        return recoveryId
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        Log.w(TAG, "Could not find recovery ID, defaulting to 0")
        return 0
    }

    private fun recoverPublicKey(
        messageHash: ByteArray,
        r: BigInteger,
        s: BigInteger,
        recoveryId: Int,
        curve: ECDomainParameters
    ): ECPoint? {
        val n = curve.n
        val i = BigInteger.valueOf((recoveryId / 2).toLong())
        val x = r.add(i.multiply(n))

        val prime = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
        if (x >= prime) {
            return null
        }

        val R = decompressKey(x, (recoveryId and 1) == 1, curve)
        if (!R.multiply(n).isInfinity) {
            return null
        }

        val e = BigInteger(1, messageHash)
        val eInv = BigInteger.ZERO.subtract(e).mod(n)
        val rInv = r.modInverse(n)
        val srInv = rInv.multiply(s).mod(n)
        val eInvrInv = rInv.multiply(eInv).mod(n)

        return curve.g.multiply(eInvrInv).add(R.multiply(srInv))
    }

    private fun decompressKey(xBN: BigInteger, yBit: Boolean, curve: ECDomainParameters): ECPoint {
        val compEnc = ByteArray(33)
        compEnc[0] = if (yBit) 0x03 else 0x02
        val xBytes = xBN.toByteArray()
        if (xBytes.size <= 32) {
            System.arraycopy(xBytes, 0, compEnc, 33 - xBytes.size, xBytes.size)
        } else {
            System.arraycopy(xBytes, xBytes.size - 32, compEnc, 1, 32)
        }
        return curve.curve.decodePoint(compEnc)
    }

    private fun createSignedTransaction(
        tx: TransactionData,
        r: BigInteger,
        s: BigInteger,
        recoveryId: Int
    ): ByteArray {
        val v = BigInteger.valueOf(CHAIN_ID * 2 + 35 + recoveryId)

        return rlpEncode(
            listOf(
                rlpEncodeInteger(tx.nonce),
                rlpEncodeInteger(tx.gasPrice),
                rlpEncodeInteger(tx.gasLimit),
                tx.to.removePrefix("0x").hexToByteArray(),
                rlpEncodeInteger(tx.value),
                tx.data,
                rlpEncodeInteger(v),
                bigIntegerToMinimalBytes(r),
                bigIntegerToMinimalBytes(s)
            )
        )
    }
    
    private fun bigIntegerToMinimalBytes(value: BigInteger): ByteArray {
        if (value == BigInteger.ZERO) {
            return byteArrayOf()
        }
        var bytes = value.toByteArray()
        if (bytes[0] == 0.toByte() && bytes.size > 1) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }

    private fun sendRawTransaction(signedTx: ByteArray): String {
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "eth_sendRawTransaction")
            put("params", JSONArray().apply {
                put("0x${signedTx.toHexString()}")
            })
            put("id", 1)
        }

        val response = makeRpcCall(requestJson)
        
        if (response.has("error")) {
            val error = response.getJSONObject("error")
            val code = error.optInt("code", 0)
            val message = error.optString("message", "Unknown error")
            throw Exception("RPC Error ($code): $message")
        }
        
        return response.getString("result")
    }

    private fun getNonce(address: String): BigInteger {
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "eth_getTransactionCount")
            put("params", JSONArray().apply {
                put(address)
                put("pending")
            })
            put("id", 1)
        }

        val response = makeRpcCall(requestJson)
        val resultHex = response.getString("result")
        return BigInteger(resultHex.removePrefix("0x"), 16)
    }

    private fun getGasPrice(): BigInteger {
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "eth_gasPrice")
            put("params", JSONArray())
            put("id", 1)
        }

        val response = makeRpcCall(requestJson)
        val resultHex = response.getString("result")
        val baseGasPrice = BigInteger(resultHex.removePrefix("0x"), 16)
        return baseGasPrice.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100))
    }

    private fun estimateGas(from: String, to: String, data: ByteArray): BigInteger {
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "eth_estimateGas")
            put("params", JSONArray().apply {
                put(JSONObject().apply {
                    put("from", from)
                    put("to", to)
                    put("data", "0x${data.toHexString()}")
                })
            })
            put("id", 1)
        }

        return try {
            val response = makeRpcCall(requestJson)
            val resultHex = response.getString("result")
            val estimated = BigInteger(resultHex.removePrefix("0x"), 16)
            estimated.multiply(BigInteger.valueOf(150)).divide(BigInteger.valueOf(100))
        } catch (e: Exception) {
            BigInteger.valueOf(100000)
        }
    }

    private fun buildTransferData(toAddress: String, amount: BigInteger): ByteArray {
        val methodId = "a9059cbb"
        val paddedAddress = toAddress.removePrefix("0x").padStart(64, '0')
        val paddedAmount = amount.toString(16).padStart(64, '0')
        return "$methodId$paddedAddress$paddedAmount".hexToByteArray()
    }

    private fun makeRpcCall(requestJson: JSONObject): JSONObject {
        var lastException: Exception? = null

        for (i in RPC_URLS.indices) {
            val rpcUrl = RPC_URLS[(currentRpcIndex + i) % RPC_URLS.size]
            try {
                val request = Request.Builder()
                    .url(rpcUrl)
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: throw Exception("Empty response")
                        currentRpcIndex = (currentRpcIndex + i) % RPC_URLS.size
                        return JSONObject(responseBody)
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("All RPC endpoints failed")
    }

    private fun rlpEncode(items: List<Any>): ByteArray {
        val encoded = items.map { item ->
            when (item) {
                is ByteArray -> rlpEncodeBytes(item)
                else -> throw IllegalArgumentException("Unsupported type")
            }
        }

        val totalLength = encoded.sumOf { it.size }
        return if (totalLength < 56) {
            byteArrayOf((0xc0 + totalLength).toByte()) + encoded.reduce { acc, bytes -> acc + bytes }
        } else {
            val lengthBytes = bigIntegerToBytes(BigInteger.valueOf(totalLength.toLong()))
            byteArrayOf((0xf7 + lengthBytes.size).toByte()) + lengthBytes + encoded.reduce { acc, bytes -> acc + bytes }
        }
    }

    private fun rlpEncodeBytes(bytes: ByteArray): ByteArray {
        return when {
            bytes.size == 1 && bytes[0].toInt() and 0xFF < 0x80 -> bytes
            bytes.isEmpty() -> byteArrayOf(0x80.toByte())
            bytes.size < 56 -> byteArrayOf((0x80 + bytes.size).toByte()) + bytes
            else -> {
                val lengthBytes = bigIntegerToBytes(BigInteger.valueOf(bytes.size.toLong()))
                byteArrayOf((0xb7 + lengthBytes.size).toByte()) + lengthBytes + bytes
            }
        }
    }

    private fun rlpEncodeInteger(value: BigInteger): ByteArray {
        return if (value == BigInteger.ZERO) {
            byteArrayOf()
        } else {
            var bytes = value.toByteArray()
            if (bytes[0] == 0.toByte() && bytes.size > 1) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            bytes
        }
    }

    private fun bigIntegerToBytes(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        if (bytes[0] == 0.toByte() && bytes.size > 1) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }

    private fun String.hexToByteArray(): ByteArray {
        val hex = this.removePrefix("0x")
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
