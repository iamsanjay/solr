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
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_EDIT_PERM;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Optional;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.backup.BackupFilePaths;
import org.apache.solr.core.backup.ShardBackupId;
import org.apache.solr.core.backup.repository.BackupRepository;
import org.apache.solr.handler.IncrementalShardBackup;
import org.apache.solr.handler.SnapShooter;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.jersey.SolrJerseyResponse;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

@Path(("/cores/{coreName}/backup/{name}"))
public class BackupCoreAPI extends CoreAdminAPIBase {
  @Inject
  public BackupCoreAPI(
      CoreContainer coreContainer,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse,
      CoreAdminHandler.CoreAdminAsyncTracker coreAdminAsyncTracker) {
    super(coreContainer, coreAdminAsyncTracker, solrQueryRequest, solrQueryResponse);
  }

  @POST
  @Produces({"application/json", "application/xml", BINARY_CONTENT_TYPE_V2})
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse createBackup(
      @Schema(description = "The name of the core.") @PathParam("core") String coreName,
      @Schema(description = "The backup will be created in a directory called snapshot.<name>")
          @PathParam("name")
          String name,
      @Schema(description = "The POJO for representing additional backup params.") @RequestBody
          BackupCoreRequestBody backupCoreRequestBody,
      @Parameter(description = "The id to associate with the async task.") @QueryParam("async")
          String taskId)
      throws Exception {
    if (coreName == null)
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Missing required parameter: " + CoreAdminParams.CORE);
    return handlePotentiallyAsynchronousTask(
        null,
        coreName,
        taskId,
        "backup",
        () -> {
          try (BackupRepository repository =
                  coreContainer.newBackupRepository(backupCoreRequestBody.repository);
              SolrCore core = coreContainer.getCore(coreName)) {
            String location = repository.getBackupLocation(backupCoreRequestBody.location);
            if (location == null) {
              throw new SolrException(
                  SolrException.ErrorCode.BAD_REQUEST,
                  "'location' is not specified as a query"
                      + " parameter or as a default repository property");
            }
            URI locationUri = repository.createDirectoryURI(location);
            repository.createDirectory(locationUri);

            if (backupCoreRequestBody.incremental) {
              if ("file".equals(locationUri.getScheme())) {
                core.getCoreContainer().assertPathAllowed(Paths.get(locationUri));
              }
              if (backupCoreRequestBody.shardBackupId == null) {
                throw new SolrException(
                    SolrException.ErrorCode.BAD_REQUEST,
                    "Missing required parameter: shardBackupId");
              }
              final ShardBackupId shardBackupId =
                  ShardBackupId.from(backupCoreRequestBody.shardBackupId);
              final ShardBackupId prevShardBackupId =
                  backupCoreRequestBody.prevShardBackupId != null
                      ? ShardBackupId.from(backupCoreRequestBody.prevShardBackupId)
                      : null;
              BackupFilePaths incBackupFiles = new BackupFilePaths(repository, locationUri);
              IncrementalShardBackup incSnapShooter =
                  new IncrementalShardBackup(
                      repository,
                      core,
                      incBackupFiles,
                      prevShardBackupId,
                      shardBackupId,
                      Optional.ofNullable(backupCoreRequestBody.commitName));
              return incSnapShooter.backup();
            } else {
              SnapShooter snapShooter =
                  new SnapShooter(
                      repository, core, locationUri, name, backupCoreRequestBody.commitName);
              // validateCreateSnapshot will create parent dirs instead of throw; that choice is
              // dubious.
              // But we want to throw. One reason is that this dir really should, in fact must,
              // already
              // exist here if triggered via a collection backup on a shared file system. Otherwise,
              // perhaps the FS location isn't shared -- we want an error.
              if (!snapShooter.getBackupRepository().exists(snapShooter.getLocation())) {
                throw new SolrException(
                    SolrException.ErrorCode.BAD_REQUEST,
                    "Directory to contain snapshots doesn't exist: "
                        + snapShooter.getLocation()
                        + ". "
                        + "Note that Backup/Restore of a SolrCloud collection "
                        + "requires a shared file system mounted at the same path on all nodes!");
              }
              snapShooter.validateCreateSnapshot();
              return snapShooter.createSnapshot();
            }
          } catch (Exception exp) {
            throw new SolrException(
                SolrException.ErrorCode.SERVER_ERROR,
                "Failed to backup core=" + coreName + " because " + exp,
                exp);
          }
        });
  }

  public static class BackupCoreRequestBody extends SolrJerseyResponse {

    @Schema(description = "The name of the repository to be used for backup.")
    @JsonProperty("repository")
    public String repository;

    @Schema(description = "The path where the backup will be created")
    @JsonProperty("location")
    public String location;

    @JsonProperty("shardBackupId")
    public String shardBackupId;

    @JsonProperty("prevShardBackupId")
    public String prevShardBackupId;

    @Schema(
        description =
            "The name of the commit which was used while taking a snapshot using the CREATESNAPSHOT command.")
    @JsonProperty("commitName")
    public String commitName;

    @Schema(description = "To turn on incremental backup feature")
    @JsonProperty("incremental")
    public boolean incremental;
  }
}
