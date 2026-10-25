# YAFM: Yet Another File Manager

An Android file manager for phone storage, root and network servers, with a video and music player built in.

[Русская версия](README.ru.md)

## Features

### Files
- Phone storage, and the whole file system with root (KernelSU, Magisk, APatch).
- Copy and move between any storages: phone, root, SFTP, qsh, SMB, FTP. Names already taken get a “ (1)” suffix, nothing is overwritten.
- Categories on the home screen list every image, video, song, document or archive in internal storage, grouped by folder. A folder's files are selected with one tap, and a category can be narrowed to some file types (say, pdf and docx).
- Recent: files added or changed in the last 30 days, by day, each marked as added or changed.
- Favorite folders on the home screen, from any storage. Sort order, file types and list or grid view are kept per folder; category folders can be collapsed.
- Duplicates: files with the same content, under any name, in a category or a folder tree. The copy outside messenger and cache folders is kept.
- Thumbnails for images and videos, type labels on icons (PDF, Word, ZIP...), list and grid views, search, sorting, hidden files.
- Send files to other apps, invert the selection, open any file as text. Files opened from other apps ("Open with") get a choice of actions.
- Batch rename, permissions (chmod), file properties with MD5 and SHA-256. Links can be created in advanced mode (root folders and SFTP).
- Photos: a basic editor (rotate, mirror, resize, draw) that keeps the shooting date and place, and clean copies without metadata for sharing.
- Links are never followed when deleting or copying folders: a link is removed, not what it points to.

### Archives
- Extracts by content, not by name: zip, 7z, tar, ar and cpio, compressed with gz, bz2, xz, lzma, zstd, lz4, snappy, Z or brotli, and single compressed files.
- Creates zip, 7z, tar, tar.gz, tar.bz2, tar.xz, tar.zst and tar.lz4.
- Files are only written inside the chosen folder; links and devices from an archive are skipped. Existing files are kept, skipped or replaced, as chosen.

### Players
- Video: libVLC, so nearly any format plays (mkv with AC3/DTS, avi, wmv, flv...). Gestures for seeking, brightness and volume, audio track and subtitle choice, speed, frame modes, screen lock, resume, next/previous in the folder. Opening a video can ask whether to use it or another app.
- Music: background playback with the system notification and lock screen controls, cover art, shuffle, repeat, the folder as a queue that goes round (also from the Music category); the notification opens the player.

### Network storage
- **SFTP** (SSH): one connection per server; the server key is remembered on first connect and a changed key is refused.
- **qsh** (SSH over QUIC with TCP fallback): browse over the server's sftp subsystem, copy with `qsh cp`. Sign in with this phone's key by pairing with a code from `qshd pair`, or add the key shown in the settings to the server. One-time codes (2FA) are asked for when the server wants one.
- **SMB** 2/3 (Windows shares, NAS) and **FTP** (unencrypted, for trusted networks).
- Saved passwords are encrypted with a key from the Android Keystore.

### Other
- App manager with APK backup, storage analysis by type, root shell (with the top CPU and RAM processes), text editor.
- Themes: system, light, dark and AMOLED black, with wallpaper colors. Portrait and landscape.
- Updates from GitHub releases, checked at startup (can be turned off).
- English and Russian.

## Building

```bash
# once: put the qsh client into the APK (the qsh-aarch64-android.tar.gz asset of a qsh release)
scripts/fetch-qsh.sh <URL or path of qsh-aarch64-android.tar.gz>
./gradlew assembleDebug
```

Without `libqsh.so` the app builds and works, only qsh connections are unavailable. The build is for arm64-v8a only.

Release builds are signed when `YAFM_STORE_FILE`, `YAFM_STORE_PASSWORD` and `YAFM_KEY_ALIAS` are set in `~/.gradle/gradle.properties`.

```bash
./gradlew :app:testDebugUnitTest   # SFTP client, archives, local delete, version checks
```

## Requirements

Android 8.0 or newer, arm64.
