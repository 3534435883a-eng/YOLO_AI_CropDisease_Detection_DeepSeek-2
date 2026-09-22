package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 Flask 侧 /embed 获取向量。任何失败都转成 EmbeddingUnavailableException，
 * 由 KnowledgeRetriever 降级为 BM25-only，绝不影响主流程。
 */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final int timeoutMs;
    private final RestTemplate restTemplate;

    public HttpEmbeddingClient(@Value("${embedding.base-url:http://127.0.0.1:5000}") String baseUrl,
                               @Value("${embedding.timeout-ms:3000}") int timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.timeoutMs = timeoutMs;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    public double[] embed(String text) throws EmbeddingUnavailableException {
        List<String> texts = new ArrayList<String>();
        texts.add(text == null ? "" : text);
        return parseAll(request(texts)).get(0);
    }

    /**
     * 单次 HTTP 请求完成整批向量化。
     *
     * <p>返回条数必须与入参一致才接受：Flask 侧若截断或乱序，按错位使用会把 A 块的向量
     * 存到 B 块上，这类错误在检索阶段表现为"偶发答非所问"，极难排查，故宁可整批判失败。</p>
     */
    public List<double[]> embedBatch(List<String> texts) throws EmbeddingUnavailableException {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<double[]>();
        }
        List<double[]> vectors = parseAll(request(texts));
        if (vectors.size() != texts.size()) {
            throw new EmbeddingUnavailableException("embedding batch size mismatch: requested "
                    + texts.size() + " but got " + vectors.size());
        }
        return vectors;
    }

    private String request(List<String> texts) throws EmbeddingUnavailableException {
        String url = normalizedBaseUrl() + "/embed";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<String, Object>();
        List<String> payload = new ArrayList<String>();
        for (String text : texts) {
            payload.add(text == null ? "" : text);
        }
        body.put("texts", payload);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url,
                    new HttpEntity<Map<String, Object>>(body, headers), String.class);
            return response.getBody();
        } catch (Exception error) {
            throw new EmbeddingUnavailableException("embedding request failed: " + error.getClass().getSimpleName(), error);
        }
    }

    private List<double[]> parseAll(String payload) throws EmbeddingUnavailableException {
        if (payload == null || payload.trim().isEmpty()) {
            throw new EmbeddingUnavailableException("empty embedding response");
        }
        try {
            JSONObject root = JSON.parseObject(payload);
            JSONArray vectors = root.getJSONArray("vectors");
            if (vectors == null || vectors.isEmpty()) {
                throw new EmbeddingUnavailableException("embedding response has no vectors");
            }
            List<double[]> result = new ArrayList<double[]>();
            for (int row = 0; row < vectors.size(); row++) {
                JSONArray vector = vectors.getJSONArray(row);
                if (vector == null || vector.isEmpty()) {
                    throw new EmbeddingUnavailableException("embedding response row " + row + " is empty");
                }
                double[] values = new double[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = vector.getDoubleValue(i);
                }
                result.add(values);
            }
            return result;
        } catch (EmbeddingUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new EmbeddingUnavailableException("malformed embedding response", error);
        }
    }

    private String normalizedBaseUrl() throws EmbeddingUnavailableException {
        if (baseUrl.isEmpty()) {
            throw new EmbeddingUnavailableException("embedding base url not configured");
        }
        String normalized = baseUrl;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
