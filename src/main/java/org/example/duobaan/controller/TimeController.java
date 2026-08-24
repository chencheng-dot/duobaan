package org.example.duobaan.controller;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Locale;

import org.example.duobaan.model.dto.TimeNow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时时间接口。
 */
@RestController
@RequestMapping("/api/time")
public class TimeController {

    private static final String[] WEEKDAYS_CN = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    @GetMapping("/now")
    public TimeNow now() {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek dow = now.getDayOfWeek();
        String weekday = WEEKDAYS_CN[dow.getValue() - 1];
        boolean workday = dow.getValue() <= 5;
        return new TimeNow(now, weekday, workday, TimeNow.periodOf(now));
    }
}
