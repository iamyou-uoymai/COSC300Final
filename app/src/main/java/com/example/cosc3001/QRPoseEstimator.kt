package com.example.cosc3001

import android.util.Log
import com.google.ar.core.Pose
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import kotlin.math.sqrt

/**
 * QR pose estimation helper. Encapsulates OpenCV solvePnP and ARCore coordinate conversion.
 * Provides debug logging and returns both the raw marker pose and a corrected upright pose for models.
 */
class QRPoseEstimator {

    data class CameraIntrinsics(
        val focalLength: FloatArray,     // [fx, fy]
        val principalPoint: FloatArray   // [cx, cy]
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CameraIntrinsics

            if (!focalLength.contentEquals(other.focalLength)) return false
            if (!principalPoint.contentEquals(other.principalPoint)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = focalLength.contentHashCode()
            result = 31 * result + principalPoint.contentHashCode()
            return result
        }
    }

    data class Result(
        val success: Boolean,
        val markerCenterPose: Pose?,    // Pose at the center of the QR marker with its original plane orientation
        val uprightPose: Pose?,         // Pose corrected so that the QR forward aligns to +Y (for upright models)
        val reason: String? = null
    )

    // Order 4 corners as: Top-Left, Top-Right, Bottom-Right, Bottom-Left
    fun orderQuadTLTRBRBL(points: List<Point>): List<Point> {
        require(points.size == 4) { "orderQuad requires 4 points" }
        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.x - it.y }
        val tlIndex = sums.withIndex().minByOrNull { it.value }!!.index
        val brIndex = sums.withIndex().maxByOrNull { it.value }!!.index
        val remaining = (0..3).filter { it != tlIndex && it != brIndex }
        val trIndex = remaining.maxByOrNull { diffs[it] }!!
        val blIndex = remaining.minByOrNull { diffs[it] }!!
        return listOf(points[tlIndex], points[trIndex], points[brIndex], points[blIndex])
    }

    fun estimatePose(
        imagePoints: List<Point>,   // 3 or 4 points (ZXing may return 3)
        qrSizeMeters: Double,
        intrinsics: CameraIntrinsics,
        cameraPose: Pose
    ): Result {
        if (imagePoints.size < 3) return Result(false, null, null, "Not enough image points: ${imagePoints.size}")
        val orderedImagePoints: List<Point> = try {
            val pts = if (imagePoints.size == 3) {
                val a = imagePoints[0]
                val b = imagePoints[1]
                val c = imagePoints[2]
                var dX = b.x + (c.x - a.x)
                var dY = b.y + (c.y - a.y)
                dX = dX.coerceIn(0.0, 1e9) // we'll trust downstream clamping
                dY = dY.coerceIn(0.0, 1e9)
                listOf(a, b, c, Point(dX, dY))
            } else imagePoints.take(4)
            orderQuadTLTRBRBL(pts)
        } catch (t: Throwable) {
            return Result(false, null, null, "orderQuad failed: ${t.message}")
        }

        try {
            val objectPoints = listOf(
                Point3(0.0, 0.0, 0.0),
                Point3(qrSizeMeters, 0.0, 0.0),
                Point3(qrSizeMeters, qrSizeMeters, 0.0),
                Point3(0.0, qrSizeMeters, 0.0)
            )
            val cameraMatrix = Mat.zeros(3, 3, CvType.CV_64F)
            cameraMatrix.put(0, 0, intrinsics.focalLength[0].toDouble())
            cameraMatrix.put(1, 1, intrinsics.focalLength[1].toDouble())
            cameraMatrix.put(0, 2, intrinsics.principalPoint[0].toDouble())
            cameraMatrix.put(1, 2, intrinsics.principalPoint[1].toDouble())
            cameraMatrix.put(2, 2, 1.0)
            val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0)

            val rvec = Mat()
            val tvec = Mat()
            val objMat = MatOfPoint3f(*objectPoints.toTypedArray())
            val imgMat = MatOfPoint2f(*orderedImagePoints.toTypedArray())

            val startNs = System.nanoTime()
            var success = Calib3d.solvePnP(
                objMat, imgMat, cameraMatrix, distCoeffs,
                rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE
            )
            var method = "IPPE_SQUARE"
            if (!success) {
                val inliers = Mat()
                success = Calib3d.solvePnPRansac(
                    objMat, imgMat, cameraMatrix, distCoeffs,
                    rvec, tvec, false,
                    1000, 16.0F, 0.995, inliers, Calib3d.SOLVEPNP_ITERATIVE
                )
                method = "RANSAC(${inliers.rows()} inliers)"
            }
            val durMs = (System.nanoTime() - startNs) / 1e6
            Log.d("QRPose", "solvePnP success=$success via $method in ${"%.2f".format(durMs)}ms")
            if (!success) return Result(false, null, null, "solvePnP failed")

            // Rodrigues to rotation matrix
            val rotMat = Mat()
            Calib3d.Rodrigues(rvec, rotMat) // R_cv (object->camera)
            val RcvT = rotMat.t()           // R_cam->obj = R^T
            val RcvTArr = FloatArray(9)
            for (i in 0..2) for (j in 0..2) RcvTArr[i * 3 + j] = RcvT.get(i, j)[0].toFloat()

            // t_cam_to_obj_cv = - R^T * t
            val tcv = floatArrayOf(
                tvec.get(0, 0)[0].toFloat(),
                tvec.get(1, 0)[0].toFloat(),
                tvec.get(2, 0)[0].toFloat()
            )
            val tCamToObjCv = FloatArray(3)
            tCamToObjCv[0] = -(RcvTArr[0] * tcv[0] + RcvTArr[1] * tcv[1] + RcvTArr[2] * tcv[2])
            tCamToObjCv[1] = -(RcvTArr[3] * tcv[0] + RcvTArr[4] * tcv[1] + RcvTArr[5] * tcv[2])
            tCamToObjCv[2] = -(RcvTArr[6] * tcv[0] + RcvTArr[7] * tcv[1] + RcvTArr[8] * tcv[2])

            // Convert to AR camera coordinates using S = diag(1, -1, -1)
            val s = floatArrayOf(1f, -1f, -1f)
            val RarArr = FloatArray(9)
            for (i in 0..2) for (j in 0..2) RarArr[i * 3 + j] = s[i] * RcvTArr[i * 3 + j] * s[j]
            val tCamToObjAr = floatArrayOf(
                s[0] * tCamToObjCv[0],
                s[1] * tCamToObjCv[1],
                s[2] * tCamToObjCv[2]
            )

            val quat = rotationMatrixToQuaternion(RarArr)
            val qrPoseInCameraSpace = Pose(tCamToObjAr, quat)
            val worldPose = cameraPose.compose(qrPoseInCameraSpace)

            // Compute center of marker in world space
            val qrSizeF = qrSizeMeters.toFloat()
            val centerWorld = worldPose.transformPoint(floatArrayOf(qrSizeF / 2f, qrSizeF / 2f, 0f))
            val markerCenterPose = Pose(centerWorld, worldPose.rotationQuaternion)

            // Align QR forward (+Z) to world +Y so the model stands upright
            val worldRot = worldPose.rotationQuaternion
            val forward = rotateVecByQuat(worldRot, floatArrayOf(0f, 0f, 1f))
            val fNorm = normalizeVec(forward)
            val qAlignWorld = quatFromVectorsSafe(fNorm, floatArrayOf(0f, 1f, 0f))
            val qCorrected = quatMultiply(qAlignWorld, worldRot)
            val uprightPose = Pose(centerWorld, qCorrected)

            return Result(true, markerCenterPose, uprightPose)
        } catch (t: Throwable) {
            Log.e("QRPose", "Exception during pose estimation: ${t.message}", t)
            return Result(false, null, null, t.message)
        }
    }

    // Math helpers (x,y,z,w)
    private fun rotationMatrixToQuaternion(m: FloatArray): FloatArray {
        val r00 = m[0]; val r01 = m[1]; val r02 = m[2]
        val r10 = m[3]; val r11 = m[4]; val r12 = m[5]
        val r20 = m[6]; val r21 = m[7]; val r22 = m[8]
        val trace = r00 + r11 + r22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt((trace + 1.0f)) * 2f
            q[3] = 0.25f * s
            q[0] = (r21 - r12) / s
            q[1] = (r02 - r20) / s
            q[2] = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1.0f + r00 - r11 - r22) * 2f
            q[3] = (r21 - r12) / s
            q[0] = 0.25f * s
            q[1] = (r01 + r10) / s
            q[2] = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1.0f + r11 - r00 - r22) * 2f
            q[3] = (r02 - r20) / s
            q[0] = (r01 + r10) / s
            q[1] = 0.25f * s
            q[2] = (r12 + r21) / s
        } else {
            val s = sqrt(1.0f + r22 - r00 - r11) * 2f
            q[3] = (r10 - r01) / s
            q[0] = (r02 + r20) / s
            q[1] = (r12 + r21) / s
            q[2] = 0.25f * s
        }
        return q
    }

    private fun quatMultiply(q1: FloatArray, q2: FloatArray): FloatArray {
        val x1 = q1[0]; val y1 = q1[1]; val z1 = q1[2]; val w1 = q1[3]
        val x2 = q2[0]; val y2 = q2[1]; val z2 = q2[2]; val w2 = q2[3]
        return floatArrayOf(
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
        )
    }

    private fun rotateVecByQuat(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val vx = v[0]; val vy = v[1]; val vz = v[2]
        val res = FloatArray(3)
        res[0] = vx * (1 - 2 * (qy * qy + qz * qz)) + vy * 2 * (qx * qy - qz * qw) + vz * 2 * (qx * qz + qy * qw)
        res[1] = vx * 2 * (qx * qy + qz * qw) + vy * (1 - 2 * (qx * qx + qz * qz)) + vz * 2 * (qy * qz - qx * qw)
        res[2] = vx * 2 * (qx * qz - qy * qw) + vy * 2 * (qy * qz + qx * qw) + vz * (1 - 2 * (qx * qx + qy * qy))
        return res
    }

    private fun normalizeVec(v: FloatArray): FloatArray {
        val norm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (norm < 1e-6f) return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf(v[0] / norm, v[1] / norm, v[2] / norm)
    }

    private fun normalizeQuat(q: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        return floatArrayOf(x / n, y / n, z / n, w / n)
    }

    // New: Safe quaternion from vectors, handles parallel/opposite cases and returns normalized quaternion
    private fun quatFromVectorsSafe(v1In: FloatArray, v2In: FloatArray): FloatArray {
        val v1 = normalizeVec(v1In)
        val v2 = normalizeVec(v2In)
        val dot = v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2]
        if (dot > 1.0f - 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        if (dot < -1.0f + 1e-6f) {
            val arbitrary = if (kotlin.math.abs(v1[0]) < 0.1f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val axis = normalizeVec(
                floatArrayOf(
                    v1[1] * arbitrary[2] - v1[2] * arbitrary[1],
                    v1[2] * arbitrary[0] - v1[0] * arbitrary[2],
                    v1[0] * arbitrary[1] - v1[1] * arbitrary[0]
                )
            )
            return floatArrayOf(axis[0], axis[1], axis[2], 0f)
        }
        val cross = floatArrayOf(
            v1[1] * v2[2] - v1[2] * v2[1],
            v1[2] * v2[0] - v1[0] * v2[2],
            v1[0] * v2[1] - v1[1] * v2[0]
        )
        val q = floatArrayOf(cross[0], cross[1], cross[2], 1f + dot)
        return normalizeQuat(q)
    }
}
