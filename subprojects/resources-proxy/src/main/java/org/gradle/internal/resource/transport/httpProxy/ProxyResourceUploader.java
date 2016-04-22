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
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.gradle.internal.resource.local.LocalResource;
import org.gradle.internal.resource.transfer.ExternalResourceUploader;
import org.gradle.internal.resource.transport.http.RepeatableInputStreamEntity;

import java.io.IOException;
import java.net.URI;

public class ProxyResourceUploader implements ExternalResourceUploader {

    private final ProxyClientHelper http;

    public ProxyResourceUploader(ProxyClientHelper http) {
        this.http = http;
    }

    public void upload(LocalResource resource, URI destination) throws IOException {
        HttpPut method = new HttpPut(destination);
        final RepeatableInputStreamEntity entity = new RepeatableInputStreamEntity(resource, ContentType.APPLICATION_OCTET_STREAM);
        method.setEntity(entity);
        HttpResponse response = http.performHttpRequest(method);
        response.ignore();
        if (!http.wasSuccessful(response)) {
            throw new IOException(String.format("Could not PUT '%s'. Received status code %s from server: %s",
                    destination, response.getStatusCode(), response.getStatusMessage() /*getReasonPhrase()*/ ));
        }

    }
}
