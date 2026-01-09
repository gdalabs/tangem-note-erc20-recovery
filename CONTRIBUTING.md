# Contributing to Tangem Note ERC-20 Token Recovery

Thank you for your interest in contributing to this project! This document provides guidelines for contributing.

## How to Contribute

### Reporting Bugs

1. Check if the bug has already been reported in [Issues](https://github.com/yourusername/tangem-note-recovery/issues)
2. If not, create a new issue with:
   - A clear, descriptive title
   - Steps to reproduce the problem
   - Expected behavior
   - Actual behavior
   - Device model and Android version
   - Any error messages or logs

### Suggesting Features

1. Open a new issue with the "feature request" label
2. Describe the feature and why it would be useful
3. Include any relevant examples or mockups

### Submitting Code Changes

1. Fork the repository
2. Create a new branch for your feature/fix:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. Make your changes
4. Test thoroughly on a real device with a Tangem Note card
5. Commit with clear, descriptive messages:
   ```bash
   git commit -m "Add support for custom gas price"
   ```
6. Push to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```
7. Open a Pull Request

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions focused and small

## Testing

- Test all changes on a real Android device with NFC
- Test with actual Tangem Note cards when possible
- Verify transactions on testnets before mainnet if adding new transaction types

## Security Considerations

When contributing code that handles:
- Private keys or signatures
- Transaction creation
- RPC communication

Please ensure:
- No private keys are ever logged or stored
- All cryptographic operations use well-tested libraries
- Error messages don't leak sensitive information

## Questions?

Feel free to open an issue for any questions about contributing.

Thank you for helping make this tool better!
