package com.staysafeai.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.staysafeai.app.databinding.ActivityMirrorTestBinding

class MirrorTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMirrorTestBinding

    private var currentStep = 0

    data class TestStep(
        val title: String,
        val instruction: String,
        val detail: String,
        val yesLabel: String,
        val noLabel: String,
        val yesResult: String,
        val noResult: String,
        val yesIsThreат: Boolean
    )

    private val steps = listOf(
        TestStep(
            title = "👆 Fingernail Gap Test",
            instruction = "Place your fingertip on the mirror surface.",
            detail = "Look at where your fingertip meets its reflection.\n\n" +
                "• Normal mirror → there is a GAP between your finger and the reflection\n" +
                "• Two-way mirror → your finger TOUCHES the reflection directly (no gap)\n\n" +
                "This works because a two-way mirror has the reflective coating on the FRONT surface, while a normal mirror has it on the BACK.",
            yesLabel = "No gap — finger touches reflection",
            noLabel = "There is a gap (normal)",
            yesResult = "⚠️ No gap detected — this may be a two-way mirror. Run remaining tests to confirm.",
            noResult = "✅ Gap present — this is likely a normal mirror.",
            yesIsThreат = true
        ),
        TestStep(
            title = "🔦 Flashlight Blackout Test",
            instruction = "Turn off ALL room lights. Press your phone flashlight firmly against the mirror.",
            detail = "In a dark room, press the flashlight flush against the mirror and look for light passing through.\n\n" +
                "• Normal mirror → reflects light back, you cannot see through it\n" +
                "• Two-way mirror → you can see a darkened room on the other side\n\n" +
                "The gap between the mirror and the wall matters — if there's a cavity behind it, it's suspicious.",
            yesLabel = "I can see through it",
            noLabel = "Cannot see through (opaque)",
            yesResult = "🚨 Light passed through — HIGH probability two-way mirror with camera behind it.",
            noResult = "✅ Mirror is opaque — likely a normal mirror.",
            yesIsThreат = true
        ),
        TestStep(
            title = "📏 Mirror Mounting Check",
            instruction = "Check how the mirror is attached to the wall.",
            detail = "Hidden camera mirrors are typically:\n\n" +
                "• Mounted flush against wall (no frame gap)\n" +
                "• Unusually heavy for its size\n" +
                "• Positioned facing the bed or bathroom\n" +
                "• Cannot be easily moved or tilted\n" +
                "• Has unusual wiring or brackets nearby\n\n" +
                "Normal decorative mirrors are usually framed and can be tilted.",
            yesLabel = "Suspicious mounting (flush, heavy, facing bed)",
            noLabel = "Normal mounting with frame gap",
            yesResult = "⚠️ Suspicious mounting detected. Combined with other findings this increases threat level.",
            noResult = "✅ Normal mounting — no issues found.",
            yesIsThreат = true
        ),
        TestStep(
            title = "🌡️ Heat Check",
            instruction = "Hold your palm 2–3cm from the mirror surface for 10 seconds.",
            detail = "A camera behind the mirror generates heat from:\n• The camera sensor\n• The IR illuminators\n• The wireless transmitter\n\n" +
                "A two-way mirror with a camera may feel slightly warmer than a normal mirror, especially near the center or edges where the lens is positioned.",
            yesLabel = "Mirror feels unusually warm",
            noLabel = "Mirror is room temperature",
            yesResult = "⚠️ Heat detected — possible camera behind mirror. Combine with flashlight test.",
            noResult = "✅ Normal temperature — no heat source detected.",
            yesIsThreат = true
        )
    )

    private val results = mutableMapOf<Int, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMirrorTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showStep(0)
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnYes.setOnClickListener {
            recordAnswer(true)
        }
        binding.btnNo.setOnClickListener {
            recordAnswer(false)
        }
        binding.btnPrevious.setOnClickListener {
            if (currentStep > 0) showStep(currentStep - 1)
        }
    }

    private fun recordAnswer(isYes: Boolean) {
        results[currentStep] = isYes

        // Show result for this step
        val step = steps[currentStep]
        val resultText = if (isYes) step.yesResult else step.noResult
        binding.tvStepResult.text = resultText
        binding.tvStepResult.visibility = View.VISIBLE
        binding.cardResult.visibility = View.VISIBLE
        binding.cardResult.setCardBackgroundColor(
            getColor(
                if (isYes && step.yesIsThreат) com.staysafeai.app.R.color.danger_red_bg
                else com.staysafeai.app.R.color.safe_green_bg
            )
        )

        // Advance after short delay
        binding.root.postDelayed({
            if (currentStep < steps.size - 1) {
                showStep(currentStep + 1)
            } else {
                showFinalResult()
            }
        }, 2000)
    }

    private fun showStep(index: Int) {
        currentStep = index
        val step = steps[index]

        binding.tvStepNumber.text = "Step ${index + 1} of ${steps.size}"
        binding.tvStepTitle.text = step.title
        binding.tvStepInstruction.text = step.instruction
        binding.tvStepDetail.text = step.detail
        binding.btnYes.text = step.yesLabel
        binding.btnNo.text = step.noLabel
        binding.tvStepResult.visibility = View.GONE
        binding.cardResult.visibility = View.GONE

        binding.progressSteps.progress = ((index + 1) * 100 / steps.size)
        binding.btnPrevious.visibility = if (index > 0) View.VISIBLE else View.GONE
    }

    private fun showFinalResult() {
        val threatCount = results.count { (step, isYes) ->
            isYes && steps[step].yesIsThreат
        }

        binding.layoutSteps.visibility = View.GONE
        binding.layoutFinalResult.visibility = View.VISIBLE

        when {
            threatCount >= 3 -> {
                binding.tvFinalTitle.text = "🚨 HIGH THREAT — Likely Two-Way Mirror Camera"
                binding.tvFinalDetail.text = "$threatCount/4 tests flagged positive.\n\n" +
                    "Do NOT disturb the mirror. Photograph it, then:\n" +
                    "1. Contact hotel management immediately\n" +
                    "2. Request a different room\n" +
                    "3. If refused, call local police\n" +
                    "4. Report to hotel chain headquarters"
                binding.cardFinalResult.setCardBackgroundColor(getColor(com.staysafeai.app.R.color.danger_red_bg))
            }
            threatCount >= 2 -> {
                binding.tvFinalTitle.text = "⚠️ MEDIUM THREAT — Further Investigation Needed"
                binding.tvFinalDetail.text = "$threatCount/4 tests flagged positive.\n\n" +
                    "There is a possibility of a two-way mirror camera. Consider:\n" +
                    "1. Requesting hotel maintenance to check the mirror\n" +
                    "2. Covering the mirror if uncomfortable\n" +
                    "3. Running the CamGuard full sensor scan"
                binding.cardFinalResult.setCardBackgroundColor(getColor(com.staysafeai.app.R.color.warning_orange_bg))
            }
            threatCount == 1 -> {
                binding.tvFinalTitle.text = "ℹ️ LOW CONCERN — One Flag"
                binding.tvFinalDetail.text = "Only 1 test flagged. Likely a false positive.\n\n" +
                    "The mirror is probably normal. If still concerned:\n" +
                    "1. Cover the mirror with a towel when changing\n" +
                    "2. Run the full sensor scan"
                binding.cardFinalResult.setCardBackgroundColor(getColor(com.staysafeai.app.R.color.caution_yellow_bg))
            }
            else -> {
                binding.tvFinalTitle.text = "✅ MIRROR APPEARS SAFE"
                binding.tvFinalDetail.text = "All 4 tests passed. This is a normal mirror.\n\n" +
                    "No evidence of hidden camera found."
                binding.cardFinalResult.setCardBackgroundColor(getColor(com.staysafeai.app.R.color.safe_green_bg))
            }
        }

        binding.btnRetestMirror.setOnClickListener {
            results.clear()
            showStep(0)
            binding.layoutSteps.visibility = View.VISIBLE
            binding.layoutFinalResult.visibility = View.GONE
        }

        binding.btnDoneMirror.setOnClickListener { finish() }
    }
}
