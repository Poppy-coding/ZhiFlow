package com.example.backend.utils;

import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.UUID;

@Component
public class MinioUtils {

    //注入minio客户端设置
    @Resource
    private MinioClient minioClient;

    //bucket桶名字
    private static final String BUCKET_NAME = "zhiflow";

    //节点网址
    @Value("${storage.minio.endpoint}")
    private String endpoint;

    /**
     *
     * 上传文件
     * @param file
     * @return
     */
    public String uploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        //创建名字 防止mimio中名字重复
        String objectName = UUID.randomUUID() + fileSuffix(file.getOriginalFilename());//uuid + 文件后缀
        uploadObject(objectName, file);
        return objectUrl(objectName);//返回object url
    }

    /**
     * 按指定的对象名称上传文件
     * @param objectName MinIO对象名称
     * @param file 上传文件
     */
    public void uploadObject(String objectName, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        validateObjectName(objectName);
        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType();//获取上传文件的格式
        try (InputStream inputStream = file.getInputStream()//输入流 从本地注入minio
        ) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET_NAME)
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 文件上传失败", e);
        }
    }

    /**
     *生成MinIO GET方式预签名URL，有效期1小时 就是返回一个可以播放的地址
     * @param source
     * @return
     */
    public String readableSource(String source) {
        if (source == null || !source.startsWith(normalizedEndpoint() + "/" + BUCKET_NAME + "/")) {
            return source;
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(BUCKET_NAME)
                    .object(objectName(source))
                    .expiry(60 * 60)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 预签名地址生成失败", e);
        }
    }

    /**
     * 移除文件
     * @param fileUrl
     */
    public void removeFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        if (!fileUrl.startsWith(normalizedEndpoint() + "/" + BUCKET_NAME + "/")) {
            return;
        }
        removeObject(objectName(fileUrl));
    }

    /**
     * 根据对象名称删除MinIO文件
     * @param objectName MinIO对象名称
     */
    public void removeObject(String objectName) {
        validateObjectName(objectName);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET_NAME)
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 文件删除失败", e);
        }
    }

    /**
     * 从minio的文件创建输入流 并注入方法参数的输出流
     * @param objectName
     * @param out
     */
    public void minioToOutput(String objectName, OutputStream out){
        validateObjectName(objectName);
        try(GetObjectResponse inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(BUCKET_NAME)
                .object(objectName)
                .build())){
            inputStream.transferTo(out);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO分片读取失败", e);
        }
    }
    public String uploadLocalFile(File file, String originalFileName){
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("本地文件不存在");
        }
        String objectName = UUID.randomUUID() + fileSuffix(originalFileName);//只要后缀
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET_NAME)
                    .object(objectName)
                    .stream(inputStream, file.length(), -1)
                    .contentType("application/octet-stream")
                    .build());
            return objectUrl(objectName);
        } catch (Exception e) {
            throw new IllegalStateException("完整文件上传MinIO失败", e);
        }
    }

    /**
     * 返回文件名字
     * @param objectName
     * @return
     */
    public String objectUrl(String objectName) {
        validateObjectName(objectName);
        return normalizedEndpoint() + "/" + BUCKET_NAME + "/" + objectName;
    }

    private String objectName(String fileUrl) {
        String path = URI.create(fileUrl).getPath();
        String bucketPrefix = "/" + BUCKET_NAME + "/";
        int start = path.indexOf(bucketPrefix);
        if (start < 0) {
            throw new IllegalArgumentException("无效的 MinIO 文件地址");
        }
        String objectName = path.substring(start + bucketPrefix.length());
        validateObjectName(objectName);
        return objectName;
    }

    private void validateObjectName(String objectName) {
        if (objectName == null || objectName.isBlank()
                || objectName.startsWith("/") || objectName.contains("..")) {
            throw new IllegalArgumentException("无效的 MinIO 文件名");
        }
    }

    /**
     * 把接口地址末尾所有的斜杠 `/` 全部去掉，返回处理后的字符串。
     * @return
     */
    private String normalizedEndpoint() {
        return endpoint.replaceAll("/+$", "");
    }

    /**
     * 获取文件后缀
     * @param filename
     * @return
     */
    private String fileSuffix(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');//获取最后一个“.”的下标
        if (dot < 0 || filename.length() - dot > 11) {
            return "";
        }
        String suffix = filename.substring(dot).toLowerCase();//获取文件后缀
        return suffix.matches("\\.[a-z0-9]+") ? suffix : "";
    }
}
