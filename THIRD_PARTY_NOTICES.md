# Third-party notices

ComicViewer's APK contains no third-party runtime library. It uses Android platform APIs and the
project's own Java, resources, and artwork. The following development components are not linked
into the APK:

| Component | Use | License |
|---|---|---|
| Gradle Wrapper 8.14.4 | Reproducible build bootstrap; the wrapper JAR is vendored | Apache License 2.0 |
| Android Gradle Plugin 8.13.2 | Build tooling downloaded from Google's Maven repository | Android SDK license terms |
| Android SDK Platform/Build Tools | Compilation, packaging, alignment, and signature verification | Android SDK license terms |
| JUnit 4.13.2 | Unit tests only | Eclipse Public License 1.0 |
| Hamcrest Core 1.3 | Transitive unit-test assertions only | BSD 3-Clause License |

The Gradle Wrapper's Apache 2.0 license is reproduced in
[`third_party/gradle-wrapper-LICENSE.txt`](third_party/gradle-wrapper-LICENSE.txt). JUnit and
Hamcrest are resolved at build/test time with pinned SHA-256 checksums and are not redistributed in
ComicViewer's source tree or APK.

The launcher icon and its Android derivatives are original artwork created for ComicViewer. They are
included under the repository's MIT License. No stock, trademarked, or externally licensed visual
asset is included.
