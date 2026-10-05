package com.prempoddar.duet.net

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Signalling through HiveMQ's free public MQTT broker over TLS. Topics are random-looking
 * (derived from the session code) and every payload is sealed with the session key, so the broker
 * only sees noise. No music ever goes through it.
 */
class MqttSignaling(
    private val topic: String,
    private val key: ByteArray,
    /** "host" or this guest's peer id: the inbox we listen on. */
    private val me: String,
    private val onSignal: (Signal) -> Unit,
    private val onState: (connected: Boolean) -> Unit,
) : Signaling {
    private val client = MqttAsyncClient(BROKER, "duet-" + UUID.randomUUID().toString().take(12), MemoryPersistence())
    private val pending = ArrayList<Pair<String, ByteArray>>()

    fun connect() {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                client.subscribe(inbox(me), 1)
                onState(true)
                flush()
            }

            override fun connectionLost(cause: Throwable?) = onState(false)

            override fun messageArrived(t: String?, message: MqttMessage) {
                Signal.open(key, message.payload)?.let(onSignal)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 30
            connectionTimeout = 15
        }
        runCatching {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "relay unreachable", exception)
                    onState(false)
                }
            })
        }.onFailure { onState(false) }
    }

    @Synchronized override fun send(to: String, signal: Signal) {
        val bytes = Signal.seal(key, signal)
        if (client.isConnected) publish(to, bytes) else pending += to to bytes
    }

    @Synchronized private fun flush() {
        pending.forEach { (to, bytes) -> publish(to, bytes) }
        pending.clear()
    }

    private fun publish(to: String, bytes: ByteArray) {
        runCatching { client.publish(inbox(to), MqttMessage(bytes).apply { qos = 1 }) }
    }

    override fun close() {
        runCatching { client.disconnect(500) }
        runCatching { client.close(true) }
    }

    private fun inbox(who: String) = "duet/v1/$topic/$who"

    private companion object {
        const val TAG = "Duet"
        const val BROKER = "ssl://broker.hivemq.com:8883"
    }
}
