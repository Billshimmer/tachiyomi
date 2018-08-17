package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.GlideInputStream
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressBar
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig.ZoomType
import eu.kanade.tachiyomi.util.ImageUtil
import eu.kanade.tachiyomi.util.dpToPx
import eu.kanade.tachiyomi.util.gone
import eu.kanade.tachiyomi.util.visible
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.util.concurrent.TimeUnit


@SuppressLint("ViewConstructor")
class PagerPageHolder(
        val viewer: PagerViewer,
        val page: ReaderPage
) : FrameLayout(viewer.activity), ViewPagerAdapter.PositionableView {

    override val item
        get() = page

    private val progressBar = createProgressBar()

    private var subsamplingImageView: SubsamplingScaleImageView? = null

    private var imageView: ImageView? = null

    private var retryButton: PagerButton? = null

    private var decodeErrorLayout: ViewGroup? = null

    /**
     * Subscription for status changes of the page.
     */
    private var statusSubscription: Subscription? = null

    /**
     * Subscription for progress changes of the page.
     */
    private var progressSubscription: Subscription? = null

    private var readImageHeaderSubscription: Subscription? = null

    init {
        addView(progressBar)
        observeStatus()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubscribeProgress()
        unsubscribeStatus()
        unsubscribeReadImageHeader()
        subsamplingImageView?.setOnImageEventListener(null)
    }

    /**
     * Observes the status of the page and notify the changes.
     *
     * @see processStatus
     */
    private fun observeStatus() {
        statusSubscription?.unsubscribe()

        val loader = page.chapter.pageLoader ?: return
        statusSubscription = loader.getPage(page)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { processStatus(it) }
    }

    /**
     * Observes the progress of the page and updates view.
     */
    private fun observeProgress() {
        progressSubscription?.unsubscribe()

        progressSubscription = Observable.interval(32, TimeUnit.MILLISECONDS) // 30fps
            .map { page.progress }
            .distinctUntilChanged()
            .onBackpressureLatest()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { value -> progressBar.setProgress(value) }
    }

    /**
     * Called when the status of the page changes.
     *
     * @param status the new status of the page.
     */
    private fun processStatus(status: Int) {
        when (status) {
            Page.QUEUE -> setQueued()
            Page.LOAD_PAGE -> setLoading()
            Page.DOWNLOAD_IMAGE -> {
                observeProgress()
                setDownloading()
            }
            Page.READY -> {
                setImage()
                unsubscribeProgress()
            }
            Page.ERROR -> {
                setError()
                unsubscribeProgress()
            }
        }
    }

    /**
     * Unsubscribes from the status subscription.
     */
    private fun unsubscribeStatus() {
        statusSubscription?.unsubscribe()
        statusSubscription = null
    }

    /**
     * Unsubscribes from the progress subscription.
     */
    private fun unsubscribeProgress() {
        progressSubscription?.unsubscribe()
        progressSubscription = null
    }

    private fun unsubscribeReadImageHeader() {
        readImageHeaderSubscription?.unsubscribe()
        readImageHeaderSubscription = null
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressBar.visible()
        retryButton?.gone()
        decodeErrorLayout?.gone()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressBar.visible()
        retryButton?.gone()
        decodeErrorLayout?.gone()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        progressBar.visible()
        retryButton?.gone()
        decodeErrorLayout?.gone()
    }

    /**
     * Called when the page is ready.
     */
    private fun setImage() {
        progressBar.visible()
        progressBar.completeAndFadeOut()
        retryButton?.gone()
        decodeErrorLayout?.gone()

        unsubscribeReadImageHeader()
        val streamFn = page.stream ?: return

        readImageHeaderSubscription = Observable
            .fromCallable { ImageUtil.isAnimatedImage(streamFn) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { isAnimatedImage ->
                if (!isAnimatedImage) {
                    initSubsamplingImageView().setImage(ImageSource.provider(streamFn))
                } else {
                    initImageView().setImage(GlideInputStream(streamFn))
                }
            }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError() {
        progressBar.gone()
        initRetryButton().visible()
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressBar.gone()
    }

    /**
     * Called when an image fails to decode.
     */
    private fun onImageDecodeError() {
        progressBar.gone()
        initDecodeErrorLayout().visible()
    }

    /**
     * Creates a new progress bar.
     */
    @SuppressLint("PrivateResource")
    private fun createProgressBar(): ReaderProgressBar {
        return ReaderProgressBar(context, null).apply {

            val size = 48.dpToPx
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
        }
    }

    /**
     * Initializes a subsampling scale view.
     */
    private fun initSubsamplingImageView(): SubsamplingScaleImageView {
        if (subsamplingImageView != null) return subsamplingImageView!!

        val config = viewer.config

        subsamplingImageView = SubsamplingScaleImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setMaxTileSize(viewer.activity.maxBitmapSize)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_FIXED)
            setDoubleTapZoomDuration(config.doubleTapAnimDuration)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumScaleType(config.imageScaleType)
            setMinimumDpi(90)
            setMinimumTileDpi(180)
            setRegionDecoderClass(config.regionDecoder)
            setBitmapDecoderClass(config.bitmapDecoder)
            setCropBorders(config.imageCropBorders)
            setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    when (config.imageZoomType) {
                        ZoomType.Left -> setScaleAndCenter(scale, PointF(0f, 0f))
                        ZoomType.Right -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0f))
                        ZoomType.Center -> setScaleAndCenter(scale, center.apply { y = 0f })
                    }
                    onImageDecoded()
                }

                override fun onImageLoadError(e: Exception) {
                    onImageDecodeError()
                }
            })
        }
        addView(subsamplingImageView)
        return subsamplingImageView!!
    }

    /**
     * Initializes an image view, used for GIFs.
     */
    private fun initImageView(): ImageView {
        if (imageView != null) return imageView!!

        imageView = PhotoView(context, null).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            adjustViewBounds = true
            setZoomTransitionDuration(viewer.config.doubleTapAnimDuration)
            setScaleLevels(1f, 2f, 3f)
            // Force 2 scale levels on double tap
            setOnDoubleTapListener(object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (scale > 1f) {
                        setScale(1f, e.x, e.y, true)
                    } else {
                        setScale(2f, e.x, e.y, true)
                    }
                    return true
                }
            })
        }
        addView(imageView)
        return imageView!!
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initRetryButton(): PagerButton {
        if (retryButton != null) return retryButton!!

        retryButton = PagerButton(context, viewer).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            setText(R.string.action_retry)
            setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }
        addView(retryButton)
        return retryButton!!
    }

    /**
     * Initializes a decode error layout.
     */
    private fun initDecodeErrorLayout(): ViewGroup {
        if (decodeErrorLayout != null) return decodeErrorLayout!!

        val margins = 8.dpToPx

        val decodeLayout = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
        }
        decodeErrorLayout = decodeLayout

        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                setMargins(margins, margins, margins, margins)
            }
            gravity = Gravity.CENTER
            setText(R.string.decode_image_error)

            decodeLayout.addView(this)
        }

        PagerButton(context, viewer).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                setMargins(margins, margins, margins, margins)
            }
            setText(R.string.action_retry)
            setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }

            decodeLayout.addView(this)
        }

        val imageUrl = page.imageUrl
        if (imageUrl.orEmpty().startsWith("http")) {
            PagerButton(context, viewer).apply {
                layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    setMargins(margins, margins, margins, margins)
                }
                setText(R.string.action_open_in_browser)
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl))
                    context.startActivity(intent)
                }

                decodeLayout.addView(this)
            }
        }

        addView(decodeLayout)
        return decodeLayout
    }

    private fun ImageView.setImage(stream: GlideInputStream) {
        GlideApp.with(this)
            .load(stream)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                ): Boolean {
                    onImageDecodeError()
                    return false
                }

                override fun onResourceReady(
                        resource: Drawable?,
                        model: Any?,
                        target: Target<Drawable>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                ): Boolean {
                    onImageDecoded()
                    return false
                }
            })
            .into(this)
    }

}