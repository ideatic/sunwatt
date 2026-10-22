# build-logic

Gradle included build with the plugin `sunwatt.xcstrings`. It turns the Apple `.xcstrings` catalogs (the single source of the user texts, see `FEATURES.md` "Texts") into Android `strings.xml` files at build time. Generated files are never edited by hand and never committed.

## Use

In the app module: `plugins { id("sunwatt.xcstrings") }`. The task `generateXcstringsResources` writes `build/generated/xcstrings/res` and AGP adds it to every variant. The catalogs default to the six listed in `DEFAULT_CATALOGS` (`XcstringsPlugin.kt`), read from `../apple` (relative to the Android root project). Override with:

```kotlin
xcstrings { catalogs.set(mapOf("core" to layout.projectDirectory.file("...Core.xcstrings"))) }
```

Not converted: the `InfoPlist` catalogs (iOS permission texts) and the Siri phrase catalogs (`AppShortcuts`, `ShortcutTitles`).

## Output

One file per language and table: `values/strings_core.xml`, `values-es/strings_core.xml`, and so on for `es pt fr de it nl`. Tables: `core`, `widgets`, `app`, `intents`, `watch`, `widgetext` (the widget extension `Localizable.xcstrings`).

The build fails, with the table, key and language, when:

- a language is missing for a key;
- a key has `variations` or `substitutions` (plurals are not supported yet: "unsupported variations in <key>").

`shouldTranslate: false` keys go to `values/` only, as `translatable="false"`.

## Resource names

`<table>_<slug>`. The slug comes from the English text (the `en` localization if there is one, else the key):

1. placeholders (`%@`, `%lld`, ...) are removed;
2. accents are folded (`é` -> `e`) and the text is lower-cased;
3. every run of characters outside `[a-z0-9]` becomes `_`;
4. the first 6 words are kept, cut to 48 characters.

If two keys of one table give the same name, both get `_` plus the first 6 hex characters of the SHA-1 of the key. A text with no letters or digits gets the slug `text`.

Example: `"%@ kW now · %@ kWh today"` in `App.xcstrings` is `app_kw_now_kwh_today`.

## Find the name of a key

Run `./gradlew :app:printXcstringsNames` (from `android/`). It prints `name<TAB>key` for every string. Without Gradle:

```sh
java -cp build/classes/kotlin/main:<kotlin-stdlib.jar> sunwatt.xcstrings.XcstringsCli ../../apple --names | grep -i "cheapest"
```

## Text conversion

- `%@` -> `%s`; `%lld`, `%ld`, `%d` -> `%d`; `%f`, `%.1f` stay. A string with more than one placeholder gets positional ones (`%1$s`, `%2$d`) in order of appearance. A single `%` becomes `%%`.
- Escapes: `\`, `'`, `"`, `&`, `<`, `>`, newline, tab. A leading `@` or `?` gets a backslash. Leading, trailing and repeated spaces become ` `.
- Each string has an XML comment with its English key, and the catalog `comment` when there is one.

## Test

```sh
cd android/build-logic && gradle test
```

The tests convert the real catalogs: key counts, same names in all languages, same placeholders as English.
