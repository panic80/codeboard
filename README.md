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

Recognition runs on the device using bundled word lists. With Autocorrect and Use most likely suggestion enabled, English gestures also use a bounded context preference to resolve close shape matches. Context has less influence when an alternative fits the gesture poorly, especially when the original match closely follows the finger's path. An additional check penalizes candidates whose middle path misses part of the swipe or takes a route the finger did not follow. This reduces errors such as `this`/`third` and `shirt`/`short` while keeping context useful for ambiguous paths. Some spellings share nearly identical paths, so the alternatives remain useful. Swipe words are separated automatically; punctuation stays attached to the preceding word. Backspace immediately after a swipe removes that word. Words outside the dictionaries can be entered by tapping. The word lists and their source/license notices are in `app/src/main/assets/swipe/` and in **Swipe dictionary credits** in settings.

Path matching also compares travel direction, so brief reversals are less likely to disappear when two word shapes look similar. A continuous pause near one position or a small loop can indicate a doubled letter. Time from separate visits to a key is never added together; each pause must stay in a small region continuously. This reduces false doubled-letter evidence on words with reversals and returning strokes, such as `strike`, especially when touch events arrive frequently.

### Autocorrect and personal words

Typed English and Korean words receive spelling suggestions. Pressing Space applies the highest eligible spelling suggestion by default. English ranking considers the preceding words using bundled offline n-gram counts. Valid words compete with alternatives that differ by one substitution, insertion, deletion or letter swap, using context, typing similarity and a bounded word-frequency preference. Automatic replacement requires a clear lead over both the original and competing alternatives. Uncertain alternatives remain available as tappable suggestions, while saved personal words and explicitly selected spellings are protected. The model uses only preceding text, so it cannot use words you have not typed yet. Turn off **Features > Use most likely suggestion** to use conservative correction, which leaves ambiguous words as typed. Tap an alternative to choose a spelling. Backspace immediately after an automatic correction restores the original spelling and keeps the typed space. Autocorrect can be switched off in **Features > Autocorrect** and is unavailable in passwords, email/URL fields, or fields that request no suggestions. Mixed-case identifiers and tokens with numbers or code symbols are preserved.

The **Add word** button beside the suggestions saves the word you are typing directly to your personal dictionary. The button shows the exact spelling it will save and changes to **Saved** afterward. If a word was just autocorrected, **Add word** restores and teaches your original spelling. Spelling suggestions have their own **Features > Spelling suggestions** switch, so you can use suggestions and teach words while automatic correction is off.

English suggestions also offer word completions while you type and next-word predictions after a completed word. Completions use the typed prefix, word frequency and the preceding words. Completion-only suggestions require a tap; Space still uses the spelling-correction result, which can repair an ordinary missing letter such as `giv` → `give`. Next-word choices are marked **→** and append the chosen word plus a space. Unmarked choices still replace the previous word, and Backspace can still undo its automatic correction. These predictions use the bundled offline phrase counts, with two-word context preferred over one-word context. They do not upload typing or require an account. Korean retains its existing spelling and swipe suggestions.

English typo ranking gives common words and omitted letters more weight, so inputs such as `frend` and `collegue` prefer `friend` and `colleague`. Short consonant abbreviations are protected from weak automatic guesses. Context can also surface a two-edit alternative to a rare known word, but these wider matches require a tap. Two-edit alternatives to unknown English inputs of four letters or fewer also require a tap, avoiding changes such as `mssg` to `most`. One-edit corrections and longer-word corrections retain their existing behavior. The phrase data are limited and missing phrases are treated as unknown, so suggestions can still miss the intended word.

Typing assistance also supports editors that do not provide extracted text, using verified keyboard edits, surrounding text, and reported cursor positions. Unknown or ambiguous cursor locations fail closed. Passwords and fields requesting no suggestions remain excluded. The empty suggestion bar explains when spelling suggestions are switched off or unavailable in the current field.

Use **Features > Personal dictionary > Add word or email** to save an English or Korean word or an email address. Personal words are stored on the device, keep their saved spelling, and are shared by autocorrect and swipe typing. Tap a saved word in that screen to remove it. Dictionary changes take effect without restarting the app and survive app updates.

Email addresses can also be saved from the suggestion row after typing a complete address, including digits, dots and plus tags. Use **SYM** for `@` and `+`; switching pages preserves the address being entered. Type the beginning of a saved address and tap its suggestion to reuse it. Addresses remain literal, are never automatically spell-corrected, and do not enter the swipe dictionary. Email fields support these literal suggestions unless the app requests no suggestions.

### Adaptive typing and accuracy tools

**Features > Learn from my typing** is enabled by default. Verified uncorrected word commits build a bounded local vocabulary and phrase history. An unfamiliar word becomes a suggestion after three commits, or immediately after explicitly choosing its spelling. Choosing an alternative twice can favor that same correction in future typing or swipe results. Undoing an automatic correction suppresses that original/replacement pair until an explicit choice teaches it again. Automatically generated corrections are not learned as user examples. Learning can be switched off or reset with **Clear learned typing**; explicitly added personal-dictionary words are retained.

**Adapt to my taps** uses actual tap positions as bounded suggestion evidence. Explicitly choosing a spelling calibrates per-key offsets, separately for portrait/landscape, English/Korean, and keyboard-size buckets. It does not change the key labels or force taps onto a different key. Ordinary spelling corrections remain available when tap positions provide no useful distinction.

Context now includes an authored conversational seed in English and Korean, plus learned continuations using up to four preceding words. English retains the bundled corpus counts. Korean gains next-word predictions and contextual spelling/swipe ranking. These are compact statistical models, not a pretrained neural language model or a general Korean grammar engine. The small authored seed is suggestion evidence, not measured word frequency. Missing-space suggestions are offered only when both words are in the dictionary and their phrase has support; they require a tap and preserve the original token.

**Tools > Review text** previews basic English grammar, missing-space and Korean spacing/spelling repairs for a message of up to 2,000 characters with the cursor at its end. Tap **Apply** to accept or **Undo** to restore the original while the editor remains unchanged. **Features > Chat punctuation repair** additionally allows this review to suggest spaces for semicolons between words. It is off by default, and never rewrites semicolons automatically while coding. This is a limited rule-based review, not unrestricted AI rewriting.

**Tools > Typing test** (also in Features) provides held-out English/Korean sentences, final character/word error rates, typing speed, and a count of characters removed during editing. Use the same prompts with another selected keyboard for comparison. Learning is disabled in the practice field. Only the last aggregate result is saved locally; phone timing and accuracy must be measured on the device, not inferred from unit tests.

Learning and tap calibration are disabled in passwords, email/URL fields, fields requesting no suggestions, and fields marked `IME_FLAG_NO_PERSONALIZED_LEARNING`. Learned history is excluded from cloud backup and device transfer. No typing is sent to a server. The authored seed files `en_phrases.txt` and `ko_phrases.txt` use the application's code license.

### Keyboard tools and typing conveniences

Tap **Tools** beside **SYM** for emoji, clipboard, voice typing, resize, language switching and settings. The emoji picker includes categories and recent selections. Its Backspace removes a complete supported emoji, including flags and variation selectors.

**Tools > Resize** changes keyboard height and width live. Width can be 60–100%; the narrower keyboard is centered. Portrait and landscape sizes are saved separately, with Reset and Done controls.

Drag horizontally across the spacebar to move the cursor. Press Shift first to select text while dragging. Holding Space without dragging still opens Android's keyboard chooser. Cursor control can be disabled in Features.

Automatic English sentence capitalization and double-space period are enabled by default and have independent Features switches. Double-space punctuation applies to ordinary words; email addresses, URLs, numeric tokens and mixed-case code identifiers stay literal. Apps that request no suggestions and password fields are excluded.

**Tools > Voice** opens the phone's installed speech recognizer in English or Korean. It requires a recognizer app and an editor that exposes a verifiable collapsed cursor. Results are inserted only after the original, unchanged editor resumes; ambiguous new input sessions discard the result. Voice availability and focus-return behavior require testing on a device.

**<p align="center">If you are using Codeboard and would like to thank me:</p>**

<p align="center"><a href="https://www.buymeacoffee.com/gazlaws" target="_blank" ><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee" style="height: 41px !important;width: 164px !important;box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;-webkit-box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;" ></a></p>
