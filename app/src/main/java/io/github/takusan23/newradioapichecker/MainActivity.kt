package io.github.takusan23.newradioapichecker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.telephony.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.takusan23.newradioapichecker.databinding.ActivityMainBinding
import kotlinx.coroutines.channels.awaitClose

class MainActivity : AppCompatActivity() {

    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val telephonyManager by lazy { getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }
    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    // コールバック解除用
    private var connectivityManagerCallback: ConnectivityManager.NetworkCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.all { it.value }) {
            startListen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // 権限確認
        if (isGranted()) {
            // あるので監視開始
            startListen()
        } else {
            // ない
            permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE))
        }
    }

    private fun startListen() {
        listenUnlimitedNetwork {
            viewBinding.activityMainMeteredTextview.text = if (it) "無制限のネットワーク接続" else "上限ありのネットワーク接続"
        }
        listenNewRadio(
            onCellInfoCallback = {
                viewBinding.activityMainBandTextview.text = when (it) {
                    is CellInfoLte -> """LTE
接続中バンド：${it.cellIdentity.bands.map { it.toString() }} (${it.cellIdentity.earfcn})"""
                    is CellInfoNr -> """5G
接続中バンド：${(it.cellIdentity as CellIdentityNr).bands.map { it.toString() }} (${(it.cellIdentity as CellIdentityNr).nrarfcn})
${if ((it.cellIdentity as CellIdentityNr).nrarfcn > 2054166) "ミリ波に接続中" else "Sub-6に接続中"}"""
                    else -> "それ以外"
                }
            },
            onDisplayInfoCallback = {
                viewBinding.activityMainNewRadioTextview.text = when (it.overrideNetworkType) {
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE Advanced Pro（5Ge）"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE キャリアアグリゲーション"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G Sub-6 ネットワーク"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "5G ミリ波 ネットワーク (非推奨)"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G ミリ波 (もしくはそれ同等の) ネットワーク"
                    else -> "それ以外"
                }
            },
            onAnchorBandCallback = {
                viewBinding.activityMainAnchorBandTextview.text = if (it) "アンカーバンド接続中" else "4G接続中、もしくはアンカーバンドではありません。"
            }
        )
    }

    private fun isGranted(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val readPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        return (fineLocation == PackageManager.PERMISSION_GRANTED) && (readPhoneState == PackageManager.PERMISSION_GRANTED)
    }

    private fun listenNewRadio(
        onCellInfoCallback: (CellInfo) -> Unit,
        onDisplayInfoCallback: (TelephonyDisplayInfo) -> Unit,
        onAnchorBandCallback: (Boolean) -> Unit,
    ) {

        // onCellInfoChanged onDisplayInfoChanged の結果を一時的に持っておく
        var tempCellInfo: CellInfo? = null
        var tempTelephonyDisplayInfo: TelephonyDisplayInfo? = null

        // アンカーバンドかどうかを送る
        fun checkAnchorBand() {
            if (tempCellInfo == null && tempTelephonyDisplayInfo == null) return
            // CellInfoがLTEのもので、実際に表示しているアイコンが5Gの場合はアンカーバンド
            val isAnchorBand = tempCellInfo is CellInfoLte && tempTelephonyDisplayInfo?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
            onAnchorBandCallback(isAnchorBand)
        }

        // Android 12より書き方が変わった
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener, TelephonyCallback.CellInfoListener {
                /** 実際の状態 */
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    onCellInfoCallback(cellInfo[0])
                    tempCellInfo = cellInfo[0]
                    checkAnchorBand()
                }

                /** アンテナピクトと同じやつ */
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    onDisplayInfoCallback(telephonyDisplayInfo)
                    tempTelephonyDisplayInfo = telephonyDisplayInfo
                    checkAnchorBand()
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback!!)
        } else {
            phoneStateListener = object : PhoneStateListener() {

                @SuppressLint("MissingPermission")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    super.onCellInfoChanged(cellInfo)
                    cellInfo?.get(0)?.let { onCellInfoCallback(it) }
                    tempCellInfo = cellInfo?.get(0)
                    checkAnchorBand()
                }

                @SuppressLint("MissingPermission")
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    super.onDisplayInfoChanged(telephonyDisplayInfo)
                    onDisplayInfoCallback(telephonyDisplayInfo)
                    tempTelephonyDisplayInfo = telephonyDisplayInfo
                    checkAnchorBand()
                }
            }
            telephonyManager.listen(phoneStateListener!!, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED or PhoneStateListener.LISTEN_CELL_INFO)
        }
    }

    private fun listenUnlimitedNetwork(onResult: (Boolean) -> Unit) {
        connectivityManagerCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                // 無制限プランを契約している場合はtrue
                val isUnlimited = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                onResult(isUnlimited)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(connectivityManagerCallback!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManagerCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        }
    }

}