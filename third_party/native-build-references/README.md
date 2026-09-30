# Native source and build references

Eight bundled binaries are unmodified files inherited from RikkaHub commit
`12ee935e0b0063dc3194d5145248795e071393cc`. Their SHA-256 values and packaged
license notices are listed in
[the native NOTICE](../../app/src/main/assets/licenses/native-libraries/NOTICE.txt).

The GitHub Release supplies `Orbis-2.6.0-native-sources.zip` alongside the
APK and application source. That companion archive contains upstream source
archives, not executable installers. Its `SOURCES.json` records URLs,
pinned revisions, downloaded sizes and SHA-256 values. No downloaded build
script is executed when preparing that archive.

## MuPDF 1.26.8

- [MuPDF source](https://github.com/ArtifexSoftware/mupdf/tree/c1b5d46e70512666b8695fb31ff280be0eff2469)
- [Android fitz build project](https://github.com/ArtifexSoftware/mupdf-android-fitz/tree/0c15358b3ef0b45cd03baab47079d94447018d9f)
- The Android project's `libmupdf` submodule points to exactly the listed
  MuPDF commit. It sets NDK `28.2.13676358` and builds
  `libmupdf/platform/java/Android.mk`.
- The companion source archive includes the complete 17 submodule source
  archives at the commits recorded in MuPDF's tree, not just empty gitlink
  directories. Preserve their original license files when unpacking or
  redistributing. The original dependency inventory is included as
  `mupdf-1.26.8-thirdparty.rst`.
- To build from an online checkout, recursively clone the Android build
  project, check out its pinned commit, initialize submodules, select the
  requested ABIs and run its documented Android library build. The copied
  `mupdf-android-fitz/build.gradle` is a provenance reference; it is not a
  standalone replacement for the entire upstream project.

## PRoot 5.1.107.92 and build dependencies

- [PRoot source](https://github.com/termux/proot/tree/7266fb3e8516535682f5a9c8f3a7e70f6506eddb)
- [RikkaHub Android build repository](https://github.com/rikkahub/termux-packages/tree/d9f8efa3a3e9c368a06546b1bf64d094d46c54ad)
- [talloc 2.4.3 source archive](https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz)
  SHA-256 `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`
- [libandroid-shmem 0.7 source](https://github.com/termux/libandroid-shmem/tree/7f0bd7e25dbdd146265aff7c6a890029e374622d)

`proot/build_proot_android.yml` and the three copied `build.sh` files are
unchanged upstream references. The workflow builds both ABIs using the full
Termux package build environment and extracts the two executables into
the same `libproot_exec.so` / `libproot_loader.so` names used by Orbis.
The build recipe statically links talloc and libandroid-shmem. Their
source archives, licenses and build scripts accompany PRoot's source.

## Simple / cppjieba

Simple is used under its MIT license option. Its actual historical binary
revision is not encoded in the inherited artifact and has not been
established. The companion archive therefore labels this as a reference
snapshot, not as proven exact corresponding source:

- [Simple reference source](https://github.com/wangfenjin/simple/tree/45db071ba8043ffe8a2e5dfe41f9d68fb477576c)
- [cppjieba source pinned by that reference build](https://github.com/yanyiwu/cppjieba/tree/194c144d8b5ed1baf3190d07c5226e804454ab47)

The original MIT/GPL dual-license text and cppjieba MIT text are packaged.
These references do not change the license of user-imported dictionaries,
documents or other data.

## Scope

The source and build references document versions and a source-availability
path; this release does not assert bit-for-bit reproducibility of all
inherited historical binaries. Keep the native source companion available
with APK downloads. A source ZIP containing only the application's Kotlin
and inherited `.so` files is not by itself the full native source package.
