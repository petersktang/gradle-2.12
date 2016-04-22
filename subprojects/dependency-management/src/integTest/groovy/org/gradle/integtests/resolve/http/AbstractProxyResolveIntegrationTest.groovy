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
package org.gradle.integtests.resolve.http

import org.gradle.api.JavaVersion
import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.test.fixtures.server.http.HttpServer
import org.gradle.test.fixtures.server.http.TestProxyServer
import org.gradle.util.SetSystemProperties
import org.junit.Rule
import spock.lang.Unroll

abstract class AbstractProxyResolveIntegrationTest extends AbstractHttpDependencyResolutionTest {
    @Rule SetSystemProperties systemProperties = new SetSystemProperties()
    protected TestProxyServer testProxyServer

    @Rule
    TestProxyServer getProxyServer() {
        if (testProxyServer == null) {
            testProxyServer = new TestProxyServer()
        }
        return testProxyServer
    }

    abstract String getProxyScheme()
    abstract String getRepoServerUrl()
    abstract boolean isTunnel()
    abstract void setupServer()

    def setup() {
        buildFile << """
configurations { compile }
dependencies { compile 'log4j:log4j:1.2.17' }
task listJars << {
    assert configurations.compile.collect { it.name } == ['log4j-1.2.17.jar']
}
"""
    }

    @Unroll
    def "uses configured proxy to access remote #targetServerScheme repository"() {
        given:
        proxyServer.start()
        and:
        buildFile << """
repositories {
    maven { url "${repoServerUrl}" }
}
"""
        when:
        configureProxy()

        then:
        succeeds('listJars')

        and:
        if (isTunnel()) {
            proxyServer.requestCount == 1
        } else {
            proxyServer.requestCount == 2
        }

        where:
        targetServerScheme = new URL(repoServerUrl).protocol
    }

    @Unroll
    def "uses authenticated proxy to access remote #targetServerScheme repository"() {
        given:
        def (proxyUserName, proxyPassword) = ['proxyUser', 'proxyPassword']
        proxyServer.start(proxyUserName, proxyPassword)
        and:
        buildFile << """
repositories {
    maven { url "${repoServerUrl}" }
}
"""
        when:
        configureProxy(proxyUserName, proxyPassword)

        then:
        succeeds('listJars')

        and:
        if (isTunnel()) {
            proxyServer.requestCount == 1
        } else {
            proxyServer.requestCount == 2
        }

        where:
        targetServerScheme = new URL(repoServerUrl).protocol
    }

    @Unroll
    def "uses configured proxy to access remote #targetServerScheme repository when both https.proxy and http.proxy are specified"() {
        given:
        proxyServer.start()
        and:
        buildFile << """
repositories {
    maven { url "${repoServerUrl}" }
}
"""
        when:
        configureProxy()
        configureProxyHostFor(targetServerScheme)

        then:
        succeeds('listJars')

        and:
        proxyServer.requestCount == (tunnel ? 1 : 2)

        where:
        targetServerScheme = new URL(repoServerUrl).protocol
    }

    @Unroll
    def "can resolve from #targetServerScheme repo with #proxyServerScheme proxy configured"() {
        given:
        proxyServer.start()
        and:
        buildFile << """
repositories {
    maven { url "${targetServerScheme}://repo1.maven.org/maven2/" }
}
"""
        when:
        configureProxy()

        then:
        succeeds('listJars')

        and:
        proxyServer.requestCount == 0

        where:
        proxyServerScheme | targetServerScheme
        proxyScheme       | (proxyScheme == 'https' ? 'http' : 'https')
    }

    @Unroll
    def "passes target credentials to #authScheme authenticated server via proxy"() {
        given:
        def (proxyUserName, proxyPassword) = ['proxyUser', 'proxyPassword']
        def (repoUserName, repoPassword) = ['targetUser', 'targetPassword']
        proxyServer.start(proxyUserName, proxyPassword)
        setupServer()
        and:
        buildFile << """
repositories {
    maven {
        url "${proxyScheme}://localhost:${proxyScheme == 'https' ? server.sslPort : server.port}/repo"
        credentials {
            username '$repoUserName'
            password '$repoPassword'
        }
    }
}
"""
        and:
        def repo = mavenHttpRepo
        def module = repo.module('log4j', 'log4j', '1.2.17')
        module.publish()

        when:
        server.authenticationScheme = authScheme
        configureProxy(proxyUserName, proxyPassword)

        and:
        module.pom.expectGet(repoUserName, repoPassword)
        module.artifact.expectGet(repoUserName, repoPassword)

        then:
        succeeds('listJars')

        and:
        // authentication
        // pom and jar requests
        proxyServer.requestCount == (tunnel ? 1 : requestCount)

        where:
        authScheme                   | requestCount
        HttpServer.AuthScheme.BASIC  | 3
        HttpServer.AuthScheme.DIGEST | 3
        HttpServer.AuthScheme.NTLM   | 4
    }

    def configureProxyHostFor(String scheme) {
        executer.withArgument("-D${scheme}.proxyHost=localhost")
        executer.withArgument("-D${scheme}.proxyPort=${proxyServer.port}")
        // use proxy even when accessing localhost
        executer.withArgument("-Dhttp.nonProxyHosts=${JavaVersion.current() >= JavaVersion.VERSION_1_7 ? '' : '~localhost'}")
    }

    def configureProxy(String userName=null, String password=null) {
        configureProxyHostFor(proxyScheme)
        if (userName) {
            executer.withArgument("-D${proxyScheme}.proxyUser=${userName}")
        }
        if (password) {
            executer.withArgument("-D${proxyScheme}.proxyPassword=${password}")
        }
    }
}
