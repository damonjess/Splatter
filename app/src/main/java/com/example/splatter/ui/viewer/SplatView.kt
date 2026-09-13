package com.example.splatter.ui.viewer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.splatter.model.SplatPoint

class SplatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = GLSplatRenderer()

    private var previousX = 0f
    private var previousY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.cameraDistance = (renderer.cameraDistance / detector.scaleFactor).coerceIn(0.2f, 20.0f)
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
                // Two-finger pan target translation
                renderer.targetX += distanceX * 0.003f
                renderer.targetY -= distanceY * 0.003f
                requestRender()
                return true
            }
            return false
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

    fun resetCamera() {
        queueEvent {
            renderer.pitchDegrees = 20f
            renderer.yawDegrees = 45f
            renderer.cameraDistance = 2.5f
            requestRender()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.pointerCount == 1) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    previousX = event.x
                    previousY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
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
