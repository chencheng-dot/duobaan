package org.example.duobaan.model.dto;

import java.time.LocalDateTime;

/**
 * 实时时间视图：含星期与工作日判断。
 */
public record TimeNow(
        LocalDateTime now,
        String weekday,
        boolean workday,
        String period) {

    /** 时段：早晨/上午/中午/下午/傍晚/晚上 */
    public static String periodOf(LocalDateTime now) {
        int h = now.getHour();
        if (h < 6) {
            return "凌晨";
        }
        if (h < 9) {
            return "早晨";
        }
        if (h < 12) {
            return "上午";
        }
        if (h < 13) {
            return "中午";
        }
        if (h < 17) {
            return "下午";
        }
        if (h < 19) {
            return "傍晚";
        }
        return "晚上";
    }
}
