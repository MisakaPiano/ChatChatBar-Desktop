# Persistent isolated next-preview signing

This series uses `com.example.chatbar.lansyncnext`, separately from the first
delivered `com.example.chatbar.lansync` preview and the ordinary application.
The first preview's signing identity cannot be recovered from its APK. Neither
uninstalling nor clearing its data is part of this transition. Data migration
from that preview is separate and cannot be assumed complete via its narrower
protocol1 transport.

The local RSA-2048 identity is persisted under Windows user-scope DPAPI, outside
Git. Its public SHA-256 certificate fingerprint is
`801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7`.
This is the next isolated development-preview identity, **not** the old signing
laboratory identity or an ordinary/production release identity. Do not recreate
or silently replace it. Its protected snapshot only reopens under the original
Windows account and environment; independent disaster recovery is not proven.

`apk_v2_local.py prepare` only reads an original cloud-built APK and creates a
public detached v2 block. It never writes an APK or exports a private key. The
request pins the original artifact/run/source, each APK hash, expected final
hash and certificate. Cloud assembly preserves all ZIP entry bytes and offsets.
Official Android SDK verification must pass for every resulting APK; a changed
executable byte must fail verification.

The intended cloud-only upgrade proof installs version81, seeds synthetic data
and a Keystore-protected identity, replaces it with version82 using `adb install
-r`, then reads the same persisted repositories, peer state and protected key.
Its instrumentation APK must carry the same signing identity. Each stage must
actually pass before any upgrade claim. This does not test a physical phone,
Windows interoperability, Wi-Fi discovery/QR, human acceptance or first-preview
data migration. Base, upgrade and test APKs are separate engineering outputs;
no workflow alone authorizes user delivery, installation or public release.
