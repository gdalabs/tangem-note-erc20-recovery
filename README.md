# Tangem Note ERC-20 Token Recovery

An open-source Android application to recover ERC-20 tokens accidentally sent to Tangem Note hardware wallets.

## Problem

Tangem Note is an older version of Tangem's hardware wallet that only displays ETH balance in its official app. If you accidentally send ERC-20 tokens (like USDT, USDC, etc.) to a Tangem Note wallet address, the tokens exist on the blockchain but are invisible in the Tangem app, and there's no built-in way to transfer them out.

This app solves that problem by:
1. Reading your Tangem Note card via NFC
2. Displaying any ERC-20 token balance
3. Creating and signing transfer transactions using your card
4. Broadcasting the transaction to recover your tokens

## Features

- **Card Reading**: Scan your Tangem Note card via NFC to get your wallet address
- **Any ERC-20 Token Support**: Select from popular tokens (USDT, USDC, DAI, WETH, LINK, UNI) or enter any custom token contract address
- **Token Info Auto-Detection**: Automatically fetches token name, symbol, and decimals
- **Balance Display**: Shows your ETH and selected token balance
- **Secure Signing**: Transactions are signed on the card itself - your private key never leaves the hardware wallet
- **Transaction Broadcasting**: Sends the signed transaction to the Ethereum network

## Supported Tokens

### Preset Tokens
| Token | Contract Address |
|-------|------------------|
| USDT | 0xdAC17F958D2ee523a2206206994597C13D831ec7 |
| USDC | 0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48 |
| DAI | 0x6B175474E89094C44Da98b954EedeAC495271d0F |
| WETH | 0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2 |
| LINK | 0x514910771AF9Ca656af840dff83E8264EcF986CA |
| UNI | 0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984 |

### Custom Tokens
You can enter any ERC-20 token contract address to recover tokens not in the preset list.

## Requirements

- Android device with NFC capability
- Tangem Note card
- Sufficient ETH balance for gas fees (approximately 0.005 ETH)

## How to Use

1. **Install the APK** on your Android device
2. **Enable NFC** in your device settings
3. **Open the app** and tap "Scan Tangem Note Card"
4. **Hold your Tangem Note card** against the back of your phone until the card is read
5. **Select a token** from the preset list or enter a custom contract address
6. **Enter the recipient address** where you want to send the tokens
7. **Enter the amount** to send (or tap MAX for full balance)
8. **Tap "Send Token"** and confirm the transaction
9. **Hold your card steady** while the transaction is signed
10. **Wait for confirmation** - the transaction hash will be displayed

## Uninstalling and NFC behavior

Open Android **Settings > Apps > Tangem USDT Recovery > Uninstall**, or
long-press the launcher icon and choose **App info > Uninstall**.
Removing this app does not erase or reset the Tangem card.

With Android platform tools and an authorized USB connection, you can also run:

```bash
adb uninstall com.tangem.usdtrecovery
```

The app handles NFC only while its activity is in the foreground. It does not
register a system-wide NFC tag handler or request device administrator privileges.
If uninstall fails, report the Android version, device model, exact error, and
APK version in issue #1; that report currently contains no reproduction steps.

## Technical Details

### Tangem Note Protocol

This app implements the Tangem card protocol directly using Android NFC APIs:

- **AID**: `A000000812010208`
- **READ Command**: Instruction `0xF2` with PIN1 (SHA256 of "000000")
- **SIGN Command**: Instruction `0xFB` with PIN1, PIN2 (SHA256 of "000"), and transaction hash

### Transaction Signing

1. Creates an ERC-20 `transfer` transaction
2. RLP-encodes the transaction for signing
3. Computes Keccak256 hash
4. Sends hash to card for ECDSA signing
5. Recovers the recovery ID (v) from the signature
6. Broadcasts the signed transaction via public RPC endpoints

### Security

- **Private keys never leave the card** - all signing happens on the hardware
- **No external dependencies for signing** - uses Android's built-in NFC
- **Open source** - you can audit the code yourself

## Building from Source

### Prerequisites

- Android Studio Arctic Fox or later
- JDK 17
- Android SDK 34

### Build Steps

Set `ANDROID_HOME` to your SDK directory, or create an untracked
`local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.

```bash
# Clone the repository
git clone https://github.com/gdalabs/tangem-note-erc20-recovery.git
cd tangem-note-erc20-recovery

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

The APK will be generated in `app/build/outputs/apk/`.

Debug builds are named **Tangem Recovery Preview** and use package ID
`com.tangem.usdtrecovery.preview`. They can be installed alongside the original
release, whose signing certificate differs from this preview. To uninstall the
preview, select its name in Android Settings or run
`adb uninstall com.tangem.usdtrecovery.preview`.

Run the JVM checks and Android lint:

```bash
./gradlew testDebugUnitTest lintDebug
```

To check installation and removal on a clean, disposable Android device:

```bash
./scripts/check-install-uninstall.sh DEVICE_SERIAL app/build/outputs/apk/debug/app-debug.apk
```

The script refuses to overwrite an existing installation. This check requires
an actual connected device or emulator and does not validate NFC or token recovery.
For NFC testing, open the app explicitly, scan a Tangem Note, then leave the app
and confirm that tapping the card does not launch it. Verify balances and the
recipient/amount independently before attempting any transfer.

## Dependencies

- **OkHttp**: HTTP client for RPC calls
- **BouncyCastle**: Cryptographic operations (Keccak256, secp256k1)
- **Material Components**: UI components

## Disclaimer

⚠️ **USE AT YOUR OWN RISK**

- This is an unofficial tool not affiliated with Tangem
- Always test with a small amount first
- Transactions on the blockchain are irreversible
- Ensure you have enough ETH for gas fees before sending
- The authors are not responsible for any loss of funds

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Tangem for their SDK documentation
- The Ethereum community for RLP and transaction specifications
- All contributors who helped test and improve this tool

## Support

If you find this tool useful, consider:
- ⭐ Starring this repository
- 🐛 Reporting bugs via GitHub Issues
- 💡 Suggesting improvements
- 🔀 Contributing code via Pull Requests
- 🙏 Donation: 0x06bd871E7bdEa8D7Ed6e3De1D85044fc6D7eCBA2
