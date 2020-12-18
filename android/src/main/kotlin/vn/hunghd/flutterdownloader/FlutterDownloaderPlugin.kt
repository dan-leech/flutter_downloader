package vn.hunghd.flutterdownloader

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class FlutterDownloaderPlugin : MethodCallHandler, FlutterPlugin {
    private lateinit var callHandler: FlutterDownloaderCallHandler

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) =
            registerWorkManager(binding.binaryMessenger, binding.applicationContext)

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    companion object {
        private const val CHANNEL = "vn.hunghd/downloader"
        const val CALLBACK_DISPATCHER_HANDLE_KEY = "callback_dispatcher_handle_key"
        const val SHARED_PREFERENCES_KEY = "vn.hunghd.downloader.pref"

        var pluginRegistryCallback: PluginRegistry.PluginRegistrantCallback? = null

        @JvmStatic
        private fun registerWorkManager(messenger: BinaryMessenger, ctx: Context) {
            val channel = MethodChannel(messenger, CHANNEL)
            channel.setMethodCallHandler(`FlutterDownloaderPlugin`().apply { callHandler = FlutterDownloaderCallHandler(ctx) })
        }

        @JvmStatic
        fun registerWith(registrar: PluginRegistry.Registrar) =
                registerWorkManager(registrar.messenger(), registrar.activeContext())

        @Deprecated(message = "Use the Android v2 embedding method.")
        @JvmStatic
        fun setPluginRegistrantCallback(pluginRegistryCallback: PluginRegistry.PluginRegistrantCallback) {
            `FlutterDownloaderPlugin`.pluginRegistryCallback = pluginRegistryCallback
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) = callHandler.handle(call, result)
}