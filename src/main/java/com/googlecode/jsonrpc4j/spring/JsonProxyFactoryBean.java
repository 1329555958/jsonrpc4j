package com.googlecode.jsonrpc4j.spring;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.remoting.support.UrlBasedRemoteAccessor;

import com.googlecode.jsonrpc4j.JsonRpcClient.RequestListener;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.googlecode.jsonrpc4j.ReflectionUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

/**
 * {@link FactoryBean} for creating a {@link UrlBasedRemoteAccessor}
 * (aka consumer) for accessing an HTTP based JSON-RPC service.
 */
@SuppressWarnings("unused")
class JsonProxyFactoryBean extends UrlBasedRemoteAccessor implements MethodInterceptor, InitializingBean, FactoryBean<Object>, ApplicationContextAware {

    private Object proxyObject = null;
    private RequestListener requestListener = null;
    private ObjectMapper objectMapper = null;
    private JsonRpcHttpClient jsonRpcHttpClient = null;
    private Map<String, String> extraHttpHeaders = new HashMap<>();
    private String contentType;

    private SSLContext sslContext = null;
    private HostnameVerifier hostNameVerifier = null;

    private ApplicationContext applicationContext;


    private static LoadBalancerClient loadBalancerClient;
    //服务名称
    private String serviceId;


    @Autowired

    private Tracer tracer;


    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        proxyObject = ProxyFactory.getProxy(getServiceInterface(), this);

        if (objectMapper == null && applicationContext != null && applicationContext.containsBean("objectMapper")) {
            objectMapper = (ObjectMapper) applicationContext.getBean("objectMapper");
        }
        if (objectMapper == null && applicationContext != null) {
            try {
                objectMapper = BeanFactoryUtils.beanOfTypeIncludingAncestors(applicationContext, ObjectMapper.class);
            } catch (Exception e) {
                logger.debug(e);
            }
        }
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        try {
            jsonRpcHttpClient = new JsonRpcHttpClient(objectMapper, extraHttpHeaders);
            jsonRpcHttpClient.setRequestListener(requestListener);
            jsonRpcHttpClient.setSslContext(sslContext);
            jsonRpcHttpClient.setHostNameVerifier(hostNameVerifier);
            if (StringUtils.isEmpty(serviceId)) {
                jsonRpcHttpClient.setServiceUrl(new URL(getServiceUrl()));
            } else {
                jsonRpcHttpClient.setLoadBalancerClient(loadBalancerClient);
                jsonRpcHttpClient.setServiceId(serviceId);
                jsonRpcHttpClient.setServicePath(getServiceUrl());
                jsonRpcHttpClient.setTracer(tracer);
            }

            if (contentType != null) {
                jsonRpcHttpClient.setContentType(contentType);
            }
        } catch (MalformedURLException mue) {
            throw new RuntimeException(mue);
        }
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public LoadBalancerClient getLoadBalancerClient() {
        return loadBalancerClient;
    }

    @Autowired
    public void setLoadBalancerClient(LoadBalancerClient loadBalancerClient) {
        this.loadBalancerClient = loadBalancerClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(MethodInvocation invocation)
            throws Throwable {
        Method method = invocation.getMethod();
        if (method.getDeclaringClass() == Object.class && method.getName().equals("toString")) {
            return proxyObject.getClass().getName() + "@" + System.identityHashCode(proxyObject);
        }

        Type retType = (invocation.getMethod().getGenericReturnType() != null) ? invocation.getMethod().getGenericReturnType() : invocation.getMethod().getReturnType();
        Object arguments = ReflectionUtil.parseArguments(invocation.getMethod(), invocation.getArguments());

        return jsonRpcHttpClient.invoke(invocation.getMethod().getName(), arguments, retType, extraHttpHeaders);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getObject() {
        return proxyObject;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<?> getObjectType() {
        return getServiceInterface();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSingleton() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * @param objectMapper the objectMapper to set
     */
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param extraHttpHeaders the extraHttpHeaders to set
     */
    public void setExtraHttpHeaders(Map<String, String> extraHttpHeaders) {
        this.extraHttpHeaders = extraHttpHeaders;
    }

    /**
     * @param requestListener the requestListener to set
     */
    public void setRequestListener(RequestListener requestListener) {
        this.requestListener = requestListener;
    }

    /**
     * @param sslContext SSL context to pass to JsonRpcClient
     */
    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /**
     * @param hostNameVerifier the hostNameVerifier to pass to JsonRpcClient
     */
    public void setHostNameVerifier(HostnameVerifier hostNameVerifier) {
        this.hostNameVerifier = hostNameVerifier;
    }

    /**
     * @param contentType the contentType to pass to JsonRpcClient
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }


    public Tracer getTracer() {
        return tracer;
    }

    public void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }
}
