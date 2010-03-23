/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.providers;

import com.google.common.collect.Multimap;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.Cookie;
import com.ning.http.client.FilePart;
import com.ning.http.client.Headers;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.Request;
import com.ning.http.client.RequestType;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import com.ning.http.collection.Pair;
import com.ning.http.multipart.ByteArrayPartSource;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.multipart.PartSource;
import com.ning.http.url.Url;
import com.ning.http.url.Url.Protocol;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultCookie;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.internal.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.jboss.netty.channel.Channels.pipeline;

@ChannelPipelineCoverage(value = "one")
public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider<HttpResponse> {
    private final static Logger log = LogManager.getLogger(NettyAsyncHttpProvider.class);
    private final ClientBootstrap bootstrap;
    private final static int MAX_BUFFERRED_BYTES = 8192;

    private volatile int redirectCount = 0;
    private final AsyncHttpClientConfig config;

    private final ConcurrentHashMap<Url, Channel> connectionsPool = new ConcurrentHashMap<Url, Channel>();

    private volatile int maxConnectionsPerHost;
    private final HashedWheelTimer timer = new HashedWheelTimer();

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {
        bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                config.executorService()));
        this.config = config;
    }

    void configure(final boolean useSSL) {
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                if (useSSL){
                    SSLEngine sslEngine = config.getSSLEngine();
                    if (sslEngine == null){
                        InputStream keyStoreStream = NettyAsyncHttpProvider.class.getResourceAsStream("keystore.jks");
                        log.warn("No SSLEngine specified. Using the default one");
                        char[] keyStorePassword = "changeit".toCharArray();
                        KeyStore ks = KeyStore.getInstance("JKS");
                        ks.load(keyStoreStream, keyStorePassword);

                        SSLContext sslContext = SSLContext.getInstance("TLS");
                        char[] certificatePassword = "changeit".toCharArray();
                        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                        kmf.init(ks, certificatePassword);

                        // Initialize the SSLContext to work with our key managers.
                        KeyManager[] keyManagers = kmf.getKeyManagers();
                        sslContext.init(keyManagers,new TrustManager[]{DUMMY_TRUST_MANAGER},new SecureRandom());
                        sslEngine = sslContext.createSSLEngine();
                        sslEngine.setUseClientMode(true);                        
                    }
                    pipeline.addLast("ssl", new SslHandler(sslEngine));
                }
                            
                pipeline.addLast("decoder", new HttpResponseDecoder());
                pipeline.addLast("encoder", new HttpRequestEncoder());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }

                IdleStateHandler h = new IdleStateHandler(timer, 0, 0, config.getIdleConnectionTimeoutInMs(), TimeUnit.MILLISECONDS) {
                    @SuppressWarnings("unused")
                    public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) throws MalformedURLException {
                        e.getChannel().close();
                        removeFromCache(ctx, e);
                    }
                };
                pipeline.addLast("timeout", h);
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });
    }

    private Channel lookupInCache(Url url) {
        Channel channel = connectionsPool.get(url);
        if (channel != null) {
            /**
             * The Channel will eventually be closed by Netty and will becomes invalid.
             * We might suffer a memory leak if we don't scan for closed channel. The
             * AsyncHttpClientConfig.reaper() will always make sure those are cleared.
             */
            if (channel.isOpen()) {
                channel.setReadable(true);
            } else {
                connectionsPool.remove(url);
            }
        }
        return channel;
    }

    /**
     * Non Blocking connect.
     */
    private final static class ConnectListener<T> implements ChannelFutureListener {

        private final AsyncHttpClientConfig config;
        private final AsyncHandler<T> asyncHandler;
        private final NettyResponseFuture<T> future;
        private final HttpRequest nettyRequest;

        private ConnectListener(AsyncHttpClientConfig config,
                                AsyncHandler<T> asyncHandler,
                                NettyResponseFuture<T> future,
                                HttpRequest nettyRequest) {
            this.config = config;
            this.asyncHandler = asyncHandler;
            this.future = future;
            this.nettyRequest = nettyRequest;
        }

        public NettyResponseFuture<T> future() {
            return future;
        }

        public final void operationComplete(ChannelFuture f) throws Exception {
            try {
                executeRequest(f.getChannel(), asyncHandler, config, future, nettyRequest);
            } catch (ConnectException ex){
                future.abort(ex);
            }
        }

        public static class Builder<T> {
            private final AsyncHttpClientConfig config;
            private final Request request;
            private final AsyncHandler<T> asyncHandler;

            public Builder(AsyncHttpClientConfig config, Request request, AsyncHandler<T> asyncHandler) {
                this.config = config;
                this.request = request;
                this.asyncHandler = asyncHandler;
            }

            public ConnectListener<T> build() throws IOException {

                Url url = createUrl(request.getUrl());
                HttpRequest nettyRequest = buildRequest(config,request,url);

                if (log.isDebugEnabled())
                    log.debug("Executing the execute operation: " + asyncHandler);

                NettyResponseFuture<T> future = new NettyResponseFuture<T>(url, request, asyncHandler,
                        nettyRequest, config.getRequestTimeoutInMs());

                return new ConnectListener<T>(config, asyncHandler, future, nettyRequest);
            }
        }
    }

    private final static <T> void executeRequest(final Channel channel,
                                             final AsyncHandler<T> asyncHandler,
                                             final AsyncHttpClientConfig config,
                                             final NettyResponseFuture<T> future,
                                             final HttpRequest nettyRequest) throws ConnectException {

        if (!channel.isConnected()){
            throw new ConnectException("Connection refused to " + channel.getRemoteAddress());
        }

        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(future);
        channel.write(nettyRequest);

        config.reaper().schedule(new Callable<Object>() {
            public Object call() {
                if (!future.isDone()) {
                    future.abort(new TimeoutException());
                }
                return null;
            }

        }, config.getRequestTimeoutInMs(), TimeUnit.MILLISECONDS);
    }

    private final static HttpRequest buildRequest(AsyncHttpClientConfig config,Request request, Url url) throws IOException{
        HttpRequest nettyRequest = null;
        switch (request.getType()) {
            case GET:
                nettyRequest = construct(config, request, HttpMethod.GET, url);
                break;
            case POST:
                nettyRequest = construct(config, request, HttpMethod.POST, url);
                break;
            case DELETE:
                nettyRequest = construct(config, request, HttpMethod.DELETE, url);
                break;
            case PUT:
                nettyRequest = construct(config, request, HttpMethod.PUT, url);
                break;
            case HEAD:
                nettyRequest = construct(config, request, HttpMethod.HEAD, url);
                break;
        }
        return nettyRequest;
    }

    private final static Url createUrl(String u) {
        URI uri = URI.create(u);
        final String scheme = uri.getScheme().toLowerCase();
        if (scheme == null || !scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + u
                    + ", must be equal (ignoring case) to 'http'");
        }

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ", must be non-null");
        } else if (path.length() == 0) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ", must be present");
        } else if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ". must start with a '/'");
        }

        int port = uri.getPort();
        if (port == -1)
            port = scheme.equals("http")? 80: 443 ;
               
        return new Url(uri.getScheme(), uri.getHost(), port, uri.getPath(), uri.getQuery());
    }

    @SuppressWarnings("deprecation")
    private final static HttpRequest construct(AsyncHttpClientConfig config,
                                               Request request,
                                               HttpMethod m,
                                               Url url) throws IOException {
        String host = url.getHost();

        if (request.getVirtualHost() != null) {
            host = request.getVirtualHost();
        }

        HttpRequest nettyRequest;
        String queryString = url.getQueryString();

        // does this GET request have a query string
        if (RequestType.GET.equals(request.getType()) && queryString != null) {
        	nettyRequest = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, m, url.getUri());
        } else {
          nettyRequest = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, m, url.getPath());
        }
        nettyRequest.setHeader(HttpHeaders.Names.HOST, host + ":" + url.getPort());

        Headers h = request.getHeaders();
        if (h != null) {
            Iterator<Pair<String, String>> i = h.iterator();
            Pair<String, String> p;
            while (i.hasNext()) {
                p = i.next();
                if ("host".equalsIgnoreCase(p.getFirst())) {
                    continue;
                }
                String key = p.getFirst() == null ? "" : p.getFirst();
                String value = p.getSecond() == null ? "" : p.getSecond();

                nettyRequest.setHeader(key, value);
            }
        }

        String ka = config.getKeepAlive() ? "keep-alive" : "close";
        nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, ka);
        if (config.getProxyServer() != null) {
            nettyRequest.setHeader("Proxy-Connection", ka);
        }

        if (config.getUserAgent() != null) {
            nettyRequest.setHeader("User-Agent", config.getUserAgent());
        }

        if (request.getCookies() != null && !request.getCookies().isEmpty()) {
            CookieEncoder httpCookieEncoder = new CookieEncoder(false);
            Iterator<Cookie> ic = request.getCookies().iterator();
            Cookie c;
            org.jboss.netty.handler.codec.http.Cookie cookie;
            while (ic.hasNext()) {
                c = ic.next();
                cookie = new DefaultCookie(c.getName(), c.getValue());
                cookie.setPath(c.getPath());
                cookie.setMaxAge(c.getMaxAge());
                cookie.setDomain(c.getDomain());
                httpCookieEncoder.addCookie(cookie);
            }
            nettyRequest.setHeader(HttpHeaders.Names.COOKIE, httpCookieEncoder.encode());
        }

        if (config.isCompressionEnabled()) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
        }

        switch (request.getType()) {
            case POST:
                if (request.getByteData() != null) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getByteData()));
                } else if (request.getStringData() != null) {
                    // TODO: Not sure we need to reconfigure that one.
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStringData().length()));
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getStringData(), "UTF-8"));
                } else if (request.getStreamData() != null) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStreamData().available()));
                    byte[] b = new byte[(int) request.getStreamData().available()];
                    request.getStreamData().read(b);
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(b));
                } else if (request.getParams() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (final Entry<String, String> param : request.getParams().entries()) {
                        sb.append(param.getKey());
                        sb.append("=");
                        sb.append(param.getValue());
                        sb.append("&");
                    }
                    sb.deleteCharAt(sb.length() - 1);
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(sb.length()));
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(sb.toString().getBytes()));
                } else if (request.getParts() != null) {
                    int lenght = computeAndSetContentLength(request, nettyRequest);

                    if (lenght == -1) {
                        lenght = MAX_BUFFERRED_BYTES;
                    }

                    MultipartRequestEntity mre = createMultipartRequestEntity(request.getParts(), request.getParams());

                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, mre.getContentType());
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(mre.getContentLength()));

                    ChannelBuffer b = ChannelBuffers.dynamicBuffer((int) lenght);
                    mre.writeRequest(new ChannelBufferOutputStream(b));
                    nettyRequest.setContent(b);
                } else if (request.getEntityWriter() != null) {
                    int lenght = computeAndSetContentLength(request, nettyRequest);
                    
                    if (lenght == -1) {
                        lenght = MAX_BUFFERRED_BYTES;
                    }

                    ChannelBuffer b = ChannelBuffers.dynamicBuffer((int) lenght);
                    request.getEntityWriter().writeEntity(new ChannelBufferOutputStream(b));
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, b.writerIndex());
                    nettyRequest.setContent(b);
                }
                break;
            case PUT:
                if (request.getByteData() != null) {
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getByteData()));
                } else if (request.getStringData() != null) {
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getStringData(), "UTF-8"));
                }
                break;
        }

        if (nettyRequest.getHeader(HttpHeaders.Names.CONTENT_TYPE) == null) {
            nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, "txt/html; charset=utf-8");
        }
        if (log.isDebugEnabled())
            log.debug("Constructed request: " + nettyRequest);
        return nettyRequest;
    }

    public void close() {
        Iterator<Entry<Url, Channel>> i = connectionsPool.entrySet().iterator();
        while (i.hasNext()) {
            i.next().getValue().close();
        }
        timer.stop();
        config.reaper().shutdown();
        config.executorService().shutdown();
    }

    /* @Override */
    public Response prepareResponse(final HttpResponseStatus<HttpResponse> status,
                                    final HttpResponseHeaders<HttpResponse> headers,
                                    final Collection<HttpResponseBodyPart<HttpResponse>> bodyParts) {
        return new NettyAsyncResponse(status,headers,bodyParts);
    }


    public <T> Future<T> execute(final Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        if (connectionsPool.size() >= config.getMaxTotalConnections()) {
            throw new IOException("Too many connections");
        }
        Url url = createUrl(request.getUrl());

        if (log.isDebugEnabled())
            log.debug("Lookup cache: " + url.toString());

        Channel channel = lookupInCache(url);
        if (channel != null) {
            HttpRequest nettyRequest = buildRequest(config,request,url);
            NettyResponseFuture<T> future = new NettyResponseFuture<T>(url, request, asyncHandler,
                nettyRequest, config.getRequestTimeoutInMs());
            executeRequest(channel,asyncHandler,config,future,nettyRequest);
            return future;
        }
        configure(url.getProtocol().compareTo(Protocol.HTTPS) == 0);

        ChannelFuture channelFuture = null;
        ConnectListener<T> c = new ConnectListener.Builder<T>(config, request, asyncHandler).build();
        try{
            if (config.getProxyServer() == null) {
                channelFuture = bootstrap.connect(
                        new InetSocketAddress(url.getHost(), url.getPort()));
            } else {
                channelFuture = bootstrap.connect(
                        new InetSocketAddress(config.getProxyServer().getHost(), config.getProxyServer().getPort()));
            }
            bootstrap.setOption("connectTimeout", (int) config.getConnectionTimeoutInMs());
        } catch (Throwable t){
            log.error(t);
            c.future().abort(t.getCause());
            return c.future(); 
        }
        channelFuture.addListener(c);
        return c.future();
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        /**
         * Discard in memory bytes if the HttpContent.interrupt() has been invoked.
         */
        if (ctx.getAttachment() instanceof DiscardEvent) {
            ctx.getChannel().setReadable(false);
            return;
        }

        NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
        Request request = future.getRequest();
        HttpRequest nettyRequest = future.getNettyRequest();
        AsyncHandler<?> handler = future.getAsyncHandler();

        try{
            if (e.getMessage() instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e.getMessage();
                // Required if there is some trailling headers.
                future.setHttpResponse(response);

                String ka = response.getHeader("Connection");
                future.setKeepAlive(ka == null || ka.toLowerCase().equals("keep-alive"));


                if (config.isRedirectEnabled()
                        && (response.getStatus().getCode() == 302 || response.getStatus().getCode() == 301) ){

                    if ( redirectCount++ < config.getMaxRedirects()) {
                        HttpRequest r = construct(config,request, map(request.getType()), createUrl(response.getHeader(HttpHeaders.Names.LOCATION)));
                        ctx.getChannel().write(r);
                        return;
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                redirectCount = 0;
                if (log.isDebugEnabled()){
                    log.debug("Status: " + response.getStatus());
                    log.debug("Version: " + response.getProtocolVersion());
                    log.debug("\"");
                    if (!response.getHeaderNames().isEmpty()) {
                        for (String name : response.getHeaderNames()) {
                            log.debug("Header: " + name + " = " + response.getHeaders(name));
                        }
                        log.debug("\"");
                    }
                }

                if (updateStatusAndInterrupt(handler, new ResponseStatus(future.getUrl(),response, this))) {
                    finishUpdate(handler, future, ctx);
                    return;
                } else if (updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getUrl(),response, this))) {
                    finishUpdate(handler, future, ctx);
                    return;
                } else if (!response.isChunked()) {
                    updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getUrl(),response, this));
                    finishUpdate(handler, future, ctx);
                    return;
                }

                if (response.getStatus().getCode() != 200 || nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
                    markAsDoneAndCacheConnection(future, ctx.getChannel());
                }

            } else if (e.getMessage() instanceof HttpChunk) {
                HttpChunk chunk = (HttpChunk) e.getMessage();

                if (handler != null) {
                    if (updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getUrl(),null, this,chunk)) || chunk.isLast()) {
                        if (chunk instanceof HttpChunkTrailer) {
                            updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getUrl(),
                                    future.getHttpResponse(), this, (HttpChunkTrailer) chunk));
                        }
                        finishUpdate(handler, future, ctx);
                        return;
                    }
                }
            }
        } catch (RuntimeException t){
            future.abort(t);
            throw t;
        }
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        removeFromCache(ctx, e);
        ctx.sendUpstream(e);
    }

    private void removeFromCache(ChannelHandlerContext ctx, ChannelEvent e) throws MalformedURLException {
        if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
            connectionsPool.remove(future.getUrl());
        }
    }

    private void markAsDoneAndCacheConnection(final NettyResponseFuture<?> future, final Channel channel) throws MalformedURLException {
        if (future.getKeepAlive() && maxConnectionsPerHost++ < config.getMaxConnectionPerHost()) {
            connectionsPool.put(future.getUrl(), channel);
        } else {
            connectionsPool.remove(future.getUrl());
        }
        future.done();
    }

    private void finishUpdate(AsyncHandler<?> handler, NettyResponseFuture<?> future, ChannelHandlerContext ctx) throws IOException {
        ctx.setAttachment(new DiscardEvent());
        markAsDoneAndCacheConnection(future, ctx.getChannel());
        ctx.getChannel().setReadable(false);
    }

    @SuppressWarnings("unchecked")
    private final boolean updateStatusAndInterrupt(AsyncHandler handler, HttpResponseStatus c) throws Exception {
        return (handler.onStatusReceived(c) == STATE.CONTINUE ? false : true);
    }

    @SuppressWarnings("unchecked")
    private final boolean updateHeadersAndInterrupt(AsyncHandler handler, HttpResponseHeaders c) throws Exception {
        return (handler.onHeadersReceived(c) == STATE.CONTINUE ? false : true);
    }

    @SuppressWarnings("unchecked")
    private final boolean updateBodyAndInterrupt(AsyncHandler handler, HttpResponseBodyPart c) throws Exception {
        return (handler.onBodyPartReceived(c) == STATE.CONTINUE ? false : true);
    }

    //Simple marker for stopping publishing bytes.
    private final static class DiscardEvent {
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception {
        Channel ch = e.getChannel();
        Throwable cause = e.getCause();

        if (log.isDebugEnabled())
            log.debug("I/O Exception during read or execute: ", e.getCause());
        if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

            if (future!= null){
                future.getAsyncHandler().onThrowable(cause);
            }
        }

        if (log.isDebugEnabled()){
            log.debug(e);
            log.debug(ch);
        }
    }

    private final static int computeAndSetContentLength(Request request, HttpRequest r) {
        int lenght = (int) request.getLength();
        if (lenght == -1 && r.getHeader(HttpHeaders.Names.CONTENT_LENGTH) != null) {
            lenght = Integer.valueOf(r.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        }

        if (lenght != -1) {
            r.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(lenght));
        }
        return lenght;
    }

    /**
     * Map CommonsHttp Method to Netty Method.
     *
     * @param type
     * @return
     */
    private final static HttpMethod map(RequestType type) {
        switch (type) {
            case GET:
                return HttpMethod.GET;
            case POST:
                return HttpMethod.POST;
            case DELETE:
                return HttpMethod.DELETE;
            case PUT:
                return HttpMethod.PUT;
            case HEAD:
                return HttpMethod.HEAD;
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * This is quite ugly as our internal names are duplicated, but we build on top of HTTP Client implementation.
     * @param params
     * @param methodParams
     * @return
     * @throws java.io.FileNotFoundException
     */
    private final static MultipartRequestEntity createMultipartRequestEntity(List<Part> params, Multimap<String,String> methodParams) throws FileNotFoundException {
        com.ning.http.multipart.Part[] parts = new com.ning.http.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof StringPart) {
                parts[i] = new com.ning.http.multipart.StringPart(part.getName(),
                        ((StringPart) part).getValue(),
                        "UTF-8");
            } else if (part instanceof FilePart) {
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        ((FilePart) part).getFile(),
                        ((FilePart) part).getMimeType(),
                        ((FilePart) part).getCharSet());

            } else if (part instanceof ByteArrayPart) {
                PartSource source = new ByteArrayPartSource(((ByteArrayPart) part).getFileName(), ((ByteArrayPart) part).getData());
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        source,
                        ((ByteArrayPart) part).getMimeType(),
                        ((ByteArrayPart) part).getCharSet());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
            } else {
                throw new IllegalArgumentException(String.format("Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, methodParams);
    }
    
    private static final TrustManager DUMMY_TRUST_MANAGER = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(
                X509Certificate[] chain, String authType) throws CertificateException {
        }

        public void checkServerTrusted(
                X509Certificate[] chain, String authType) throws CertificateException {
        }
    };

}