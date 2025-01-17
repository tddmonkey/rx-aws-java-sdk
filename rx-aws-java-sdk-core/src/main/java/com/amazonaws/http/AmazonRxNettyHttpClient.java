/*
 * Copyright 2014-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import rx.Observable;
import rx.schedulers.Schedulers;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContentDecompressor;
import iep.io.reactivex.netty.RxNetty;
import iep.io.reactivex.netty.client.PooledConnectionReleasedEvent;
import iep.io.reactivex.netty.protocol.http.AbstractHttpConfigurator;
import iep.io.reactivex.netty.protocol.http.server.HttpServerRequest;
import iep.io.reactivex.netty.protocol.http.server.HttpServerResponse;
import iep.io.reactivex.netty.protocol.http.server.HttpResponseHeaders;
import iep.io.reactivex.netty.protocol.http.client.ClientRequestResponseConverter;
import iep.io.reactivex.netty.protocol.http.client.HttpClient;
import iep.io.reactivex.netty.protocol.http.client.HttpClient.HttpClientConfig;
import iep.io.reactivex.netty.protocol.http.client.HttpClientRequest;
import iep.io.reactivex.netty.protocol.http.client.HttpClientResponse;
import iep.io.reactivex.netty.protocol.http.client.HttpClientPipelineConfigurator;
import iep.io.reactivex.netty.protocol.http.client.HttpRequestHeaders;
import iep.io.reactivex.netty.pipeline.PipelineConfigurator;
import iep.io.reactivex.netty.pipeline.PipelineConfiguratorComposite;
import iep.io.reactivex.netty.pipeline.ssl.DefaultFactories;

import org.w3c.dom.Node;

import com.amazonaws.*;
import com.amazonaws.auth.*;
import com.amazonaws.event.*;
import com.amazonaws.handlers.*;
import com.amazonaws.http.*;
import com.amazonaws.internal.*;
import com.amazonaws.metrics.*;
import com.amazonaws.regions.*;
import com.amazonaws.transform.*;
import com.amazonaws.util.*;
import com.amazonaws.util.AWSRequestMetrics.Field;

import org.joda.time.format.ISODateTimeFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import javax.xml.bind.DatatypeConverter;

import java.security.MessageDigest;


abstract public class AmazonRxNettyHttpClient extends AmazonWebServiceClient {

  private static final Map<String,HttpClient<ByteBuf,ByteBuf>> CLIENTS =
    new ConcurrentHashMap<String,HttpClient<ByteBuf,ByteBuf>>();

  protected String mkToken(String... tokens) {
    if (tokens.length == 1)
      return tokens[0];
    else if (Arrays.stream(tokens).anyMatch(t -> { return t != null; }))
      return Arrays.stream(tokens).reduce((s1, s2) -> s1 + "|" + s2).get();
    else
      return null;
  }

  static {
    SignerFactory.registerSigner("AWS4SignerType", RxAWS4Signer.class);
  }

  private AWSCredentialsProvider awsCredentialsProvider;

  public AmazonRxNettyHttpClient() {
    this(new DefaultAWSCredentialsProviderChain(), new ClientConfiguration());
  }

  public AmazonRxNettyHttpClient(AWSCredentialsProvider awsCredentialsProvider) {
    this(awsCredentialsProvider, new ClientConfiguration());
  }

  public AmazonRxNettyHttpClient(ClientConfiguration clientConfiguration) {
    this(new DefaultAWSCredentialsProviderChain(), clientConfiguration);
  }

  public AmazonRxNettyHttpClient(
    AWSCredentialsProvider awsCredentialsProvider,
    ClientConfiguration clientConfiguration
  ) {
    super(clientConfiguration);
    this.awsCredentialsProvider = awsCredentialsProvider;
    init();
  }

  abstract protected void init();

  private <Y> Observable<Long> getBackoffStrategyDelay(Request<Y> request, int cnt, AmazonClientException error) {
      if (cnt == 0) return Observable.just(0L);
      else {
        long delay = clientConfiguration.getRetryPolicy().getBackoffStrategy().delayBeforeNextRetry(request.getOriginalRequest(), error, cnt);
        return Observable.timer(delay, TimeUnit.MILLISECONDS);
      }
    }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invokeStax(
    Request<Y> request,
    Unmarshaller<X,StaxUnmarshallerContext> unmarshaller,
    List<Unmarshaller<AmazonServiceException,Node>> errorUnmarshallers,
    ExecutionContext executionContext
  ) {
    StaxRxNettyResponseHandler<X> responseHandler = new StaxRxNettyResponseHandler<X>(unmarshaller);
    XmlRxNettyErrorResponseHandler errorResponseHandler = new XmlRxNettyErrorResponseHandler(errorUnmarshallers);

    return invoke(request, responseHandler, errorResponseHandler, executionContext);
  }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invokeJson(
    Request<Y> request,
    Unmarshaller<X,JsonUnmarshallerContext> unmarshaller,
    List<JsonErrorUnmarshaller> errorUnmarshallers,
    ExecutionContext executionContext
  ) {
    JsonRxNettyResponseHandler<X> responseHandler = new JsonRxNettyResponseHandler<X>(unmarshaller);
    JsonRxNettyErrorResponseHandler errorResponseHandler = new JsonRxNettyErrorResponseHandler(request.getServiceName(), errorUnmarshallers);

    return invoke(request, responseHandler, errorResponseHandler, executionContext);
  }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invokeJsonV2(
    Request<Y> request,
    Unmarshaller<X,JsonUnmarshallerContext> unmarshaller,
    List<JsonErrorUnmarshallerV2> errorUnmarshallers,
    ExecutionContext executionContext
  ) {
    JsonRxNettyResponseHandler<X> responseHandler = new JsonRxNettyResponseHandler<X>(unmarshaller);
    JsonRxNettyErrorResponseHandlerV2 errorResponseHandler = new JsonRxNettyErrorResponseHandlerV2(request.getServiceName(), errorUnmarshallers);

    return invoke(request, responseHandler, errorResponseHandler, executionContext);
  }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invoke(
    Request<Y> request,
    RxNettyResponseHandler<AmazonWebServiceResponse<X>> responseHandler,
    RxNettyResponseHandler<AmazonServiceException> errorResponseHandler,
    ExecutionContext executionContext
  ) {
    return Observable.defer(() -> {
      final AtomicReference<AmazonClientException> error =
        new AtomicReference<AmazonClientException>(null);

      final AtomicInteger cnt = new AtomicInteger(0);

      final Map<String, List<String>> originalParameters =
        new LinkedHashMap<String, List<String>>(request.getParameters());
      final Map<String, String> originalHeaders =
        new HashMap<String, String>(request.getHeaders());
      // Always mark the input stream before execution.
      final InputStream originalContent = request.getContent();
      if (originalContent != null && originalContent.markSupported()) {
        AmazonWebServiceRequest awsreq = request.getOriginalRequest();
        final int readLimit = awsreq.getRequestClientOptions().getReadLimit();
        originalContent.mark(readLimit);
      }

      return Observable.<X,Boolean>using(
        () -> {
          if (cnt.get() == 0) return Boolean.valueOf(false);
          return Boolean.valueOf(true);
        },
        (isPrepared) -> {
          assert(cnt.get() == 0 || error.get() != null);
          if (
            cnt.get() == 0 ||
            (
              cnt.get() < clientConfiguration.getRetryPolicy().getMaxErrorRetry() &&
              clientConfiguration.getRetryPolicy().getRetryCondition().shouldRetry(
                request.getOriginalRequest(), error.get(), cnt.get()
              )
            )
          ) {
            return Observable.defer(() -> {
              if (isPrepared) return Observable.just(null);
              return  prepareRequest(request, executionContext);
            })
            .flatMap(v -> { return getBackoffStrategyDelay(request, cnt.get(), error.get()); })
            .flatMap(i -> {
              try {
                if (isPrepared) {
                  request.setParameters(originalParameters);
                  request.setHeaders(originalHeaders);
                  request.setContent(originalContent);
                }
                return invokeImpl(request, responseHandler, errorResponseHandler, executionContext);
              }
              catch (java.io.UnsupportedEncodingException e) {
                return Observable.<X>error(e);
              }
            })
            .doOnNext(n -> {
              error.set(null);
            })
            .onErrorResumeNext(t -> {
              if (t instanceof AmazonClientException) error.set((AmazonClientException) t);
              else error.set(new AmazonClientException(t));
              return Observable.empty();
            });
          }
          else return Observable.<X>error(error.get());
        },
        (isPrepared) -> {
          cnt.getAndIncrement();
        }
      )
      .repeat()
      .first();
    });
  }

  protected <Y extends AmazonWebServiceRequest> Observable<Void> prepareRequest(
    Request<Y> request,
    ExecutionContext executionContext
  ) {
    return Observable.defer(() -> {
      request.setEndpoint(endpoint);
      request.setTimeOffset(timeOffset);
      request.addHeader("User-agent", "rx-" + clientConfiguration.getUserAgent());
      if (clientConfiguration.useGzip())
        request.addHeader("Accept-encoding", "gzip");
      AmazonWebServiceRequest originalRequest = request.getOriginalRequest();

      AWSCredentials credentials = request.getOriginalRequest().getRequestCredentials();
      if (credentials == null) {
        credentials = awsCredentialsProvider.getCredentials();
      }

      executionContext.setCredentials(credentials);

      for (RequestHandler2 requestHandler2 : requestHandler2s(executionContext)) {
        if (requestHandler2 instanceof CredentialsRequestHandler)
          ((CredentialsRequestHandler) requestHandler2).setCredentials(
            executionContext.getCredentials()
          );
        requestHandler2.beforeRequest(request);
      }

      ProgressListener listener = originalRequest.getGeneralProgressListener();

      String serviceName = request.getServiceName().substring(6).toLowerCase();
      if (serviceName.endsWith("v2"))
        serviceName = serviceName.substring(0, serviceName.length() - 2);
      if (serviceName.equals("cloudwatch"))
        serviceName = "monitoring";
      String hostName = endpoint.getHost();
      String regionName = AwsHostNameUtils.parseRegionName(hostName, serviceName);

      Signer signer = SignerFactory.getSigner(serviceName, regionName);
      signer.sign(request, credentials);
      return Observable.<Void>just(null);
    });
  }

  private List<RequestHandler2> requestHandler2s(ExecutionContext executionContext) {
    List<RequestHandler2> requestHandler2s = executionContext.getRequestHandler2s();
    return (requestHandler2s == null) ? java.util.Collections.emptyList() : requestHandler2s;
  }

  private void afterError(
    Request<?> request,
    Response<?> response,
    List<RequestHandler2> requestHandler2s,
    AmazonClientException e
  ) {
    for (RequestHandler2 handler2 : requestHandler2s) {
      handler2.afterError(request, response, e);
    }
  }

  private <T> void afterResponse(
    Request<?> request,
    List<RequestHandler2> requestHandler2s,
    Response<T> response
  ) {
    for (RequestHandler2 handler2 : requestHandler2s) {
      handler2.afterResponse(request, response);
    }
  }

  protected <X,Y extends AmazonWebServiceRequest> Observable<X> invokeImpl(
    Request<Y> request,
    RxNettyResponseHandler<AmazonWebServiceResponse<X>> responseHandler,
    RxNettyResponseHandler<AmazonServiceException> errorResponseHandler,
    ExecutionContext executionContext
  ) throws java.io.UnsupportedEncodingException {

    return Observable.defer(() -> {
      final List<RequestHandler2> requestHandler2s = requestHandler2s(executionContext);
      StringBuffer sbPath = new StringBuffer();
      if (request.getResourcePath() != null) sbPath.append(request.getResourcePath());
      if (sbPath.length() == 0) sbPath.append("/");

      String content = null;
      if (request.getContent() != null)
        content = ((StringInputStream) request.getContent()).getString();

      String queryString = RxSdkHttpUtils.encodeParameters(request);
      if (RxSdkHttpUtils.usePayloadForQueryParameters(request)) {
        request.addHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        content = queryString;
      }
      else if (queryString != null) {
        sbPath.append("?").append(queryString);
      }

      HttpClientRequest<ByteBuf> rxRequest = HttpClientRequest.create(
        HttpMethod.valueOf(request.getHttpMethod().toString()),
        sbPath.toString()
      );
      HttpRequestHeaders rxHeaders = rxRequest.getHeaders();
      request.getHeaders().entrySet().stream().forEach(e -> {
        rxHeaders.set(e.getKey(), e.getValue());
      });

      if (content != null) rxRequest.withContent(content);

      return getClient(endpoint.getHost()).submit(rxRequest)
      .flatMap(response -> {
        if (response.getStatus().code() / 100 == 2) {
          try {
            return responseHandler.handle(response).map(r -> {
              Response<X> awsResponse = new Response<X>(r.getResult(), null);
              afterResponse(request, requestHandler2s, awsResponse);
              return awsResponse.getAwsResponse();
            });
          }
          catch (Exception e) {
            return Observable.error(e);
          }
        }
        else {
          try {
            return errorResponseHandler.handle(response).flatMap(e -> {
              e.setServiceName(request.getServiceName());
              afterError(request, null, requestHandler2s, e);
              return Observable.error(e);
            });
          }
          catch (Exception e) {
            return Observable.error(e);
          }
        }
      });
    })
    .onErrorResumeNext(t -> {
      if (t instanceof AmazonClientException) return Observable.error(t);
      else return  Observable.error(new AmazonClientException(t));
    });
  }

  private HttpClient<ByteBuf,ByteBuf> getClient(String host) {
    Protocol protocol = clientConfiguration.getProtocol();
    String key = protocol + "|" + host;
    if (!CLIENTS.containsKey(key)) {
      boolean isSecure;
      int port;
      if (Protocol.HTTP.equals(protocol)) {
        isSecure = false;
        port = 80;
      }
      else if (Protocol.HTTPS.equals(protocol)) {
        isSecure = true;
        port = 443;
      }
      else {
        throw new IllegalStateException("unknown protocol: " + protocol);
      }

      HttpClientConfig config = new HttpClient.HttpClientConfig.Builder()
        .setFollowRedirect(true)
        .readTimeout(clientConfiguration.getSocketTimeout(), TimeUnit.MILLISECONDS)
        .responseSubscriptionTimeout(5000, TimeUnit.MILLISECONDS)
        .build();

      HttpClient<ByteBuf,ByteBuf> client = RxNetty.<ByteBuf,ByteBuf>newHttpClientBuilder(host, port)
        .withName(host + "." + port)
        .config(config)
        .channelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, clientConfiguration.getConnectionTimeout())
        .withMaxConnections(clientConfiguration.getMaxConnections())
        .withIdleConnectionsTimeoutMillis(60000)
        .withSslEngineFactory((isSecure) ? DefaultFactories.trustAll() : null)
        .pipelineConfigurator(
          new PipelineConfiguratorComposite<HttpClientResponse<ByteBuf>,HttpClientRequest<ByteBuf>>(
            new HttpClientPipelineConfigurator<ByteBuf,ByteBuf>(),
            new HttpDecompressionConfigurator()
          )
        )
        .appendPipelineConfigurator(
          pipeline -> pipeline.addLast(new ActiveLifeTracker(clientConfiguration.getConnectionTTL()))
        )
        .disableAutoReleaseBuffers()
        .build();
      CLIENTS.putIfAbsent(key, client);
    }
    return CLIENTS.get(key);
  }

  public class HttpDecompressionConfigurator implements PipelineConfigurator<ByteBuf,ByteBuf> {
    @Override
    public void configureNewPipeline(ChannelPipeline pipeline) {
      pipeline.addLast("deflater", new HttpContentDecompressor());
    }
  }

  private static class ActiveLifeTracker extends ChannelDuplexHandler {
    private long activationTime;
    private long ttl;

    public ActiveLifeTracker(long ttl) {
      this.ttl = ttl;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      activationTime = System.currentTimeMillis();
      super.channelActive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof PooledConnectionReleasedEvent) {
        long timeActive = System.currentTimeMillis() - activationTime;
        if (ttl >= 0 && timeActive > ttl) {
          ctx.channel().attr(ClientRequestResponseConverter.DISCARD_CONNECTION).set(true);
        }
      }
      super.userEventTriggered(ctx, evt);
    }
  }
}
