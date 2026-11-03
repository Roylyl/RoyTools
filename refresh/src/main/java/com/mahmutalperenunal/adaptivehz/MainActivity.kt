package com.mahmutalperenunal.adaptivehz

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Compatibility entry for existing refresh-rate notification intents. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent().setClassName(packageName, "com.github.kr328.clash.MainActivity")
            .putExtra("roytools_tab", "refresh").addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
