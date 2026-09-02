package com.phone.mirror.phone.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.phone.mirror.phone.Phase0TestScreen
import com.phone.mirror.phone.di.AppContainer
import com.phone.mirror.phone.ui.device.DeviceListScreen
import com.phone.mirror.phone.ui.files.FileBrowserScreen
import com.phone.mirror.phone.ui.gallery.GalleryScreen
import com.phone.mirror.phone.ui.mirror.MirrorScreen
import com.phone.mirror.phone.ui.pairing.PairingScreen

/** 导航路由常量 */
object Routes {
    const val DEVICE_LIST = "device_list"
    const val PAIRING = "pairing"
    const val PHASE0 = "phase0"
    const val MIRROR = "mirror/{deviceId}"
    const val FILES = "files/{deviceId}"
    const val GALLERY = "gallery/{deviceId}"
}

/**
 * 整个 App 的导航图。
 */
@Composable
fun AppNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.DEVICE_LIST,
        modifier = modifier,
    ) {
        composable(Routes.DEVICE_LIST) {
            DeviceListScreen(
                container = container,
                onMirrorClick = { deviceId ->
                    navController.navigate(Routes.MIRROR.replace("{deviceId}", deviceId))
                },
                onPairingClick = {
                    navController.navigate(Routes.PAIRING)
                },
                onPhase0Click = {
                    navController.navigate(Routes.PHASE0)
                },
                onGalleryClick = { deviceId ->
                    navController.navigate(Routes.GALLERY.replace("{deviceId}", deviceId))
                },
                onFilesClick = { deviceId ->
                    navController.navigate(Routes.FILES.replace("{deviceId}", deviceId))
                },
            )
        }

        composable(Routes.PAIRING) {
            PairingScreen(container = container)
        }

        composable(Routes.PHASE0) {
            Phase0TestScreen()
        }

        composable(
            route = Routes.MIRROR,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId")
            MirrorScreen(container = container, deviceId = deviceId.orEmpty())
        }

        composable(
            route = Routes.GALLERY,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId")
            GalleryScreen(container = container, deviceId = deviceId.orEmpty())
        }

        composable(
            route = Routes.FILES,
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
        ) { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId")
            FileBrowserScreen(container = container, deviceId = deviceId.orEmpty())
        }
    }
}
