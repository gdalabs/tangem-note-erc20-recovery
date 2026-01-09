package com.tangem.usdtrecovery

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tangem.usdtrecovery.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        
        // Popular ERC-20 tokens on Ethereum Mainnet
        val PRESET_TOKENS = mapOf(
            "USDT" to TokenInfo("USDT", "Tether USD", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6),
            "USDC" to TokenInfo("USDC", "USD Coin", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6),
            "DAI" to TokenInfo("DAI", "Dai Stablecoin", "0x6B175474E89094C44Da98b954EescdeCB5BE3830", 18),
            "WETH" to TokenInfo("WETH", "Wrapped Ether", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", 18),
            "LINK" to TokenInfo("LINK", "Chainlink Token", "0x514910771AF9Ca656af840dff83E8264EcF986CA", 18),
            "UNI" to TokenInfo("UNI", "Uniswap", "0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", 18)
        )
    }

    data class TokenInfo(
        val symbol: String,
        val name: String,
        val contractAddress: String,
        val decimals: Int
    )

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null

    private val cardManager = TangemCardManager()
    private val ethHelper = EthereumHelper()

    private var currentCardInfo: TangemCardManager.CardInfo? = null
    private var ethBalance: BigInteger = BigInteger.ZERO
    private var tokenBalance: BigInteger = BigInteger.ZERO
    
    // Current selected token
    private var selectedToken: TokenInfo? = null
    private var isCustomToken = false

    // Pending transaction data
    private var pendingTransaction: EthereumHelper.TransactionData? = null
    private var pendingTxHash: ByteArray? = null
    private var pendingRecipient: String? = null
    private var pendingAmount: BigInteger? = null

    private enum class PendingAction {
        SCAN_CARD,
        SIGN_TRANSACTION
    }

    private var pendingAction: PendingAction? = null
    private var isSigningInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNfc()
        setupUI()
        handleIntent(intent)
    }

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showStatus("Error: NFC is not available on this device")
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            showStatus("Please enable NFC in settings")
        }

        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFilters = arrayOf(tagFilter, techFilter)

        techLists = arrayOf(
            arrayOf(IsoDep::class.java.name),
            arrayOf(NfcA::class.java.name),
            arrayOf(IsoDep::class.java.name, NfcA::class.java.name)
        )
    }

    private fun setupUI() {
        // Scan Card button
        binding.btnScanCard.setOnClickListener {
            pendingAction = PendingAction.SCAN_CARD
            showProgress(true)
            showStatus("Please tap your Tangem Note card to the back of your phone...")
        }

        // Token chip selection
        binding.chipUsdt.setOnClickListener { selectPresetToken("USDT") }
        binding.chipUsdc.setOnClickListener { selectPresetToken("USDC") }
        binding.chipDai.setOnClickListener { selectPresetToken("DAI") }
        binding.chipWeth.setOnClickListener { selectPresetToken("WETH") }
        binding.chipLink.setOnClickListener { selectPresetToken("LINK") }
        binding.chipUni.setOnClickListener { selectPresetToken("UNI") }
        binding.chipCustom.setOnClickListener { selectCustomToken() }

        // Load custom token button
        binding.btnLoadToken.setOnClickListener {
            val contractAddress = binding.etTokenContract.text.toString().trim()
            if (isValidEthereumAddress(contractAddress)) {
                loadCustomTokenInfo(contractAddress)
            } else {
                showStatus("Invalid contract address")
            }
        }

        // Amount input validation
        binding.etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateSendButton()
            }
        })

        // Recipient address validation
        binding.etRecipientAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateSendButton()
            }
        })

        // Max amount button
        binding.btnMaxAmount.setOnClickListener {
            val token = selectedToken ?: return@setOnClickListener
            if (tokenBalance > BigInteger.ZERO) {
                val maxAmount = BigDecimal(tokenBalance).divide(
                    BigDecimal.TEN.pow(token.decimals),
                    token.decimals,
                    RoundingMode.DOWN
                )
                binding.etAmount.setText(maxAmount.stripTrailingZeros().toPlainString())
            }
        }

        // Send button
        binding.btnSendToken.setOnClickListener {
            val recipientAddress = binding.etRecipientAddress.text.toString().trim()
            val amountStr = binding.etAmount.text.toString().trim()
            val token = selectedToken

            if (token == null) {
                showStatus("Please select a token first")
                return@setOnClickListener
            }

            if (!isValidEthereumAddress(recipientAddress)) {
                showStatus("Invalid recipient address")
                return@setOnClickListener
            }

            try {
                val amountDecimal = BigDecimal(amountStr)
                val amountInSmallestUnit = amountDecimal.multiply(
                    BigDecimal.TEN.pow(token.decimals)
                ).toBigInteger()

                if (amountInSmallestUnit <= BigInteger.ZERO) {
                    showStatus("Amount must be greater than 0")
                    return@setOnClickListener
                }

                if (amountInSmallestUnit > tokenBalance) {
                    showStatus("Insufficient ${token.symbol} balance")
                    return@setOnClickListener
                }

                // Show confirmation dialog
                AlertDialog.Builder(this)
                    .setTitle("Confirm Transfer")
                    .setMessage("Send $amountStr ${token.symbol} to\n$recipientAddress\n\nThis action cannot be undone.")
                    .setPositiveButton("Confirm") { _, _ ->
                        prepareTransaction(recipientAddress, amountInSmallestUnit, amountDecimal, token)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: NumberFormatException) {
                showStatus("Invalid amount format")
            }
        }
    }

    private fun selectPresetToken(symbol: String) {
        val token = PRESET_TOKENS[symbol] ?: return
        selectedToken = token
        isCustomToken = false
        
        // Hide custom token input
        binding.tilTokenContract.visibility = View.GONE
        binding.btnLoadToken.visibility = View.GONE
        
        // Load token balance if card is scanned
        currentCardInfo?.let { cardInfo ->
            loadTokenBalance(cardInfo.walletAddress, token)
        } ?: run {
            showTokenInfo(token, BigInteger.ZERO)
        }
    }

    private fun selectCustomToken() {
        isCustomToken = true
        selectedToken = null
        
        // Show custom token input
        binding.tilTokenContract.visibility = View.VISIBLE
        binding.btnLoadToken.visibility = View.VISIBLE
        binding.llTokenInfo.visibility = View.GONE
        
        // Clear token balance
        tokenBalance = BigInteger.ZERO
        validateSendButton()
    }

    private fun loadCustomTokenInfo(contractAddress: String) {
        val cardInfo = currentCardInfo
        if (cardInfo == null) {
            showStatus("Please scan your card first")
            return
        }
        
        showProgress(true)
        showStatus("Loading token info...")
        
        lifecycleScope.launch {
            try {
                val tokenInfoResult = withContext(Dispatchers.IO) {
                    ethHelper.getTokenInfo(contractAddress)
                }
                
                tokenInfoResult.fold(
                    onSuccess = { ethTokenInfo ->
                        val token = TokenInfo(ethTokenInfo.symbol, ethTokenInfo.name, contractAddress, ethTokenInfo.decimals)
                        selectedToken = token
                        
                        // Load balance
                        loadTokenBalance(cardInfo.walletAddress, token)
                    },
                    onFailure = { error ->
                        showStatus("Error loading token info: ${error.message}")
                        showProgress(false)
                    }
                )
            } catch (e: Exception) {
                showStatus("Error: ${e.message}")
                showProgress(false)
            }
        }
    }

    private fun loadTokenBalance(walletAddress: String, token: TokenInfo) {
        showProgress(true)
        
        lifecycleScope.launch {
            try {
                val balanceResult = withContext(Dispatchers.IO) {
                    ethHelper.getTokenBalance(walletAddress, token.contractAddress)
                }
                
                balanceResult.fold(
                    onSuccess = { balance ->
                        tokenBalance = balance
                        showTokenInfo(token, balance)
                        showStatus("Token balance loaded successfully!")
                    },
                    onFailure = { error ->
                        showStatus("Error loading balance: ${error.message}")
                        showTokenInfo(token, BigInteger.ZERO)
                    }
                )
            } catch (e: Exception) {
                showStatus("Error: ${e.message}")
            } finally {
                showProgress(false)
            }
        }
    }

    private fun showTokenInfo(token: TokenInfo, balance: BigInteger) {
        binding.llTokenInfo.visibility = View.VISIBLE
        binding.tvTokenName.text = "${token.name} (${token.symbol})"
        
        val balanceFormatted = BigDecimal(balance).divide(
            BigDecimal.TEN.pow(token.decimals),
            token.decimals,
            RoundingMode.DOWN
        ).stripTrailingZeros().toPlainString()
        
        binding.tvTokenBalance.text = "$balanceFormatted ${token.symbol}"
        
        // Enable transfer inputs
        binding.etRecipientAddress.isEnabled = true
        binding.etAmount.isEnabled = true
        binding.btnMaxAmount.isEnabled = balance > BigInteger.ZERO
        
        validateSendButton()
    }

    private fun validateSendButton() {
        val recipientAddress = binding.etRecipientAddress.text.toString().trim()
        val amountStr = binding.etAmount.text.toString().trim()
        val token = selectedToken

        var isValid = false

        if (token != null && 
            currentCardInfo != null && 
            isValidEthereumAddress(recipientAddress) && 
            amountStr.isNotEmpty()) {
            try {
                val amountDecimal = BigDecimal(amountStr)
                val amountInSmallestUnit = amountDecimal.multiply(
                    BigDecimal.TEN.pow(token.decimals)
                ).toBigInteger()

                isValid = amountInSmallestUnit > BigInteger.ZERO && 
                          amountInSmallestUnit <= tokenBalance
            } catch (e: NumberFormatException) {
                isValid = false
            }
        }

        binding.btnSendToken.isEnabled = isValid
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        Log.d(TAG, "handleIntent: action=$action")

        if (action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED) {

            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let { handleTag(it) }
        }
    }

    private fun handleTag(tag: Tag) {
        Log.d(TAG, "handleTag: pendingAction=$pendingAction, isSigningInProgress=$isSigningInProgress")

        // If signing is in progress or pending, always route to signing
        if (isSigningInProgress || pendingAction == PendingAction.SIGN_TRANSACTION) {
            Log.d(TAG, "Routing to SIGN_TRANSACTION")
            signTransaction(tag)
            return
        }

        when (pendingAction) {
            PendingAction.SCAN_CARD -> {
                Log.d(TAG, "Processing SCAN_CARD action")
                readCard(tag)
            }
            PendingAction.SIGN_TRANSACTION -> {
                Log.d(TAG, "Processing SIGN_TRANSACTION action")
                signTransaction(tag)
            }
            null -> {
                if (pendingTxHash != null) {
                    Log.d(TAG, "Have pending tx hash, routing to signing")
                    pendingAction = PendingAction.SIGN_TRANSACTION
                    signTransaction(tag)
                } else {
                    Log.d(TAG, "No pending action, defaulting to SCAN_CARD")
                    pendingAction = PendingAction.SCAN_CARD
                    showProgress(true)
                    readCard(tag)
                }
            }
        }
    }

    private fun readCard(tag: Tag) {
        Log.d(TAG, "readCard called")
        lifecycleScope.launch {
            try {
                showStatus("Reading card...")

                val result = withContext(Dispatchers.IO) {
                    cardManager.readCard(tag)
                }

                result.fold(
                    onSuccess = { cardInfo ->
                        Log.d(TAG, "Card read success: ${cardInfo.walletAddress}")
                        currentCardInfo = cardInfo
                        binding.tvWalletAddress.text = "Wallet: ${cardInfo.walletAddress}"
                        binding.etRecipientAddress.isEnabled = true
                        showStatus("Card read successfully!\n\nAddress: ${cardInfo.walletAddress}\n\nLoading ETH balance...")
                        loadEthBalance(cardInfo.walletAddress)
                        
                        // If token is already selected, load its balance
                        selectedToken?.let { token ->
                            loadTokenBalance(cardInfo.walletAddress, token)
                        }
                        
                        pendingAction = null
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Card read failed: ${error.message}", error)
                        showStatus("Error reading card: ${error.message}")
                        showProgress(false)
                        pendingAction = null
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in readCard: ${e.message}", e)
                showStatus("Error: ${e.message}")
                showProgress(false)
                pendingAction = null
            }
        }
    }

    private fun loadEthBalance(address: String) {
        lifecycleScope.launch {
            try {
                val ethResult = withContext(Dispatchers.IO) {
                    ethHelper.getEthBalance(address)
                }

                ethResult.fold(
                    onSuccess = { balance ->
                        ethBalance = balance
                        val ethFormatted = BigDecimal(balance).divide(
                            BigDecimal.TEN.pow(18),
                            8,
                            RoundingMode.DOWN
                        )
                        binding.tvEthBalance.text = "ETH Balance: $ethFormatted ETH"
                    },
                    onFailure = { error ->
                        binding.tvEthBalance.text = "ETH Balance: Error"
                    }
                )
            } catch (e: Exception) {
                binding.tvEthBalance.text = "ETH Balance: Error"
            } finally {
                showProgress(false)
            }
        }
    }

    private fun prepareTransaction(recipientAddress: String, amount: BigInteger, displayAmount: BigDecimal, token: TokenInfo) {
        val cardInfo = currentCardInfo ?: run {
            showStatus("Error: No card scanned")
            return
        }

        showProgress(true)
        showStatus("Preparing transaction...")

        lifecycleScope.launch {
            try {
                val txResult = withContext(Dispatchers.IO) {
                    ethHelper.createTokenTransferTransaction(
                        fromAddress = cardInfo.walletAddress,
                        toAddress = recipientAddress,
                        amount = amount,
                        tokenContract = token.contractAddress
                    )
                }

                txResult.fold(
                    onSuccess = { (txData, txHash) ->
                        pendingTransaction = txData
                        pendingTxHash = txHash
                        pendingRecipient = recipientAddress
                        pendingAmount = amount
                        pendingAction = PendingAction.SIGN_TRANSACTION
                        isSigningInProgress = true

                        showStatus("Transaction prepared!\n\nSending: $displayAmount ${token.symbol}\nTo: $recipientAddress\n\n*** IMPORTANT ***\nPlease tap your Tangem Note card and HOLD IT STEADY until signing is complete.")
                        showProgress(false)
                    },
                    onFailure = { error ->
                        showStatus("Error preparing transaction: ${error.message}")
                        showProgress(false)
                    }
                )
            } catch (e: Exception) {
                showStatus("Error: ${e.message}")
                showProgress(false)
            }
        }
    }

    private fun signTransaction(tag: Tag) {
        val txHash = pendingTxHash ?: run {
            showStatus("Error: No pending transaction")
            clearSigningState()
            return
        }

        showProgress(true)
        showStatus("Signing transaction with card...\n\nPlease keep the card steady!")

        lifecycleScope.launch {
            try {
                val signResult = withContext(Dispatchers.IO) {
                    cardManager.signHash(tag, txHash)
                }

                signResult.fold(
                    onSuccess = { result ->
                        showStatus("Transaction signed! Broadcasting...")
                        broadcastTransaction(result.signature)
                        isSigningInProgress = false
                    },
                    onFailure = { error ->
                        val errorMsg = error.message ?: "Unknown error"
                        Log.e(TAG, "Signing failed: $errorMsg")
                        showStatus("Signing failed: $errorMsg\n\n*** Please tap the card again to retry ***")
                        showProgress(false)
                        pendingAction = PendingAction.SIGN_TRANSACTION
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during signing: ${e.message}", e)
                showStatus("Error: ${e.message}\n\n*** Please tap the card again to retry ***")
                showProgress(false)
                pendingAction = PendingAction.SIGN_TRANSACTION
            }
        }
    }

    private fun broadcastTransaction(signature: ByteArray) {
        val txData = pendingTransaction ?: run {
            showStatus("Error: No pending transaction data")
            showProgress(false)
            return
        }

        val cardInfo = currentCardInfo ?: run {
            showStatus("Error: No card info")
            showProgress(false)
            return
        }

        lifecycleScope.launch {
            try {
                val broadcastResult = withContext(Dispatchers.IO) {
                    ethHelper.broadcastTransaction(txData, signature, cardInfo.publicKey)
                }

                broadcastResult.fold(
                    onSuccess = { txHash ->
                        showStatus("Transaction broadcast successfully!\n\nTx Hash: $txHash\n\nView on Etherscan:\nhttps://etherscan.io/tx/$txHash")
                        clearSigningState()
                        
                        // Refresh balances
                        currentCardInfo?.let { 
                            loadEthBalance(it.walletAddress)
                            selectedToken?.let { token ->
                                loadTokenBalance(it.walletAddress, token)
                            }
                        }
                    },
                    onFailure = { error ->
                        showStatus("Error broadcasting transaction: ${error.message}")
                        clearSigningState()
                    }
                )
            } catch (e: Exception) {
                showStatus("Error: ${e.message}")
                clearSigningState()
            } finally {
                showProgress(false)
            }
        }
    }

    private fun clearSigningState() {
        pendingTransaction = null
        pendingTxHash = null
        pendingRecipient = null
        pendingAmount = null
        pendingAction = null
        isSigningInProgress = false
    }

    private fun showStatus(message: String) {
        Log.d(TAG, "Status: $message")
        runOnUiThread {
            binding.tvStatus.text = message
        }
    }

    private fun showProgress(show: Boolean) {
        runOnUiThread {
            binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun isValidEthereumAddress(address: String): Boolean {
        return address.matches(Regex("^0x[a-fA-F0-9]{40}$"))
    }
}
