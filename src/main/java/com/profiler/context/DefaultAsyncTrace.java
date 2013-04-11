package com.profiler.context;

import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.profiler.common.AnnotationKey;
import com.profiler.common.ServiceType;
import com.profiler.interceptor.MethodDescriptor;
import com.profiler.logging.LoggingUtils;
import com.profiler.util.StringUtils;

/**
 *
 */
public class DefaultAsyncTrace implements AsyncTrace {
    private static final Logger logger = Logger.getLogger(DefaultAsyncTrace.class.getName());
    private static final boolean isDebug = logger.isLoggable(Level.FINE);

    public static final int NON_REGIST = -1;
    // private int id;
    // 비동기일 경우 traceenable의 경우 애매함. span을 보내는것으로 데이터를 생성하므로 약간 이상.
    // private boolean tracingEnabled;



    private final AtomicInteger state = new AtomicInteger(STATE_INIT);

    private int asyncId = NON_REGIST;
    private SpanEvent spanEvent;

    private Storage storage;
    private TimerTask timeoutTask;

    public DefaultAsyncTrace(SpanEvent spanEvent) {
        this.spanEvent = spanEvent;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    @Override
    public void setTimeoutTask(TimerTask timeoutTask) {
        this.timeoutTask = timeoutTask;
    }

    @Override
    public void setAsyncId(int asyncId) {
        this.asyncId = asyncId;
    }

    @Override
    public int getAsyncId() {
        return asyncId;
    }


    private Object attachObject;

    @Override
    public Object getAttachObject() {
        return attachObject;
    }

    @Override
    public void setAttachObject(Object attachObject) {
        this.attachObject = attachObject;
    }

    @Override
    public void traceBlockBegin() {
    }

    @Override
    public void markBeforeTime() {
        spanEvent.setStartTime(System.currentTimeMillis());
    }

    @Override
    public long getBeforeTime() {
        return spanEvent.getStartTime();
    }

    @Override
    public void traceBlockEnd() {
        logSpan(this.spanEvent);
    }

    @Override
    public void markAfterTime() {
        spanEvent.setEndTime(System.currentTimeMillis());
    }


    @Override
    public void recordApi(MethodDescriptor methodDescriptor) {
        if (methodDescriptor == null) {
            return;
        }
        if (methodDescriptor.getApiId() == 0) {
            recordAttribute(AnnotationKey.API, methodDescriptor.getFullName());
        } else {
            recordAttribute(AnnotationKey.API_DID, methodDescriptor.getApiId());
        }
    }

    @Override
    public void recordAttribute(final AnnotationKey key, final String value) {
        recordAttribute(key, (Object) value);
    }

    @Override
    public void recordException(Object result) {
        if (result instanceof Throwable) {
            Throwable th = (Throwable) result;
            String drop = StringUtils.drop(th.getMessage());
            recordAttribute(AnnotationKey.EXCEPTION, drop);

//            TODO 비동기 api일 경우, span에 exception을 마크하기가 까다로움
//            AnnotationKey span = getCallStack().getSpan();
//            if (span.getException() == 0) {
//                span.setException(1);
//            }
        }
    }



    @Override
    public void recordAttribute(final AnnotationKey key, final Object value) {
        spanEvent.addAnnotation(new TraceAnnotation(key, value));
    }

    @Override
    public void recordServiceType(final ServiceType serviceType) {
        this.spanEvent.setServiceType(serviceType);
    }

    @Override
    public void recordRpcName(final String rpcName) {
        this.spanEvent.setRpc(rpcName);

    }


    @Override
    public void recordDestinationId(String destinationId) {
        this.spanEvent.setDestionationId(destinationId);
    }

    // TODO: final String... endPoint로 받으면 합치는데 비용이 들어가 그냥 한번에 받는게 나을것 같음.
    @Override
    public void recordEndPoint(final String endPoint) {
        this.spanEvent.setEndPoint(endPoint);
    }

    private void annotate(final AnnotationKey key) {
        this.spanEvent.addAnnotation(new TraceAnnotation(key));

    }

    void logSpan(SpanEvent spanEvent) {
        try {
            if (isDebug) {
                Thread thread = Thread.currentThread();
                logger.info("[WRITE SpanEvent]" + spanEvent + " CurrentThreadID=" + thread.getId() + ",\n\t CurrentThreadName=" + thread.getName());
            }
            this.storage.store(spanEvent);
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    public int getState() {
        return state.get();
    }

    public void timeout() {
        if (state.compareAndSet(STATE_INIT, STATE_TIMEOUT)) {
            // TODO timeout spanEvent log 던지기.
            // 뭘 어떤 내용을 던져야 되는지 아직 모르겠음????
        }
    }

    public boolean fire() {
        if (state.compareAndSet(STATE_INIT, STATE_FIRE)) {
            if (timeoutTask != null) {
                // timeout이 걸려 있는 asynctrace일 경우 호출해 준다.
                this.timeoutTask.cancel();
            }
            return true;
        }
        return false;
    }


}
