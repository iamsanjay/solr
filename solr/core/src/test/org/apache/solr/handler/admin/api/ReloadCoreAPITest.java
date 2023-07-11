/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin.api;

import static org.apache.solr.SolrTestCaseJ4.assumeWorkingMockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import javax.inject.Singleton;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.jersey.CatchAllExceptionMapper;
import org.apache.solr.jersey.InjectionFactories;
import org.apache.solr.jersey.NotFoundExceptionMapper;
import org.apache.solr.jersey.SolrJacksonMapper;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.SolrQueryResponse;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.BeforeClass;
import org.junit.Test;

public class ReloadCoreAPITest extends JerseyTest {
  private CoreContainer mockCoreContainer;
  private ReloadCoreAPI reloadCoreAPI;

  private static final String NON_EXISTENT_CORE = "";
  private static final String CORE_NAME = "demo";

  @BeforeClass
  public static void ensureWorkingMockito() {
    assumeWorkingMockito();
  }

  @Override
  public Application configure() {
    forceSet(TestProperties.CONTAINER_PORT, "0");
    setUpMocks();
    ResourceConfig config = new ResourceConfig();
    config.register(ReloadCoreAPI.class);
    config.register(CatchAllExceptionMapper.class);
    config.register(SolrJacksonMapper.class);
    config.register(NotFoundExceptionMapper.class);
    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(new InjectionFactories.SingletonFactory<>(mockCoreContainer))
                .to(CoreContainer.class)
                .in(Singleton.class);
          }
        });
    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(
                    new Factory<SolrQueryRequest>() {
                      @Override
                      public SolrQueryRequest provide() {
                        return new SolrQueryRequestBase(
                            mockCoreContainer.getCore("demo"), new ModifiableSolrParams()) {};
                      }

                      @Override
                      public void dispose(SolrQueryRequest instance) {}
                    })
                .to(SolrQueryRequest.class)
                .in(RequestScoped.class);
          }
        });
    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(
                    new Factory<SolrQueryResponse>() {
                      @Override
                      public SolrQueryResponse provide() {
                        return new SolrQueryResponse();
                      }

                      @Override
                      public void dispose(SolrQueryResponse instance) {}
                    })
                .to(SolrQueryResponse.class)
                .in(RequestScoped.class);
          }
        });
    return config;
  }

  @Test
  public void testValidReloadCoreAPIResponse() {
    final Response response = target("/cores/demo/reload").request().post(Entity.json(""));
    final String responseJsonStr = response.readEntity(String.class).toString();
    assertEquals(200, response.getStatus());
    assertEquals("{\"responseHeader\":{\"status\":0,\"QTime\":0}}", responseJsonStr);
  }

  @Test
  public void testNonExistentCoreExceptionResponse() {
    doThrow(
            new SolrException(
                SolrException.ErrorCode.BAD_REQUEST, "No such core: " + NON_EXISTENT_CORE))
        .when(mockCoreContainer)
        .reload("demo");
    final Response response =
        target("/cores/" + NON_EXISTENT_CORE + "/reload").request().post(Entity.json(""));
    final String responseJsonStr = response.readEntity(String.class).toString();
    assertEquals(400, response.getStatus());
    assertTrue(responseJsonStr.contains("No such core"));
  }

  @Test
  public void testUnableToReloadExceptionResponse() {
    doThrow(new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unable to reload core"))
        .when(mockCoreContainer)
        .reload(CORE_NAME);
    final Response response =
        target("/cores/" + CORE_NAME + "/reload").request().post(Entity.json(""));
    final String responseJsonStr = response.readEntity(String.class).toString();
    assertEquals(500, response.getStatus());
    assertTrue(responseJsonStr.contains("Unable to reload core"));
  }

  private void setUpMocks() {
    mockCoreContainer = mock(CoreContainer.class);
  }
}
