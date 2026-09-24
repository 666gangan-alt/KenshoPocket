# KenshoPocket GitHub公開 引き継ぎ書

## 目的

`KenshoPocket` Androidプロジェクトを、GitHubアカウント `666gangan-alt` の公開リポジトリとして公開し、GitHub Actionsによるリモートビルドを完了させる。

## 対象

- ローカルプロジェクト: `C:\Users\user\Documents\ChatGPT\システム開発\KenshoPocket`
- GitHub所有者: `666gangan-alt`
- リポジトリ名: `KenshoPocket`
- 公開範囲: Public
- 予定URL: `https://github.com/666gangan-alt/KenshoPocket`
- ブランチ: `main`

## 完了済み

- `KenshoPocket` フォルダを親リポジトリから分離し、独立Gitリポジトリとして初期化済み。
- 初回コミット作成済み。
  - commit: `a257134 Initial KenshoPocket Android project`
- GitHub Actionsワークフロー作成済み。
  - `.github/workflows/android.yml`
  - 実行内容: `testDebugUnitTest lintDebug assembleDebug`
  - APKとレポートをArtifactとして14日保持
  - 権限は `contents: read`
- `.gitignore` にビルド生成物、署名鍵、ローカル設定、環境変数、一時ログの除外を設定済み。
- 公開前の簡易秘密情報検索を実施済み。実トークンや秘密鍵は検出されていない。
- Kotlin一時エラーログにローカルユーザーパスが含まれていたため、初回コミットから除去済み。
- ワークツリーはコミット時点でclean。

## 現在のブロッカー

元タスクではCodex内蔵ブラウザが `github.com` に対して「保存済みの拒否設定」と判定し、UI操作ができなかった。ユーザーはブラウザ権限を許可してアプリ再起動も行ったが、元タスクのセッションでは拒否状態が残った。

新しいタスクでは、最初に内蔵ブラウザからGitHubへアクセスできるか確認する。アクセスできない場合、回避操作はせず、ユーザーにGitHub固有の拒否ルール削除または手動リポジトリ作成を依頼する。

## 残作業

1. GitHubへサインイン済みであることを確認する。認証画面はユーザー本人に操作してもらう。
2. `666gangan-alt/KenshoPocket` が既に存在するか確認する。
3. 存在しない場合、次の設定で空リポジトリを作成する。
   - Repository name: `KenshoPocket`
   - Visibility: Public
   - README: 追加しない
   - `.gitignore`: 追加しない
   - License: 追加しない
4. 「Create repository」の最終クリック直前で、公開リポジトリ作成についてユーザーへ確認する。
5. ローカルリポジトリへremoteを設定する。
   - `origin = https://github.com/666gangan-alt/KenshoPocket.git`
6. `main` をpushする。認証が必要な場合はユーザーへ引き継ぐ。トークンやパスワードをチャットへ入力させない。
7. GitHub Actionsの実行結果を確認する。
8. 失敗時はログを調査し、プロジェクトまたはワークフローを修正して再pushする。
9. 成功時はActions Artifact内のdebug APKを確認し、公開URLとビルド結果を報告する。

## 注意事項

- 親フォルダ `C:\Users\user\Documents\ChatGPT\システム開発` にもGitリポジトリがある。必ず `KenshoPocket` を作業ディレクトリとして扱い、親や兄弟プロジェクトをcommitしない。
- `KenshoPocket` 内には独立した `.git` が存在する。
- 公開前に `git status --short` と `git remote -v` を再確認する。
- `.jks`、`.keystore`、`local.properties`、`.env*`、`.kotlin/` をpushしない。
- リポジトリ作成や公開投稿など外部へ影響する最終操作は、実行直前の確認を行う。
- 認証、CAPTCHA、二要素認証はユーザー本人に操作してもらう。

## 推奨確認コマンド

```powershell
Set-Location 'C:\Users\user\Documents\ChatGPT\システム開発\KenshoPocket'
git status --short
git log -1 --oneline
git remote -v
git ls-files | rg '(^|/)\.kotlin/|\.jks$|\.keystore$|local\.properties$|^\.env'
```

期待値:

- `git status --short`: 出力なし（この引き継ぎ書をcommitした後）
- 最新commit: 引き継ぎ書追加コミット
- remote: 公開リポジトリ作成前は未設定
- 除外対象ファイル検索: 出力なし

