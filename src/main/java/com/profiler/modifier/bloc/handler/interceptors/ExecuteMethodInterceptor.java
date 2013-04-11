package com.profiler.modifier.bloc.handler.interceptors;

import java.util.Enumeration;
import java.util.UUID;
import java.util.logging.Level;
import com.profiler.logging.Logger;

import com.profiler.common.AnnotationKey;
import com.profiler.common.ServiceType;
import com.profiler.context.*;
import com.profiler.interceptor.ByteCodeMethodDescriptorSupport;
import com.profiler.interceptor.MethodDescriptor;
import com.profiler.interceptor.StaticAroundInterceptor;
import com.profiler.interceptor.TraceContextSupport;
import com.profiler.logging.LoggerFactory;
import com.profiler.logging.LoggingUtils;
import com.profiler.util.NumberUtils;

/**
 * @author netspider
 */
public class ExecuteMethodInterceptor implements StaticAroundInterceptor, ByteCodeMethodDescriptorSupport, TraceContextSupport {

    private final Logger logger = LoggerFactory.getLogger(ExecuteMethodInterceptor.class.getName());
    private final boolean isDebug = logger.isDebugEnabled();

    private MethodDescriptor descriptor;
    private TraceContext traceContext;

    @Override
    public void before(Object target, String className, String methodName, String parameterDescription, Object[] args) {
        if (isDebug) {
            LoggingUtils.logBefore(logger, target, className, methodName, parameterDescription, args);
        }

        try {
            external.org.apache.coyote.Request request = (external.org.apache.coyote.Request) args[0];
            String requestURL = request.requestURI().toString();
            String clientIP = request.remoteAddr().toString();
            String parameters = getRequestParameter(request);

            DefaultTraceID traceId = populateTraceIdFromRequest(request);
            Trace trace;
            if (traceId != null) {
                if (logger.isInfoEnabled()) {
                    logger.info("TraceID exist. continue trace. " + traceId);
                    logger.debug("requestUrl:" + requestURL + " clientIp" + clientIP + " parameter:" + parameters);
                }

                trace = traceContext.continueTraceObject(traceId);
            } else {
                trace = new DefaultTrace();
                if (logger.isInfoEnabled()) {
                    logger.info("TraceID not exist. start new trace. " + trace.getTraceId());
                    logger.debug("requestUrl:" + requestURL + " clientIp" + clientIP + " parameter:" + parameters);
                }
                trace = traceContext.newTraceObject();
            }

            trace.markBeforeTime();

            trace.recordServiceType(ServiceType.BLOC);
            trace.recordRpcName(requestURL);


            trace.recordEndPoint(request.protocol().toString() + ":" + request.serverName().toString() + ":" + request.getServerPort());
            trace.recordDestinationId(request.serverName().toString() + ":" + request.getServerPort());
            trace.recordAttribute(AnnotationKey.HTTP_URL, request.requestURI().toString());
            if (parameters != null && parameters.length() > 0) {
                trace.recordAttribute(AnnotationKey.HTTP_PARAM, parameters);
            }

        } catch (Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn( "Tomcat StandardHostValve trace start fail. Caused:" + e.getMessage(), e);
            }
        }
    }

    @Override
    public void after(Object target, String className, String methodName, String parameterDescription, Object[] args, Object result) {
        if (isDebug) {
            LoggingUtils.logAfter(logger, target, className, methodName, parameterDescription, args, result);
        }

//        traceContext.getActiveThreadCounter().end();
        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }
        traceContext.detachTraceObject();
        if (trace.getStackFrameId() != 0) {
            logger.warn("Corrupted CallStack found. StackId not Root(0)");
            // 문제 있는 callstack을 dump하면 도움이 될듯.
        }

		trace.recordApi(descriptor);
//        trace.recordApi(this.apiId);
        trace.recordException(result);

        trace.markAfterTime();
        trace.traceRootBlockEnd();
    }

    /**
     * Pupulate source trace from HTTP Header.
     *
     * @param request
     * @return
     */
    private DefaultTraceID populateTraceIdFromRequest(external.org.apache.coyote.Request request) {
        String strUUID = request.getHeader(Header.HTTP_TRACE_ID.toString());
        if (strUUID != null) {
            UUID uuid = UUID.fromString(strUUID);
            int parentSpanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_PARENT_SPAN_ID.toString()), SpanID.NULL);
            int spanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_SPAN_ID.toString()), SpanID.NULL);
            boolean sampled = Boolean.parseBoolean(request.getHeader(Header.HTTP_SAMPLED.toString()));
            short flags = NumberUtils.parseShort(request.getHeader(Header.HTTP_FLAGS.toString()), (short) 0);

            DefaultTraceID id = new DefaultTraceID(uuid, parentSpanID, spanID, sampled, flags);
            if (logger.isInfoEnabled()) {
                logger.info("TraceID exist. continue trace. " + id);
            }
            return id;
        } else {
            return null;
        }
    }

    private String getRequestParameter(external.org.apache.coyote.Request request) {
        Enumeration<?> attrs = request.getParameters().getParameterNames();

        StringBuilder params = new StringBuilder();

        while (attrs.hasMoreElements()) {
            String keyString = attrs.nextElement().toString();
            Object value = request.getParameters().getParameter(keyString);

            if (value != null) {
                String valueString = value.toString();
                int valueStringLength = valueString.length();

                if (valueStringLength > 0 && valueStringLength < 100)
                    params.append(keyString).append("=").append(valueString);
            }
        }

        return params.toString();
    }

    @Override
    public void setMethodDescriptor(MethodDescriptor descriptor) {
        this.descriptor = descriptor;
        traceContext.cacheApi(descriptor);
    }


    @Override
    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}