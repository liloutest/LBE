package com.lbe.imsdk.ui.presentation.screen

import LightPullToRefreshList
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.shouldShowRationale
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.gson.Gson
import com.lbe.imsdk.R
import com.lbe.imsdk.model.MediaMessage
import com.lbe.imsdk.model.MessageEntity
import com.lbe.imsdk.model.proto.IMMsg
import com.lbe.imsdk.model.resp.AnswerTimeoutContent
import com.lbe.imsdk.model.resp.CsJoinInfo
import com.lbe.imsdk.model.resp.IconUrl
import com.lbe.imsdk.model.resp.RankingContent
import com.lbe.imsdk.ui.presentation.components.*
import com.lbe.imsdk.ui.presentation.viewmodel.ChatScreenViewModel
import com.lbe.imsdk.ui.presentation.viewmodel.ConnectionStatus
import com.lbe.imsdk.utils.FileUtils
import com.lbe.imsdk.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

data class ChatScreenUiState(
    var messages: MutableSet<MessageEntity> = mutableSetOf(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_STARTED,
    val login: Boolean = false,
)

enum class MessagePosition {
    LEFT, RIGHT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Appbar(viewModel: ChatScreenViewModel) {
    val kickOffLine by viewModel.kickOffLine.collectAsState(false)
    val kickOfflineMessage = stringResource(R.string.chat_session_status_8)
    val ctx = LocalContext.current

    CenterAlignedTopAppBar(
        title = {
            Text(
                stringResource(R.string.chat_session_status_1), style = TextStyle(
                    color = Color(0xff18243E), fontSize = 18.sp, fontWeight = FontWeight.W500
                )
            )
        }, colors = topAppBarColors(
            containerColor = Color(0xFFF3F4F6), titleContentColor = Color.Black
        ), navigationIcon = {
            IconButton(onClick = {
                if (ctx is Activity) {
                    ctx.finish()
                }
            }) {
                Image(
                    painter = painterResource(R.drawable.back),
                    contentDescription = "Localized description",
                    modifier = Modifier.size(width = 24.dp, height = 24.dp)
                )
            }
        }, actions = {
            val turnCustom = viewModel.turnCustomServiceState.collectAsState().value
            if (turnCustom) {
                Box(modifier = Modifier.padding(end = 16.dp)) {
                    CloseCustomerServiceButton(
                        onClick = viewModel::showEndCustomServiceDialog
                    )
                }
            }

            /*IconButton(onClick = {
                if (kickOffLine) {
                    Toast.makeText(
                        ctx, kickOfflineMessage, Toast.LENGTH_SHORT
                    ).show()
                    return@IconButton
                }
                viewModel.turnCustomerService()
            }) {
                Image(
                    painter = painterResource(R.drawable.cs),
                    contentDescription = "Localized description",
                    modifier = Modifier.size(width = 24.dp, height = 24.dp)
                )
            }*/
        })
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatScreenViewModel,
    imageLoader: ImageLoader,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val uploadImageLimit = stringResource(R.string.chat_session_status_24)
    val uploadVideoLimit = stringResource(R.string.chat_session_status_25)
    val faqNotExist = stringResource(R.string.chat_session_status_28)
    val kickOfflineMessage = stringResource(R.string.chat_session_status_8)

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val uiState by viewModel.uiState.observeAsState(ChatScreenUiState())

//    val messages by viewModel.messageList.collectAsState()

    val input by viewModel.inputMsg.observeAsState("init")
    var showDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val currentFocus = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val lazyListState = rememberLazyListState()

    var pickFileEvent by remember { mutableStateOf("") }

    val pickFilesResult = remember { mutableStateOf<List<Uri>>(emptyList()) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(
                9
            ), onResult = { uris: List<Uri> ->
                pickFilesResult.value = uris
                pickFileEvent = pickFileEvent.plus(",")
            })

    val mediaPermissionState = rememberMultiplePermissionsState(
        permissions = if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) else listOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ),
        onPermissionsResult = { permits ->
            println("授权回调--->>> $permits")
            val allPermitted = permits.values.all { it }
            if (allPermitted) {
                launcher.launch(
                    PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        },
    )

    val isConnected by viewModel.isConnected.observeAsState(initial = true)

//    val screenHeightPx =
//        with(LocalDensity.current) { (configuration.screenHeightDp.dp - 155.dp).toPx() }
    var showToBottomButton by remember { mutableStateOf(false) }
    val toBottomEvent by viewModel.toBottom.collectAsState("")
    val previousToBottomEvent = rememberSaveable { mutableStateOf(toBottomEvent) }
    val recivedEvent by viewModel.recived.collectAsState("")

    val kickOffLine by viewModel.kickOffLine.collectAsState(false)
    val kickOffLineEvent by viewModel.kickOfflineEvent.collectAsState("")
    val previousKickOffLineEvent = rememberSaveable { mutableStateOf(kickOffLineEvent) }

    val faqNotExistEvent by viewModel.faqNotExistEvent.collectAsState("")
    val previousFaqNotExistEvent = rememberSaveable { mutableStateOf(faqNotExistEvent) }

    val isRefreshing = remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) {
                    println("LbeChat Lifecycle --->> ChatScreen ON_RESUME: ${viewModel.sdkInit}")
                    if (viewModel.sdkInit) {
                        coroutineScope.launch {
                            viewModel.checkNeedSyncRemote()
                        }
                    }
                }
                if (event == Lifecycle.Event.ON_DESTROY) {
                    println("LbeChat Lifecycle --->> ChatScreen ON_DESTROY")
                }
            }
        })
    }

    LaunchedEffect(viewModel) {
//        snapshotFlow { lazyListState.layoutInfo }.collect { layoutInfo ->
//            val totalItems = layoutInfo.totalItemsCount
//            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
//            if (totalItems > 0 && lastVisibleItemIndex == totalItems - 1) {
//            }
//        }
        snapshotFlow { lazyListState.canScrollForward }
            .distinctUntilChanged()
            .collect { canScrollForward ->
//            if (canScrollForward) {
//                currentFocus.clearFocus()
//            }
                showToBottomButton = canScrollForward
            }

    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    currentFocus.clearFocus()
                },
            ),
        containerColor = Color(0xFFF3F4F6),
        topBar = { Appbar(viewModel) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xffFF6164).copy(0.1f)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.network_unavailable),
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.chat_session_status_9),
                        style = TextStyle(
                            color = Color(0xff979797),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W400
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp, bottom = 14.dp)
                    )
                }
            } else {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp),
                    color = Color(0xffEBEBEB),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                LightPullToRefreshList(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = isRefreshing.value,
                    onRefresh = {
                        isRefreshing.value = true
                        if (viewModel.currentPage > 1) {
                            viewModel.currentPage -= 1
                            viewModel.filterLocalMessages()
                        } else {
                            viewModel.loadHistory()
                        }
                        delay(500)
                        isRefreshing.value = false
                    }) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
                        state = lazyListState
                    ) {
                        val msgList = uiState.messages.toList()
                        itemsIndexed(
                            msgList,
                        ) { index, message ->
                            MessageItem(
                                msgList,
                                message = message,
                                if (message.senderUid == viewModel.uid) MessagePosition.RIGHT
                                else MessagePosition.LEFT,
                                viewModel,
                                navController,
                                imageLoader,
                                modifier = Modifier
                                    .fillMaxWidth(),
                            )
                            LaunchedEffect(uiState.messages) {
                                if (index <= uiState.messages.size - 1) {
                                    val visitAbleMsg = uiState.messages.toList()[index]
                                    if (!visitAbleMsg.readed && visitAbleMsg.senderUid != viewModel.uid) {
                                        viewModel.markRead(message)
                                    }
                                }
                            }
                        }
                    }
                }

                if (showToBottomButton) {
                    ToBottom(viewModel = viewModel, goToTop = {
                        coroutineScope.launch {
                            delay(59)
                            viewModel.scrollToBottom()
                            viewModel.resetRecivCount()
                            showToBottomButton = false
                        }
                    })
                }

            }

            var isExpanded by remember { mutableStateOf(false) }
            var lineCount by remember { mutableIntStateOf(1) }
            var textFieldHeight by remember { mutableStateOf(42.dp) }
            Column {
                if (!viewModel.turnCustomServiceState.collectAsState().value) {
                    Box(modifier = Modifier.padding(start = 16.dp, bottom = 10.dp)) {
                        StartCustomerServiceButton() {
                            //人工服务
                            if (kickOffLine) {
                                Toast.makeText(
                                    context, kickOfflineMessage, Toast.LENGTH_SHORT
                                ).show()
                                return@StartCustomerServiceButton
                            }
                            viewModel.turnCustomerService()
                        }
                    }
                }
//                timeoutTips(viewModel = viewModel)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp, end = 16.dp)
                ) {
                    Box(
                        modifier = Modifier.height(textFieldHeight)
                    ) {
                        if (isExpanded) {
                            Surface(
                                color = Color.White,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .align(Alignment.TopStart)
                                    .clickable {
                                        showDialog = true
                                    }) {
                                Image(
                                    painter = painterResource(R.drawable.expanded),
                                    contentDescription = "",
                                    modifier = Modifier
                                        .padding(5.dp)
                                        .size(8.dp)
                                )
                            }
                        }

                        Surface(
                            color = Color.White,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .align(Alignment.BottomStart)
                                .clickable {
                                    if (!mediaPermissionState.allPermissionsGranted) {
                                        println("授权检查--->>> ${mediaPermissionState.permissions.map { e -> "${e.permission}, ${e.status}" }} ||||| ${mediaPermissionState.allPermissionsGranted}")
                                        mediaPermissionState.launchMultiplePermissionRequest()
                                    } else {
                                        launcher.launch(PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    }
                                }) {
                            Image(
                                painter = painterResource(R.drawable.open_file),
                                contentDescription = "",
                                modifier = Modifier
                                    .padding(5.dp)
                                    .size(21.dp, 16.dp)
                            )
                            LaunchedEffect(
                                pickFileEvent
                            ) {
                                if (pickFilesResult.value.isNotEmpty()) {
                                    val uris = pickFilesResult.value
                                    Log.d(
                                        ChatScreenViewModel.FILE_SELECT,
                                        "${pickFilesResult.value}"
                                    )
                                    for (uri in uris) {
                                        try {
                                            val cr = context.contentResolver
                                            cr.takePersistableUriPermission(
                                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            )
                                            val projection = arrayOf(
                                                MediaStore.MediaColumns.DISPLAY_NAME,
                                                MediaStore.MediaColumns.MIME_TYPE,
//                                                MediaStore.MediaColumns.DATA,
                                            )
                                            val metaCursor =
                                                cr.query(uri, projection, null, null, null)
                                            var fName = ""
                                            var mime = ""
                                            metaCursor?.use { mCursor ->
                                                if (mCursor.moveToFirst()) {
                                                    fName = mCursor.getString(0)
                                                    mime = mCursor.getString(1)
//                                                    val path = mCursor.getString(2)

                                                }
                                            }
//                                                Log.d(
//                                                    ChatScreenViewModel.FILE_SELECT,
//                                                    "查询 --->>> fileName: $fName, mime: $mime, \npath: $path"
//                                                )
                                            if (fName.isEmpty()) {
                                                return@LaunchedEffect
                                            }

                                            suspend fun copyToCache(
                                                uri: Uri,
                                                fName: String
                                            ): String = withContext(
                                                Dispatchers.IO
                                            ) {
                                                val inputStream =
                                                    context.contentResolver.openInputStream(uri)
                                                        ?: throw Exception("inputStream is null")
                                                val tempFile = File(context.cacheDir, fName)

                                                tempFile.outputStream().use { output ->
                                                    inputStream.copyTo(output)
                                                }
                                                return@withContext tempFile.absolutePath
                                            }

                                            val path = copyToCache(uri, fName)
                                            val file = File(path)
                                            val mediaMessage = MediaMessage(
                                                width = 0,
                                                height = 0,
                                                file = file,
                                                path = path,
                                                mime = mime,
                                                isImage = FileUtils.isImage(mime),
                                                fileName = fName,
                                                fileSize = file.length(),
                                            )
                                            if (FileUtils.isImage(mediaMessage.mime) && mediaMessage.file.length() > 1024 * 1024 * 10) {
                                                Toast.makeText(
                                                    context,
                                                    uploadImageLimit,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@LaunchedEffect
                                            }
                                            if (!FileUtils.isImage(mediaMessage.mime) && mediaMessage.file.length() > 1024 * 1024 * 100) {
                                                Toast.makeText(
                                                    context,
                                                    uploadVideoLimit,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@LaunchedEffect
                                            }
                                            viewModel.preInsertUpload(mediaMessage)
//                                                Log.d(
//                                                    ChatScreenViewModel.FILE_SELECT,
//                                                    "found file --->> ${file.name}, ${file.path}, ${file.length()}, ${file.absolutePath}, mimeType: $mime, Is image file: ${
//                                                        FileUtils.isImage(
//                                                            mime
//                                                        )
//                                                    }"
//                                                )
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    val maxLength = 500
                    if (showDialog) {
                        Dialog(
                            onDismissRequest = { showDialog = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = MaterialTheme.shapes.medium,
                                color = Color(0xFFF3F4F6)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Surface(
                                            color = Color.White,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    showDialog = false
                                                }) {
                                            Image(
                                                painter = painterResource(R.drawable.close),
                                                contentDescription = "",
                                                modifier = Modifier
                                                    .padding(5.dp)
                                                    .size(8.dp)
                                            )
                                        }

                                        Text(
                                            "${input.length}/$maxLength", style = TextStyle(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.W400,
                                                color = Color(0xff979797)
                                            )
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    BasicTextField(
                                        value = input,
                                        onValueChange = { newValue ->
                                            if (newValue.length <= maxLength) {
                                                viewModel.onMessageChange(newValue)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                detectTapGestures(onLongPress = {
                                                    clipboardManager
                                                        .getText()
                                                        ?.let { clipboardText ->
                                                            viewModel.onMessageChange(
                                                                clipboardText.text
                                                            )
                                                        }
                                                })
                                            },
                                        readOnly = false,
                                        textStyle = TextStyle(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.W400,
                                            color = Color.Black,
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Text,
                                            imeAction = ImeAction.None,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    BasicTextField(
                        value = input,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = {
                                    clipboardManager
                                        .getText()
                                        ?.let { clipboardText ->
                                            viewModel.onMessageChange(
                                                clipboardText.text
                                            )
                                        }
                                },
                                onClick = {}
                            ),
                        onValueChange = { newValue ->
                            if (newValue.length <= maxLength) {
                                viewModel.onMessageChange(newValue)
                            }
                        },
                        maxLines = 5,
                        onTextLayout = { textLayoutResult ->
                            lineCount = textLayoutResult.lineCount
                            isExpanded = lineCount > 4
                            if (!isExpanded) {
                                if (lineCount < 2) {
                                    textFieldHeight = 42.dp
                                } else {
                                    val calculatedHeightPx = textLayoutResult.size.height
                                    textFieldHeight = with(density) {
                                        calculatedHeightPx.toDp().plus(23.dp)
                                            .coerceAtMost(203.dp)
                                    }
                                }
                            }
                        },
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 42.dp, max = 180.dp)
                                    .padding(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = 9.dp,
                                        bottom = 11.dp
                                    ),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    innerTextField()
                                    if (input.isEmpty()) {
                                        Text(
                                            stringResource(R.string.chat_session_status_12),
                                            style = TextStyle(
                                                color = Color(0xffEBEBEB),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.W400,
                                            )
                                        )
                                    }
                                }
                                val cannotSendEmptyMessage =
                                    stringResource(R.string.chat_session_status_23)
                                Image(
                                    painter = painterResource(R.drawable.send),
                                    contentDescription = "Send Button",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            if (kickOffLine) {
                                                Toast
                                                    .makeText(
                                                        context,
                                                        kickOfflineMessage,
                                                        Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                                return@clickable
                                            }

                                            viewModel.sendMessageFromTextInput(messageSent = {
                                                currentFocus.clearFocus()
                                            }, trimToast = {
                                                Toast
                                                    .makeText(
                                                        context,
                                                        cannotSendEmptyMessage,
                                                        Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            })
                                        })
                            }
                        },
                    )
                }
            }
        }

        if (viewModel.endCustomServiceDialog.collectAsState().value) {
            EndCustomServiceDialog(onDismissRequest = {
                viewModel.endCustomServiceDialog.value = false
            }, endService = viewModel::endSession)
        }

        LaunchedEffect(toBottomEvent) {
            if (toBottomEvent.isNotEmpty() && toBottomEvent != previousToBottomEvent.value) {
                coroutineScope.launch {
                    showToBottomButton = false
                    delay(59)
                    if (uiState.messages.isNotEmpty()) {
                        lazyListState.requestScrollToItem(uiState.messages.size - 1)
                    }
                    previousToBottomEvent.value = toBottomEvent
                }
            }
        }

        LaunchedEffect(kickOffLineEvent) {
            if (kickOffLineEvent.isNotEmpty() && kickOffLineEvent != previousKickOffLineEvent.value) {
                Toast.makeText(
                    context, kickOfflineMessage, Toast.LENGTH_LONG
                ).show()
            }
        }

        LaunchedEffect(faqNotExistEvent) {
            if (faqNotExistEvent.isNotEmpty() && faqNotExistEvent != previousFaqNotExistEvent.value) {
                Toast.makeText(context, faqNotExist, Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(recivedEvent) {
            if (recivedEvent.isNotEmpty()) {
                if (!showToBottomButton) {
                    coroutineScope.launch {
                        delay(100)
                        if (uiState.messages.isNotEmpty()) {
                            lazyListState.requestScrollToItem(uiState.messages.size - 1)
                        }
                        showToBottomButton = false
                    }
                    viewModel.resetRecivCount()
                }
            }
        }
    }
}

//@Composable
//fun timeoutTips(viewModel: ChatScreenViewModel) {
//    val timeoutVisibility by viewModel.isTimeOut.collectAsState()
//    val timeOutConfigOpen by viewModel.timeOutConfigOpen.collectAsState()
//    AnimatedVisibility(visible = timeOutConfigOpen && timeoutVisibility) {
//        Log.d("TimeOut", "timeoutVisibility --->>> $timeoutVisibility")
//        Column {
//            Surface(
//                color = Color(0xffEBEBEB), modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(start = 12.dp)
//            ) {
//                Text(
//                    stringResource(R.string.chat_session_status_3, viewModel.timeOut),
//                    style = TextStyle(
//                        color = Color(0xff979797), fontSize = 12.sp, fontWeight = FontWeight.W400
//                    ),
//                    textAlign = TextAlign.Center,
//                    modifier = Modifier.padding(top = 14.dp, bottom = 14.dp)
//                )
//            }
//            Spacer(modifier = Modifier.height(12.dp))
//        }
//    }
//}

@Composable
fun ToBottom(viewModel: ChatScreenViewModel, goToTop: () -> Unit) {
    val recivCount by viewModel.recivCount.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = Color(0xff0054FC).copy(alpha = 0.15f),
            modifier = Modifier
                .padding(bottom = 50.dp, end = 16.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                    )
                )
                .clickable {
                    goToTop()
                }
//                .blur(20.dp)
                .align(Alignment.BottomEnd)) {
            Row(
                modifier = Modifier.padding(
                    start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp
                )
            ) {
                Text(
                    if (recivCount == 0) stringResource(R.string.chat_session_status_10) else stringResource(
                        R.string.chat_session_status_11, recivCount
                    ), style = TextStyle(
                        color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.W400
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.to_bottom),
                    contentDescription = "",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun MessageItem(
    messages: List<MessageEntity>,
    message: MessageEntity,
    messagePosition: MessagePosition,
    viewModel: ChatScreenViewModel,
    navController: NavController,
    imageLoader: ImageLoader,
    modifier: Modifier,
) {
    when (message.msgType) {
        4 -> {}
        5 -> {
            if (message.msgBody.isNotEmpty()) {
                val csJoinInfo = Gson().fromJson(message.msgBody, CsJoinInfo::class.java)

                Box(
                    contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        color = Color.White,
                    ) {
                        Text(
                            stringResource(
                                R.string.chat_session_status_4, csJoinInfo.username
                            ),//"${csJoinInfo.username} 将为您服务",
                            style = TextStyle(
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.W400,
                            ), modifier = Modifier.padding(
                                start = 8.dp, end = 8.dp, bottom = 8.dp, top = 19.dp
                            )
                        )
                    }

                    if (csJoinInfo.faceUrl.isNotEmpty()) {
                        val iconUrl = Gson().fromJson(csJoinInfo.faceUrl, IconUrl::class.java)
                        if (iconUrl.url.isNotEmpty()) {
                            NormalDecryptedOrNotImageView(
                                key = iconUrl.key,
                                url = iconUrl.url,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape),
                                imageLoader
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.default_cs_avatar),
                                contentDescription = "",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.default_cs_avatar),
                            contentDescription = "",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        6, 7, 11, 13 -> {
            val content = when (message.msgType) {
                6 -> stringResource(R.string.chat_session_status_6)
                7 -> {
                    val rankingContent =
                        Gson().fromJson(message.msgBody, RankingContent::class.java)
                    stringResource(R.string.chat_session_status_2, rankingContent.number)
                }

                11 -> stringResource(R.string.chat_session_status_5)
                13 -> stringResource(R.string.chat_session_status_7)
                else -> "Not result"
            }
            SystemMessageContent(content)
        }

        IMMsg.ContentType.AnswerMsgTimeoutContentType_VALUE -> {
            val content =
                Gson().fromJson(message.msgBody, AnswerTimeoutContent::class.java)
            SystemMessageContent(
                stringResource(
                    R.string.chat_session_status_3,
                    content.timeout
                )
            )
        }

        else ->
            Box(
                modifier = modifier,
                contentAlignment = if (messagePosition == MessagePosition.LEFT) Alignment.TopStart else Alignment.BottomEnd
            ) {
                if (messagePosition == MessagePosition.LEFT) {
                    RecievedFromCustomerService(
                        messages,
                        message,
                        messagePosition,
                        viewModel = viewModel,
                        navController,
                        imageLoader
                    )
                } else {
                    UserInput(
                        messages,
                        message,
                        messagePosition,
                        viewModel = viewModel,
                        navController,
                        imageLoader
                    )
                }
            }
    }
}

@Composable
fun RecievedFromCustomerService(
    messages: List<MessageEntity>,
    message: MessageEntity,
    messagePosition: MessagePosition,
    viewModel: ChatScreenViewModel,
    navController: NavController,
    imageLoader: ImageLoader,
) {
    val currentIndex = messages.indexOf(message)
    var needShowTime = false
    var sameCurrentDay = false
    if (currentIndex != 0) {
        val prev = messages[currentIndex - 1]
        val diff = (message.sendTime - prev.sendTime) / 1000
        sameCurrentDay = TimeUtils.isSameDay(Date(), Date(message.sendTime))
        if (diff > 60 * 3) {
            needShowTime = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(visible = needShowTime) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (sameCurrentDay) TimeUtils.formatHHMMTime(message.sendTime) else TimeUtils.formatYYMMHHMMTime(
                        message.sendTime
                    ),
                    style = TextStyle(
                        color = Color(0xff979797),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W400
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 9.dp)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            if (message.csAvatar is IconUrl) {
                NormalDecryptedOrNotImageView(
                    key = (message.csAvatar as IconUrl).key,
                    url = (message.csAvatar as IconUrl).url,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape),
                    imageLoader,
                    contentScale = ContentScale.Crop,
                    error = painterResource(id = if (message.senderUid.isEmpty()) R.drawable.robots_avatar else R.drawable.default_cs_avatar),
                )
            } else {
                AsyncImage(
                    model = message.csAvatar,
                    contentDescription = "Yo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape),
                    error = painterResource(id = if (message.senderUid.isEmpty()) R.drawable.robots_avatar else R.drawable.default_cs_avatar),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp)
            ) {
                Text(
                    text = message.customerServiceNickname.ifEmpty {
                        if (message.senderUid.isNotEmpty()) stringResource(
                            R.string.chat_session_status_1
                        ) else stringResource(R.string.chat_session_status_14)
                    },
                    modifier = Modifier.align(if (messagePosition == MessagePosition.LEFT) Alignment.Start else Alignment.End),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W400,
                        color = Color(0xff979797)
                    )
                )
                Spacer(Modifier.height(8.dp))
                MsgTypeContent(message, viewModel, navController, false, imageLoader)
            }
        }
    }
}

@Composable
fun UserInput(
    messages: List<MessageEntity>,
    message: MessageEntity,
    messagePosition: MessagePosition,
    viewModel: ChatScreenViewModel,
    navController: NavController,
    imageLoader: ImageLoader,
) {
    val currentIndex = messages.indexOf(message)
    var needShowTime = false
    var sameCurrentDay = false
    if (currentIndex != 0) {
        val prev = messages[currentIndex - 1]
        val diff = (message.sendTime - prev.sendTime) / 1000
        sameCurrentDay = TimeUtils.isSameDay(Date(), Date(message.sendTime))
        if (diff > 60 * 3) {
            needShowTime = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visible = needShowTime) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (sameCurrentDay) TimeUtils.formatHHMMTime(message.sendTime) else TimeUtils.formatYYMMHHMMTime(
                        message.sendTime
                    ),
                    style = TextStyle(
                        color = Color(0xff979797), fontSize = 10.sp, fontWeight = FontWeight.W400
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 9.dp)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
            ) {
                Text(
                    text = viewModel.nickName.ifEmpty {
                        stringResource(
                            if (viewModel.isGuest) R.string.chat_session_status_15 else R.string.chat_session_status_16,
                            viewModel.nickId
                        )
                    },
                    modifier = Modifier.align(if (messagePosition == MessagePosition.LEFT) Alignment.Start else Alignment.End),
                    style = TextStyle(
                        fontSize = 14.sp, fontWeight = FontWeight.W400, color = Color(0xff979797)
                    )
                )
                Spacer(Modifier.height(8.dp))
                MsgTypeContent(message, viewModel, navController, true, imageLoader)
            }
            val iconUrl = viewModel.userAvatar
            if (null != iconUrl) {
                if (iconUrl is IconUrl) {
                    NormalDecryptedOrNotImageView(
                        key = iconUrl.key,
                        url = iconUrl.url,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                        imageLoader,
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.default_user_avatar),
                    )
                } else {
                    AsyncImage(
                        model = viewModel.userAvatar,
                        contentDescription = "Yo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                        error = painterResource(id = R.drawable.default_user_avatar),
                    )
                }
            } else {
                Image(
                    painter = painterResource(id = R.drawable.default_user_avatar),
                    contentDescription = "",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}





