# CLAUDE.md — "If This Is the End" Study App (Light Phone III)

Read this at the start of every session. If anything here conflicts with what you remember
about Android development, **this file wins** — this is not a standard Android environment.

---

## 1. Platform constraints (non-negotiable)

- **Target:** Light Phone III on LightOS. **1080 × 1240, 3.92", black and white.** API 34.
  **No Google Play Services.**
- **Monochrome screen — color never carries meaning.** Use label, position, weight, layout.
- **All app code in the `tool` module**, using primitives from `sdk/client`.
- **Kotlin, Compose, Coroutines, MVVM.** Screens extend `LightScreen`; view models extend
  `LightScreenViewModel`.
- **Navigate with `navigateTo` only.** Back stack is automatic — do NOT override
  `onBackPressed()`.
- **The SDK does not render a back bar.** Every non-root screen places its own:
  ```kotlin
  LightTopBar(
      leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
  )
  ```
  Root (`@InitialScreen`) screens omit it — `goBack()` there finishes the activity.
  `sdk/client/README.md:64` claims the framework provides one. It is wrong.
- **Third-party libraries are restricted and lint-enforced.** Check `lint-rules/` first.
- **Offline only.** No network, no sync, no accounts, no analytics.
- **Confirm SDK components exist** in `sdk/client/` or `NOTES.md` before using them.

## 2. Build commands

```bash
./gradlew :tool:assembleDebug
```

Requires `gpr.user` / `gpr.key` in `local.properties`. **Never read, print, or commit it.**

---

## 3. What the app is

Display name: **Small Group**. Set `label = "Small Group"` in `tool/lighttool.toml`.

⚠️ **The `id` in `lighttool.toml` must be unique.** Jordan's prayer list app already uses
`com.thelightphone.app` on the same phone. Installing this tool with that id would
overwrite the prayer app and destroy its data. Change `id` before the first install.

A personal companion to a 27-lesson small-group study of 1 and 2 Peter. It holds the
lesson content and lets Jordan write and keep his own answer to every question.

Design values: quiet, fast to open, comfortable to read and to type in. No streaks, no
gamification, no notifications.

---

## 4. The content file

`if_this_is_the_end.json` — read from device storage, never bundled in the APK. It is
copyrighted study material for personal use: **it is never committed to the repo.** Add it
to `.gitignore`.

Structure:

```json
{
  "title": "If This Is the End",
  "lessonCount": 27,
  "lessons": [
    {
      "lesson": 17,
      "title": "Live in Light of the End",
      "passage": "4:7—11",
      "scriptureRef": "1 Peter 4:7–11",
      "scripture": "The end of all things is near; therefore...",
      "commentary": "...",
      "items": [
        { "id": "L17Q1", "type": "question", "number": 1, "text": "When Peter says..." },
        { "id": "L17C1", "type": "childrens", "number": null, "text": "How and when do you pray?" }
      ],
      "footnotes": ["1 Peter 1:15–16"]
    }
  ]
}
```

Notes on the data:
- `items` is in **book order** — questions and Children's Questions interleaved. Preserve
  that order; do not sort or separate them.
- `type` is `question` or `childrens`. Children's Questions are shown inline, visually
  distinguished (they have no number).
- `scripture`, `commentary`, and `footnotes` may be empty strings or empty arrays. Lesson 1
  has no scripture (it is a read-the-whole-book overview); most lessons have no commentary.
  Handle empties by omitting the section, not by showing a blank heading.
- **`id` is the stable key for answers.** Store every answer against the item `id`, never
  against a list position.

---

## 5. Answers — the core of the app

- One answer per item id. Free text, multi-line, no length limit.
- **Autosave.** Save on every meaningful pause and on leaving the screen. Jordan is typing
  paragraphs on a 3.92" keyboard; losing an answer to a mis-tap is unacceptable.
- Answers persist in the app's own storage, independent of the content file. Reinstalling
  the app with `adb install -r` must never lose them.
- Each lesson also has a free-text **Notes** field (the book has a notes and prayer-requests
  page per lesson). Key it `L<n>NOTES`.
- **Export:** a way to write all answers out to a single readable text file, so Jordan can
  pull it off the phone with adb and keep it. The `tool` module cannot reach a real
  external-files-directory path — `Context` is import-blocked and `SealedLightContext`
  exposes no `getExternalFilesDir` equivalent. Use `lightContext.fileShare`
  (`LightFileShare`) as the drop location instead, the same approach the prayer-list
  project used for its JSON seed import (see `NOTES.md` §7).

---

## 6. Screens

**HomeScreen** (`@InitialScreen`) — the 27 lessons in order. Each row: lesson number,
title, passage reference, and how many of its questions have an answer (e.g. "7/12").
Export is reachable from here.

**LessonScreen** — one lesson. In order: passage reference, scripture text, commentary if
present, then the list of items in book order. Each item row shows its question text
(truncated if long) and whether it has an answer. Footnotes at the bottom if present.
Access to the lesson's Notes.

**ItemScreen** — one question. Full question text at the top, the current answer (or a
"tap to write your answer" placeholder) below it. Tapping the answer opens **AnswerEditorScreen**.
Next and previous buttons move between items without going back to the lesson list — this
matters when working through a lesson in one sitting.

Note: the SDK's only free-text input component, `LightTextInputEditor`, takes over the whole
screen (its own top bar, embedded keyboard, submit button) and only has room for a short
one- or two-line title — not the full question text. So the question display and the answer
editor are two screens, not one:

**AnswerEditorScreen** — `LightTextInputEditor` wrapping one item's answer, opened from
ItemScreen. Autosaves on a short pause while typing and flushes a final save on every way of
leaving the screen — the SAVE button, the back button, or the app being backgrounded — via
the screen's `willHide()` / `onAppPause()` hooks, so no route out of this screen can lose an
answer.

**NotesScreen** — the lesson's free-text notes.

---

## 7. Build order

One phase at a time. Do not start the next until the current one compiles and Jordan has
confirmed it looks right.

0. **Recon** — if `NOTES.md` was copied from the prayer-list project, verify it against the
   current SDK rather than redoing it from scratch. Otherwise write it.
1. **Data layer** — load and parse the content file from device storage; answer storage
   keyed by item id. No UI.
2. **HomeScreen and LessonScreen** — navigation and display only, no editing.
3. **ItemScreen** — the answer editor, autosave, next/previous navigation.
4. **Notes and export.**
5. **Polish** — typography and line spacing for reading and typing on a small screen;
   empty states; lint clean.

---

## 8. Working agreement

- If an SDK component doesn't exist, say so and propose building it from primitives.
- If a lint rule blocks an approach, name what it blocked and give two alternatives.
- Keep functions short and the layout obvious. Jordan reads this code himself and is not an
  Android developer.
- Don't add features that aren't in this spec. Suggest, don't build.
- **Never alter, paraphrase, or regenerate the study text.** Display exactly what the
  content file says. If something looks wrong, surface it rather than fixing it.
