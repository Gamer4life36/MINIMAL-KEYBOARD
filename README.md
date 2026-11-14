# Simple Keyboard

A minimal Android keyboard: plain QWERTY, dark theme, no emojis, no toolbar, no suggestion bar.

- Letters page: QWERTY, shift (double-tap for caps lock), backspace (hold to repeat), `?123`, comma, wide space bar, enter.
- `?123` page: numbers and basic punctuation, including the period.
- Quiet autocorrect: typos are fixed when a word ends (space, punctuation or enter), a quick double space types `. `, and sentences start with a capital when the text box asks for it. Off in password, email and web-address fields.
- On Android 16+ the system's globe/switcher strip under the keyboard is replaced by a single hide-keyboard arrow.
- Works fully offline. Needs no permissions.

## Build

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Install it, open **Simple Keyboard**, tap **1. Turn on keyboard**, then **2. Switch to it**.

## Word list

`app/src/main/assets/words.txt` and `shortcuts.txt` are generated from the AOSP LatinIME English word list (Apache 2.0, see `NOTICE-wordlist.txt`) by `tools/build_dict.py`. `tools/AutocorrectCheck.java` is a desktop check of the autocorrect engine against common typos.
