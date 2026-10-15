package com.doumiot.spring.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * 格式化日期时间工具类
 *
 * @author http://com.doumiot
 * @since 2022-08-04
 * @version 1.0
 */
public class FormatDateTimeUtil {
    private static final SimpleDateFormat formatDate = new SimpleDateFormat("yyyy年MM月dd日");
    private static final SimpleDateFormat formatDateTime = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
    private static final SimpleDateFormat formatTime = new SimpleDateFormat("HH:mm:ss");

    /**
     * 格式化日期
     *
     * @param time 时间戳
     * @return 格式化后的日期字符串
     */
    public static String getFormatDate(long time) {
        return formatDate.format(new Date(time));
    }

    /**
     * 格式化日期时间
     *
     * @param time 时间戳
     * @return 格式化后的日期时间字符串
     */
    public static String getFormatDateTime(long time) {
        return formatDateTime.format(new Date(time));
    }

    /**
     * 格式化时间
     *
     * @param time 时间戳
     * @return 格式化后的时间字符串
     */
    public static String getFormatTime(long time) {
        return formatTime.format(new Date(time));
    }

    private static final long _1h = 3600000;

    /**
     * 判断时间是否为今天
     *
     * @param time 时间戳
     * @return 是否为今天
     */
    public static boolean isToday(long time) {
        //Calendar使用单例，多次调用不重复创建对象
        Calendar calendar = Calendar.getInstance();
        //使用System获取当前时间
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long today = calendar.getTimeInMillis();
        return time - today < _1h * 24 && time - today > 0;
    }
}