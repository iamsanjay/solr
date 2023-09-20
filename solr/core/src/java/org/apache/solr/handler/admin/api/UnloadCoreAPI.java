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

package org.apache.solr.handler.admin.api;

import static org.apache.solr.client.solrj.impl.BinaryResponseParser.BINARY_CONTENT_TYPE_V2;
import static org.apache.solr.security.PermissionNameProvider.Name.CORE_EDIT_PERM;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.common.annotation.JsonProperty;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.jersey.JacksonReflectMapWriter;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.TestInjection;

/**
 * V2 API for unloading an existing Solr core.
 *
 * <p>The new API (POST /v2/cores/coreName/unload is equivalent to the v1 /admin/cores?action=unload
 * command.
 */
@Path("/cores/{coreName}/unload")
public class UnloadCoreAPI extends CoreAdminAPIBase {

  @Inject
  public UnloadCoreAPI(
      CoreContainer coreContainer,
      CoreAdminHandler.CoreAdminAsyncTracker coreAdminAsyncTracker,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse) {
    super(coreContainer, coreAdminAsyncTracker, solrQueryRequest, solrQueryResponse);
  }

  @POST
  @Produces({"application/json", "application/xml", BINARY_CONTENT_TYPE_V2})
  @PermissionName(CORE_EDIT_PERM)
  public SolrJerseyResponse unloadCore(
      @PathParam("coreName") String coreName,
      @Schema(description = "The POJO for representing additional Unload core params") @RequestBody
          UnloadCoreRequestBody requestBody)
      throws Exception {
    ensureRequiredParameterProvided("coreName", coreName);
    SolrJerseyResponse solrJerseyResponse = instantiateJerseyResponse(SolrJerseyResponse.class);
    if (requestBody == null) {
      requestBody = new UnloadCoreRequestBody();
    }

    final var requestBodyFinal = requestBody; // Lambda below requires a 'final' variable
    return handlePotentiallyAsynchronousTask(
        solrJerseyResponse,
        coreName,
        requestBody.async,
        "unload",
        () -> {
          CoreDescriptor cdescr = coreContainer.getCoreDescriptor(coreName);
          coreContainer.unload(
              coreName,
              requestBodyFinal.deleteIndex,
              requestBodyFinal.deleteDataDir,
              requestBodyFinal.deleteInstanceDir);
          assert TestInjection.injectNonExistentCoreExceptionAfterUnload(coreName);
          return solrJerseyResponse;
        });
  }

  public static class UnloadCoreRequestBody implements JacksonReflectMapWriter {
    @Schema(description = "If true, will remove the index when unloading the core.")
    @JsonProperty(defaultValue = "false")
    public boolean deleteIndex;

    @Schema(description = "If true, removes the data directory and all sub-directories.")
    @JsonProperty(defaultValue = "false")
    public boolean deleteDataDir;

    @Schema(
        description =
            "If true, removes everything related to the core, including the index directory, configuration files and other related files.")
    @JsonProperty(defaultValue = "false")
    public boolean deleteInstanceDir;

    @Schema(description = "Request ID to track this action which will be processed asynchronously.")
    @JsonProperty
    public String async;
  }
}
