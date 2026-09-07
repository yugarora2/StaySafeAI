package com.staysafeai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.staysafeai.app.databinding.ActivityLoginBinding
import com.staysafeai.app.managers.GoogleAuthManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: GoogleAuthManager

    private val RC_SIGN_IN = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = GoogleAuthManager(this)

        // Already logged in — skip to main
        if (authManager.isLoggedIn()) {
            goToMain()
            return
        }

        setupUI()
    }

    private fun setupUI() {
        binding.btnGoogleSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        // App features preview
        binding.tvFeature1.text = "📡 EMF • 🔴 IR • 📶 Wi-Fi • 🔦 Lens • 🎙️ Mic"
        binding.tvFeature2.text = "🤖 AI Security Expert Evaluation"
        binding.tvFeature3.text = "📍 Pinpoints exact device location"
        binding.tvSubtitle.text = "The world's most advanced hidden camera & bug detector"
    }

    private fun startGoogleSignIn() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnGoogleSignIn.isEnabled = false

        val signInIntent = authManager.googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                binding.progressBar.visibility = View.GONE
                binding.btnGoogleSignIn.isEnabled = true
                android.util.Log.e("LoginActivity", "Google sign in failed", e)
                showError("Sign-in failed: ${e.statusCode}")
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        lifecycleScope.launch {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@LoginActivity)
                    ?: throw Exception("No account")

                val success = authManager.firebaseAuthWithGoogle(account)

                if (success) {
                    goToMain()
                } else {
                    showError("Authentication failed")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnGoogleSignIn.isEnabled = true
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }
}
