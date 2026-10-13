# EriTV Android TV — permanent signing and in-app updates

## Downloader distribution

The permanent URL is:
`https://github.com/euphgd-ctrl/OpenTV-AndroidTV/releases/download/eritv-current/EriTV.apk`

AFTVnews code: **6861670** (user-registered). Do not change the asset name or release tag; the workflow replaces only its contents.

## One-time signing activation (repository administrator)

1. Open **Settings → Secrets and variables → Actions → New repository secret** in GitHub.
2. Name: `ERITV_SIGNING_PASSPHRASE`. Value: a strong randomly generated secret. **Do not commit, post, or share it.** Keep an offline backup.
3. Re-run **Build Android APK** via Actions → Build Android APK → Run workflow on main, or push a commit to main.
4. The first signed run creates a PKCS#12 signing key, encrypts it using AES-256-CBC/PBKDF2 (500,000 iterations), and commits only `signing/eritv-release.p12.enc`. It also pins the public certificate fingerprint in `signing/eritv-cert.sha256`.
5. The workflow builds a non-debuggable EriTV release APK signed with the persistent key, validates its signature and certificate, uploads the signed APK to the immutable-path `eritv-current/EriTV.apk` URL, and publishes `eritv-update.json` with the SHA-256 and version.
6. The legacy `updates/eritv.json` file is also refreshed after a successful published release, allowing v1.3.0 clients to detect new versions.

**Do not rotate or lose the secret.** The encrypted private signing key plus the secret are required to preserve update compatibility. Anyone with both can sign APKs that replace EriTV. Keep the secret in GitHub Actions and offline user-owned secure storage.

**Safeguard:** If the GitHub secret is absent or incorrect, the public EriTV release is not overwritten. A missing secret skips publication; a wrong secret fails decryption. New signing certificates are never generated if encrypted key material already exists.

## First-time transition for existing users

Builds shipped before the persistent signing key was established used GitHub runner-generated debug certificates, which are not guaranteed to match the new release key. Android explicitly rejects in-place upgrades between different APK signing keys. The updater detects a mismatch and explains the one-time fix:

1. Uninstall old EriTV from TV Settings → Apps.
2. Reinstall using Downloader code **6861670**.
3. Subsequent upgrades from the new signing lineage may install over the existing app without uninstalling, after user consent. Android's install confirmation and unknown-source permissions still apply.

Do not promise the old debug-signed release can be upgraded without uninstalling.

## Versioning and quality checks

When modifying EriTV:
- Increment `eritv/build.gradle.kts` `versionCode` and `versionName` (never decrease).
- Update APK/tag names used in `.github/workflows/build-apk.yml` for numbered test releases. **Do not change** the `eritv-current/EriTV.apk` permanent URL.
- CI uses proper `:eritv:assembleRelease` when signing is active; it verifies APK signature and certificate fingerprint before uploading to the permanent download location.
- The updater checks `eritv-current/eritv-update.json`, requires the correct canonical APK URL, validates download SHA-256, Android package ID, version and signing certificate, then asks Android Package Installer to install after user confirmation.
- Existing EriTV HLS buffering and reconnection behavior should be left unchanged unless independently tested.
- Never publish a debug-signed APK to the permanent public EriTV release after signing activation.

## Manual validation on actual Android TV

- Install signed v1.4.0 fresh and launch; EriTV plays live, reconnects correctly.
- For an in-place-update test, release a higher version using the same key; launch old version, accept update, approve **Allow from this source** for EriTV (if requested), and approve Android Package Installer.
- Verify settings and installed package persist; verify playback resumes.
- Install pre-signing v1.3.0 separately; after metadata update, confirm it recommends one-time uninstall/reinstall instead of attempting an incompatible update.
- Confirm that the permanent URL and code 6861670 still download the new APK through Downloader.

## Where to get the passphrase

The repository does **not** contain the secret. Repository owner must supply it via GitHub Actions secret as described above.

## Signing activation check

A workflow dispatch or main-branch documentation commit can activate the first signed build after the repository signing secret is configured. Verify the public APK asset and signed release job before announcing activation.
