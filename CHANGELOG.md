# Changelog

## 0.1.4 - 2026-11-17

- Android 7.0/7.1 support: the plugin declares minSdk 24 and every newer Android API it uses has a version check and fallback. Requires PAM Native 1.53.1 or later.

## 0.1.3 - 2026-11-08

- Every `v*` publication tag is certified by the PAM Native ecosystem gate
  (`push-in/pam` `ecosystem-compatibility.yml`). No code changes.

## 0.1.2 - 2026-10-28

- Android: `WebViewFactory` has a `(Context)` constructor, which the
  generated view registry calls; 0.1.1 failed to build in a real app.

## 0.1.1

- Name the reserved protocol keys/events and ship the moved Swift bridge tests.

## 0.1.0

- Owns the optional native webview capability extracted from the 1.46 core.
- Preserves PHP aliases and native contracts during migration.

