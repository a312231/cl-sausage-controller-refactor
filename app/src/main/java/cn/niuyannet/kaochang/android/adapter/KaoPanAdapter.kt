package cn.niuyannet.kaochang.android.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.niuyannet.kaochang.android.databinding.ItemKaopanBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.services.FaultHeatPauseManager
import androidx.core.graphics.toColorInt

class KaoPanAdapter(val callBack:((kaoPan: KaoPan)->Unit)) : ListAdapter<KaoPan, KaoPanAdapter.ViewHolder>(KaoPanDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKaopanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position),callBack)
    }

    class ViewHolder(private val binding: ItemKaopanBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("SetTextI18n", "DefaultLocale")
        fun bind(kaoPan: KaoPan, callBack:((kaoPan: KaoPan)->Unit)) {
            binding.apply {
                itemView.setOnClickListener {
                    callBack.invoke(kaoPan)
                }
                // 设置烤盘位置
                tvPosition.text = "工位: ${kaoPan.positionSn}"
                // 设置区域颜色
                when (kaoPan.positionRegion) {
                    1 -> {
                        // 一区字体淡黄色
                        tvPosition.setTextColor(Color.parseColor("#FFFFCC"))
                    }
                    2 -> {
                        // 二区为淡红色
                        tvPosition.setTextColor(Color.parseColor("#FF9999"))
                    }
                    3 -> {
                        // 三区为淡橙色
                        tvPosition.setTextColor(Color.parseColor("#FFD699"))
                    }
                    else -> {
                        // 过渡区为黑色
                        tvPosition.setTextColor("#000000".toColorInt())
                    }
                }
                // 设置烤盘状态
                when(kaoPan.status) {
                    KaoPan.STATUS_EMPTY -> {
                        tvStatus.text = formatKaoPanStatusText(kaoPan)
                        tvStatus.setTextColor("#000000".toColorInt())
                        tvStatus.setBackgroundColor("#CCCCCC".toColorInt()) // 灰色背景
                    }
                    KaoPan.STATUS_BAKING -> {
                        tvStatus.text = formatKaoPanStatusText(kaoPan)
                        tvStatus.setTextColor("#FFFFFF".toColorInt())
                        tvStatus.setBackgroundColor("#FF0000".toColorInt()) // 红色背景
                    }
                    KaoPan.STATUS_SELLABLE_KEEP_WARM -> {
                        tvStatus.text = formatKaoPanStatusText(kaoPan)
                        tvStatus.setTextColor("#FFFFFF".toColorInt())
                        tvStatus.setBackgroundColor("#00FF00".toColorInt()) // 绿色背景
                    }
                    KaoPan.STATUS_WAITING_RETURN_KEEP_WARM -> {
                        tvStatus.text = formatKaoPanStatusText(kaoPan)
                        tvStatus.setTextColor("#000000".toColorInt())
                        tvStatus.setBackgroundColor("#FFD699".toColorInt()) // 橙色背景
                    }
                    else -> {
                        tvStatus.text = "未知"
                        tvStatus.setTextColor("#000000".toColorInt())
                        tvStatus.setBackgroundColor("#CCCCCC".toColorInt()) // 灰色背景
                    }
                }
                //是否有烤肠
                if (kaoPan.isHasSausage){
                    tvHasSausage.text ="有烤肠"
                }else{
                    tvHasSausage.text = "无烤肠"
                }
                // 设置口味
                tvTaste.text = formatKaoPanTasteText(kaoPan)

            }
        }
    }
}

internal fun formatKaoPanStatusText(
    kaoPan: KaoPan,
    nowMs: Long = System.currentTimeMillis(),
    config: AppConfigBean = AppConfig.getAppConfig()
): String {
    return when (kaoPan.status) {
        KaoPan.STATUS_EMPTY -> when (kaoPan.uiReservationState) {
            KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT -> "待补肠"
            KaoPan.UI_RESERVATION_WAIT_RETURN -> "待回补"
            else -> "空缺"
        }
        KaoPan.STATUS_BAKING -> {
            val seconds = FaultHeatPauseManager.effectiveBakingElapsedMs(kaoPan, config, nowMs) / 1000L
            "烤制中:${String.format("%02d:%02d", seconds / 60, seconds % 60)}"
        }
        KaoPan.STATUS_SELLABLE_KEEP_WARM -> {
            val seconds = (nowMs - kaoPan.holdingTime).coerceAtLeast(0L) / 1000L
            "保温:${String.format("%02d:%02d", seconds / 60, seconds % 60)}"
        }
        KaoPan.STATUS_WAITING_RETURN_KEEP_WARM -> {
            val seconds = (nowMs - kaoPan.holdingTime).coerceAtLeast(0L) / 1000L
            "待回搬保温:${String.format("%02d:%02d", seconds / 60, seconds % 60)}"
        }
        else -> "未知"
    }
}

internal fun formatKaoPanTasteText(kaoPan: KaoPan): String {
    if (kaoPan.status == KaoPan.STATUS_EMPTY) {
        return "口味: ${formatTasteName(kaoPan.uiReservationTaste) ?: "无"}"
    }

    val text = formatTasteName(kaoPan.taste) ?: "无"
    return "口味: $text"
}

private fun formatTasteName(taste: cn.niuyannet.kaochang.android.model.bean.Taste?): String? {
    return when {
        !taste?.tasteName.isNullOrEmpty() -> taste?.tasteName
        !taste?.tasteCode.isNullOrEmpty() -> taste?.tasteCode
        else -> null
    }
}

private class KaoPanDiffCallback : DiffUtil.ItemCallback<KaoPan>() {
    override fun areItemsTheSame(oldItem: KaoPan, newItem: KaoPan): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: KaoPan, newItem: KaoPan): Boolean {
        return oldItem.positionSn == newItem.positionSn &&
               oldItem.status == newItem.status &&
               oldItem.temperature == newItem.temperature &&
               oldItem.taste?.tasteCode == newItem.taste?.tasteCode &&
               oldItem.taste?.tasteName == newItem.taste?.tasteName &&
               oldItem.uiReservationState == newItem.uiReservationState &&
               oldItem.uiReservationTaste?.tasteCode == newItem.uiReservationTaste?.tasteCode &&
               oldItem.uiReservationTaste?.tasteName == newItem.uiReservationTaste?.tasteName &&
               oldItem.isHasSausage == newItem.isHasSausage
    }
}
