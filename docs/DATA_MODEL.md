# データモデル・状態遷移・処理契約

対象: 懸賞Pocket v1.0

この文書は論理スキーマである。実装時にRoom Entity、DAO、Migration、型変換、exported schemaと対応付ける。記載の型・制約をUIだけの検査で代用しない。

## 1. 型と共通規則

IDはUUID文字列。DBのInstantはUTCのepoch millisecondsをLongで保存し、バックアップJSONではISO 8601のUTC表現を使う。LocalDateは `YYYY-MM-DD`、LocalTimeは `HH:mm`、ZoneIdはIANAの文字列。

日時計算はClockを注入してテスト可能にする。業務ロジック内で `System.currentTimeMillis()` や `LocalDate.now()` を直呼びしない。初期ZoneIdは `Asia/Tokyo`。端末のタイムゾーンが変わってもキャンペーンの基準地域を無断で変えない。

列挙型は名前または固定コードで保存し、ordinalで保存しない。ユーザー入力の文字列はtrimと長さ検証を行うが、URLなど意味が変わる値は無差別に正規化しない。

金額は整数の円。小数・Floatで管理しない。WinRecord.estimatedValueYenは数量を含むその当選記録の合計評価額であり、単価ではない。quantityは1以上の整数。未評価はnullで、0円とは違う。名前200文字、メモ10,000文字、1URL8,192文字、1回の原文200,000文字、取込候補100件をv1の設計上限とする。超過は明示エラーとし、切り捨てない。

## 2. DeadlineValue

```text
precision: UNKNOWN | DATE_ONLY | DATE_TIME
localDate: LocalDate?
localTime: LocalTime?
zoneId: String                 // default Asia/Tokyo
confirmed: Boolean
sourceKind: MANUAL | SHARED_TEXT | OCR | PAGE_METADATA | BACKUP
rawText: String?
inferredYear: Boolean
confirmedAt: Instant?
selectedUtcOffsetSeconds: Int?  // DSTの重複時刻を確定した場合
```

不変条件:

- UNKNOWNではdate/timeともnull。
- DATE_ONLYではdate必須、timeはnull。
- DATE_TIMEではdate/time両方必須。
- confirmedはユーザーの確認操作でのみtrueにできる。Web取得成功だけでtrueにしない。
- inferredYearは確認後も抽出由来の記録として残してよい。
- 期限候補を確定せず保存する場合はconfirmed=falseのまま残すかUNKNOWNにする。どちらも締切通知は生成しない。

`managementCutoff()`:

```text
UNKNOWN                  -> null
未確認                   -> null
DATE_ONLY                -> localDate.plusDays(1).atStartOfDay(zoneId).toInstant()
DATE_TIME                -> 指定localDateTimeをzoneIdで解釈したInstant
```

DATE_ONLYの境界は内部整理用であり、UIに公式の23:59締切として表示しない。DATE_TIMEが分精度ならその分の開始を保守的な停止基準とする。現在時刻が境界以上なら応募促進通知は停止するが、リンク確認と履歴の後付けは許可する。

DSTの存在するZoneIdもテストする。存在しないローカル時刻は初めの有効時刻へ繰り下げた候補として警告し、重複時刻は選んだoffsetを保持する。日本時間以外の曖昧時刻を勝手に確定しない。

## 3. エンティティ

### 3.1 Campaign

| フィールド | 型／意味 |
|---|---|
| id | UUID PK |
| title | String 必須 |
| organizer | String? 主催者 |
| prizeSummary | String? 賞品概要 |
| conditions | String? 応募条件 |
| campaignKind | LOTTERY / POINTS / GIVEAWAY / OTHER |
| primaryUrlId | UUID? 自分のCampaignUrlに限る |
| deadline | DeadlineValueの埋込列 |
| startsAt | Instant? 開始が確認できた場合のみ |
| currentEntryRuleId | UUID |
| isFavorite | Boolean |
| lifecycle | ACTIVE / ARCHIVED / TRASH |
| sourceSessionId | UUID? |
| verificationStatus | UNCHECKED / USER_REVIEWED / NEEDS_REVIEW |
| verificationNote | String? |
| note | String? |
| createdAt / updatedAt | Instant |
| revision | Long 楽観的競合検出 |
| trashedAt | Instant? |

主催者の正当性をverificationStatusだけで保証しない。USER_REVIEWEDは利用者が確認したという履歴である。

「終了済み」はlifecycleと別の派生値。締切超過でARCHIVEDへ自動変更したり、応募結果を変更したりしない。通常の一覧フィルターでは管理上の終了を除外できる。

### 3.2 CampaignUrl

id、campaignId(FK)、label、role(APPLY / OFFICIAL / RESULT / REDEEM / OTHER)、originalUrl、launchUrl、dedupeKey、resolvedUrl?、resolvedAt?、fetchStatus、reviewRequired、reviewedAt?、sourceKind、createdAt、sortOrderを持つ。

インデックス: campaignId、dedupeKey。URLの使い回しがあり得るため、dedupeKeyを全キャンペーンでuniqueにはしない。

originalUrlは原文上の表現、launchUrlは確認済みの起動用URL。resolvedUrlは情報であって、勝手にlaunchUrlを置換しない。複数のCampaignUrlが同一キャンペーンに属せる。

### 3.3 EntryRule

id、campaignId、revision、mode(ONCE / DAILY / WEEKLY / MANUAL)、zoneId、resetLocalTime、weekStartsOn、confirmed、createdAtを持つ。変更時は新しいルールを作り、過去の応募記録が参照するルールを上書きしない。

DAILY/WEEKLYのv1は1期間1記録。特殊条件・複数口数はMANUALにする。抽出がDAILYと推定しても、confirmed=falseの間は周期通知を作らない。

### 3.4 EntryRecord

| フィールド | 型／意味 |
|---|---|
| id | UUID PK |
| campaignId | UUID FK |
| entryRuleId | UUID FK、応募時のルール |
| appliedAt | Instant、ユーザー確認した応募時刻 |
| periodStart / periodEnd | Instant?、応募時の期間のスナップショット |
| periodKey | String? |
| activeDedupeKey | String? UNIQUE |
| clientMutationId | UUID UNIQUE、同じ保存操作の重複防止 |
| launchSessionId | UUID? |
| result | PENDING / WIN / LOSE / UNKNOWN |
| resultUpdatedAt | Instant? |
| campaignTitleSnapshot | String |
| urlSnapshot | String? |
| note | String? |
| createdAt / updatedAt | Instant |
| voidedAt | Instant? |

ONCEのperiodKeyは固定の`ONCE`、periodStartとperiodEndはnull。MANUALのperiodKeyはnull。DAILY/WEEKLYは期間開始を一意に識別するキーを使う。

activeDedupeKeyは限定周期の有効な記録について `campaignId:entryRuleId:periodKey`。MANUALとvoid済みはnull。SQLiteのuniqueとDAOのトランザクションを併用する。

周期変更前の記録があるため、activeDedupeKeyだけで応募可否を決めない。新規登録時には、現在のルールが定義する期間内の有効EntryRecordを、過去のruleIdも含めて検索する。ONCEでは全期間を検索する。既存記録があれば上書きや二重追加ではなく、既存記録の確認・訂正導線を出す。

取り消す場合はvoidedAtを付け、activeDedupeKeyをnullにする。WINに賞品が関連付いている場合、取り消しで当選記録を消さず、関連の解除・日時訂正等を明示的に処理する。

### 3.5 LaunchSession

id、campaignId、urlSnapshot、launchedAt、ruleIdSnapshot、periodKeySnapshot、originRoute、originAnchor?、state、observedBackgroundAt?、confirmationShownAt?、confirmedEntryId?、updatedAtを持つ。

state:

```text
PREPARED → LAUNCHED → NEEDS_CONFIRMATION → CONFIRMED
                   ├→ NOT_APPLIED
                   └→ REVIEW_LATER
PREPARED／LAUNCHED → FAILED
```

FAILEDは応募済みにならない。REVIEW_LATERはモーダルを再表示せずカードへ残す。CONFIRMEDへの同じ操作を再実行してもEntryRecordは増えない。

プロセス再生成後に単純なonResumeだけで再表示せず、記録されたセッションと実際の起動成功状態から判断する。複数の確認待ちはリスト化し、同時に複数のシートを開かない。

### 3.6 WinRecord

id、campaignId?、entryRecordId?、campaignTitleSnapshot?、prizeName、wonOn(LocalDate)、recordedAt、prizeType、quantity、estimatedValueYen?、pointsProgram?、pointsQuantity?、notificationMethod?、receiveStatus、claimDeadline?、receiveDeadline?、claimedAt?、shippedAt?、receivedAt?、note?、createdAt、updatedAt、trashedAt?を持つ。

prizeType: ITEM / CASH / GIFT_CODE / POINTS / OTHER。

receiveStatus: UNCLAIMED / CLAIMED / SHIPPED / RECEIVED / EXPIRED / DECLINED。

entryRecordIdがある場合は、指定Campaignに属するか検査する。EntryRecordとの関連がない過去当選も保存できる。1つのEntryRecordに複数WinRecordを紐付け可能とする。

当選登録が関連EntryRecordをWINに更新する操作は同一トランザクションで行う。最後の賞品記録を削除した場合、応募結果を自動でLOSEへ変更しない。「当選結果は残す／結果を未確認に戻す」をユーザーが選ぶ。

### 3.7 MediaFileとAttachmentLink

MediaFile: id、relativePath、mimeType、byteSize、width?、height?、sha256、createdAt。データ本体は内部ストレージ。外部のcontent URIを永続的な本体参照としない。

AttachmentLink: id、mediaFileId(FK)、campaignId?、winId?、draftId?、importSessionId?、caption?、sortOrder。所有先は1つだけとする。同一MediaFileに複数Linkを許可し、一括取込の元画像を重複保存しない。

ファイルは原則immutableにする。差替えは新MediaFileを作り、DB参照を更新する。参照数が0になった古いファイルは安全な遅延清掃対象とし、バックアップや復元処理中は削除しない。

### 3.8 ImportSession / Draft

ImportSession: id、sourceKind、sourcePackage?、receivedAt、rawText?、payloadHash、status、createdAt、updatedAt。statusはRECEIVED / PARSING / REVIEW / COMMITTED / CANCELLED / FAILED。

Draft: id、draftKind(CAMPAIGN_EDIT / CAMPAIGN_NEW / IMPORT_REVIEW / WIN_EDIT)、targetId?、sourceRevision?、payloadSchemaVersion、payloadJson、lastSavedRevision、updatedAt。

payloadJsonには候補一覧、各候補のユーザー修正、選択状態、警告、確認状態、元の抽出位置を含める。UIの再生成で取込をやり直して修正内容を消さない。

同一payloadHashの共有が再度来た場合は既存候補を提案するが、無条件に無視しない。通信サイトで同じ文面を使う別企画があり得るため、ユーザーが別登録を選べる。

### 3.9 Tag / CampaignTag

Tag: id、name、normalizedName UNIQUE、createdAt。CampaignTag: campaignId＋tagIdの複合PK。

タグ名はユーザー定義可。削除時にCampaign本体は消さず関連だけ外す。タグ一覧から該当懸賞へ絞り込める。

### 3.10 ReminderRule

id、campaignId?、winId?、purpose、triggerType、dayOffset?、localTime?、minutesBefore?、zoneId、enabled、precisionPreference、ruleRevision、createdAt、updatedAtを持つ。

purpose: APPLICATION / REPEAT_ENTRY / CLAIM / RECEIVE / REVIEW。

triggerType: CALENDAR_DAY_OFFSET / MINUTES_BEFORE / PERIOD_START_TIME / EXPLICIT_DATETIME。

CALENDAR_DAY_OFFSETの-1は「その地域の前日の指定時刻」であり、固定24時間前ではない。MINUTES_BEFOREはInstantからの経過分数として計算する。

### 3.11 ReminderOccurrence

id、logicalKey UNIQUE、targetType、targetId、ruleId、plannedAt、effectiveAt、validUntil?、periodKey?、state、osScheduledAt?、postedAt?、attemptCount、skipReason?、updatedAtを持つ。

state: PENDING / SCHEDULED / POSTING / POSTED / BLOCKED_PERMISSION / BLOCKED_CHANNEL / SKIPPED / CANCELLED。

stateはOS実際の配信と完全同期しているとは仮定しない。POSTEDは通知APIの呼出しが成功したという意味で、画面表示や閲覧を保証しない。古いPOSTINGは安定tagで再実行可能にする。

### 3.12 NotificationEvent

id、occurrenceId?、eventType、occurredAt、safeReasonCode、scheduleModeを持つ。機密を含む通知本文やURL全文は保存しない。通常保持90日とし、診断画面から削除できる。

### 3.13 AppSetting / RestoreJournal

AppSetting: key PK、typedValueJson、schemaVersion、updatedAt。テーマ、通知初期値、おやすみ時間、ブラウザ設定、バックアップ履歴等を管理する。業務設定はDBトランザクション内で復元可能にする。

RestoreJournal: id、stageDirectory、preparedFileIds、phase(PREPARING / READY / COMMITTED / CLEANED / FAILED)、createdAt、updatedAt。再起動後に安全に清掃・復旧するための内部データ。バックアップ対象外。

## 4. 主要なインデックス・制約

- Campaign: lifecycle、deadlineDate、updatedAt、isFavorite。
- CampaignUrl: campaignId、dedupeKey。
- EntryRecord: campaignId＋appliedAt、result＋appliedAt、clientMutationId UNIQUE、activeDedupeKey UNIQUE。
- WinRecord: wonOn、receiveStatus、entryRecordId、claimDeadlineDate、receiveDeadlineDate。
- ReminderOccurrence: logicalKey UNIQUE、state＋effectiveAt。
- Draft: draftKind＋targetId、updatedAt。
- ImportSession: payloadHash、status＋updatedAt。

CampaignとEntryRule／primaryUrlの循環参照は、遅延評価する外部キー等を使用して同一トランザクション内で作成できるようにする。参照が完成する前の中間状態をUIや通知処理に公開しない。

全ての外部キーに適切なindexを置く。Cascadeは仕様が明確な補助関連に限定する。当選や応募履歴を意図せず連鎖削除しない。

## 5. 状態を混同しない

別々に管理する状態:

```text
Campaign表示状態: ACTIVE / ARCHIVED / TRASH
開催の管理状態: UPCOMING / OPEN_BY_RECORDED_DATE / ENDED_BY_RECORDED_DATE / UNKNOWN
応募状況: 未応募 / 今期応募済み / 確認待ち
応募結果: PENDING / WIN / LOSE / UNKNOWN
賞品の状態: UNCLAIMED ... RECEIVED / EXPIRED / DECLINED
```

OPEN_BY_RECORDED_DATEは登録内容から見た期間内という意味で、公式の開催中確認を代替しない。

締切超過は応募結果に影響しない。賞品の期限切れは当選結果に影響しない。当選後も毎日応募するかは公式の条件と利用者の設定に従い、当選だけで自動的に全周期を停止しない。

Campaignをアーカイブ／ゴミ箱に移すと応募促進通知は停止するが、既存WinRecordとその手続／受取通知は残す。応募履歴の集計も、EntryRecordが有効な間は保持する。

Campaignの完全削除では、関連応募履歴が削除されることを明示確認する。関連WinRecordは単独記録へ変換して保持することを初期動作とし、campaignIdとentryRecordIdを外して名称のスナップショットを残す。賞品自体の完全削除は当選側の別操作で行う。

## 6. 周期の計算

DAILYは対象ZoneIdで直近のresetLocalTimeをperiodStart、次のローカル日のresetLocalTimeをperiodEndとする。WEEKLYは指定曜日／時刻の直近境界を始点とする。区間は `[start, end)`。

日本時間00:00境界の例:

```text
2026-09-23 23:59:59 JST → 9/23の期間
2026-09-24 00:00:00 JST → 9/24の期間
```

7時境界の例:

```text
2026-09-24 06:30 JST → 9/23 07:00〜9/24 07:00
```

periodKeyは人間向けラベルではなく、一意な開始Instant等から構成する。ルール変更で過去の応募を再分類して書き換えない。ただし現在期間の応募可否は、現在ルールが定める時間区間内の過去応募を検索して求める。

## 7. トランザクション契約

### 7.1 CommitImport

選択候補の全検証→必要画像の内部保存→1つのRoomトランザクションでCampaign/URL/Tag/Rule等を登録→ImportSessionをCOMMITTED→通知再整合。

同じImportSessionの保存を再実行して二重登録しない。ユーザーが確認していない候補を勝手に含めない。1件の不正データで部分登録した場合に件数が合わなくならないよう、v1は選択分のall-or-nothingを採用する。

### 7.2 ConfirmApplication

```text
clientMutationIdを取得
Room transaction:
  同じmutationがあれば既存結果を返す
  Campaign/Ruleを読み、入力appliedAtとルールを検証
  現在ルールの対象期間の有効応募を検索
  限定周期に既存応募があれば既存記録を返す
  EntryRecordをinsert
  対応LaunchSessionをCONFIRMEDへ
  当該期間の未配信応募通知をCANCELLEDへ
transaction成功後:
  OSの既存通知を取り消す／次回予定を再整合
```

DB成功後にOS処理が失敗しても、次回起動・ワーカーで補正する。OS操作失敗を理由にDBへもう1件の応募を追加しない。

### 7.3 AddWin / MarkReceived

AddWinはWinRecord保存と関連EntryRecordのWIN更新を同一トランザクションにする。MarkReceivedはreceiveStatusとreceivedAt、未配信の賞品通知停止を同一トランザクションにする。

### 7.4 RestoreBackup

検証・新規画像配置・復元ジャーナル記録の後、論理データ置換をトランザクションで行う。DB commit前に既存画像を削除しない。commit後の古い画像削除やOS予定の再設定は再試行可能にする。

## 8. 層とインターフェース

実装クラス名は変更可能だが、以下の責務を混在させない。

```text
CampaignRepository
EntryRepository
WinRepository
DraftRepository
ImportParser                   // 純粋な候補生成、通信しない
DateCandidateParser            // ClockとZoneIdを注入
UrlCandidateParser
PageMetadataFetcher            // 任意の明示通信
OnDeviceTextRecognizer         // 画像→テキスト
PeriodCalculator               // 純粋計算
EligibilityEvaluator           // 記録上の未応募判定
ReminderPlanner                // 入力Snapshot→Occurrence候補
ReminderScheduler              // OS APIとの接続
NotificationDispatcher         // 権限再確認・投稿
ExternalPageLauncher
BackupExporter / BackupRestorer
```

UIはDAOを直接呼ばずViewModel/UseCaseを経由する。ReminderPlanner、DateCandidateParser、PeriodCalculator、StatsCalculatorはAndroid OSに依存しないロジックとして単体試験できるようにする。

## 9. バックアップのバージョン

RoomのDBバージョンとバックアップschemaVersionは別管理。アプリ更新時に旧バックアップを読み込める移行処理を用意する。未知の将来バージョンを読み込んでデータを失わないよう、対応外なら原本を残して拒否する。

チェックサムは破損検出に用いるもので、バックアップの作成者が信頼できる証明ではない。チェックサムが正しくても型・パス・容量・外部キー等の検査を省略しない。
