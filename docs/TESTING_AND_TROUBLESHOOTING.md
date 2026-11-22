# Testing & Troubleshooting Guide — Shubhankar Cloud Gaming

## 1. Automated Verification
### Android Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```
Covers:
- App title & custom adaptive launcher resources
- Exact coin pricing rules (`10 coins = 1 hour`, `20 coins = 2 hours`, negative balance prevention)
- Hardware gamepad & virtual joystick `ControllerInputBus` packet serialization
- Compose UI rendering (`AuthScreen`, `WalletScreen`)

### Backend Unit Tests
```bash
cd backend
npm test
```
Covers coin reservation math and early-disconnect proportional coin refund calculation.

## 2. Production Readiness Checklist (Before Claiming Production-Ready)
1. **Real GPU Nodes**: Verify at least one Windows RTX node reports `online` in `/api/v1/servers/status` with verified game executables.
2. **Licensed Games**: Confirm every enabled game is legally licensed and installed on the reporting GPU nodes.
3. **WebRTC Media & TURN**: Test end-to-end video/audio playback over both Wi-Fi and 4G/LTE mobile data using Coturn.
4. **Auth & Coins**: Test Google Sign-In (`GOOGLE_WEB_CLIENT_ID`), JWT refresh rotation, daily 20-coin deduplication across UTC boundaries, and concurrent session double-spend protection.

## 3. Troubleshooting
- **Android "Backend Server Offline"**: If testing on Android Emulator with backend running on your workstation, set Server URL in Settings to `http://10.0.2.2:4000/api/v1/`. For physical devices over LAN, use HTTPS or add your LAN IP to `network_security_config.xml`.
- **High Latency / Frame Drops on Samsung Galaxy M20**: Enable **Low-Memory Device Mode** in Settings, switch preset to `720p • 30 FPS` or `720p • 60 FPS`, and verify H.264 hardware decoder compatibility if H.265 shows frame jitter.
- **WebRTC Black Screen on Mobile Data**: Confirm UDP ports `3478` and `49152-65535` are open on Coturn and Windows GPU host firewalls.
