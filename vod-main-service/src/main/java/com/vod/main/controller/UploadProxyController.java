package com.vod.main.controller;

import com.vod.common.api.ErrorCode;
import com.vod.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * 上传接口转发：前端统一访问主服务 8080，上传相关请求转发给 minio 微服务（8081）。
 */
@Slf4j
@RestController
@RequestMapping("/api/video/upload")
public class UploadProxyController {

    private final RestTemplate proxyRestTemplate;
    private final String baseUrl;

    public UploadProxyController(
            @Value("${vod.minio-service.base-url:http://127.0.0.1:8081}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        this.proxyRestTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request) {
        String query = request.getQueryString();
        String target = baseUrl + request.getRequestURI() + (query == null ? "" : "?" + query);
        HttpHeaders headers = new HttpHeaders();
        copyHeader(request, headers, HttpHeaders.AUTHORIZATION);
        copyHeader(request, headers, HttpHeaders.CONTENT_TYPE);
        copyHeader(request, headers, HttpHeaders.ACCEPT);

        byte[] body = readBody(request);
        try {
            ResponseEntity<byte[]> response = proxyRestTemplate.exchange(
                    URI.create(target),
                    HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body.length == 0 ? null : body, headers),
                    byte[].class);
            HttpHeaders responseHeaders = new HttpHeaders();
            if (response.getHeaders().getContentType() != null) {
                responseHeaders.setContentType(response.getHeaders().getContentType());
            }
            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
        } catch (HttpStatusCodeException ex) {
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            return new ResponseEntity<>(ex.getResponseBodyAsByteArray(), responseHeaders, ex.getStatusCode());
        } catch (Exception ex) {
            log.warn("转发上传请求失败 {} {}: {}", request.getMethod(), target, ex.getMessage());
            throw new BusinessException(ErrorCode.MINIO_UNAVAILABLE);
        }
    }

    private void copyHeader(HttpServletRequest request, HttpHeaders headers, String name) {
        List<String> values = java.util.Collections.list(request.getHeaders(name));
        if (!values.isEmpty()) {
            headers.put(name, values);
        }
    }

    private byte[] readBody(HttpServletRequest request) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (IOException ex) {
            log.warn("读取上传请求体失败: {}", ex.getMessage());
            return new byte[0];
        }
    }
}
