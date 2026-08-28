# ComicViewer privacy policy

Effective date: 2026-08-28

ComicViewer is an offline Android comic reader maintained by
[the jnesew GitHub account](https://github.com/jnesew). It is designed not to collect, transmit,
sell, or share personal information.

## Data the app accesses

ComicViewer can access only comics and folders that the user selects through Android's system
document picker. Depending on the selected provider, filenames, folder names, comic metadata, and
comic page images may contain personal or sensitive information. ComicViewer uses that content only
on the device to display and organize the user's library.

The app requests no Android storage or media permission and no network permission.

## Data stored on the device

ComicViewer stores the following in its private application storage:

- document references granted by Android;
- titles, series metadata, page counts, and local content fingerprints;
- reading progress, bookmarks, favorites, reading direction, zoom, and display preferences;
- generated cover images and temporary caches needed to read selected documents.

A seekable document is normally read in place. If a document provider cannot supply random access,
ComicViewer may create a temporary private cache copy for the active reading session and removes it
when the comic closes.

## Collection, sharing, and third parties

ComicViewer has no account system, advertising, analytics, telemetry, crash-reporting service,
cloud synchronization, or third-party runtime library. The app performs no network operation, so it
does not send comic content, usage information, device identifiers, or other data to the maintainer
or any third party.

Android's document picker and the user's chosen document provider are platform or separately
installed components. Their handling of files is governed by their own terms and privacy policies.

## Retention and deletion

Library metadata and reading state remain on the device until the user removes the corresponding
title or clears ComicViewer's application data. Removing a title deletes ComicViewer's associated
private state and caches but does not delete the original comic. Uninstalling the app asks Android
to remove its private application data.

ComicViewer disables Android backup and declares cloud-backup and device-transfer exclusions for
all application data. Some device manufacturers may customize device-transfer behavior outside the
application's control.

## Children and accounts

ComicViewer does not knowingly collect information from children or adults and does not provide
account creation.

## Changes and contact

Material changes to this policy will be published in this file and reflected by its effective date.
For privacy questions, use the [repository issue tracker](../../issues/new) without attaching private
documents or personal comic content. Security vulnerabilities should instead follow
[SECURITY.md](SECURITY.md).
