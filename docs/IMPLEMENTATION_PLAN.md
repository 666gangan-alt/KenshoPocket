# 実装計画・成果物・開発の進め方

対象: 懸賞Pocket v1.0

この順序は機能を削るためではなく、初期から実機で縦につながる動作を確認し、不具合の原因を切り分けやすくするためのもの。各段階の完了とv1全体の完成を区別する。

## P0. 環境と最小ビルド

リポジトリの現状を確認し、既存コード・ユーザーの変更・署名・applicationId・DBを保護する。空リポジトリならGradle Wrapperを含むAndroid Studioプロジェクトを作成する。

stableの互換構成を固定し、Version Catalogを作る。JDK、Gradle、AGP、Kotlin、SDK、Compose BOM、Room、Navigation、Browser、WorkManager、ML Kitの採用版をBUILD_ENVIRONMENT.mdへ記録する。

日本語resources、テーマ、アプリアイコン、Manifest、MainActivity、基本NavHost、初期CIを作る。仮のアプリアイコンで開始してよいが、他社ロゴの流用はしない。

終了条件: 最小アプリをbuildでき、生成APKを確認できる。環境に端末があれば起動できる。ここだけでアプリ完成としない。

## P1. 手動登録から応募確認までの動く縦切り

RoomのCampaign、URL、EntryRule、EntryRecord、LaunchSession、Draftを実装する。今日／一覧／詳細／編集をつなぎ、手動登録→永続保存→URL起動→復帰確認→応募履歴まで動かす。

この段階から戻るジェスチャー、下書き、スクロール復帰、IME対応を入れる。あとから全面的にナビゲーションを付け替える設計にしない。

終了条件: 再起動後も保存データが残る。URL起動だけでは応募済みにならない。ブラウザ復帰で一覧が先頭に跳ねない。戻るジェスチャーをキャンセルしてもデータが消えない。

## P2. 文章・共有・一括取込

ImportSession、候補パーサー、日付候補、URL処理、タグ、共有入口を作る。プレビュー、選択、候補編集、年確認、一括保存、重複候補の処理を実装する。

`testdata/import_cases.json`をテストに読み込み、結果の指定部分を検証する。文字列の解析処理と通信を分ける。共有UIから戻る際も下書きが残る。

終了条件: 複数件の文章を分けられる。年なし日付を勝手に確定しない。不正URLを開かない。同じ取込確定操作で二重登録されない。

## P3. 通知・周期・診断

PeriodCalculator、ReminderPlanner、Scheduler、Dispatcherを分離実装する。通知権限、チャンネル、時刻優先オプション、再起動再整合、時刻変更、応募後停止、翌周期再開をつなぐ。

アプリ設定に次回通知プレビュー、テスト通知、権限状態、ログを作る。スケジュールされたことと実際に届いたことを混同しない。

終了条件: `testdata/reminder_cases.json`と通知のUI/実機テストが対象環境で通る。朝の締切後に通知しない。拒否・強制停止等を診断で説明できる。

## P4. 当選・受取・履歴・集計

WinRecordと応募結果の整合を実装する。単独当選登録、画像参照、手続／受取期限、受取済みの通知停止、カレンダー、履歴、タグ検索を完成させる。

`testdata/statistics_cases.json`で集計の分母、未判明結果、単独当選、複数賞品、ポイント未換算を確認する。

終了条件: 締切を過ぎても結果待ちを保持。賞品期限切れでも当選履歴が消えない。集計値が明記した定義と一致する。

## P5. 画像・OCR・任意ページ取得

画像選択、画像共有、外部カメラ、内部コピー、プレビュー、回転、端末内OCRを実装する。初回機内モードでもbundledモデルが利用できることを確認する。

元文章への確認画面を通し、URL誤認識・年不足を明示する。ページ情報取得は明示操作だけで動作し、通信制限、リダイレクト検査、危険URL拒否を単体テストとモックで検証する。

終了条件: 画像から候補を作れて、失敗しても編集・貼り付けで続行できる。OCRの誤りを黙って補正しない。ページを取得できても安全や開催中と断言しない。

## P6. バックアップ・復元・更新保護

論理JSON＋画像のZIP書出し、manifest検証、置換復元、復元ジャーナル、CSV書出しを実装する。アーカイブ・ゴミ箱・復元・完全削除の関連履歴処理を完成させる。

破損、容量不足、処理中キャンセル、プロセス終了、古いschema等をテストする。Room migrationとアップデートインストールを確認する。

終了条件: 元データと画像が往復で一致する。復元失敗時に既存データを失わない。APK更新後もデータが残る。

## P7. 操作性・回帰・APK納品

全画面を実際に操作し、未接続のボタンや仮データを除去する。Back、Predictive Back、フォント拡大、TalkBack、ライト／ダーク、横画面、低ストレージを確認する。

最低限のコマンド:

```bash
./gradlew --version
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest    # 対応端末・エミュレータがある場合
```

最後のコマンドを実行できない場合は、理由と残るUI/実機テストを記録する。JUnitが通っただけで実機通知やジェスチャーを合格としない。

正式利用用の署名付きAPKは安全に保管した同一鍵で作成する。秘密鍵、パスワード、local.properties等はGitへ含めない。署名鍵が必要な操作を環境に存在しない秘密情報で捏造しない。[S14]

## 推奨パッケージ構成

```text
app/src/main/java/jp/kenshopocket/app/
  MainActivity.kt
  ShareImportActivity.kt
  AppContainer.kt
  core/
    model/ time/ validation/ navigation/ ui/
  data/
    local/ dao/ entity/ repository/ media/ backup/
  domain/
    campaign/ entry/ reminder/ stats/ importer/
  platform/
    browser/ sharing/ notification/ receiver/ worker/ ocr/ network/
  feature/
    today/ list/ detail/ edit/ importreview/ wins/
    calendar/ history/ settings/ backup/ drafts/
```

クラス数を増やすことが目的ではない。依存方向を明確にし、UIからOSのアラーム処理やSQLを直呼びしない。

## CIの実装要件

push／pull request／手動実行に対応するビルドを用意する。通常権限はcontents:readを基本とする。依存キャッシュ、単体テスト、Lint、debug APKの生成、レポートとAPKのartifact化を行う。

CI用アクションのバージョンは作成時に公式情報で確認し、可能な範囲で安全に固定する。秘密情報をログへ出さない。信頼できないpull requestへ署名秘密情報を渡さない。一般ビルドと正式署名ビルドは分離する。

GitHub Actionsを作っただけでは実行済みとしない。生成したworkflowと実行ログの状態を区別する。

## 実装時に作る報告ファイル

`docs/IMPLEMENTATION_STATUS.md`: F01〜F15とP0〜P7の状態、該当コミット、未完了の内容。

`docs/BUILD_ENVIRONMENT.md`: バージョン固定表、環境、採用根拠、ビルド手順。

`docs/TEST_REPORT.md`: テストID、PASS/FAIL/NOT_RUN/BLOCKED、端末・OS・日時、証拠、再現手順。

`docs/RELEASE_NOTES.md`: 実装機能、既知の制限、データ移行、通知制限、導入手順。

最終報告で「全機能完成」と言えるのは、対象機能が実装され、必要な試験が実施され、その結果で判断できる場合だけ。未実施項目は未実施と記す。
