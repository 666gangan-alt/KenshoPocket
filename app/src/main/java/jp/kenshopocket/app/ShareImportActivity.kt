package jp.kenshopocket.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.time.ZoneId
import java.util.UUID
import jp.kenshopocket.app.domain.importer.ImportCandidate
import jp.kenshopocket.app.domain.importer.ImportParser
import jp.kenshopocket.app.domain.importer.SourceKind
import kotlinx.coroutines.launch

class ShareImportActivity : ComponentActivity() {
    private var sharedText by mutableStateOf("")
    private var candidates by mutableStateOf<List<ReviewCandidate>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private lateinit var draftId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        draftId = savedInstanceState?.getString("draftId") ?: UUID.randomUUID().toString()
        sharedText = savedInstanceState?.getString("sharedText")
            ?: if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(200_000) else ""
        setContent { MaterialTheme { ImportContent() } }
        if (savedInstanceState == null && intent.action != Intent.ACTION_SEND && sharedText.isBlank()) {
            lifecycleScope.launch {
                val draft = (application as KenshoPocketApplication).repository.latestDraft()
                if (draft != null && sharedText.isBlank()) {
                    draftId = draft.id
                    sharedText = draft.payloadJson.take(200_000)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draftId", draftId)
        outState.putString("sharedText", sharedText)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (!isFinishing && sharedText.isNotBlank()) saveDraft()
        super.onStop()
    }

    @Composable
    private fun ImportContent() {
        Scaffold(bottomBar = {
            if (candidates.isNotEmpty()) Button(
                enabled = !saving && candidates.any { it.selected }, onClick = ::commitSelected,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            ) { Text(if (saving) "保存中…" else "選択した候補を保存して戻る") }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("共有内容を確認", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text("抽出結果は候補です。確認するまで締切通知は作られません。")
                    OutlinedTextField(sharedText, { sharedText = it.take(200_000) }, Modifier.fillMaxWidth().height(220.dp), label = { Text("原文") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::parseText, enabled = sharedText.isNotBlank()) { Text("候補を作る") }
                        TextButton(onClick = { saveDraft(); finish() }) { Text("下書きに保存して戻る") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                itemsIndexed(candidates, key = { _, item -> item.key }) { index, item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row { Checkbox(item.selected, { update(index) { copy(selected = it) } }); Text("候補 ${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                            OutlinedTextField(item.value.title, { title -> update(index) { copy(value = value.copy(title = title)) } }, Modifier.fillMaxWidth(), label = { Text("懸賞名") })
                            Text("応募URL: ${item.value.launchUrlCandidate ?: "未設定"}")
                            Text("締切候補: ${item.value.dateCandidate ?: "未設定"}${item.value.timeCandidate?.let { " $it" }.orEmpty()}")
                            if (item.value.dateCandidate != null) Row { Checkbox(item.deadlineConfirmed, { update(index) { copy(deadlineConfirmed = it) } }); Text("この締切候補を確認した", modifier = Modifier.padding(top = 12.dp)) }
                            if (item.value.warnings.isNotEmpty()) Text("要確認: ${item.value.warnings.joinToString()}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    private fun update(index: Int, block: ReviewCandidate.() -> ReviewCandidate) {
        candidates = candidates.toMutableList().also { it[index] = it[index].block() }
    }

    private fun parseText() {
        runCatching { ImportParser().parse(sharedText, SourceKind.SHARED_TEXT, ZoneId.of("Asia/Tokyo")) }
            .onSuccess { parsed ->
                candidates = parsed.map { ReviewCandidate(UUID.randomUUID().toString(), it) }
                error = if (parsed.isEmpty()) "懸賞候補を見つけられませんでした。原文を編集してください。" else null
                saveDraft()
            }.onFailure { error = it.message }
    }

    private fun commitSelected() {
        val selected = candidates.filter { it.selected }
        if (selected.any { it.value.title.isBlank() }) { error = "懸賞名が空の候補があります"; return }
        saving = true
        lifecycleScope.launch {
            val repository = (application as KenshoPocketApplication).repository
            runCatching {
                repository.commitImport(selected.map { it.value }, selected.mapIndexedNotNull { index, value -> index.takeIf { value.deadlineConfirmed } }.toSet())
                repository.deleteDraft(draftId)
            }.onSuccess { finish() }.onFailure { error = it.message; saving = false }
        }
    }

    private fun saveDraft() = lifecycleScope.launch {
        runCatching { (application as KenshoPocketApplication).repository.saveDraft(draftId, sharedText) }
            .onFailure { error = "下書きを保存できません: ${it.message}" }
    }
}

private data class ReviewCandidate(val key: String, val value: ImportCandidate, val selected: Boolean = true, val deadlineConfirmed: Boolean = false)
