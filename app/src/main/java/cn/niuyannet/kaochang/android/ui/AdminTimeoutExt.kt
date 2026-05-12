package cn.niuyannet.kaochang.android.ui

import android.view.View
import androidx.fragment.app.Fragment

fun Fragment.resetAdminIdleTimer(reason: String) {
    (activity as? AdminInactivityTimeoutActivity)?.resetAdminIdleTimer(reason)
}

fun Fragment.beginAdminBusy(reason: String): AdminBusyState.Token? {
    return (activity as? AdminInactivityTimeoutActivity)?.beginAdminBusy(reason)
}

fun Fragment.endAdminBusy(token: AdminBusyState.Token?) {
    (activity as? AdminInactivityTimeoutActivity)?.endAdminBusy(token)
}

fun Fragment.registerAdminTextInputs(root: View?) {
    (activity as? AdminInactivityTimeoutActivity)?.registerAdminTextInputs(root)
}
