# Cinema vs supplied reference APK — verified findings (2026-11-18)

**Reference inspected locally:** user-supplied `base.apk`, SHA-256 `7883dc0e4dd15e726994e10f75c066b061241a3a8ef7192f5caf6595ba897442`, 12,083,500 bytes; contains `classes.dex` and native `libffmpegJNI.so`. APK is compiled and partly obfuscated; the original Kotlin sources are not embedded. These observations come from DEX class/method/string tables, not an operational network capture.

**Cinema baseline:** v0.4.34 / `main` at `7dcf64631eedbd3e89f8da3ffb7f525be2d410c0`.

## Confirmed architecture gaps

| Reference DEX evidence | Cinema baseline | Consequence / test needed |
|---|---|---|
| `SettingsViewModel`: `guest_mode_override`, `guest_streams_override` | `MirrorGuestCookies` + `MirrorGuestSession`, not equivalent independently selectable source profiles | Test guest web film + guest AJAX using **same site and same film**; inspect actual returned response, not only cookies |
| `SettingsViewModel`: `dns_mode`, `proxy_type`, `proxy_host`, `proxy_port`, user/pass fields; network class `yv2` references DNS-over-HTTPS endpoints | No equivalent end-to-end DNS/proxy controls for metadata, AJAX and media segments | A web page, media server, and external CDN may use different hosts/routes. Check them separately |
| Network `gh4` handles `hdmbbs` and mirror choice; request executor `pz0` distinguishes public/private mirrors, HTTP 403, stale Anubis cookie and browser challenge | Cinema implements partial cookie/Anubis handling and attempts candidate mirrors | Need a stage-by-stage log of request host, status, challenge, **without** cookies or tokens |
| `gn5` parses `initCDNMoviesEvents` / eight-argument `initCDNSeriesEvents`, recognizes `streams`, `subtitle`, `thumbnails`; `nn5` calls CDN API | Cinema approximates the same public flow via `MirrorPlaybackRequest`, `MirrorVoiceDiscovery`, `MirrorMediaParser` | Confirm actual POST fields and JSON fields from a real successful reference session |
| `VeoveoRepository` exists and uses a distinct balancer / proxy path, separate from Rezka; `CollapseRepository` separate | Cinema has Rezka and Collapse only | A feature marketed as “mirror” may actually be a distinct **source**. Do not treat different sources as interchangeable mirrors |
| `PlaybackService` recognizes HLS, DASH, MP4, MKV, subtitle formats; `PlayerViewModel` has recovery/reconnect paths | Cinema uses Media3 player with its own failover; no equivalent independent background playback service | Test actual media URL, redirect chain, headers, first frame, resume, and errors separately |
| Reference `FilmRepository` uses `/ajax/person_info/`; `FilmRepository` also has film/people parsing | Cinema v0.4.34 added a hypothesized JSON adapter for `person_info` | Test real response shape for exact film/person id and verify loaded photo, not merely a stubbed JSON |

**Important:** The APK does **not** prove free access to paid content. Guest streams, DNS-over-HTTPS and network proxy settings are separate concepts. Do not conflate them or manufacture subscription entitlements. Do not copy an embedded token/credential from the reference APK into Cinema.

## Why previous green CI is insufficient

GitHub Actions currently compiles the app and runs JVM + Android tests using local HTML/JSON fixtures. `SourceFlowTest.media3PlaysTheParsedHttpsStreamWithoutForwardingSessionToCdn` plays a generated MP4 from two local `MockWebServer` instances. It does not check a real HDRezka film, a specific translator, a real CDN, DNS blocking, source protections or the user's network.

## Acceptance criteria before calling Cinema “fixed”

1. On the **same physical device**, compare the same film, site-origin, voice, season and episode in reference vs Cinema, ideally with the same VPN state.
2. Capture a minimal redacted trace for each stage: mirror DNS and TLS; film GET; challenge; translator extraction; episodes AJAX; streams AJAX; CDN HEAD/GET; Media3 first frame. Never collect or publish cookies, passwords, auth tokens, full URLs with query strings, or video payloads.
3. Verify both film metadata and actor/director portraits against **real** source data (no placeholders).
4. Verify at least one freely available full episode and a movie on user-chosen mirror, then another mirror. For unavailable paid/DRM content, preserve correct access error; no bypass.
5. Only then package a signed release and verify an in-place upgrade. A green emulator CI **alone** must never be reported as real playback success.

## Follow-up engineering priorities

- First: instrument reproducible, redacted on-device network diagnosis. Avoid adding more guessed hosts or translation IDs.
- Second: consolidate HTTP, WebView and Media3 connectivity policy, so optional DNS/proxy/guest modes affect every relevant network layer consistently; test compatibility with existing VPN.
- Third: implement distinct sources through explicit source adapters and provenance. Respect server-provided entitlement/availability.
- Fourth: derive film UI from verified metadata with correct photos, franchise, people and related items. Do not mark “loaded” until images/links have actually been checked.
- Fifth: run reproducible real-device playback and compare behavior to the supplied reference.

No production code or release should be altered on the basis of this analysis alone.
