package com.faceswap.bulk

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat6
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Subdiv2D
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * Classic landmark-based face swap:
 *  1. Convex hull of the target face's landmarks (the region being replaced).
 *  2. Delaunay-triangulate that hull.
 *  3. Warp each source triangle onto the matching target triangle
 *     (affine warp per triangle, not one global warp — this is what lets a
 *     flat 2D warp still look plausible on a turned/tilted face).
 *  4. Poisson-blend the warped face into the target with seamlessClone so
 *     skin tone/lighting matches the target photo instead of looking pasted on.
 *
 * Everything here is OpenCV running on-device; nothing leaves the phone.
 */
object FaceSwapEngine {

    /** @return the target bitmap with [source]'s face swapped in, or null if the swap failed. */
    fun swap(source: Bitmap, sourceFace: DetectedFace, target: Bitmap, targetFace: DetectedFace): Bitmap? {
        val img1 = Mat()
        val img2 = Mat()
        Utils.bitmapToMat(source, img1)
        Utils.bitmapToMat(target, img2)
        Imgproc.cvtColor(img1, img1, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(img2, img2, Imgproc.COLOR_RGBA2RGB)

        val points1 = sourceFace.points
        val points2 = targetFace.points
        if (points1.size != points2.size) return null

        // Convex hull indices computed on the TARGET points, then reused to
        // pull the matching points out of BOTH lists so hull1[i] and hull2[i]
        // are still the same anatomical landmark.
        val hullIndices = convexHullIndices(points2)
        if (hullIndices.size < 3) return null
        val hull1 = hullIndices.map { points1[it] }
        val hull2 = hullIndices.map { points2[it] }

        val triangleIndexSets = delaunayTriangleIndices(hull2, img2.cols(), img2.rows())
        if (triangleIndexSets.isEmpty()) return null

        val img1Warped = img2.clone()
        // start from the target photo so untouched pixels (hair, background,
        // ears) come through unchanged; only the hull region gets overwritten.
        img2.copyTo(img1Warped)

        for (tri in triangleIndexSets) {
            val t1 = arrayOf(hull1[tri[0]], hull1[tri[1]], hull1[tri[2]])
            val t2 = arrayOf(hull2[tri[0]], hull2[tri[1]], hull2[tri[2]])
            warpTriangle(img1, img1Warped, t1, t2)
        }

        // Mask = filled convex hull of the target face, used both to select
        // the blend region and to find the clone center.
        val hull2MatPoints = MatOfPoint(*hull2.toTypedArray())
        val mask = Mat.zeros(img2.size(), CvType.CV_8UC1)
        Imgproc.fillConvexPoly(mask, hull2MatPoints, Scalar(255.0))

        val r = Imgproc.boundingRect(hull2MatPoints)
        val center = Point(
            (r.x + r.width / 2).toDouble().coerceIn(1.0, (img2.cols() - 2).toDouble()),
            (r.y + r.height / 2).toDouble().coerceIn(1.0, (img2.rows() - 2).toDouble())
        )

        val output = Mat()
        Photo.seamlessClone(img1Warped, img2, mask, center, output, Photo.NORMAL_CLONE)

        val resultBitmap = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(output, output, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(output, resultBitmap)

        img1.release(); img2.release(); img1Warped.release()
        mask.release(); output.release()
        return resultBitmap
    }

    private fun convexHullIndices(points: List<Point>): List<Int> {
        val mat = MatOfPoint(*points.toTypedArray())
        val hullIdxMat = MatOfInt()
        Imgproc.convexHull(mat, hullIdxMat, false)
        return hullIdxMat.toArray().toList()
    }

    /** Delaunay-triangulates [hullPoints] and returns each triangle as 3 indices into that same list. */
    private fun delaunayTriangleIndices(hullPoints: List<Point>, width: Int, height: Int): List<IntArray> {
        val rect = Rect(0, 0, width, height)
        val subdiv = Subdiv2D(rect)
        for (p in hullPoints) {
            val clamped = Point(
                p.x.coerceIn(0.0, (width - 1).toDouble()),
                p.y.coerceIn(0.0, (height - 1).toDouble())
            )
            subdiv.insert(clamped)
        }

        val triangleList = MatOfFloat6()
        subdiv.getTriangleList(triangleList)

        // Each row of triangleList is a flat (x1,y1,x2,y2,x3,y3) triangle.
        // Map each vertex back to the closest hull point index.
        val result = ArrayList<IntArray>()
        val rows = triangleList.rows()
        val arr = FloatArray(6)
        for (i in 0 until rows) {
            triangleList.get(i, 0, arr)
            val v0 = Point(arr[0].toDouble(), arr[1].toDouble())
            val v1 = Point(arr[2].toDouble(), arr[3].toDouble())
            val v2 = Point(arr[4].toDouble(), arr[5].toDouble())
            if (!inRect(rect, v0) || !inRect(rect, v1) || !inRect(rect, v2)) continue
            val i0 = closestIndex(hullPoints, v0)
            val i1 = closestIndex(hullPoints, v1)
            val i2 = closestIndex(hullPoints, v2)
            if (i0 != i1 && i1 != i2 && i0 != i2) {
                result.add(intArrayOf(i0, i1, i2))
            }
        }
        return result
    }

    private fun inRect(r: Rect, p: Point) =
        p.x >= r.x && p.y >= r.y && p.x < r.x + r.width && p.y < r.y + r.height

    private fun closestIndex(points: List<Point>, target: Point): Int {
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for ((i, p) in points.withIndex()) {
            val dx = p.x - target.x
            val dy = p.y - target.y
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    /** Warps triangle [t1] from [src] into triangle [t2]'s location in [dst], in place, blended by a feathered mask. */
    private fun warpTriangle(src: Mat, dst: Mat, t1: Array<Point>, t2: Array<Point>) {
        val r1 = Imgproc.boundingRect(MatOfPoint(*t1))
        val r2 = Imgproc.boundingRect(MatOfPoint(*t2))
        if (r1.width <= 0 || r1.height <= 0 || r2.width <= 0 || r2.height <= 0) return

        val t1Rect = t1.map { Point(it.x - r1.x, it.y - r1.y) }
        val t2Rect = t2.map { Point(it.x - r2.x, it.y - r2.y) }

        val mask = Mat.zeros(r2.height, r2.width, CvType.CV_8UC1)
        Imgproc.fillConvexPoly(mask, MatOfPoint(*t2Rect.toTypedArray()), Scalar(255.0))

        val srcRectClamped = clampRect(r1, src.cols(), src.rows())
        val dstRectClamped = clampRect(r2, dst.cols(), dst.rows())
        if (srcRectClamped.width <= 0 || srcRectClamped.height <= 0) return
        if (dstRectClamped.width <= 0 || dstRectClamped.height <= 0) return

        val srcCrop = Mat(src, srcRectClamped)
        val warped = Mat.zeros(r2.height, r2.width, src.type())

        val srcTri = MatOfPoint2f(*t1Rect.toTypedArray())
        val dstTri = MatOfPoint2f(*t2Rect.toTypedArray())
        val warpMat = Imgproc.getAffineTransform(srcTri, dstTri)

        val srcForWarp = Mat.zeros(r1.height, r1.width, src.type())
        srcCrop.copyTo(
            srcForWarp.submat(
                Rect(0, 0, srcRectClamped.width, srcRectClamped.height)
            )
        )
        Imgproc.warpAffine(
            srcForWarp, warped, warpMat, warped.size(),
            Imgproc.INTER_LINEAR, Core.BORDER_REFLECT_101
        )

        val destRoi = dst.submat(dstRectClamped)
        val warpedCropped = Mat(warped, Rect(0, 0, dstRectClamped.width, dstRectClamped.height))
        val maskCropped = Mat(mask, Rect(0, 0, dstRectClamped.width, dstRectClamped.height))
        warpedCropped.copyTo(destRoi, maskCropped)

        mask.release(); srcCrop.release(); warped.release(); srcForWarp.release()
        warpedCropped.release(); maskCropped.release()
    }

    private fun clampRect(r: Rect, maxW: Int, maxH: Int): Rect {
        val x = max(0, r.x)
        val y = max(0, r.y)
        val w = min(r.width, maxW - x)
        val h = min(r.height, maxH - y)
        return Rect(x, y, max(0, w), max(0, h))
    }
}
