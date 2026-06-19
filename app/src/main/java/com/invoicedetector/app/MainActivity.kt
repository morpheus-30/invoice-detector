package com.invoicedetector.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.invoicedetector.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // System photo/content picker - no storage permission needed.
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            binding.previewPlaceholder.visibility = View.GONE
            binding.preview.setImageURI(uri)
            viewModel.process(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectButton.setOnClickListener { pickImage.launch("image/*") }
        binding.clearButton.setOnClickListener { viewModel.clearIndex() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MainViewModel.UiState) {
        val processing = state is MainViewModel.UiState.Processing
        binding.progress.visibility = if (processing) View.VISIBLE else View.GONE
        binding.selectButton.isEnabled = !processing

        when (state) {
            is MainViewModel.UiState.Idle -> {
                binding.resultCard.visibility = View.GONE
            }
            is MainViewModel.UiState.Processing -> {
                binding.resultCard.visibility = View.GONE
            }
            is MainViewModel.UiState.Info -> {
                binding.resultCard.visibility = View.GONE
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_SHORT).show()
            }
            is MainViewModel.UiState.Done -> {
                showResult(ResultFormatter.format(state.result))
            }
        }
    }

    private fun showResult(formatted: FormattedResult) {
        val color = ContextCompat.getColor(this, levelColor(formatted.level))

        binding.resultCard.visibility = View.VISIBLE
        binding.verdictText.text = formatted.verdict
        binding.verdictText.setTextColor(color)
        binding.verdictSubtitle.text = formatted.subtitle

        binding.verdictIcon.setImageResource(formatted.iconRes)
        binding.verdictIcon.backgroundTintList = ColorStateList.valueOf(color)

        populateFields(formatted.fields)
    }

    private fun populateFields(fields: List<Pair<String, String>>) {
        binding.fieldsContainer.removeAllViews()
        binding.fieldsDivider.visibility = if (fields.isEmpty()) View.GONE else View.VISIBLE
        for ((label, value) in fields) {
            binding.fieldsContainer.addView(buildFieldRow(label, value))
        }
    }

    private fun buildFieldRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(6)
            setPadding(0, pad, 0, pad)
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            textSize = 14f
            gravity = Gravity.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
        }
        row.addView(labelView)
        row.addView(valueView)
        return row
    }

    private fun levelColor(level: ResultLevel): Int = when (level) {
        ResultLevel.OK -> R.color.ok_green
        ResultLevel.WARN -> R.color.warn_amber
        ResultLevel.ERROR -> R.color.error_red
        ResultLevel.INFO -> R.color.info_blue
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
