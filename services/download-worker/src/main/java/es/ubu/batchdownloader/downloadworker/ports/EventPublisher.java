package es.ubu.batchdownloader.downloadworker.ports;

public interface EventPublisher {
    void publish(String routingKey, Object event);
}
