package com.example.splatter.ui.viewer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.splatter.model.SplatPoint
import kotlin.math.abs

/**
 * Orbit-controlled 3D splat/mesh viewport with the same Polycam-style
 * interactions as [MeshView]:
 *  - one-finger orbit, two-finger pan, pinch zoom
 *  - turntable auto-rotate that pauses on touch
 *  - flick inertia after a fast orbit
 *  - double-tap to reset the view
 */
class SplatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = GLSplatRenderer()

    private var previousX = 0f
    private var previousY = 0f

    // ---- turntable ----
    private var autoRotate = false
    private val turntableTicker = object : Runnable {
        override fun run() {
            if (!autoRotate) return
            renderer.yawDegrees = (renderer.yawDegrees + 0.35f) % 360f
            requestRender()
            postDelayed(this, 16)
        }
    }

    // ---- flick inertia ----
    private var inertia = false
    private var inertiaVelocity = 0f
    private val inertiaTicker = object : Runnable {
        override fun run() {
            if (!inertia) return
            renderer.yawDegrees = (renderer.yawDegrees + inertiaVelocity * 0.02f) % 360f
            inertiaVelocity *= 0.93f
            requestRender()
            if (abs(inertiaVelocity) > 8f) {
                postDelayed(this, 16)
            } else {
                inertia = false
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.cameraDistance = (renderer.cameraDistance / detector.scaleFactor).coerceIn(0.1f, 60.0f)
            requestRender()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e2.pointerCount == 2) {
                // Two-finger pan the orbit target
                renderer.targetX += distanceX * 0.003f
                renderer.targetY -= distanceY * 0.003f
                requestRender()
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            stopMotion()
            resetCamera()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e2.pointerCount > 1) return false
            if (abs(velocityX) < abs(velocityY)) return false
            inertia = true
            inertiaVelocity = -velocityX * 0.02f
            postDelayed(inertiaTicker, 16)
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPoints(points: List<SplatPoint>) {
        queueEvent {
            renderer.setSplatPoints(points)
            requestRender()
        }
    }

    fun setSplatSizeMultiplier(multiplier: Float) {
        queueEvent {
            renderer.pointSizeMultiplier = multiplier
            requestRender()
        }
    }

    /** Turntable spin. Any touch pauses it; toggle again to resume. */
    fun setAutoRotate(enabled: Boolean) {
        autoRotate = enabled
        if (enabled) {
            inertia = false
            removeCallbacks(inertiaTicker)
            removeCallbacks(turntableTicker)
            postDelayed(turntableTicker, 16)
        } else {
            removeCallbacks(turntableTicker)
        }
    }

    fun resetCamera() {
        queueEvent {
            renderer.resetView()
            requestRender()
        }
    }

    /** Set the viewport clear colour. `variant`: 0 = studio dark, 1 = neutral grey, 2 = white. */
    fun setBackground(variant: Int) {
        queueEvent {
            when (variant) {
                1 -> renderer.setBackgroundColor(0.20f, 0.21f, 0.24f)
                2 -> renderer.setBackgroundColor(0.93f, 0.94f, 0.96f)
                else -> renderer.setBackgroundColor(0.04f, 0.043f, 0.058f)
            }
            requestRender()
        }
    }

    private fun stopMotion() {
        autoRotate = false
        inertia = false
        removeCallbacks(turntableTicker)
        removeCallbacks(inertiaTicker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMotion()
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY

                    renderer.yawDegrees = (renderer.yawDegrees - dx * 0.3f) % 360f
                    renderer.pitchDegrees = (renderer.pitchDegrees + dy * 0.3f).coerceIn(-85f, 85f)

                    previousX = event.x
                    previousY = event.y

                    requestRender()
                }
            }
        }
        return true
    }
}
