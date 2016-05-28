/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.resource.transport.httpProxy;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.apache.ApacheHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.protocol.BasicHttpContext;


import org.gradle.api.UncheckedIOException;
import org.gradle.internal.resource.transport.http.HttpRequestException;
import org.gradle.internal.resource.transport.http.HttpSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

/**
 * Provides some convenience and unified logging.
 */
public class ProxyClientHelper implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyClientHelper.class);
    private HttpRequestFactory client;
    private final BasicHttpContext httpContext = new BasicHttpContext();
    private final HttpSettings settings;
    //private static final HttpTransport TRANSPORT = new ApacheHttpTransport();
    private static final HttpTransport TRANSPORT = new NetHttpTransport.Builder().build();

    public ProxyClientHelper(HttpSettings settings) {
        this.settings = settings;
    }

    public HttpResponse performRawHead(String source) {
        return performRequest(new HttpHead(source));
    }

    public HttpResponse performHead(String source) {
        return processResponse(source, "HEAD", performRawHead(source));
    }

    public HttpResponse performRawGet(String source) {
        return performRequest(new HttpGet(source));
    }

    public HttpResponse performGet(String source) {
        return processResponse(source, "GET", performRawGet(source));
    }

    public HttpResponse performRequest(HttpRequestBase request) {
        String method = request.getMethod();

        HttpResponse response;
        try {
            response = executeGetOrHead(request);
        } catch (IOException e) {
            throw new HttpRequestException(String.format("Could not %s '%s'.", method, request.getURI()), e);
        }

        return response;
    }

    // http://www.codingpedia.org/ama/how-to-use-the-new-apache-http-client-to-make-a-head-request/

    protected HttpResponse executeGetOrHead(HttpRequestBase method) throws IOException {
        HttpResponse httpResponse = performHttpRequest(method);
        // Consume content for non-successful, responses. This avoids the connection being left open.
        if (!wasSuccessful(httpResponse)) {
            httpResponse.ignore();
            return httpResponse;
        }
        return httpResponse;
    }

    public boolean wasMissing(HttpResponse response) {
        int statusCode = response.getStatusCode();
        return statusCode == 404;
    }

    public boolean wasSuccessful(HttpResponse response) {
        int statusCode = response.getStatusCode();
        return statusCode >= 200 && statusCode < 400;
    }

    public HttpResponse performHttpRequest(HttpRequestBase request) throws IOException {
        /* ------------------------------------------------------------------------------------------------- */
        GenericUrl theUrl = new GenericUrl(request.getURI().toString());
        HttpRequest rq=null;
        if ("HEAD".equals(request.getMethod())) {
            rq=getClient().buildHeadRequest(theUrl);
        } else if ("GET".equals(request.getMethod())) {
            rq=getClient().buildGetRequest(theUrl);
        } else if ("PUT".equals(request.getMethod())) {
            rq=getClient().buildPutRequest(theUrl,null);
        } else if ("DELETE".equals(request.getMethod())) {
            rq=getClient().buildDeleteRequest(theUrl);
        } else if ("POST".equals(request.getMethod())) {
            rq=getClient().buildPostRequest(theUrl,null);
        } else if ("PATCH".equals(request.getMethod())) {
            rq=getClient().buildPatchRequest(theUrl,null);
        } else {
            rq=getClient().buildRequest(request.getMethod(),theUrl,null);
        }
         //rq.getHeaders().setContentType("application/json"); no logic on REDIRECT_LOCATIONS yet
        LOGGER.debug("Performing HTTP {}: {}", request.getMethod(), request.getURI());
        return rq.execute();
        /* ------------------------------------------------------------------------------------------------- */
        // Without this, HTTP Client prohibits multiple redirects to the same location within the same context
        //httpContext.removeAttribute(HttpClientContext.REDIRECT_LOCATIONS);
        //LOGGER.debug("Performing HTTP {}: {}", request.getMethod(), request.getURI());
        //return getClient().execute(request, httpContext);
    }

    private HttpResponse processResponse(String source, String method, HttpResponse response) {
        if (wasMissing(response)) {
            LOGGER.info("Resource missing. [HTTP {}: {}]", method, source);
            return null;
        }
        if (!wasSuccessful(response)) {
            LOGGER.info("Failed to get resource: {}. [HTTP {}: {}]", method, response.getStatusCode(), source);
            throw new UncheckedIOException(String.format("Could not %s '%s'. Received status code %s from server: %s",
                method, source, response.getStatusCode(), response.getStatusMessage()));
        }

        return response;
    }

    private synchronized HttpRequestFactory getClient() {
        if (client == null) {
            AutoProxyDetector.run();
            /*
            this.client = TRANSPORT.createRequestFactory(new HttpRequestInitializer() {
                public void initialize(HttpRequest httpRequest) {
                    this.initialize(httpRequest);
                    httpRequest.setConnectTimeout(3 * 60000);  // 3 minutes connect timeout
                    httpRequest.setReadTimeout(3 * 60000);  // 3 minutes
                }
            });
            */
            //HttpClientBuilder builder = HttpClientBuilder.create();
            //builder.setRedirectStrategy(new AlwaysRedirectRedirectStrategy());
            //new HttpClientConfigurer(settings).configure(builder);
            //this.client = builder.build();
            this.client = new NetHttpTransport.Builder().build()
                .createRequestFactory(new HttpRequestInitializer() {
                    public void initialize(HttpRequest httpRequest) {
                        this.initialize(httpRequest);
                        httpRequest.setConnectTimeout(3 * 1000);  // 3 seconds connect timeout
                        httpRequest.setReadTimeout(3 * 1000);  // 3 seconds
                    }
                });
        }
        return client;
    }

    public synchronized void close() throws IOException {
        if (client != null) {
            client.getTransport().shutdown();
            //client.close();
        }
    }

}
