package org.autojs.autodroid.ui

import android.widget.Switch
import androidx.core.content.ContextCompat
import org.autojs.autojs6.R

/** Switch 主色着色（选中=ad_primary，未选=浅灰），替代系统默认绿色。 */
object AdSwitchTint {

    fun apply(switch: Switch) {
        switch.thumbTintList = ContextCompat.getColorStateList(switch.context, R.color.ad_switch_thumb)
        switch.trackTintList = ContextCompat.getColorStateList(switch.context, R.color.ad_switch_track)
    }
}
