package com.example.splatter.model

/**
 * Represents a single 3D Gaussian Splat point with spatial, color, scale, and rotation attributes.
 */
data class SplatPoint(
    var x: Float,
    var y: Float,
    var z: Float,
    var r: Float,
    var g: Float,
    var b: Float,
    var alpha: Float = 0.85f,
    var scaleX: Float = 0.015f,
    var scaleY: Float = 0.015f,
    var scaleZ: Float = 0.015f,
    var rotW: Float = 1.0f,
    var rotX: Float = 0.0f,
    var rotY: Float = 0.0f,
    var rotZ: Float = 0.0f
)
