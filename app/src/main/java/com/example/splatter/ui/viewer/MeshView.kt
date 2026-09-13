package com.example.splatter.ui.viewer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.splatter.processor.mesh.TriangleMesh

/**
 * Orbit-controlled 3D mesh viewport (mirrors SplatView gestures).
 */
class MeshView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = GLMeshRenderer()

    private var previousX = 0f
    private var previousY = 0f

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

    fun setMesh(mesh: TriangleMesh) {
        queueEvent {
            renderer.setMesh(mesh)
            requestRender()
        }
    }

    fun setWireframe(enabled: Boolean) {
        queueEvent {
            renderer.wireframeMode = enabled
            requestRender()
        }
    }

    fun resetCamera() {
        queueEvent {
            renderer.pitchDegrees = 20f
            renderer.yawDegrees = 45f
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
