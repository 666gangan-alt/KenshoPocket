# GitHubリモートビルド

## 用途

ローカルWindowsサンドボックスのファイル実パス制限に依存せず、GitHub-hosted Ubuntu runnerで次を実行する。

1. JDK 17とGradle Wrapperの検証
2. `testDebugUnitTest`
3. `lintDebug`
4. `assembleDebug`
5. Debug APKと試験・LintレポートのArtifacts保存

このCIは実機通知、再起動、Doze、通知権限UI、Predictive Backを検証しない。

## リポジトリ構成

`KenshoPocket`フォルダを単独GitHubリポジトリのルートとして登録する。現在の親フォルダ全体を登録すると、無関係なアプリやファイルまで含まれるため行わない。

必要ファイル:

- `.github/workflows/android.yml`
- `gradlew` / `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle.kts` / `build.gradle.kts` / `app/`

`.tools`、`.build-cache`、`.build-user`、`.android-user`、署名鍵、生成済み`build`は登録しない。

## GitHubでの実行

pushまたはPull Requestで自動実行する。GitHub画面では `Actions` → `Android verification` → `Run workflow` でも手動実行できる。

成功時、workflow runの`Artifacts`から次を取得できる。

- `KenshoPocket-debug-...`: Debug APK。14日保持。
- `KenshoPocket-reports-...`: JUnitとLintの結果。成功・失敗にかかわらず存在する範囲を14日保持。

Debug APKはGitHub runnerが作る一時的なdebug keyで署名される。ローカルの既存0.1.0-dev APKと署名が異なる可能性があるため、端末で更新インストールできない場合は既存アプリをアンインストールしてから入れる。正式配布・継続更新用の署名には使用しない。

## 権限と秘密情報

workflowの`GITHUB_TOKEN`は`contents: read`のみ。デプロイ、Release作成、PR書込み、署名鍵、APIトークンは使用しない。外部からのPRでも秘密情報を渡さない。

Gradleキャッシュは公式Gradle Actionのbasic cacheを使う。古いpushの実行は同じbranch/refの新しい実行が始まると中止し、不要なActions時間を抑える。

## 初回登録

GitHub上で空のリポジトリを作成した後、通常のPowerShellからこのフォルダ内で実行する。`OWNER`とURLは実際のGitHubアカウント／リポジトリに置き換える。

```powershell
git init
git add .
git commit -m "Initial KenshoPocket Android project"
git branch -M main
git remote add origin https://github.com/OWNER/KenshoPocket.git
git push -u origin main
```

既存の親リポジトリの履歴へ混ぜず、単独リポジトリとして初期化する。GitHub Desktopを使う場合も、追加対象はこの`KenshoPocket`フォルダだけにする。

## 成功条件

- workflowの`Test, lint, and build APK`が緑になる。
- Artifactsに今回のrun番号・commit SHAを含むAPKとレポートがある。
- APK内のversionNameが`0.2.0-dev`である。
- 失敗時はレポートとログを確認し、タスクを除外して成功扱いにしない。
