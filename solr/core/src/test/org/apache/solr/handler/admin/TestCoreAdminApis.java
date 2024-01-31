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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.api.Api;
import org.apache.solr.api.ApiBag;
import org.apache.solr.core.CoreContainer;

public class TestCoreAdminApis extends SolrTestCaseJ4 {

  public void testCalls() throws Exception {
    Map<String, Object[]> calls = new HashMap<>();
    CoreContainer mockCC = getCoreContainerMock(calls, new HashMap<>());

    ApiBag apiBag;
    try (CoreAdminHandler coreAdminHandler = new CoreAdminHandler(mockCC)) {
      apiBag = new ApiBag(false);
      for (Api api : coreAdminHandler.getApis()) {
        apiBag.register(api, Collections.emptyMap());
      }
    }
  }

  @SuppressWarnings({"unchecked"})
  public static CoreContainer getCoreContainerMock(
      final Map<String, Object[]> in, Map<String, Object> out) {
    assumeWorkingMockito();

    CoreContainer mockCC = mock(CoreContainer.class);
    doAnswer(
            invocationOnMock -> {
              in.put("rename", invocationOnMock.getArguments());
              return null;
            })
        .when(mockCC)
        .rename(any(String.class), any(String.class));

    doAnswer(
            invocationOnMock -> {
              in.put("unload", invocationOnMock.getArguments());
              return null;
            })
        .when(mockCC)
        .unload(any(String.class), anyBoolean(), anyBoolean(), anyBoolean());

    when(mockCC.getCoreRootDirectory()).thenReturn(Paths.get("coreroot"));
    when(mockCC.getContainerProperties()).thenReturn(new Properties());

    when(mockCC.getRequestHandlers()).thenAnswer(invocationOnMock -> out.get("getRequestHandlers"));
    return mockCC;
  }
}
