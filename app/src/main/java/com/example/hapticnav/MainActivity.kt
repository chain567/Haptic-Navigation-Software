package com.example.hapticnav

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.hapticnav.ui.theme.HapticnavTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.google.maps.android.compose.*
import com.google.maps.android.ktx.utils.sphericalDistance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.*

// --- Bluetooth Setup ---
private val LEFT_DEVICE_ADDRESS = BuildConfig.BLUETOOTH_LEFT_DEVICE_ADDRESS.trim()
private val RIGHT_DEVICE_ADDRESS = BuildConfig.BLUETOOTH_RIGHT_DEVICE_ADDRESS.trim()

val CLASSIC_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

// --- Logging Tag ---
private const val TAG = "HapticNavDebug"

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class MainActivity : ComponentActivity() {

    // --- Separate Sockets/Streams for Left and Right ---
    private var leftBluetoothSocket: BluetoothSocket? = null
    private var leftOutputStream: OutputStream? = null
    private var rightBluetoothSocket: BluetoothSocket? = null
    private var rightOutputStream: OutputStream? = null
    private val bluetoothScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leftWriteMutex = Mutex()
    private val rightWriteMutex = Mutex()
    private var leftConnectionResult: ((Boolean) -> Unit)? = null
    private var rightConnectionResult: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HapticnavTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HapticMapScreen(
                        modifier = Modifier.padding(innerPadding),
                        // --- Pass separate connection functions ---
                        onConnectLeft = { onResult -> connectToDevice(LEFT_DEVICE_ADDRESS, isLeft = true, onResult) },
                        onConnectRight = { onResult -> connectToDevice(RIGHT_DEVICE_ADDRESS, isLeft = false, onResult) },
                        onDisconnect = { disconnectAllDevices() },
                        // --- Pass a command sender that knows left/right ---
                        onSendCommand = { command, isLeft -> sendBluetoothCommand(command, isLeft) }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    // --- Function now parameterized for address and type (left/right) ---
    private fun connectToDevice(deviceAddress: String, isLeft: Boolean, onResult: (Boolean) -> Unit) {
        val deviceType = if (isLeft) "LEFT" else "RIGHT"
        if (isLeft) leftConnectionResult = onResult else rightConnectionResult = onResult
        Log.d(TAG, "connectToDevice called for $deviceType")

        // Permissions checks...
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth permissions not granted.")
            onResult(false)
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth Adapter not available or disabled.")
            onResult(false)
            return
        }

        if (deviceAddress.isBlank()) {
            Log.e(TAG, "Bluetooth address for $deviceType is missing from local.properties.")
            onResult(false)
            return
        }

        val device = try {
            bluetoothAdapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid Bluetooth address for $deviceType. Did you replace the placeholder?")
            e.printStackTrace()
            onResult(false)
            return
        }

        bluetoothScope.launch {
            var connectingSocket: BluetoothSocket? = null
            try {
                val existingSocket = if (isLeft) leftBluetoothSocket else rightBluetoothSocket
                if (existingSocket != null && existingSocket.isConnected) {
                    Log.d(TAG, "Already connected to $deviceType.")
                    withContext(Dispatchers.Main) { onResult(true) }
                    return@launch
                }

                Log.d(TAG, "Creating RFCOMM socket for $deviceType...")
                val socket = device.createRfcommSocketToServiceRecord(CLASSIC_SPP_UUID)
                connectingSocket = socket

                Log.d(TAG, "Connecting to socket for $deviceType...")
                socket.connect() // Blocking call

                if (!withContext(Dispatchers.Main) {
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    }) {
                    socket.close()
                    return@launch
                }
                val stream = socket.outputStream
                Log.d(TAG, "Connection successful to $deviceType")

                // --- Store socket/stream in the correct variable ---
                if (isLeft) {
                    leftBluetoothSocket = socket
                    leftOutputStream = stream
                } else {
                    rightBluetoothSocket = socket
                    rightOutputStream = stream
                }

                // Complete the connection check before enabling navigation commands.
                val writeMutex = if (isLeft) leftWriteMutex else rightWriteMutex
                writeMutex.withLock {
                    stream.write("3".toByteArray())
                    delay(WARNING_DURATION_MS)
                    stream.write("0".toByteArray())
                }

                withContext(Dispatchers.Main) {
                    val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    if (!visible) disconnectDevice(isLeft)
                    onResult(visible)
                }

            } catch (e: CancellationException) {
                runCatching { connectingSocket?.close() }
                throw e
            } catch (e: IOException) {
                runCatching { connectingSocket?.close() }
                Log.e(TAG, "IOException during BT connect for $deviceType.", e)
                e.printStackTrace()
                // --- Close and nullify the correct socket ---
                try {
                    if (isLeft) leftBluetoothSocket?.close() else rightBluetoothSocket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                }
                if (isLeft) {
                    leftBluetoothSocket = null
                    leftOutputStream = null
                } else {
                    rightBluetoothSocket = null
                    rightOutputStream = null
                }
                withContext(Dispatchers.Main) { onResult(false) }
            } catch (e: SecurityException) {
                runCatching { connectingSocket?.close() }
                Log.e(TAG, "SecurityException during BT connect for $deviceType. Permissions missing?", e)
                e.printStackTrace()
                try {
                    if (isLeft) leftBluetoothSocket?.close() else rightBluetoothSocket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                }
                if (isLeft) {
                    leftBluetoothSocket = null
                    leftOutputStream = null
                } else {
                    rightBluetoothSocket = null
                    rightOutputStream = null
                }
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    private fun sendBluetoothCommand(command: String, isLeft: Boolean) {
        val writeMutex = if (isLeft) leftWriteMutex else rightWriteMutex
        bluetoothScope.launch(Dispatchers.Main.immediate) {
            try {
                writeMutex.withLock {
                    val stream = (if (isLeft) leftOutputStream else rightOutputStream)
                        ?: return@withLock
                    withContext(Dispatchers.IO) {
                        stream.write(command.toByteArray())
                        // The Arduino warning handler is busy for about 1.4 seconds.
                        if (command == "3") delay(WARNING_DURATION_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth write failed.", e)
                disconnectDevice(isLeft)
                withContext(Dispatchers.Main) {
                    (if (isLeft) leftConnectionResult else rightConnectionResult)?.invoke(false)
                }
            }
        }
    }

    private fun disconnectDevice(isLeft: Boolean) {
        val stream = if (isLeft) leftOutputStream else rightOutputStream
        val socket = if (isLeft) leftBluetoothSocket else rightBluetoothSocket
        val writeMutex = if (isLeft) leftWriteMutex else rightWriteMutex
        if (isLeft) {
            leftOutputStream = null
            leftBluetoothSocket = null
        } else {
            rightOutputStream = null
            rightBluetoothSocket = null
        }
        // Finish resource cleanup even when the activity's normal work is cancelled.
        bluetoothScope.launch(NonCancellable) {
            writeMutex.withLock {
                runCatching { stream?.write("0".toByteArray()) }
                runCatching { stream?.close() }
                runCatching { socket?.close() }
            }
        }
    }

    private fun disconnectAllDevices() {
        disconnectDevice(true)
        disconnectDevice(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called.")
        disconnectAllDevices()
        bluetoothScope.cancel()
    }
}

// --- Logging Tag for Composable ---
private const val HAPTIC_SCREEN_TAG = "HapticMapScreenDebug"


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HapticMapScreen(
    modifier: Modifier = Modifier,
    // --- Separate connection callbacks ---
    onConnectLeft: ((Boolean) -> Unit) -> Unit,
    onConnectRight: ((Boolean) -> Unit) -> Unit,
    onDisconnect: () -> Unit,
    // --- Command sender knows left/right ---
    onSendCommand: (command: String, isLeft: Boolean) -> Unit
) {
    val context = LocalContext.current

    // --- State Variables ---
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var finalDestination by remember { mutableStateOf<LatLng?>(null) }
    var currentWaypoint by remember { mutableStateOf<LatLng?>(null) }
    var searchInput by remember { mutableStateOf("") }
    var isNavigating by remember { mutableStateOf(false) }

    var lastSentCommandLeft by remember { mutableStateOf<String?>(null) }
    var lastSentCommandRight by remember { mutableStateOf<String?>(null) }

    var routePolyline by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeSteps by remember { mutableStateOf<List<com.google.maps.model.DirectionsStep>>(emptyList()) }
    var currentStepIndex by remember { mutableStateOf(0) }
    var turnInstructions by remember { mutableStateOf<List<String>>(emptyList()) }
    var turnMarkers by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    var hasCameraCenteredOnUser by remember { mutableStateOf(false) }
    var leftBtStatus by remember { mutableStateOf(ConnectionStatus.DISCONNECTED) }
    var rightBtStatus by remember { mutableStateOf(ConnectionStatus.DISCONNECTED) }

    var showCommandDetails by remember { mutableStateOf(false) }
    var autoCommandDisplay by remember { mutableStateOf("Auto-Command: N/A") }
    var distanceToWaypoint by remember { mutableStateOf<Float?>(null) }

    val cueController = remember { HapticCueController() }
    var cueTimerTick by remember { mutableStateOf(0) }
    var cueDeadline by remember { mutableStateOf<Long?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    val screenScope = rememberCoroutineScope()

    // --- WALL TEST: State variables ---
    var isWallTestActive by remember { mutableStateOf(false) }
    var wallDestination by remember { mutableStateOf<LatLng?>(null) }

    // --- Permissions ---
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    val permissionsState = rememberMultiplePermissionsState(permissions = permissions)

    // --- API Key ---
    val apiKey = remember(context) {
        try {
            val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            applicationInfo.metaData?.getString("com.google.android.geo.API_KEY")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(HAPTIC_SCREEN_TAG, "Failed to get API key", e)
            e.printStackTrace()
            null
        }
    }


    // Wake exactly when a timed cue ends, even if the GPS position has not changed.
    LaunchedEffect(cueDeadline) {
        val deadline = cueDeadline ?: return@LaunchedEffect
        delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        cueTimerTick++
    }

    LaunchedEffect(userLocation, currentWaypoint, isNavigating, leftBtStatus,
        rightBtStatus, currentStepIndex, isWallTestActive, cueTimerTick) {
        val active = isNavigating || isWallTestActive
        val position = userLocation
        val waypoint = currentWaypoint
        val distance = if (active && position != null && waypoint != null) {
            position.sphericalDistance(waypoint)
        } else null
        distanceToWaypoint = distance?.toFloat()
        val instruction = turnInstructions.getOrNull(currentStepIndex)?.lowercase(Locale.ROOT).orEmpty()
        val side = when {
            isWallTestActive || instruction.contains("left") -> TurnSide.LEFT
            instruction.contains("right") -> TurnSide.RIGHT
            else -> TurnSide.NONE
        }
        val cue = cueController.update(
            distanceMeters = distance,
            stepIndex = currentStepIndex,
            side = side,
            finalWaypoint = isWallTestActive || (routeSteps.isNotEmpty() && currentStepIndex == routeSteps.lastIndex),
            active = active,
            nowMillis = SystemClock.elapsedRealtime()
        )
        cueDeadline = cue.resumeAtMillis
        if (leftBtStatus != ConnectionStatus.CONNECTED) lastSentCommandLeft = null
        if (rightBtStatus != ConnectionStatus.CONNECTED) lastSentCommandRight = null
        if (leftBtStatus == ConnectionStatus.CONNECTED && lastSentCommandLeft != cue.left) {
            onSendCommand(cue.left, true)
            lastSentCommandLeft = cue.left
        }
        if (rightBtStatus == ConnectionStatus.CONNECTED && lastSentCommandRight != cue.right) {
            onSendCommand(cue.right, false)
            lastSentCommandRight = cue.right
        }
        autoCommandDisplay = "Auto-Cmd: ${cue.label} [L:${cue.left}, R:${cue.right}]"
        if (cue.advanceWaypoint) {
            currentStepIndex++
            currentWaypoint = routeSteps.getOrNull(currentStepIndex)?.let {
                LatLng(it.endLocation.lat, it.endLocation.lng)
            }
            if (currentWaypoint == null) isNavigating = false
        }
        if (cue.arrived) {
            isNavigating = false
            isWallTestActive = false
            currentWaypoint = null
            wallDestination = null
        }
    }

    // --- Lifecycle aware disconnect ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                Log.d(HAPTIC_SCREEN_TAG, "App going to background. Disconnecting all devices.")
                isNavigating = false
                isWallTestActive = false
                currentWaypoint = null
                wallDestination = null
                onDisconnect()
                leftBtStatus = ConnectionStatus.DISCONNECTED
                rightBtStatus = ConnectionStatus.DISCONNECTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }


    // --- UI ---
    Box(modifier = modifier.fillMaxSize()) {
        if (permissionsState.allPermissionsGranted) {
            // --- WALL TEST: Conditionally get location updates ---
            if (!isWallTestActive) {
                LocationEffect { location ->
                    userLocation = LatLng(location.latitude, location.longitude)
                }
            }


            val cameraPositionState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 1f)
            }

            LaunchedEffect(userLocation) {
                userLocation?.let {
                    if (!hasCameraCenteredOnUser) {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 17f))
                        hasCameraCenteredOnUser = true
                    }
                }
            }


            GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = cameraPositionState) {
                // --- WALL TEST: Show wall destination on map ---
                if (isWallTestActive) {
                    wallDestination?.let { Marker(state = MarkerState(position = it), title = "Wall") }
                }

                finalDestination?.let { Marker(state = MarkerState(position = it), title = "Final Destination") }
                currentWaypoint?.let { Marker(state = MarkerState(position = it), title = "Next Turn") }
                userLocation?.let { Marker(state = MarkerState(position = it), title = "You are here") }
                if (routePolyline.isNotEmpty()) { Polyline(points = routePolyline) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()) // Make the bottom sheet scrollable
            ) {
                Text(
                    text = userLocation?.let { "Your Location: %.4f, %.4f".format(it.latitude, it.longitude) } ?: "Fetching location...",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Left: ${leftBtStatus.name}",
                            color = when (leftBtStatus) {
                                ConnectionStatus.CONNECTED -> Color(0xFF388E3C)
                                ConnectionStatus.CONNECTING -> Color.Gray
                                ConnectionStatus.DISCONNECTED -> Color.Red
                            }
                        )
                        Text(
                            text = "Right: ${rightBtStatus.name}",
                            color = when (rightBtStatus) {
                                ConnectionStatus.CONNECTED -> Color(0xFF388E3C)
                                ConnectionStatus.CONNECTING -> Color.Gray
                                ConnectionStatus.DISCONNECTED -> Color.Red
                            }
                        )
                    }
                }


                // --- Test Mode & Wall Test Buttons ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Button(
                        onClick = { showCommandDetails = !showCommandDetails },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showCommandDetails) Color.Blue.copy(alpha = 0.7f) else Color.Gray
                        ),
                        enabled = !isWallTestActive // Disable while wall test is running
                    ) {
                        Text(text = if (showCommandDetails) "Hide commands" else "Show commands")
                    }

                    // --- WALL TEST: Button to toggle the test ---
                    Button(
                        onClick = {
                            if (!isWallTestActive) {
                                val origin = userLocation ?: return@Button
                                val target = origin.move(110.0, 0.0)
                                wallDestination = target
                                currentWaypoint = target
                                currentStepIndex = 0
                                cueController.reset()
                                isWallTestActive = true
                                isNavigating = false
                            } else {
                                isWallTestActive = false
                                currentWaypoint = null
                                wallDestination = null
                            }
                        },
                        enabled = isWallTestActive || (!isNavigating && !isRouting && userLocation != null &&
                            (leftBtStatus == ConnectionStatus.CONNECTED || rightBtStatus == ConnectionStatus.CONNECTED)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isWallTestActive) Color(0xFFC62828) else Color(0xFF2E7D32) // Red when active, Green when inactive
                        )
                    ) {
                        Text(text = if (isWallTestActive) "Stop Wall Test" else "Start Wall Test")
                    }
                }


                if (showCommandDetails || isWallTestActive) { // Show debug text for both modes
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = autoCommandDisplay,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        color = Color.Blue.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                routeError?.let { Text(it, color = Color.Red) }

                // --- Main UI for navigation, search, or wall test controls ---
                when {
                    // --- WALL TEST: Show movement controls ---
                    isWallTestActive -> {
                        if (distanceToWaypoint != null) {
                            Text(
                                text = "Distance to Wall: ${distanceToWaypoint?.toInt()} meters",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                        Button(
                            onClick = {
                                userLocation?.let { current ->
                                    wallDestination?.let { wall ->
                                        // Move user 5 meters closer to the wall
                                        val bearing = current.getBearing(wall)
                                        val remaining = current.sphericalDistance(wall)
                                        userLocation = current.move(minOf(5.0, remaining), bearing)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Move 5m Towards Wall")
                        }
                    }

                    isNavigating -> { // Real navigation UI
                        if (distanceToWaypoint != null) {
                            Text(
                                text = "Next Turn: ${distanceToWaypoint?.toInt()} meters",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                        Button(onClick = {
                            isNavigating = false
                            routePolyline = emptyList()
                            routeSteps = emptyList()
                            currentStepIndex = 0
                            currentWaypoint = null
                            turnInstructions = emptyList()
                            turnMarkers = emptyList()
                            finalDestination = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel Navigation")
                        }
                    }

                    else -> { // Search UI
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = searchInput,
                                onValueChange = { searchInput = it },
                                label = { Text("Search Destination") },
                                modifier = Modifier.weight(1f),
                                enabled = !isRouting
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val origin = userLocation ?: return@Button
                                    val key = apiKey
                                    if (key.isNullOrBlank()) {
                                        routeError = "Maps configuration is missing."
                                        return@Button
                                    }
                                    isRouting = true
                                    routeError = null
                                    screenScope.launch {
                                        try {
                                            val query = searchInput.trim()
                                            val result = withContext(Dispatchers.IO) {
                                                val geoContext = GeoApiContext.Builder().apiKey(key).build()
                                                try {
                                                    val address = Geocoder(context).getFromLocationName(query, 1)?.firstOrNull()
                                                        ?: throw IOException("Destination not found.")
                                                    val destination = LatLng(address.latitude, address.longitude)
                                                    val directions = DirectionsApi.getDirections(
                                                        geoContext,
                                                        "${origin.latitude},${origin.longitude}",
                                                        "${destination.latitude},${destination.longitude}"
                                                    ).await()
                                                    destination to directions.routes.firstOrNull()
                                                } finally { geoContext.shutdown() }
                                            }
                                            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                                            val route = result.second
                                            val steps = route?.legs?.firstOrNull()?.steps?.toList().orEmpty()
                                            if (route == null || steps.isEmpty()) {
                                                routeError = "No route found for this destination."
                                            } else {
                                                finalDestination = result.first
                                                routePolyline = PolyUtil.decode(route.overviewPolyline.encodedPath)
                                                routeSteps = steps
                                                turnInstructions = steps.map { it.htmlInstructions.replace(Regex("<[^>]*>"), "") }
                                                turnMarkers = steps.map { LatLng(it.endLocation.lat, it.endLocation.lng) }
                                                currentStepIndex = 0
                                                currentWaypoint = LatLng(steps[0].endLocation.lat, steps[0].endLocation.lng)
                                                cueController.reset()
                                                isNavigating = true
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e(HAPTIC_SCREEN_TAG, "Route request failed.", e)
                                            routeError = "Could not load the route. Check the destination and connection."
                                        } finally { isRouting = false }
                                    }
                                },
                                enabled = !isRouting && searchInput.isNotBlank() && userLocation != null &&
                                    (leftBtStatus == ConnectionStatus.CONNECTED || rightBtStatus == ConnectionStatus.CONNECTED)
                            ) {
                                Text(if (isRouting) "Loading…" else "Go")
                            }
                        }
                    }
                }


                Spacer(modifier = Modifier.height(8.dp))

                // --- Connect Buttons ---
                if (!isNavigating && !isWallTestActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Left Button
                        if (leftBtStatus != ConnectionStatus.CONNECTED) {
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = {
                                        leftBtStatus = ConnectionStatus.CONNECTING
                                        onConnectLeft { success ->
                                            leftBtStatus = if (success) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
                                        }
                                    },
                                    enabled = leftBtStatus == ConnectionStatus.DISCONNECTED,
                                ) {
                                    if (leftBtStatus == ConnectionStatus.CONNECTING) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                                    } else {
                                        Text("Connect Left")
                                    }
                                }
                            }
                        }
                        // Right Button
                        if (rightBtStatus != ConnectionStatus.CONNECTED) {
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = {
                                        rightBtStatus = ConnectionStatus.CONNECTING
                                        onConnectRight { success ->
                                            rightBtStatus = if (success) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
                                        }
                                    },
                                    enabled = rightBtStatus == ConnectionStatus.DISCONNECTED,
                                ) {
                                    if (rightBtStatus == ConnectionStatus.CONNECTING) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                                    } else {
                                        Text("Connect Right")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // --- Permission rationale UI ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Location and Bluetooth permissions are required for this app to function.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}


// --- Helper functions for Wall Test ---

/**
 * Calculates the bearing from one LatLng to another.
 */
fun LatLng.getBearing(destination: LatLng): Double {
    val lat1 = Math.toRadians(this.latitude)
    val lon1 = Math.toRadians(this.longitude)
    val lat2 = Math.toRadians(destination.latitude)
    val lon2 = Math.toRadians(destination.longitude)

    val dLon = lon2 - lon1
    val y = Math.sin(dLon) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
    val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    return bearing
}

/**
 * Moves a LatLng point by a given distance in meters at a specific bearing.
 */
fun LatLng.move(distanceMeters: Double, bearingDegrees: Double): LatLng {
    val earthRadius = 6378137.0 // Meters
    val distanceRatio = distanceMeters / earthRadius
    val bearingRadians = Math.toRadians(bearingDegrees)

    val lat1 = Math.toRadians(this.latitude)
    val lon1 = Math.toRadians(this.longitude)

    val lat2 = Math.asin(Math.sin(lat1) * Math.cos(distanceRatio) + Math.cos(lat1) * Math.sin(distanceRatio) * Math.cos(bearingRadians))
    var lon2 = lon1 + Math.atan2(Math.sin(bearingRadians) * Math.sin(distanceRatio) * Math.cos(lat1), Math.cos(distanceRatio) - Math.sin(lat1) * Math.sin(lat2))

    // Normalize lon2 to -180 to 180
    lon2 = (Math.toDegrees(lon2) + 540) % 360 - 180

    return LatLng(Math.toDegrees(lat2), lon2)
}


@SuppressLint("MissingPermission")
@Composable
fun LocationEffect(onLocationUpdate: (Location) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback by rememberUpdatedState(onLocationUpdate)
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    DisposableEffect(lifecycleOwner, fusedLocationClient) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let(currentCallback)
            }
        }
        var requesting = false
        fun startUpdates() {
            if (requesting) return
            try {
                fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                requesting = true
            } catch (e: SecurityException) {
                Log.w(HAPTIC_SCREEN_TAG, "Location permission is unavailable.", e)
            }
        }
        fun stopUpdates() {
            fusedLocationClient.removeLocationUpdates(callback)
            requesting = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startUpdates()
                Lifecycle.Event.ON_STOP -> stopUpdates()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startUpdates()
        onDispose {
            stopUpdates()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
