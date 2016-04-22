/*
 * Copyright 2011 the original author or authors.
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
import org.apache.http.client.utils.DateUtils;
import org.gradle.internal.hash.HashValue;
import org.gradle.internal.resource.metadata.DefaultExternalResourceMetaData;
import org.gradle.internal.resource.metadata.ExternalResourceMetaData;
import org.gradle.internal.resource.transfer.ExternalResourceReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class ProxyResponseResource implements ExternalResourceReadResponse {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyResponseResource.class);

    private final String method;
    private final URI source;
    private final HttpResponse response;
    private final ExternalResourceMetaData metaData;
    private boolean wasOpened;

    public ProxyResponseResource(String method, URI source, HttpResponse response) {
        this.method = method;
        this.source = source;
        this.response = response;

        String etag = getEtag(response);
        this.metaData = new DefaultExternalResourceMetaData(source, getLastModified(), getContentLength(), getContentType(), etag, getSha1(response, etag));
    }

    public URI getURI() {
        return source;
    }

    @Override
    public String toString() {
        return String.format("Http Proxy %s Resource: %s", method, source);
    }

    public ExternalResourceMetaData getMetaData() {
        return metaData;
    }

    public int getStatusCode() {
        return response.getStatusCode();
    }

    public long getLastModified() {
        String lastModified = response.getHeaders().getLastModified();
        try {
            return DateUtils.parseDate(lastModified).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public long getContentLength() {
        long contentLength = response.getHeaders().getContentLength();
        return contentLength;
    }

    public String getHeaderValue(String name) {
        String header = response.getHeaders().getFirstHeaderStringValue(name);
        return header;
    }

    public String getContentType() {
        final String contentType = response.getHeaders().getContentType();
        return contentType;
    }

    public boolean isLocal() {
        return false;
    }

    public InputStream openStream() throws IOException {
        if(wasOpened){
            throw new IOException("Unable to open Stream as it was opened before.");
        }
        LOGGER.debug("Attempting to download resource {}.", source);
        this.wasOpened = true;
        return response.getContent();
    }

    public void close() throws IOException {
        response.ignore();
        //EntityUtils.consume(response.getEntity());
    }

    private static String getEtag(HttpResponse response) {
        String etag = response.getHeaders().getETag();
        return etag;
    }

    private static HashValue getSha1(HttpResponse response, String etag) {
        String sha1Header = response.getHeaders().getFirstHeaderStringValue("X-Checksum-Sha1"); //getFirstHeader("X-Checksum-Sha1");
        if (sha1Header != null) {
            return new HashValue(sha1Header);
        }

        // Nexus uses sha1 etags, with a constant prefix
        // e.g {SHA1{b8ad5573a5e9eba7d48ed77a48ad098e3ec2590b}}
        if (etag != null && etag.startsWith("{SHA1{")) {
            String hash = etag.substring(6, etag.length() - 2);
            return new HashValue(hash);
        }

        return null;
    }
}
