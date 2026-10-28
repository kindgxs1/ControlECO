package com.controleco.app.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.controleco.app.MainActivity
import com.controleco.app.R

/**
 * 快捷设置磁贴「记一笔」（手动入口，见
 * docs/自动记账捕获机制.md 第一节）：下拉快捷面板点一下就进快速记账页，
 * 无权限要求、无界面后台逻辑。
 */
class QuickAddTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "记一笔"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    /**
     * 低版本（< API 34）仍走旧的 Intent 入口：PendingIntent 版本在 API 34 才公开，
     * 旧版本上调用会 NoSuchMethodError。lint 的 StartActivityAndCollapseDeprecated 是对
     * "向 34+ 目标版本使用旧入口"的通用告警，这里已按设备版本分流，故显式抑制。
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CaptureCenter.EXTRA_ROUTE, ROUTE_QUICK_ADD)
        if (Build.VERSION.SDK_INT >= 34) {
            // API 34 起必须用 PendingIntent 版本（Intent 版本会抛异常）
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val ROUTE_QUICK_ADD = "quickAdd"
    }
}
