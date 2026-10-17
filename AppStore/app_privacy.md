# SunWatt: App Store Connect answers

## App Privacy questionnaire

**Do you or your third-party partners collect data from this app?** No.
Result: **Data Not Collected**.

Why:
- No account, no login.
- No analytics, no crash-reporting SDK, no ads, no third-party SDKs.
- The app contacts only: the FusionSolar server in the user's own kiosk link, `apidatos.ree.es` and `api.energy-charts.info`. These are the data sources the app reads from. The developer receives nothing.
- Settings and the last data stay on the device (App Group shared with the widgets).
- No location, contacts, identifiers, purchases or usage data are read or sent.

"Collect" in Apple's terms means data sent off the device to the developer or a partner. Public price APIs get ordinary HTTP requests with no identifier. The FusionSolar request goes to the user's own plant provider with the user's own link. Check: do not add the privacy answers "Data Linked to You" or "Used to Track You".

Privacy policy URL: `https://REPLACE-ME/sunwatt/privacy` (host `docs/privacy.html`).

## Export compliance

- Uses encryption? Only standard HTTPS (URLSession / system TLS).
- Answer: **No** to "non-exempt encryption". Set in Info.plist: `ITSAppUsesNonExemptEncryption = NO`.
- No proprietary or custom encryption. No French declaration needed.

## Age rating (result: 4+)

Answer **None** / **No** to all:
- Cartoon or fantasy violence, realistic violence, prolonged graphic violence
- Sexual content or nudity, profanity or crude humor
- Alcohol, tobacco or drug references, mature/suggestive themes
- Horror/fear themes, medical/treatment information
- Simulated gambling, contests, gambling
- Unrestricted web access: **No**
- User-generated content, chat/messaging: **No**
- Parental controls / age assurance features: **No**

## Content rights

- "Does your app contain, show or access third-party content?" **Yes**.
- "Do you have the necessary rights?" **Yes**:
  - Spain PVPC prices: public data from Red Eléctrica (`apidatos.ree.es`).
  - Other countries: Energy-Charts, Fraunhofer ISE, licence CC BY 4.0. The app shows the attribution ("Energy-Charts, Fraunhofer ISE, CC BY 4.0"). Check that the in-app "About" or settings screen has this text before submission.
  - Solar data: the user's own plant, read through the user's own kiosk link.
- Not affiliated with Huawei, Red Eléctrica or Fraunhofer ISE. The description says so.
- Keywords contain no third-party trademarks (no Huawei, no FusionSolar).

## Other fields

- Seller / developer: ideatic Development, S.L.U. (Spain)
- Price: Free. No in-app purchases.
- Copyright: 2026 ideatic Development, S.L.U.
- Primary category: Utilities. Secondary: Lifestyle.
- Spain DSA trader status: App Store Connect asks if you are a trader. A company selling a free app: answer per your legal advice (usually "trader") and give the company address and contact data.
