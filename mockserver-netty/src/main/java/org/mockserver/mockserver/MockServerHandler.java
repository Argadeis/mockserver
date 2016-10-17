package org.mockserver.mockserver;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.net.MediaType;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import org.mockserver.client.serialization.*;
import org.mockserver.filters.RequestLogFilter;
import org.mockserver.logging.LogFormatter;
import org.mockserver.mappers.ContentTypeMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.MockServerMatcher;
import org.mockserver.mock.action.ActionHandler;
import org.mockserver.model.*;
import org.mockserver.socket.SSLFactory;
import org.mockserver.validator.ExpectationValidator;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.mockserver.configuration.ConfigurationProperties.enableCORS;
import static org.mockserver.model.ConnectionOptions.isFalseOrNull;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;

@ChannelHandler.Sharable
public class MockServerHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private LogFormatter logFormatter = new LogFormatter(logger);
    // mockserver
    private MockServer server;
    private RequestLogFilter requestLogFilter;
    private MockServerMatcher mockServerMatcher;
    private ActionHandler actionHandler;
    // serializers
    private ExpectationSerializer expectationSerializer = new ExpectationSerializer();
    private HttpRequestSerializer httpRequestSerializer = new HttpRequestSerializer();
    private PortBindingSerializer portBindingSerializer = new PortBindingSerializer();
    private VerificationSerializer verificationSerializer = new VerificationSerializer();
    private VerificationSequenceSerializer verificationSequenceSerializer = new VerificationSequenceSerializer();
    // validators
    private ExpectationValidator expectationValidator = new ExpectationValidator();

    public MockServerHandler(MockServer server, MockServerMatcher mockServerMatcher, RequestLogFilter requestLogFilter) {
        this.mockServerMatcher = mockServerMatcher;
        this.server = server;
        this.requestLogFilter = requestLogFilter;
        actionHandler = new ActionHandler(requestLogFilter);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {

        try {
            if (enableCORS() && request.getMethod().getValue().equals("OPTIONS") && !request.getFirstHeader("Origin").isEmpty()) {

                writeResponse(ctx, request, HttpResponseStatus.OK);

            } else if (request.matches("PUT", "/status")) {

                List<Integer> actualPortBindings = server.getPorts();
                writeResponse(ctx, request, HttpResponseStatus.OK, portBindingSerializer.serialize(portBinding(actualPortBindings)), "application/json");

            } else if (request.matches("PUT", "/bind")) {

                PortBinding requestedPortBindings = portBindingSerializer.deserialize(request.getBodyAsString());
                try {
                    List<Integer> actualPortBindings = server.bindToPorts(requestedPortBindings.getPorts());
                    writeResponse(ctx, request, HttpResponseStatus.ACCEPTED, portBindingSerializer.serialize(portBinding(actualPortBindings)), "application/json");
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof BindException) {
                        writeResponse(ctx, request, HttpResponseStatus.NOT_ACCEPTABLE, e.getMessage() + " port already in use", MediaType.create("text", "plain").toString());
                    } else {
                        throw e;
                    }
                }

            } else if (request.matches("PUT", "/expectation")) {

                Expectation expectation = expectationSerializer.deserialize(request.getBodyAsString());
                List<String> validationErrors = expectationValidator.isValid(expectation);
                if (validationErrors.isEmpty()) {
                    SSLFactory.addSubjectAlternativeName(expectation.getHttpRequest().getFirstHeader(HttpHeaders.Names.HOST));
                    mockServerMatcher.when(expectation.getHttpRequest(), expectation.getTimes(), expectation.getTimeToLive()).thenRespond(expectation.getHttpResponse(false)).thenForward(expectation.getHttpForward()).thenError(expectation.getHttpError()).thenCallback(expectation.getHttpCallback());
                    logFormatter.infoLog("creating expectation:{}", expectation);
                    writeResponse(ctx, request, HttpResponseStatus.CREATED);
                } else {
                    String errorMessage = validationErrors.size() + " errors:\n - " + Joiner.on("\n - ").join(validationErrors) + "\n";
                    writeResponse(ctx, request, HttpResponseStatus.NOT_ACCEPTABLE, errorMessage, MediaType.create("text", "plain").toString());
                }

            } else if (request.matches("PUT", "/clear")) {

                org.mockserver.model.HttpRequest httpRequest = httpRequestSerializer.deserialize(request.getBodyAsString());
                if (request.hasQueryStringParameter("type", "expectation")) {
                    mockServerMatcher.clear(httpRequest);
                } else if (request.hasQueryStringParameter("type", "log")) {
                    requestLogFilter.clear(httpRequest);
                } else {
                    requestLogFilter.clear(httpRequest);
                    mockServerMatcher.clear(httpRequest);
                }
                logFormatter.infoLog("clearing expectations and request logs that match:{}", httpRequest);
                writeResponse(ctx, request, HttpResponseStatus.ACCEPTED);

            } else if (request.matches("PUT", "/reset")) {

                requestLogFilter.reset();
                mockServerMatcher.reset();
                logFormatter.infoLog("resetting all expectations and request logs");
                writeResponse(ctx, request, HttpResponseStatus.ACCEPTED);

            } else if (request.matches("PUT", "/dumpToLog")) {

                mockServerMatcher.dumpToLog(httpRequestSerializer.deserialize(request.getBodyAsString()));
                writeResponse(ctx, request, HttpResponseStatus.ACCEPTED);

            } else if (request.matches("PUT", "/retrieve")) {

                if (request.hasQueryStringParameter("type", "expectation")) {
                    Expectation[] expectations = mockServerMatcher.retrieve(httpRequestSerializer.deserialize(request.getBodyAsString()));
                    writeResponse(ctx, request, HttpResponseStatus.OK, expectationSerializer.serialize(expectations), "application/json");
                } else {
                    HttpRequest[] requests = requestLogFilter.retrieve(httpRequestSerializer.deserialize(request.getBodyAsString()));
                    writeResponse(ctx, request, HttpResponseStatus.OK, httpRequestSerializer.serialize(requests), "application/json");
                }

            } else if (request.matches("PUT", "/verify")) {

                Verification verification = verificationSerializer.deserialize(request.getBodyAsString());
                logFormatter.infoLog("verifying:{}", verification);
                String result = requestLogFilter.verify(verification);
                if (result.isEmpty()) {
                    writeResponse(ctx, request, HttpResponseStatus.ACCEPTED);
                } else {
                    writeResponse(ctx, request, HttpResponseStatus.NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
                }

            } else if (request.matches("PUT", "/verifySequence")) {

                VerificationSequence verificationSequence = verificationSequenceSerializer.deserialize(request.getBodyAsString());
                String result = requestLogFilter.verify(verificationSequence);
                logFormatter.infoLog("verifying sequence:{}", verificationSequence);
                if (result.isEmpty()) {
                    writeResponse(ctx, request, HttpResponseStatus.ACCEPTED);
                } else {
                    writeResponse(ctx, request, HttpResponseStatus.NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
                }

            } else if (request.matches("PUT", "/stop")) {

                writeResponse(ctx, request, HttpResponseStatus.ACCEPTED);
                ctx.flush();
                ctx.close();
                server.stop();

            } else {

                Action handle = mockServerMatcher.handle(request);
                if (handle instanceof HttpError) {
                    HttpError httpError = ((HttpError) handle).applyDelay();
                    if (httpError.getResponseBytes() != null) {
                        // write byte directly by skipping over HTTP codec
                        ChannelHandlerContext httpCodecContext = ctx.pipeline().context(HttpServerCodec.class);
                        if (httpCodecContext != null) {
                            httpCodecContext.writeAndFlush(Unpooled.wrappedBuffer(httpError.getResponseBytes())).awaitUninterruptibly();
                        }
                    }
                    if (httpError.getDropConnection()) {
                        ctx.close();
                    }
                } else {
                    HttpResponse response = actionHandler.processAction(handle, request);
                    logFormatter.infoLog("returning response:{}" + System.getProperty("line.separator") + " for request:{}", response, request);
                    writeResponse(ctx, request, response);
                }

            }
        } catch (Exception e) {
            logger.error("Exception processing " + request, e);
            writeResponse(ctx, request, HttpResponseStatus.BAD_REQUEST);
        }

    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus responseStatus) {
        writeResponse(ctx, request, responseStatus, "", "application/json");
    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus responseStatus, String body, String contentType) {
        HttpResponse response = response()
                .withStatusCode(responseStatus.code())
                .withBody(body);
        if (body != null && !body.isEmpty()) {
            response.updateHeader(header(CONTENT_TYPE, contentType + "; charset=utf-8"));
        }
        if (enableCORS()) {
            response.withHeader("Access-Control-Allow-Origin", "*");
            response.withHeader("Access-Control-Allow-Methods", "PUT");
            response.withHeader("X-CORS", "MockServer CORS support enabled by default, to disable ConfigurationProperties.enableCORS(false) or -Dmockserver.disableCORS=false");
        }
        writeResponse(ctx, request, response);
    }

    private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        if (response == null) {
            response = notFoundResponse();
        }

        addConnectionHeader(request, response);

        writeAndCloseSocket(ctx, request, response);
    }

    private void addConnectionHeader(HttpRequest request, HttpResponse response) {
        ConnectionOptions connectionOptions = response.getConnectionOptions();
        if (connectionOptions != null && connectionOptions.getKeepAliveOverride() != null) {
            if (connectionOptions.getKeepAliveOverride()) {
                response.updateHeader(header(CONNECTION, HttpHeaders.Values.KEEP_ALIVE));
            } else {
                response.updateHeader(header(CONNECTION, HttpHeaders.Values.CLOSE));
            }
        } else if (connectionOptions == null || isFalseOrNull(connectionOptions.getSuppressConnectionHeader())) {
            if (request.isKeepAlive() != null && request.isKeepAlive()
                    && (connectionOptions == null || isFalseOrNull(connectionOptions.getCloseSocket()))) {
                response.updateHeader(header(CONNECTION, HttpHeaders.Values.KEEP_ALIVE));
            } else {
                response.updateHeader(header(CONNECTION, HttpHeaders.Values.CLOSE));
            }
        }
    }

    private void writeAndCloseSocket(ChannelHandlerContext ctx, HttpRequest request, HttpResponse response) {
        boolean closeChannel;

        ConnectionOptions connectionOptions = response.getConnectionOptions();
        if (connectionOptions != null && connectionOptions.getCloseSocket() != null) {
            closeChannel = connectionOptions.getCloseSocket();
        } else {
            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
        }

        if (closeChannel) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.write(response);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (!cause.getMessage().contains("Connection reset by peer")) {
            logger.warn("Exception caught by MockServer handler -> closing pipeline", cause);
        }
        ctx.close();
    }
}
