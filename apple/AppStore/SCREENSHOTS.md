# SunWatt: App Store screenshots

Rendered, not captured: `cd Packages/SunWattKit && swift run SunWattSnapshots --store ../../AppStore/screenshots [en-US es-ES ...]` writes `AppStore/screenshots/<locale>/<n>_<device>_<shot>.png` (iPhone 6.9" 1320 x 2868, iPad 13" 2064 x 2752, Mac 2880 x 1800; no alpha; demo data; each language shows its own market). The Mac set has no Lock Screen shot. The Siri shot (6) is not rendered yet. Backgrounds: `AppStore/art/bg-{day,night}-{portrait,landscape}.png`, made with an image model (abstract light only, no text or UI); every screen and widget on top is the real app view, so the shots show the app as it is. `AppStore/screenshots/` is git-ignored (333 MB for 7 languages): render all languages right before the upload (leave out the language list), check them, then upload with `scripts/upload_metadata.sh --screenshots AppStore/screenshots`.

Apple scales down from the largest size in each family. Upload the sizes below. Check current sizes in App Store Connect before capture: Apple changes the list when new devices ship.

## Required sizes

| Device | Display | Size in px (portrait) | Need |
|---|---|---|---|
| iPhone | 6.9" (iPhone 16/17 Pro Max class) | 1320 x 2868 (also accepted: 1290 x 2796, 1260 x 2736) | Required, 1 to 10 |
| iPhone | 6.5" | 1284 x 2778 or 1242 x 2688 | Only if 6.9" is not given. Skip |
| iPad | 13" (M4 / Pro 13") | 2064 x 2752 (also accepted: 2048 x 2732) | Required if the app runs on iPad |
| Mac | any | 1280 x 800, 1440 x 900, 2560 x 1600 or 2880 x 1800 | Required if the app runs on Mac |
| Apple Watch | Ultra 3 / Series 11 class | 422 x 514 (Ultra 3), 416 x 496 (Series 10/11), 410 x 502 (Ultra 1/2), 396 x 484 (Series 9) | Required if there is a Watch app. Upload the largest size shown in App Store Connect |

Rules: PNG or JPEG, no alpha, sRGB. 1 to 10 screenshots per device and per language. Use the same 5 to 6 screens in all 7 languages (en-US, es-ES, pt-PT, fr-FR, de-DE, it, nl-NL).

Widget and app renders (`swift run SunWattSnapshots ../../Screenshots/widgets`, git-ignored) are handy to check a single widget; the store set itself comes from `--store` above.

## Screens, in order (iPhone, iPad, Mac)

Every slide shows a different surface, so the set does not repeat the same UI.

1. Solar widgets (today curve, current kW): the main feature. Caption key `solar`. All devices.
2. Price widgets on the Home Screen: small + medium + large. Caption key `price`. All devices.
3. Lock Screen widgets (rectangular + circular) and the Control Center controls. Caption key `lock`. iPhone, iPad.
4. Apple Watch: price page (with the cheapest hours) in front, solar page behind. Caption key `watch`. iPhone only.
5. App dashboard with the interactive chart (a touched point). Caption key `app`. All devices.
6. Setup screen with the three solar systems. Caption key `setup`. iPhone, Mac (on the iPad it is a small card lost on the big screen).

Not used now: `cheap` (the watch and the dashboard already show the cheapest hours) and `siri`.

Apple Watch (3 shots): price complication on a watch face; Watch app with today's chart; solar value. Captions: `watch` (only 1 line on watch art, optional).

All slides use the warm day background so the set reads as one; the Lock Screen keeps its night wallpaper inside the phone. Use demo data only: no real plant name or address.

## Captions (max 40 characters)

| Key | en-US | es-ES | pt-PT | fr-FR | de-DE | it | nl-NL |
|---|---|---|---|---|---|---|---|
| price | Electricity price at a glance | El precio de la luz, de un vistazo | O preço da luz num relance | Le prix de l'élec d'un coup d'œil | Strompreis auf einen Blick | Il prezzo della luce a colpo d'occhio | Stroomprijs in één oogopslag |
| solar | Your solar output, live | Tu producción solar, en directo | A tua produção solar, ao vivo | Votre production solaire en direct | Ihre Solarleistung live | La tua produzione solare, live | Je zonne-opbrengst, live |
| cheap | Find the cheapest hours | Encuentra las horas más baratas | Descobre as horas mais baratas | Repérez les heures les moins chères | Die günstigsten Stunden finden | Trova le ore più convenienti | Vind de goedkoopste uren |
| lock | On Lock Screen and Control Center | En bloqueo y Centro de control | No ecrã bloqueado e Centro de Controlo | Écran verrouillé et Centre de contrôle | Sperrbildschirm & Kontrollzentrum | Blocco e Centro di Controllo | Toegangsscherm en Bedieningscentrum |
| app | Charts you can explore | Gráficas que puedes explorar | Gráficos que podes explorar | Des graphiques interactifs | Interaktive Diagramme | Grafici interattivi | Interactieve grafieken |
| siri | Just ask Siri | Pregúntaselo a Siri | Pergunta à Siri | Demandez à Siri | Einfach Siri fragen | Chiedi a Siri | Vraag het aan Siri |
| watch | Also on your Apple Watch | También en tu Apple Watch | Também no teu Apple Watch | Aussi sur votre Apple Watch | Auch auf Ihrer Apple Watch | Anche sul tuo Apple Watch | Ook op je Apple Watch |
| setup | For Huawei, SolarEdge and PVOutput | Para Huawei, SolarEdge y PVOutput | Para Huawei, SolarEdge e PVOutput | Pour Huawei, SolarEdge et PVOutput | Für Huawei, SolarEdge und PVOutput | Per Huawei, SolarEdge e PVOutput | Voor Huawei, SolarEdge en PVOutput |

Length check: the longest are pt-PT `lock` (38) and nl-NL `lock` (35). Run a quick count after any edit: each must be 40 or less. Spanish captions say "luz" (PVPC); for Spain screens 1 and 3 may add "PVPC" in the screenshot art.
