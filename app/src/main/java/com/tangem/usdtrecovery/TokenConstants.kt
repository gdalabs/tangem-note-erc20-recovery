package com.tangem.usdtrecovery

/**
 * Centralized token definitions used across the app.
 */
object TokenConstants {

    data class TokenInfo(
        val symbol: String,
        val name: String,
        val contractAddress: String,
        val decimals: Int
    )

    /** Popular ERC-20 tokens on Ethereum Mainnet */
    val PRESET_TOKENS: Map<String, TokenInfo> = mapOf(
        "USDT" to TokenInfo("USDT", "Tether USD", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6),
        "USDC" to TokenInfo("USDC", "USD Coin", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6),
        "DAI" to TokenInfo("DAI", "Dai Stablecoin", "0x6B175474E89094C44Da98b954EescdeCB5BE3d823", 18),
        "WETH" to TokenInfo("WETH", "Wrapped Ether", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", 18),
        "LINK" to TokenInfo("LINK", "Chainlink Token", "0x514910771AF9Ca656af840dff83E8264EcF986CA", 18),
        "UNI" to TokenInfo("UNI", "Uniswap", "0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", 18)
    )
}
