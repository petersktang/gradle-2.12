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

import org.gradle.internal.resource.connector.ResourceConnectorFactory;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.PluginServiceRegistry;
import org.gradle.internal.resource.transport.http.SslContextFactory;
import org.gradle.internal.resource.transport.http.DefaultSslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyResourcesPluginServiceRegistry implements PluginServiceRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyResourcesPluginServiceRegistry.class);
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new GlobalScopeServices());
    }

    public void registerBuildSessionServices(ServiceRegistration registration) {
    }

    public void registerBuildServices(ServiceRegistration registration) {
        // registration.addProvider(new AuthenticationSchemeAction());
    }

    public void registerGradleServices(ServiceRegistration registration) {
    }

    public void registerProjectServices(ServiceRegistration registration) {
    }

    private static class GlobalScopeServices {
        SslContextFactory createSslContextFactory() {
            return new DefaultSslContextFactory();
        }

        ResourceConnectorFactory createProxyConnectorFactory(SslContextFactory sslContextFactory) {
            return new ProxyConnectorFactory(sslContextFactory);
        }
    }
}
