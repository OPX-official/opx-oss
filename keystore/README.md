# Signing key

`opx-release.p12` is the key every artifact from this repository is signed with, both the
debug and the release variant:

- alias `opx`, PKCS12, password `opxoss`
- subject `CN=OPX OSS, O=OPX, OU=Android, C=BD`, valid until 2056

Android will not install a build over an existing app unless the signing certificate is
byte-identical, and AGP's default debug key is regenerated on every CI runner — that is why
each new build used to require uninstalling the previous one first. A single committed key
fixes that: run 40 installs over run 39.

Besides the key, `versionCode` in `app/build.gradle` comes from `GITHUB_RUN_NUMBER`
(`1000 + run`), so every CI build is also numerically newer than the last.

## Using a private key instead

Set any of these before `./gradlew assembleRelease` (and as repository secrets in CI):

| variable | default |
|---|---|
| `OPX_RELEASE_STORE_FILE` | `keystore/opx-release.p12` (relative to the repo root) |
| `OPX_RELEASE_STORE_TYPE` | `PKCS12` |
| `OPX_RELEASE_STORE_PASSWORD` | `opxoss` |
| `OPX_RELEASE_KEY_ALIAS` | `opx` |
| `OPX_RELEASE_KEY_PASSWORD` | `opxoss` |

The committed key is public by design, which is fine for alpha sideload builds. A key used
for a store listing must never be committed — use the variables above with a key that stays
outside the repository.

## Rotating

Replacing the key means the next build is a *different* app to Android: users have to
uninstall once more. Sign with a new key only at a deliberate release boundary.
