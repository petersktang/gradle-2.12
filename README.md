## gradle-2.12 extended

### Background

I tried various settings recommended on the internet to run gradle build behind corporate NTLM proxy. None of them works for me. I decided to extend gradle by creating a subproject within gradle. The change is based on [gradle 2.12](http://gradle.org/gradle-download/)

### Summary of change
[Subproject "launcher"](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/launcher) is extended to use [Proxy-Vole](https://github.com/petersktang/proxy-vole), to detect corporate proxy setting.

[Subproject "resourcesProxy"](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/resources-proxy) is modeled after [subproject "resourcesHttp"](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/resources-http) and replace HttpClient by NetHttpTransport in [Google Http Java Client](https://developers.google.com/api-client-library/java/google-http-java-client/)

[Subproject "resourcesHttp"](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/resources-http) is disabled by removing its supporting protocols

[Subproject "pluginUse"](https://github.com/petersktang/gradle-2.12/tree/master/subprojects/plugin-use) has [PluginUsePluginServiceRegistry](https://github.com/petersktang/gradle-2.12/blob/master/subprojects/plugin-use/src/main/java/org/gradle/plugin/use/internal/PluginUsePluginServiceRegistry.java) changed references to ProxyPluginResolutionServiceClient instead of HttpPluginResolutionServiceClient, where [ProxyPluginResolutionServiceClient](https://github.com/petersktang/gradle-2.12/blob/master/subprojects/plugin-use/src/main/java/org/gradle/plugin/use/resolve/service/internal/ProxyPluginResolutionServiceClient.java) is also added within "pluginUse"

### Build Binary
Download this source, and run the below command to create your gradle bindary as in [Gradle](https://github.com/gradle/gradle).

    ./gradlew install -Pgradle_installPath=/usr/local/gradle-source-build
