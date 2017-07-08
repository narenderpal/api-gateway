package io.vertx.stackoverflow.gateway;

import java.util.HashMap;
import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.sstore.LocalSessionStore;

/**
 * Created by napal on 25/06/17.
 */
public class APIGatewayVerticle extends BaseVerticle {


  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 8080;

  private static Logger logger = LoggerFactory.getLogger(APIGatewayVerticle.class);

  private static final Map<String, String> SERVICE_DNS;
  static {
    SERVICE_DNS = new HashMap<>();
    SERVICE_DNS.put("user.v1", "104.198.157.96:80");
    SERVICE_DNS.put("question.v1", "35.197.27.49:80");
    SERVICE_DNS.put("questions.v2", "104.154.110.52:80");
    //SERVICE_DNS.put("user.v1", "localhost:8091");
    //SERVICE_DNS.put("question.v1", "localhost:8090");
  }


  @Override
  public void start(Future<Void> future) throws Exception {
    super.start();

    // get HTTP host and port from configuration, or use default value
    String host = config().getString("api.gateway.http.address", DEFAULT_HOST);
    int port = config().getInteger("api.gateway.http.port", DEFAULT_PORT);

    Router router = Router.router(vertx);
    // cookie and session handler
    enableLocalSession(router);

    // body handler
    router.route().handler(BodyHandler.create());

    // version handler
    router.get("/api/v").handler(this::apiVersion);

    JsonObject conf = new JsonObject().put("keyStore", new JsonObject()
      .put("path", "keystore.jceks")
      .put("type", "jceks")
      .put("password", "secret"));

    JWTAuth authProvider = JWTAuth.create(vertx, conf);

    // auth token validation handler
    router.route("/api/*").
      method(HttpMethod.PATCH).
      //method(HttpMethod.POST).
      //method(HttpMethod.PUT).
      //method(HttpMethod.DELETE).
      handler(this::authHandler);

    router.route().handler(UserSessionHandler.create(authProvider));
    //router.get("/uaa").handler(this::authUaaHandler);
    //router.get("/login").handler(this::loginEntryHandler);
    //router.post("/logout").handler(this::logoutHandler);

    // api dispatcher
    router.route("/api/*").handler(this::dispatchRequests);

    // static content
    router.route("/*").handler(StaticHandler.create());

    // enable HTTPS
    HttpServerOptions httpServerOptions = new HttpServerOptions();
      //.setSsl(true)
      //.setKeyStoreOptions(new JksOptions().setPath("server.jks").setPassword("123456"));

    // create http server
    vertx.createHttpServer(httpServerOptions)
      .requestHandler(router::accept)
      .listen(port, host, ar -> {
        if (ar.succeeded()) {
          future.complete();
          logger.info("API Gateway is running on port " + port);
        } else {
          future.fail(ar.cause());
        }
      });
  }

  private void dispatchRequests(RoutingContext context) {
    System.out.println("dispatchRequests.....");
    int initialOffset = 8; // length of `/api/v1/`
    // run with circuit breaker in order to deal with failure
    circuitBreaker.execute(future -> {

      // get relative path and retrieve prefix to dispatch client
      String path = context.request().uri();
      System.out.println("dispatchRequests.....path:" + path); // -> path :  /api/v1/user/napal

      if (path.length() <= initialOffset) {
        notFound(context);
        future.complete();
        return;
      }

      // get api version
      String separator = "/";
      String version = (path.substring(0, initialOffset).split(separator))[2];
      System.out.println("dispatchRequests.....version:" + version);

      // get service prefix e.g user or  question
      String servicePrefix = (path.substring(initialOffset).split(separator))[0];
      System.out.println("dispatchRequests.....servicePrefix:" + servicePrefix);

      // get service DNS
      String serviceName = SERVICE_DNS.get(servicePrefix + "." + version);
      System.out.println("dispatchRequests.....serviceName:" + serviceName);

      if (serviceName == null) {
        notFound(context);
        future.complete();
      }

      // generate relative uri
      String newPath = separator + path.substring(initialOffset);
      System.out.println("dispatchRequests.....newPath:" + newPath);

      // create HTTP client
      HttpClient client = vertx.createHttpClient();

      // dispatch http request
      if (client != null) {
        doDispatch(context, serviceName, newPath, client, future);
      } else {
        notFound(context);
        future.complete();
      }
    }).setHandler(ar -> {
      if (ar.failed()) {
        badGateway(ar.cause(), context);
      }
    });
  }

  private void doDispatch(RoutingContext context, String host, String path, HttpClient client, Future<Object> cbFuture) {
    System.out.println("doDispatch.....");
    HttpClientRequest toReq;
    if (host.contains(":")) {
       int port = Integer.parseInt(host.split(":")[1]);
       String hostAddress =  host.split(":")[0];
       toReq = client
        .request(context.request().method(), port, hostAddress, path, response -> {
          handleResponse(context, response, cbFuture);
        });
    } else {
       toReq = client
        .request(context.request().method(), host, path, response -> {
          handleResponse(context, response, cbFuture);
        });
    }
    // set headers
    context.request().headers().forEach(header -> {
      System.out.println("doDispatch.....set headers key :" + header.getKey());
      System.out.println("doDispatch.....set headers value:" + header.getValue());
      toReq.putHeader(header.getKey(), header.getValue());
    });

    //toReq.putHeader("Host", "localhost:8091");

    if (context.user() != null) {
      System.out.println("doDispatch.....context.user().principal().encode() :" + context.user().principal().encode());
      toReq.putHeader("user-principal", context.user().principal().encode());
    }

    // send request
    if (context.getBody() == null) {
      System.out.println("doDispatch.....Request body empty");
      toReq.end();
    } else {
      //System.out.println("doDispatch.....send request body:" + toReq.g);
      System.out.println("doDispatch.....send request uri:" + toReq.uri());
      System.out.println("doDispatch.....send request absoluteURI:" + toReq.absoluteURI());
      System.out.println("doDispatch.....send request method:" + toReq.method());
      toReq.end(context.getBody());
    }
  }

  private void handleResponse(RoutingContext context, HttpClientResponse response, Future<Object> cbFuture) {
    response.bodyHandler(body -> {
      System.out.println("doDispatch.....Got response code :" + response.statusCode());
      if (response.statusCode() >= 500) {
        // api endpoint server error, circuit breaker should fail
        cbFuture.fail(response.statusCode() + ": " + body.toString());
      } else {
        HttpServerResponse toRsp = context.response()
          .setStatusCode(response.statusCode());
        response.headers().forEach(header -> {
          toRsp.putHeader(header.getKey(), header.getValue());
        });
        // send response
        toRsp.end(body);
        cbFuture.complete();
      }
    });
  }

  private void apiVersion(RoutingContext context) {
    context.response()
      .end(new JsonObject().put("version", "v1").encodePrettily());
  }

  private void authHandler(RoutingContext context) {
    String authHeader = context.request().getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      String authToken = authHeader.split("Bearer ")[1];
      System.out.println("JWT Auth Token: " + authToken);
      context.next();
    } else {
      // Allow user create and login post requests
      if (context.request().method() == HttpMethod.POST
        && (context.request().uri().contains("/user/login") || context.request().uri().endsWith("/user"))) {
          context.next();
      } else {
        // Return 401
        logger.warn("Invalid Auth Token, Request: " + context.request().method() + " " + context.request().uri());
        System.out.println("Invalid Auth Token, Request: " + context.request().method() + " " + context.request().uri());

        context.response().setStatusCode(401).setStatusMessage(
          "Authentication failed. Missing or invalid Authorization token.").end();
      }
    }
  }

  private void logoutHandler(RoutingContext context) {
    context.clearUser();
    context.session().destroy();
    context.response().setStatusCode(204).end();
  }

  /**
   * Enable local session storage in requests.
   *
   * @param router router instance
   */
  protected void enableLocalSession(Router router) {
    router.route().handler(CookieHandler.create());
    router.route().handler(SessionHandler.create(
      LocalSessionStore.create(vertx, "question.user.session")));
  }
}
