package jp.kenshopocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import jp.kenshopocket.app.feature.notification.NotificationScreen
import jp.kenshopocket.app.feature.notification.NotificationViewModel
import androidx.navigation.navArgument
import jp.kenshopocket.app.data.CampaignCard
import kotlinx.coroutines.launch

private const val TODAY = "today"
private const val LIST = "list"
private const val WINS = "wins"
private const val MORE = "more"

@Composable
fun KenshoPocketApp(viewModel: MainViewModel, notifications: NotificationViewModel, openPage: suspend (String, String) -> Unit) {
    val nav = rememberNavController()
    val cards by viewModel.cards.collectAsState()
    val confirmation by viewModel.confirmation.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val current by nav.currentBackStackEntryAsState()
    val notification by viewModel.notificationRequest.collectAsState()
    val notificationScope = rememberCoroutineScope()
    LaunchedEffect(notification, current?.destination?.route) {
        // Keep an active editor and its input intact; handle the notification after leaving it.
        val route = current?.destination?.route
        if (notification != null && route != null && route !in setOf("edit", MORE, "notifications/{id}")) {
            viewModel.consumeNotification()?.let { (id, action) ->
                // Clearing the consumed key changes this effect's key; finish navigation in the UI scope.
                notificationScope.launch {
                    nav.navigate("detail/$id") { launchSingleTop = true }
                    if (action == "OPEN") openPage(id, "detail/$id")
                }
            }
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() } }

    MaterialTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    listOf(TODAY to Icons.Default.Home, LIST to Icons.Default.List, WINS to Icons.Default.CardGiftcard, MORE to Icons.Default.MoreHoriz).forEach { (route, icon) ->
                        NavigationBarItem(
                            selected = current?.destination?.route == route,
                            onClick = { nav.navigate(route) { popUpTo(TODAY) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(when(route) { TODAY -> "今日"; LIST -> "一覧"; WINS -> "当選"; else -> "その他" }) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(navController = nav, startDestination = TODAY, modifier = Modifier.padding(padding)) {
                composable(TODAY) { CampaignList(cards, true, { nav.navigate("edit") }, { nav.navigate("detail/$it") }, openPage) }
                composable(LIST) { CampaignList(cards, false, { nav.navigate("edit") }, { nav.navigate("detail/$it") }, openPage) }
                composable(WINS) { Placeholder("当選・受取管理", "当選記録は次の実装工程で追加します。") }
                composable(MORE) { NotificationScreen(notifications) }
                composable("notifications/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    NotificationScreen(notifications, entry.arguments?.getString("id")) { nav.popBackStack() }
                }
                composable("edit") { EditScreen(onBack = { nav.popBackStack() }, onSave = { title, url, deadline -> viewModel.addCampaign(title, url, deadline) { nav.popBackStack() } }) }
                composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    val card = cards.firstOrNull { it.campaign.id == id }
                    DetailScreen(card, { nav.popBackStack() }, { nav.navigate("notifications/$id") }, { openPage(id, "detail/$id") })
                }
            }
        }
        confirmation?.let {
            AlertDialog(
                onDismissRequest = { viewModel.resolveConfirmation("LATER") },
                title = { Text(stringResource(R.string.confirm_applied)) },
                text = { Text("ページを開いただけでは応募済みになりません。") },
                confirmButton = { Button(onClick = { viewModel.resolveConfirmation("APPLIED") }) { Text(stringResource(R.string.applied)) } },
                dismissButton = { Row { TextButton(onClick = { viewModel.resolveConfirmation("NOT_APPLIED") }) { Text(stringResource(R.string.not_applied)) }; TextButton(onClick = { viewModel.resolveConfirmation("LATER") }) { Text(stringResource(R.string.review_later)) } } },
            )
        }
    }
}

@Composable
private fun CampaignList(cards: List<CampaignCard>, today: Boolean, add: () -> Unit, detail: (String) -> Unit, openPage: suspend (String, String) -> Unit) {
    val state = rememberLazyListState()
    Scaffold(floatingActionButton = { FloatingActionButton(onClick = add) { Icon(Icons.Default.Add, stringResource(R.string.add_campaign)) } }) { padding ->
        if (cards.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (today) "今日の対象はありません" else stringResource(R.string.no_campaigns)); Spacer(Modifier.height(16.dp)); Button(onClick = add) { Text(stringResource(R.string.add_campaign)) } } }
        else LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(padding)) {
            items(cards, key = { it.campaign.id }) { card -> CampaignCardView(card, { detail(card.campaign.id) }, { openPage(card.campaign.id, if (today) TODAY else LIST) }) }
        }
    }
}

@Composable
private fun CampaignCardView(card: CampaignCard, detail: () -> Unit, open: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth().clickable(onClick = detail)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.campaign.title, style = MaterialTheme.typography.titleMedium)
            Text(if (!card.campaign.deadlineConfirmed && card.campaign.deadlineDate != null) stringResource(R.string.reminder_date_candidate, card.campaign.deadlineDate) else card.campaign.deadlineDate?.let { "$it 締切" } ?: "締切未設定・要確認")
            Text(when { card.pendingConfirmation -> "応募完了の確認待ち"; card.entryCount > 0 -> "応募済み"; else -> "未応募" })
            when {
                card.url == null -> TextButton(onClick = detail) { Text(stringResource(R.string.url_missing)) }
                card.url.reviewRequired -> TextButton(onClick = detail) { Text(stringResource(R.string.url_review_required)) }
                else -> Button(onClick = { scope.launch { open() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.open_application)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(onBack: () -> Unit, onSave: (String, String, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var deadline by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.add_campaign)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }) }, bottomBar = { Button(onClick = { if (title.isBlank()) error = "懸賞名を入力してください" else onSave(title, url, deadline.ifBlank { null }) }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) { Text(stringResource(R.string.save)) } }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(title, { title = it; error = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.campaign_title)) }, isError = error != null, supportingText = { error?.let { Text(it) } })
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.application_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(deadline, { deadline = it }, Modifier.fillMaxWidth(), label = { Text("締切日 YYYY-MM-DD（任意）") })
            Text("入力内容は保存を押すまで本体データを変更しません。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(card: CampaignCard?, back: () -> Unit, notifications: () -> Unit, open: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text(card?.campaign?.title ?: "詳細") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (card == null) Text("この項目は削除されています") else {
                Text(card.campaign.deadlineDate ?: "締切未設定・要確認")
                if (!card.campaign.deadlineConfirmed) Text(stringResource(R.string.reminder_unconfirmed))
                OutlinedNotificationButton(notifications)
                if (card.url != null) Button(onClick = { scope.launch { open() } }, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.open_application)) }
                Text("応募履歴: ${card.entryCount}件")
            }
        }
    }
}

@Composable private fun OutlinedNotificationButton(click: () -> Unit) = TextButton(onClick = click) { Text(stringResource(R.string.reminder_title)) }

@Composable private fun Placeholder(title: String, detail: String) = Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(detail) }
