## gradle-2.12

### Background

I tried various settings recommended on the internet to run gradle build behind corporate NTLM proxy. None of the works for me. I decided to create a subproject within gradle. The change was based on [gradle 2.12](http://gradle.org/gradle-download/)

### Summary of change
[Subproject launcher](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/launcher) is extended to use [Proxy-Vole](https://github.com/petersktang/proxy-vole), to detect corporate proxy setting.

[Subproject resourcesProxy](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/resources-proxy) is modeled after "resourcesHttp" and replace HttpClient by NetHttpTransport supported by [Google Http Java Client](https://developers.google.com/api-client-library/java/google-http-java-client/)

[Subproject resourcesHttp](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/resources-http) is disabled by removing its supporting protocols

[Subproject pluginUse](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/plugin-use) has [PluginUsePluginServiceRegistry](https://github.com/petersktang/gradle-2.12/blob/master/subprojects/plugin-use/src/main/java/org/gradle/plugin/use/internal/PluginUsePluginServiceRegistry.java) changed references to ProxyPluginResolutionServiceClient instead of HttpPluginResolutionServiceClient, where [ProxyPluginResolutionServiceClient](https://github.com/petersktang/gradle-2.12/blob/master/subprojects/plugin-use/src/main/java/org/gradle/plugin/use/resolve/service/internal/ProxyPluginResolutionServiceClient.java) is also added within "pluginUse"

### Build Binary
Same as build instruction documented in [Gradle](https://github.com/gradle/gradle) run the below to create the gradle binary.

    ./gradlew install -Pgradle_installPath=/usr/local/gradle-source-build
