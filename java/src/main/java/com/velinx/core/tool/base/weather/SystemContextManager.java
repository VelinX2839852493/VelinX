package com.velinx.core.tool.base.weather;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 全局环境上下文管理器
 * 用于为 AI 准备 [时间 | 位置 | 天气] 信息
 */
public class SystemContextManager {

    // 获取完整的 AI 环境描述字符串
    public static String getFullContext() {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String location = getCityByIp();
        String weather = getWeatherByCity(location);

        return String.format("当前环境：[%s | %s | %s]\n", currentTime, location, weather);
    }

    /**
     * 获取位置信息 - 使用 ip-api (免Key, 准到城市)
     */
    private static String getCityByIp() {
        try {
            URL url = new URL("http://ip-api.com/json/?lang=zh-CN");
            String json = httpRequest(url);
            // 简单解析 JSON 字段 "city"
            if (json.contains("\"city\":\"")) {
                int start = json.indexOf("\"city\":\"") + 8;
                int end = json.indexOf("\"", start);
                return json.substring(start, end);
            }
        } catch (Exception e) {
            return "位置未知";
        }
        return "未知城市";
    }

    /**
     * 获取天气信息 - 使用 wttr.in (免Key)
     */
    private static String getWeatherByCity(String city) {
        try {
            // format=1 返回类似：☀️ +22°C
            URL url = new URL("https://wttr.in/" + city + "?format=1");
            String weather = httpRequest(url);
            return weather.trim();
        } catch (Exception e) {
            return "天气数据获取失败";
        }
    }

    // 通用的 HTTP GET 请求工具方法
    private static String httpRequest(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000); // 3秒超时，防止阻塞 AI 回复
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } finally {
            conn.disconnect();
        }
    }
}