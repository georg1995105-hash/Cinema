# Lite TV — performance experiment (2026-11-21)

This is an isolated experiment in the Cinema repository. **Do not merge into Cinema/main**: the APK is a separate, third-party Android app (`limehd.ru.lite`), not Cinema. No APK or signing keys are stored in this public repository.

## Input and constraints
- Starting point: local `LiteTV_Fixed_Landscape_v2.apk`, Lite TV 5.3.1 (638).
- Landscape fix remains byte-for-byte identical in AndroidManifest.xml (all 21 Activity elements).
- All APK files except `classes7.dex` and signature metadata remain byte-for-byte identical when decompressed.
- Modified DEX has recalculated SHA-1 and Adler-32. APK has valid archive CRC and locally verified APK Signature Scheme v2, using a new locally held test key.
- A *different* signing key from v2 means installing v3 over v2 is not possible without uninstalling v2. Back up local app data first.

## Identified slow paths
1. `limehd.ru.ctv.ui.player.core.LimexPlayerLoadControl.<init>()`: `DefaultLoadControl` originally configured min/max buffer 15000/15000 ms, initial playback buffer 3000 ms, rebuffer 3000 ms.
2. `limehd.ru.common.data.api.ApiClient.getStreamingClient()`: streaming OkHttp connection timeout 15 seconds. Stream read and full call timeout configured as unlimited (preserved to avoid truncating long live TV).
3. `limehd.ru.common.data.api.ApiClientConfig` default initialization: general API timeout 20 seconds and call timeout 40 seconds.
4. `limehd.ru.ctv.MainActivity.onCreate()`: early `warmUpWebView()` schedules `RmpVast.warmUp` for advertising after first draw. This may compete with immediate user interaction.

## Exact v3 patches (`classes7.dex` only)

| Parameter | Original | Experimental v3 |
|---|---:|---:|
| ExoPlayer min buffer | 15,000 ms | 8,000 ms |
| ExoPlayer max buffer | 15,000 ms | 15,000 ms (unchanged) |
| Buffer before first frame | 3,000 ms | 1,000 ms |
| Buffer after interruption | 3,000 ms | 2,500 ms |
| Streaming connection timeout | 15 s | 8 s |
| Default API timeout | 20 s | 12 s |
| Default API overall call timeout | 40 s | 24 s |
| Advertising WebView pre-warm in MainActivity.onCreate | scheduled | pre-warm invocation skipped (all SDK and billing code retained) |

This is *not* a magical acceleration of slow remote servers. Actual startup performance varies by channel/CDN, Wi-Fi/mobile network, cold/warm start, device hardware and whether the stream has valid segments.

## Verified locally
- Source vs output: only `classes7.dex` and APK signature changed. `AndroidManifest.xml`, resources, assets, other DEX files are identical.
- The DEX patch changes only the specified 16-bit constant values and one 3-unit invocation (replaced with NOPs). All method/code lengths, branch offsets and class identifiers remain unchanged.
- DEX SHA-1 / Adler-32 corrected; ZIP CRC clean; resources.arsc still 4-byte aligned.
- APK v2 cryptographic signature verified; targetSdk=35 unchanged, all 21 activities locked to landscape.

## Not verified
- App installed and launched on a real device.
- Real-world milliseconds to catalogue first paint and first frame of a TV channel.
- Whether a flaky CDN responds better to 8 s connection timeout.
- Potential impact of removing the optional advertising WebView pre-warm.
- Login, favorites, billing, OTA and playback regression tests.

## Real-device acceptance criteria
On **the same phone/tablet and same network**, compare v2 and v3: (1) cold launch to visible channel catalogue, (2) click to first frame on 5 representative channels, (3) rapid switching among channels, (4) playback for 30+ minutes on unstable Wi-Fi/mobile, (5) pause/resume/background/foreground, (6) portrait/landscape rotation remains locked, (7) billing/favorites/access where applicable. If new buffering/stalls appear, restore previous player values.

**Important:** Source-level redesign of startup dependencies, aggressive ad SDK removal, persistent cache changes, stream prefetch and universal speed guarantees are not claimed here. Those would need native source or runtime instrumentation and regression testing.
