# AGENTS.md — Autogram project instructions

## Language

**All project documentation and code comments are written in English.** This
includes:

- Release notes (`docs/release-notes/*.md` + GitHub release bodies).
- README, this AGENTS.md, and any other `.md` files in the repo.
- Code comments (Kotlin `//` and KDoc `/** */`).
- Git commit messages.
- Timber log messages (they feed Sentry breadcrumbs and must be readable
  by any developer).

**Do not translate:**

- `strings.xml` resources — those are per-locale user-facing UI text.
  The app ships with 6 locales (`values/`, `values-es/`, `values-fr/`,
  `values-de/`, `values-zh/`, `values-ja/`). `values/` is the English
  fallback; the others are translations for end users.

## Documentation format

Release notes follow the NitrOdoo pattern:

```
# vX.Y.Z — Short title + emoji

Short paragraph explaining the release's purpose.

## ✨ Added  |  🐛 Fixed  |  🔍 Changed  |  🧪 Tests  |  🔧 Internal

- **Bold lead** for each bullet. Explain *why* the change was made,
  not just *what* changed. If there's a non-obvious tradeoff or
  rationale, include it inline.

## Install

One-sentence upgrade note if anything is non-obvious (migrations,
new permissions, user-visible re-configuration).
```

## Release flow

`scripts/publish_autogram_release.sh --manual <version>` picks up
`docs/release-notes/v<version>.md` automatically and uses it as the
GitHub release body. Write the notes file first, then run the script.

When a user needs to test a newly created build on a phone or in Android
Auto, publish it to GitHub first so the in-app updater can install it from
`PepeImpulso/autogram-releases`. Do not tell the user to manually install a
fresh local APK unless they explicitly ask for that fallback.
