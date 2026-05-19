package com.lbe.imsdk.ui.presentation.viewmodel

import NetworkMonitor
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.lbe.imsdk.data.local.IMLocalRepository
import com.lbe.imsdk.data.remote.LbeConfigApiRepository
import com.lbe.imsdk.data.remote.LbeImApiRepository
import com.lbe.imsdk.data.remote.LbeOssApiRepository
import com.lbe.imsdk.model.*
import com.lbe.imsdk.model.proto.IMMsg
import com.lbe.imsdk.model.req.*
import com.lbe.imsdk.model.req.Pagination
import com.lbe.imsdk.model.resp.*
import com.lbe.imsdk.service.BASE_URL
import com.lbe.imsdk.service.ChatService
import com.lbe.imsdk.service.DynamicHeaderUrlRequestFactory
import com.lbe.imsdk.ui.presentation.components.ProgressRequestBody
import com.lbe.imsdk.ui.presentation.components.ProgressRequestBody.Companion.toRequestBody
import com.lbe.imsdk.ui.presentation.screen.ChatScreenUiState
import com.lbe.imsdk.utils.Converts.entityToMediaSendBody
import com.lbe.imsdk.utils.Converts.entityToSendBody
import com.lbe.imsdk.utils.Converts.protoToEntity
import com.lbe.imsdk.utils.Converts.protoTypeConvert
import com.lbe.imsdk.utils.Converts.sendBodyToEntity
import com.lbe.imsdk.utils.FileLogger
import com.lbe.imsdk.utils.TimeUtils.timeStampGen
import com.lbe.imsdk.utils.UUIDUtils.uuidGen
import com.lbe.imsdk.utils.UploadBigFileUtils
import com.tinder.scarlet.Lifecycle
import com.tinder.scarlet.Message
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.WebSocket.Event.OnConnectionClosed
import com.tinder.scarlet.WebSocket.Event.OnConnectionClosing
import com.tinder.scarlet.WebSocket.Event.OnConnectionFailed
import com.tinder.scarlet.WebSocket.Event.OnConnectionOpened
import com.tinder.scarlet.WebSocket.Event.OnMessageReceived
import com.tinder.scarlet.lifecycle.LifecycleRegistry
import com.tinder.scarlet.retry.BackoffStrategy
import com.tinder.scarlet.retry.ExponentialBackoffStrategy
import com.tinder.scarlet.retry.LinearBackoffStrategy
import com.tinder.scarlet.streamadapter.rxjava2.RxJava2StreamAdapterFactory
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.internal.notify
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*
import kotlin.collections.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ConnectionStatus {
    NOT_STARTED, OPENED, CLOSED, CONNECTING, CLOSING, FAILED, RECEIVED
}

class ChatScreenViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LbeIM_Websocket"
        const val REALM = "RealmTAG"
        const val UPLOAD = "LbeIM_UPLOAD"
        const val FILE_SELECT = "File Select"
        const val IMAGE_ENCRYPTION = "Image Encryption"
        const val CONTINUE_UPLOAD = "CONTINUE_UPLOAD"
        const val RETROFIT = "Lbe Retrofit"
        var lbeToken = ""
        var lbeSign = ""
    }

    var uid = ""
    var wssHost = ""
    var lbeSession = ""
    var seq: Int = 0
    var sessionList: MutableList<SessionEntry> = mutableListOf()
    var currentSession: SessionEntry? = null
    var currentSessionIndex = 0
    var currentSessionTotalPages = 1
    var showPageSize = 20
    var currentPage = 1
    var remoteLastMsgType = -1

    //游客
    var isGuest = false
    var nickId: String = ""
    var nickName: String = ""

    // url String / [IconUrl]
    var userAvatar: Any? = null
    var lbeIdentity: String = ""
    var progressList: MutableMap<String, MutableStateFlow<Float>> = mutableMapOf()
    val tempUploadInfos: MutableMap<String, TempUploadInfo> = mutableMapOf()
    var sdkInit: Boolean = false
    var endSession: Boolean = false
    var isAnonymous: Boolean = false

    private val jobs: MutableMap<String, Job> = mutableMapOf()
    private val mergeMultiUploadReqQueue: MutableMap<String, CompleteMultiPartUploadReq> =
        mutableMapOf()
    private val uploadTasks: MutableMap<String, UploadTask> = mutableMapOf()

    private val _uiState = MutableLiveData(ChatScreenUiState())
    val uiState: LiveData<ChatScreenUiState> = _uiState
    private var allMessageSize = 0

    var recivCount = MutableStateFlow(0)
    var toBottom = MutableStateFlow("")
    var recived = MutableStateFlow("")
    var lastCsMessage: MessageEntity? = null

//    var isTimeOut = MutableStateFlow(false)
//    var timeOutConfigOpen = MutableStateFlow(false)
//    var timeOutTimer: Timer? = null
//    var timeOut: Long = 5

    var kickOfflineEvent = MutableStateFlow(value = "")
    var kickOffLine = MutableStateFlow(false)
    var faqNotExistEvent = MutableStateFlow(value = "")

    //接入人工客服
    val turnCustomServiceState = MutableStateFlow(false)
    val endCustomServiceDialog = MutableStateFlow(false)

    private lateinit var initArgs: InitArgs

//    private val _messages = MutableStateFlow<List<MessageEntity>>(mutableListOf())
//    val messageList = _messages

    private val _inputMsg = MutableLiveData("")
    val inputMsg: LiveData<String> = _inputMsg

    private val okHttpClient: OkHttpClient =
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.DAYS)
            .retryOnConnectionFailure(true)
            .build()
    private var chatService: ChatService? = null
    private var wsSubs: Disposable? = null

    private val networkMonitor = NetworkMonitor(application)

    val isConnected = networkMonitor.isConnected

    private val sharedPreferences: SharedPreferences =
        application.getSharedPreferences("my_prefs", Context.MODE_PRIVATE)

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("coroutineExceptionHandler --->> Unhandled exception: ${throwable.message}")
    }
    private var socketLifecycleRegistry: LifecycleRegistry? = null
    private var pingTimer: Timer? = null

    private val fileLogger = FileLogger(application)

    // base api Repository
    private lateinit var configApiRepository: LbeConfigApiRepository

    // im api Repository
    private var imApiRepository: LbeImApiRepository? = null

    // oss api Repository
    private var ossApiRepository: LbeOssApiRepository? = null

    init {
        networkMonitor.startMonitoring()
        // testOfflineTakeByCache()
    }

    override fun onCleared() {
        socketLifecycleRegistry?.onNext(Lifecycle.State.Destroyed)
        networkMonitor.stopMonitoring()
//        disConnection()
        println("LbeChat Lifecycle --->> ChatScreenViewModel onCleared")
        super.onCleared()
    }

//    private fun disConnection() {
//        if (sdkInit) {
//            Log.d(TAG, "websocket disConnectionSdk")
//            sdkInit = false
//            wsSubs?.dispose()
//            okHttpClient.dispatcher.executorService.shutdown()
//            jobs["sdkJob"]?.cancel()
//            pingTimer?.cancel()
//            pingTimer = null
//        }
//    }

//    private fun testOfflineTakeByCache() {
//        currentSession = SessionEntry(sessionId = "cn-43ro83fqqzm2", latestMsg = null)
//        lbeSession = "cn-43ro83fqqzm2"
//        syncPageInfo(currentSession)
//        viewModelScope.launch(Dispatchers.IO) {
//            filterLocalMessages()
//            scrollToBottom()
//        }
//    }

    fun initSdk(args: InitArgs) {
        lbeSign = args.lbeSign
        isGuest = args.nickId.isEmpty()
        nickId = args.nickId.ifEmpty {
            sharedPreferences.edit().putBoolean("needSaveNickId", true).apply()
            isAnonymous = true
            sharedPreferences.getString("anonymousNickId", "").toString()
        }
        nickName = args.nickName
        userAvatar = args.headerIcon
        if (args.headerIcon.isNotEmpty()) {
            try {
                val iconUrl = Gson().fromJson<IconUrl>(args.headerIcon, IconUrl::class.java)
                userAvatar = if (iconUrl.url.isNotEmpty()) {
                    iconUrl
                } else {
                    ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        lbeIdentity = args.lbeIdentity
        initArgs = args
        configApiRepository = LbeConfigApiRepository(baseUrl = initArgs.domain.ifEmpty { BASE_URL })
        realInitSdk()
    }

    private fun realInitSdk() {
        val sdkJob = viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            try {
                println("NetworkMonitor [prepare]---->>> ${networkMonitor.isNetworkAvailable()}")
                if (!networkAvailable()) {
                    return@launch
                }
                fetchConfig()
                createSession()
                fetchSessionList()
                observerConnection()
//                fetchTimeoutConfig()
                faq(faqReqBody = FaqReqBody(faqType = 0, id = ""))
                sdkInit = true
                schedulePingJob()
            } catch (e: Exception) {
                e.printStackTrace()
                println("realInitSdk error: $e")
            }
        }
        jobs["sdkJob"] = sdkJob
    }

    private suspend fun reCreateSession() {
        try {
            createSession()
            allMessageSize = 0
            _uiState.postValue(_uiState.value?.copy(messages = mutableSetOf()))
            sessionList.clear()
            currentSessionIndex = 0
            fetchSessionList()
            observerConnection()
            schedulePingJob()
            faq(faqReqBody = FaqReqBody(faqType = 0, id = ""))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun networkAvailable(): Boolean {
        return networkMonitor.isNetworkAvailable()
    }

    private suspend fun <T> safeApiCall(apiCall: suspend () -> T): Result<T> {
        return try {
            val result = apiCall() ?: throw Exception("result is null")
            Result.success(result)
        } catch (e: HttpException) {
            println("SafeApiCall HTTP error: ${e.code()} - ${e.message()}")
            Result.failure(e)
        } catch (e: IOException) {
            println("SafeApiCall Network error: ${e.localizedMessage}")
            Result.failure(e)
        } catch (e: Exception) {
            println("SafeApiCall Unexpected error: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun fetchConfig() {
        val result = safeApiCall {
            configApiRepository.fetchConfig(lbeSign, lbeIdentity, ConfigBody(0, 1))
        }
        result.onSuccess { config ->
            Log.d(RETROFIT, "获取配置: $config")
            wssHost = config.data.ws[0]
            val imBaseUrl = config.data.rest[0]
            val ossBaseUrl = config.data.oss[0]
            imApiRepository = LbeImApiRepository(imBaseUrl)
            ossApiRepository = LbeOssApiRepository(ossBaseUrl)
        }.onFailure { err ->
            withContext(Dispatchers.Main) {
                Toast
                    .makeText(
                        getApplication(),
                        err.message,
                        Toast.LENGTH_SHORT
                    )
                    .show()
            }
            Log.d(RETROFIT, "获取配置异常: $err")
        }
    }

    private suspend fun createSession() {
        if (!networkAvailable()) {
            return
        }
        val language = initArgs.language.let {
            if (it == "0" || it.contains("zh")) "zh" 
            else if (it == "2" || it.contains("vi")) "vi"
            else "en"
        }
        val result = safeApiCall {
            imApiRepository?.createSession(
                lbeSign, lbeIdentity, SessionBody(
                    identityID = initArgs.lbeIdentity,
                    nickId = nickId,
                    nickName = nickName,
                    phone = initArgs.phone,
                    email = initArgs.email,
                    language = language,
                    device = initArgs.device,
                    source = initArgs.source,
                    headIcon = initArgs.headerIcon,
                    groupID = initArgs.groupID,
                    uid = "",
                    extraInfo = Gson().toJson(initArgs.extraInfo),
                )
            )
        }
        result.onSuccess { session ->
            Log.d(RETROFIT, "创建会话: $session")
            lbeToken = session!!.data.token
            lbeSession = session.data.sessionId
            uid = session.data.uid
            if (sharedPreferences.getBoolean("needSaveNickId", false)) {
                nickId = session.data.nickId
                sharedPreferences.edit().putString("anonymousNickId", session.data.nickId).apply()
                sharedPreferences.edit().putBoolean("needSaveNickId", false).apply()
            }
            endSession = false
        }.onFailure { error ->
            withContext(Dispatchers.Main) {
                Toast
                    .makeText(
                        getApplication(),
                        error.message,
                        Toast.LENGTH_SHORT
                    )
                    .show()
            }
            Log.d(RETROFIT, "创建会话异常: $error")
        }
    }

    private suspend fun fetchSessionList() {
        if (!networkAvailable()) {
            return
        }
        try {
            val result = safeApiCall {
                imApiRepository?.fetchSessionList(
                    lbeToken = lbeToken, lbeIdentity = lbeIdentity, body = SessionListReq(
                        pagination = Pagination(
                            pageNumber = 1, showNumber = 1000
                        ), sessionType = 2
                    )
                )
            }
            result.onSuccess { sessionListRep ->
                Log.d(RETROFIT, "会话列表: $sessionListRep")
                if (sessionListRep?.data?.sessionList?.isEmpty() == true) {
                    return
                }
                sessionList.addAll(sessionListRep!!.data.sessionList)
                currentSession = sessionList[currentSessionIndex]
                seq = currentSession?.latestMsg?.msgSeq ?: 0
                remoteLastMsgType = currentSession?.latestMsg?.msgType ?: 0
                turnCustomServiceState.value =
                    sessionListRep.data.sessionList.firstOrNull { it.sessionId == lbeSession }?.status?.let { it == 1 }
                        ?: false
                checkNeedSyncRemote()
                syncPageInfo(currentSession)
                filterLocalMessages()
                scrollToBottom()
                syncPendingJobs()
            }.onFailure { err ->
                Log.d(RETROFIT, "会话列表异常: $err")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadHistory() {
        if (currentSessionIndex >= sessionList.size - 1) {
            return
        }
        currentSessionIndex += 1
        currentSession = sessionList[currentSessionIndex]
        viewModelScope.launch(Dispatchers.IO) {
            checkNeedSyncRemote()
            syncPageInfo(currentSession)
            filterLocalMessages()
        }
    }

    private fun syncPageInfo(currentSession: SessionEntry?) {
        val cacheMessages = IMLocalRepository.filterMessages(currentSession?.sessionId ?: "")
        Log.d(
            REALM,
            "syncPageInfo cacheMessages size---->>> ${cacheMessages.size}, currentSession: ${currentSession}"
        )
        if (cacheMessages.isNotEmpty()) {
            currentSessionTotalPages = Math.max(cacheMessages.size / showPageSize, 1)
            currentPage = currentSessionTotalPages
            Log.d(
                REALM,
                "syncPageInfo ---->>> currentSessionTotalPages: $currentSessionTotalPages, currentPage: $currentPage"
            )
        } else {
            currentPage = 1
            currentSessionTotalPages = 1
        }
    }

    suspend fun checkNeedSyncRemote() {
        val cacheMessages = IMLocalRepository.filterMessages(currentSession?.sessionId ?: "")
        Log.d(
            REALM,
            "checkNeedSyncRemote --->>> cache size: ${cacheMessages.size} |  remote lastSeq: $seq , remoteLastMsgType: $remoteLastMsgType"
        )
        if (cacheMessages.size < seq) {
            fetchHistoryAndSync(currentSession)
        }
    }

    private fun afterSendUpdateList() {
        currentSessionIndex = 0
        currentSession = sessionList[currentSessionIndex]
        val cacheMessages = IMLocalRepository.filterMessages(currentSession?.sessionId ?: "")
        currentSessionTotalPages = cacheMessages.size / showPageSize
        currentPage = currentSessionTotalPages
        val subList = pagination(cacheMessages)
        viewModelScope.launch(Dispatchers.Main) {
            val messages = uiState.value?.messages?.toMutableList()?.also {
                it.clear()
                it.addAll(subList)
                it.sortBy { t -> t.sendTime }
            }
            allMessageSize = messages?.size ?: 0
            _uiState.postValue(messages?.let { _uiState.value?.copy(messages = it.toMutableSet()) })
            Log.d(
                REALM,
                "发送完更新列表 ---->>> messages: ${messages?.size}, allMessageSize: $allMessageSize, \n $messages"
            )
        }
    }

    fun filterLocalMessages(
        sid: String = currentSession?.sessionId ?: "",
    ) {
        Log.d(
            REALM,
            "分页 ---->>> currentSessionIndex: $currentSessionIndex ,sessionId: $sid, currentSessionTotalPages: $currentSessionTotalPages, currentPage: $currentPage, seq: $seq"
        )
        if ((currentSessionTotalPages != 0 && currentPage > currentSessionTotalPages) || currentPage < 1) return

        val cacheMessages = IMLocalRepository.filterMessages(sid)
        val subList = pagination(cacheMessages)

        viewModelScope.launch(Dispatchers.Main) {
            val messages = uiState.value?.messages?.toMutableList()?.also {
                it.addAll(0, subList)
                it.sortBy { t -> t.sendTime }
            }
            allMessageSize = messages?.size ?: 0
            _uiState.postValue(messages?.let { _uiState.value?.copy(messages = it.toMutableSet()) })
            Log.d(
                REALM,
                "分页后 messageList size --->> ${messages?.size}, allMessageSize: $allMessageSize"
            )
        }
    }

    private fun pagination(source: List<MessageEntity>): List<MessageEntity> {
        currentSessionTotalPages = source.size / showPageSize
        val yu = source.size % showPageSize
        Log.d(REALM, "分页总页数: $currentSessionTotalPages, currentPage: $currentPage, 取余: $yu")

        val subList = if (currentPage == 1 && yu != 0) {
            val start = Math.max((currentPage - 1) * showPageSize, 0)
            val end = Math.min(currentPage * showPageSize + yu, source.size)
            Log.d(REALM, "最后一页 --->>> currentPage: $currentPage, start: $start, end: $end")
            source.subList(start, end)
        } else {
            val start = Math.max(
                source.size - showPageSize * (currentSessionTotalPages - (currentPage - 1)), 0
            )
            val end = Math.min(start + showPageSize, source.size)
            Log.d(
                REALM,
                "非最后一页 --->>> source.size: ${source.size}, currentPage: $currentPage, start: $start, end: $end"
            )
            source.subList(start, end)
        }
        return subList
    }

    fun scrollToBottom() {
        toBottom.value += ","
    }

    fun resetRecivCount() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(99)
            recivCount.update { 0 }
        }
    }

//    private suspend fun fetchTimeoutConfig() {
//        if (!networkAvailable()) {
//            return
//        }
//        val result = safeApiCall {
//            imApiRepository?.fetchTimeoutConfig(
//                lbeSign = lbeSign,
//                lbeToken = lbeToken,
//                lbeIdentity = lbeIdentity,
//            )
//        }
//        result.onSuccess { timeoutConfig ->
//            timeOut = timeoutConfig!!.data.timeout
//            timeOutConfigOpen.update { timeoutConfig.data.isOpen }
//            Log.d(REALM, "FetchTimeoutConfig ---->>> $timeoutConfig, timeOut: $timeOut")
//        }.onFailure { err ->
//            println("网络异常 --->>> FetchTimeoutConfig error --->>>  $err")
//        }
//    }

    fun markRead(message: MessageEntity) {
        if (!networkAvailable()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            try {
                val markRead = imApiRepository?.markRead(
                    lbeSign = lbeSign,
                    lbeToken = lbeToken,
                    lbeIdentity = lbeIdentity,
                    body = MarkReadReqBody(sessionId = message.sessionId, seq = message.msgSeq)
                )
                Log.d(REALM, "MarkRead ---->>> $markRead")
                IMLocalRepository.findMsgAndMarkMeRead(message.clientMsgID)
            } catch (e: Exception) {
                println("Mark Msg Read error --->>>  $e")
            }
        }
    }

    fun faq(faqReqBody: FaqReqBody) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!networkAvailable()) {
                return@launch
            }
            delay(500)
            val result = safeApiCall {
                imApiRepository?.faq(
                    lbeSession = lbeSession,
                    lbeToken = lbeToken,
                    lbeIdentity = lbeIdentity,
                    body = faqReqBody
                )
            }
            result.onSuccess { faqResp ->
                Log.d(RETROFIT, "什么 --->> $faqResp")
                if (faqResp!!.code == 20006) {
                    faqNotExistEvent.value += ","
                }
            }
            result.onFailure { err ->
                println("网络异常 --->>> Fetch faq  error --->>>  $err")
            }
        }
    }

    fun turnCustomerService() {
        if (!sdkInit) {
            return
        }
        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            if (null == imApiRepository) {
                return@launch
            }
            if (endSession) {
                reCreateSession()
            }
            try {
                val resp = imApiRepository!!.turnCustomerService(
                    lbeSign = lbeSign,
                    lbeToken = lbeToken,
                    lbeIdentity = lbeIdentity,
                    lbeSession = lbeSession,
                )
                if (checkEndSession(resp.code)) {
                    return@launch
                }
            } catch (e: Exception) {
                Log.d(RETROFIT, "Fetch turnCSResp  error: $e")
            }
        }
    }

    fun endSession() {
        endCustomServiceDialog.value = false
        if (!sdkInit) {
            return
        }
        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            if (null == imApiRepository) {
                return@launch
            }
            try {
                imApiRepository!!.endSession(
                    lbeSign = lbeSign,
                    lbeToken = lbeToken,
                    lbeIdentity = lbeIdentity,
                    lbeSession = lbeSession,
                    body = SessionIdBody(sessionId = lbeSession)
                )
//                disConnection()
//                reCreateSession()
            } catch (e: Exception) {
                Log.d(RETROFIT, "Fetch turnCSResp  error: $e")
            }
        }
    }

    private suspend fun fetchHistoryAndSync(currentSession: SessionEntry?) {
        if (!networkAvailable()) {
            return
        }

        val result = safeApiCall {
            imApiRepository?.fetchHistory(
                lbeSign = lbeSign,
                lbeToken = lbeToken,
                lbeIdentity = lbeIdentity,
                body = HistoryBody(
                    sessionId = currentSession?.sessionId ?: "", seqCondition = SeqCondition(
                        startSeq = 0,
                        endSeq = currentSession?.latestMsg?.msgSeq ?: 0
                    )
                )
            )
        }

        result.onSuccess { history ->
            Log.d(RETROFIT, "会话历史: ${history!!.data.content.size}")
            Log.d(REALM, "History sync")
            if (history.data.content.isNotEmpty()) {
                seq = history.data.content.last().msgSeq
                for (content in history.data.content) {
                    when (content.msgType) {
                        1, 2, 4, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13,14 -> {
                            val entity = MessageEntity().apply {
                                sessionId = content.sessionId
                                senderUid = content.senderUid
                                msgBody = content.msgBody
                                clientMsgID = content.clientMsgID
                                msgType = content.msgType
                                sendTime = content.sendTime.toLong()
                                msgSeq = content.msgSeq
                                readed = (content.status == 1)
                                customerServiceNickname = content.senderNickname
                                customerServiceAvatar = content.senderFaceURL
                            }
                            IMLocalRepository.insertMessage(entity)
                        }

                        else -> {
                            continue
                        }
                    }
                }
            }
            syncPageInfo(currentSession)
        }.onFailure { err ->
            Log.d(RETROFIT, "会话历史异常: $err")
        }
    }

    private fun syncPendingJobs() {
        val pendingCache =
            IMLocalRepository.findAllPendingUploadMediaMessages(currentSession?.sessionId ?: "")
        Log.d(
            REALM,
            "PendingJobs --->>> ${pendingCache.map { cache -> "${cache.clientMsgID} || ${cache.uploadTask?.progress} " }}"
        )
        for (pending in pendingCache) {
            if (pending.uploadTask?.uploadStatus != UploadStatus.CHUNKS_MERGE_COMPLETED.name) {
                if (!pending.pendingUpload) {
                    viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                        IMLocalRepository.findMediaMsgPendingAndUpdateProgress(
                            pending.clientMsgID, pending.uploadTask
                        )
                        val msg = IMLocalRepository.findMsgByClientMsgId(pending.clientMsgID)
                        updateSingleMessage(source = msg) { m ->
                            m.uploadTask = pending.uploadTask
                            m.pendingUpload = true
                        }
                    }
                }
                progressList[pending.clientMsgID] =
                    MutableStateFlow(pending.uploadTask?.progress ?: 0.0f)
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun observerConnection() {
        try {
            if (wssHost.isEmpty()) {
                return
            }
            socketLifecycleRegistry?.onNext(Lifecycle.State.Destroyed)
            socketLifecycleRegistry = null
            socketLifecycleRegistry = LifecycleRegistry()
            val scarletInstance = Scarlet.Builder()
                .lifecycle(socketLifecycleRegistry!!)
                /// 重连策略 3 秒重连
                .backoffStrategy(ExponentialBackoffStrategy(3000L, 5000L))
                .webSocketFactory(
                    okHttpClient.newWebSocketFactory(
                        DynamicHeaderUrlRequestFactory(
                            url = wssHost, lbeToken = lbeToken, lbeSession = lbeSession,
                        )
                    )
                ).addStreamAdapterFactory(RxJava2StreamAdapterFactory()).build()

            chatService = scarletInstance.create<ChatService>()
            Log.d(TAG, "Observing Connection")
            updateConnectionStatus(ConnectionStatus.CONNECTING)

            wsSubs = chatService?.observeConnection()?.subscribe(
                { response ->
                    onResponseReceived(response)
                },
                { error ->
                    error.localizedMessage?.let { Log.e(TAG, "websocket 出错 ---->>> $it") }
                },
            )
            socketLifecycleRegistry?.onNext(Lifecycle.State.Started)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onResponseReceived(response: WebSocket.Event) {
        Log.d(TAG, "webSocket response --->>> $response")

        when (response) {
            is OnConnectionOpened<*> -> updateConnectionStatus(ConnectionStatus.OPENED)

            is OnConnectionClosed -> updateConnectionStatus(ConnectionStatus.CLOSED)

            is OnConnectionClosing -> updateConnectionStatus(ConnectionStatus.CLOSING)

            is OnConnectionFailed -> updateConnectionStatus(ConnectionStatus.FAILED)

            is OnMessageReceived -> handleOnMessageReceived(response.message)
        }
    }

    private fun handleOnMessageReceived(message: Message) {
        try {
            val value = (message as Message.Bytes).value
            viewModelScope.launch {
                val msgEntity = IMMsg.MsgEntityToFrontEnd.parseFrom(value)
                Log.d(TAG, "handleOnMessageReceived protobuf bytes --->>>  $msgEntity")
                fileLogger.log(TAG, "handleOnMessageReceived protobuf bytes --->>>  $msgEntity")

                if (msgEntity.msgBody.senderUid == "111") {
                    return@launch
                }

                when (msgEntity.msgType) {
                    IMMsg.MsgType.TextMsgType -> {
                        remoteLastMsgType = protoTypeConvert(msgEntity)

                        val receivedSeq = msgEntity.msgBody.msgSeq
                        println("接收转人工系统消息 --->> remoteLastMsgType: $remoteLastMsgType ,receivedSeq: $receivedSeq, seq: $seq")
                        if (remoteLastMsgType == 1 || remoteLastMsgType == 2 || remoteLastMsgType == 3 || remoteLastMsgType == 8 || remoteLastMsgType == 9 || remoteLastMsgType == 10 || remoteLastMsgType == 12) {
                            if (receivedSeq - seq > 2) {
                                fetchHistoryAndSync(sessionList[0])
                            }
                            seq = receivedSeq
                            recivCount.value += 1
                        }
                        if (remoteLastMsgType == 5) {
                            turnCustomServiceState.value = true
                        }
                        if (remoteLastMsgType == 6) {
                            turnCustomServiceState.value = false
                        }
                        Log.d(
                            TAG, "收到消息 --->> seq: $seq, remoteLastMsgType: $remoteLastMsgType"
                        )
                        val entity = protoToEntity(
                            lbeSession,
                            msgEntity
                        )
                        println("接收转人工系统消息 --->>> $entity")

                        lastCsMessage = entity
//                        if (timeOutConfigOpen.value) {
//                            scheduleTimeoutJob()
//                        }
                        viewModelScope.launch {
                            IMLocalRepository.insertMessage(entity)
                            if (entity.senderUid != uid) {
                                addSingleMsgToUI(entity)
                                recived.value += ","
                            }
                        }
                    }

                    IMMsg.MsgType.HasReadReceiptMsgType -> {
                        val hasReadMsg = msgEntity.hasReadReceiptMsg
                        val sessionId = hasReadMsg.sessionID
                        val markReadList = hasReadMsg.hasReadSeqsList

                        Log.d(
                            TAG,
                            "收到客服标记已读消息 --->> sessionId: $sessionId, markReadList: $markReadList}"
                        )
                        for (seq in markReadList) {
                            IMLocalRepository.findMsgAndMarkCsRead(sessionId, seq.toInt())
                        }
                        markMsgReadFromUI(sessionId, markReadList)
                    }

                    IMMsg.MsgType.EndSessionMsgType -> {
                        endSession = true
                    }

                    IMMsg.MsgType.KickOffLineMsgType -> {
//                        disConnection()
                        kickOfflineEvent.value += ","
                        kickOffLine.update { true }
                    }

                    else -> {
                        Log.d(TAG, "Not Impl yet ---> ${msgEntity.msgType}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleOnMessageReceived: ", e)
        }
    }

    private fun schedulePingJob() {
        val period = 1000 * 15L
        val toServer = IMMsg.MsgEntityToServer.newBuilder().setMsgType(IMMsg.MsgType.TextMsgType)
            .setMsgBody(IMMsg.MsgBody.newBuilder().setMsgBody("ping").build()).build()
        pingTimer?.cancel()
        pingTimer = Timer()
        pingTimer?.schedule(object : TimerTask() {
            override fun run() {
                val sendStatus = chatService?.sendMessage(toServer.toByteArray())
                Log.d("Ping Job", "$toServer ---->>> $sendStatus")
                fileLogger.log("Ping Job", "$toServer ---->>> $sendStatus")
            }
        }, period, period)
    }

//    private fun scheduleTimeoutJob() {
//        val period = 1000 * 60 * timeOut
//        Log.d("TimeOut", "超时提醒，period: $period")
//        timeOutTimer?.cancel()
//        timeOutTimer = Timer()
//        timeOutTimer?.schedule(object : TimerTask() {
//            override fun run() {
//                if (lastCsMessage?.msgSeq!! <= seq) {
//                    generateLocalTimeOutMessage()
//                    Log.d(
//                        "TimeOut",
//                        "客服回复重启，用户没回复， seq: $seq, last: ${lastCsMessage?.msgSeq}"
//                    )
//                    isTimeOut.update { _ -> true }
//                    timeOutTimer?.cancel()
//                    timeOutTimer = null
//                }
//            }
//        }, period)
//    }

    /// 超时处理，只保留在本地
//    fun generateLocalTimeOutMessage() {
//        viewModelScope.launch(Dispatchers.IO) {
//            val sendBody = genMsgBody(type = CustomMessageType.TYPE_TIME_OUT_REPLY, msgBody = "")
//            insertCacheMaybeUpdateUI(sendBody, localFile = null, updateUI = false)
//        }
//    }

    private fun updateConnectionStatus(connectionStatus: ConnectionStatus) {
        Log.d(TAG, "websocket update status --->>> ${connectionStatus.name}")
        fileLogger.log(TAG, "websocket update status --->>> ${connectionStatus.name}")
        if (connectionStatus == ConnectionStatus.OPENED) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(2.seconds)
                sessionList.clear()
                currentSessionIndex = 0
                fetchSessionList()
            }
        }

//        viewModelScope.launch(Dispatchers.Main) {
//            _uiState.postValue(_uiState.value?.copy(connectionStatus = connectionStatus))
//        }
    }

    // 检查是否结束会话
    private fun checkEndSession(code: Int): Boolean {
        if (code != 20003) {
            return false
        }
        viewModelScope.launch(Dispatchers.IO) {
            reCreateSession()
        }
        return true
    }

    fun onMessageChange(message: String) {
        _inputMsg.postValue(message)
    }

    fun sendMessageFromTextInput(messageSent: () -> Unit, trimToast: () -> Unit) {
        if ((_inputMsg.value?.trim() ?: "").isEmpty()) {
            _inputMsg.postValue("")
            trimToast()
            messageSent()
            return
        }

        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            if (endSession) {
                reCreateSession()
            }

            val sendBody = genMsgBody(type = 1, msgBody = _inputMsg.value ?: "")
            send(
                messageSent = messageSent,
                preSend = {
                    insertCacheMaybeUpdateUI(sendBody, localFile = null, updateUI = false)
                },
                sendBody,
            )
            clearInput()
        }
    }

    private fun senMessageFromMedia(msgBody: MsgBody, preSend: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            if (endSession) {
                reCreateSession()
                send(messageSent = {}, preSend = preSend, msgBody = msgBody)
            }
        }
    }

    private fun send(messageSent: () -> Unit, preSend: () -> Unit, msgBody: MsgBody) {
        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            preSend()
            imApiRepository ?: return@launch
            try {
                val senMsg = imApiRepository!!.sendMsg(
                    lbeToken = lbeToken,
                    lbeIdentity = lbeIdentity,
                    lbeSession = lbeSession,
                    body = msgBody
                )
                if (checkEndSession(senMsg.code.toInt())) {
                    return@launch
                }
                senMsg.data ?: return@launch
                seq = senMsg.data.msgReq
//                if (lastCsMessage != null) {
//                    Log.d("TimeOut", "seq: $seq, lastCsSeq: ${lastCsMessage!!.msgSeq}")
//                    if (seq > (lastCsMessage?.msgSeq ?: 0)) {
//                        isTimeOut.update { false }
//                        timeOutTimer?.cancel()
//                        timeOutTimer = null
//                    }
//                }
                remoteLastMsgType = msgBody.msgType
                IMLocalRepository.findMsgAndSetSeq(msgBody.clientMsgId, seq)
            } catch (e: Exception) {
                println("send error -->> $e")
                IMLocalRepository.findMsgAndSetStatus(msgBody.clientMsgId, false)
            } finally {
                afterSendUpdateList()
                scrollToBottom()
                viewModelScope.launch(Dispatchers.Main) {
                    messageSent()
                    clearInput()
                }
            }
        }
    }

    private fun genMsgBody(type: Int, msgBody: String = ""): MsgBody {
        val uuid = uuidGen()
        val timeStamp = timeStampGen()
        val clientMsgId = "${uuid}-${timeStamp}"
        val body = MsgBody(
            msgBody = msgBody,
            msgSeq = seq,
            msgType = type,
            clientMsgId = clientMsgId,
            source = 100,
            sendTime = timeStamp.toString()
        )
        return body
    }

    fun reSendMessage(clientMsgId: String) {
        if (!networkAvailable()) {
            return
        }

        val entity = IMLocalRepository.findMsgByClientMsgId(clientMsgId)
        var newClientMsgId: String
        if (entity != null) {
            val list = entity.clientMsgID.split("-").toMutableList()
            list.removeLastOrNull()
            list.add(timeStampGen().toString())
            newClientMsgId = list.joinToString(separator = "-")
            val body = entityToSendBody(entity, newClientMsgId)
            println("reSend ====>>> old: ${entity.clientMsgID}, new: $newClientMsgId")
            viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                try {
                    imApiRepository ?: return@launch
                    val senMsg = imApiRepository!!.sendMsg(
                        lbeToken = lbeToken,
                        lbeSession = lbeSession,
                        lbeIdentity = lbeIdentity,
                        body = body
                    )
                    if (checkEndSession(senMsg.code.toInt())) {
                        return@launch
                    }
                    senMsg.data ?: return@launch
                    seq = senMsg.data.msgReq
                    IMLocalRepository.updateResendMessage(clientMsgId, newClientMsgId, seq)
                } catch (e: Exception) {
                    println("reSend error -->> $e")
                } finally {
                    afterSendUpdateList()
                    scrollToBottom()
                }
            }
        }
    }

    private fun genTempUploadInfo(message: MessageEntity): TempUploadInfo {
        val file = File(message.localFile?.path ?: "")
        return TempUploadInfo(
            sendBody = entityToMediaSendBody(message), mediaMessage = MediaMessage(
                fileName = message.localFile?.fileName ?: "",
                path = message.localFile?.path ?: "",
                width = message.localFile?.width ?: 0,
                height = message.localFile?.height ?: 0,
                isImage = message.msgType == 2,
                mime = message.localFile?.mimeType ?: "",
                file = file,
                fileSize = file.length()
            )
        )
    }

    fun preInsertUpload(mediaMessage: MediaMessage) {
        val sendBody = genMsgBody(
            type = if (mediaMessage.isImage) 2 else 3,
        )

        val localFile = genLocalFile(mediaMessage)
        localFile.isBigFile = mediaMessage.file.length() > UploadBigFileUtils.defaultChunkSize

        val entity = insertCacheMaybeUpdateUI(
            sendBody = sendBody, localFile = localFile
        )
        tempUploadInfos[entity.clientMsgID] =
            TempUploadInfo(sendBody = sendBody, mediaMessage = mediaMessage)
    }

    fun upload(
        message: MessageEntity, thumbBitmap: Bitmap
    ) {
        Log.d(UPLOAD, "upload file size---->>> ${message.localFile?.size}")
        if (!networkAvailable()) {
            return
        }
        message.localFile?.size?.let {
            if (it > UploadBigFileUtils.defaultChunkSize) {
                bigFileUpload(message, thumbBitmap)
            } else {
                singleUpload(message, thumbBitmap)
            }
        }
    }

    private fun singleUpload(
        message: MessageEntity, thumbBitmap: Bitmap
    ) {
        val tempUploadInfo = tempUploadInfos[message.clientMsgID]
        val thumbWidth = thumbBitmap.width
        val thumbHeight = thumbBitmap.height
        tempUploadInfo?.let {
            viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                ossApiRepository ?: return@launch
                try {
                    val thumbnailResp = uploadThumbnail(thumbBitmap)
                    val rep = ossApiRepository!!.singleUpload(
                        file = MultipartBody.Part.createFormData(
                            "file",
                            it.mediaMessage.fileName,
                            ProgressRequestBody(
                                delegate = it.mediaMessage.file.asRequestBody(),
                                listener = { bytesWritten, contentLength ->
                                    val progress = (1.0 * bytesWritten) / contentLength
                                    Log.d(
                                        UPLOAD,
                                        "Single upload  ${it.mediaMessage.fileName} ---->>>  bytesWritten: $bytesWritten, $contentLength, progress: $progress"
                                    )
                                    val emitProgress = progressList[message.clientMsgID]
                                    if (emitProgress != null) {
                                        viewModelScope.launch(Dispatchers.Main) {
                                            emitProgress.value = progress.toFloat()
                                        }

                                        if (emitProgress.value == 1.0f) {
                                            val uploadTask = UploadTask()
                                            uploadTask.progress = 1.0f
                                            viewModelScope.launch(Dispatchers.IO) {
                                                IMLocalRepository.findMediaMsgAndUpdateProgress(
                                                    message.clientMsgID, uploadTask
                                                )
                                            }
                                        }
                                    }
                                })
                        ), signType = if (it.mediaMessage.isImage) 2 else 1, token = lbeToken
                    )
                    Log.d(UPLOAD, "Single upload ---->>> ${rep.data.paths[0]}")
                    val mediaSource = MediaSource(
                        width = thumbWidth, height = thumbHeight, thumbnail = Thumbnail(
                            url = thumbnailResp.data.paths[0].url,
                            key = thumbnailResp.data.paths[0].key
                        ), resource = Resource(
                            url = rep.data.paths[0].url, key = rep.data.paths[0].key
                        )
                    )
                    it.sendBody.msgBody = Gson().toJson(mediaSource)
                    senMessageFromMedia(it.sendBody, preSend = {
                        viewModelScope.launch(Dispatchers.IO) {
                            IMLocalRepository.findMediaMsgAndUpdateBody(
                                message.clientMsgID, it.sendBody.msgBody
                            )
                        }
                    })
                } catch (e: Exception) {
                    Log.d(UPLOAD, "Single upload error --->> $e")
                }
            }
        }
    }

    private fun insertCacheMaybeUpdateUI(
        sendBody: MsgBody, localFile: LocalMediaFile?, updateUI: Boolean = true
    ): MessageEntity {
        val entity = sendBodyToEntity(
            lbeSession,
            uid,
            seq,
            sendBody
        )
        if (localFile != null) {
            entity.localFile = localFile
        }
        viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            IMLocalRepository.insertMessage(entity)
            if (sessionList.isNotEmpty()) {
                syncPageInfo(sessionList[0])
            }
            if (updateUI) {
                afterSendUpdateList()
                scrollToBottom()
                progressList[entity.clientMsgID] = MutableStateFlow(0.0f)
            }
        }
        return entity
    }

    private fun genLocalFile(mediaMessage: MediaMessage): LocalMediaFile {
        val localFile = LocalMediaFile()
        localFile.fileName = mediaMessage.fileName
        localFile.path = mediaMessage.path
        localFile.size = mediaMessage.fileSize
        localFile.mimeType = mediaMessage.mime
        localFile.width = mediaMessage.height
        localFile.height = mediaMessage.width
        return localFile
    }

    private suspend fun updateUploadStatus(clientMsgID: String, uploadTask: UploadTask?) {
        IMLocalRepository.findMediaMsgAndUpdateProgress(clientMsgID, uploadTask)
        updateSingleMessage(IMLocalRepository.findMsgByClientMsgId(clientMsgID)) { m ->
            m.uploadTask = uploadTask
        }
    }

    private fun bigFileUpload(
        message: MessageEntity, thumbBitmap: Bitmap
    ) {
        ossApiRepository ?: return
        try {
            val tempUploadInfo = tempUploadInfos[message.clientMsgID]
            val thumbWidth = thumbBitmap.width
            val thumbHeight = thumbBitmap.height

            tempUploadInfo?.let { it ->
                it.thumbWidth = thumbWidth
                it.thumbHeight = thumbHeight
                var executeIndex = 0
                val uploadTask = UploadTask()
                uploadTask.executeIndex = executeIndex
                uploadTasks[message.clientMsgID] = uploadTask

                mergeMultiUploadReqQueue[message.clientMsgID] = CompleteMultiPartUploadReq(
                    uploadId = "", name = it.mediaMessage.fileName, part = mutableListOf()
                )

                val job = viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                    val thumbnailResp = uploadThumbnail(thumbBitmap)
                    val thumbnailSource = MediaSource(
                        width = thumbWidth, height = thumbHeight, thumbnail = Thumbnail(
                            url = thumbnailResp.data.paths[0].url,
                            key = thumbnailResp.data.paths[0].key
                        ), resource = Resource(
                            url = "", key = ""
                        )
                    )

                    uploadTask.uploadStatus = UploadStatus.THUMBNAIL_UPLOADED.name
                    updateUploadStatus(message.clientMsgID, uploadTask)

                    it.sendBody.msgBody = Gson().toJson(thumbnailSource)
                    IMLocalRepository.findMediaMsgAndUpdateBody(
                        message.clientMsgID, it.sendBody.msgBody
                    )
                    updateSingleMessage(source = message) { m ->
                        m.msgBody = it.sendBody.msgBody
                    }
                    scrollToBottom()
                    val initRep = ossApiRepository!!.initMultiPartUpload(
                        body = InitMultiPartUploadBody(
                            size = it.mediaMessage.fileSize,
                            name = it.mediaMessage.fileName,
                            contentType = ""
                        ), token = lbeToken
                    )
                    Log.d(UPLOAD, "init multi upload --->>> $initRep")

                    uploadTask.initTrunksRepJson = Gson().toJson(initRep)
                    val start = System.currentTimeMillis()
                    Log.d(
                        UPLOAD,
                        "Big file upload ---->>> fileName: ${it.mediaMessage.fileName}, Fs hash: ${it.mediaMessage.path}, split start: $start"
                    )

                    uploadTask.uploadStatus = UploadStatus.CHUNKS_INIT.name
                    updateUploadStatus(message.clientMsgID, uploadTask)

                    if (initRep.data.node.size > 1) {
                        UploadBigFileUtils.splitFile(
                            it.mediaMessage.file, UploadBigFileUtils.defaultChunkSize
                        )
                    } else {
                        UploadBigFileUtils.splitFile(
                            it.mediaMessage.file, initRep.data.node[0].size
                        )
                    }
                    val end = System.currentTimeMillis()
                    Log.d(UPLOAD, "split end: $end, diff: ${end - start}")

                    val taskLength = initRep.data.node.size
                    uploadTasks[message.clientMsgID]?.taskLength = taskLength

                    mergeMultiUploadReqQueue[message.clientMsgID] = CompleteMultiPartUploadReq(
                        uploadId = initRep.data.uploadId,
                        name = it.mediaMessage.fileName,
                        part = mutableListOf()
                    )
                    val buffers = UploadBigFileUtils.blocks[it.mediaMessage.file.hashCode()]

                    uploadTask.uploadStatus = UploadStatus.UPLOADING.name
                    updateUploadStatus(message.clientMsgID, uploadTask)

                    if (buffers != null) {
                        var deltaSize = 0L
                        for (buffer in buffers) {
                            val md5 = MessageDigest.getInstance("MD5")
                            val sign = md5.digest(buffer.array())
                            val hexString = sign.joinToString("") { "%02x".format(it) }
                            Log.d(
                                UPLOAD,
                                "split chunk size: ${buffer.array().size}, hexString: $hexString"
                            )

                            val bodyFromBuffer =
                                ProgressRequestBody(
                                    delegate = buffer.array().toRequestBody(
                                        contentType = "application/octet-stream".toMediaTypeOrNull(),
                                        byteCount = buffer.array().size
                                    ), listener = { bytesWritten, _ ->
                                        val totalProgress =
                                            (1.0 * (deltaSize + bytesWritten)) / it.mediaMessage.fileSize
//                                        val currentBlockProgress =
//                                            (1.0 * bytesWritten) / contentLength

                                        val emitProgress = progressList[message.clientMsgID]
                                        if (emitProgress != null) {
                                            viewModelScope.launch(Dispatchers.Main) {
                                                emitProgress.value = totalProgress.toFloat()
                                            }

                                            uploadTask.reqBodyJson =
                                                Gson().toJson(mergeMultiUploadReqQueue[message.clientMsgID])
                                            uploadTask.progress = emitProgress.value
                                            viewModelScope.launch(Dispatchers.IO) {
                                                IMLocalRepository.findMediaMsgAndUpdateProgress(
                                                    message.clientMsgID, uploadTask
                                                )
                                            }

                                            if (emitProgress.value == 1.0f) {
                                                uploadTask.progress = 1.0f
                                            }
                                        }
                                    })
                            ossApiRepository!!.uploadBinary(
                                url = initRep.data.node[buffers.indexOf(buffer)].url, bodyFromBuffer
                            )

                            mergeMultiUploadReqQueue[message.clientMsgID]?.part?.add(
                                Part(
                                    partNumber = executeIndex + 1, etag = hexString
                                )
                            )
                            uploadTasks[message.clientMsgID]?.executeIndex = executeIndex
                            deltaSize += buffer.array().size
                            executeIndex++
                        }
                    }
                    Log.d(UPLOAD, "iter --->> ${mergeMultiUploadReqQueue[message.clientMsgID]}")
                    val mergeUpload =
                        mergeMultiUploadReqQueue[message.clientMsgID]?.let { reqBody ->
                            ossApiRepository!!.completeMultiPartUpload(
                                body = reqBody, token = lbeToken
                            )
                        }

                    uploadTask.uploadStatus = UploadStatus.CHUNKS_MERGE_COMPLETED.name
                    updateUploadStatus(message.clientMsgID, uploadTask)

                    UploadBigFileUtils.releaseMemory(it.mediaMessage.file.hashCode())
                    Log.d(UPLOAD, "BigFileUpload success ---> ${mergeUpload?.data?.location}")
                    val mediaSource = MediaSource(
                        width = thumbWidth, height = thumbHeight, thumbnail = Thumbnail(
                            url = thumbnailResp.data.paths[0].url,
                            key = thumbnailResp.data.paths[0].key
                        ), resource = Resource(
                            url = mergeUpload?.data?.location ?: "", key = ""
                        )
                    )
                    it.sendBody.msgBody = Gson().toJson(mediaSource)
                    senMessageFromMedia(it.sendBody, preSend = {
                        viewModelScope.launch(Dispatchers.IO) {
                            IMLocalRepository.findMediaMsgAndUpdateBody(
                                message.clientMsgID, it.sendBody.msgBody
                            )
                        }
                    })
                }
                jobs[message.clientMsgID] = job
            }
        } catch (e: Exception) {
            Log.d(UPLOAD, "Big file upload error --->>> $e")
        }
    }

    private fun generateThumbnail(message: MessageEntity, context: Context): Bitmap? {
        return try {
            val uri = Uri.parse(message.localFile?.path)
            when {
                message.msgType == 3 -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            retriever.setDataSource(context, uri)
                        } else {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                retriever.setDataSource(pfd.fileDescriptor)
                            }
                        }

                        val duration =
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLong() ?: 0
                        val frameTime = (duration * 1000) / 3

                        val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                            retriever.getFrameAtTime(
                                frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: retriever.getFrameAtTime(
                                0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )
                        } else {
                            retriever.getFrameAtTime(frameTime) ?: retriever.getFrameAtTime(0)
                        }

                        bitmap?.let {
                            val width = it.width
                            val height = it.height
                            val ratio = width.toFloat() / height.toFloat()
                            val targetWidth = 300
                            val targetHeight = (targetWidth / ratio).toInt()
                            Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true)
                                .also { scaled ->
                                    if (scaled != it) {
                                        it.recycle()
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        Log.e("Thumbnail", "生成视频缩略图失败: ${e.message}")
                        null
                    } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) {
                            Log.e("Thumbnail", "释放 MediaMetadataRetriever 失败: ${e.message}")
                        }
                    }
                }

                message.msgType == 2 -> {
                    try {
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BitmapFactory.decodeStream(input, null, options)
                        }

                        val width = options.outWidth
                        val height = options.outHeight
                        val ratio = width.toFloat() / height.toFloat()
                        val targetWidth = 300
                        val sampleSize =
                            Math.max(1, Math.ceil(width.toDouble() / targetWidth).toInt())

                        options.apply {
                            inJustDecodeBounds = false
                            inSampleSize = sampleSize
                        }

                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bitmap = BitmapFactory.decodeStream(input, null, options)
                            bitmap?.let {
                                val targetHeight = (targetWidth / ratio).toInt()
                                Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true)
                                    .also { scaled ->
                                        if (scaled != it) {
                                            it.recycle()
                                        }
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Thumbnail", "生成图片缩略图失败: ${e.message}")
                        null
                    }
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e("Thumbnail", "生成缩略图失败: ${e.message}")
            null
        }
    }

    fun continueSplitTrunksUpload(
        message: MessageEntity, file: File, context: Context
    ) {
        try {
            if (!networkAvailable()) {
                return
            }
            ossApiRepository ?: return
            val job = viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                IMLocalRepository.findMediaMsgSetUploadContinue(message.clientMsgID)
                updateSingleMessage(source = message) { m ->
                    m.pendingUpload = false
                    m.uploadTask = message.uploadTask
                }

                val uploadTask = message.uploadTask
                val newTask = UploadTask()
                if (uploadTask != null) {
                    newTask.executeIndex = uploadTask.executeIndex
                    newTask.taskLength = uploadTask.taskLength
                    newTask.progress = uploadTask.progress
                    newTask.reqBodyJson = uploadTask.reqBodyJson
                    newTask.initTrunksRepJson = uploadTask.initTrunksRepJson
                    newTask.lastTrunkUploadLength = uploadTask.lastTrunkUploadLength
                    newTask.uploadStatus = uploadTask.uploadStatus
                }
                uploadTasks[message.clientMsgID] = newTask

                if (newTask.uploadStatus == UploadStatus.INIT.name || newTask.uploadStatus == UploadStatus.THUMBNAIL_UPLOADED.name || newTask.uploadStatus == UploadStatus.CHUNKS_INIT.name) {
                    val thumbBitmap = generateThumbnail(message, context)
                    val tempUploadInfo = genTempUploadInfo(message)
                    tempUploadInfos[message.clientMsgID] = tempUploadInfo
                    if (thumbBitmap != null) {
                        bigFileUpload(message, thumbBitmap)
                    }
                    return@launch
                }

                mergeMultiUploadReqQueue[message.clientMsgID] =
                    Gson().fromJson(newTask.reqBodyJson, CompleteMultiPartUploadReq::class.java)

                var executeIndex = mergeMultiUploadReqQueue[message.clientMsgID]?.part?.size ?: 0

                val initRep = Gson().fromJson(
                    newTask.initTrunksRepJson, InitMultiPartUploadRep::class.java
                )

                if (newTask.taskLength > 1) {
                    UploadBigFileUtils.splitFile(file, UploadBigFileUtils.defaultChunkSize)
                } else {
                    UploadBigFileUtils.splitFile(file, initRep.data.node[0].size)
                }

                val buffers = UploadBigFileUtils.blocks[file.hashCode()]

                if (buffers != null) {
                    var deltaSize = 0L
                    var tempIndex = executeIndex
                    for (buffer in buffers) {
                        if (tempIndex != 0) {
                            Log.d(CONTINUE_UPLOAD, "jump tempIndex: $tempIndex")
                            deltaSize += buffer.array().size
                            tempIndex--
                            continue
                        }

                        val md5 = MessageDigest.getInstance("MD5")
                        val sign = md5.digest(buffer.array())
                        val hexString = sign.joinToString("") { "%02x".format(it) }
                        Log.d(
                            UPLOAD,
                            "split chunk size: ${buffer.array().size}, hexString: $hexString"
                        )

                        val bodyFromBuffer =
                            ProgressRequestBody(
                                delegate = buffer.array().toRequestBody(
                                    contentType = "application/octet-stream".toMediaTypeOrNull(),
                                    byteCount = buffer.array().size
                                ), listener = { bytesWritten, contentLength ->
                                    val totalProgress =
                                        ((1.0 * (deltaSize + bytesWritten)) / message.localFile?.size!!)
//                                val currentTrunkProgress = (1.0 * bytesWritten) / contentLength

                                    val emitProgress = progressList[message.clientMsgID]
                                    if (emitProgress != null) {
                                        viewModelScope.launch(Dispatchers.Main) {
                                            if (totalProgress.toFloat() >= emitProgress.value) {
                                                emitProgress.value = totalProgress.toFloat()
                                            }
                                        }

                                        newTask.progress = emitProgress.value
                                        newTask.reqBodyJson =
                                            Gson().toJson(mergeMultiUploadReqQueue[message.clientMsgID])
                                        viewModelScope.launch(Dispatchers.IO) {
                                            IMLocalRepository.findMediaMsgAndUpdateProgress(
                                                message.clientMsgID, newTask
                                            )
                                        }
                                        if (emitProgress.value == 1.0f) {
                                            newTask.progress = 1.0f
                                        }
                                    }
                                })

                        val bIndex = buffers.indexOf(buffer)
                        Log.d(CONTINUE_UPLOAD, "分块上传 index --->>> $bIndex")
                        ossApiRepository!!.uploadBinary(
                            url = initRep.data.node[bIndex].url, bodyFromBuffer
                        )

                        Log.d(CONTINUE_UPLOAD, "合 part executeIndex --->>> $executeIndex")
                        mergeMultiUploadReqQueue[message.clientMsgID]?.part?.add(
                            Part(
                                partNumber = executeIndex + 1, etag = hexString
                            )
                        )
                        uploadTasks[message.clientMsgID]?.executeIndex = executeIndex
                        deltaSize += buffer.array().size
                        executeIndex++
                    }
                }

                val reqBody = mergeMultiUploadReqQueue[message.clientMsgID]
                Log.d(UPLOAD, "merge reqBody --->> $reqBody")
                if (reqBody != null) {
                    val mergeUpload = ossApiRepository!!.completeMultiPartUpload(
                        body = reqBody, token = lbeToken
                    )

                    UploadBigFileUtils.releaseMemory(file.hashCode())
                    Log.d(
                        UPLOAD, "BigFileUpload 断点续传 merge success ---> $mergeUpload"
                    )
                    val cacheMediaSource = Gson().fromJson(message.msgBody, MediaSource::class.java)
                    val mediaSource = MediaSource(
                        width = tempUploadInfos[message.clientMsgID]?.thumbWidth ?: 100,
                        height = tempUploadInfos[message.clientMsgID]?.thumbHeight ?: 100,
                        thumbnail = Thumbnail(
                            url = cacheMediaSource.thumbnail.url,
                            key = cacheMediaSource.thumbnail.key
                        ),
                        resource = Resource(
                            url = mergeUpload.data.location, key = ""
                        )
                    )
                    val sendBody = entityToMediaSendBody(message)
                    sendBody.msgBody = Gson().toJson(mediaSource)
                    senMessageFromMedia(sendBody, preSend = {
                        viewModelScope.launch(Dispatchers.IO) {
                            IMLocalRepository.findMediaMsgAndUpdateBody(
                                sendBody.clientMsgId, sendBody.msgBody
                            )
                        }
                    })
                }
            }
            jobs[message.clientMsgID] = job
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pendingUpload(clientMsgId: String, progress: State<Float>?) {
        val job = jobs[clientMsgId]
        job?.cancel()
        progress?.let {
            viewModelScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                Log.d(CONTINUE_UPLOAD, "暂停上传进度: ${it.value}")
                val mergeReq = mergeMultiUploadReqQueue[clientMsgId]
                val uploadTask = uploadTasks[clientMsgId]
                Log.d(CONTINUE_UPLOAD, "暂停截取 mergeReq ---->>> $mergeReq")
                Log.d(CONTINUE_UPLOAD, "暂停截取 uploadTask ---->>> $uploadTask")
                uploadTask?.progress = progress.value
                uploadTask?.reqBodyJson = Gson().toJson(mergeReq)
                IMLocalRepository.findMediaMsgPendingAndUpdateProgress(
                    clientMsgId,
                    uploadTask = uploadTask
                )
                val msg = IMLocalRepository.findMsgByClientMsgId(clientMsgId)
                updateSingleMessage(source = msg) { m ->
                    m.uploadTask = uploadTask
                    m.pendingUpload = true
                }
            }
        }
    }

    private fun updateSingleMessage(
        source: MessageEntity?, callback: (msg: MessageEntity) -> Unit
    ) {
        val messages = uiState.value?.messages?.toMutableList()
        val cacheMsg = messages?.find { it.clientMsgID == source?.clientMsgID }
        if (cacheMsg != null) {
            val index = messages.indexOf(cacheMsg)
            val newMsg = MessageEntity.copy(cacheMsg)
            callback(newMsg)
            messages.removeAt(index)
            messages.add(index, newMsg)
            Log.d(
                REALM,
                "updateSingleMessage 查找到 msg, 并更新 --->>> old: $cacheMsg, \n new: $newMsg"
            )
        }
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.postValue(messages?.let { _uiState.value?.copy(messages = it.toMutableSet()) })
        }
    }

    private suspend fun uploadThumbnail(
        bitmap: Bitmap
    ): SingleUploadRep {
        val bao = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bao)
        val buffer = bao.toByteArray()
        val thumbnailResp = ossApiRepository!!.singleUpload(
            file = MultipartBody.Part.createFormData(
                "file", "lbe_${uuidGen()}_${timeStampGen()}.jpg", buffer.toRequestBody()
            ), signType = 2, token = lbeToken
        )
        withContext(Dispatchers.IO) {
            bao.close()
        }
        Log.d(UPLOAD, "thumbnail upload ---->>> ${thumbnailResp.data.paths[0]}")
        return thumbnailResp
    }

    private fun addSingleMsgToUI(message: MessageEntity) {
        Log.d(TAG, "addSingleMsgToUI: $message")
        val messages = uiState.value?.messages
        messages?.add(message)
        allMessageSize = messages?.size ?: 0
        _uiState.postValue(messages?.let { _uiState.value?.copy(messages = it) })
    }

    private fun markMsgReadFromUI(sessionId: String, seqs: MutableList<Long>) {
        val messages = uiState.value?.messages?.toMutableList()
        for (seq in seqs) {
            val cacheMsg = messages?.find { it.sessionId == sessionId && it.msgSeq == seq.toInt() }
            if (cacheMsg != null) {
                val index = messages.indexOf(cacheMsg)
                val newMsg = MessageEntity.copy(cacheMsg)
                newMsg.readed = true
                messages.removeAt(index)
                messages.add(index, newMsg)
                Log.d(TAG, "查找到 msg --->>> $cacheMsg")
                Log.d(
                    TAG,
                    "msg after mark read--->>> ${messages.find { it.sessionId == sessionId && it.msgSeq == seq.toInt() }?.readed}"
                )
            }
        }
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.postValue(messages?.let { _uiState.value?.copy(messages = it.toMutableSet()) })
        }
    }

    private fun clearInput() {
        viewModelScope.launch {
            delay(50)
            _inputMsg.postValue("")
        }
    }

    fun showEndCustomServiceDialog() {
        endCustomServiceDialog.value = true
    }

}

