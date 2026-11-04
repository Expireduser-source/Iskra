# Store graphics

Everything here is made from the app's own art (the character pictures belong to the project owner).

| File | How |
|---|---|
| `app/src/main/res/drawable-nodpi/ic_launcher_art.webp` | launcher icon foreground: head of `iskra_stand.webp`, fitted into the adaptive-icon safe zone |
| `fastlane/metadata/android/*/images/icon.png` | 512×512 store icon, same portrait on the brand background |
| `fastlane/metadata/android/*/images/featureGraphic.png` | 1024×500 banner: `node tools/store/render.js` (Chromium via Playwright) |
| `fastlane/metadata/android/*/images/phoneScreenshots/` | real screenshots from the CI emulator check (`smoke-api*.zip` on the build release) |
| `app/src/main/res/drawable-nodpi/qr_project.png` | QR code of the project page: `tools/qr/qr_png.py` (needs `pip install qrcode pillow`) |
