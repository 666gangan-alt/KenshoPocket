# 懸賞Pocket — Android実装（検証待ち版）

更新日: 2026-09-25

## 何を作るアプリか

LINE等の文章・URL・スクリーンショットから懸賞を登録し、締切や毎日の応募を通知し、応募ページを1タップで開き、応募履歴と当選・受取履歴を管理するAndroidアプリ。

戻るジェスチャー、Predictive Back、入力途中の下書き、ブラウザから戻った時の一覧位置保持を最優先の仕様に含めた。

Kotlin＋Compose＋Roomで実装したAndroidアプリです。GitHub Actions run #7で単体試験、Lint、Debug APK生成まで成功しています。実機通知、共有取込、ブラウザ往復、Predictive Back等は未完了のため、v1完成版ではなく検証待ち版として扱います。

## ファイル一覧

| ファイル | 役割 |
|---|---|
| AGENTS.md | Codexが読む共通指示、禁止事項、検証方針 |
| CODEX_START.md | 最初に渡す指示文と、途中からの続行指示 |
| docs/SPEC.md | 全体機能、画面、通知、取込、安全性、納品仕様 |
| docs/UX_NAVIGATION.md | 戻るジェスチャー、下書き、片手操作、状態保持の契約 |
| docs/DATA_MODEL.md | DB、状態遷移、日時、二重登録防止、復元処理 |
| docs/IMPLEMENTATION_PLAN.md | 機能単位の開発順序、ビルド、CI、成果報告 |
| docs/ACCEPTANCE_TESTS.md | 100項目の受け入れ試験仕様 |
| docs/SOURCES.md | 技術上の確認に使った公式資料 |
| testdata/import_cases.json | 文章・日付・URL解析の16ケース |
| testdata/reminder_cases.json | 通知・周期判定の12ケース |
| testdata/statistics_cases.json | 集計の3ケース |

## Codexへの渡し方

ZIPを展開し、**AGENTS.md、CODEX_START.md、docs、testdataがリポジトリのルートに並ぶ形**で配置する。ZIP自体を置くだけではなく、中のファイルを配置する。

Codexでそのリポジトリを開き、CODEX_START.mdの「開始指示」を渡して開発を始める。AGENTS.mdを使ったプロジェクト指示の公式説明はdocs/SOURCES.mdのS13を参照。

別途用意した1ファイル版は閲覧・添付用。分割版を用いる場合は、必要な章だけを拾って重要仕様を省略しない。

## 採用した基本方針

Android単体、Kotlin＋Compose、Room、端末内OCR、ローカル通知。独自サーバー、有料AI API、アカウント登録はv1の前提にしない。

「ワンタップ」は応募ページへの移動を指す。応募操作の完了はユーザーが確認する。年のない日付は候補であり、期限を勝手に確定しない。通知の遅延・不達につながるOS制約を表示する。

## テスト用データについて

文章や日時は架空の解析テスト用。画像で例示されたキャンペーンの開催状況を検証したデータではない。固定日2026-09-23はパーサーの基準日であって、過去の告知がその年のものだと認定する意味はない。

example.invalidのURLには実通信しない。fixtureは完成済みテストコードではなく、Codexが単体テストへ組み込む入力と期待値である。

## 現時点の納品範囲

手動登録、共有候補レビュー、HTTPS応募URL起動、応募確認、通知計画、入力下書き復元、監査ループ、CIを実装済み。最新APKは[Google Drive](https://drive.google.com/file/d/1gD-BcttmvQ1e3MXPC1yUGGRXBYWELhd8/view?usp=drivesdk)から取得できます。状態・試験結果・未実施範囲は[`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md)と[`docs/TEST_REPORT.md`](docs/TEST_REPORT.md)を参照してください。
