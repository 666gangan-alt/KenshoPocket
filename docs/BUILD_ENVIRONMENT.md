# ビルド環境

確認日: 2026-09-23

| 項目 | 固定値 |
|---|---|
| JDK | 17.0.20.1 |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin / Compose compiler plugin | 2.3.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Compose BOM | 2025.04.01 |
| Navigation Compose | 2.8.9 |
| Room | 2.7.1 |
| AndroidX Browser | 1.8.0 |
| WorkManager / Tracing KTX | 2.11.2 / 1.3.0 |
| Robolectric / AndroidX Test Core | 4.16.1 / 1.6.1 |

同じワークスペースで検証済みのJDK・SDK・Gradle構成を再利用し、依存は動的バージョンにせず固定する。

`build.ps1`は既存のASCIIジャンクションが正しいワークスペースを指す場合のみ再利用する。ワークスペース外へのジャンクション自動作成は行わない。別名パスはアクセス権の問題を解消するものではない。

```powershell
.\build.ps1 -CheckOnly
.\build.ps1 -Tasks @('--version')
.\build.ps1 -Tasks @('testDebugUnitTest','lintDebug','assembleDebug')
```

## ビルド起動スクリプトの修正（2026-09-23）

- Javaの`user.home`をプロジェクトの`.build-user`、一時ファイルを`.build-user/tmp`に明示。誤って`C:\`をホームとして使わない。
- Gradleは`.build-cache`、Androidユーザー設定は`.android-user`を使用。呼出し元の環境変数と作業ディレクトリは成功／失敗のどちらでも復元する。
- Kotlinコンパイラはin-processを指定し、ワークスペース外のdaemonマーカーファイルへの書込みに依存しない。
- `KENSHO_DEBUG_KEYSTORE`未指定時は既存の`USERPROFILE/.android/debug.keystore`を参照する。既存APKがあるのに鍵が見つからない場合は停止し、別の署名鍵を黙って生成しない。
- `scripts/BuildPreflight.java`でJavaの実パス取得を検査する。アクセス拒否時はGradle開始前に`BUILD_ENVIRONMENT_BLOCKED`として停止する。kapt／javacや試験を除外して成功扱いにはしない。
- 既存のローカルMaven配置で実行する場合は`.\build.ps1 -Offline`。通常ビルドでは`KENSHO_MAVEN_REPO`を指定しない限り公式リポジトリを使用する。

### 残っている環境側の問題

実行ユーザーから対象JARは読める一方、親の`C:\Users\user`に対するフォルダ検索が拒否される。このためJavaの`toRealPath()`が失敗する。日本語を含まない`Documents`・`AppData`でも再現し、Javaホーム指定だけでは解消しない。

今回の修正後も事前検査でアクセス拒否を検出した。**全体ビルドの復旧・新APK生成は未完了**。ACL変更、サンドボックス無効化、管理者権限での実行は行っていない。

利用者側では、まずCodexを再起動して実行環境を再確認する。改善しない場合は、Windowsサンドボックスのセットアップ／フォルダアクセス設定について管理者・サポートに確認する。公式資料も、以前動作したサンドボックスの不調について再起動・セットアップの再実施・ログ確認を案内している。[公式Windowsサンドボックスのトラブルシューティング](https://learn.chatgpt.com/docs/windows/windows-sandbox#troubleshooting-and-faq)

環境を再確認した後の手順:

```powershell
.\build.ps1 -CheckOnly
# BUILD_PREFLIGHT_OKを確認してから全タスクを実行する
.\build.ps1 -Tasks @('testDebugUnitTest','lintDebug','assembleDebug')
```

既存の権限ポリシーを無効化したり、ユーザーフォルダ全体に広い権限を付与したりする手順は本プロジェクトでは提供しない。

## 通知実装の参照と環境制限

WorkManagerは[公式リリース情報](https://developer.android.com/jetpack/androidx/releases/work)でstableを確認して固定。[Tracingのリリース情報](https://developer.android.com/jetpack/androidx/releases/tracing)。AlarmManagerは[公式ガイド](https://developer.android.com/develop/background-work/services/alarms)、通知権限は[公式ガイド](https://developer.android.com/develop/ui/compose/notifications/notification-permission)を参照した。

2026-09-23のP3実装では、依存解決後にkapt／javacがJAR実パス確認で`AccessDeniedException`となった。**新APK・全Gradle試験・Lintは成功未確認**。`TEST_REPORT.md`参照。

診断用の任意環境変数（通常の環境では省略可能）:

- `KENSHO_MAVEN_REPO`: `scripts/prepare-local-maven.ps1`が作るローカルMavenディレクトリ。未指定ならGoogle/Maven Central。
- `KENSHO_DEBUG_KEYSTORE`: 既存Debug署名鍵の読取パス。鍵の複製・コミットはしない。
- `KENSHO_ROBOLECTRIC_JARS`: 既存instrumented Android JARのディレクトリ。指定時のみRobolectricをofflineにする。

ワークスペース内の`.build-cache`と`.build-user`はGit除外。DB schemaは2、`NOTIFICATION_MIGRATION`で1→2を移行する。schema JSONは1と2を保持。既存テーブルは削除しないが、移行の実行検証は未完了。
