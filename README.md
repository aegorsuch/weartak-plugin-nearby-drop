# WearTAK Plugin Nearby Drop

## Purpose and Capabilities

Standalone WearTAK plugin for explicit watch-to-watch PKCS12 transfer using
Google Nearby Connections. On hosts that advertise the optional import-review
capability, the wearer can explicitly hand a received bundle to WearTAK review.
The host owns password entry, identity/CA review, destination selection and any
final save. Nearby Drop has no apply method and never treats transfer receipt or
staging as import, enrollment, trust, or connection.

The plugin opens from the WearTAK Plugins menu to two actions, **Receive** then
**Send**. Where a working Android document picker is available, **Send** can
choose a local PKCS12 bundle and requires an explicit **Offer selected bundle** tap.
**Receive** discovers a sender, both wearers compare Nearby's authentication
digits and explicitly confirm, and the receiver reviews the payload type and
exact size before accepting transfer. Receiver receipt means bytes received and
structurally checked, never successful certificate import or enrollment. A
separate **Review in WearTAK** action appears only when a compatible host
advertises the PKCS12 staging capability.

Nearby Drop declares Wi-Fi state access and Wi-Fi state change permissions on
modern Android for Google Nearby Connections. Server-package handoff requires
a WearTAK host supporting plugin API v3 and the relevant capability; older
hosts may reject the plugin. Export requires normal WearTAK plugin launch and
explicit wearer approval. Physical watch-to-watch transfer and server-package
export remain unverified.

When a watch only offers the placeholder document picker, standalone PKCS12
bundle selection is unavailable; reopen Nearby Drop from WearTAK to check
server-package support.

Only PKCS12 containers up to **32,700 bytes** are accepted. Structural checks
require PFX version 3 and a MAC, reject visible plaintext private/secret-key bags
and unsupported bag types, and allow encrypted safes, encrypted private keys and
X.509 certificate bags. They do **not** establish cryptographic validity, verify
the MAC/password, or make certificates trusted. Those checks belong to the
future host importer. Use a strong, independently communicated P12 password.
An empty or weak password cannot be detected without opening the container.

Enrollment URLs, arbitrary documents, FILE/STREAM payloads, CoT markers and
general file sharing are outside this MVP. No file names, callsigns, server
names, certificate contents, transfer IDs or payload hashes enter discovery
metadata. Discovery uses only the fixed name `Nearby Drop v2` and a versioned
service ID; it does not authenticate a peer. Wire protocol v2 offers carry a
typed content kind (PKCS12 or server package) so a receiver refuses kinds it
cannot validate before acceptance.

### Server package (event provisioning)

On hosts advertising the optional SDK `ServerPackage` V1 capability, **Send**
offers **Server package** and, when a working picker exists, **Bundle file**.
**Server package** opens a
WearTAK-owned picker: the wearer selects one configured server and, in WearTAK,
explicitly confirms any sensitive items. The plugin never reads WearTAK's server
or credential stores. The host exports one canonical `WTSP` v1 package of at
most 32,700 bytes (server name/host/port, TLS, public or private CA with up to
four CA certificates, identity mode, and optionally a shared client PKCS12 plus
its password and/or a shared username/password for intentional event
provisioning). Managed, non-exportable or oversized identities are refused by
the host (`MANAGED_RESTRICTED`, `NONEXPORTABLE_KEY`, `TOO_LARGE`) and nothing is
shared. Callsign and device UID are never included.

The sender sees a non-secret summary (server, trust, identity mode, which
sensitive item types are present) before an explicit **Offer server package**.
Sensitive fields have no independent app-layer encryption and rely on the
Nearby UKEY2-encrypted connection plus mutual code confirmation. Secret values
are never shown, logged or persisted; package bytes stay in memory on a
`FLAG_SECURE` window and are wiped on send, cancel, leaving the screen or any
terminal state.

The receiver accepts a server-package offer only when its host can stage
packages; bytes are validated with the SDK codec. After receipt it sees the
same non-secret summary and must tap **Review in WearTAK** to stage and open
host review. **Check WearTAK status** reports host outcomes distinctly: saved
configuration (`APPLIED`) is not enrollment (`ENROLLED`) or connection
(`CONNECTED`), and `NETWORK_FAILED` is reported as such. Uncertain export,
stage or status results fail closed with no automatic retry. Administrator
PKCS12 bundles can be sent separately as a **Bundle file** where a working
document picker is available.

## Status

Experimental **5.9.0**, not a production release. Source, protocol tests,
fake-Nearby lifecycle tests, host Binder lifecycle tests, review-handoff tests
and debug APK builds are provided. Host-side emulator tests have exercised
synthetic loopback transfer and review. A signed build has been discovered and
bound by WearTAK on two watches, but physical watch-to-watch transfer and the
server-package export capability remain unverified.

The optional SDK V1 PKCS12 staging and ServerPackage V1 export/stage clients are
integrated. The matching SDK license permission is in the pinned canonical
source. Availability depends on the cooperating host implementation. On a
supporting host, staging
is called only after the wearer taps **Review in WearTAK**. The host admits at
most 32,700 bytes for up to five minutes, returns an opaque one-use handle, then
opens host-owned review. Only a confirmed `STAGED` result clears the plugin's
byte copy; rejected or uncertain stage outcomes preserve it in memory and are
never retried automatically. Requesting review is not an import result. The
host alone owns the final wearer-confirmed decision.

No background service scans or automatic transfers. A foreground wearer action
starts each session; it expires after two minutes. Leaving the screen, host stop,
failure or cancellation closes connections and clears app-owned content buffers
best-effort. Process death discards pending in-memory state. This is not a claim
of secure erasure of JVM/Google Play services copies.

## Point of Contacts

Maintainer: repository owner. No separate support contact or response commitment
has been designated. Use the repository issue tracker for development feedback.

- Canonical source: <https://git.tak.gov/weartak-plugins/weartak-plugin-nearby-drop>
- Public mirror: <https://github.com/aegorsuch/weartak-plugin-nearby-drop>

## Ports Required

No application-configured TCP/UDP listener or TAK Server connection.
Nearby Connections manages its own Bluetooth/BLE and supported local transports;
there is no fixed network port allowlist verified for this plugin.

Advertising and connection requests explicitly disable disruptive upgrades and
request low power. The app does not enable radios or request forced Wi-Fi
switching. This is not a guarantee about transport internals on every device.

Builds require HTTPS access to Gradle/Maven repositories and the public GitHub
mirror of the WearTAK SDK source.

## Equipment Required

Two compatible Wear OS watches with Bluetooth/BLE, Android API 30 or later,
Google Play services exposing Nearby Connections, and this plugin installed.
Keep both watches on the transfer screen and verify the displayed code on both.
Sending a PKCS12 bundle requires an accessible local document and a working
Android document picker. Server-package sending requires a supporting host.

The cooperating WearTAK host is needed for plugin discovery; binding uses the
SDK's signature-protected permission. Release builds use the established TPC
plugin signer, subject to CI's certificate check. Signing keys remain in
protected CI variables and are never included in this repository.

## Equipment Supported

No specific watch model has been certified. A Google Nearby maintainer
[confirmed Wear OS availability using Bluetooth](https://github.com/google/nearby/issues/3163#issuecomment-2604070399);
that report is evidence of API feasibility, not device acceptance for this app.
Wi-Fi interoperability and watches without Google Play services are not claimed.
Wearable Data Layer's phone/watch sync is not substituted for watch-to-watch
transport. This is not interoperable with ATAK TakNearbyDrop or system Quick Share.

Permissions follow the Android Nearby contract: legacy Bluetooth/Wi-Fi and
location permissions on older Android versions; Bluetooth scan/connect/advertise
on API 31+; nearby Wi-Fi devices on API 33+. Runtime permission denial stops the
operation explicitly. Location permission is used only to satisfy older Nearby
discovery requirements; the app does not capture location, health or sensor data.
No broad storage permission, network enrollment permission or radio-setting
toggle is requested.

## Compilation

Use JDK 17, Android SDK platform/build tools for API 34, Python 3, Git and
Gradle wrapper 8.10.2. Built AARs are ignored local artifacts, not vendored
source.

In PowerShell from this isolated checkout:

```powershell
# Fetches the public mirror at the immutable canonical SDK revision.
python ci\fetch_sdk.py
New-Item -ItemType Directory -Force build\sdk-source | Out-Null
tar -xf build\sdk-source.tar -C build\sdk-source
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
Push-Location build\sdk-source
.\gradlew.bat --no-daemon --console=plain assembleDebug assembleRelease
Pop-Location
New-Item -ItemType Directory -Force app\libs | Out-Null
Copy-Item build\sdk-source\build\outputs\aar\weartak-plugin-sdk-debug.aar app\libs\weartak-plugin-sdk-debug.aar
Copy-Item build\sdk-source\build\outputs\aar\weartak-plugin-sdk-release.aar app\libs\weartak-plugin-sdk-release.aar
.\gradlew.bat --no-daemon :protocol:test :app:testDebugUnitTest :app:lintDebug :app:packageDebugArtifact
```

The fetcher checks out WearTAK SDK source revision
`86bdc8b04797c2cf6caa30e78e796abac1c2a330` from the public GitHub mirror
(canonical SDK merge request 9, tree
`a03d33bc077d81f80cdb53b04ea75e18cf0200a1`), which includes the PKCS12 review,
ServerPackage V1 APIs and matching additional-permission notice. It verifies
the GPL-3.0-or-later license declaration and exports that exact source tree with
repository line endings (`core.autocrlf=false`). The source archive is
cross-platform; Android AAR bytes differ between the validated Windows local
build and TPC CI build environments. Gradle verifies separate debug and release
SHA-256 pairs for each environment. The TPC CI hashes are
`93064008acda73ccfb0c60ad0ef85bcb7f3f0769446a615a934cb1c437011a50` and
`beae3192d0e80d6e1f8da39ffbb20f5e0921c904a34ab44268b9b4b35e04faa1`; the
validated Windows local hashes are
`db00bfe9fd98644fbd07161c4b3dfbf65c07513a8197de752fc2a00c59668044` and
`f90a36a6cada1a55a3154944a8b524119217cb95176fd055c2a7dd3aeafaa7a7`.
Gradle also checks that the SDK's additional permission matches this
repository's text before building. The SDK's complete GPL and
additional-permission notices are copied from that pinned source into the APK.
This replaces the template's older, unversioned v1/v2 AAR. The SDK publishes
no signed AAR; this pinned source build is the supported consumption path.
The Gradle wrapper distribution is checksum-pinned.

Named local artifact:
`app\build\outputs\nearbydrop\debug\WearTAK-Plugin-Nearby-Drop-5.9.0-p0.apk`.
It is **debug-signed**, not a production release. CI assigns pipeline-unique
version codes and versions.

`.gitlab-ci.yml` fetches the public SDK mirror at an immutable revision. Merge
requests run source-fetch tests, plugin tests/lint and a debug build without
production signing. Protected default-branch pushes and protected version tags
use the established TPC plugin release signing variables, verify the APK signer
SHA-256 against
`1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`, and retain
the signed APK plus checksum as pipeline artifacts. Main artifacts expire after
30 days; official version-tag artifacts are retained indefinitely. Missing
signing variables, invalid keystore, or signer mismatch fail the release job;
there is no debug-signed fallback.

TPC signing variables are inherited from protected group CI configuration:
`WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. No key or value is stored here.

The entry screen's **Licenses and notices** opens the bundled license texts.
CI checks the four Google runtime versions and their notice files against the
release runtime configuration and compares the resolved module inventory with
`ci/release-runtime-components.txt`. The separately supplied SDK AAR is checked
by `verifySdk`. This inventory is not a complete license-compliance
determination; review the other resolved dependencies and their applicable
notices before release, and review changes before updating the inventory.

The optional `publish_github_main` job is absent unless the protected CI
variable `NEARBY_DROP_AUTO_PUBLISH` is `true`. It additionally requires a
manual job action after a successful protected-main signed build. Configure a
protected, masked `NEARBY_DROP_GITHUB_TOKEN` limited to Contents write on
`aegorsuch/weartak-plugin-nearby-drop` and pre-create the
`TAK.gov-Forge-CI-CD-Pipeline-Release` tag on an existing mirror commit before
enabling it. The publisher never creates or moves tags and re-downloads the APK
to verify its hash before making the release latest, then checks the
`releases/latest` response before removing older managed assets. A missing token
or tag does not block the signed build:
the verified artifact may instead be mirrored manually after approval.

## Developer Notes

`protocol` is a pure Kotlin/JVM module; `app` owns Android UI, permission flow,
Nearby adaptation and a minimal signature-protected WearTAK plugin service.
All visible UI text is resource-backed. The launcher follows the existing
plugin template's native AppCompat scroll/action pattern, with round-screen
padding, 48dp touch targets, system font scaling, secure-window flag and
backup/device-transfer exclusions.

The original protocol is not TakNearbyDrop's endpoint-offer wire format:

| Frame | Body | Meaning |
|---|---|---|
| OFFER | Kind u8 + 32-bit size + SHA-256 | Metadata over the verified encrypted connection only |
| ACCEPT | Empty | Receiver explicitly accepts this offer |
| DATA | Bounded PKCS12 or `WTSP` package bytes | Sent only after ACCEPT for this transfer ID |
| RECEIPT | Empty | Receiver acknowledged structurally checked bytes, not import |

Every frame has magic/version/type, a random UUID and an exact body length.
Frames are at most 32,768 bytes; data is at most 32,700. A session permits one
peer/offer, has an absolute two-minute lifetime, and rejects duplicate,
out-of-order, unsolicited and mismatched frames. Discovery shows at most 20
compatible endpoints. Late callbacks cannot revive a cancelled session. The
sender never infers receipt from `sendPayload` task success.

App-owned bundle bytes are never intentionally written to disk. Unsupported
FILE/STREAM payloads from a verified but misbehaving peer are cancelled, handles
closed and Nearby's exact incoming file URI deleted best-effort. Google Play
services may stage an unsolicited FILE before the callback; this layer cannot
guarantee zero disk usage or secure deletion inside that service. Cleanup errors
are reported without payloads, paths, authentication codes or credentials.

### Debug-only synthetic fixture hook

Debug builds include `FixtureReceiveService` (`src/debug`) for offline simulator
demos. Release builds do not contain it, and the release `FixtureHook` is a
no-op. The hook is guarded by the `BIND_PLUGIN` signature permission. It accepts
a delivery only from the initialized V3 host whose package ends in
`.nearbyfixture`, and only with the marker
`WEARTAK-NEARBY-DROP-SYNTHETIC-FIXTURE-V1`. The payload must be a valid
ServerPackage V1 of at most 32,700 bytes, and the host must advertise receive
support.

One package is held in memory for 60 s. When `MainActivity` next comes to the
foreground, it runs through a loopback of two real `DropSession`s, with no Nearby
radio, advertising or discovery. The screen shows a visible fixture notice. The
wearer must still tap Accept and then Review in WearTAK.

### Required host/SDK follow-up

The SDK V1 contract provides capability discovery, bounded one-use staging,
opaque expiring handles, host review launch and non-sensitive status. It has no
apply endpoint. The host implementation must authenticate every Binder call,
bind stages to the active plugin session, reuse the existing NFC review flow,
and wipe staged bytes on all terminal paths or session death. Password entry,
identity/chain validation, destination selection, management locks, multi-CA
selection and final per-server trust/save stay host-owned. No CoT/chat/task
arguments, arbitrary intents, reflection or direct host storage access are used.

The host-side implementation and lifecycle tests are maintained in the
WearTAK host repository. This repository changes only the plugin and its SDK
source integration; it does not modify host code.

For initial host qualification, Core 5.8.4-p972 (`da3e7790`) contains the V3
PKCS12 and server-package controls and is a known source-level candidate, not
an established minimum version. Exercise the debug-only synthetic fixture
with a compatible test host, without radios or real credentials. Separately,
after obtaining an approved signed plugin, verify release host discovery and
capability negotiation. The release does not contain the fixture.
Physical-watch radio transfer remains separate acceptance work.

## License

Original Nearby Drop source is **GPL-3.0-or-later**: GNU General Public License
version 3 or, at your option, any later version; see [LICENSE](LICENSE).
The copyright holder grants a scoped GPLv3 Section 7 additional permission for
combination with the listed Google Play services components; see
[LICENSE-ADDITIONAL-PERMISSION.md](LICENSE-ADDITIONAL-PERMISSION.md). It covers
only material the grantor owns or controls, does not cover Google or other
third-party components, and does not replace their terms or notices.
The Gradle wrapper retains its Apache-2.0 notices. See [NOTICE](NOTICE) for
dependency/source provenance.

[RyanR3/TakNearbyDrop](https://github.com/RyanR3/TakNearbyDrop) (GPLv3) informed
research into the sharing workflow; no source or assets were copied from it.
No proprietary host NFC implementation was copied.

The WearTAK SDK AARs are built from canonical source revision
`86bdc8b04797c2cf6caa30e78e796abac1c2a330`. Its source has the standard
GPL-3.0-or-later `LICENSE` and an additional-permission notice identical to
this repository's; the grant covers only the defined owner-controlled work.
The older template AAR is no longer used.
Google Play services retain separate licensing terms.

### Dependency licensing evidence

| Dependency | Resolved version | Declared license | Included in debug APK |
|---|---|---|---|
| Canonical pinned WearTAK Plugin SDK debug AAR | Built from `86bdc8b04797c2cf6caa30e78e796abac1c2a330`; TPC CI SHA-256 `93064008acda73ccfb0c60ad0ef85bcb7f3f0769446a615a934cb1c437011a50` | GPL-3.0-or-later + scoped Section 7 permission | Yes |
| Canonical pinned WearTAK Plugin SDK release AAR | Built from `86bdc8b04797c2cf6caa30e78e796abac1c2a330`; TPC CI SHA-256 `beae3192d0e80d6e1f8da39ffbb20f5e0921c904a34ab44268b9b4b35e04faa1` | GPL-3.0-or-later + scoped Section 7 permission | Yes |
| `com.google.android.gms:play-services-nearby` | 19.3.0 | Android SDK License | Yes |
| `com.google.android.gms:play-services-base` | 18.5.0 | Android SDK License | Yes |
| `com.google.android.gms:play-services-basement` | 18.4.0 | Android SDK License | Yes |
| `com.google.android.gms:play-services-tasks` | 18.2.0 | Android SDK License | Yes |
| `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 | Yes |
| `androidx.appcompat:appcompat` | 1.7.0 | Apache-2.0 | Yes |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0.21 | Apache-2.0 | Yes |
| `junit:junit` | 4.13.2 | EPL-1.0 | No, tests only |
| `org.robolectric:robolectric` | 4.13 | MIT | No, tests only |

These are direct dependencies and the Google runtime group, not a complete
transitive SBOM. The SDK license was checked in the resolved Maven POMs. Google's
[Nearby Connections Android setup guide](https://developers.google.com/nearby/connections/android/get-started)
covers API setup; the [Google Play services setup guide](https://developers.google.com/android/guides/setup)
describes dependency configuration.

The [Nearby 19.3.0 POM](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-nearby/19.3.0/play-services-nearby-19.3.0.pom)
links to the [Android SDK License](https://developer.android.com/studio/terms).
Its sections 3.1-3.2 address compatible Android use, 3.4 restricts SDK copying,
modification and redistribution except as otherwise permitted, 3.5 preserves
open-source component licenses, 3.8 preserves notices, and 4.1 leaves ownership
of independently developed applications with their developers. The
[Google APIs Terms of Service](https://developers.google.com/terms) separately
requires documented access methods (§2(c)), recognizes separate open-source
licenses (§2(e)), and lists restricted uses (§4(a)). Google's
[open-source notice guidance](https://developers.google.com/android/guides/opensource)
describes notice responsibilities.

The APK bundles this project's `LICENSE` as `assets/LICENSE.txt`,
`LICENSE-ADDITIONAL-PERMISSION.md` as
`assets/LICENSE-ADDITIONAL-PERMISSION.txt`, and `NOTICE` as
`assets/THIRD_PARTY_NOTICES.txt`. From the pinned SDK source it also bundles
`assets/WEARTAK-SDK-LICENSE.txt` and
`assets/WEARTAK-SDK-LICENSE-ADDITIONAL-PERMISSION.txt`. It copies each Google
Play services AAR's `third_party_licenses.txt` unmodified to
`assets/third_party/<artifact>-<version>-third_party_licenses.txt`; these files
cover open-source components within the AARs, not the Android SDK License.
Those separate Google terms are linked above, not copied into the APK.
Bundling notices and linking to terms documents neither grants rights in
Google's components nor establishes GPL compatibility or distribution
clearance; those questions still require review.

The WearTAK SDK source license is verified; clarify applicable Google
redistribution terms before authorizing an APK release. A future native Android
Bluetooth transport could remove the Google client dependency, but needs a new
authenticated protocol/adapter and separate watch acceptance; it is not a
drop-in replacement. `compileOnly` is not a working fix: the current APK needs
client classes that are not supplied to its class loader by the separately
installed Play services process.

This source license does not relicense third-party binaries. No release or
publication is authorized by the presence of a locally built debug APK.
