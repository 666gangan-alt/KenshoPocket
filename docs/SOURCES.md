# 技術仕様の確認に用いた公式資料

確認日: 2026-09-23

この設計の画面構成、データモデル、初期値、上限、テスト項目は本プロジェクト独自の採用仕様。以下はAndroid・ML Kit・Codexの既存機能と制約を確認するための公式資料である。ライブラリの版と互換性は実装開始時に再確認して固定する。

## S01 — Predictive Back

Android Developers: Set up Predictive back。対応するNavigation ComposeやMaterialコンポーネント、標準アニメーション、ルートで戻るを横取りする影響を確認。

```text
https://developer.android.com/develop/ui/compose/system/predictive-back-setup
```

## S02 — 操作領域・アクセシビリティ

Android Developers: API defaults。48dp以上の操作領域、Semantics、読み上げラベル等の基準を確認。

```text
https://developer.android.com/develop/ui/compose/accessibility/api-defaults
```

## S03 — 通知権限

Android Developers: Notification runtime permission。Android 13以降のPOST_NOTIFICATIONS、許可・拒否時の挙動と要求タイミングを確認。

```text
https://developer.android.com/develop/ui/compose/notifications/notification-permission
```

## S04 — アラームと時刻精度

Android Developers: Schedule alarms。非正確／正確アラーム、SCHEDULE_EXACT_ALARM、権限確認、取消・再構築、電力制限等を確認。

```text
https://developer.android.com/develop/background-work/services/alarms
```

## S05 — 他アプリからの共有受取

Android Developers: Receive simple data from other apps。共有Intent、テキスト・画像の受け取り方を確認。

```text
https://developer.android.com/develop/ui/compose/sharing/receive
```

## S06 — 日本語の画像文字認識

Google for Developers: Recognize text in images with ML Kit on Android。日本語モデル、bundled／unbundledの違い、画像品質の影響を確認。bundledはモデルがビルド時に含まれる方式。

```text
https://developers.google.com/ml-kit/vision/text-recognition/v2/android
```

## S07 — 応募ページのブラウザ表示

Chrome for Developers: Overview of Android Custom Tabs。アプリ内の導線からブラウザ機能を使うための構成を確認。

```text
https://developer.chrome.com/docs/android/custom-tabs
```

## S08 — 永続的なバックグラウンド処理

Android Developers: Task scheduling。WorkManagerを継続・遅延可能なバックグラウンド処理に使う方針を確認。時刻指定通知の絶対保証として扱わない。

```text
https://developer.android.com/develop/background-work/background-tasks/persistent
```

## S09 — DB移行

Android Developers: Migrate your Room database。schema管理、Migration、破壊的移行のデータ損失に関する挙動を確認。

```text
https://developer.android.com/training/data-storage/room/migrating-db-versions
```

## S10 — OSバックアップの制御

Android Developers: Back up user data with Auto Backup。バックアップ設定と除外、端末移行の扱いを確認。アプリ独自のクラウド同期とOS機能を混同しない。

```text
https://developer.android.com/identity/data/autobackup
```

## S11 — ユーザーが選ぶファイル保存・読込

Android Developers: Access documents and other files from shared storage。Storage Access Frameworkによる保存先・読込対象の選択を確認。

```text
https://developer.android.com/training/data-storage/shared/documents-files
```

## S12 — 画面端とキーボード

Android Developers: About window insets。システムバー、IME、ジェスチャー領域とアプリUIの重なりを避けるための構成を確認。

```text
https://developer.android.com/develop/ui/compose/system/insets
```

## S13 — Codexへのプロジェクト指示

OpenAI公式: Custom instructions with AGENTS.md。Codexが作業前にAGENTS.mdを読み、階層に沿って指示を扱う仕組みを確認。長大な設計全部をAGENTS.mdへ押し込まず、重要制約と読むファイルを指定する。

```text
https://developers.openai.com/codex/guides/agents-md
```

確認時、このURLはOpenAIのChatGPT Learn側の公式解説へ転送された。

```text
https://learn.chatgpt.com/docs/agent-configuration/agents-md
```

## S14 — APKの署名と更新

Android Developers: Sign your app。インストール可能なAPKの署名、debug署名、継続配布用の署名鍵の保管・更新を確認。

```text
https://developer.android.com/studio/publish/app-signing
```
