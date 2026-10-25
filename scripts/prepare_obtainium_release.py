#!/usr/bin/env python3
"""Local release preparation. Uses GitHub only for read-only release/source checks."""

import argparse
import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import warnings


PACKAGE = "io.github.jnesew.comicviewer"
RETIRED_PACKAGE = "com.localtools.comicviewer"
BUILD_TOOLS = "35.0.1"
MAX_CODE = 2_100_000_000
SIGNING_ENV = {
    "COMICVIEWER_SIGNING_PROPERTIES", "COMICVIEWER_KEYSTORE_PATH",
    "COMICVIEWER_STORE_PASSWORD", "COMICVIEWER_KEY_ALIAS", "COMICVIEWER_KEY_PASSWORD",
}
PASSWORD_ENV = {"COMICVIEWER_STORE_PASSWORD", "COMICVIEWER_KEY_PASSWORD"}
CERTIFICATE_PIN = Path("scripts/release-certificate.sha256")
LOCAL_SIGNING = Path("signing/release.local.json")
STABLE = r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def stable_version(value):
    require(re.fullmatch(STABLE, value), f"Expected a stable X.Y.Z version, got {value!r}.")
    return tuple(map(int, value.split(".")))


def published_version(value):
    # A final X.Y.Z is newer than every prerelease of the same base version.
    match = re.fullmatch(STABLE + r"(?:-([0-9A-Za-z.-]+))?", value)
    require(match, f"Unsupported published version {value!r}; inspect the release history.")
    return tuple(map(int, match.group(1, 2, 3))), match.group(4) is None


def properties(text, unique=()):
    """Read Java properties, with last-value precedence except explicitly unique keys."""
    def unescape(value):
        def replace(match):
            token = match.group(1)
            if token.startswith("u"):
                return chr(int(token[1:], 16))
            return {"n": "\n", "r": "\r", "t": "\t", "f": "\f"}.get(token, token)
        return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", replace, value)

    result = {}
    logical = ""
    for line in text.splitlines():
        line = line.lstrip()
        if not logical and (not line or line.startswith(("#", "!"))):
            continue
        logical += line
        trailing = len(logical) - len(logical.rstrip("\\"))
        if trailing % 2:
            logical = logical[:-1]
            continue
        match = re.match(r"((?:\\.|[^=:\s])+)\s*(?:[=:]\s*)?(.*)$", logical)
        require(match, "Malformed Java properties file.")
        key, value = map(unescape, match.groups())
        require(key not in result or key not in unique, f"Duplicate property {key!r}.")
        result[key] = value
        logical = ""
    require(not logical, "Unterminated properties continuation.")
    return result


def read_version(root):
    data = properties((root / "gradle.properties").read_text(encoding="utf-8"),
                      unique=("comicViewerVersionName", "comicViewerVersionCode"))
    name = data.get("comicViewerVersionName", "")
    stable_version(name)
    code = data.get("comicViewerVersionCode", "")
    require(re.fullmatch(r"[1-9]\d*", code), "Invalid comicViewerVersionCode.")
    require(int(code) <= MAX_CODE, "Version code exceeds the supported Android limit.")
    return name, int(code)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def normalize_certificate(value):
    value = value.lower().replace(":", "").strip()
    require(re.fullmatch(r"[0-9a-f]{64}", value),
            "Expected a certificate SHA-256 with 64 hexadecimal digits.")
    return value


def expected_certificate(root, env):
    path = root / CERTIFICATE_PIN
    require(path.is_file(), f"Missing tracked release certificate: {CERTIFICATE_PIN}.")
    expected = normalize_certificate(path.read_text(encoding="ascii"))
    if "COMICVIEWER_EXPECTED_CERT_SHA256" in env:
        require(normalize_certificate(env["COMICVIEWER_EXPECTED_CERT_SHA256"]) == expected,
                "COMICVIEWER_EXPECTED_CERT_SHA256 disagrees with the tracked certificate pin; remove the stale variable or review the pin.")
    return expected


def verify_published_certificate(history, expected):
    latest = max(history.records, key=lambda r: (published_version(r["version_name"]), r["version_code"]))
    require(latest["certificate_sha256"] == expected,
            "Tracked certificate pin differs from the latest published APK; inspect the release history.")


def run(root, args, env=None, capture=True, check=True, input_text=None):
    # No shell and no credential values in arguments or printed command lines.
    child_env = {key: value for key, value in (os.environ if env is None else env).items()
                 if key not in PASSWORD_ENV}
    result = subprocess.run(list(map(str, args)), cwd=root, env=child_env,
                            text=True, encoding="utf-8", input=input_text,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if check and result.returncode:
        if capture:
            print(result.stderr.strip(), file=sys.stderr)
        raise ReleaseError(f"Command failed ({result.returncode}): {Path(str(args[0])).name}")
    return result


def git(root, *args):
    return run(root, ["git", *args]).stdout.strip()


def clean_commit(root, expected=None):
    require(not git(root, "status", "--porcelain", "--untracked-files=normal"),
            "Commit source-tree changes before release preparation.")
    commit = git(root, "rev-parse", "HEAD")
    require(expected is None or commit == expected, "HEAD changed during release preparation.")
    return commit


def repository(root):
    url = git(root, "config", "--get", "remote.origin.url")
    match = re.fullmatch(
        r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)"
        r"([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?", url)
    require(match, "origin must identify a GitHub repository over HTTPS or SSH.")
    return match.group(1)


def toolchain(root, env):
    require(shutil.which("git", path=env.get("PATH")), "Install git.")
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    local = root / "local.properties"
    if not sdk and local.is_file():
        sdk = properties(local.read_text(encoding="utf-8")).get("sdk.dir")
    require(sdk, "Set ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in local.properties.")
    sdk = Path(sdk).expanduser()
    if not sdk.is_absolute():
        sdk = root / sdk
    sdk = sdk.resolve()
    require((sdk / "platforms/android-36/android.jar").is_file(), "Install Android SDK Platform 36.")
    tools = sdk / "build-tools" / BUILD_TOOLS
    for name in ("aapt2", "apksigner", "zipalign"):
        require(os.access(tools / name, os.X_OK), f"Install Android Build Tools {BUILD_TOOLS}.")
    java_home = env.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java", path=env.get("PATH"))
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac", path=env.get("PATH"))
    require(java and javac, "Install JDK 17 and set JAVA_HOME.")
    java_result = run(root, [java, "-version"], env)
    java_version = (java_result.stdout + java_result.stderr).strip()
    compiler = run(root, [javac, "-version"], env)
    require(re.search(r'version "17(?:\.|\")', java_version)
            and re.search(r"javac 17(?:\.|\s|$)", compiler.stdout + compiler.stderr),
            "JDK 17 is required; set JAVA_HOME to the JDK 17 installation.")
    env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
    env["JAVA_HOME"] = str(Path(java).resolve().parent.parent)
    env["PATH"] = str(Path(javac).resolve().parent) + os.pathsep + env.get("PATH", "")
    info = {"java": java_version, "build_tools": BUILD_TOOLS, "platform": 36}
    for key, path in (("platform_revision", sdk / "platforms/android-36/source.properties"),
                      ("build_tools_revision", tools / "source.properties")):
        require(path.is_file(), f"Missing SDK package metadata: {path.name}")
        info[key] = properties(path.read_text(encoding="utf-8")).get("Pkg.Revision", "unknown")
    for key, path, pattern in (
        ("gradle", root / "gradle/wrapper/gradle-wrapper.properties", r"gradle-([\d.]+)-bin.zip"),
        ("android_gradle_plugin", root / "build.gradle", r"version ['\"]([\d.]+)['\"]"),
    ):
        match = re.search(pattern, path.read_text(encoding="utf-8"))
        require(match, f"Cannot read pinned {key} version.")
        info[key] = match.group(1)
    return tools, info


def apk_info(root, tools, apk, env):
    badging = run(root, [tools / "aapt2", "dump", "badging", apk], env).stdout
    line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", line))
    require(all(key in fields for key in ("name", "versionCode", "versionName")),
            f"Cannot read APK package/version: {apk.name}")
    require(re.fullmatch(r"[1-9]\d*", fields["versionCode"]), "Invalid APK version code.")
    return {"package": fields["name"], "version_name": fields["versionName"],
            "version_code": int(fields["versionCode"]), "debuggable": "application-debuggable" in badging}


def certificate(root, tools, apk, env):
    output = run(root, [tools / "apksigner", "verify", "--print-certs", apk], env).stdout
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: (.+)$", output, re.M)
    require(len(signers) == 1, "Expected exactly one APK signing certificate.")
    return normalize_certificate(signers[0])


class PublishedHistory:
    def __init__(self, root, tools, env):
        self.root, self.tools, self.env = root, tools, env
        require(shutil.which("gh", path=env.get("PATH")), "Install GitHub CLI (gh) and authenticate it.")
        self.repo = repository(root)
        pages = self.api(f"repos/{self.repo}/releases", "--paginate", "--slurp")
        self.releases = [item for page in pages for item in page if not item["draft"]]
        require(self.releases, "No published releases found; initial publication needs a separate bootstrap.")
        refs = git(root, "ls-remote", "--tags", "origin")
        self.refs = dict(line.split()[::-1] for line in refs.splitlines())
        self.records = []
        self.cache = root / "dist/release-cache"
        self.cache.mkdir(parents=True, exist_ok=True)
        for release in self.releases:
            tag = release["tag_name"]
            require(tag.startswith("v"), f"Unexpected published release tag {tag!r}.")
            published_version(tag[1:])
            assets = [a for a in release["assets"] if a["name"].lower().endswith(".apk")]
            require(len(assets) <= 1, f"Release {tag} has multiple APKs; inspect its version history.")
            if not assets:
                # The retired v1.0.0 release now has only a checksum asset. Establish
                # its application ID from the remote tagged source instead of guessing.
                commit = self.tag_commit(tag)
                build = git(root, "show", f"{commit}:app/build.gradle")
                require(re.search(r"applicationId\s+['\"]" + re.escape(RETIRED_PACKAGE) + r"['\"]", build),
                        f"Published {tag} has no APK; restore its artifact before comparing versions.")
                continue
            asset = assets[0]
            expected_digest = asset.get("digest")
            require(expected_digest and re.fullmatch(r"sha256:[0-9a-f]{64}", expected_digest),
                    f"GitHub has no SHA-256 for {tag}; cannot safely cache its APK.")
            path = self.cache / f"{int(asset['id'])}.apk"
            if not path.is_file() or digest(path) != expected_digest[7:]:
                fd, temporary = tempfile.mkstemp(prefix=".download-", dir=self.cache)
                try:
                    with os.fdopen(fd, "wb") as output:
                        result = subprocess.run(
                            ["gh", "api", "--hostname", "github.com",
                             f"repos/{self.repo}/releases/assets/{int(asset['id'])}",
                             "-H", "Accept: application/octet-stream"],
                            cwd=root, env=env, stdout=output, check=False)
                    require(result.returncode == 0, f"Cannot download published APK for {tag}.")
                    require(digest(Path(temporary)) == expected_digest[7:], f"APK checksum mismatch for {tag}.")
                    os.replace(temporary, path)
                finally:
                    Path(temporary).unlink(missing_ok=True)
            info = apk_info(root, tools, path, env)
            if info["package"] == RETIRED_PACKAGE:
                continue
            require(info["package"] == PACKAGE, f"Unexpected package in published {tag}.")
            require(info["version_name"] == tag[1:], f"Published APK and tag disagree for {tag}.")
            info.update(tag=tag, sha256=expected_digest[7:],
                        certificate_sha256=certificate(root, tools, path, env), prerelease=release["prerelease"])
            self.records.append(info)
        require(self.records, "No published APKs for the current application ID were found.")

    def api(self, endpoint, *args):
        return json.loads(run(self.root, ["gh", "api", "--hostname", "github.com", endpoint, *args], self.env).stdout)

    def tag_commit(self, tag):
        ref = f"refs/tags/{tag}"
        expected = self.refs.get(ref + "^{}") or self.refs.get(ref)
        require(expected, f"Published tag {tag} is missing from origin.")
        git(self.root, "fetch", "--no-tags", "origin", ref)
        commit = git(self.root, "rev-parse", "FETCH_HEAD^{commit}")
        require(commit == expected, f"Remote tag {tag} changed while checking it.")
        return commit

    def validate(self, name, code, commit):
        same = [r for r in self.records if r["version_name"] == name]
        newer = [r for r in self.records if published_version(r["version_name"]) > (stable_version(name), True)]
        if same:
            require(not newer, "A newer version is already published.")
            require(all(r["version_code"] == code for r in same), "Published version code differs; bump the version.")
            require(all(self.tag_commit(r["tag"]) == commit for r in same),
                    "This version is already published from another commit; bump it.")
            require(all(r["version_code"] <= code for r in self.records), "A higher version code is already published.")
            return True  # Only an exact source rebuild; final APK must also match.
        require(not newer, "Version name must exceed every published version.")
        maximum = max(r["version_code"] for r in self.records)
        require(code > maximum, f"Version code must exceed published code {maximum}; bump gradle.properties.")
        return False


def validate_notes(root, name, code):
    notes = root / f"docs/RELEASE-NOTES-{name}.md"
    require(notes.is_file(), f"Missing {notes.relative_to(root)}.")
    text = notes.read_text(encoding="utf-8")
    require(text.startswith(f"# ComicViewer {name}\n"), "Release notes have the wrong version heading.")
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    match = re.search(r"^## " + re.escape(name) + r" - (\d{4}-\d{2}-\d{2})\n(.*?)(?=^## |\Z)",
                      changelog, re.M | re.S)
    require(match, "CHANGELOG.md needs a dated section for this version.")
    try:
        datetime.date.fromisoformat(match.group(1))
    except ValueError as error:
        raise ReleaseError("Invalid release date in CHANGELOG.md.") from error
    texts = [text, match.group(2)]
    require(len(text.splitlines()) > 2 and match.group(2).strip(), "Release notes must describe the changes.")
    for locale in ("en-US", "fi-FI"):
        path = root / f"fastlane/metadata/android/{locale}/changelogs/{code}.txt"
        require(path.is_file(), f"Missing localized changelog {path.relative_to(root)}.")
        value = path.read_text(encoding="utf-8").strip()
        require(0 < len(value) <= 500, f"{locale} changelog must contain 1–500 characters.")
        texts.append(value)
    require(not any(re.search(r"\b(?:TODO|TBD|PLACEHOLDER)\b", value, re.I) for value in texts),
            "Finish the TODO/TBD/placeholder release notes and localized changelogs.")
    return notes


def bump(root, name, code, history, kind):
    base = max([stable_version(name)] + [published_version(r["version_name"])[0] for r in history.records])
    parts = list(base)
    index = {"major": 0, "minor": 1, "patch": 2}[kind]
    parts[index] += 1
    parts[index + 1:] = [0] * (2 - index)
    new_name = ".".join(map(str, parts))
    new_code = max([code] + [r["version_code"] for r in history.records]) + 1
    require(new_code <= MAX_CODE, "No supported version code remains.")
    files = {}
    for locale, text in (("en-US", "TODO: Describe the changes for users."),
                         ("fi-FI", "TODO: Kuvaa muutokset käyttäjille.")):
        path = root / f"fastlane/metadata/android/{locale}/changelogs/{new_code}.txt"
        require(not path.exists(), f"Refusing to overwrite {path.relative_to(root)}.")
        files[path] = text + "\n"
    notes = root / f"docs/RELEASE-NOTES-{new_name}.md"
    require(not notes.exists(), f"Refusing to overwrite {notes.relative_to(root)}.")
    files[notes] = f"# ComicViewer {new_name}\n\nTODO: Write reviewed release notes.\n"
    changelog = root / "CHANGELOG.md"
    text = changelog.read_text(encoding="utf-8")
    require(not re.search(r"^## " + re.escape(new_name) + r"(?: |$)", text, re.M),
            "The new version already has a CHANGELOG section.")
    entry = f"## {new_name} - {datetime.date.today().isoformat()}\n\n- TODO: Summarize changes.\n\n"
    first = re.search(r"^## ", text, re.M)
    require(first, "Cannot find the release sections in CHANGELOG.md.")
    files[changelog] = text[:first.start()] + entry + text[first.start():]
    version_file = root / "gradle.properties"
    text = version_file.read_text(encoding="utf-8")
    for key, value in (("comicViewerVersionName", new_name), ("comicViewerVersionCode", new_code)):
        text, count = re.subn(r"^" + key + r"\s*[=:].*$", f"{key}={value}", text, flags=re.M)
        require(count == 1, f"Cannot safely update {key}.")
    files[version_file] = text
    originals = {path: path.read_bytes() if path.exists() else None for path in files}
    try:
        for path, value in files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value, encoding="utf-8")
    except BaseException:
        for path, value in originals.items():
            if value is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(value)
        raise
    print(f"Prepared v{new_name}, version code {new_code}. Edit the TODO notes, then commit the changes.")


def signing_metadata(root, env):
    path = Path(env.get("COMICVIEWER_SIGNING_PROPERTIES", "signing/keystore.properties"))
    if not path.is_absolute():
        path = root / path
    require(not env.get("COMICVIEWER_SIGNING_PROPERTIES") or path.is_file(), "Signing properties file is missing.")
    local = root / LOCAL_SIGNING
    if (local.exists() or local.is_symlink()) and not env.get("COMICVIEWER_SIGNING_PROPERTIES"):
        config = read_local_signing(local)
    else:
        legacy = properties(path.read_text(encoding="iso-8859-1")) if path.is_file() else {}
        config = {"keystore": legacy.get("storeFile", ""), "alias": legacy.get("keyAlias", "")}
    return {"COMICVIEWER_KEYSTORE_PATH": env.get("COMICVIEWER_KEYSTORE_PATH", config.get("keystore", "")),
            "COMICVIEWER_KEY_ALIAS": env.get("COMICVIEWER_KEY_ALIAS", config.get("alias", ""))}


def read_local_signing(path):
    require(path.is_file() and not path.is_symlink(), "Local signing settings must be a regular file.")
    config = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(config, dict) and set(config) == {"keystore", "alias"}
            and all(isinstance(value, str) and value for value in config.values()),
            "Local signing settings must contain only a keystore path and alias; rerun --setup after reviewing the file.")
    return config


def hidden_password(prompt):
    require(sys.stdin.isatty(), "Password entry requires a terminal; for automation use --non-interactive with environment passwords.")
    try:
        # Refuse getpass's plain-input fallback when echo cannot be disabled.
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            value = getpass.getpass(prompt)
    except (EOFError, getpass.GetPassWarning) as error:
        raise ReleaseError("Could not read a hidden password from the terminal; no password was saved.") from error
    require(value, "Signing password cannot be empty.")
    return value


def terminal_input(prompt):
    require(sys.stdin.isatty(), "Signing setup requires a terminal; use --non-interactive with environment settings for automation.")
    try:
        return input(prompt).strip()
    except EOFError as error:
        raise ReleaseError("Signing setup cancelled; no settings were saved.") from error


def signing_probe(root, env, keystore, alias, store_password, key_password):
    require(not any("\n" in value or "\r" in value for value in (store_password, key_password)),
            "Signing passwords must be single-line values.")
    helper = root / "scripts/SigningKeyProbe.java"
    require(helper.is_file(), "Missing build-time signing key inspector.")
    result = run(root, [Path(env["JAVA_HOME"]) / "bin/java", "--source", "17", helper, keystore, alias], env,
                 check=False, input_text=store_password + "\n" + key_password + "\n")
    require(result.returncode == 0,
            "Cannot unlock or inspect the signing keystore; check the path, store password, and JDK 17.")
    data = json.loads(result.stdout)
    entries = data.get("entries") if isinstance(data, dict) else None
    require(isinstance(entries, list), "Invalid response from the signing key inspector.")
    for entry in entries:
        require(isinstance(entry, dict) and isinstance(entry.get("alias"), str) and entry["alias"]
                and isinstance(entry.get("key_unlocked"), bool)
                and isinstance(entry.get("certificate_sha256"), str), "Invalid signing key entry.")
        entry["certificate_sha256"] = normalize_certificate(entry["certificate_sha256"])
    require(len({entry["alias"] for entry in entries}) == len(entries), "Duplicate signing aliases.")
    return entries


def signing(root, env, expected, non_interactive=False, password_env=None, setup=False):
    values = signing_metadata(root, env)
    path = values["COMICVIEWER_KEYSTORE_PATH"]
    if setup and not non_interactive:
        path = terminal_input("Keystore path" + (f" [{path}]" if path else "") + ": ") or path
    require(path, "Configure a keystore path with --setup or COMICVIEWER_KEYSTORE_PATH.")
    keystore = Path(path).expanduser()
    if not keystore.is_absolute():
        keystore = root / keystore
    require(keystore.is_file(), "Configured signing keystore does not exist.")
    values["COMICVIEWER_KEYSTORE_PATH"] = str(keystore.resolve())
    configured_alias = values["COMICVIEWER_KEY_ALIAS"]
    require(setup or configured_alias, "Configure the signing alias with --setup or COMICVIEWER_KEY_ALIAS.")

    supplied = password_env or {}
    if non_interactive:
        store_password = supplied.get("COMICVIEWER_STORE_PASSWORD", "")
        require(store_password, "--non-interactive requires COMICVIEWER_STORE_PASSWORD in the environment.")
        key_password = supplied.get("COMICVIEWER_KEY_PASSWORD") or store_password
    else:
        store_password = hidden_password("Keystore password: ")
        key_password = store_password

    # Discovery considers only private-key aliases with the independently pinned certificate.
    alias = configured_alias if not setup or env.get("COMICVIEWER_KEY_ALIAS") else ""
    entries = signing_probe(root, env, keystore, alias, store_password, key_password)
    if alias:
        require(len(entries) == 1 and entries[0]["alias"] == alias, "Configured alias has no private signing key.")
        entry = entries[0]
    else:
        matching = [entry for entry in entries if entry["certificate_sha256"] == expected]
        require(matching, "No private signing key matches the tracked certificate pin.")
        if len(matching) == 1:
            entry = matching[0]
        else:
            require(not non_interactive, "Multiple signing aliases match; set COMICVIEWER_KEY_ALIAS for unattended setup.")
            print("Matching signing aliases:")
            for index, candidate in enumerate(matching, 1):
                print(f"  {index}. {candidate['alias']}")
            choice = terminal_input("Signing alias number: ")
            require(choice.isascii() and choice.isdigit() and 1 <= int(choice) <= len(matching), "Invalid signing alias selection.")
            entry = matching[int(choice) - 1]
    require(entry["certificate_sha256"] == expected,
            "Configured keystore alias has the wrong signing certificate.")
    values["COMICVIEWER_KEY_ALIAS"] = entry["alias"]
    if not entry["key_unlocked"]:
        require(not non_interactive,
                "Cannot unlock the private key; supply the correct COMICVIEWER_KEY_PASSWORD for --non-interactive.")
        key_password = hidden_password("Key password (different from keystore): ")
        retry = signing_probe(root, env, keystore, entry["alias"], store_password, key_password)
        require(len(retry) == 1 and retry[0] == dict(entry, key_unlocked=True),
                "Cannot unlock the private key, or its certificate changed; check the key password.")
    values["COMICVIEWER_STORE_PASSWORD"] = store_password
    values["COMICVIEWER_KEY_PASSWORD"] = key_password
    print(f"Signing key verified: {values['COMICVIEWER_KEY_ALIAS']} ({expected})", flush=True)
    return values


def save_signing_setup(root, values):
    path = root / LOCAL_SIGNING
    require(path.parent.resolve().is_relative_to(root) and not path.is_symlink(),
            "Local signing settings must stay inside the checkout.")
    require(run(root, ["git", "check-ignore", "--quiet", "--", LOCAL_SIGNING], check=False).returncode == 0,
            "Local signing settings must be Git-ignored and untracked.")
    if path.exists():
        read_local_signing(path)  # Refuse to overwrite a different kind of local file.
    config = {"keystore": values["COMICVIEWER_KEYSTORE_PATH"], "alias": values["COMICVIEWER_KEY_ALIAS"]}
    require(Path(config["keystore"]) != path, "Keystore and local settings must use different files.")
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".release.local-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(config, output, indent=2, ensure_ascii=False)
            output.write("\n")
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(f"Saved keystore path and alias to {LOCAL_SIGNING}. No password was saved.")


def remote_source(root, tag, commit):
    git(root, "fetch", "--no-tags", "origin", "+refs/heads/main:refs/remotes/origin/main")
    result = run(root, ["git", "merge-base", "--is-ancestor", commit, "refs/remotes/origin/main"], check=False)
    require(result.returncode == 0, "Release commit is not on remote main; push/merge the source first.")
    local = run(root, ["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], check=False)
    require(local.returncode == 0 and local.stdout.strip() == commit, f"HEAD needs the exact local tag {tag}.")
    refs = dict(line.split()[::-1] for line in git(root, "ls-remote", "--tags", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}").splitlines())
    require((refs.get(f"refs/tags/{tag}^{{}}") or refs.get(f"refs/tags/{tag}")) == commit,
            f"Push the exact tag {tag} to origin before public release preparation.")


def finish_bundle(staging, destination):
    """Never overwrite a staged release. Identical, complete reruns are harmless."""
    if destination.exists():
        require(destination.is_dir() and not destination.is_symlink(), "Staging destination is not a regular directory.")
        old = {p.name: digest(p) for p in destination.iterdir() if p.is_file() and not p.is_symlink()}
        new = {p.name: digest(p) for p in staging.iterdir()}
        require(len(list(destination.iterdir())) == len(old) and old == new,
                f"Conflicting output already exists at {destination}; nothing was overwritten.")
        return
    staging.rename(destination)


def check_existing_bundle(destination, commit, name, code, expected):
    if not destination.exists():
        return
    require(destination.is_dir() and not destination.is_symlink(), "Staging destination is not a regular directory.")
    path = destination / "release.json"
    require(path.is_file() and not path.is_symlink(),
            f"Existing output has no release manifest: {destination}; nothing will be overwritten.")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    require(all(manifest.get(key) == value for key, value in {
        "commit": commit, "version_name": name, "version_code": code,
        "certificate_sha256": expected, "publishable": expected is not None,
    }.items()), "Existing bundle belongs to a different source/version/certificate; nothing will be overwritten.")
    files = manifest.get("files", {})
    require(isinstance(files, dict) and files, "Existing bundle manifest has no file inventory.")
    for filename, sha256 in files.items():
        require(Path(filename).name == filename, "Invalid filename in existing bundle manifest.")
        item = destination / filename
        require(item.is_file() and not item.is_symlink() and digest(item) == sha256,
                "Existing bundle is incomplete or modified; nothing will be overwritten.")
    apks = [filename for filename in files if filename.endswith(".apk")]
    require(len(apks) == 1, "Existing bundle must contain exactly one APK.")
    checksum = apks[0] + ".sha256"
    require({p.name for p in destination.iterdir()} == set(files) | {"release.json", checksum},
            "Existing bundle has unexpected or missing files.")
    require((destination / checksum).read_text(encoding="ascii") == f"{files[apks[0]]}  {apks[0]}\n",
            "Existing bundle checksum was modified.")


def prepare(root, args):
    env = os.environ.copy()
    password_env = {key: env.pop(key) for key in PASSWORD_ENV if key in env}
    if args.setup:
        toolchain(root, env)
        expected = expected_certificate(root, env)
        values = signing(root, env, expected, non_interactive=args.non_interactive,
                         password_env=password_env, setup=True)
        save_signing_setup(root, values)
        values.clear()
        return
    name, code = read_version(root)
    commit = clean_commit(root)
    tools, tool_info = toolchain(root, env)
    history = None
    rebuilding = False
    expected = None
    if not args.development:
        history = PublishedHistory(root, tools, env)
        if args.bump:
            clean_commit(root, commit)
            bump(root, name, code, history, args.bump)
            return
        rebuilding = history.validate(name, code, commit)
        expected = expected_certificate(root, env)
        verify_published_certificate(history, expected)
        run(root, [sys.executable, root / "scripts/compare_apk_signatures.py", "--check"], env, capture=False)
        remote_source(root, f"v{name}", commit)
    notes = validate_notes(root, name, code)
    clean_commit(root, commit)
    dist = root / "dist"
    basename = f"ComicViewer-Android-v{name}"
    destination = dist / (f"development-v{name}-{commit[:12]}" if args.development else f"obtainium-v{name}")
    check_existing_bundle(destination, commit, name, code, expected)
    if not args.development:
        signing_values = signing(root, env, expected, non_interactive=args.non_interactive, password_env=password_env)
    if args.check:
        print(f"Preflight passed: v{name}, code {code}, commit {commit}, "
              + ("unsigned development verification" if args.development else "public release"))
        return

    dist.mkdir(exist_ok=True)
    # Directory-level lock also protects the canonical build output from two concurrent runs.
    lock = dist / ".release-preparation.lock"
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise ReleaseError("Another release run is active (or left dist/.release-preparation.lock); inspect it before removing the lock.") from error
    try:
        with tempfile.TemporaryDirectory(prefix=".release-", dir=dist) as temporary:
            staging = Path(temporary) / "bundle"
            staging.mkdir()
            build_env = {key: value for key, value in env.items() if key not in SIGNING_ENV}
            for script in ("scripts/verify-source-security.sh", "scripts/verify-database-roundtrip.sh"):
                print(f"Running {script}", flush=True)
                run(root, [root / script], build_env, capture=False)
            candidate = Path(temporary) / "candidate-unsigned.apk"
            print("Running build-release.sh", flush=True)
            run(root, [root / "build-release.sh", "--output", candidate], build_env, capture=False)
            unsigned = Path(temporary) / "rebuilt-unsigned.apk"
            run(root, [root / "scripts/verify-reproducible-build.sh", "--output", unsigned], build_env, capture=False)
            require(candidate.is_file() and candidate.read_bytes() == unsigned.read_bytes(),
                    "Canonical release candidate differs from the independent reproducible build.")
            runtime = run(root, [root / "gradlew", "--no-daemon", "--dependency-verification", "strict",
                                "-PcomicViewerUnsignedRelease=true", "-q", ":app:dependencies",
                                "--configuration", "releaseRuntimeClasspath"], build_env).stdout
            require("No dependencies" in runtime and not re.search(r"^[+\\]---", runtime, re.M),
                    "Release runtime dependencies are not empty.")
            info = apk_info(root, tools, candidate, env)
            require(info == {"package": PACKAGE, "version_name": name, "version_code": code, "debuggable": False},
                    "APK package, version, or debuggable flag does not match the release.")
            permissions = run(root, [tools / "aapt2", "dump", "permissions", candidate], env).stdout
            require(not re.search(r"^uses-permission(?:\S*)?:", permissions, re.M), "Release APK requests Android permissions.")
            signature_check = run(root, [tools / "apksigner", "verify", candidate], env, check=False)
            require(signature_check.returncode != 0 and "Missing META-INF/MANIFEST.MF" in
                    signature_check.stdout + signature_check.stderr,
                    "Canonical candidate must be unsigned; signature inspection returned an unexpected result.")
            apk = staging / (f"{basename}-development-unsigned.apk" if args.development else f"{basename}.apk")
            if args.development:
                shutil.copyfile(candidate, apk)
            else:
                run(root, [tools / "apksigner", "sign", "--alignment-preserved", "true",
                           "--v4-signing-enabled", "false", "--debuggable-apk-permitted", "false",
                           "--ks", signing_values["COMICVIEWER_KEYSTORE_PATH"],
                           "--ks-key-alias", signing_values["COMICVIEWER_KEY_ALIAS"],
                           "--ks-pass", "stdin", "--key-pass", "stdin", "--pass-encoding", "utf-8",
                           "--out", apk, candidate], env, capture=False,
                    input_text=signing_values["COMICVIEWER_STORE_PASSWORD"] + "\n"
                    + signing_values["COMICVIEWER_KEY_PASSWORD"] + "\n")
                signing_values.clear()
                run(root, [root / "scripts/verify-release-apk.sh", apk, unsigned, expected], env, capture=False)
                if rebuilding:
                    prior = [r for r in history.records if r["version_name"] == name]
                    require(all(digest(apk) == r["sha256"] for r in prior),
                            "Rebuilt APK differs from the already published APK; bump the version.")
            run(root, [tools / "zipalign", "-c", "-P", "16", "4", apk], env, capture=False)
            shutil.copyfile(notes, staging / "release-notes.md")
            for locale in ("en-US", "fi-FI"):
                shutil.copyfile(root / f"fastlane/metadata/android/{locale}/changelogs/{code}.txt",
                                staging / f"changelog-{locale}.txt")
            checks = {"source_security": "passed", "database_roundtrip": "passed", "unit_tests": "passed",
                      "release_lint": "passed", "independent_reproducibility": "passed", "runtime_dependencies": "empty",
                      "requested_permissions": "none", "signature_rebuild_comparison": "not_run" if args.development else "passed",
                      "connected_android_tests": "not_run", "manual_device_tests": "not_run"}
            clean_commit(root, commit)
            if history:
                # A newer APK may have been published while the local build ran.
                history = PublishedHistory(root, tools, env)
                verify_published_certificate(history, expected)
                rebuilding = history.validate(name, code, commit)
                if rebuilding:
                    require(all(digest(apk) == r["sha256"] for r in history.records
                                if r["version_name"] == name),
                            "Published APK changed during preparation; nothing was staged.")
                remote_source(root, f"v{name}", commit)
            baseline = [r for r in history.records if r["version_name"] != name] if history else []
            manifest = {"schema": 1, "publishable": not args.development, "tag": None if args.development else f"v{name}",
                        "commit": commit, "package": PACKAGE, "version_name": name, "version_code": code,
                        "certificate_sha256": expected, "unsigned_sha256": digest(unsigned), "toolchain": tool_info,
                        "checks": checks, "published_baseline": baseline,
                        "files": {p.name: digest(p) for p in sorted(staging.iterdir())}}
            (staging / "release.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            checksum = staging / f"{apk.name}.sha256"
            checksum.write_text(f"{digest(apk)}  {apk.name}\n", encoding="ascii")
            finish_bundle(staging, destination)
    finally:
        lock.rmdir()
    print(f"Prepared {'unsigned development bundle' if args.development else 'verified release bundle'}: {destination}")
    if not args.development:
        command = ["gh", "release", "create", f"v{name}", str(destination / apk.name), str(destination / checksum.name),
                   str(destination / "release.json"), "--repo", history.repo, "--verify-tag", "--title", f"v{name}",
                   "--notes-file", str(destination / "release-notes.md")]
        if rebuilding:
            print("This exact version is already published. Verified the rebuild; no publication command needed.")
        else:
            print("After device testing and reviewing the bundle, publish manually with:\n  " + shlex.join(command))


def main(argv=None, root=None):
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--setup", action="store_true", help="Verify the signing key and save only its path and alias locally.")
    action.add_argument("--check", action="store_true", help="Run preflight only; do not build, sign, or stage.")
    action.add_argument("--bump", choices=("patch", "minor", "major"), help="Explicitly update the version and create TODO release notes.")
    parser.add_argument("--development", action="store_true", help="Verify locally into a separate unsigned, non-publishable bundle; no GitHub access.")
    parser.add_argument("--non-interactive", action="store_true", help="Read signing passwords from environment variables instead of hidden terminal prompts.")
    args = parser.parse_args(argv)
    if (args.bump or args.setup) and args.development:
        parser.error("--development cannot be combined with --bump or --setup")
    root = (root or Path(__file__).resolve().parent.parent).resolve()
    try:
        prepare(root, args)
    except (ReleaseError, OSError, ValueError) as error:
        print(f"Release preparation failed: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Release preparation interrupted; no final bundle was replaced.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
