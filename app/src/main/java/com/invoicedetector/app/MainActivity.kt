package com.invoicedetector.app

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        binding.tamperCheck.setOnCheckedChangeListener { _, checked ->
            viewModel.setTamperCheck(checked)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MainViewModel.UiState) {
        val processing = state is MainViewModel.UiState.Processing
        binding.progress.visibility = if (processing) android.view.View.VISIBLE else android.view.View.GONE
        binding.selectButton.isEnabled = !processing

        when (state) {
            is MainViewModel.UiState.Idle -> {
                binding.statusText.text = getString(R.string.status_idle)
                binding.detailText.text = ""
            }
            is MainViewModel.UiState.Processing -> {
                binding.statusText.text = getString(R.string.status_processing)
                binding.detailText.text = ""
            }
            is MainViewModel.UiState.Info -> {
                binding.statusText.text = state.message
                binding.detailText.text = ""
            }
            is MainViewModel.UiState.Done -> {
                val formatted = ResultFormatter.format(state.result)
                binding.statusText.text = formatted.status
                binding.detailText.text = formatted.detail
                val colorRes = when (formatted.level) {
                    ResultLevel.OK -> R.color.ok_green
                    ResultLevel.WARN -> R.color.warn_amber
                    ResultLevel.ERROR -> R.color.error_red
                }
                binding.statusText.setTextColor(ContextCompat.getColor(this, colorRes))
            }
        }
    }
}
