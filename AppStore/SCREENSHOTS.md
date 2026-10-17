# SunWatt: App Store screenshots

Rendered, not captured: `cd Packages/SunWattKit && swift run SunWattSnapshots --store ../../AppStore/screenshots [en-US es-ES ...]` writes `AppStore/screenshots/<locale>/<n>_<device>_<shot>.png` (iPhone 6.9" 1320 x 2868, iPad 13" 2064 x 2752, Mac 2880 x 1800; no alpha; demo data; each language shows its own market). The Mac set has no Lock Screen shot. The Siri shot (6) is not rendered yet. Backgrounds: `AppStore/art/bg-{day,night}-{portrait,landscape}.png`, made with an image model (abstract light only, no text or UI); every screen and widget on top is the real app view, so the shots show the app as it is. Upload with `scripts/upload_metadata.sh --screenshots AppStore/screenshots`.

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

Existing widget renders are in `Screenshots/widgets/` (small, medium, large, Lock Screen). Use them as art for the framed screens, but the final shots must be real captures from the simulator at the sizes above.

## Screens, in order (iPhone, iPad, Mac)

1. Price widgets on the Home Screen: small + medium + large, price high, coloured. Caption key `price`.
2. Solar widget (today curve, current kW). Caption key `solar`.
3. Cheapest hours: large widget or dashboard card with "cheapest upcoming hours". Caption key `cheap`.
4. Lock Screen widgets (rectangular + circular) and the Control Center control. Caption key `lock`.
5. App dashboard with the interactive chart and country picker. Caption key `app`.
6. Siri / Shortcuts result ("What's the electricity price in SunWatt?"). Caption key `siri`.

Apple Watch (3 shots): price complication on a watch face; Watch app with today's chart; solar value. Captions: `watch` (only 1 line on watch art, optional).

Capture in light mode for 1, 2, 3, 5 and dark mode for 4, 6, so the set shows both looks. Use demo data only: no real plant name or address.

## Captions (max 40 characters)

| Key | en-US | es-ES | pt-PT | fr-FR | de-DE | it | nl-NL |
|---|---|---|---|---|---|---|---|
| price | Electricity price at a glance | El precio de la luz, de un vistazo | O preço da luz num relance | Le prix de l'élec d'un coup d'œil | Strompreis auf einen Blick | Il prezzo della luce a colpo d'occhio | Stroomprijs in één oogopslag |
| solar | Your solar output, live | Tu producción solar, en directo | A tua produção solar, ao vivo | Votre production solaire en direct | Ihre Solarleistung live | La tua produzione solare, live | Je zonne-opbrengst, live |
| cheap | Find the cheapest hours | Encuentra las horas más baratas | Descobre as horas mais baratas | Repérez les heures les moins chères | Die günstigsten Stunden finden | Trova le ore più convenienti | Vind de goedkoopste uren |
| lock | On Lock Screen and Control Center | En bloqueo y Centro de control | No ecrã bloqueado e Centro de Controlo | Écran verrouillé et Centre de contrôle | Sperrbildschirm & Kontrollzentrum | Blocco e Centro di Controllo | Toegangsscherm en Bedieningscentrum |
| app | Charts you can explore | Gráficas que puedes explorar | Gráficos que podes explorar | Des graphiques interactifs | Interaktive Diagramme | Grafici interattivi | Interactieve grafieken |
| siri | Just ask Siri | Pregúntaselo a Siri | Pergunta à Siri | Demandez à Siri | Einfach Siri fragen | Chiedi a Siri | Vraag het aan Siri |
| watch | Price on your wrist | El precio en tu muñeca | O preço no pulso | Le prix au poignet | Der Preis am Handgelenk | Il prezzo al polso | De prijs op je pols |

Length check: the longest are pt-PT `lock` (38) and nl-NL `lock` (35). Run a quick count after any edit: each must be 40 or less. Spanish captions say "luz" (PVPC); for Spain screens 1 and 3 may add "PVPC" in the screenshot art.
