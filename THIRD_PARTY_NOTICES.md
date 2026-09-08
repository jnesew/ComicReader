# Third-party notices

ComicViewer's APK contains no third-party runtime library. It uses Android platform APIs and the
project's own Java, resources, and artwork. The following development components are not linked
into the APK:

| Component | Use | License |
|---|---|---|
| Gradle Wrapper 8.14.4 | Reproducible build bootstrap; the wrapper JAR is vendored | Apache License 2.0 |
| Android Gradle Plugin 8.13.2 | Build tooling downloaded from Google's Maven repository | Apache License 2.0 (AOSP build tooling) |
| Android SDK Platform/Build Tools | Compilation, packaging, alignment, and signature verification | Android SDK license terms |
| JUnit 4.13.2 | Unit tests only | Eclipse Public License 1.0 |
| Hamcrest Core 1.3 | Transitive unit-test assertions only | BSD 3-Clause License |

The Gradle Wrapper's Apache 2.0 license is reproduced in
[`third_party/gradle-wrapper-LICENSE.txt`](third_party/gradle-wrapper-LICENSE.txt). JUnit and
Hamcrest are resolved at build/test time with pinned SHA-256 checksums and are not redistributed in
ComicViewer's source tree or APK.

The Android Gradle Plugin is AOSP build tooling; see the
[upstream Gradle task source license](https://android.googlesource.com/platform/tools/base/+/studio-master-dev/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/TaskManager.java).
SDK package distribution terms are distinct from the licenses of their individual components.
The pinned AGP artifact's POM/NOTICE should also be checked in the final build environment;
it could not be retrieved during this preparation pass.

## Visual assets

The launcher icon and its Android derivatives are original artwork created for ComicViewer and are
included under the repository's MIT License.

The README's Obtainium badge is copied from the
[Obtainium project](https://github.com/ImranR98/Obtainium/tree/main/assets/graphics) as directed by
its [official badging guide](https://github.com/ImranR98/apps.obtainium.imranr.dev/blob/main/BADGING.md).
Obtainium is distributed under the GNU General Public License version 3; the upstream license is
reproduced in
[`third_party/obtainium-badge-LICENSE.txt`](third_party/obtainium-badge-LICENSE.txt). The badge is
used only in repository documentation and is not included in the ComicViewer APK.

Promotional screenshots include:

- *Pepper & Carrot — Episode 10: Summer Special*: art and scenario by David Revoy, English
  translation by Alex Gryson, and the Hereva universe by David Revoy, licensed under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/);
- “Signal in the Rain,” an original test sample dedicated under
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/); and
- *Little Nemo in Slumberland* (1905) by Winsor McCay, public-domain newspaper artwork;
  see [store screenshot provenance](docs/STORE-ASSETS.md) for identified pages and remaining
  thumbnail provenance checks.

These sample images appear only inside documentation screenshots and are not included in the APK.
