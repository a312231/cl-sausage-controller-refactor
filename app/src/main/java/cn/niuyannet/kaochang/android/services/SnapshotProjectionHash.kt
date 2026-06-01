package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import java.security.MessageDigest

object SnapshotProjectionHash {

    fun hash(pans: List<KaoPan>, boxes: List<KaoPanBox>): String {
        val canonical = buildString {
            append("pans:")
            pans.sortedBy { it.positionSn }.forEach { pan ->
                append(pan.positionSn)
                append('|').append(pan.isHasSausage)
                append('|').append(pan.status)
                // holdingTime is the warm-state start timestamp; 0L means not warming.
                // Keep it as a boolean projection so wall-clock time does not churn the hash.
                append('|').append(pan.holdingTime > 0L)
                append('|').append(pan.taste?.tasteCode.orEmpty())
                append('|').append(pan.taste?.productId ?: 0)
                append(';')
            }
            append("boxes:")
            boxes.sortedBy { it.positionSn }.forEach { box ->
                append(box.positionSn)
                append('|').append(box.num)
                append('|').append(box.tasteCode.orEmpty())
                append('|').append(box.productId.orEmpty())
                append(';')
            }
        }
        return sha256(canonical)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
