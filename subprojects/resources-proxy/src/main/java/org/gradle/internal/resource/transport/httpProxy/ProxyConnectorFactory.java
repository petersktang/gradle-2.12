/*
 * Copyright 2015 the original author or authors.
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

import com.google.common.collect.Sets;
import org.gradle.authentication.Authentication;
import org.gradle.authentication.http.BasicAuthentication;
import org.gradle.authentication.http.DigestAuthentication;
import org.gradle.internal.resource.transport.http.SslContextFactory;
import org.gradle.internal.resource.transport.http.DefaultHttpSettings;
import org.gradle.internal.authentication.AllSchemesAuthentication;
import org.gradle.internal.resource.connector.ResourceConnectorFactory;
import org.gradle.internal.resource.connector.ResourceConnectorSpecification;
import org.gradle.internal.resource.transfer.DefaultExternalResourceConnector;
import org.gradle.internal.resource.transfer.ExternalResourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class ProxyConnectorFactory implements ResourceConnectorFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyConnectorFactory.class);
    private SslContextFactory sslContextFactory;

    public ProxyConnectorFactory(SslContextFactory sslContextFactory) {
        this.sslContextFactory = sslContextFactory;
    }

    public Set<String> getSupportedProtocols() { return Sets.newHashSet("http", "https"); }


    public Set<Class<? extends Authentication>> getSupportedAuthentication() {
        Set<Class<? extends Authentication>> supported = new HashSet<Class<? extends Authentication>>();
        supported.add(BasicAuthentication.class);
        supported.add(DigestAuthentication.class);
        supported.add(AllSchemesAuthentication.class);
        return supported;
    }

    public ExternalResourceConnector createResourceConnector(ResourceConnectorSpecification connectionDetails) {
        ProxyClientHelper http = new ProxyClientHelper(new DefaultHttpSettings(connectionDetails.getAuthentications(), sslContextFactory));
        ProxyResourceAccessor accessor = new ProxyResourceAccessor(http);
        ProxyResourceLister lister = new ProxyResourceLister(accessor);
        ProxyResourceUploader uploader = new ProxyResourceUploader(http);
        return new DefaultExternalResourceConnector(accessor, lister, uploader);
    }
}
