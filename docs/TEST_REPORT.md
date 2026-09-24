# 試験報告

実行日: 2026-09-23 / 環境: Windows、JDK 17.0.20.1、Android SDK 36

## ビルド起動設定の修正後の確認

- PowerShell構文検査: PASS。
- `build.ps1 -CheckOnly`: Javaホームがプロジェクト内`.build-user`になったことを確認。プロジェクト・各キャッシュ・SDKで`AccessDeniedException`を検出し、Gradle開始前に停止した（事前検査は意図どおり動作、環境は依然BLOCKED）。
- 失敗後の7環境変数と作業ディレクトリの復元: PASS。
- `scripts/test-domain.ps1`: 再実行で`OK (22 tests)`。
- 新APK・全Gradle試験・Lint: 未完了のまま。親フォルダアクセス権をこのセッションで変更していない。対応は`BUILD_ENVIRONMENT.md`参照。

## GitHubリモート環境

- workflow必須ファイル、Gradle Wrapper JAR、build設定の存在確認: PASS。
- workflow静的検査（read-only token、45分timeout、全3 Gradle task、APK欠落時エラー、14日保持、`pull_request_target`／write権限なし）: PASS。
- GitHub Actions実行: NOT_RUN。GitHub CLI未導入、Git remote未設定、リポジトリ未作成のため。
- リモートでPASSするまでは、新APK・全テスト・Lint成功とは扱わない。

## 今回の結果（0.2.0-devソース）

| 対象 | 結果 | 証拠 |
|---|---|---|
| 独立JVMドメイン試験 | PASS | `scripts/test-domain.ps1`でJUnit `OK (22 tests)`。文章解析11、通知計算7、周期回帰4。今回のソースを新しい出力先にコンパイルして実行 |
| Kotlinアプリコード | 限定PASS | `compileDebugKotlin -x kaptDebugKotlin`成功。最終修正後も同タスク成功。Room生成処理を除外した診断であり、全体のビルド成功ではない |
| `testDebugUnitTest lintDebug assembleDebug` | BLOCKED | kaptでJDK ZipFileSystem終了時に`AccessDeniedException`。新APK未生成、全テストとLintも未完了 |
| kapt除外後の診断 | BLOCKED | javacでもSDK／依存JARの実パス取得が拒否され、生成Javaの型解決にも失敗。タスク除外によるAPKは生成・配布していない |
| 新規Room／OS統合試験 | NOT_RUN | Room統合15件（migration含む）、通知Gateway 2件、JSON fixture直接読込1件（12ケース）を追加したが未実行 |
| `connectedDebugAndroidTest` | NOT_RUN | ADBが利用するユーザ設定パスをこの実行環境で作成できず、接続端末も確認できなかった |

通常の検証コマンド（今回は未完了）:

```powershell
.\build.ps1 -Tasks @('testDebugUnitTest','lintDebug','assembleDebug')
```

実行環境では既存依存キャッシュを`scripts/prepare-local-maven.ps1`でワークスペース内にコピーし、`GRADLE_USER_HOME`を`.build-cache`、`KENSHO_MAVEN_REPO`を`.build-cache/maven`に指定して`--offline -Pkotlin.compiler.execution.strategy=in-process`でも試した。依存解決後もkapt／javacの実パス確認が拒否された。権限変更・昇格は行っていない。kapt除外は切り分け専用であり正式ビルドには使用しない。

今回PASSした独立試験:

```powershell
.\scripts\prepare-local-maven.ps1 -CachePath 'C:\Users\user\.gradle\caches\modules-2\files-2.1'
.\scripts\test-domain.ps1
```

周期回帰4件は、月曜開始の週境界、リセット前の通知抑制、夏時間の23時間日、同一開始終了時刻のおやすみ時間入力拒否。独立試験はAndroid／Room／OS連携の代替ではない。

未実行の統合試験は最も早い1件のみの予約、未確認候補の抑制、応募後停止、権限・チャンネル復帰、期限超過、投稿失敗再試行、スヌーズ上限、おやすみ延期保持、翌期間の再通知、設定OFF、周期確認、応募確認の冪等性、v1データ保持migrationを対象とする。合格とは扱わない。

## 既存APK（今回更新なし）

`app/build/outputs/apk/debug/app-debug.apk`は存在するが、`output-metadata.json`上は**versionCode 1 / 0.1.0-dev**、17,775,021 bytes。今回の通知実装を含むAPKとして案内しない。

前回記録では21件のGradle試験・Lint・APK生成がPASSだったが、今回の変更に対する証拠として流用しない。

## 未実施

OS通知の実配信、実機共有、Predictive Back完了/キャンセル、ブラウザ往復、プロセス再生成、通知権限拒否、画像/OCR、バックアップ復元、200%フォント、TalkBack、横画面は未実施。
