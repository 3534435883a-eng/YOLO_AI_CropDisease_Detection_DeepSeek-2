package com.example.Ece.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DeepSeekHttpConfig {

    @Bean(name = "deepSeekRestTemplate")
    public RestTemplate deepSeekRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    /**
     * 流式（{@code stream=true}）专用，**不要与 {@link #deepSeekRestTemplate()} 合并**。
     *
     * <p>两者的读超时语义不同：{@code HttpURLConnection.setReadTimeout} 限制的是**每次
     * {@code read()} 的阻塞时长**，不是整段响应的总时长。非流式请求要在 60s 内拿到完整响应，
     * 所以那里的 60s 是合理的；但流式请求会经历<b>首 token 之前的静默期</b>——放开思考 + 长推演时，
     * 上游排队与长推理起步很容易超过 60s，届时会抛 {@code SocketTimeoutException}
     * 并把整条流掐断。这正是长推演最容易踩的坑。</p>
     *
     * <p>因此这里把读超时设为 <b>0（无限）</b>，由上层（SSE emitter 的超时预算）兜底。
     * 不设 0 而设一个大值（如 300s）也可以，但那样静默期的上限就变成一个需要单独论证的数字，
     * 而真正该限制的是整段推演的时长，那件事已经由 emitter 管了，这里再加一层只会让两处超时打架。</p>
     *
     * <p><b>为什么用 {@code SimpleClientHttpRequestFactory} 而不是 Apache HttpClient</b>：
     * 本项目 pom 里的 {@code httpclient} 是 4.2.1（2012 年），而 Spring 5.x 的
     * {@code HttpComponentsClientHttpRequestFactory} 要求 4.3+，直接上会 {@code NoSuchMethodError}。
     * 为一个流式请求去升这个全局依赖，收益不抵风险。</p>
     */
    @Bean(name = "deepSeekStreamingRestTemplate")
    public RestTemplate deepSeekStreamingRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(0);
        return new RestTemplate(factory);
    }
}
