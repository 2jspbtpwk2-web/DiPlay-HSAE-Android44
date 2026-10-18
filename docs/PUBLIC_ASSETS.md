# Assets in the public source copy

The public copy excludes private credentials and artwork/media whose public
redistribution terms were not established. Original code licenses, attribution
and third-party license texts remain in place. These asset substitutions do not
change the license of any other component.

## Newly authored resource placeholders

All replacements use a simple geometric display, stand and status dot. They use
no original image, logo, font, screenshot, downloaded asset or brand artwork.
The XML and generator are marked `GPL-3.0-only`, under the repository's GPLv3
license. No MIT relicensing is introduced.

| Resource | Public-copy format | Reason |
| --- | --- | --- |
| `common` drawable `ic_carplay` | Vector XML | Usable by the modern Android/Compose target; replaces the excluded Apple image. |
| `common` raw `ic_car_home` | Generated 256×256 RGBA PNG | `CarPlayHostActivity.defaultAirPlayIconBytes()` reads the raw bytes for an AirPlay image payload. XML bytes would not be a PNG. |
| `common` raw `placeholder_icon` | Generated 256×256 RGBA PNG | `updateAirPlayIconPreview()` uses `BitmapFactory.decodeResource`; an XML shape/vector is not a raw bitmap there. |
| `shared` mipmap `ic_launcher`, `ic_launcher_round`, `ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome` | Vector XML | Keeps all launcher resource names, including the existing API26 adaptive-icon references. The modern target supports vectors. |
| Legacy drawable `ic_launcher`, `ic_carplay` | Shape/layer-list XML | Uses API18-compatible drawables; it does not require vector inflation on Android 4.3/4.4. |

Recreate the two raw PNG resources from this copy's source:

```sh
python3 scripts/generate_public_placeholder_icons.py
```

The generator only uses Python's standard library and writes those two named
resources. It performs no downloads or original-image reads. The PNG payloads
contain image data and required PNG framing only, with no personal metadata.

## Exclusions and known limitations

- Excluded: the original Apple CarPlay icon and copies, BYDMate PolyForm
  Noncommercial HUD images, Tucson/emblem artwork, old launcher images,
  unverified raw pictures and website screenshots. Attribution and license
  history are retained in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Excluded: the original AAC/H264 test files. These icon replacements do not
  replace diagnostic video/audio fixtures. Tests or scripts requiring those
  media need separately generated, documented synthetic inputs.
- Excluded: the unverified `assets/native/tunhelper` executable. Its corresponding
  source, build procedure and license were not found. The legacy wired target
  needs a separately supplied, appropriately licensed implementation before
  claiming a complete working public build.
- Retained: rebuildable USB-interface/screen-crypto `.so` files and their source
  and build scripts, including the full Monocypher notices. They are unrelated
  to the excluded TUN executable.
- Excluded: accessory authentication containers, Android signing keys, pairing
  data and existing vehicle-test APKs. A default source-only build cannot
  complete standalone accessory authentication without suitable external inputs.

The legacy `ReceiverActivity` reads Tucson artwork inside `runCatching` and skips
setting the hero bitmap when the image is absent. Its emblem read is also caught.
Missing images therefore leave empty/reserved artwork areas; no replacement car
photograph is implied. This review made no Kotlin change or claim of runtime
validation of that behavior.

This source copy differs from the private, vehicle-test package. Resource names
and source call sites were reviewed, but no build, emulator test or vehicle test
was run as part of replacing the icons. An unchanged private APK's earlier test
results do not validate these public-copy substitutions.
