package com.example.backend.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class FfmpegUtils {
    @Value("${tool.ffmpeg.dir:}")
    private String ffmpegDir;
    //返回有序的音频分段
    public List<Path> extractAudioSegments(String videoSource, Path workingDirectory){
        if(videoSource == null || videoSource.isBlank()){
            throw new IllegalArgumentException("音频文件不能为空");
        }
        if(workingDirectory == null){
            throw new IllegalArgumentException("工作地址不能为空");
        }
        Process process = null;
        try {
            Path directory = workingDirectory.toAbsolutePath();//获得绝对地址
            Files.createDirectories(directory);
            Path outputPattern = directory.resolve("audio_%06d.mp3");
            process = new ProcessBuilder(
                    resolveExecutable(),
                    "-nostdin",            // 禁用交互输入
                    "-y",                  // 覆盖已有输出文件
                    "-i", videoSource,
                    "-map", "0:a:0",       // 选择第一条音轨
                    "-vn",                 // 不输出视频
                    "-ac", "1",            // 单声道
                    "-ar", "16000",        // 16kHz采样率
                    "-c:a", "libmp3lame",   // MP3编码
                    "-b:a", "64k",          // 音频码率
                    "-f", "segment",
                    "-segment_time", "60", // 每段约60秒
                    "-reset_timestamps", "1",
                    outputPattern.toString()
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean finished = process.waitFor(15, TimeUnit.MINUTES);
            if (!finished) {
                throw new IllegalStateException("FFmpeg音频提取超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg音频提取失败，退出码：" + process.exitValue());
            }
            List<Path> segments;
            try(Stream<Path> files = Files.list(directory)){
                segments = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                .toString()
                                .matches("audio_\\d+\\.mp3"))
                        .sorted(Comparator.comparing(
                                path -> path.getFileName().toString()
                        ))
                        .toList();
            }
            if (segments.isEmpty()) {
                throw new IllegalStateException("没有生成音频分段");
            }
            for (Path segment : segments) {
                if (Files.size(segment) == 0) {
                    throw new IllegalStateException("音频分段为空：" + segment.getFileName());
                }
            }

            return segments;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("音频提取被中断", e);
        }catch(IOException e){
            throw new IllegalStateException("FFmpeg执行或文件处理失败", e);
        }finally {
            // 超时或中断时，终止仍在运行的进程
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
    /**
     * 根据配置目录和操作系统，确定FFmpeg可执行文件路径。
     */
    private String resolveExecutable() {
        if (ffmpegDir == null || ffmpegDir.isBlank()) {
            return "ffmpeg";
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(ffmpegDir.trim(), windows ? "ffmpeg.exe" : "ffmpeg").toString();
    }
}
