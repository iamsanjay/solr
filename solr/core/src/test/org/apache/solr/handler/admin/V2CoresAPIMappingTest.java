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

package org.apache.solr.handler.admin;

import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.apache.solr.common.params.CoreAdminParams.CORE;
import static org.apache.solr.common.params.CoreAdminParams.CoreAdminAction.STATUS;
import static org.apache.solr.common.params.CoreAdminParams.INDEX_INFO;

import java.util.Locale;
import java.util.Map;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.handler.admin.api.AllCoresStatusAPI;
import org.apache.solr.handler.admin.api.SingleCoreStatusAPI;
import org.junit.Test;

/**
 * Unit tests for the /cores APIs.
 *
 * <p>Note that the V2 requests made by these tests are not necessarily semantically valid. They
 * shouldn't be taken as examples. In several instances, mutually exclusive JSON parameters are
 * provided. This is done to exercise conversion of all parameters, even if particular combinations
 * are never expected in the same request.
 */
public class V2CoresAPIMappingTest extends V2ApiMappingTest<CoreAdminHandler> {

  @Override
  public void populateApiBag() {
    final CoreAdminHandler handler = getRequestHandler();
    apiBag.registerObject(new SingleCoreStatusAPI(handler));
    apiBag.registerObject(new AllCoresStatusAPI(handler));
  }

  @Override
  public CoreAdminHandler createUnderlyingRequestHandler() {
    return createMock(CoreAdminHandler.class);
  }

  @Override
  public boolean isCoreSpecific() {
    return false;
  }

  @Test
  public void testSpecificCoreStatusApiAllParams() throws Exception {
    final SolrParams v1Params =
        captureConvertedV1Params(
            "/cores/someCore", "GET", Map.of(INDEX_INFO, new String[] {"true"}));

    assertEquals(STATUS.name().toLowerCase(Locale.ROOT), v1Params.get(ACTION));
    assertEquals("someCore", v1Params.get(CORE));
    assertTrue(v1Params.getPrimitiveBool(INDEX_INFO));
  }

  @Test
  public void testAllCoreStatusApiAllParams() throws Exception {
    final SolrParams v1Params =
        captureConvertedV1Params("/cores", "GET", Map.of(INDEX_INFO, new String[] {"true"}));

    assertEquals(STATUS.name().toLowerCase(Locale.ROOT), v1Params.get(ACTION));
    assertNull("Expected 'core' parameter to be null", v1Params.get(CORE));
    assertTrue(v1Params.getPrimitiveBool(INDEX_INFO));
  }
}
