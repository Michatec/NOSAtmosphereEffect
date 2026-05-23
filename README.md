# Atmo Engine

**Atmo Engine** is an Android application designed to replicate the distinctive "Atmosphere" transition effect found in Nothing OS.

## 📥 Download
Atmo Engine is available to download from the Orion Store and F-Droid.

<a href="https://f-droid.org/packages/com.saad_khan_rind.atmosphere_effect/">
<img alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80">
</a>

## ⚠️ Device Support & Disclaimer

**Current Testing Status:**
This application has currently been tested **exclusively on the Samsung Galaxy S25 Ultra and Nothing Phone 1**.

While it may work on other Android devices running Android 13+ (API 33+), behavior on different manufacturers' skins (FuntouchOS, OxygenOS, etc.) is not guaranteed.

## Usage Guide

Follow these steps to set up the effect properly on your device.

### 1\. Select Your Effect

Open the app and choose your desired atmosphere style from the selection screen:

* **Original Atmosphere:** Signature style. Drifting ambient atmospheric clouds that transition to blur when unlocked.
* **Reverse Atmosphere:** Mysterious reveal. Deep ambient clouds fade to a sharp clear view when unlocked.
* **Simple Frosted:** Modern minimalism. Applies a clean, uniform frosted glass blur (no clouds).
* **Simple Frosted (Reverse):** Elegant clarity. Wakes up from a heavy frosted blur into a crystal clear wallpaper.
* **Halftone Print:** Retro aesthetic. Sharp view dissolves into comic-book CMYK dots when locked.
* **Halftone Print (Reverse):** Retro aesthetic. CMYK dots seamlessly expand into continuous color when unlocked.
* **Color Fill:** Liquid awakening. Colors flow outward from your fingerprint.
* **Color Fill (Reverse):** Fluid drain. Colors wash away into grayscale.


### 2\. Select Image & Playlist Mode
After selecting an effect, you will be prompted to choose your wallpaper mode:

* **Single Image:** Standard mode. Pick one image, crop it, and apply.

* **Multiple Images (Playlist):** Select multiple images from your gallery to create a Wallpaper Playlist. You can simply apply the play list as it or adjust & crop any image from the playlist you want. Once finished, the app will automatically rotate through these wallpapers based on your settings.

* **Edit Existing Playlist:** If you already have a playlist running, this option loads your currently saved wallpapers (including your exact zoom and crop settings). You can easily remove old images, add new ones from your gallery, or tweak existing crops without having to start from scratch.

### 3\. Application & Activation

Please follow these simple steps to apply the wallpaper:

1. **Apply the Wallpaper:** Once you are happy with your crop or playlist selection, tap the **"Apply"** button.
2. **Review Instructions:** A dialog box will appear with instructions to set the wallpaper to both screens. Tap **"Set Wallpaper"** to proceed.
3. **Set Wallpaper:** The app will redirect you to the Android System's Live Wallpaper preview screen. Tap **"Set Wallpaper"** (or the checkmark/apply icon, depending on your device).
4. **MANDATORY Selection:** When prompted, you must select **"Home screen and Lock screen"**.
   > *Why? Both screens must be controlled by the live wallpaper to ensure a smooth transition when you unlock your device.*
5. **Finish:** Setup is complete! Lock and unlock your screen to see the applied effect in action.


## Advanced Customization
Take full control of the animation and look. You can now tweak the following settings dynamically:
### Visual Adjustments
* **Dimness Level:** Adjust the darkening overlay to ensure your home screen icons remain readable against bright wallpapers.
* **Blob Saturation:** (Original Atmosphere & Reverse Atmosphere Effects Only) Adjusts the color intensity of the drifting atmospheric clouds. Increase to make the colors vibrant and punchy, or decrease to zero for a muted, grayscale cloud effect.
* **Blob Contrast:** (Original Atmosphere & Reverse Atmosphere Effects Only) Adjusts the harshness of the atmospheric clouds. Higher values create distinct, separated color pools, while lower values blend the colors softly and smoothly together.
* **Blur Strength:** (Frosted Effects Only) Use the slider to fine-tune the intensity of the blur radius, from a light mist to heavy glass.
* **Noise Grain:** Enable a film-grain texture on top of the blur. You can customize:
    * **Noise Strength:** How visible the grain is.
    * **Noise Scale:** The size/coarseness of the grain particles.
* **Halftone Pixel Size:** (Halftone Effects Only) Dynamically adjust the size of the printed dots. Setting this to `0` renders the original continuous tones instead of dots.
* **Black & White Effect:** (Halftone Effects Only) Converts the CMYK color halftone pattern into a single-channel grayscale newspaper print.
* **Fingerprint Location:** (Color Fill Effects Only) Two sliders to adjust the horizontal and vertical position of effect start place sync with the fingerprint location.
### Animation & Behavior
* **Animation Duration:** Control the total transition duration.
* **Lock Delay (Anti-Flicker):** Adds a configurable pause before the wallpaper resets when you lock the phone. This prevents the visual glitch where the wallpaper "snaps" back to its initial state before the screen turns fully black.
* **Unlock Check Interval:** Adjusts how frequently the app detects unlock events. Tuning this eliminates "delayed start" issues, ensuring the animation begins immediately when you wake your device.
### Playlist & Rotation
(Only available when using Multiple Images mode)
* **Rotation Interval:** Controls how often the wallpaper changes from your playlist.
    * **Options:** System Theme (Light/Dark), Every Lock (Instant), 15 Minutes, 1 Hour, up to 24 Hours.
    * **System Theme Sync:** When selected, the wallpaper will only change when your device switches between Light and Dark mode.
    * **Smart Rotation:** To prevent lag or visual glitches, the wallpaper only rotates when the screen is OFF.
    * *Example:* If you set "15 Minutes", the app checks the time whenever you lock your phone. If 15 minutes have passed since the last change, it swaps the wallpaper in the background so it's ready the next time you unlock.

## Screenshots
<div align="center">
  <img src="https://github.com/user-attachments/assets/5ca9fd98-880a-4377-973b-9192771aa185" width="45%" alt="1st" />
  <img src="https://github.com/user-attachments/assets/e8bda8f0-821d-43c4-8194-421916560c64" width="45%" alt="2nd" />
  <br/>
  <img src="https://github.com/user-attachments/assets/160d8da4-fe29-40f2-90c1-74f0fc003fdc" width="45%" alt="3rd" />
  <img src="https://github.com/user-attachments/assets/1e0734af-2e51-4941-9d03-42cd032099c0" width="45%" alt="4th" />
</div>

## Telegram Group
I've made a telegram group for the discussion of issues and feature suggestion. You can join it using [this link](https://t.me/atmosphereEffect).

## Known Issues

* **Samsung Adaptive Clock:** As mentioned, programmatically setting the lock screen interferes with Samsung's Adaptive Clock on OneUI.

## Build & Installation

This project is built using Kotlin and Gradle.

1.  Clone the repository.
2.  Open in Android Studio (Ladybug or newer recommended).
3.  Sync Gradle.
4.  Build and Run on your device.

<!-- end list -->

```bash
git clone https://github.com/yourusername/NOSAtmosphereEffect.git
```

## Author

**Saad Ullah Khan**
📍 Passau, Germany
📧 [khansaad45678900@gmail.com](mailto:khansaad45678900@gmail.com)
🔗 [LinkedIn](https://www.linkedin.com/in/saadullahkhan456)
💻 [GitHub](https://github.com/saad-khan-rind)
📄 [Download Resume](https://drive.usercontent.google.com/u/0/uc?id=1tj_Cz6jpkkibTZ4Ed-ReYybzOUu6k4Vw&export=download)

## License

This project is open-source and available under the [MIT License](LICENSE).

## Privacy Policy

The privacy policy is [this](https://saad-khan-rind.github.io/NOSAtmosphereEffect/privacy-policy).

---

⭐️ **Feel free to fork, star, and use this code!**

---

