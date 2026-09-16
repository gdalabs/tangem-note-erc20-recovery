package com.tangem.usdtrecovery

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class EthereumHelperTest {
    private val helper = EthereumHelper()

    @Test
    fun `signing hash matches the published EIP-155 vector`() {
        val tx = EthereumHelper.TransactionData(
            BigInteger.valueOf(9), BigInteger("20000000000"),
            BigInteger.valueOf(21000), "0x3535353535353535353535353535353535353535",
            BigInteger("1000000000000000000"), byteArrayOf()
        )
        assertEquals(
            "daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53",
            helper.encodeTransactionForSigning(tx).joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `published signature recovers the expected card key`() {
        val curve = CustomNamedCurves.getByName("secp256k1")
        // Public test key from EIP-155, never used for real funds.
        val point = curve.g.multiply(BigInteger("46".repeat(32), 16))
        val hashHex = "daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53"
        val hash = hashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val r = BigInteger("18515461264373351373200002665853028612451056578545711640558177340181847433846")
        val signatureS = BigInteger("46948507304638947509940763649030358759909902576025900602547168820602576006531")
        assertEquals(0, helper.findRecoveryId(hash, r, signatureS, point.getEncoded(false)))
        assertEquals(0, helper.findRecoveryId(hash, r, signatureS, point.getEncoded(true)))
    }

    @Test
    fun `empty RLP list uses canonical encoding`() {
        val method = EthereumHelper::class.java.getDeclaredMethod("rlpEncode", List::class.java)
        method.isAccessible = true
        assertArrayEquals(byteArrayOf(0xc0.toByte()), method.invoke(helper, emptyList<ByteArray>()) as ByteArray)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `signature without a matching card key is rejected`() {
        val curve = CustomNamedCurves.getByName("secp256k1")
        helper.findRecoveryId(ByteArray(32), BigInteger.ONE, BigInteger.ONE, curve.g.getEncoded(false))
    }

    @Test
    fun `raw signature starting with DER marker is parsed as raw`() {
        val signature = ByteArray(64)
        signature[0] = 0x30
        signature[63] = 1
        val method = EthereumHelper::class.java.getDeclaredMethod("parseSignature", ByteArray::class.java)
        method.isAccessible = true
        val parsed = method.invoke(helper, signature) as Pair<*, *>
        assertEquals(BigInteger(1, signature.copyOfRange(0, 32)), parsed.first)
        assertEquals(BigInteger.ONE, parsed.second)
    }

    @Test
    fun `preset contracts are valid Ethereum addresses`() {
        for (token in TokenConstants.PRESET_TOKENS.values) {
            assertTrue(token.symbol, token.contractAddress.matches(Regex("0x[0-9a-fA-F]{40}")))
        }
        assertEquals(
            "0x6b175474e89094c44da98b954eedeac495271d0f",
            TokenConstants.PRESET_TOKENS.getValue("DAI").contractAddress.lowercase()
        )
    }
}
