package com.example.rockpaperscissors
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Strategy
import com.example.rockpaperscissors.databinding.ActivityMainBinding
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import java.util.*
import kotlin.text.Charsets.UTF_8

class MainActivity : AppCompatActivity() {

    private enum class GameChoice {
        ROCK, PAPER, SCISSORS;

        fun beats(other: GameChoice): Boolean =
            (this == ROCK && other == SCISSORS)
                    || (this == SCISSORS && other == PAPER)
                    || (this == PAPER && other == ROCK)
    }

    internal object CodenameGenerator {
        private val COLORS = arrayOf(
            "Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet", "Purple", "Lavender"
        )
        private val TREATS = arrayOf(
            "Cupcake", "Donut", "Eclair", "Froyo", "Gingerbread", "Honeycomb",
            "Ice Cream Sandwich", "Jellybean", "Kit Kat", "Lollipop", "Marshmallow", "Nougat",
            "Oreo", "Pie"
        )
        private val generator = Random()

        fun generate(): String {
            val color = COLORS[generator.nextInt(COLORS.size)]
            val treat = TREATS[generator.nextInt(COLORS.size)]
            return "$color $treat"
        }
    }

    private val STRATEGY = Strategy.P2P_STAR

    private lateinit var connectionsClient: ConnectionsClient

    private val REQUEST_CODE_REQUIRED_PERMISSIONS = 1

    private var opponentName: String? = null
    private var opponentEndpointId: String? = null
    private var opponentScore = 0
    private var opponentChoice: GameChoice? = null

    private var myCodeName: String = CodenameGenerator.generate()
    private var myScore = 0
    private var myChoice: GameChoice? = null

    private lateinit var binding: ActivityMainBinding

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                opponentChoice = GameChoice.valueOf(String(it, UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            runOnUiThread {
                if (update.status == PayloadTransferUpdate.Status.SUCCESS
                    && myChoice != null && opponentChoice != null
                ) {
                    val mc = myChoice!!
                    val oc = opponentChoice!!
                    when {
                        mc.beats(oc) -> {
                            binding.status.text = "${mc.name} beats ${oc.name}"
                            myScore++
                        }
                        mc == oc -> {
                            binding.status.text = "You both chose ${mc.name}"
                        }
                        else -> {
                            binding.status.text = "${mc.name} loses to ${oc.name}"
                            opponentScore++
                        }
                    }
                    binding.score.text = "$myScore : $opponentScore"
                    myChoice = null
                    opponentChoice = null
                    setGameControllerEnabled(true)
                }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            opponentName = "Opponent\n(${info.endpointName})"
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                runOnUiThread {
                    connectionsClient.stopAdvertising()
                    connectionsClient.stopDiscovery()
                    opponentEndpointId = endpointId
                    binding.opponentName.text = opponentName
                    binding.status.text = "Connected"
                    setGameControllerEnabled(true)
                }
            } else {
                runOnUiThread {
                    binding.status.text = "Connection failed: ${result.status.statusMessage}"
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            runOnUiThread {
                resetGame()
                binding.status.text = "Disconnected from opponent"
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(myCodeName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    runOnUiThread {
                        binding.status.text = "Requesting connection..."
                    }
                }.addOnFailureListener {
                    runOnUiThread {
                        binding.status.text = "Failed to request connection: ${it.message}"
                    }
                }
        }

        override fun onEndpointLost(endpointId: String) {
            runOnUiThread {
                binding.status.text = "Opponent lost!"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connectionsClient = Nearby.getConnectionsClient(this)

        binding.myName.text = "You\n($myCodeName)"
        binding.findOpponent.setOnClickListener {
            startAdvertising()
            startDiscovery()
            binding.status.text = "Searching for opponents..."
            binding.findOpponent.visibility = View.GONE
            binding.disconnect.visibility = View.VISIBLE
        }

        binding.apply {
            rock.setOnClickListener { sendGameChoice(GameChoice.ROCK) }
            paper.setOnClickListener { sendGameChoice(GameChoice.PAPER) }
            scissors.setOnClickListener { sendGameChoice(GameChoice.SCISSORS) }
        }

        binding.disconnect.setOnClickListener {
            opponentEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
            resetGame()
        }

        resetGame()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(myCodeName, packageName, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                runOnUiThread {
                    binding.status.text = "Advertising started"
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    binding.status.text = "Advertising failed: ${e.message}"
                }
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(packageName, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                runOnUiThread {
                    binding.status.text = "Discovery started"
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    binding.status.text = "Discovery failed: ${e.message}"
                }
            }
    }

    @CallSuper
    override fun onStop() {
        connectionsClient.apply {
            stopAdvertising()
            stopDiscovery()
            stopAllEndpoints()
        }
        resetGame()
        super.onStop()
    }

    private fun resetGame() {
        opponentEndpointId = null
        opponentName = null
        opponentChoice = null
        opponentScore = 0
        myChoice = null
        myScore = 0
        runOnUiThread {
            binding.disconnect.visibility = View.GONE
            binding.findOpponent.visibility = View.VISIBLE
            setGameControllerEnabled(false)
            binding.opponentName.text = "opponent\n(none yet)"
            binding.status.text = "..."
            binding.score.text = ":"
        }
    }

    private fun sendGameChoice(choice: GameChoice) {
        myChoice = choice
        opponentEndpointId?.let {
            connectionsClient.sendPayload(it, Payload.fromBytes(choice.name.toByteArray(Charsets.UTF_8)))
            runOnUiThread {
                binding.status.text = "You chose ${choice.name}"
                setGameControllerEnabled(false)
            }
        } ?: runOnUiThread {
            binding.status.text = "Opponent not found!"
        }
    }

    private fun setGameControllerEnabled(state: Boolean) {
        binding.apply {
            rock.isEnabled = state
            paper.isEnabled = state
            scissors.isEnabled = state
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, start discovering and advertising
        } else {
            Toast.makeText(this, "Permission denied. Cannot start.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @CallSuper
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            if (grantResults.any { it == PackageManager.PERMISSION_DENIED }) {
                Toast.makeText(this, "Cannot start without required permissions", Toast.LENGTH_LONG).show()
                finish()
            } else {
                recreate()
            }
        }
    }
}


