package io.github.onecdx.app

import android.app.Application
import io.github.onecdx.app.editor.TextMateSetup

class OnecdxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TextMateSetup.start(this)
    }
}
