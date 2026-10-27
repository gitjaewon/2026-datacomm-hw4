// JobQueue.java — Worker의 수신 Thread → 계산 Thread 작업 큐 (Mutex 1개 + Condition Variable 1개)
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class JobQueue<T> {
    private final ArrayDeque<T> q = new ArrayDeque<>();
    private final ReentrantLock m = new ReentrantLock();
    private final Condition notEmpty = m.newCondition();   // 수신 → 계산 "일이 들어왔다"

    void put(T job) {
        m.lock();
        try {
            q.addLast(job);
            notEmpty.signal();
        } finally { m.unlock(); }
    }

    T take() throws InterruptedException {
        m.lock();
        try {
            while (q.isEmpty()) notEmpty.await();          // spurious wakeup 대비 while
            return q.pollFirst();
        } finally { m.unlock(); }
    }
}
