package vn.hunghd.flutterdownloader

import android.content.Context
import android.os.Handler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import io.flutter.view.FlutterMain
import io.flutter.view.FlutterNativeView
import io.flutter.view.FlutterRunArguments
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

class CallbackMessenger(private val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private val isolateStarted = AtomicBoolean(false)
        private val isolateQueue = ArrayDeque<MutableList<*>>()
        private var backgroundFlutterView: FlutterNativeView? = null
    }

    private var backgroundChannel: MethodChannel? = null

    init {
        Handler(context.mainLooper).post { startBackgroundIsolate(context) }
    }

    fun sendUpdateProcessEvent(taskId: String, status: Int, progress: Int) {
        val args: MutableList<Any> = ArrayList()
        val callbackHandle = FlutterDownloaderCallHandler.getCallBackHandler()
        args.add(callbackHandle)
        args.add(taskId)
        args.add(status)
        args.add(progress)
        synchronized(isolateStarted) {
            if (!isolateStarted.get()) {
                isolateQueue.add(args)
            } else {
                Handler(context.mainLooper).post { backgroundChannel!!.invokeMethod("", args) }
            }
        }
    }

    private fun startBackgroundIsolate(context: Context) {
        synchronized(isolateStarted) {
            if (backgroundFlutterView == null) {
                val pref = context.getSharedPreferences(FlutterDownloaderPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                val callbackHandle = pref.getLong(FlutterDownloaderPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                FlutterMain.startInitialization(context) // Starts initialization of the native system, if already initialized this does nothing
                FlutterMain.ensureInitializationComplete(context, null)
                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                backgroundFlutterView = FlutterNativeView(context, true)

                val args = FlutterRunArguments()
                args.bundlePath = FlutterMain.findAppBundlePath()
                args.entrypoint = callbackInfo.callbackName
                args.libraryPath = callbackInfo.callbackLibraryPath
                backgroundFlutterView!!.runFromBundle(args)
            }
        }
        backgroundChannel = MethodChannel(backgroundFlutterView, "vn.hunghd/downloader_background")
        backgroundChannel!!.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "didInitializeDispatcher") {
            synchronized(isolateStarted) {
                while (!isolateQueue.isEmpty()) {
                    backgroundChannel!!.invokeMethod("", isolateQueue.remove())
                }
                isolateStarted.set(true)
                result.success(null)
            }
        } else {
            result.notImplemented()
        }
    }
}