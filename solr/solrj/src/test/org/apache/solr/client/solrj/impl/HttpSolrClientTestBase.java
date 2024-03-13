/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.impl;

import static org.hamcrest.CoreMatchers.instanceOf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrPing;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.embedded.JettyConfig;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hamcrest.MatcherAssert;
import org.junit.BeforeClass;

public abstract class HttpSolrClientTestBase extends SolrJettyTestBase {

  protected static final String DEFAULT_CORE = "foo";
  protected static final String SLOW_SERVLET_PATH = "/slow";
  protected static final String SLOW_SERVLET_REGEX = SLOW_SERVLET_PATH + "/*";
  protected static final String DEBUG_SERVLET_PATH = "/debug";
  protected static final String DEBUG_SERVLET_REGEX = DEBUG_SERVLET_PATH + "/*";
  protected static final String REDIRECT_SERVLET_PATH = "/redirect";
  protected static final String REDIRECT_SERVLET_REGEX = REDIRECT_SERVLET_PATH + "/*";

  @BeforeClass
  public static void beforeTest() throws Exception {
    JettyConfig jettyConfig =
        JettyConfig.builder()
            .withServlet(
                new ServletHolder(BasicHttpSolrClientTest.RedirectServlet.class),
                REDIRECT_SERVLET_REGEX)
            .withServlet(
                new ServletHolder(BasicHttpSolrClientTest.SlowServlet.class), SLOW_SERVLET_REGEX)
            .withServlet(new ServletHolder(DebugServlet.class), DEBUG_SERVLET_REGEX)
            .withSSLConfig(sslConfig.buildServerSSLConfig())
            .build();
    createAndStartJetty(legacyExampleCollection1SolrHome(), jettyConfig);
  }

  @Override
  public void tearDown() throws Exception {
    System.clearProperty("basicauth");
    System.clearProperty(HttpClientUtil.SYS_PROP_HTTP_CLIENT_BUILDER_FACTORY);
    DebugServlet.clear();
    super.tearDown();
  }

  protected abstract <B extends HttpSolrClientBuilderBase<?, ?>> B builder(
      String url, int connectionTimeout, int socketTimeout);

  protected abstract String expectedUserAgent();

  protected abstract void testQuerySetup(SolrRequest.METHOD method, ResponseParser rp)
      throws Exception;

  public void testQueryGet() throws Exception {
    testQuerySetup(SolrRequest.METHOD.GET, null);
    // default method
    assertEquals("get", DebugServlet.lastMethod);
    // agent
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    // default wt
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    // default version
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    // agent
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    // content-type
    assertNull(DebugServlet.headers.get("content-type"));
    // param encoding
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
  }

  public void testQueryPost() throws Exception {
    testQuerySetup(SolrRequest.METHOD.POST, null);

    assertEquals("post", DebugServlet.lastMethod);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));
  }

  public void testQueryPut() throws Exception {
    testQuerySetup(SolrRequest.METHOD.PUT, null);

    assertEquals("put", DebugServlet.lastMethod);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));
  }

  public void testQueryXmlGet() throws Exception {
    testQuerySetup(SolrRequest.METHOD.GET, new XMLResponseParser());

    assertEquals("get", DebugServlet.lastMethod);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
  }

  public void testQueryXmlPost() throws Exception {
    testQuerySetup(SolrRequest.METHOD.POST, new XMLResponseParser());

    assertEquals("post", DebugServlet.lastMethod);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));
  }

  public void testQueryXmlPut() throws Exception {
    testQuerySetup(SolrRequest.METHOD.PUT, new XMLResponseParser());

    assertEquals("put", DebugServlet.lastMethod);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));
  }

  protected void validateDelete() {
    // default method
    assertEquals("post", DebugServlet.lastMethod);
    // agent
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    // default wt
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    // default version
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    // agent
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
  }

  public void testGetById(HttpSolrClientBase client) throws Exception {
    DebugServlet.clear();
    Collection<String> ids = Collections.singletonList("a");
    try {
      client.getById("a");
    } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
    }

    try {
      client.getById(ids, null);
    } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
    }

    try {
      client.getById("foo", "a");
    } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
    }

    try {
      client.getById("foo", ids, null);
    } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
    }
  }

  /**
   * test that SolrExceptions thrown by HttpSolrClient can correctly encapsulate http status codes
   * even when not on the list of ErrorCodes solr may return.
   */
  public void testSolrExceptionCodeNotFromSolr(HttpSolrClientBase client)
      throws IOException, SolrServerException {
    final int status = 527;
    assertEquals(
        status
            + " didn't generate an UNKNOWN error code, someone modified the list of valid ErrorCode's w/o changing this test to work a different way",
        SolrException.ErrorCode.UNKNOWN,
        SolrException.ErrorCode.getErrorCode(status));

    DebugServlet.setErrorCode(status);
    try {
      SolrQuery q = new SolrQuery("foo");
      client.query(q, SolrRequest.METHOD.GET);
      fail("Didn't get excepted exception from oversided request");
    } catch (SolrException e) {
      assertEquals("Unexpected exception status code", status, e.code());
    }
  }

  /**
   * test that SolrExceptions thrown by HttpSolrClient can correctly encapsulate http status codes
   * even when not on the list of ErrorCodes solr may return.
   */
  public void testSolrExceptionWithNullBaseurl(HttpSolrClientBase client)
      throws IOException, SolrServerException {
    final int status = 527;
    assertEquals(
        status
            + " didn't generate an UNKNOWN error code, someone modified the list of valid ErrorCode's w/o changing this test to work a different way",
        SolrException.ErrorCode.UNKNOWN,
        SolrException.ErrorCode.getErrorCode(status));

    DebugServlet.setErrorCode(status);
    try {
      // if client base url is null, request url will be used in exception message
      SolrPing ping = new SolrPing();
      ping.setBasePath(getBaseUrl() + DEBUG_SERVLET_PATH);
      client.request(ping, DEFAULT_CORE);

      fail("Didn't get excepted exception from oversided request");
    } catch (SolrException e) {
      assertEquals("Unexpected exception status code", status, e.code());
      assertTrue(e.getMessage().contains(getBaseUrl()));
    }
  }

  protected enum WT {
    JAVABIN,
    XML
  };

  protected void testUpdate(HttpSolrClientBase client, WT wt, String contentType, String docIdValue)
      throws Exception {
    DebugServlet.clear();
    UpdateRequest req = new UpdateRequest();
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", docIdValue);
    req.add(doc);
    req.setParam("a", "\u1234");

    try {
      client.request(req);
    } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
    }

    assertEquals("post", DebugServlet.lastMethod);
    assertEquals(expectedUserAgent(), DebugServlet.headers.get("user-agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals(
        wt.toString().toLowerCase(Locale.ROOT), DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(
        client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals(contentType, DebugServlet.headers.get("content-type"));
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);

    if (wt == WT.XML) {
      String requestBody = new String(DebugServlet.requestBody, StandardCharsets.UTF_8);
      assertTrue(requestBody, requestBody.contains("<field name=\"id\">" + docIdValue));
    } else if (wt == WT.JAVABIN) {
      assertNotNull(DebugServlet.requestBody);
    }
  }

  protected void testCollectionParameters(
      HttpSolrClientBase baseUrlClient, HttpSolrClientBase collection1UrlClient)
      throws IOException, SolrServerException {
    try {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", "collection");
      baseUrlClient.add("collection1", doc);
      baseUrlClient.commit("collection1");

      assertEquals(
          1,
          baseUrlClient
              .query("collection1", new SolrQuery("id:collection"))
              .getResults()
              .getNumFound());

      assertEquals(
          1, collection1UrlClient.query(new SolrQuery("id:collection")).getResults().getNumFound());
    } finally {
      baseUrlClient.close();
      collection1UrlClient.close();
    }
  }

  protected void setReqParamsOf(UpdateRequest req, String... keys) {
    if (keys != null) {
      for (String k : keys) {
        req.setParam(k, k + "Value");
      }
    }
  }

  protected void verifyServletState(HttpSolrClientBase client, SolrRequest<?> request) {
    // check query String
    Iterator<String> paramNames = request.getParams().getParameterNamesIterator();
    while (paramNames.hasNext()) {
      String name = paramNames.next();
      String[] values = request.getParams().getParams(name);
      if (values != null) {
        for (String value : values) {
          boolean shouldBeInQueryString =
              client.getUrlParamNames().contains(name)
                  || (request.getQueryParams() != null && request.getQueryParams().contains(name));
          assertEquals(
              shouldBeInQueryString, DebugServlet.queryString.contains(name + "=" + value));
          // in either case, it should be in the parameters
          assertNotNull(DebugServlet.parameters.get(name));
          assertEquals(1, DebugServlet.parameters.get(name).length);
          assertEquals(value, DebugServlet.parameters.get(name)[0]);
        }
      }
    }
  }

  protected void testQueryString() throws Exception {
    final String clientUrl = getBaseUrl() + DEBUG_SERVLET_PATH;
    UpdateRequest req = new UpdateRequest();

    try (HttpSolrClientBase client =
        builder(clientUrl, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT)
            .withDefaultCollection(DEFAULT_CORE)
            .withTheseParamNamesInTheUrl(Set.of("serverOnly"))
            .build()) {
      // test without request query params
      DebugServlet.clear();
      setReqParamsOf(req, "serverOnly", "notServer");

      try {
        client.request(req);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
      }
      verifyServletState(client, req);

      // test without server query params
      DebugServlet.clear();
    }
    try (HttpSolrClientBase client =
        builder(clientUrl, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT)
            .withTheseParamNamesInTheUrl(Set.of())
            .build()) {
      req = new UpdateRequest();
      req.setQueryParams(Set.of("requestOnly"));
      setReqParamsOf(req, "requestOnly", "notRequest");
      try {
        client.request(req);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
      }
      verifyServletState(client, req);

      // test with both request and server query params
      DebugServlet.clear();
    }
    try (HttpSolrClientBase client =
        builder(clientUrl, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT)
            .withTheseParamNamesInTheUrl(Set.of("serverOnly", "both"))
            .build()) {
      req = new UpdateRequest();
      req.setQueryParams(Set.of("requestOnly", "both"));
      setReqParamsOf(req, "serverOnly", "requestOnly", "both", "neither");
      try {
        client.request(req);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
      }
      verifyServletState(client, req);
    }
    try (HttpSolrClientBase client =
        builder(clientUrl, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT)
            .withTheseParamNamesInTheUrl(Set.of("serverOnly", "both"))
            .build()) {

      // test with both request and server query params with single stream
      DebugServlet.clear();
      req = new UpdateRequest();
      req.add(new SolrInputDocument());
      req.setQueryParams(Set.of("requestOnly", "both"));
      setReqParamsOf(req, "serverOnly", "requestOnly", "both", "neither");
      try {
        client.request(req);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {
      }
      // NOTE: single stream requests send all the params
      // as part of the query string.  So add "neither" to the request
      // so it passes the verification step.
      req.setQueryParams(Set.of("requestOnly", "both", "neither"));
      verifyServletState(client, req);
    }
  }

  protected void testGetRawStream(HttpSolrClientBase client) throws Exception {
    DebugServlet.clear();
    final var req = new QueryRequest(params("q", "*:*"));
    req.setResponseParser(new InputStreamResponseParser("xml"));
    final var rsp = req.process(client);
    Object stream = rsp.getResponse().get("stream");
    assertNotNull(stream);
    MatcherAssert.assertThat(stream, instanceOf(InputStream.class));
    InputStream is = (InputStream) stream;
    assertNotNull(is.readAllBytes()); // throws IOException if closed
    org.apache.solr.common.util.IOUtils.closeQuietly((InputStream) stream);
  }

  protected void testSetCredentialsExplicitly(HttpSolrClientBase client) {
    QueryRequest r = new QueryRequest(new SolrQuery("quick brown fox"));
    try {
      ignoreException("Error from server");
      client.request(r);
    } catch (Exception e) {
      // expected
    }
    unIgnoreException("Error from server");
    assertTrue(DebugServlet.headers.size() > 0);
    String authorizationHeader = DebugServlet.headers.get("authorization");
    assertNotNull(
        "No authorization information in headers found. Headers: " + DebugServlet.headers,
        authorizationHeader);
    assertEquals(
        "Basic "
            + Base64.getEncoder().encodeToString("foo:explicit".getBytes(StandardCharsets.UTF_8)),
        authorizationHeader);
  }

  protected void testPerRequestCredentials(HttpSolrClientBase client) {
    QueryRequest r = new QueryRequest(new SolrQuery("quick brown fox"));
    r.setBasicAuthCredentials("foo3", "per-request");
    try {
      ignoreException("Error from server");
      client.request(r);
    } catch (Exception e) {
      // expected
    }
    unIgnoreException("Error from server");
    assertTrue(DebugServlet.headers.size() > 0);
    String authorizationHeader = DebugServlet.headers.get("authorization");
    assertNotNull(
        "No authorization information in headers found. Headers: " + DebugServlet.headers,
        authorizationHeader);
    assertEquals(
        "Basic "
            + Base64.getEncoder()
                .encodeToString("foo3:per-request".getBytes(StandardCharsets.UTF_8)),
        authorizationHeader);
  }

  protected void testNoCredentials(HttpSolrClientBase client) {
    QueryRequest r = new QueryRequest(new SolrQuery("quick brown fox"));
    try {
      ignoreException("Error from server");
      client.request(r);
    } catch (Exception e) {
      // expected
    }
    unIgnoreException("Error from server");
    assertFalse(
        "Expecting no authorization header but got: " + DebugServlet.headers,
        DebugServlet.headers.containsKey("authorization"));
  }

  protected void testUseOptionalCredentials(HttpSolrClientBase client) {
    QueryRequest r = new QueryRequest(new SolrQuery("quick brown fox"));
    try {
      ignoreException("Error from server");
      client.request(r);
    } catch (Exception e) {
      // expected
    }
    unIgnoreException("Error from server");
    assertTrue(DebugServlet.headers.size() > 0);
    String authorizationHeader = DebugServlet.headers.get("authorization");
    assertNotNull(
        "No authorization information in headers found. Headers: " + DebugServlet.headers,
        authorizationHeader);
    assertEquals(
        "Basic "
            + Base64.getEncoder().encodeToString("foo:expli:cit".getBytes(StandardCharsets.UTF_8)),
        authorizationHeader);
  }

  protected void testUseOptionalCredentialsWithNull(HttpSolrClientBase client) {
    // username foo, password with embedded colon separator is "expli:cit".
    QueryRequest r = new QueryRequest(new SolrQuery("quick brown fox"));
    try {
      ignoreException("Error from server");
      client.request(r);
    } catch (Exception e) {
      // expected
    }
    unIgnoreException("Error from server");
    assertTrue(DebugServlet.headers.size() > 0);
    String authorizationHeader = DebugServlet.headers.get("authorization");
    assertNull(
        "No authorization headers expected. Headers: " + DebugServlet.headers, authorizationHeader);
  }
}
