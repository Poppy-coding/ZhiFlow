package com.example.backend.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class SiliconFlowAsrUtils {

    //最多尝试3次
    private static final int MAX_ATTEMPTS = 3;

    //硅基流动API Key
    @Value("${ai.asr.api-key:}")
    private String apiKey;

    //语音识别接口地址
    @Value("${ai.asr.url}")
    private String url;

    //语音识别模型
    @Value("${ai.asr.model}")
    private String model;

    //复用HTTP客户端，重试由audioToText方法统一处理
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            .callTimeout(4, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .build();

    /**
     * 将一个MP3音频分段识别为文字。
     * @param audioPath 音频文件路径
     * @return 识别出的文字
     */
    public String audioToText(Path audioPath){
        if(audioPath == null || !Files.isRegularFile(audioPath)
                || !Files.isReadable(audioPath)){
            throw new IllegalArgumentException("音频文件不存在或无法读取");
        }
        if(apiKey == null || apiKey.isBlank()){
            throw new IllegalStateException("未配置ASR API Key");
        }

        IOException lastError = null;
        for(int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++){
            if(Thread.currentThread().isInterrupted()){
                throw new IllegalStateException("ASR调用已被中断");
            }
            try {
                return execute(audioPath);
            }catch(IOException e){
                lastError = e;
                if(Thread.currentThread().isInterrupted()){
                    throw new IllegalStateException("ASR调用已被中断", e);
                }
                if(attempt < MAX_ATTEMPTS){
                    waitBeforeRetry(attempt);
                }
            }
        }
        throw new IllegalStateException("ASR调用失败，已达到最大尝试次数", lastError);
    }

    /**
     * 上传音频并执行一次识别请求。
     */
    private String execute(Path audioPath) throws IOException {
        RequestBody audioBody = RequestBody.create(
                audioPath.toFile(), MediaType.parse("audio/mpeg"));

        //提交音频文件和模型名称
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioPath.getFileName().toString(), audioBody)
                .addFormDataPart("model", model)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey.trim())
                .post(requestBody)
                .build();

        //自动关闭响应，释放连接资源
        try(Response response = client.newCall(request).execute()){
            int code = response.code();
            if(!response.isSuccessful()){
                //限流和服务端故障，可以重试
                if(code == 429 || (code >= 500 && code < 600)){
                    throw new IOException("ASR服务暂时不可用，HTTP " + code);
                }
                throw new IllegalArgumentException("ASR请求被拒绝，HTTP " + code);
            }

            ResponseBody responseBody = response.body();
            if(responseBody == null){
                throw new IllegalStateException("ASR响应体为空");
            }

            JSONObject json;
            try {
                json = JSON.parseObject(responseBody.string());
            }catch(JSONException e){
                throw new IllegalStateException("ASR响应不是有效的JSON", e);
            }
            String text = json == null ? null : json.getString("text");
            if(text == null || text.isBlank()){
                throw new IllegalStateException("ASR没有返回有效文字");
            }
            return text.trim();
        }
    }

    /**
     * 第一次失败等待1秒，第二次失败等待2秒。
     */
    private void waitBeforeRetry(int attempt){
        try {
            TimeUnit.SECONDS.sleep(attempt);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR重试等待被中断", e);
        }
    }
}
