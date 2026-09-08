# CLAUDE.md — Prayer List Tool for Light Phone III

Read this file at the start of every session. If anything here conflicts with what you
remember about Android development, **this file wins** — this is not a standard Android
environment.

---

## 1. Platform constraints (non-negotiable)

- **Target:** Light Phone III running LightOS. Screen is **1080 × 1240, 3.92", black and
  white.** Android API 34. **No Google Play Services.**
- **Because the display is monochrome, color can never carry meaning.** Distinguish things
  by label, position, weight, and layout. Never "the green one means answered."
- **All app code goes in the `tool` module**, using primitives from `sdk/client`. Do not
  restructure the project or add modules.
- **Kotlin, Compose, Coroutines, MVVM.** Screens extend `LightScreen`; view models extend
  `LightScreenViewModel`. Each screen needs a direct reference to its ViewModel's class
  type and a factory method to create one. Follow `HomeScreen` as the pattern.
- **Navigate with `navigateTo` only.** LightOS does not use Android system navigation.
- **The SDK does not render a back bar.** Every non-root screen must place its own
  `leftButton` on `LightTopBar` using `LightBarButton.LightIcon` with `LightIcons.BACK`,
  calling `goBack()`. The back stack is automatic — **do not override `onBackPressed()`.**
  (`sdk/client/README.md:64` claims a back bar is provided; it is wrong.)
- **Third-party libraries are restricted and lint-enforced.** Do not add a dependency
  without checking `lint-rules/` first. If you think you need one, stop and ask me.
- **Offline only.** No network calls, no sync, no accounts, no analytics. All data stays
  on the device.
- **Before using any SDK component, confirm it exists** by reading `sdk/client/` or
  `NOTES.md`. Do not invent component names from memory of Material or other libraries.

## 2. Build commands

```bash
./gradlew :tool:assembleDebug     # must pass before any phase is considered done
./gradlew tasks                   # confirm exact task names rather than guessing
```

Requires `gpr.user` and `gpr.key` in `local.properties`. **Never read, print, echo, or
commit that file.**

---

## 3. What the app is

A private prayer list. I keep track of people I pray for, organized into groups (small
group, Bible study, family, church), and for each person I track three separate kinds of
notes: prayer requests, praises, and general life updates.

Design values: quiet, fast to open, readable at a glance, minimal typing. It should feel
like a paper notebook, not a productivity app. No streaks, no badges, no notifications
unless I specifically ask for them later.

---

## 4. Data model

```
Group
  id: String
  name: String
  sortOrder: Int

Person
  id: String
  name: String
  groupId: String?        // null = ungrouped
  note: String?           // optional one-line context, e.g. "Dave's brother"
  archived: Boolean

Entry
  id: String
  personId: String
  type: EntryType         // REQUEST | PRAISE | UPDATE
  text: String
  createdAt: Long
  answeredAt: Long?       // REQUEST only; non-null means answered
  archived: Boolean
```

Rules:

- A person belongs to at most one group.
- Only `REQUEST` entries can be marked answered. Answered requests leave the main
  Requests list and appear in an "Answered" section at the bottom of that tab.
- Nothing is ever hard-deleted without an explicit delete action from me, and every
  delete is confirmed first (see §4a).

## 4a. Archive vs Delete

These are two separate actions. **Archive** is reversible and keeps history; **Delete**
is permanent. They are never the same control, and where both appear they must be
visually distinct without relying on color (different position/section, wording, an
icon, or the fact that Delete always asks for confirmation).

**Archive** — people only.

- Sets `Person.archived = true`. The person leaves every list (Home counts, Group
  screens, Manage's People section) and moves to Manage's "Archived" section.
- Every `Entry` for that person is kept, untouched. Unarchiving restores the person
  exactly as they were.
- Archive never asks for confirmation — it changes nothing permanently.

**Delete** — permanent, not reversible, available for entries, people, and groups.

- **Delete an entry** removes just that one `Entry` row. Nothing else is affected.
- **Delete a person** removes that `Person` and *every* `Entry` belonging to them
  (requests, praises, updates — answered or not, archived or not). Works the same for
  an archived person.
- **Delete a group** always asks what to do with the people in it — the app never
  decides:
  - *Keep them* — the people stay, become ungrouped (`groupId = null`), and keep all
    their entries. Only the `Group` row is removed.
  - *Delete them too* — every person in the group and all of their entries are
    deleted as well, then the group.

**Every delete shows a confirmation screen first.** The SDK has no dialog component,
so this is a dedicated screen (`ConfirmDeleteScreen`, or `ConfirmDeleteGroupScreen`
for the group choice). The confirmation names exactly what is being deleted and what
goes with it, with counts — e.g. "Delete Ben Bayly and their 12 entries?" — and has
explicit confirm and cancel actions. Cancel (and the back button) must leave
everything untouched: the confirmation screen itself never writes; the caller only
deletes on an affirmative result.

---

## 5. Screens

**HomeScreen** — list of groups, each row showing group name and person count. An
"Ungrouped" row appears only when ungrouped people exist. Access to the manage screen
from here.

**GroupScreen** — the people in one group, alphabetical. Each row shows name and optional
note. Tap a person to open them.

**PersonScreen** — the core screen. Person's name at top. Three tabs across:
**Requests | Praises | Updates**. Below, the entries of the selected type, newest first,
each showing text and date. Empty tabs show a short plain message. An add action creates
a new entry of the currently selected type. Tapping an entry allows edit, mark-answered
(requests only), or delete.

**EntryEditScreen** — text field, type selector, save, cancel.

**ManageScreen** — create/rename/delete/reorder groups; add/rename/archive/delete people;
move a person between groups. Archive and delete follow §4a; a person's Delete is on the
person's edit screen (reached from here, and from the Archived section).

---

## 6. Build order

Work one phase at a time. Do not start the next phase until the current one compiles and
I've confirmed it looks right.

0. **Recon** — read `docs/`, `sdk/client/`, `examples/`, `lint-rules/`; write `NOTES.md`
   documenting available components, base classes, forbidden APIs, and persistence options.
1. **Data layer** — models plus a repository with persistence and a small seed dataset.
   No UI.
2. **Navigation** — HomeScreen and GroupScreen.
3. **PersonScreen** with the three tabs.
4. **Entry creation and editing**, including mark-answered.
5. **ManageScreen**.
6. **JSON seed import** from the app's external files directory, one-time on launch.
7. **Polish** — sorting, empty states, lint clean, emulator check at 1080 × 1240.

---

## 7. Working agreement

- If an SDK component you want doesn't exist, say so and propose building it from
  primitives. Don't silently substitute a Material or AndroidX component.
- If a lint rule blocks an approach, tell me what it blocked and give me two alternatives
  rather than picking one and moving on.
- Keep functions short and the file layout obvious. I'll be reading this code myself, and
  I'm not an Android developer.
- Don't add features that aren't in this spec. If you think one would help, mention it —
  don't build it.
