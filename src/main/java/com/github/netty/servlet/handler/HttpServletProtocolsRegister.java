package com.github.netty.servlet.handler;

import com.github.netty.core.ProtocolsRegister;
import com.github.netty.servlet.ServletContext;
import com.github.netty.servlet.ServletFilterRegistration;
import com.github.netty.servlet.ServletRegistration;
import com.github.netty.servlet.support.ServletEventListenerManager;
import com.github.netty.springboot.NettyProperties;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import java.util.Map;

/**
 * httpServlet协议注册器
 * @author acer01
 *  2018/11/11/011
 */
public class HttpServletProtocolsRegister implements ProtocolsRegister {

    public static final String HANDLER_SSL = "SSL";
    public static final String HANDLER_CHUNKED_WRITE = "ChunkedWrite";
    public static final String HANDLER_AGGREGATOR = "Aggregator";
    public static final String HANDLER_SERVLET = "Servlet";
    public static final String HANDLER_HTTP_CODEC = "HttpCodec";

    /**
     * servlet上下文
     */
    private final ServletContext servletContext;
    /**
     * https配置信息
     */
    private SslContext sslContext;
    private ChannelHandler servletHandler;

    public HttpServletProtocolsRegister(NettyProperties properties, ServletContext servletContext, SslContext sslContext) throws SSLException {
        this.servletContext = servletContext;
        this.servletHandler = new ServletHandler(servletContext,properties);
        // TODO: 10月16日/0016   ssl没测试能不能用
        this.sslContext = sslContext;
    }

    @Override
    public void onServerStart() throws Exception {
        ServletEventListenerManager listenerManager = servletContext.getServletEventListenerManager();
        if(listenerManager.hasServletContextListener()){
            listenerManager.onServletContextInitialized(new ServletContextEvent(servletContext));
        }

        initFilter(servletContext);
        initServlet(servletContext);
    }

    @Override
    public void onServerStop() {
        ServletEventListenerManager listenerManager = servletContext.getServletEventListenerManager();
        if(listenerManager.hasServletContextListener()){
            listenerManager.onServletContextDestroyed(new ServletContextEvent(servletContext));
        }

        destroyFilter();
        destroyServlet();
    }

    /**
     * 初始化过滤器
     * @param servletContext
     */
    protected void initFilter(ServletContext servletContext) throws ServletException {
        Map<String, ServletFilterRegistration> servletFilterRegistrationMap = servletContext.getFilterRegistrations();
        for(Map.Entry<String,ServletFilterRegistration> entry : servletFilterRegistrationMap.entrySet()){
            ServletFilterRegistration registration = entry.getValue();
            registration.getFilter().init(registration.getFilterConfig());
            registration.setInitParameter("_init","true");
        }
    }

    /**
     * 初始化servlet
     * @param servletContext
     */
    protected void initServlet(ServletContext servletContext) throws ServletException {
        Map<String, ServletRegistration> servletRegistrationMap = servletContext.getServletRegistrations();
        for(Map.Entry<String,ServletRegistration> entry : servletRegistrationMap.entrySet()){
            ServletRegistration registration = entry.getValue();
            registration.getServlet().init(registration.getServletConfig());
            registration.setInitParameter("_init","true");
        }
    }

    /**
     * 销毁过滤器
     */
    protected void destroyFilter(){
        Map<String, ServletFilterRegistration> servletRegistrationMap = servletContext.getFilterRegistrations();
        for(Map.Entry<String,ServletFilterRegistration> entry : servletRegistrationMap.entrySet()){
            ServletFilterRegistration registration = entry.getValue();
            Filter filter = registration.getFilter();
            if(filter == null) {
                continue;
            }
            String initFlag = registration.getInitParameter("_init");
            if(initFlag != null && "true".equals(initFlag)){
                filter.destroy();
            }
        }
    }

    /**
     * 销毁servlet
     */
    protected void destroyServlet(){
        Map<String, ServletRegistration> servletRegistrationMap = servletContext.getServletRegistrations();
        for(Map.Entry<String,ServletRegistration> entry : servletRegistrationMap.entrySet()){
            ServletRegistration registration = entry.getValue();
            Servlet servlet = registration.getServlet();
            if(servlet == null) {
                continue;
            }
            String initFlag = registration.getInitParameter("_init");
            if(initFlag != null && "true".equals(initFlag)){
                servlet.destroy();
            }
        }
    }

    @Override
    public boolean canSupport(ByteBuf msg) {
        return true;
    }

    @Override
    public void register(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        if (sslContext != null) {
            SSLEngine engine = sslContext.newEngine(ch.alloc());
            engine.setUseClientMode(false);
            pipeline.addLast(HANDLER_SSL, new SslHandler(engine,true));
        }

        //HTTP编码解码
        pipeline.addLast(HANDLER_HTTP_CODEC, new HttpServerCodec(4096, 8192, 5 * 1024 * 1024, false));

        //HTTP请求body聚合，设置最大消息值为 5M
        pipeline.addLast(HANDLER_AGGREGATOR, new HttpObjectAggregator(5 * 1024 * 1024));

        //内容压缩
//                    pipeline.addLast("ContentCompressor", new HttpContentCompressor());
//                pipeline.addLast("ContentDecompressor", new HttpContentDecompressor());

        //分段写入, 用于流传输, 防止响应数据过大
//                pipeline.addLast("ChunkedWrite",new ChunkedWriteHandler());

        //业务调度器, 让对应的Servlet处理请求
        pipeline.addLast(HANDLER_SERVLET, servletHandler);
    }

    @Override
    public String getProtocolName() {
        return "http/servlet";
    }

    public ServletContext getServletContext() {
        return servletContext;
    }
}