package com.topjohnwu.magisk.ui.surequest

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.hardware.fingerprint.FingerprintManager
import android.os.CountDownTimer
import android.text.TextUtils
import com.skoumal.teanity.databinding.ComparableRvItem
import com.skoumal.teanity.util.DiffObservableList
import com.skoumal.teanity.util.KObservableField
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.Config
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.data.database.MagiskDB
import com.topjohnwu.magisk.model.entity.Policy
import com.topjohnwu.magisk.model.entity.recycler.SpinnerRvItem
import com.topjohnwu.magisk.model.events.DieEvent
import com.topjohnwu.magisk.ui.base.MagiskViewModel
import com.topjohnwu.magisk.utils.FingerprintHelper
import com.topjohnwu.magisk.utils.SuConnector
import com.topjohnwu.magisk.utils.now
import me.tatarka.bindingcollectionadapter2.ItemBinding
import java.io.IOException
import java.util.concurrent.TimeUnit.*

class SuRequestViewModel(
    private val packageManager: PackageManager,
    private val database: MagiskDB,
    private val timeoutPrefs: SharedPreferences,
    private val resources: Resources
) : MagiskViewModel() {

    val icon = KObservableField<Drawable?>(null)
    val title = KObservableField("")
    val packageName = KObservableField("")

    val denyText = KObservableField(resources.getString(R.string.deny))
    val warningText = KObservableField<CharSequence>(resources.getString(R.string.su_warning))

    val canUseFingerprint = KObservableField(FingerprintHelper.useFingerprint())
    val selectedItemPosition = KObservableField(0)

    val items = DiffObservableList(ComparableRvItem.callback)
    val itemBinding = ItemBinding.of<ComparableRvItem<*>> { binding, _, item ->
        item.bind(binding)
    }


    var handler: ActionHandler? = null
    private var timer: CountDownTimer? = null
    private var policy: Policy? = null
        set(value) {
            field = value
            updatePolicy(value)
        }

    init {
        resources.getStringArray(R.array.allow_timeout)
            .map { SpinnerRvItem(it) }
            .let { items.update(it) }
    }

    private fun updatePolicy(policy: Policy?) {
        policy ?: return

        icon.value = policy.info.loadIcon(packageManager)
        title.value = policy.appName
        packageName.value = policy.packageName

        selectedItemPosition.value = timeoutPrefs.getInt(policy.packageName, 0)
    }

    private fun cancelTimer() {
        timer?.cancel()
        denyText.value = resources.getString(R.string.deny)
    }

    fun grantPressed() {
        handler?.handleAction(Policy.ALLOW)
        timer?.cancel()
    }

    fun denyPressed() {
        handler?.handleAction(Policy.DENY)
        timer?.cancel()
    }

    fun spinnerTouched(): Boolean {
        cancelTimer()
        return false
    }

    fun handleRequest(intent: Intent, createUICallback: () -> Unit): Boolean {
        val socketName = intent.getStringExtra("socket") ?: return false

        val connector: SuConnector
        try {
            connector = object : SuConnector(socketName) {
                @Throws(IOException::class)
                override fun onResponse() {
                    out.writeInt(policy?.policy ?: return)
                }
            }
            val bundle = connector.readSocketInput()
            val uid = bundle.getString("uid")?.toIntOrNull() ?: return false
            database.clearOutdated()
            policy = database.getPolicy(uid) ?: Policy(uid, packageManager)
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            return false
        }

        handler = object : ActionHandler() {
            override fun handleAction() {
                connector.response()
                done()
            }

            override fun handleAction(action: Int) {
                val pos = selectedItemPosition.value
                timeoutPrefs.edit().putInt(policy?.packageName, pos).apply()
                handleAction(action, Config.Value.TIMEOUT_LIST[pos])
            }

            override fun handleAction(action: Int, time: Int) {
                policy?.apply {
                    policy = action
                    if (time >= 0) {
                        until = if (time == 0) {
                            0
                        } else {
                            MILLISECONDS.toSeconds(now) + MINUTES.toSeconds(time.toLong())
                        }
                        database.updatePolicy(this)
                    }
                }
                policy?.policy = action

                handleAction()
            }
        }

        // Never allow com.topjohnwu.magisk (could be malware)
        if (TextUtils.equals(policy?.packageName, BuildConfig.APPLICATION_ID))
            return false

        // If not interactive, response directly
        if (policy?.policy != Policy.INTERACTIVE) {
            handler?.handleAction()
            return true
        }

        when (Config.get<Any>(Config.Key.SU_AUTO_RESPONSE) as Int) {
            Config.Value.SU_AUTO_DENY -> {
                handler?.handleAction(Policy.DENY, 0)
                return true
            }
            Config.Value.SU_AUTO_ALLOW -> {
                handler?.handleAction(Policy.ALLOW, 0)
                return true
            }
        }

        createUICallback()
        showUI()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showUI() {
        val seconds = Config.get<Int>(Config.Key.SU_REQUEST_TIMEOUT).toLong()
        val millis = SECONDS.toMillis(seconds)
        timer = object : CountDownTimer(millis, 1000) {
            override fun onTick(remains: Long) {
                denyText.value = "%s (%d)"
                    .format(resources.getString(R.string.deny), remains / 1000)
            }

            override fun onFinish() {
                denyText.value = resources.getString(R.string.deny)
                handler?.handleAction(Policy.DENY)
            }
        }
        timer?.start()
        handler?.addCancel(Runnable { cancelTimer() })

        val useFP = canUseFingerprint.value

        if (useFP)
            try {
                val helper = SuFingerprint()
                helper.authenticate()
                handler?.addCancel(Runnable { helper.cancel() })
            } catch (e: Exception) {
                e.printStackTrace()
            }
    }

    private inner class SuFingerprint @Throws(Exception::class)
    internal constructor() : FingerprintHelper() {

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            warningText.value = errString
        }

        override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence) {
            warningText.value = helpString
        }

        override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
            handler?.handleAction(Policy.ALLOW)
        }

        override fun onAuthenticationFailed() {
            warningText.value = resources.getString(R.string.auth_fail)
        }
    }

    open inner class ActionHandler {
        private val cancelTasks = mutableListOf<Runnable>()

        internal open fun handleAction() {
            done()
        }

        internal open fun handleAction(action: Int) {
            done()
        }

        internal open fun handleAction(action: Int, time: Int) {
            done()
        }

        internal fun addCancel(r: Runnable) {
            cancelTasks.add(r)
        }

        internal fun done() {
            cancelTasks.forEach { it.run() }
            DieEvent().publish()
        }
    }

}