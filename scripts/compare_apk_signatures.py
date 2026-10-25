#!/usr/bin/env python3
"""Verify a rebuilt APK using the original signature and the pinned SDK signer."""
import argparse
from pathlib import Path
import sys
import zipfile


def load_copier():
    try:
        import apksigcopier
    except ImportError as error:
        raise RuntimeError(
            f"Install apksigcopier for {sys.executable}; see docs/RELEASING.md."
        ) from error

    # apksigcopier 1.1.1 explicitly sets the UTF-8 filename bit even for ASCII
    # META-INF names. Newer zipfile versions clear it on write, changing signed
    # header bytes. Preserve that explicit bit only in the copier's metadata
    # class, without changing zipfile globally or the APK signature checks.
    base = apksigcopier.APKZipInfo
    probe = base(zipfile.ZipInfo("META-INF/MANIFEST.MF"))
    if probe.flag_bits & 0x800 and not probe._encodeFilenameFlags()[1] & 0x800:
        class PreservedFilenameFlags(base):
            def _encodeFilenameFlags(self):
                if self.flag_bits & 0x800:
                    return self.filename.encode("utf-8"), self.flag_bits
                return super()._encodeFilenameFlags()

        apksigcopier.APKZipInfo = PreservedFilenameFlags
    return apksigcopier


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check Python dependency compatibility only.")
    parser.add_argument("--apksigner", type=Path)
    parser.add_argument("signed_apk", nargs="?")
    parser.add_argument("unsigned_apk", nargs="?")
    args = parser.parse_args()
    if not args.check and not all((args.apksigner, args.signed_apk, args.unsigned_apk)):
        parser.error("--apksigner, signed_apk, and unsigned_apk are required")
    try:
        copier = load_copier()
    except (RuntimeError, AttributeError) as error:
        print(f"Signature comparison unavailable: {error}", file=sys.stderr)
        return 1
    if args.check:
        return 0
    try:
        copier.do_compare(args.signed_apk, args.unsigned_apk, unsigned=True,
                          verify_cmd=(str(args.apksigner.resolve()), "verify"))
    except (copier.APKSigCopierError, OSError, zipfile.BadZipFile) as error:
        print(f"Signature-aware comparison failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
