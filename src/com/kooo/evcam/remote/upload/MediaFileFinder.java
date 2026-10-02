package com.kooo.evcam.remote.upload;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 媒体文件查找工具
 * 统一处理视频/照片文件的查找逻辑
 */
public class MediaFileFinder {
    private static final String TAG = "MediaFileFinder";

    /** 最新一个录像文件（按修改时间），没有则返回 null。远程「视频」指令用。 */
    public static File findLatestVideoFile(Context context) {
        try {
            File dir = StorageHelper.getVideoDir(context);
            File[] files = dir == null ? null : dir.listFiles();
            File latest = null;
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".mp4")
                            && (latest == null || f.lastModified() > latest.lastModified())) {
                        latest = f;
                    }
                }
            }
            return latest;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 最新 n 个录像文件（按修改时间倒序），远程「视频列表/视频N」指令用。 */
    public static List<File> listLatestVideoFiles(Context context, int count) {
        List<File> result = new ArrayList<>();
        try {
            File dir = StorageHelper.getVideoDir(context);
            File[] files = dir == null ? null : dir.listFiles();
            List<File> all = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".mp4")) {
                        all.add(f);
                    }
                }
            }
            all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < all.size() && i < count; i++) {
                result.add(all.get(i));
            }
        } catch (Throwable t) {
            // 忽略，返回已收集的部分
        }
        return result;
    }

    /** 最新一张照片（按修改时间），没有则返回 null。远程「照片」指令用。 */
    public static File findLatestPhotoFile(Context context) {
        List<File> list = listLatestPhotoFiles(context, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 最新 n 张照片（按修改时间倒序），远程「照片列表/照片N」指令用。 */
    public static List<File> listLatestPhotoFiles(Context context, int count) {
        List<File> result = new ArrayList<>();
        try {
            File dir = StorageHelper.getPhotoDir(context);
            File[] files = dir == null ? null : dir.listFiles();
            List<File> all = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.US);
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) {
                        all.add(f);
                    }
                }
            }
            all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < all.size() && i < count; i++) {
                result.add(all.get(i));
            }
        } catch (Throwable t) {
            // 忽略，返回已收集的部分
        }
        return result;
    }

    private final Context context;

    public MediaFileFinder(Context context) {
        this.context = context;
    }
    
    /**
     * 查找视频文件
     * 优先从临时目录查找，再查找最终目录
     * 
     * @param timestamp 录制时间戳
     * @return 视频文件列表，如果未找到返回空列表
     */
    public List<File> findVideoFiles(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            AppLog.e(TAG, "时间戳为空，无法查找视频文件");
            return new ArrayList<>();
        }
        
        // 1. 优先从临时目录查找
        File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        if (tempDir.exists() && tempDir.isDirectory()) {
            File[] tempFiles = tempDir.listFiles((dir, name) -> 
                name.endsWith(".mp4") && 
                name.startsWith(timestamp + "_") && 
                new File(dir, name).length() > 0
            );
            
            if (tempFiles != null && tempFiles.length > 0) {
                AppLog.d(TAG, "从临时目录找到 " + tempFiles.length + " 个视频文件");
                return new ArrayList<>(Arrays.asList(tempFiles));
            }
        }
        
        // 2. 从最终目录查找
        File videoDir = StorageHelper.getVideoDir(context);
        if (videoDir == null || !videoDir.exists()) {
            AppLog.e(TAG, "视频目录不存在");
            return new ArrayList<>();
        }
        
        File[] files = videoDir.listFiles((dir, name) -> 
            name.startsWith(timestamp) && name.endsWith(".mp4")
        );
        
        if (files == null || files.length == 0) {
            AppLog.e(TAG, "未找到录制的视频文件，时间戳: " + timestamp);
            return new ArrayList<>();
        }
        
        AppLog.d(TAG, "从最终目录找到 " + files.length + " 个视频文件");
        return new ArrayList<>(Arrays.asList(files));
    }
    
    /**
     * 查找视频文件（支持多个时间戳）
     * 用于 Watchdog 重建后查找所有录制的文件
     * 
     * @param timestamps 所有录制时间戳列表
     * @return 视频文件列表，如果未找到返回空列表
     */
    public List<File> findVideoFiles(List<String> timestamps) {
        if (timestamps == null || timestamps.isEmpty()) {
            AppLog.e(TAG, "时间戳列表为空，无法查找视频文件");
            return new ArrayList<>();
        }
        
        List<File> allFiles = new ArrayList<>();
        
        // 1. 从临时目录查找所有时间戳对应的文件
        File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        if (tempDir.exists() && tempDir.isDirectory()) {
            File[] tempFiles = tempDir.listFiles((dir, name) -> {
                if (!name.endsWith(".mp4") || new File(dir, name).length() == 0) {
                    return false;
                }
                for (String ts : timestamps) {
                    if (name.startsWith(ts + "_")) {
                        return true;
                    }
                }
                return false;
            });
            
            if (tempFiles != null && tempFiles.length > 0) {
                allFiles.addAll(Arrays.asList(tempFiles));
                AppLog.d(TAG, "从临时目录找到 " + tempFiles.length + " 个视频文件");
            }
        }
        
        // 2. 从最终目录查找所有时间戳对应的文件
        File videoDir = StorageHelper.getVideoDir(context);
        if (videoDir != null && videoDir.exists()) {
            File[] files = videoDir.listFiles((dir, name) -> {
                if (!name.endsWith(".mp4")) {
                    return false;
                }
                for (String ts : timestamps) {
                    if (name.startsWith(ts)) {
                        return true;
                    }
                }
                return false;
            });
            
            if (files != null && files.length > 0) {
                // 避免重复添加（临时目录和最终目录可能有同名文件）
                for (File f : files) {
                    boolean exists = false;
                    for (File existing : allFiles) {
                        if (existing.getName().equals(f.getName())) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        allFiles.add(f);
                    }
                }
                AppLog.d(TAG, "从最终目录额外找到视频文件");
            }
        }
        
        if (allFiles.isEmpty()) {
            AppLog.e(TAG, "未找到录制的视频文件，时间戳: " + timestamps);
        } else {
            AppLog.d(TAG, "总共找到 " + allFiles.size() + " 个视频文件（时间戳数: " + timestamps.size() + "）");
        }
        
        return allFiles;
    }
    
    /**
     * 查找照片文件
     * 
     * @param timestamp 拍照时间戳
     * @return 照片文件列表，如果未找到返回空列表
     */
    public List<File> findPhotoFiles(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            AppLog.e(TAG, "时间戳为空，无法查找照片文件");
            return new ArrayList<>();
        }
        
        File photoDir = StorageHelper.getPhotoDir(context);
        if (photoDir == null || !photoDir.exists()) {
            AppLog.e(TAG, "照片目录不存在");
            return new ArrayList<>();
        }
        
        File[] files = photoDir.listFiles((dir, name) -> 
            name.startsWith(timestamp) && 
            (name.endsWith(".jpg") || name.endsWith(".jpeg"))
        );
        
        if (files == null || files.length == 0) {
            AppLog.e(TAG, "未找到拍摄的照片，时间戳: " + timestamp);
            return new ArrayList<>();
        }
        
        AppLog.d(TAG, "找到 " + files.length + " 张照片");
        return new ArrayList<>(Arrays.asList(files));
    }
    
    /**
     * 将临时文件传输到最终目录
     * 
     * @param tempFiles 临时文件列表
     */
    public void transferToFinalDir(List<File> tempFiles) {
        if (tempFiles == null || tempFiles.isEmpty()) {
            return;
        }
        
        // 获取最终视频目录
        File videoDir = StorageHelper.getVideoDir(context);
        if (videoDir == null) {
            AppLog.e(TAG, "无法获取视频目录，跳过文件传输");
            return;
        }
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        for (File tempFile : tempFiles) {
            if (tempFile.exists()) {
                // 构造目标文件路径
                File targetFile = new File(videoDir, tempFile.getName());
                
                transferManager.addTransferTask(tempFile, targetFile, new FileTransferManager.TransferCallback() {
                    @Override
                    public void onTransferComplete(File sourceFile, File targetFile) {
                        AppLog.d(TAG, "文件传输完成: " + sourceFile.getName() + " -> " + targetFile.getAbsolutePath());
                    }
                    
                    @Override
                    public void onTransferFailed(File sourceFile, File targetFile, String error) {
                        AppLog.e(TAG, "文件传输失败: " + sourceFile.getName() + ", 错误: " + error);
                    }
                });
            }
        }
    }
}
