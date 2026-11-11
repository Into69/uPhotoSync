<p align="center">
  <img src="Glossy%20Smartphone-to-NAS%20Sync%20Icon.png" alt="uPhotoSync icon" width="160">
</p>

<h1 align="center">uPhotoSync</h1>

<p align="center">
  Back up your Android phone's photos and videos to a UniFi NAS (UNAS) over SMB.
</p>

---

## Download

Get **`uPhotoSync.apk`** from the [Releases page](https://github.com/Into69/uPhotoSync/releases).

To install it, open the APK on your phone. If Android asks, allow your browser or file manager to install apps. You need **Android 10 or newer**.

> This is an early build: it hasn't been widely tested against UNAS hardware yet. Please [open an issue](https://github.com/Into69/uPhotoSync/issues) if something doesn't work.

## Features

- **Device tab:** browse your photos and videos by album. Anything already on the NAS has a cloud badge. Long-press to select items and upload just those.
- **NAS tab:** browse the share and save any photo or video back to your phone.
- **Thumbnails or details:** switch either tab between a grid and a list showing date, size and backup status.
- **Automatic backup:** new photos are uploaded shortly after you take them, and a full check runs every 6 hours. You can limit it to Wi-Fi only or charging only, and choose which albums to include.
- **Progress:** one bar for the current file (bytes sent) and one for the whole job.
- **Your choice of folder:** you choose the destination folder on the NAS, and the app creates folders there if needed.
- **Safe uploads:** each file is written under a temporary name and renamed only once complete, so an interrupted upload never looks finished. Photo location (GPS) data is kept.

## How syncing works

uPhotoSync is a **one-way backup**, not a two-way sync. It never deletes anything on the NAS.

| You do this | What happens |
|---|---|
| Take a new photo | It's uploaded on the next backup |
| Delete a photo on the phone | **The copy on the NAS stays** |
| Delete a photo on the NAS that's still on the phone | It's uploaded again on the next backup |
| Edit a photo on the phone | The edited version is uploaded |
| Change the destination folder | Everything is uploaded to the new folder; the old copies are left alone |

Files are filed by the date they were taken:

```
\\<NAS>\<share>\<destination>\2026\11\IMG_20261110_143210.jpg
```

If a different file with the same name is already in that folder, the new one is saved with its ID added to the name instead of overwriting the existing file.

## Setup

### 1. On the UniFi NAS

1. In **UniFi Drive**, create a shared drive (or use an existing one) for your backups.
2. Make sure a user has **read/write** access to it. That's the username and password the app will use.
3. Note the NAS's IP address, for example `192.168.1.20`.

### 2. In the app

1. Open **Settings** and enter:
   - **NAS address:** IP or hostname
   - **Username / Password:** the NAS user from step 2. The password is encrypted on the phone with the Android Keystore.
2. Tap the **folder button** next to *Share name* or *Destination*. It lists the shares on the NAS. Tap one, go into (or create) the folder you want, then tap **Use this folder**. That fills in both the share and the destination.
3. Turn on **Back up new photos automatically**, or use **Back up** on the Device tab.

You can also type or paste the location, for example `\\192.168.1.20\Photos\Phone`. The app splits it into the address, share and folder.

Allow the photo, video and notification permissions when asked. Allowing access to photo location keeps GPS data in the backups.

## Building from source

You need **JDK 17** and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/uPhotoSync-debug.apk
./gradlew testDebugUnitTest
```

Release builds are shrunk with R8 and signed using a `keystore.properties` file in the project root. That file is git-ignored; create your own:

```properties
storeFile=C:/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/uPhotoSync.apk
```

Without `keystore.properties`, the release APK is built unsigned.

### Built with

Kotlin, Jetpack Compose (Material 3), WorkManager, DataStore, [Coil](https://coil-kt.github.io/coil/) for thumbnails, [smbj](https://github.com/hierynomus/smbj) with BouncyCastle for SMB2/3, and [smbj-rpc](https://github.com/rapid7/smbj-rpc) to list the NAS's shares.

## Troubleshooting

| Message | Meaning |
|---|---|
| *The NAS has no share with that name* (`STATUS_BAD_NETWORK_NAME`, 0xC00000CC) | The share name is wrong. Use the folder button to pick it from the list. |
| *Wrong username or password* | Check the NAS user's credentials in UniFi Drive. |
| *This user doesn't have permission…* | Give the user read/write access to the shared drive. |
| *Couldn't reach the NAS* | Check the address, and that the phone is on the same network as the NAS. |

## Known limitations

- Thumbnails in the NAS tab download the whole file, so images over 40 MB just show an icon, and videos on the NAS have no preview.
- Files deleted on the NAS are re-uploaded on the next backup run, not instantly.
- SMB only; NFS isn't supported.
