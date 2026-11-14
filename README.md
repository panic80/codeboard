# [codeboard](https://play.google.com/store/apps/details?id=com.gazlaws.codeboard)

## Coding on your mobile device just got easier. No more switching between numbers, symbols and alphabets.

### Features:
- Cut, copy, paste, select all
- Tab
- Korean two-set (두벌식) keyboard with Hangul syllable composition
- All the braces {[<(
- ; / \ ! | &$ etc
- No ads or IAP, made for students!

### Korean keyboard

In settings, choose **Korean (두벌식)** under **Set Keyboard Layout**. Use **한/영** to switch between Korean and English. Shift selects the double consonants and shifted vowels, and backspace removes the last part of a composing syllable.

### Swipe typing

Swipe across the letter keys, then lift your finger to enter an English or Korean word. The row above the keyboard shows alternatives; tap one to replace the current word. Swipe typing is enabled by default and can be disabled in **Features > Swipe typing**. It is unavailable in password and numeric fields. Taps, coding symbols, and Ctrl shortcuts keep their usual behavior.

Recognition runs on the device using bundled word lists. With Autocorrect and Use most likely suggestion enabled, English gestures also use a bounded context preference to resolve close shape matches; clear gesture paths retain priority. Swipe words are separated automatically; punctuation stays attached to the preceding word. Backspace immediately after a swipe removes that word. Words outside the dictionaries can be entered by tapping. The word lists and their source/license notices are in `app/src/main/assets/swipe/` and in **Swipe dictionary credits** in settings.

### Autocorrect and personal words

Typed English and Korean words receive spelling suggestions. Pressing Space applies the highest eligible spelling suggestion by default. English ranking considers the preceding words using bundled offline n-gram counts; strongly supported adjacent-key mistakes can also be corrected when they form a rare dictionary word (for example, “is bot” → “is not”). Common valid phrases, saved personal words and explicitly selected alternatives remain protected. Turn off **Features > Use most likely suggestion** to use conservative correction, which leaves ambiguous words as typed. Tap an alternative to choose a spelling. Backspace immediately after an automatic correction restores the original spelling and keeps the typed space. Autocorrect can be switched off in **Features > Autocorrect** and is unavailable in passwords, email/URL fields, or fields that request no suggestions. Mixed-case identifiers and tokens with numbers or code symbols are preserved.

The **Add word** button beside the suggestions saves the word you are typing directly to your personal dictionary. The button shows the exact spelling it will save and changes to **Saved** afterward. If a word was just autocorrected, **Add word** restores and teaches your original spelling. Spelling suggestions have their own **Features > Spelling suggestions** switch, so you can use suggestions and teach words while automatic correction is off.

Typing assistance also supports editors that do not provide extracted text, using verified keyboard edits, surrounding text, and reported cursor positions. Unknown or ambiguous cursor locations fail closed. Passwords and fields requesting no suggestions remain excluded. The empty suggestion bar explains when spelling suggestions are switched off or unavailable in the current field.

Use **Features > Personal dictionary > Add word or email** to save an English or Korean word or an email address. Personal words are stored on the device, keep their saved spelling, and are shared by autocorrect and swipe typing. Tap a saved word in that screen to remove it. Dictionary changes take effect without restarting the app and survive app updates.

Email addresses can also be saved from the suggestion row after typing a complete address, including digits, dots and plus tags. Use **SYM** for `@` and `+`; switching pages preserves the address being entered. Type the beginning of a saved address and tap its suggestion to reuse it. Addresses remain literal, are never automatically spell-corrected, and do not enter the swipe dictionary. Email fields support these literal suggestions unless the app requests no suggestions.

### Keyboard tools and typing conveniences

Tap **Tools** beside **SYM** for emoji, clipboard, voice typing, resize, language switching and settings. The emoji picker includes categories and recent selections. Its Backspace removes a complete supported emoji, including flags and variation selectors.

**Tools > Resize** changes keyboard height and width live. Width can be 60–100%; the narrower keyboard is centered. Portrait and landscape sizes are saved separately, with Reset and Done controls.

Drag horizontally across the spacebar to move the cursor. Press Shift first to select text while dragging. Holding Space without dragging still opens Android's keyboard chooser. Cursor control can be disabled in Features.

Automatic English sentence capitalization and double-space period are enabled by default and have independent Features switches. Double-space punctuation applies to ordinary words; email addresses, URLs, numeric tokens and mixed-case code identifiers stay literal. Apps that request no suggestions and password fields are excluded.

**Tools > Voice** opens the phone's installed speech recognizer in English or Korean. It requires a recognizer app and an editor that exposes a verifiable collapsed cursor. Results are inserted only after the original, unchanged editor resumes; ambiguous new input sessions discard the result. Voice availability and focus-return behavior require testing on a device.

**<p align="center">If you are using Codeboard and would like to thank me:</p>**

<p align="center"><a href="https://www.buymeacoffee.com/gazlaws" target="_blank" ><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee" style="height: 41px !important;width: 164px !important;box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;-webkit-box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;" ></a></p>
