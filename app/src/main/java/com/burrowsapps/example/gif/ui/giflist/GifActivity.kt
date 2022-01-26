package com.burrowsapps.example.gif.ui.giflist

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.burrowsapps.example.gif.R
import com.burrowsapps.example.gif.data.ImageService
import com.burrowsapps.example.gif.data.source.network.NetworkResult
import com.burrowsapps.example.gif.data.source.network.TenorResponseDto
import com.burrowsapps.example.gif.data.source.network.TenorService.Companion.DEFAULT_LIMIT_COUNT
import com.burrowsapps.example.gif.databinding.ActivityGifBinding
import com.burrowsapps.example.gif.databinding.DialogPreviewBinding
import com.burrowsapps.example.gif.di.GlideApp
import com.burrowsapps.example.gif.ui.license.LicenseActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main activity that will load our Fragments via the Support Fragment Manager.
 */
@AndroidEntryPoint
class GifActivity : AppCompatActivity() {
  @Inject internal lateinit var imageService: ImageService
  @Inject internal lateinit var clipboardManager: ClipboardManager
  internal val gifViewModel by viewModels<GifViewModel>()
  private lateinit var binding: ActivityGifBinding
  private lateinit var dialogBinding: DialogPreviewBinding
  private lateinit var gridLayoutManager: GridLayoutManager
  private lateinit var gifItemDecoration: GifItemDecoration
  private lateinit var gifAdapter: GifAdapter
  private lateinit var gifDialog: AppCompatDialog
  private var hasSearchedImages = false
  private var previousImageCount = 0
  private var loadingImages = true
  private var firstVisibleImage = 0
  private var visibleImageCount = 0
  private var totalImageCount = 0
  private var nextPageNumber: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityGifBinding.inflate(layoutInflater).also { setContentView(it.root) }
    dialogBinding = DialogPreviewBinding.inflate(layoutInflater)

    // Setup
    binding.toolbar.setTitle(R.string.main_screen_title)
    setSupportActionBar(binding.toolbar)

    gridLayoutManager = GridLayoutManager(this, PORTRAIT_COLUMNS)
    gifItemDecoration = GifItemDecoration(
      resources.getDimensionPixelSize(R.dimen.gif_adapter_item_offset),
      gridLayoutManager.spanCount
    )
    gifAdapter = GifAdapter(onItemClick = { imageInfoModel ->
      showImageDialog(imageInfoModel)
    }, imageService)

    // Setup RecyclerView
    binding.recyclerView.apply {
      layoutManager = gridLayoutManager
      addItemDecoration(gifItemDecoration)
      adapter = gifAdapter
      setHasFixedSize(true)
      setItemViewCacheSize(DEFAULT_LIMIT_COUNT) // default 2
      recycledViewPool.setMaxRecycledViews(0, PORTRAIT_COLUMNS * 2) // default 5
      addRecyclerListener { holder ->
        val gifViewHolder = holder as GifAdapter.ViewHolder
        GlideApp.with(this).clear(gifViewHolder.binding.gifImage)
      }
      addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
          override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)

            // Continuous scrolling
            visibleImageCount = recyclerView.childCount
            totalImageCount = gridLayoutManager.itemCount
            firstVisibleImage = gridLayoutManager.findFirstVisibleItemPosition()

            if (loadingImages && totalImageCount > previousImageCount) {
              loadingImages = false
              previousImageCount = totalImageCount
            }

            if (!loadingImages &&
              totalImageCount - visibleImageCount <= firstVisibleImage + VISIBLE_THRESHOLD
            ) {
              gifViewModel.loadTrendingImages(nextPageNumber)

              loadingImages = true
            }
          }
        }
      )
    }

    // Custom view for Dialog
    gifDialog = AppCompatDialog(this).apply {
      setContentView(dialogBinding.root)
      setCancelable(true)
      setCanceledOnTouchOutside(true)
      setOnDismissListener {
        // https://github.com/bumptech/glide/issues/624#issuecomment-140134792
        GlideApp.with(dialogBinding.gifDialogImage.context)
          .clear(dialogBinding.gifDialogImage) // Forget view, try to free resources
        dialogBinding.gifDialogImage.setImageDrawable(null)
        dialogBinding.gifDialogProgress.show() // Make sure to show progress when loadingImages new view
      }

      // Remove "white" background for gifDialog
      window?.decorView?.setBackgroundResource(android.R.color.transparent)
    }

    // Load initial images
    gifViewModel.apply {
      loadTrendingImages(nextPageNumber)

      trendingResponse.observe(this@GifActivity) { response ->
        when (response) {
          is NetworkResult.Success -> addImages(response.data!!)
          is NetworkResult.Error -> {
            // show error message
          }
          is NetworkResult.Loading -> {} // show empty state
        }
      }

      searchResponse.observe(this@GifActivity) { response ->
        when (response) {
          is NetworkResult.Success -> addImages(response.data!!)
          is NetworkResult.Error -> {
            // show error message
          }
          is NetworkResult.Loading -> {} // show empty state
        }
      }

      nextPageResponse.observe(this@GifActivity) { response ->
        nextPageNumber = response
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    super.onCreateOptionsMenu(menu)
    menuInflater.inflate(R.menu.menu_main, menu)

    menu.findItem(R.id.menuSearch).apply {
      // Set contextual action on search icon click
      setOnActionExpandListener(
        object : MenuItem.OnActionExpandListener {
          override fun onMenuItemActionExpand(item: MenuItem) = true

          override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
            // When search is closed, go back to trending getResults
            if (hasSearchedImages) {
              // Reset
              clearImages()
              nextPageNumber = null
              gifViewModel.loadTrendingImages(nextPageNumber)

              hasSearchedImages = false
            }
            return true
          }
        }
      )

      (actionView as SearchView).apply {
        queryHint = context.getString(R.string.search_gifs)
        // Query listener for search bar
        setOnQueryTextListener(
          object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
              // Search on type
              if (newText.isNotEmpty()) {
                // Reset
                clearImages()
                gifViewModel.loadSearchImages(newText, nextPageNumber)

                hasSearchedImages = true
              }
              return false
            }

            override fun onQueryTextSubmit(query: String) = false
          }
        )
      }
    }
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.menuLicenses -> {
        startActivity(LicenseActivity.createIntent(this))
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  internal fun clearImages() {
    // Clear current data
    gifAdapter.clear()
  }

  private fun addImages(responseDto: TenorResponseDto) {
    val newList = responseDto.results.map { result ->
      val media = result.media.first()
      val tinyGif = media.tinyGif
      val gif = media.gif
      val gifUrl = gif.url

      if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, "ORIGINAL_IMAGE_URL\t $gifUrl")

      GifImageInfo(tinyGif.url, tinyGif.preview, gifUrl, gif.preview)
    }

    gifAdapter.add(newList)
  }

  private fun showImageDialog(imageInfoModel: GifImageInfo) {
    // Load associated text
    dialogBinding.gifDialogTitle.apply {
      text = imageInfoModel.gifUrl
      setOnClickListener {
        clipboardManager.setPrimaryClip(
          ClipData.newPlainText(
            "https-image-url",
            imageInfoModel.gifUrl
          )
        )
        Snackbar.make(binding.root, getString(R.string.copied_to_clipboard), LENGTH_SHORT).show()
      }
    }

    // Load image
    imageService.load(imageInfoModel.gifUrl)
      .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
      .thumbnail(imageService.load(imageInfoModel.gifPreviewUrl))
      .listener(
        object : RequestListener<GifDrawable> {
          override fun onResourceReady(
            resource: GifDrawable?,
            model: Any?,
            target: Target<GifDrawable>?,
            dataSource: DataSource?,
            isFirstResource: Boolean
          ): Boolean {
            // Hide progressbar
            dialogBinding.gifDialogProgress.hide()
            dialogBinding.gifDialogTitle.visibility = View.VISIBLE

            if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, "finished loadingImages\t $model")

            return false
          }

          override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<GifDrawable>?,
            isFirstResource: Boolean
          ): Boolean {
            // Hide progressbar
            dialogBinding.gifDialogProgress.hide()

            if (Log.isLoggable(TAG, Log.ERROR)) Log.e(TAG, "finished loadingImages\t $model", e)

            return false
          }
        }
      ).into(dialogBinding.gifDialogImage)

    gifDialog.show()
  }

  companion object {
    private const val TAG = "MainActivity"
    private const val PORTRAIT_COLUMNS = 3
    private const val VISIBLE_THRESHOLD = 5
  }
}