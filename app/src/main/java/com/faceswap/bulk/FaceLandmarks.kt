package com.faceswap.bulk

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.opencv.core.Point as CvPoint

/**
 * Fixed, deterministic order of face-contour groups. ML Kit returns the same
 * number of points per contour type on every face, so walking the groups in
 * this exact order gives two different faces landmark lists that line up
 * point-for-point — which is what the triangulation-based warp needs.
 */
private val CONTOUR_ORDER = listOf(
    FaceContour.FACE,
    FaceContour.LEFT_EYEBROW_TOP,
    FaceContour.LEFT_EYEBROW_BOTTOM,
    FaceContour.RIGHT_EYEBROW_TOP,
    FaceContour.RIGHT_EYEBROW_BOTTOM,
    FaceContour.LEFT_EYE,
    FaceContour.RIGHT_EYE,
    FaceContour.NOSE_BRIDGE,
    FaceContour.NOSE_BOTTOM,
    FaceContour.UPPER_LIP_TOP,
    FaceContour.UPPER_LIP_BOTTOM,
    FaceContour.LOWER_LIP_TOP,
    FaceContour.LOWER_LIP_BOTTOM,
)

class FaceLandmarkDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .build()
    )

    /**
     * Runs synchronously (via Tasks.await) — call this from a background
     * coroutine dispatcher, never from the main thread.
     * Returns null if zero or more than one face was found, since a swap
     * needs exactly one clear face per image.
     */
    fun detectSingleFace(bitmap: Bitmap): DetectedFace? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces: List<Face> = Tasks.await(detector.process(image))
        val face = faces.singleOrNull() ?: return null
        return toDetectedFace(face)
    }

    private fun toDetectedFace(face: Face): DetectedFace? {
        val points = ArrayList<CvPoint>()
        for (type in CONTOUR_ORDER) {
            val contour = face.getContour(type) ?: return null
            for (p in contour.points) {
                points.add(CvPoint(p.x.toDouble(), p.y.toDouble()))
            }
        }
        if (points.size < 30) return null
        return DetectedFace(points, face.boundingBox.width(), face.boundingBox.height())
    }

    fun close() = detector.close()
}

data class DetectedFace(
    val points: List<CvPoint>,
    val faceWidth: Int,
    val faceHeight: Int,
)
