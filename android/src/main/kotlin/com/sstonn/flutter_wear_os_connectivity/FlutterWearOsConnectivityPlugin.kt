package com.sstonn.flutter_wear_os_connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Pair
import androidx.concurrent.futures.await
import com.google.android.gms.wearable.*
import com.google.android.gms.wearable.CapabilityClient.FILTER_ALL
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.*
import kotlin.coroutines.CoroutineContext

const val TAG = "FlutterWearOsConnectivity"
@SuppressLint("LongLogTag")
class FlutterWearOsConnectivityPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private lateinit var callbackChannel: MethodChannel
    private var scope: (CoroutineContext) -> CoroutineScope = {
        CoroutineScope(it)
    }


    //Clients needed for Data Layer API
    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private lateinit var dataClient: DataClient
    private lateinit var capabilityClient: CapabilityClient
    private lateinit var remoteActivityHelper: RemoteActivityHelper

    //Activity and context references
    private var context: Context? = null

    //Listeners for capability changed
    private var capabilityListeners: MutableMap<String, CapabilityClient.OnCapabilityChangedListener> =
        mutableMapOf()

    //Listener for message received
    private var messageListeners: MutableMap<String, MessageClient.OnMessageReceivedListener?> =
        mutableMapOf()

    //Listener for data changed
    private var dataChangeListeners: MutableMap<String, DataClient.OnDataChangedListener?> =
        mutableMapOf()


    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "sstonn/flutter_wear_os_connectivity"
        )
        callbackChannel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "sstonn/flutter_wear_os_connectivity_callback"
        )
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "isSupported" -> {
                result.success(true)
            }
            "configure" -> {
                Log.i(
                    TAG,
                    "Configuring Wear OS Connectivity Plugin",
                )
                // Initialize all clients
                context?.let { it ->
                    messageClient = Wearable.getMessageClient(it)
                    nodeClient = Wearable.getNodeClient(it)
                    dataClient = Wearable.getDataClient(it)
                    capabilityClient = Wearable.getCapabilityClient(it)
                    remoteActivityHelper = RemoteActivityHelper(it)
                    Log.i(
                        TAG, "Wear OS Connectivity Plugin is ready")
                }
                result.success(null)
            }
            "getConnectedDevices" -> {
                scope(Dispatchers.IO).launch {
                    try {
                        val nodes = nodeClient.connectedNodes.await()
                        scope(Dispatchers.Main).launch {
                            result.success(
                                nodes.map { it.toRawMap() }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Can't retrieve connected devices, please try again", e
                        )
                        handleFlutterError(
                            result,
                            "Can't retrieve connected devices, please try again"
                        )
                    }
                }
            }
            "getCompanionPackageForDevice" -> {
                val nodeId = call.arguments as String
                scope(Dispatchers.IO).launch {
                    try {
                        val packageName: String =
                            rewriteCompanionPackageName(nodeClient.getCompanionPackageForNode(nodeId).await())
                        scope(Dispatchers.Main).launch {
                            result.success(
                                packageName
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "No companion package found for $nodeId", e
                        )
                        handleFlutterError(result, "No companion package found for $nodeId")
                    }

                }
            }
            "getLocalDeviceInfo" -> {
                scope(Dispatchers.IO).launch {
                    try {
                        val localDeviceInfo = nodeClient.localNode.await().toRawMap()
                        scope(Dispatchers.Main).launch {
                            result.success(
                                localDeviceInfo
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Can't retrieve local device info, please try again", e
                        )
                        handleFlutterError(
                            result,
                            "Can't retrieve local device info, please try again"
                        )
                    }
                }
            }
            "findDeviceIdFromBluetoothAddress" -> {
                val macAddress: String? = call.arguments as String?
                macAddress?.let {
                    scope(Dispatchers.IO).launch {
                        try {
                            val foundNodeId = nodeClient.getNodeId(it).await()
                            scope(Dispatchers.Main).launch {
                                result.success(
                                    foundNodeId
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "No device found with mac address $macAddress", e
                            )
                            result.success(null)
                        }
                    }
                    return
                }
                result.success(null)
            }
            "getAllCapabilities" -> {
                val filterType = call.arguments as Int
                scope(Dispatchers.IO).launch {
                    try {
                        val capabilities =
                            capabilityClient.getAllCapabilities(filterType)
                                .await().entries.associate { it.key to it.value.toRawMap() }
                        scope(Dispatchers.Main).launch {
                            result.success(
                                capabilities
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to retrieve capabilities, please try again", e
                        )
                        scope(Dispatchers.Main).launch {
                            result.success(
                                result.success(emptyMap<String, Map<String, Any>>())
                            )
                        }
                    }
                }
            }
            "findCapabilityByName" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String
                val filterType = arguments["filterType"] as Int
                scope(Dispatchers.IO).launch {
                    try {
                        val capabilityInfo =
                            capabilityClient.getCapability(name, filterType).await().toRawMap()
                        scope(Dispatchers.Main).launch {
                            result.success(
                                capabilityInfo
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to retrieve capability, please try again", e
                        )
                        scope(Dispatchers.Main).launch {
                            result.success(
                                null
                            )
                        }
                    }
                }
            }
            "addCapabilityListener" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String?
                val path = arguments["path"] as String?
                val filterType = arguments["filterType"] as Int?
                if (name != null) {
                    addNewCapabilityListener(result, name, null)
                } else if (path != null) {
                    addNewCapabilityListener(result, path, filterType)
                }
            }
            "removeCapabilityListener" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String?
                val path = arguments["path"] as String?
                if (name != null || path != null) {
                    removeCapabilityListener(result, (name ?: path)!!)
                }
            }
            "registerNewCapability" -> {
                val capabilityName: String? =
                    call.arguments as String?
                capabilityName?.let {
                    scope(Dispatchers.IO).launch {
                        try {
                            capabilityClient.addLocalCapability(capabilityName)
                            scope(Dispatchers.Main).launch {
                                result.success(
                                    null
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Unable to register new capability, please try again", e
                            )
                            handleFlutterError(
                                result,
                                "Unable to register new capability, please try again"
                            )
                        }
                    }
                    return
                }
                result.success(null)
            }
            "removeExistingCapability" -> {
                val capabilityName: String? =
                    call.arguments as String?
                capabilityName?.let {
                    scope(Dispatchers.IO).launch {
                        try {
                            capabilityClient.removeLocalCapability(capabilityName)
                            scope(Dispatchers.Main).launch {
                                result.success(
                                    null
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Unable to remove capability, please try again", e
                            )
                            handleFlutterError(
                                result,
                                "Unable to remove capability, please try again"
                            )
                        }
                    }
                    return
                }
                result.success(null)
            }
            "sendMessage" -> {
                val arguments = call.arguments as Map<*, *>
                val data = arguments["data"] as ByteArray
                val nodeId = arguments["nodeId"] as String
                val path = arguments["path"] as String
                scope(Dispatchers.Default).launch {
                    try {
                        val messageId = messageClient.sendMessage(
                            nodeId,
                            path,
                            data,
                        ).await()
                        scope(Dispatchers.Main).launch {
                            result.success(
                                messageId
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to send message, please try again", e
                        )
                        handleFlutterError(result, e.message ?: "")
                    }
                }
            }
            "addMessageListener" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String?
                val path = arguments["path"] as String?
                val filterType = arguments["filterType"] as Int?
                if (name != null) {
                    addNewMessageListener(result, name, null)
                } else if (path != null) {
                    addNewMessageListener(result, path, filterType)
                }
            }
            "removeMessageListener" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String?
                val path = arguments["path"] as String?
                if (name != null || path != null) {
                    removeMessageListener(result, (name ?: path)!!)
                } else {
                    result.success(null)
                }
            }
            "findDataItem" -> {
                val path = call.arguments as String
                scope(Dispatchers.IO).launch {
                    try {
                        val rawDataItem = convertAndRemapDataItemMap(
                            dataClient.getDataItem(Uri.parse(path)).await().toRawMap()
                        )
                        scope(Dispatchers.Main).launch {
                            result.success(rawDataItem)
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to find data item associated with $path", e
                        )
                        handleFlutterError(
                            result,
                            "Unable to find data item associated with $path"
                        )
                    }
                }
            }
            "getAllDataItems" -> {
                scope(Dispatchers.IO).launch {
                    try {
                        val rawDataItems = dataClient.dataItems.await()
                            .map { convertAndRemapDataItemMap(it.toRawMap()) }
                        scope(Dispatchers.Main).launch {
                            result.success(rawDataItems)
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to retrieve data items, please try again", e
                        )
                        handleFlutterError(result, "Unable to find data item")
                    }
                }
            }
            "syncData" -> {
                try {
                    val arguments = call.arguments as HashMap<*, *>
                    val path = arguments["path"] as String
                    val isUrgent = arguments["isUrgent"] as Boolean
                    val rawMapData = arguments["rawMapData"] as HashMap<*, *>
                    val filePaths = arguments["rawFilePaths"] as HashMap<*, *>
                    val putDataRequest: PutDataRequest = PutDataMapRequest.create(path).run {
                        if (isUrgent) {
                            setUrgent()
                        }
                        dataMap.putAll((filePaths.entries.associate { it.key.toString() to it.value.toString() } as HashMap<String, String>).toFileDataMap())
                        dataMap.putAll(rawMapData.toDataMap())
                        asPutDataRequest()
                    }
                    scope(Dispatchers.IO).launch {
                        try {
                            val dataItem = dataClient.putDataItem(putDataRequest).await()
                            val dataItemRawMap = convertAndRemapDataItemMap(dataItem.toRawMap())
                            scope(Dispatchers.Main).launch {
                                result.success(dataItemRawMap)
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Unable to sync data, please try again", e
                            )
                            handleFlutterError(result, e.toString())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "No data found", e
                    )
                    handleFlutterError(result, "No data found")
                }
            }
            "deleteDataItems" -> {
                val arguments = call.arguments as HashMap<*, *>
                val path = arguments["path"] as String
                val filterType = arguments["filterType"] as Int
                scope(Dispatchers.IO).launch {
                    try {
                        val numberOfDeletedItems =
                            dataClient.deleteDataItems(Uri.parse(path), filterType).await()
                        scope(Dispatchers.Main).launch {
                            result.success(
                                numberOfDeletedItems
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to delete data items on $path", e
                        )
                        handleFlutterError(result, "Unable to delete data items on $path")
                    }
                }

            }
            "getDataItems" -> {
                val arguments = call.arguments as HashMap<*, *>
                val path = arguments["path"] as String
                val filterType = arguments["filterType"] as Int
                scope(Dispatchers.IO).launch {
                    try {
                        val buffer = dataClient.getDataItems(Uri.parse(path), filterType).await()
                        val items = buffer.map { it.toRawMap() }.map {
                            convertAndRemapDataItemMap(it)
                        }
                        scope(Dispatchers.Main).launch {
                            result.success(items)
                        }
                        buffer.release()
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to retrieve items on $path", e
                        )
                        handleFlutterError(result, "Unable to retrieve items on $path")
                    }
                }
            }
            "addDataListener" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String?
                val path = arguments["path"] as String?
                val filterType = arguments["filterType"] as Int?
                if (name != null) {
                    addNewDataListener(result, name, null)
                } else if (path != null) {
                    addNewDataListener(result, path, filterType)
                }
            }
            "removeDataListener" -> {
                val arguments = call.arguments as Map<*, *>
                val name = arguments["name"] as String?
                val path = arguments["path"] as String?
                if (name != null || path != null) {
                    removeDataListener(result, (name ?: path)!!)
                }
            }
            "installCompanionPackage" -> {
                val nodeId = call.arguments as String
                scope(Dispatchers.IO).launch {
                    context?.let {
                        try {
                            val playStoreUri = "market://details?id=${it.packageName}"
                            val intent = Intent(Intent.ACTION_VIEW)
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                                .setData(Uri.parse(playStoreUri))
                            remoteActivityHelper
                                .startRemoteActivity(
                                    targetIntent = intent,
                                    targetNodeId = nodeId
                                ).await()
                            scope(Dispatchers.Main).launch {
                                result.success(
                                    null
                                )
                            }
                        } catch (e: Exception) {
                            handleFlutterError(result, e.message ?: "Failed to install companion package")
                        }

                    }
                }
            }
            "startCompanionPackage" -> {
                val arguments = call.arguments as Map<*, *>
                val nodeId = arguments["nodeId"] as String
                val path = arguments["path"] as String?
                val extras = arguments["extras"] as Map<*, *>?

                scope(Dispatchers.IO).launch {
                    try {
                        context?.let {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                                .setData(Uri.parse("wear://${it.packageName}/$path"))

                            extras?.forEach { mapEntry ->
                                intent.putExtra(mapEntry.key.toString(), mapEntry.toString())
                            }

                            remoteActivityHelper
                                .startRemoteActivity(
                                    targetIntent = intent,
                                    targetNodeId = nodeId
                                ).await()

                            scope(Dispatchers.Main).launch {
                                result.success(
                                    null
                                )
                            }
                        }
                    } catch (e: Exception) {
                        handleFlutterError(result, e.message ?: "Failed to start companion package")
                    }
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun addNewCapabilityListener(result: Result, key: String, filterType: Int?) {
        scope(Dispatchers.IO).launch {
            try {
                capabilityListeners[key]?.let {
                    capabilityClient.removeListener(it, key)
                        .await() || capabilityClient.removeListener(it).await()
                }
                val newListener: CapabilityClient.OnCapabilityChangedListener =
                    CapabilityClient.OnCapabilityChangedListener {
                        callbackChannel.invokeMethod(
                            "onCapabilityChanged",
                            mapOf(
                                "key" to key,
                                "data" to it.toRawMap()
                            )
                        )
                    }
                capabilityListeners[key] = newListener
                if (filterType != null) {
                    capabilityClient.addListener(
                        capabilityListeners[key]!!,
                        Uri.parse(key),
                        FILTER_ALL
                    ).await()
                } else {
                    capabilityClient.addListener(capabilityListeners[key]!!, key).await()
                }

                scope(Dispatchers.Main).launch {
                    result.success(
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Unable to listen to capability changed, please try again", e
                )
                handleFlutterError(
                    result,
                    "Unable to listen to capability changed, please try again"
                )
            }

        }
    }

    private fun removeCapabilityListener(result: Result, key: String) {
        capabilityListeners[key]?.let {
            scope(Dispatchers.IO).launch {
                try {
                    val isRemoved = capabilityClient.removeListener(it)
                        .await() || capabilityClient.removeListener(it, key).await()
                    scope(Dispatchers.Main).launch {
                        result.success(
                            isRemoved
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to remove capability listener, please try again", e
                    )
                    result.success(false)

                }
            }
            return
        }
        result.success(false)
    }

    private fun addNewMessageListener(result: Result, key: String, filterType: Int?) {
        scope(Dispatchers.Default).launch {
            try {
                messageListeners[key]?.let {
                    messageClient.removeListener(it).await()
                }
                val newListener: MessageClient.OnMessageReceivedListener =
                    MessageClient.OnMessageReceivedListener {
                        callbackChannel.invokeMethod(
                            "onMessageReceived",
                            mapOf(
                                "key" to key,
                                "data" to it.toRawData()
                            )
                        )
                    }
                messageListeners[key] = newListener
                if (filterType == null) {
                    messageClient.addListener(messageListeners[key]!!).await()
                } else {
                    messageClient.addListener(messageListeners[key]!!, Uri.parse(key), filterType)
                        .await()
                }
                scope(Dispatchers.Main).launch {
                    result.success(null)
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Unable to add message listener, please try again", e
                )
                handleFlutterError(
                    result,
                    "Unable to add message listener, please try again"
                )
            }

        }
    }

    private fun removeMessageListener(result: Result, key: String) {
        messageListeners[key]?.let {
            scope(Dispatchers.Default).launch {
                try {
                    val isRemoved = messageClient.removeListener(it)
                        .await()
                    scope(Dispatchers.Main).launch {
                        result.success(
                            isRemoved
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to remove message listener, please try again", e
                    )
                    result.success(false)

                }
            }
            return
        }
        result.success(false)
    }

    private fun addNewDataListener(result: Result, key: String, filterType: Int?) {
        scope(Dispatchers.Default).launch {
            try {
                dataChangeListeners[key]?.let {
                    dataClient.removeListener(it).await()
                }
                val newListener: DataClient.OnDataChangedListener =
                    DataClient.OnDataChangedListener { buffer ->
                        handleDataEventBuffer(buffer, {
                            callbackChannel.invokeMethod(
                                "onDataChanged",
                                mapOf(
                                    "key" to key,
                                    "data" to it
                                )
                            )
                        }) {
                            //TODO add error handler here
                        }

                    }
                dataChangeListeners[key] = newListener
                if (filterType == null) {
                    dataClient.addListener(dataChangeListeners[key]!!).await()
                } else {
                    dataClient.addListener(dataChangeListeners[key]!!, Uri.parse(key), filterType)
                        .await()
                }
                scope(Dispatchers.Main).launch {
                    result.success(null)
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Unable to add new data listener, please try again", e
                )
                handleFlutterError(
                    result,
                    "Unable to add new data listener, please try again"
                )
            }
        }
    }

    private fun handleDataEventBuffer(
        dataEventBuffer: DataEventBuffer,
        onDataResponse: (List<Map<String, Any?>>) -> Unit,
        onError: (java.lang.Exception) -> Unit
    ) {
        try {
            val tmpEvents = dataEventBuffer.map { dataEvent -> dataEvent }
            val events = tmpEvents.toList()
            val rawEvents: List<Map<String, Any?>> = events.toRawEventList()
            scope(Dispatchers.Default).launch {
                try {
                    val rawEventData = rawEvents.map { event ->
                        convertAndRemapDataItemMap(event["dataItem"] as HashMap<String, Any?>)
                        event
                    }
                    scope(Dispatchers.Main).launch {
                        onDataResponse(rawEventData)
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to handle data event buffer, please try again",e
                    )
                    onError(e)
                }
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to handle data event buffer, please try again",e
            )
            onError(e)
        }
    }

    private suspend fun convertAndRemapDataItemMap(dataItemMap: HashMap<String, Any?>): HashMap<String, Any?> {
        val filePaths: HashMap<String, String> = HashMap()
        val assets = dataItemMap["assets"] as HashMap<*, *>
        val fileName = assets["name"] as? String?
        val asset = assets["asset"] as? Asset?
        if (fileName != null && asset != null) {
            val inputStream =
                dataClient.getFdForAsset(asset).await().inputStream
            filePaths[fileName] =
                copyStreamToFile(inputStream, fileName).path
        }
        dataItemMap["file_paths"] = filePaths
        dataItemMap.remove("assets")
        return dataItemMap
    }

    private fun removeDataListener(result: Result, key: String) {
        dataChangeListeners[key]?.let {
            scope(Dispatchers.Default).launch {
                try {
                    val isRemoved = dataClient.removeListener(it)
                        .await()
                    scope(Dispatchers.Main).launch {
                        result.success(
                            isRemoved
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Unable to remove data listener, please try again",e
                    )
                    result.success(false)
                }
            }
            return
        }
        result.success(false)
    }


    private fun handleFlutterError(result: Result, message: String) {
        scope(Dispatchers.Main).launch {
            result.error("500", message, null)
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.context = binding.activity.applicationContext
    }

    override fun onDetachedFromActivityForConfigChanges() {
        context = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.context = binding.activity.applicationContext
    }

    override fun onDetachedFromActivity() {
        context = null
    }

    private fun fromDataMap(dataMap: DataMap): Pair<HashMap<String, *>, HashMap<String, *>> {
        val hashMap: HashMap<String, Any> = HashMap()
        var assets: HashMap<String, Any?> = HashMap()
        for (key in dataMap.keySet()) {
            val data = dataMap.get<Any>(key)
            data?.let {
                if (it is Asset) {
                    assets = hashMapOf(
                        "asset" to it,
                        "name" to dataMap.get<String>("name")
                    )
                    return@let
                } else if (it is DataMap) {
                    val childMapPair = fromDataMap(it)
                    if (key.indexOf("file#") == 0) {
                        assets[key] = childMapPair.second
                    } else {
                        hashMap[key] = childMapPair.first
                    }
                    return@let
                }
                hashMap[key] = it
            }
        }
        return Pair(hashMap, assets)
    }

    private fun copyStreamToFile(inputStream: InputStream, fileName: String): File {
        val outputFile = File(context?.cacheDir, fileName)
        inputStream.use { input ->
            val outputStream = FileOutputStream(outputFile)
            outputStream.use { output ->
                val buffer = ByteArray(4 * 1024) // buffer size
                while (true) {
                    val byteCount = input.read(buffer)
                    if (byteCount < 0) break
                    output.write(buffer, 0, byteCount)
                }
                output.flush()
            }
            outputStream.close()
        }
        inputStream.close()
        return outputFile
    }

    private fun rewriteCompanionPackageName(companionPackage: String): String {
        val regex = Regex("""com.samsung.*plugin""")
        return if (regex.matches(companionPackage)) {
            "com.samsung.android.app.watchmanager"
        } else {
            companionPackage
        }
    }


    private fun List<DataEvent>.toRawEventList(): List<Map<String, Any>> {
        return map {
            mapOf(
                "type" to it.type,
                "dataItem" to it.dataItem.toRawMap(),
                "isDataValid" to it.isDataValid
            )

        }
    }

    private fun MessageEvent.toRawData(): Map<String, Any> {
        return mapOf(
            "data" to data,
            "path" to path,
            "requestId" to this.requestId,
            "sourceNodeId" to this.sourceNodeId
        )
    }

    private fun Node.toRawMap(): Map<String, Any> {
        return mapOf(
            "name" to displayName,
            "isNearby" to isNearby,
            "id" to id
        )
    }

    private fun CapabilityInfo.toRawMap(): Map<String, Any> {
        return mapOf(
            "name" to this.name,
            "associatedNodes" to this.nodes.map { it.toRawMap() }
        )
    }

    private fun DataItem.toRawMap(): HashMap<String, Any?> {
        val mapDataItem = DataMapItem.fromDataItem(this)

        val finalMap: HashMap<String, Any?> = hashMapOf(
            "uri" to uri.toString(),
        )
        val dataItemMap: HashMap<String, Any?> = hashMapOf(
        )
        if (data != null) {
            dataItemMap.putAll(hashMapOf("data" to data!!))
            dataItemMap.putAll(mapDataItem.toRawMap())
        }
        finalMap.putAll(dataItemMap)
        return finalMap
    }

    private fun DataMapItem.toRawMap(): HashMap<String, Any> {
        val dataMapPair = fromDataMap(dataMap)
        return hashMapOf(
            "uri" to uri.toString(),
            "map" to dataMapPair.first,
            "assets" to dataMapPair.second
        )
    }


    private fun HashMap<*, String>.toFileDataMap(): DataMap {
        val dataMap = DataMap()
        for ((key, value) in this) {
            val file = File(value)
            if (file.exists()) {
                val fileDataMap = DataMap()
                val randomFile = RandomAccessFile(file, "r")
                val fileBytes = ByteArray(randomFile.length().toInt())
                randomFile.read(fileBytes)
                fileDataMap.putString("name", file.name)
                fileDataMap.putAsset(
                    "asset", Asset.createFromBytes(
                        fileBytes
                    )
                )
                dataMap.putDataMap("file#$key", fileDataMap)
            }
        }
        return dataMap
    }

    private fun HashMap<*, *>.toDataMap(): DataMap {
        val dataMap = DataMap()
        for ((key, value) in this) {
            when (value) {
                is String -> {
                    dataMap.putString(key.toString(), value)
                }
                is Boolean -> {
                    dataMap.putBoolean(key.toString(), value)
                }
                is Int -> {
                    dataMap.putInt(key.toString(), value)
                }
                is Double -> {
                    dataMap.putDouble(key.toString(), value)
                }
                is Long -> {
                    dataMap.putLong(key.toString(), value)
                }
                is ByteArray -> {
                    dataMap.putByteArray(key.toString(), value)
                }
                is FloatArray -> {
                    dataMap.putFloatArray(key.toString(), value)
                }
                is LongArray -> {
                    dataMap.putLongArray(key.toString(), value)
                }
                is HashMap<*, *> -> {
                    dataMap.putDataMap(
                        key.toString(),
                        (value).toDataMap()
                    )
                }
                is List<*> -> {
                    if ((value).isEmpty()) continue
                    @Suppress("UNCHECKED_CAST")
                    if ((value).all { it is String }) {
                        dataMap.putStringArray(
                            key.toString(),
                            (value as List<String>).toTypedArray()
                        )
                    } else if ((value).all { it is HashMap<*, *> }) {
                        dataMap.putDataMapArrayList(
                            key.toString(),
                            ArrayList((value as List<HashMap<*, *>>).map { it.toDataMap() })
                        )
                    } else if ((value).all { it is Int }) {
                        dataMap.putIntegerArrayList(
                            key.toString(),
                            ArrayList(value as List<Int>)
                        )
                    }
                }
            }
        }
        return dataMap
    }

}



