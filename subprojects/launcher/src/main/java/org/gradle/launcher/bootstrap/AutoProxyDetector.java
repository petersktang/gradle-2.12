package org.gradle.launcher.bootstrap;

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

}
