package com.profiler.modifier.servlet.interceptors;

import java.util.Enumeration;
import java.util.UUID;
import java.util.logging.Level;
import com.profiler.logging.Logger;
import com.profiler.logging.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

import com.profiler.common.AnnotationKey;
import com.profiler.common.ServiceType;
import com.profiler.context.*;
import com.profiler.interceptor.ByteCodeMethodDescriptorSupport;
import com.profiler.interceptor.MethodDescriptor;
import com.profiler.interceptor.StaticAroundInterceptor;
import com.profiler.interceptor.TraceContextSupport;
import com.profiler.logging.LoggingUtils;
import com.profiler.util.NumberUtils;

public class DoXXXInterceptor implements StaticAroundInterceptor, ByteCodeMethodDescriptorSupport, TraceContextSupport {

    private final Logger logger = LoggerFactory.getLogger(DoXXXInterceptor.class.getName());
    private final boolean isDebug = logger.isDebugEnabled();

    private MethodDescriptor descriptor;
    private TraceContext traceContext;

/*    
    java.lang.IllegalStateException: already Trace Object exist.
	at com.profiler.context.TraceContext.attachTraceObject(TraceContext.java:54)
	at com.profiler.modifier.servlet.interceptors.DoXXXInterceptor.before(DoXXXInterceptor.java:62)
	at org.springframework.web.servlet.FrameworkServlet.doGet(FrameworkServlet.java)								// profile method
**	at javax.servlet.http.HttpServlet.service(HttpServlet.java:617) 												// profile method
	at javax.servlet.http.HttpServlet.service(HttpServlet.java:717)
	at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:290)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:206)
	at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:88)
	at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:76)
	at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:235)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:206)
	at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:233)
	at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:191)
**	at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:127)  								// make traceId here
	at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:102)
	at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:109)
	at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:293)
	at org.apache.coyote.http11.Http11Processor.process(Http11Processor.java:859)
	at org.apache.coyote.http11.Http11Protocol$Http11ConnectionHandler.process(Http11Protocol.java:602)
	at org.apache.tomcat.util.net.JIoEndpoint$Worker.run(JIoEndpoint.java:489)
	at java.lang.Thread.run(Thread.java:680)
*/
    @Override
    public void before(Object target, String className, String methodName, String parameterDescription, Object[] args) {
        if (isDebug) {
            LoggingUtils.logBefore(logger, target, className, methodName, parameterDescription, args);
        }

        try {
//            traceContext.getActiveThreadCounter().start();

            HttpServletRequest request = (HttpServletRequest) args[0];
            String requestURL = request.getRequestURI();
            String clientIP = request.getRemoteAddr();

            TraceID traceId = populateTraceIdFromRequest(request);
            Trace trace;
            if (traceId != null) {
                if (logger.isInfoEnabled()) {
                    logger.info("TraceID exist. continue trace. " + traceId);
                    logger.debug("requestUrl:" + requestURL + " clientIp" + clientIP);
                }
                trace = traceContext.continueTraceObject(traceId);
            } else {
                trace = traceContext.newTraceObject();
                if (logger.isInfoEnabled()) {
                    logger.info("TraceID not exist. start new trace. " + trace.getTraceId());
                    logger.debug("requestUrl:" + requestURL + " clientIp" + clientIP);
                }
            }

            trace.markBeforeTime();
            trace.recordServiceType(ServiceType.TOMCAT);
            trace.recordRpcName(requestURL);

            int port = request.getServerPort();
            trace.recordEndPoint(request.getProtocol() + ":" + request.getServerName() + ((port > 0) ? ":" + port : ""));
            trace.recordDestinationId(request.getServerName() + ((port > 0) ? ":" + port : ""));
            trace.recordAttribute(AnnotationKey.HTTP_URL, request.getRequestURI());
        } catch (Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Tomcat StandardHostValve trace start fail. Caused:" + e.getMessage(), e);
            }
        }
    }

    @Override
    public void after(Object target, String className, String methodName, String parameterDescription, Object[] args, Object result) {
        if (isDebug) {
            LoggingUtils.logAfter(logger, target, className, methodName, parameterDescription, args, result);
        }

        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }
        traceContext.detachTraceObject();

        HttpServletRequest request = (HttpServletRequest) args[0];
        String parameters = getRequestParameter(request);
        if (parameters != null && parameters.length() > 0) {
            trace.recordAttribute(AnnotationKey.HTTP_PARAM, parameters);
        }


        if (trace.getStackFrameId() != 0) {
            logger.warn("Corrupted CallStack found. StackId not Root(0)");
            // 문제 있는 callstack을 dump하면 도움이 될듯.
        }

        trace.recordApi(descriptor);
//        trace.recordApi(this.apiId);

        trace.recordException(result);

        trace.markAfterTime();
        trace.traceBlockEnd();
    }

    /**
     * Pupulate source trace from HTTP Header.
     *
     * @param request
     * @return
     */
    private TraceID populateTraceIdFromRequest(HttpServletRequest request) {
        String strUUID = request.getHeader(Header.HTTP_TRACE_ID.toString());
        if (strUUID != null) {
            UUID uuid = UUID.fromString(strUUID);
            int parentSpanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_PARENT_SPAN_ID.toString()), SpanID.NULL);
            int spanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_SPAN_ID.toString()), SpanID.NULL);
            boolean sampled = Boolean.parseBoolean(request.getHeader(Header.HTTP_SAMPLED.toString()));
            short flags = NumberUtils.parseShort(request.getHeader(Header.HTTP_FLAGS.toString()), (short) 0);

            TraceID id = new DefaultTraceID(uuid, parentSpanID, spanID, sampled, flags);
            if (logger.isInfoEnabled()) {
                logger.info("TraceID exist. continue trace. " + id);
            }
            return id;
        } else {
            return null;
        }
    }

    private String getRequestParameter(HttpServletRequest request) {
        Enumeration<?> attrs = request.getParameterNames();
        StringBuilder params = new StringBuilder();

        while (attrs.hasMoreElements()) {
            String keyString = attrs.nextElement().toString();
            Object value = request.getParameter(keyString);

            if (value != null) {
                String valueString = value.toString();
                int valueStringLength = valueString.length();

                if (valueStringLength > 0 && valueStringLength < 100) {
                    params.append(keyString).append("=").append(valueString);
                }

                if (attrs.hasMoreElements()) {
                    params.append(", ");
                }
            }
        }
        return params.toString();
    }

    @Override
    public void setMethodDescriptor(MethodDescriptor descriptor) {
        this.descriptor = descriptor;
        this.traceContext.cacheApi(descriptor);
    }


    @Override
    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
