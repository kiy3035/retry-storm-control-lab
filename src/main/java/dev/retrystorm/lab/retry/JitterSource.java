package dev.retrystorm.lab.retry;

@FunctionalInterface
public interface JitterSource {

    double nextDouble();
}
