package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LibrePodsHook"
const val ACTION_INJECT_COLOROS_BATTERY = "me.kavishdevar.librepods.ACTION_INJECT_COLOROS_BATTERY"
const val EXTRA_COLOROS_BATTERY_MAC = "mac"
const val EXTRA_COLOROS_BATTERY_ARRAY = "battery"

@SuppressLint("DiscouragedApi", "PrivateApi")
class KotlinModule: XposedModule() {
    private val injectedBatteryByMac = ConcurrentHashMap<String, IntArray>()
    private val colorOsReceiverRegistered = AtomicBoolean(false)
    @Volatile
    private var oplusRemoteDevicesExt: Any? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module initialized at :: ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "onPackageLoaded :: ${param.packageName}")

        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            log(Log.INFO, TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")
            try {
                if (param.isFirstPackage) {
                    val abi = android.os.Build.SUPPORTED_ABIS.first()
                    val soName = "libfluoride_hooks.so"

                    val candidates = buildList {
                        add("${moduleApplicationInfo.sourceDir}!/lib/$abi/$soName")

                        moduleApplicationInfo.splitSourceDirs?.forEach { split ->
                            add("$split!/lib/$abi/$soName")
                        }
                    }

                    var loaded = false

                    for (path in candidates) {
                        try {
                            log(Log.INFO, TAG, "Trying to load native lib from $path")
                            System.load(path)
                            log(Log.INFO, TAG, "Loaded native lib from $path")
                            loaded = true
                            break
                        } catch (e: Throwable) {
                            log(Log.WARN, TAG, "Failed to load from $path: ${e.message}")
                        }
                    }

                    if (!loaded) {
                        log(Log.ERROR, TAG, "Could not load $soName from base or splits")
                        return
                    }

                    val remotePrefValue = getRemotePreferences("me.kavishdevar.librepods").getBoolean("vendor_id_hook", false)
                    log(Log.INFO, TAG, "sdp hook enabled (remote pref): $remotePrefValue")
                    NativeBridge.setSdpHook(remotePrefValue)
                    log(Log.INFO, TAG, "Native library loaded successfully")

                    hookColorOsBluetoothBattery(param)
                }
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "Failed to load native library: ${e.message}")
            }
        }

        if (param.packageName == "com.google.android.settings") {
            hookSettingsController(param, "com.google.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }

        if (param.packageName == "com.android.settings") {
            hookSettingsController(param, "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }
    }

    /**
     * Hooks ColorOS's OplusRemoteDevicesExtImpl & OplusAdapterServiceExtImpl inside
     * com.android.bluetooth so LibrePods can feed live AirPods L/R/Case battery into
     * ColorOS's native TWS battery pipeline (Bluetooth Settings, QS, Fluid Cloud, Status Bar).
     *
     * OplusRemoteDevicesExtImpl lives in /system_ext/framework/oplus_bluetooth_common_ext.jar
     * (loaded dynamically by OplusBTFactory), so we hook RemoteDevices's constructor in
     * Bluetooth.apk's classloader and obtain the live mOplusRemoteDevice instance + its
     * classloader from there. Safely no-ops on non-ColorOS ROMs.
     */
    private val colorOsHooksInstalled = AtomicBoolean(false)

    private fun hookColorOsBluetoothBattery(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        val remoteDevicesClass = try {
            Class.forName("com.android.bluetooth.btservice.RemoteDevices", false, cl)
        } catch (_: ClassNotFoundException) {
            log(Log.INFO, TAG, "RemoteDevices not found; skipping ColorOS battery hook")
            return
        }

        val oplusField = try {
            remoteDevicesClass.getDeclaredField("mOplusRemoteDevice").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            log(Log.INFO, TAG, "RemoteDevices.mOplusRemoteDevice not present (non-ColorOS); skipping")
            return
        }

        try {
            for (ctor in remoteDevicesClass.declaredConstructors) {
                hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    val remoteDevices = chain.thisObject
                    val instance = remoteDevices?.let { oplusField.get(it) }
                    if (instance != null && !instance.javaClass.name.contains("IOplusRemoteDevice")) {
                        oplusRemoteDevicesExt = instance
                        installColorOsExtHooks(instance)
                        registerColorOsBatteryReceiverIfNeeded(instance, instance.javaClass)
                    }
                    result
                }
            }
            log(Log.INFO, TAG, "Hooked RemoteDevices constructor for ColorOS battery injection")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook RemoteDevices constructor: ${e.message}")
        }
    }

    private fun installColorOsExtHooks(remoteDevicesExt: Any) {
        if (!colorOsHooksInstalled.compareAndSet(false, true)) return
        val remoteDevicesExtClass = remoteDevicesExt.javaClass
        val extCl = remoteDevicesExtClass.classLoader
        try {
            val getBatteryInfoMethod = remoteDevicesExtClass.getDeclaredMethod(
                "getBatteryInfo",
                BluetoothDevice::class.java
            )
            hook(getBatteryInfoMethod).intercept { chain ->
                val device = chain.args.getOrNull(0) as? BluetoothDevice
                val mac = device?.address?.uppercase()
                val injected = mac?.let { injectedBatteryByMac[it] }
                if (injected != null) {
                    injected.copyOf()
                } else {
                    chain.proceed()
                }
            }

            runCatching {
                val is3rdEarBudsMethod = remoteDevicesExtClass.getDeclaredMethod(
                    "is3rdEarBudsDevice",
                    BluetoothDevice::class.java
                )
                hook(is3rdEarBudsMethod).intercept { chain ->
                    val device = chain.args.getOrNull(0) as? BluetoothDevice
                    val mac = device?.address?.uppercase()
                    if (mac != null && injectedBatteryByMac.containsKey(mac)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            }.onFailure {
                log(Log.WARN, TAG, "Could not hook OplusRemoteDevicesExtImpl.is3rdEarBudsDevice: ${it.message}")
            }

            runCatching {
                val adapterExtClass = Class.forName(
                    "com.oplus.bluetooth.btservice.OplusAdapterServiceExtImpl",
                    false,
                    extCl
                )
                val checkIsAirpodsMethod = adapterExtClass.getDeclaredMethod(
                    "checkIsAirpodsDevice",
                    BluetoothDevice::class.java
                )
                hook(checkIsAirpodsMethod).intercept { chain ->
                    val device = chain.args.getOrNull(0) as? BluetoothDevice
                    val mac = device?.address?.uppercase()
                    if (mac != null && injectedBatteryByMac.containsKey(mac)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            }.onFailure {
                log(Log.WARN, TAG, "Could not hook OplusAdapterServiceExtImpl.checkIsAirpodsDevice: ${it.message}")
            }

            log(Log.INFO, TAG, "Installed ColorOS battery hooks on ${remoteDevicesExtClass.name}")
        } catch (e: Throwable) {
            colorOsHooksInstalled.set(false)
            log(Log.ERROR, TAG, "Failed to install ColorOS ext hooks: ${e.message}")
        }
    }

    private fun registerColorOsBatteryReceiverIfNeeded(
        remoteDevicesExt: Any,
        remoteDevicesExtClass: Class<*>
    ) {
        if (!colorOsReceiverRegistered.compareAndSet(false, true)) return
        try {
            val adapterField = remoteDevicesExtClass.getDeclaredField("mAdapterService").apply {
                isAccessible = true
            }
            val adapterContext = adapterField.get(remoteDevicesExt) as? Context
            if (adapterContext == null) {
                colorOsReceiverRegistered.set(false)
                return
            }

            val setTransportMethod = runCatching {
                remoteDevicesExtClass.getDeclaredMethod(
                    "setOplusDeviceProtocolTransport",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
            }.getOrNull()

            val notifyBatteryMethod = remoteDevicesExtClass.getDeclaredMethod(
                "notifyBatteryLevelChanged",
                BluetoothDevice::class.java,
                IntArray::class.java
            ).apply { isAccessible = true }

            val receiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_INJECT_COLOROS_BATTERY) return
                    val mac = intent.getStringExtra(EXTRA_COLOROS_BATTERY_MAC)?.uppercase() ?: return
                    val battery = intent.getIntArrayExtra(EXTRA_COLOROS_BATTERY_ARRAY)
                    if (battery == null || battery.size < 6) {
                        injectedBatteryByMac.remove(mac)
                        return
                    }

                    val allDisconnected = battery[0] < 0 && battery[1] < 0 && battery[2] < 0
                    if (allDisconnected) {
                        injectedBatteryByMac.remove(mac)
                    } else {
                        injectedBatteryByMac[mac] = battery.copyOf(6)
                    }

                    val ext = oplusRemoteDevicesExt ?: return
                    try {
                        @Suppress("DEPRECATION")
                        val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac) ?: return
                        if (!allDisconnected) {
                            setTransportMethod?.invoke(ext, device, 0)
                        }
                        notifyBatteryMethod.invoke(ext, device, battery.copyOf(6))
                        log(Log.INFO, TAG, "Injected ColorOS battery for ${mac.take(8)}: ${battery.contentToString()}")
                    } catch (e: Throwable) {
                        log(Log.WARN, TAG, "Failed to inject ColorOS battery: ${e.message}")
                    }
                }
            }

            adapterContext.registerReceiver(
                receiver,
                IntentFilter(ACTION_INJECT_COLOROS_BATTERY),
                "android.permission.BLUETOOTH_PRIVILEGED",
                null,
                Context.RECEIVER_EXPORTED
            )
            log(Log.INFO, TAG, "Registered ColorOS battery injection receiver in com.android.bluetooth")
        } catch (e: Throwable) {
            colorOsReceiverRegistered.set(false)
            log(Log.ERROR, TAG, "Failed to register ColorOS battery receiver: ${e.message}")
        }
    }

    private fun hookSettingsController(param: PackageLoadedParam, className: String) {
        log(Log.INFO, TAG, "Settings app detected, hooking Bluetooth icon handling")
        try {
            val headerControllerClass = Class.forName(className, false, param.defaultClassLoader)
            val updateIconMethod = headerControllerClass.getDeclaredMethod(
                "updateIcon",
                ImageView::class.java,
                String::class.java
            )

            hook(updateIconMethod).intercept { chain ->
                try {
                    log(Log.INFO, TAG, "Bluetooth icon hook called with args: ${chain.args.joinToString(", ")}")
                    val imageView = chain.args[0] as? ImageView
                    val iconUri = chain.args[1] as? String

                    if (imageView == null || iconUri == null) {
                        return@intercept chain.proceed()
                    }

                    val uri = iconUri.toUri()
                    if (!uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        return@intercept chain.proceed()
                    }

                    log(Log.INFO, TAG, "Handling AirPods icon URI: $uri")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val context = imageView.context
                            val packageName = uri.authority ?: return@post
                            val packageContext = context.createPackageContext(
                                packageName,
                                Context.CONTEXT_IGNORE_SECURITY
                            )

                            val resPath = uri.pathSegments
                            if (resPath.size >= 2 && resPath[0] == "drawable") {
                                val resourceName = resPath[1]
                                val resourceId = packageContext.resources.getIdentifier(
                                    resourceName, "drawable", packageName
                                )

                                if (resourceId != 0) {
                                    val drawable = packageContext.resources.getDrawable(
                                        resourceId, packageContext.theme
                                    )
                                    imageView.setImageDrawable(drawable)
                                    imageView.alpha = 1.0f
                                    log(Log.INFO, TAG, "Successfully loaded icon from resource: $resourceName")
                                } else {
                                    log(Log.ERROR, TAG, "Resource not found: $resourceName")
                                }
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Error loading resource from URI $uri: ${e.message}")
                        }
                    }
                    null
                } catch (e: Exception) {
                    log(Log.ERROR, TAG, "Error in Bluetooth icon hook: ${e.message}")
                    chain.proceed()
                }
            }

            log(Log.INFO, TAG, "Successfully hooked updateIcon method in Bluetooth settings")
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook Bluetooth icon handler: ${e.message}")
        }
    }
}


object NativeBridge {
    external fun setSdpHook(enabled: Boolean)
}
