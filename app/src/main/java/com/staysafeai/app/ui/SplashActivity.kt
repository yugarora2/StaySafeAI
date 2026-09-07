package com.staysafeai.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.staysafeai.app.managers.GoogleAuthManager

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = GoogleAuthManager(this)
        val next = if (auth.isLoggedIn()) MainActivity::class.java else LoginActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
