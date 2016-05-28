package org.gradle.internal.resource.transport.httpProxy;

import com.btr.proxy.search.ProxySearch;

import java.net.*;
import java.util.List;

public class AutoProxyDetector {

    public static void defaultProxySearch(String theURL) {
        ProxySearch proxySearch = new ProxySearch();
        proxySearch.addStrategy(ProxySearch.Strategy.OS_DEFAULT);
        proxySearch.addStrategy(ProxySearch.Strategy.JAVA);
        proxySearch.addStrategy(ProxySearch.Strategy.BROWSER);
        //proxySearch.addStrategy(Strategy.IE);
        ProxySelector proxySelector = proxySearch.getProxySelector();

        if (proxySelector == null) return;

        ProxySelector.setDefault(proxySelector);
        URI home = URI.create(theURL);
        //System.out.println("ProxySelector: " + proxySelector);
        System.out.println("URI: " + home);
        List<Proxy> proxyList = proxySelector.select(home);
        if (proxyList != null && !proxyList.isEmpty()) {
            for (Proxy proxy : proxyList) {
                System.out.println(proxy);
                SocketAddress address = proxy.address();
                if (address instanceof InetSocketAddress) {
                    String host = ((InetSocketAddress) address).getHostName();
                    String port = Integer.toString(((InetSocketAddress) address).getPort());
                    System.setProperty("http.proxyHost", host);
                    System.setProperty("http.proxyPort", port);
                }
            }
        }
    }

    public static void run() {
        defaultProxySearch("https://jcenter.bintray.com");
    }
    public static void detectProxy() {
        /* Creating a new proxy research strategy will initiate proxy-vole to
        * scan os configuration and system default browser
        */
        ProxySearch pSearch = ProxySearch.getDefaultProxySearch();
        /* It's possible to configure components to scan. For example, according
        * to detected OS, we will change research parameters
        */
        final String osName = System.getProperty("os.name").toLowerCase();
        if (osName.startsWith("windows")) {
            pSearch.addStrategy(ProxySearch.Strategy.IE);
            pSearch.addStrategy(ProxySearch.Strategy.WIN);
        } else if (osName.startsWith("linux")) {
            pSearch.addStrategy(ProxySearch.Strategy.GNOME);
            pSearch.addStrategy(ProxySearch.Strategy.KDE);
            pSearch.addStrategy(ProxySearch.Strategy.FIREFOX);
        } else {
            pSearch.addStrategy(ProxySearch.Strategy.OS_DEFAULT);
        }

        // For proxy-vole to be effective, it must be registered to the JVM
        ProxySelector.setDefault(pSearch.getProxySelector());

    }

}
