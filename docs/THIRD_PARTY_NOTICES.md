# Credits and license notices

This public source copy preserves the original code licenses and attribution. It
does not relicense the project as MIT or grant rights to excluded artwork,
credentials, trademarks or firmware. Public-copy asset changes are documented in
[PUBLIC_ASSETS.md](PUBLIC_ASSETS.md).

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay).
The upstream receiver is licensed under GNU GPL version 3; the full text is in
`LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.
The HSAE Android 4.4 adaptation also preserves the DiPlay origin and modification
history recorded in `adaptation/receiver/README.md` and the upstream README copies.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and
[Showcase](https://github.com/amineross/showcase) for protocol research. Existing
source comments and attribution are preserved, including fixed source references
for the decoded-video output strategy.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette,
visual arrangement and interface copy of the
[DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed
under AGPL version 3. The UI file remains marked `AGPL-3.0-only`; its license text
is included in `docs/licenses/DiAuto-AGPL-3.0.txt`. The root GPL license does not
remove that file's AGPL terms.

## Public-copy placeholder icons

The Apple-derived CarPlay image, old launcher artwork and unverified raw icons
have been excluded from this copy. Newly authored geometric display placeholders
provide the resource names required by the code. Their XML and the small PNG
generator carry `GPL-3.0-only` notices and follow the repository's GPL version 3
license. They contain no brand artwork or external images/fonts.

The two generated PNG files are raw-resource image payloads, because the host
sends or decodes PNG bytes at those call sites. They can be reproduced with
`python3 scripts/generate_public_placeholder_icons.py` using Python's standard
library. This does not restore or edit any removed image.

For provenance, the excluded upstream CarPlay icon was obtained from Apple's
developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. That original asset is
not covered by the project's open-source code license. No Apple approval,
certification or endorsement is implied by this source copy or its placeholders.

## Runtime dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license
  (MIT-style). The legacy target uses the `jdk15to18` artifacts.
- JmDNS — JmDNS contributors; Apache License 2.0. The legacy target uses 3.5.9;
  the upstream modern version catalog lists 3.6.3.
- SLF4J — QOS.ch; MIT license. The legacy target uses 1.7.36.

Dependency declarations, version catalogs and pinned legacy hashes accompany the
source. License files are retained under `docs/licenses/dependencies/` and
`adaptation/receiver/assets/licenses/`. The API18 `PatchJmDns.java` build transform
changes references to newer charset fields; it preserves DNS wire logic. A build
using that transform must retain the original Apache license/attribution and
identify the JmDNS modification. Downloaded toolchains and dependency JARs are not
part of this public source package.

## Monocypher and native helpers

The screen/audio native authentication primitive uses Monocypher 4.0.2, licensed
under `BSD-2-Clause OR CC0-1.0`. Its source headers, full `LICENCE.md` and source
manifest are retained under
`adaptation/receiver/native/screencrypto/vendor/monocypher-4.0.2/`; the bundled
license copy is retained at
`adaptation/receiver/assets/licenses/monocypher-4.0.2.txt`. Preserve the applicable
copyright, conditions and disclaimer when redistributing source or binaries.

The public copy retains USB-interface and screen-crypto native source, build
scripts and the corresponding prebuilt `.so` files. Retaining these does not
grant rights to unrelated native binaries. The separate `native/tunhelper`
executable is excluded: its source/build provenance and redistribution license
were not established in this workspace. Its absence is a known dependency gap
for the legacy wired receiver; do not describe this package as a complete
reproduction of the private vehicle-test APK.

## Authentication data excluded

No accessory identity, certificate/key container, Android signing key, pairing
record or previously built vehicle-test APK is provided in this public copy.
Source/CI builds must not silently obtain such inputs. Without separately and
appropriately provisioned runtime authentication, a source-only build does not
complete standalone accessory authentication.

For historical accuracy, the upstream public-preview notices described an
experimental accessory certificate/key pair recovered from public Carlinkit
C2Air Allwinner V821 firmware. Those data were not newly generated Apple-issued
credentials for DiPlay and were not relicensed as project source code; their
general-distribution suitability was unresolved. This attribution is retained as
history, not permission to redistribute those materials, and does not mean this
public source copy contains them. Its separate Android signing key is excluded.

## Download website attribution

The original static site layout, CSS and generator adapt DiAuto (AGPL-3.0). That
credit and the AGPL license text remain relevant to any retained or reused source.
The old website screenshots, icon and download artifacts are excluded from this
public copy; the attribution is not a claim that a replacement site was published.

## BYD HUD maneuver icons excluded

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)

The upstream maneuver PNGs under `shared/src/main/assets/byd-hud-icons` were
imported from BYDMate under PolyForm Noncommercial 1.0.0. Their license and
required notice are retained as provenance, but the PNG files are excluded from
this public copy. They were separate from the project's code license; upstream
described them as donor assets and their original provenance was not independently
established. The validated upstream DiLink5.1 windshield path used factory turn
codes rather than these images. Their exclusion grants no new image rights.

## Other excluded local artwork and media

The Tucson vehicle pictures, emblem, old launcher images, upstream screenshots
and unverified image resources are excluded pending explicit rights/provenance.
The original AAC/H264 test media are also excluded. No assumption of permission
is made merely because an asset was present in a prior working directory or APK.
See [PUBLIC_ASSETS.md](PUBLIC_ASSETS.md) for resource substitutions and limitations.
