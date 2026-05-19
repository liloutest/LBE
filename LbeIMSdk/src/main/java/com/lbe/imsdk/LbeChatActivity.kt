package com.lbe.imsdk

import android.annotation.*
import android.os.*
import android.os.Build.VERSION.*
import android.util.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.compose.ui.platform.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.*
import androidx.navigation.compose.*
import coil3.ImageLoader
import coil3.gif.*
import coil3.video.*
import com.google.gson.*
import com.lbe.imsdk.model.*
import com.lbe.imsdk.ui.presentation.components.*
import com.lbe.imsdk.ui.presentation.screen.*
import com.lbe.imsdk.ui.presentation.viewmodel.*
import com.lbe.imsdk.ui.theme.*
import java.util.*

class LbeChatActivity : ComponentActivity() {

    private val viewModel: ChatScreenViewModel by viewModels<ChatScreenViewModel>()

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        val initArgsJson = intent.getStringExtra("initArgs")
        val initArgs = Gson().fromJson(initArgsJson, InitArgs::class.java)
        updateAppLanguage(initArgs.language)
        super.onCreate(savedInstanceState)
        viewModel.initSdk(initArgs)
        Log.d("LbeIMSdk", "Sdk 接收 args --->> $initArgs")
        setContent {
            ChatAppTheme {
                val navController = rememberNavController()
                val context = LocalContext.current
                val gifImageLoader = ImageLoader.Builder(context).components {
                    if (SDK_INT >= Build.VERSION_CODES.P) {
                        add(AnimatedImageDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                    add(VideoFrameDecoder.Factory())
                }.build()

                NavHost(
                    navController = navController, startDestination = NavRoute.CHAT
                ) {
                    composable(route = NavRoute.CHAT) {
                        ChatScreen(navController, viewModel, imageLoader = gifImageLoader)
                    }
                    composable(route = "${NavRoute.MEDIA_VIEWER}/{msgClientId}") { navBackStackEntry ->
                        val msgClientId = navBackStackEntry.arguments?.getString("msgClientId")
                        msgClientId?.let {
                            MediaViewer(
                                navController, viewModel, msgClientId, gifImageLoader
                            )
                        }
                    }
                    composable(route = NavRoute.CUSTOM_REFRESH) {}
                }
            }
        }
    }

    private fun updateAppLanguage(language: String) {
        val locale = when (language.lowercase()) {
            "zh", "zh_cn", "zh-cn","0" -> Locale.CHINESE
            "vi", "vi_vn", "vi-vn","2" -> Locale("vi")
            else -> Locale.ENGLISH
        }

        val config = resources.configuration
        config.setLocale(locale)
        val context = createConfigurationContext(config)
        resources.updateConfiguration(config, resources.displayMetrics)
        baseContext.resources.updateConfiguration(config, baseContext.resources.displayMetrics)
    }
}