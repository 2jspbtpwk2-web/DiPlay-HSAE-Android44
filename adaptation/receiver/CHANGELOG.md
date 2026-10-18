> 历史legacy记录，保留为开发背景；当前0.60脱敏副本说明见仓库根README与docs/VALIDATION_PUBLIC.md，不把旧版本功能/测试当当前承诺。

# Android 4.3 adaptation changes

## 0.54-experimental

- Coalesce consecutive touch MOVE snapshots in a bounded queue, preserve normal gesture boundaries, cancel safely on saturation and reject queued work for an old session. Release held contacts on menu, focus loss and Activity teardown. Export counts and queue/write maxima without coordinates.
- Remove full binary-plist hex formatting from high-frequency HID sends and reject commands after session close. Keep wire encoding and encryption counters unchanged.
- Decode dashboard artwork on one background worker, reject obsolete results, avoid unchanged-color reloads, save settings asynchronously, cache producer-side connection progress and stop periodic UI refresh in the background. Batch diagnostic preview updates, guard late completion callbacks, and dispose every registered dialog during Activity destruction, including a reproduced diagnostic-completion window leak.
- Move periodic media snapshots off the main thread. Add low-frequency foreground main-loop delay observations; these are not display FPS.
- Send keyframe recovery requests through a single bounded background task so event-channel backpressure cannot block decoding. Avoid redundant random-access scans during ordinary decoding, count decoder errors consistently and reject late stream starts after close.
- Invalidate unhealthy ROOT channels without replaying a timed-out command, bound process output while retaining report headers and tails, parse complete exit statuses and mark incomplete output draining. Healthy commands continue to share the authorized shell.
- Cancel and guard delayed USB auto-connect callbacks when their Activity is destroyed.
- Add touch ordering, recovery-worker, UI scheduling and ROOT buffering regressions, plus independent real-Activity and final-APK media/ROOT runtime checks.

## 0.53-experimental

- Adapt the advertised canvas to the app's usable portrait or landscape area. Migrate the two former landscape defaults to automatic mode while preserving real custom dimensions. Add balanced, smooth, clarity and existing custom choices with an effective-size preview.
- Freeze the active session's advertised geometry for Surface layout and touch mapping. Keep OEM control areas outside the CarPlay canvas and fit without rotation, stretching or cropping.
- Add native-memory USB IN for NCM, iAP2 and status reads, plus native iAP2 OUT. Export bounded read counters/errno/wait maxima. Serialize connection closure with all readers/writers so a borrowed fd cannot be reused during a late ioctl. Rebuild both ARMv7 and x86 shims with ABI 0x0304.
- Bound compressed-frame queue age, recover only at a new random-access frame, retry keyframe requests at most once per second, suppress obsolete decoded outputs and keep decoder draining fair to input. Use monotonic arrival-based presentation timestamps.
- Distinguish real video frames from empty protocol messages. Aggregate crypto/conversion/handoff timing, queue/input/Surface timing and recent receive/decode/output rates without retaining media content.
- Add portrait settings, latency/recovery and privacy regressions, plus an independent synthetic-video instrumentation test against the final APK's actual decoder worker and Surface.

## 0.52-experimental

- Vehicle evidence now confirms successful NCM writes, neighbour advertisement, AirPlay TCP, pair-setup, pair-verify and encrypted auth-setup. The next failure is timing UDP setup returning a SocketException/500.
- Preserve the actual local IPv6 address and interface scope for timing, event, keepalive and media listeners. Repair an unscoped link-local peer only from its own accepted, scoped control connection; keep IPv4 fallback behaviour.
- Close partially created listener sockets after failed binds and serialize publication with close. Reset failed timing startup so it can be retried.
- Roll back only newly opened SETUP listeners after a later bind failure; keep the control connection available for retry. Ignore accept failures from rolled-back event listeners and reuse existing listener ports for repeated SETUP with the same timing peer.
- Bind microphone UDP to the local session address and keep the peer solely as the destination.
- Archive bounded socket exception class/cause, operation, errno and address-family/scope metadata without raw messages or addresses, so vendor-specific errors survive global log truncation.
- Add real Android IPv6 protocol/media binding regressions, including scoped link-local addresses and failed-bind cleanup checks.

## 0.51-experimental

- Observe the session for another 60 seconds after RECORD, preserve each attempt's transport evidence, and report activation separately from decoded video and audio playback progress.
- Add bounded IPv6 TCP handshake counters and independent AirPlay-port metadata, with no packet payload, address or raw sequence-number export.
- Add read-only IPv6 policy, filtering, socket and TUN snapshots plus process memory, CPU time, descriptors, threads and temperature samples. Capture transport counters immediately at failures and label delayed system snapshots.
- Extend the protocol diagnostic allowlist with safe SETUP, port, timing and response-duration metadata; record media first-packet/decoder output and cached AudioTrack playback progress.
- Record codec profiles, levels, color formats and advertised display/audio settings. Skip standalone media self-tests during an active session, and initialize the idle video probe at the configured display size.
- Retain prompted HSAE steering-wheel capture in the same report. Unsupported, inaccessible and unreached channels remain explicitly inconclusive.

## 0.50-experimental

- The 0.49 vehicle reports confirm USB alt selection, TLS, and iAP2 authentication succeeded. Fix the next failure: retain each outbound NCM packet across retryable writes, enforce its deadline independently of new TUN traffic, and serialize a bounded outbound queue.
- Reply to valid IPv6 neighbour solicitations for fe80::2 on the NCM Ethernet side, with checksum, hop-limit, address and DAD validation. Do not rely on TUN joining Ethernet neighbour multicast groups. Count queued requests and successfully sent advertisements.
- Correct CDC notification decoding (one-byte notification, NETWORK_CONNECTION 0x00), reject partial NTB writes, preserve true per-attempt event times, and emit real inbound/outbound stage markers in service reports.
- Add reviewable native USB source and an NDK r16b build for ARMv7/x86. Bulk OUT returns Linux errno without pinning a Java array during blocking I/O; only EPIPE triggers one clear-halt recovery.
- Add a dedicated HSAE HmiManager log section and read-only OEM interface schema discovery. The new reports identify HSAE seekup/seekdown events; the absent Skypine API is not treated as this vehicle's key protocol.
- Keep connection cleanup scoped to its generation and perform asynchronous NCM failure cleanup away from the main thread; sanitize embedded NUL bytes in report text.

## 0.49-experimental

- Add a prompted steering-wheel capture to both Super Diagnostics modes. Record live kernel EV_KEY/MSC_SCAN events, OEM MCU callback values, and foreground Android KeyEvents with timestamps and device/scan details. When the automatic connection succeeds, repeat the capture in the connected state in the same report.
- Temporarily start the OEM key callback during collection even when the voice-key preference is off, suppress diagnostic-time voice actions, then restore the service's prior running state.
- Include key assignments from system, vendor, and data keylayout files, input-service registration, media-button/audio-focus state, focused window, and key-related log entries in the same report. Do not infer Tucson track-button codes from other vehicle models.
- Keep the interactive capture bounded and omit touch coordinates and CAN traffic. A missing channel is reported separately from a missing button signal.
- Write both ROOT and app command scripts, plus raw capture intermediates, only to the app-private cache; clean them after use and keep OEM Binder registration/cleanup off the UI thread.
- Preserve per-attempt and asynchronous USB alt/JNI/NCM failure evidence, mark kernel alt verification in the wired service's own stage log, and increase retry backoff until a complete AirPlay session succeeds.

## 0.48-experimental

- Extend SuperDiagnostics with Apple USB bus/device/configuration and per-interface alternate-setting state from both app and ROOT views.
- Preserve the last NCM alternate-setting attempt, including usbfs fd mapping, sysfs path/read failure, device GET_INTERFACE result, JNI loader state, and ioctl/control-transfer return codes.
- Keep the added report fields limited to transport diagnostics; do not include USB serials, pairing records, or authentication key material.

## 0.47-experimental

- Compare USB bus and device numbers numerically when matching usbfs paths to sysfs, so zero-padded bus names no longer hide the kernel's active NCM alternate setting.
- Cache the native USB interface shim by content hash and publish it atomically, so a later app launch reuses the read-only library instead of trying to overwrite it.
- Keep unknown kernel state as a failure and preserve the native ioctl verification path.

## 0.21-experimental

Compared with the fork's previous 0.13 release:

- Add separate wireless/USB entry points and native home/settings pages based on the upstream layout. Preserve media, vehicle and input diagnostic tools.
- Keep automatic connection on open/boot optional and disabled by default. Add foreground session ownership, disconnect retry, background-session controls and Surface recovery on activity return.
- Enter projection before first-frame decoding; reattach/rebuild on projection return and changed Surface dimensions. The device operator reported this display-recovery version working.
- Add USBMUX/Lockdown/iAP2/NCM/IPv6/VPN paths using API18 descriptor and I/O adapters. Complete USB sessions still need device evidence.
- Add configurable wireless network modes, resolution/frame rate/physical size/safe area, audio focus/buffer/volume, optional navigation ducking and codec-dependent HEVC/Opus.
- Add independent media/navigation stream choices0–20, previews and unsupported-track fallback. Phone and Siri routes remain separate.
- Retain upstream Siri invocation and microphone negotiation, serialize Siri control requests, and restore click-based steering voice behavior.
- Add read-only OEM CAN speed/gear input. Independent PASCD requires verified gear mapping; actual iPhone use remains unverified.
- Record connection, media, rendering-target and current OEM service error states locally. OEM Reverse Assist/PlatformService crashes are not marked resolved.
- Expand automated validation to 115 tests; preserve API18 bytecode, single-DEX035, v1 signing and mDNS/EnumMap checks.

The upstream Android 9+ application is unchanged. This release does not constitute a complete upstream0.2.9 merge or broaden the original maintainer's supported vehicle list. Only an Android4.3 APK is distributed in this release.
