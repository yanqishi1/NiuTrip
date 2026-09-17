package com.niutrip.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.niutrip.app.AppContainer
import com.niutrip.app.service.TrackRecordingService
import com.niutrip.app.ui.auth.LoginScreen
import com.niutrip.app.ui.auth.LoginViewModel
import com.niutrip.app.ui.common.ViewModelFactory
import com.niutrip.app.ui.create.*
import com.niutrip.app.ui.checkin.CheckinScreen
import com.niutrip.app.ui.checkin.CheckinViewModel
import com.niutrip.app.ui.checkin.ImageCompressor
import com.niutrip.app.ui.detail.TrackDetailScreen
import com.niutrip.app.ui.detail.TrackDetailViewModel
import com.niutrip.app.ui.deeplink.IncomingShareDecision
import com.niutrip.app.ui.deeplink.IncomingShareViewModel
import com.niutrip.app.ui.profile.ProfileScreen
import com.niutrip.app.ui.profile.ProfileViewModel
import com.niutrip.app.ui.share.ShareSheet
import com.niutrip.app.ui.share.ShareViewModel
import com.niutrip.app.ui.shareview.ShareViewScreen
import com.niutrip.app.ui.shareview.ShareViewViewModel
import com.niutrip.app.ui.tracks.TrackListScreen
import com.niutrip.app.ui.tracks.TrackListViewModel
import com.niutrip.app.ui.theme.Green50
import com.niutrip.app.ui.theme.Green700

object Routes {
    const val LOGIN = "login"
    const val TRACKS = "tracks"
    const val PROFILE = "profile"
    const val CREATE = "createTrack"
    const val DETAIL = "trackDetail/{trackId}?readOnly={readOnly}"
    const val CHECKIN = "checkin/{trackId}"
    const val SHARE_VIEW = "shareView/{token}"
    fun detail(id: String, readOnly: Boolean = false) = "trackDetail/$id?readOnly=$readOnly"
    fun checkin(id: String) = "checkin/$id"
    fun share(token: String) = "shareView/$token"
}

private data class Tab(val label: String, val route: String, val icon: ImageVector)

@Composable fun AppNav(
    container: AppContainer,
    pendingToken: String?,
    clipboardToken: String?,
    debugTrackId: String? = null,
    consumeToken: () -> Unit,
    consumeClipboardToken: () -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val currentUserAvatarUrl by container.tokenStore.avatarUrl.collectAsState()
    val tabs = listOf(Tab("轨迹", Routes.TRACKS, Icons.Default.Map), Tab("我的", Routes.PROFILE, Icons.Default.Person))
    val showBar = destination?.route in tabs.map(Tab::route)
    var shareTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingToken, container.tokenStore.token) {
        if (pendingToken != null && container.tokenStore.token == null) nav.navigate(Routes.LOGIN)
    }
    LaunchedEffect(debugTrackId) {
        if (debugTrackId != null && container.tokenStore.token != null) nav.navigate(Routes.detail(debugTrackId))
    }

    Scaffold(bottomBar = {
        if (showBar) NavigationBar {
            tabs.forEach { tab -> NavigationBarItem(selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                onClick = { nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Green700, selectedTextColor = Green700, indicatorColor = Green50)) }
        }
    }) { outerPadding ->
        NavHost(nav, startDestination = if (container.tokenStore.token == null) Routes.LOGIN else Routes.TRACKS, modifier = Modifier.padding(outerPadding)) {
            composable(Routes.LOGIN) {
                val vm: LoginViewModel = viewModel(factory = ViewModelFactory { LoginViewModel(container.authRepository) })
                LoginScreen(vm) { nav.navigate(Routes.TRACKS) { popUpTo(Routes.LOGIN) { inclusive = true } } }
            }
            composable(Routes.TRACKS) {
                val vm: TrackListViewModel = viewModel(factory = ViewModelFactory { TrackListViewModel(container.trackRepository) })
                TrackListScreen(
                    vm,
                    { nav.navigate(Routes.CREATE) },
                    { track -> nav.navigate(Routes.detail(track.track_id, track.sharer_username != null)) },
                    { TrackRecordingService.stop(nav.context) },
                )
            }
            composable(Routes.CREATE) {
                val vm: CreateTrackViewModel = viewModel(factory = ViewModelFactory { CreateTrackViewModel(container.trackRepository, AndroidPermissionChecker(nav.context)) })
                CreateTrackScreen(vm, nav::popBackStack) { nav.navigate(Routes.detail(it)) { popUpTo(Routes.CREATE) { inclusive = true } } }
            }
            composable(Routes.DETAIL, arguments = listOf(navArgument("trackId") { type = NavType.StringType }, navArgument("readOnly") { type = NavType.BoolType; defaultValue = false })) { backStack ->
                val id = backStack.arguments?.getString("trackId").orEmpty()
                val readOnly = backStack.arguments?.getBoolean("readOnly") ?: false
                val vm: TrackDetailViewModel = viewModel(key = "detail-$id", factory = ViewModelFactory {
                    TrackDetailViewModel(id, container.trackRepository, container.locationSource,
                        container.syncRepository, ImageCompressor(nav.context),
                        recordSharedView = readOnly)
                })
                TrackDetailScreen(vm, readOnly, currentUserAvatarUrl, nav::popBackStack, { nav.navigate(Routes.checkin(it)) }, { shareTrackId = it },
                    { trackId, name, mode -> TrackRecordingService.start(nav.context, trackId, name, mode) },
                    { trackId, name, mode -> TrackRecordingService.pause(nav.context, trackId, name, mode) },
                    { trackId -> TrackRecordingService.isPaused(nav.context, trackId) },
                    { TrackRecordingService.stop(nav.context) })
            }
            composable(Routes.CHECKIN) { backStack ->
                val id = backStack.arguments?.getString("trackId").orEmpty()
                val vm: CheckinViewModel = viewModel(key = "checkin-$id", factory = ViewModelFactory { CheckinViewModel(id, container.api, container.locationSource, ImageCompressor(nav.context)) })
                CheckinScreen(vm, nav::popBackStack) { nav.popBackStack() }
            }
            composable(Routes.SHARE_VIEW) { backStack ->
                val token = backStack.arguments?.getString("token").orEmpty()
                val vm: ShareViewViewModel = viewModel(key = "share-view-$token", factory = ViewModelFactory {
                    ShareViewViewModel(token, container.api, container.locationSource)
                })
                ShareViewScreen(vm, currentUserAvatarUrl, nav::popBackStack)
            }
            composable(Routes.PROFILE) {
                val vm: ProfileViewModel = viewModel(factory = ViewModelFactory { ProfileViewModel(container.api, container.tokenStore, ImageCompressor(nav.context)) })
                ProfileScreen(vm) { TrackRecordingService.stop(nav.context); nav.navigate(Routes.LOGIN) { popUpTo(nav.graph.id) { inclusive = true } } }
            }
        }
    }
    shareTrackId?.let { id ->
        val vm: ShareViewModel = viewModel(key = "share-$id", factory = ViewModelFactory {
            ShareViewModel(id, container.api, container.trackRepository, container.syncRepository)
        })
        ShareSheet(vm) { shareTrackId = null }
    }
    val incomingShareToken = pendingToken ?: clipboardToken
    if (incomingShareToken != null && container.tokenStore.token != null && destination?.route != Routes.LOGIN) {
        val fromClipboard = pendingToken == null && clipboardToken != null
        val dismissIncomingShare = {
            consumeToken()
            consumeClipboardToken()
        }
        val inspectViewModel: IncomingShareViewModel = viewModel(
            key = "incoming-share-${if (fromClipboard) "clipboard" else "link"}-$incomingShareToken",
            factory = ViewModelFactory {
                IncomingShareViewModel(incomingShareToken, container.api, ignoreAlreadySaved = fromClipboard)
            },
        )
        val decision by inspectViewModel.decision.collectAsState()
        if (decision == IncomingShareDecision.IGNORE) {
            LaunchedEffect(incomingShareToken) { dismissIncomingShare() }
        } else if (decision == IncomingShareDecision.PROMPT) {
            AlertDialog(
                onDismissRequest = dismissIncomingShare,
                title = { Text("加入分享轨迹？") },
                text = { Text("检测到一条好友分享的轨迹链接，是否加入到「分享给我的」列表？") },
                confirmButton = {
                    TextButton(onClick = {
                        dismissIncomingShare()
                        nav.navigate(Routes.share(incomingShareToken))
                    }) { Text("加入并查看") }
                },
                dismissButton = { TextButton(onClick = dismissIncomingShare) { Text("暂不加入") } },
            )
        }
    }
}
