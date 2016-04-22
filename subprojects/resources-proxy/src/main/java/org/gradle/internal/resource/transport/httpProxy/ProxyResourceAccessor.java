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

import com.google.api.client.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.gradle.api.Nullable;
import org.gradle.internal.resource.metadata.ExternalResourceMetaData;
import org.gradle.internal.resource.transfer.ExternalResourceAccessor;
import org.gradle.internal.resource.transport.http.HttpRequestException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ProxyResourceAccessor implements ExternalResourceAccessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyResourceAccessor.class);
    private final ProxyClientHelper http;

    private final List<ProxyResponseResource> openResources = new ArrayList<ProxyResponseResource>();

    public ProxyResourceAccessor(ProxyClientHelper http) {
        this.http = http;
    }

    @Nullable
    public ProxyResponseResource openResource(final URI uri) {
        abortOpenResources();
        String location = uri.toString();
        LOGGER.debug("Constructing external resource: {}", location);

        HttpResponse response = http.performGet(location);
        if (response != null) {
            ProxyResponseResource resource = wrapResponse(uri, response);
            return recordOpenGetResource(resource);
        }

        return null;
    }

    /**
     * Same as #getResource except that it always gives access to the response body,
     * irrespective of the returned HTTP status code. Never returns {@code null}.
     */
    public ProxyResponseResource getRawResource(final URI uri) {
        abortOpenResources();
        String location = uri.toString();
        LOGGER.debug("Constructing external resource: {}", location);

        HttpRequestBase request = new HttpGet(uri);
        HttpResponse response;
        try {
            response = http.performHttpRequest(request);
        } catch (IOException e) {
            throw new HttpRequestException(String.format("Could not %s '%s'.", request.getMethod(), request.getURI()), e);
        }

        ProxyResponseResource resource = wrapResponse(uri, response);
        return recordOpenGetResource(resource);
    }

    public ExternalResourceMetaData getMetaData(URI uri) {
        abortOpenResources();
        String location = uri.toString();
        LOGGER.debug("Constructing external resource metadata: {}", location);
        HttpResponse response = http.performHead(location);
        return response == null ? null : new ProxyResponseResource("HEAD", uri, response).getMetaData();
    }

    private ProxyResponseResource recordOpenGetResource(ProxyResponseResource httpResource) {
        openResources.add(httpResource);
        return httpResource;
    }

    private void abortOpenResources() {
        for (Closeable openResource : openResources) {
            LOGGER.warn("Forcing close on abandoned resource: {}", openResource);
            try {
                openResource.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close abandoned resource", e);
            }
        }
        openResources.clear();
    }

    private ProxyResponseResource wrapResponse(URI uri, HttpResponse response) {
        return new ProxyResponseResource("GET", uri, response) {
            @Override
            public void close() throws IOException {
                super.close();
                ProxyResourceAccessor.this.openResources.remove(this);
            }
        };
    }

}
