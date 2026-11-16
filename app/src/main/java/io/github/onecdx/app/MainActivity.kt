package io.github.onecdx.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Placeholder sementara sampai Milestone 1 (Core Editor & DeX Layout) dikerjakan.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.placeholder, BuildInfo.versionName(this@MainActivity))
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#D4D4D4"))
                setBackgroundColor(Color.parseColor("#1E1E1E"))
            },
        )
    }
}

private object BuildInfo {
    fun versionName(activity: Activity): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "dev"
}
