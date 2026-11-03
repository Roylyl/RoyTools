package com.github.kr328.clash.log;

import java.util.Locale;
import java.util.Map;

/** Human-readable playback records, with the original payload retained for export. */
public final class MusicLogSummary {
    private static final Map<String, String> EVENTS = Map.ofEntries(
            Map.entry("play-request", "请求播放"), Map.entry("pause-request", "请求暂停"),
            Map.entry("source-load", "加载音源"), Map.entry("native-play-called", "已发起音频播放"),
            Map.entry("play-resolved", "播放请求已接受"), Map.entry("play-rejected", "播放请求失败"),
            Map.entry("clock-progress", "播放进度开始前进"), Map.entry("audio-playing", "音频开始播放"),
            Map.entry("audio-pause", "音频已暂停"), Map.entry("audio-waiting", "等待音频缓冲"),
            Map.entry("audio-ended", "音频播放结束"), Map.entry("track-finished", "当前歌曲播放完成"),
            Map.entry("audio-seeking", "正在跳转播放位置"), Map.entry("audio-seeked", "播放位置已跳转"),
            Map.entry("seek-request", "请求调整播放位置"), Map.entry("seek-completed", "播放位置调整完成"),
            Map.entry("loadedmetadata", "音频信息已加载"), Map.entry("loadstart", "开始加载音频"),
            Map.entry("stalled", "音频加载暂时停滞"), Map.entry("start-timeout", "播放启动超时"),
            Map.entry("start-recovery", "尝试恢复播放"), Map.entry("stale-pause-ignored", "已忽略过期暂停事件"),
            Map.entry("session-statechange", "系统音频会话状态变化"),
            Map.entry("system-play", "系统请求播放"), Map.entry("system-pause", "系统请求暂停"),
            Map.entry("system-next", "系统切换下一首"), Map.entry("system-previous", "系统切换上一首"),
            Map.entry("system-seek", "系统调整播放位置"), Map.entry("system-action-unavailable", "系统播放操作不可用"),
            Map.entry("pagehide", "音乐页面进入后台"), Map.entry("visibilitychange", "音乐页面可见状态变化")
    );

    private MusicLogSummary() { }

    public static String format(String event, Map<String, String> fields, String payload) {
        StringBuilder text = new StringBuilder(EVENTS.getOrDefault(event, event));
        String title = fields.getOrDefault("title", "");
        if (title.trim().isEmpty()) title = fields.getOrDefault("track", "");
        if (!title.trim().isEmpty() && !title.equals("null")) text.append("\n歌曲：").append(title);
        add(text, fields, "artist", "歌手");
        if (fields.containsKey("position")) {
            text.append("\n进度：").append(clock(fields.get("position")));
            if (fields.containsKey("duration") && !fields.get("duration").equals("null")) {
                text.append('/').append(clock(fields.get("duration")));
            }
        }
        if (fields.containsKey("readyState")) {
            String ready = fields.get("readyState");
            String label = switch (ready) {
                case "0" -> "未加载"; case "1" -> "已读取音频信息";
                case "2" -> "已有当前音频数据"; case "3" -> "可以播放";
                case "4" -> "音频已就绪"; default -> ready;
            };
            text.append("；就绪状态：").append(label).append('(').append(ready).append(')');
        }
        if (fields.containsKey("error")) {
            String error = fields.get("error");
            text.append("；错误：").append(error.equals("0") || error.equals("null") ? "无" : error);
        }
        if (fields.containsKey("hidden")) text.append("；后台：")
                .append(Boolean.parseBoolean(fields.get("hidden")) ? "是" : "否");
        if (fields.containsKey("paused")) text.append("；暂停：")
                .append(Boolean.parseBoolean(fields.get("paused")) ? "是" : "否");
        add(text, fields, "detail", "详情");
        return text.append("\n原始记录：").append(payload).toString();
    }

    private static void add(StringBuilder text, Map<String, String> fields, String key, String label) {
        String value = fields.getOrDefault(key, "");
        if (!value.trim().isEmpty() && !value.equals("null")) text.append('\n').append(label).append('：').append(value);
    }

    private static String clock(String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0) return "未知";
            long seconds = (long) parsed;
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        } catch (RuntimeException invalid) { return "未知"; }
    }
}
