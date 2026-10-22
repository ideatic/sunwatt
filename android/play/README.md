# Google Play listing

Store texts for Google Play, in the fastlane `supply` layout (`listings/<locale>/`). Nothing is uploaded from here yet.

## Mapping from the App Store texts

| Play file | Limit | Source |
| --- | --- | --- |
| `title.txt` | 30 | `apple/AppStore/metadata/<locale>/name.txt` (same text) |
| `short_description.txt` | 80 | New text. The App Store subtitle (30) is too short and the promotional text (170) too long. |
| `full_description.txt` | 4000 | The App Store `description.txt`, with Android words: Quick Settings tiles instead of Control Center, app shortcuts instead of Siri, Wear OS instead of Apple Watch, no Lock Screen widgets. |
| (none) | | `keywords.txt` has no Play field. Google reads the title and the descriptions. |
| `video.txt`, images | | Later: screenshots from the emulator (phone, 7-inch and 10-inch tablet, Wear OS). |

Locales: `en-US` (source), `es-ES`, `pt-PT`, `fr-FR`, `de-DE`, `it-IT`, `nl-NL` (the app's languages), plus `zh-CN`, `zh-TW`, `pl-PL` (store only: the app shows English there, so their images use the English screenshots with local captions; DECISIONS D40). The ASO rules of DECISIONS D24 apply (no third-party trademarks in the title or short description; wholesale prices never sold as a retail tariff).

## Translations

English first. Then one Haiku subagent per locale, as in `AGENTS.md` ("Translations"), with the App Store text of the same locale as the reference for terms and tone. The main agent checks every result.

## Check

```bash
python3 -I android/play/check_listing.py android/play/listings
```

## Images

Files in `listings/<locale>/images/` for `en-US` and `es-ES` (fastlane `supply` layout):

| File | Size | Notes |
| --- | --- | --- |
| `icon.png` | 512x512, 32-bit PNG | The Apple icon (`apple/App/Assets.xcassets/AppIcon.appiconset/icon-ios-1024.png`) scaled down. |
| `featureGraphic.png` | 1024x500, 24-bit PNG, no alpha | Icon, name, tagline, dashboard and a widget. |
| `phoneScreenshots/1..6.png` | 1080x2160 (2:1) | Dashboard, cheapest hours, widgets, settings, dark mode, several plants. |
| `wearScreenshots/1..3.png` | 454x454 (1:1) | Watch production page, price page, complications. |

How they were made (demo data only, no real plant or link):

1. Phone emulator, demo mode, one language at a time:
   `adb -s emulator-5554 shell cmd locale set-app-locales com.ideatic.sunwatt --locales es-ES`
   `adb -s emulator-5554 shell am start -S -n com.ideatic.sunwatt/.MainActivity --ez demo true --ez seed true --es scenario day` (also `plants`, `--es screen settings`, `cmd uimode night yes`).
2. Widgets: `am broadcast -n com.ideatic.sunwatt/.widget.DebugWidgetReceiver --es size small|medium|large`, tap "Add to home screen", then `screencap`.
3. Watch emulator: `adb -s emulator-5556 shell am start -n com.ideatic.sunwatt/.wear.demo.SeedActivity --es scenario day`, then start `.wear.MainActivity`, swipe up for the price page, start `.wear.demo.ComplicationPreviewActivity`.
4. Raw `screencap` PNGs were put on a branded canvas with Python PIL (status bar and nav bar cropped, caption on top). The icon is a 512 px resize of the Apple icon.

The widget shots show the time of the capture (widgets read the real clock), not 13:40.
