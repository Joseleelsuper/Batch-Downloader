package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public class DnsHostResolver implements HostResolver {
    @Override
    public List<InetAddress> resolve(String hostname) {
        try {
            return Arrays.asList(InetAddress.getAllByName(hostname));
        } catch (UnknownHostException exception) {
            throw new DownloadRejectedException("dns_resolution_failed", exception);
        }
    }
}
