package com.kooo.evcam.playback;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 视频分组模型
 * 将同一时间戳录制的多路视频组合在一起（前/后/左/右）
 * 文件命名格式：yyyyMMdd_HHmmss_{position}.mp4
 */
public class VideoGroup {
    
    /** 摄像头位置常量 */
    public static final String POSITION_FRONT = "front";
    public static final String POSITION_BACK = "back";
    public static final String POSITION_LEFT = "left";
    public static final String POSITION_RIGHT = "right";
    /** 四合一合成视频（单文件） */
    public static final String POSITION_QUAD = "quad";

    /** quad_yyyyMMdd_HHmmss 或 yyyyMMdd_HHmmss */
    private static final java.util.regex.Pattern TIMESTAMP_NAME =
            java.util.regex.Pattern.compile("^(?:quad_)?\\d{8}_\\d{6}$");
    /** 四合一分段延续：quad_yyyyMMdd_HHmmss_N（N 为分段序号，无后缀=第一段） */
    private static final java.util.regex.Pattern QUAD_SEGMENT_NAME =
            java.util.regex.Pattern.compile("^(?:quad_)?\\d{8}_\\d{6}(?:_(\\d+))?$");
    /** 新命名 quad_yyyy-MMdd-HHmm（分钟级，可选 -ss 秒后缀避让同名） */
    private static final java.util.regex.Pattern TIMESTAMP_NAME_MIN =
            java.util.regex.Pattern.compile("^(?:quad_)?\\d{4}-\\d{4}-\\d{4}(?:-\\d{2})?$");

    /** 判断是否为时间戳型文件名（quad_ 前缀可选） */
    private static boolean isTimestampName(String nameWithoutExt) {
        return TIMESTAMP_NAME.matcher(nameWithoutExt).matches()
                || TIMESTAMP_NAME_MIN.matcher(nameWithoutExt).matches();
    }

    /** 四合一分段序号：无后缀=0，_N 返回 N；非分段名返回 -1 */
    private static int quadSegmentIndex(String nameWithoutExt) {
        java.util.regex.Matcher m = QUAD_SEGMENT_NAME.matcher(nameWithoutExt);
        if (!m.matches()) return -1;
        String n = m.group(1);
        return n == null || n.isEmpty() ? 0 : Integer.parseInt(n);
    }

    /** 是否为四合一合成视频组 */
    public boolean isQuad() {
        return videoFiles.containsKey(POSITION_QUAD);
    }
    
    /** 时间戳前缀，如 "20260131_1254" */
    private final String timestampPrefix;
    
    /** 录制时间（解析自文件名） */
    private final Date recordTime;
    
    /** 各位置的视频文件 */
    private final Map<String, File> videoFiles;
    
    /** 四合一全部分段（含第一段），按序号排序后用于顺序回放 */
    private final List<File> quadSegments = new java.util.ArrayList<>();
    
    /** 总文件大小（所有位置之和） */
    private long totalSize;
    
    public VideoGroup(String timestampPrefix) {
        this.timestampPrefix = timestampPrefix;
        this.videoFiles = new HashMap<>();
        this.totalSize = 0;
        this.recordTime = parseTimestamp(timestampPrefix);
    }
    
    /**
     * 添加视频文件到分组
     * @param file 视频文件
     */
    public void addFile(File file) {
        String position = extractPosition(file.getName());
        if (position != null) {
            if (POSITION_QUAD.equals(position)) {
                // 四合一分段（含第一段）全部收进列表，回放按序号顺序播
                quadSegments.add(file);
                if (!videoFiles.containsKey(POSITION_QUAD)) {
                    videoFiles.put(POSITION_QUAD, file);
                }
            } else {
                videoFiles.put(position, file);
            }
            totalSize += file.length();
        }
    }

    /** 四合一全部分段（第一段在前），供顺序播放；列表项缩略图固定用第一段 */
    public List<File> getQuadSegments() {
        List<File> list = new java.util.ArrayList<>(quadSegments);
        Collections.sort(list, (a, b) -> Integer.compare(
                quadSegmentIndex(a.getName().replaceFirst("\\.[^.]+$", "")),
                quadSegmentIndex(b.getName().replaceFirst("\\.[^.]+$", ""))));
        if (!list.isEmpty()) {
            videoFiles.put(POSITION_QUAD, list.get(0));
        }
        return list;
    }
    
    /**
     * 从文件名提取时间戳前缀
     * @param fileName 文件名，如 "20260131_125430_front.mp4"
     * @return 时间戳前缀，如 "20260131_125430"
     */
    public static String extractTimestampPrefix(String fileName) {
        // 移除扩展名
        String nameWithoutExt = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            nameWithoutExt = fileName.substring(0, dotIndex);
        }

        // quad_ / 纯时间戳命名：整体就是时间戳前缀
        if (isTimestampName(nameWithoutExt)) {
            return nameWithoutExt;
        }

        // 找到最后一个下划线，它之前是时间戳
        int lastUnderscore = nameWithoutExt.lastIndexOf('_');
        if (lastUnderscore > 0) {
            return nameWithoutExt.substring(0, lastUnderscore);
        }
        return nameWithoutExt;
    }
    
    /**
     * 从文件名提取摄像头位置
     * @param fileName 文件名，如 "20260131_125430_front.mp4"
     * @return 位置，如 "front"
     */
    public static String extractPosition(String fileName) {
        // 移除扩展名
        String nameWithoutExt = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            nameWithoutExt = fileName.substring(0, dotIndex);
        }

        // quad_ / 纯时间戳命名 / 四合一分段延续（_N）：都算四合一单文件
        if (isTimestampName(nameWithoutExt) || quadSegmentIndex(nameWithoutExt) >= 0) {
            return POSITION_QUAD;
        }

        // 找到最后一个下划线，它之后是位置
        int lastUnderscore = nameWithoutExt.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < nameWithoutExt.length() - 1) {
            return nameWithoutExt.substring(lastUnderscore + 1).toLowerCase();
        }
        return null;
    }
    
    /**
     * 解析时间戳为日期
     */
    private Date parseTimestamp(String timestamp) {
        try {
            if (timestamp.startsWith("quad_")) {
                timestamp = timestamp.substring("quad_".length());
            }
            // 新命名 2026-1001-0805（可带 -ss 避让后缀）：先剥掉秒后缀
            if (timestamp.matches("\\d{4}-\\d{4}-\\d{4}(?:-\\d{2})?")) {
                timestamp = timestamp.replaceFirst("-\\d{2}$", "");
                SimpleDateFormat sdfMin = new SimpleDateFormat("yyyy-MMdd-HHmm", Locale.getDefault());
                return sdfMin.parse(timestamp);
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            return sdf.parse(timestamp);
        } catch (ParseException e) {
            return new Date(0);
        }
    }
    
    // Getters
    
    public String getTimestampPrefix() {
        return timestampPrefix;
    }
    
    public Date getRecordTime() {
        return recordTime;
    }
    
    /**
     * 获取格式化的日期时间字符串
     */
    public String getFormattedDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(recordTime);
    }
    
    /**
     * 获取格式化的日期字符串
     */
    public String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(recordTime);
    }
    
    /**
     * 获取格式化的时间字符串
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(recordTime);
    }
    
    /**
     * 获取指定位置的视频文件
     * @param position 位置（front/back/left/right）
     * @return 文件，可能为null
     */
    public File getVideoFile(String position) {
        return videoFiles.get(position);
    }
    
    /**
     * 获取前置摄像头视频
     */
    public File getFrontVideo() {
        return videoFiles.get(POSITION_FRONT);
    }
    
    /**
     * 获取后置摄像头视频
     */
    public File getBackVideo() {
        return videoFiles.get(POSITION_BACK);
    }
    
    /**
     * 获取左侧摄像头视频
     */
    public File getLeftVideo() {
        return videoFiles.get(POSITION_LEFT);
    }
    
    /**
     * 获取右侧摄像头视频
     */
    public File getRightVideo() {
        return videoFiles.get(POSITION_RIGHT);
    }
    
    /**
     * 获取所有视频文件
     */
    public Map<String, File> getAllVideoFiles() {
        return new HashMap<>(videoFiles);
    }
    
    /**
     * 获取第一个可用的缩略图文件（用于列表显示）
     * 优先级：front > back > left > right
     */
    public File getThumbnailFile() {
        if (videoFiles.containsKey(POSITION_FRONT)) {
            return videoFiles.get(POSITION_FRONT);
        } else if (videoFiles.containsKey(POSITION_BACK)) {
            return videoFiles.get(POSITION_BACK);
        } else if (videoFiles.containsKey(POSITION_LEFT)) {
            return videoFiles.get(POSITION_LEFT);
        } else if (videoFiles.containsKey(POSITION_RIGHT)) {
            return videoFiles.get(POSITION_RIGHT);
        } else if (videoFiles.containsKey(POSITION_QUAD)) {
            return videoFiles.get(POSITION_QUAD);
        }
        return null;
    }
    
    /**
     * 获取视频路数
     */
    public int getVideoCount() {
        return videoFiles.size();
    }
    
    /**
     * 获取总文件大小
     */
    public long getTotalSize() {
        return totalSize;
    }
    
    /**
     * 获取格式化的文件大小字符串
     */
    public String getFormattedSize() {
        if (totalSize < 1024) {
            return totalSize + " B";
        } else if (totalSize < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f KB", totalSize / 1024.0);
        } else if (totalSize < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f MB", totalSize / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", totalSize / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 检查是否有指定位置的视频
     */
    public boolean hasVideo(String position) {
        return videoFiles.containsKey(position);
    }
    
    /**
     * 删除所有视频文件
     * @return 成功删除的文件数
     */
    public int deleteAll() {
        int deleted = 0;
        java.util.LinkedHashSet<File> all = new java.util.LinkedHashSet<>(videoFiles.values());
        all.addAll(quadSegments);
        for (File file : all) {
            if (file.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            videoFiles.clear();
            quadSegments.clear();
            totalSize = 0;
        }
        return deleted;
    }
}
