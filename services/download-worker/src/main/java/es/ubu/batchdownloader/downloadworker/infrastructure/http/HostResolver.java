package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import java.net.InetAddress;
import java.util.List;

@FunctionalInterface
public interface HostResolver {
    List<InetAddress> resolve(String hostname);
}
