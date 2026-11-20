# SagaBridge

**SagaBridge** (`com.cipher.sagabridge`) is a customized Android Auto bridge tailored for the **Proton Saga 2026** head unit (CarbitLink / EasyConnection and QDLink screens).

The app runs locally on your Android phone, starts an Android Auto session, and streams it to the car screen over **Wi-Fi Direct (wireless)** or **USB cable**, forwarding touchscreen input back to Android Auto.

---

## Proton Saga 2026 Optimizations

SagaBridge ships with a built-in **Proton Saga 2026** profile and pre-tuned defaults out of the box:

- **Resolution & Scaling:** 720p downscale (`1280x720`), Landscape orientation, `160 DPI`, and **Fill** mode for clean full-screen display.
- **Smooth Streaming:** 30 fps target frame rate at `4000 kbps` bitrate for stable wireless performance.
- **Audio Routing:** `phone_direct` mode so music, calls, and navigation prompts play directly through the car's Bluetooth with zero delay or re-encoding overhead.
- **Auto-Updates:** Linked directly to this repository's GitHub Releases for automatic in-app update notifications.

## Requirements

- Android 10 (API 29) or newer with **Android Auto** installed.
- Car head unit supporting **CarbitLink**, **EasyConnection**, or **QDLink**.
- Wi-Fi enabled for wireless connection, or a USB data cable.

## Download

Download the latest APK from the [Releases page](https://github.com/ahmedabdelalimpro/SagaBridge/releases/latest).

## First-Time Setup

1. Grant **Notifications**, **Location** (for automatic day/night mode), and **Microphone** permissions.
2. Enable Android Auto Developer Mode: open Android Auto settings and tap **Version** 10 times.
3. Start the head unit server: tap the **⋮** menu and select **Start head unit server**.
4. Connect to your car:
   - **Wireless:** Keep the car screen on the **CarbitLink / Phone Link** page, tap **Connect without a cable**, and select your car.
   - **Cable:** Plug the phone into the car's USB port, choose **SagaBridge**, and tap **Always**.

## Every Drive

- **Wireless:** Open **SagaBridge** — it automatically finds and connects to your remembered car.
- **Cable:** Plug in and Android Auto launches automatically on the car screen.
- Keep your phone paired to the car's Bluetooth for media, calls, and voice guidance.

## Troubleshooting

- **"Android Auto needs a tap":** Android Auto stops its head unit server after a phone reboot. Tap the notification, then **⋮** and **Start head unit server**.
- **Stuck on "Starting…":** Go to **Settings → Apps → Android Auto → Force stop**, then start the head unit server again.
- **Can't find your car wirelessly:** Make sure Wi-Fi is on, the car screen is open on the Phone Link page, and the phone is close to the head unit.

## Building from Source

Requires **JDK 17** and **Android SDK 34**.

```bash
./gradlew assembleDebug