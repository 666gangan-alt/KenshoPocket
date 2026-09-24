# 実装状態

更新日: 2026-09-25

## 工程

| 工程 | 状態 | 内容 |
|---|---|---|
| P0 | 自動試験済み | Androidプロジェクト、Compose、Room、Navigation、CI、Debug APK。GitHub Actions run #7でtest/lint/build成功 |
| P1 | 新規登録実装済み／既存編集・実機確認待ち | 手動登録、一覧、詳細、HTTPS起動、ブラウザ復帰確認、応募履歴。BackHandler、タブ移動抑制、下書き保存を実装。既存キャンペーンのURL・締切・周期編集導線と実機Back/ブラウザ往復は未実装／未試験 |
| P2 | 実装済み／実機確認待ち | 非通信パーサー、共有UI、一括保存、候補のタイトル・締切・選択編集、再生成復元、旧raw下書き互換、同一下書き操作の再試行冪等性を実装。候補URL・周期の編集UIは次工程、共有実機フローは未試験 |
| P3 | CI検証済み／実機確認待ち | Room通知設定・予定・履歴、AlarmManager、WorkManager、通知アクション、設定・診断UIを実装。GitHub Actionsで49 unit tests・Lint・APK生成成功。Windowsではmigrationテスト1件がSQLiteアクセス環境依存で失敗 |
| P4〜P7 | 未着手 | 設計の実装計画に沿って継続 |

## 機能

| ID | 状態 |
|---|---|
| F01 手動登録 | 実装済み（入力途中のRoom下書き、システム戻る／Predictive Back経路を含む。実機未試験） |
| F02 文章・URL共有 | 実装済み（解析・候補状態・下書き復元・同一操作再試行。共有UI実機未試験） |
| F04 自動抽出 | 実装中（文章解析11試験PASS。ネットワーク取得・OCRは未実装） |
| F05 一覧・検索 | 実装中（一覧のみ） |
| F06 応募ページ | 実装済み／実機確認待ち（HTTPSのみ、危険URLは確認導線、Custom Tabs失敗時は標準ブラウザ／コピーへフォールバック） |
| F07 応募記録 | 実装済み／実機確認待ち（応募済みの自動確定なし、起動セッションを永続復元） |
| F08 リマインド | CI検証済み／実機確認待ち（計画・周期計算・Room永続化・OS連携・診断UI。通知実配信とDoze等は未試験） |
| その他 | 未着手 |

自動試験、Lint、APKビルドの結果は `TEST_REPORT.md`に記録する。実機試験済みとは扱わない。

## 今回のP3実装

- 確認済み締切の3日前・前日・当日と時刻の2時間前。確認済み周期のみ毎日／毎週通知。
- 未来7日分をDBに保存し、OSには次回1件だけ予約。標準と時刻優先（未許可時フォールバック）。
- 応募済み・期限超過・通知権限・チャンネル・おやすみ時間を配信前に再判定。
- 再起動・時刻変更・アプリ更新・起動・データ変更・24時間周期で再整合。
- 通知から詳細／応募ページ、期限内の1時間スヌーズ。編集中は画面移動を保留。
- 「その他」に設定・診断・テスト通知・予定・理由別履歴。詳細から個別設定。
- DB v1→v2の非破壊migration。取込由来DAILY/WEEKLYは推定値のため再確認を必要にする。
- 投稿中マーカーと安定tagで再試行。履歴は表示・閲覧成功と区別し90日保持。

## 次の検証／残作業

1. WindowsのRobolectric migrationテストでSQLiteファイル作成環境を直し、49件をローカルでも全件成功させる。
2. 実機で通知許可／拒否、再起動、Doze、通知アクション、システム戻る／共有取込／ブラウザ往復を試験する。
3. 独立した確認リマインダー、周期モード・開始曜日・リセット時刻の編集UI、賞品期限通知は未実装。

v1全体の完成とは扱わない。現在の配布物は検証待ちの0.2.0-dev APKであり、実機試験を完了したリリース版ではない。

ローカルWindowsではPATHにJDKがなく、Gradle監査はBLOCKED。GitHub ActionsのJDK 17 Ubuntu runnerで同一コミットの検証を行い、ローカル制約と製品CIの判定を分離する。

GitHub-hosted Ubuntu runner用のリモート検証環境を`.github/workflows/android.yml`に構築済み。JDK 17、Gradle Wrapper検証、単体試験、Lint、Debug APK生成、APK／レポートArtifactsを定義した。run #7（commit `5ff17d4`）は成功し、APK SHA-256は`FD15BA3A328FE5F7E253CC2D8220101FDF04625D9DB8AC176193468B501F76FC`。GitHub ActionsはNode/Ubuntu移行のwarningを表示するが、ビルド結果は成功。登録手順は`docs/GITHUB_REMOTE_BUILD.md`参照。

## 監査ループの今回の成果

- `fcfd213`: HTTPS候補の確認導線、監査スクリプト、6時間ループ文書を追加。
- `883c3e8`: 共有／手動下書きの候補状態復元、起動セッションのプロセス再生成復元、応募確認の旧保留状態解消、通知予約境界、同一取込操作の再試行冪等性を追加。
- `13ae862`: NavHostのシステム戻る／Predictive Backでも手動入力を保存するBackHandlerを追加。
- `e93eaae` / `5ff17d4`: 子画面のタブ遷移による入力破棄を抑止し、タイトル未入力の部分入力、旧raw共有下書き、互換テストを追加。
- 監査役サブエージェントは読み取り専用で再監査し、High指摘を修正後に`assembleDebug`・`lintDebug`・CIを再確認した。
- CI生成APKはGoogle Driveの[KenshoPocket-debug-7-5ff17d4.apk](https://drive.google.com/file/d/1gD-BcttmvQ1e3MXPC1yUGGRXBYWELhd8/view?usp=drivesdk)へ保存済み（18,234,568 bytes）。
