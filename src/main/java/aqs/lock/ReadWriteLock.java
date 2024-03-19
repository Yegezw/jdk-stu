package aqs.lock;

public interface ReadWriteLock {

    Lock readLock();

    Lock writeLock();
}
