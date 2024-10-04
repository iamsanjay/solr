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

package org.apache.solr.filestore;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.DELETE;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.GET;
import static org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST;
import static org.apache.solr.common.SolrException.ErrorCode.SERVER_ERROR;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.jcip.annotations.NotThreadSafe;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.InputStreamResponseParser;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrPaths;
import org.apache.solr.filestore.FileStoreAPI.MetaData;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
public class DistribFileStore implements FileStore {
  static final long MAX_PKG_SIZE =
      Long.parseLong(System.getProperty("max.file.store.size", String.valueOf(100 * 1024 * 1024)));

  /** This is where al the files in the package store are listed */
  static final String ZK_PACKAGESTORE = "/packagestore";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final CoreContainer coreContainer;
  private Map<String, FileInfo> tmpFiles = new ConcurrentHashMap<>();

  private final Path solrHome;

  public DistribFileStore(CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
    this.solrHome = Paths.get(this.coreContainer.getSolrHome());
  }

  @Override
  public Path getRealpath(String path) {
    return _getRealPath(path, solrHome);
  }

  private static Path _getRealPath(String path, Path solrHome) {
    if (File.separatorChar == '\\') {
      path = path.replace('/', File.separatorChar);
    }
    SolrPaths.assertNotUnc(Path.of(path));
    while (path.startsWith(File.separator)) { // Trim all leading slashes
      path = path.substring(1);
    }
    var finalPath = getFileStoreDirPath(solrHome).resolve(path);
    // Guard against path traversal by asserting final path is sub path of filestore
    if (!finalPath.normalize().startsWith(getFileStoreDirPath(solrHome).normalize())) {
      throw new SolrException(BAD_REQUEST, "Illegal path " + path);
    }
    return finalPath;
  }

  class FileInfo {
    final String path;
    String metaPath;
    ByteBuffer fileData, metaData;

    FileInfo(String path) {
      this.path = path;
    }

    ByteBuffer getFileData(boolean validate) throws IOException {
      if (fileData == null) {
        fileData = ByteBuffer.wrap(Files.readAllBytes(getRealpath(path)));
      }
      return fileData;
    }

    public String getMetaPath() {
      if (metaPath == null) {
        metaPath = _getMetapath(path);
      }
      return metaPath;
    }

    private void persistToFile(ByteBuffer data, ByteBuffer meta) throws IOException {
      synchronized (DistribFileStore.this) {
        this.metaData = meta;
        this.fileData = data;
        _persistToFile(solrHome, path, data, meta);
        if (log.isInfoEnabled()) {
          log.info(
              "persisted a file {} and metadata. sizes {} {}", path, data.limit(), meta.limit());
        }
      }
    }

    public boolean exists(boolean validateContent, boolean fetchMissing) throws IOException {
      Path file = getRealpath(path);
      if (!Files.exists(file)) {
        if (fetchMissing) {
          return fetchFromAnyNode();
        } else {
          return false;
        }
      }

      if (validateContent) {
        MetaData metaData = readMetaData();
        if (metaData == null) return false;
        try (InputStream is = Files.newInputStream(file)) {
          if (!Objects.equals(DigestUtils.sha512Hex(is), metaData.sha512)) {
            deleteFile();
          } else {
            return true;
          }
        } catch (Exception e) {
          throw new SolrException(SERVER_ERROR, "unable to parse metadata json file");
        }
      } else {
        return true;
      }

      return false;
    }

    private void deleteFile() {
      try {
        IOUtils.deleteFilesIfExist(getRealpath(path), getRealpath(getMetaPath()));
      } catch (IOException e) {
        log.error("Unable to delete files: {}", path);
      }
    }

    private boolean fetchFileFromNodeAndPersist(String fromNode) {
      log.info("fetching a file {} from {} ", path, fromNode);
      String baseUrl =
          coreContainer.getZkController().getZkStateReader().getBaseUrlV2ForNodeName(fromNode);
      if (baseUrl == null) throw new SolrException(BAD_REQUEST, "No such node");

      ByteBuffer metadata = null;
      Map<?, ?> m = null;

      InputStream is = null;

      try (var solrClient =
          new Http2SolrClient.Builder(baseUrl)
              .withHttpClient(coreContainer.getDefaultHttpSolrClient())
              .build()) {
        GenericSolrRequest request = new GenericSolrRequest(GET, "/node/files" + getMetaPath());

        request.setResponseParser(new InputStreamResponseParser(null));
        var response = solrClient.request(request);

        is = (InputStream) response.get("stream");

        metadata =
            Utils.newBytesConsumer((int) MAX_PKG_SIZE).accept((InputStream) response.get("stream"));

        m = (Map<?, ?>) Utils.fromJSON(metadata.array(), metadata.arrayOffset(), metadata.limit());

      } catch (SolrServerException | IOException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error fetching metadata", e);
      } finally {
        try {
          IOUtils.close(is);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      try (var solrClient =
          new Http2SolrClient.Builder(baseUrl)
              .withHttpClient(coreContainer.getDefaultHttpSolrClient())
              .build()) {
        GenericSolrRequest solrRequest = new GenericSolrRequest(GET, "/node/files" + path);
        solrRequest.setResponseParser(new InputStreamResponseParser(null));
        var response = solrClient.request(solrRequest);

        is = (InputStream) response.get("stream");

        ByteBuffer filedata =
            Utils.newBytesConsumer((int) MAX_PKG_SIZE).accept((InputStream) response.get("stream"));

        filedata.mark();
        String sha512 = DigestUtils.sha512Hex(new ByteBufferInputStream(filedata));
        String expected = (String) m.get("sha512");
        if (!sha512.equals(expected)) {
          throw new SolrException(
              SERVER_ERROR, "sha512 mismatch downloading : " + path + " from node : " + fromNode);
        }
        filedata.reset();
        persistToFile(filedata, metadata);
        return true;

      } catch (SolrServerException | IOException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error fetching data", e);
      } finally {
        try {
          IOUtils.close(is);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    boolean fetchFromAnyNode() {
      ArrayList<String> nodesToAttemptFetchFrom =
          FileStoreUtils.fetchAndShuffleRemoteLiveNodes(coreContainer);
      for (String liveNode : nodesToAttemptFetchFrom) {
        try {
          String baseUrl =
              coreContainer.getZkController().getZkStateReader().getBaseUrlV2ForNodeName(liveNode);
          final var solrParams = new ModifiableSolrParams();
          solrParams.add("meta", "true");
          solrParams.add("omitHeader", "true");

          final var request = new GenericSolrRequest(GET, "/node/files" + path, solrParams);
          boolean nodeHasBlob = false;

          try (var solrClient =
              new Http2SolrClient.Builder(baseUrl)
                  .withHttpClient(coreContainer.getDefaultHttpSolrClient())
                  .build()) {
            var resp = solrClient.request(request);

            if (Utils.getObjectByPath(resp, false, Arrays.asList("files", path)) != null) {
              nodeHasBlob = true;
            }
          }

          if (nodeHasBlob) {
            boolean success = fetchFileFromNodeAndPersist(liveNode);
            if (success) return true;
          }
        } catch (Exception e) {
          // it's OK for some nodes to fail
        }
      }

      return false;
    }

    public Path realPath() {
      return getRealpath(path);
    }

    @SuppressWarnings("unchecked")
    MetaData readMetaData() throws IOException {
      File file = getRealpath(getMetaPath()).toFile();
      if (file.exists()) {
        try (InputStream fis = new FileInputStream(file)) {
          return new MetaData((Map<String, Object>) Utils.fromJSON(fis));
        }
      }
      return null;
    }

    public FileDetails getDetails() {
      FileType type = getType(path, false);

      return new FileDetails() {
        @Override
        public MetaData getMetaData() {
          try {
            return readMetaData();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }

        @Override
        public Date getTimeStamp() {
          return new Date(realPath().toFile().lastModified());
        }

        @Override
        public boolean isDir() {
          return type == FileType.DIRECTORY;
        }

        @Override
        public long size() {
          return realPath().toFile().length();
        }

        @Override
        public String getSimpleName() {
          int idx = path.lastIndexOf('/');
          if (idx == -1) return path;
          return path.substring(idx + 1);
        }
      };
    }

    public void readData(Consumer<FileEntry> consumer) throws IOException {
      MetaData meta = readMetaData();
      try (InputStream is = new FileInputStream(realPath().toFile())) {
        consumer.accept(
            new FileEntry(null, meta, path) {
              @Override
              public InputStream getInputStream() {
                return is;
              }
            });
      }
    }
  }

  @Override
  public void put(FileEntry entry) throws IOException {
    FileInfo info = new FileInfo(entry.path);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Utils.writeJson(entry.getMetaData(), baos, true);
    byte[] bytes = baos.toByteArray();
    info.persistToFile(entry.buf, ByteBuffer.wrap(bytes, 0, bytes.length));
    distribute(info);
  }

  private void distribute(FileInfo info) {
    try {
      String dirName = info.path.substring(0, info.path.lastIndexOf('/'));
      coreContainer
          .getZkController()
          .getZkClient()
          .makePath(ZK_PACKAGESTORE + dirName, false, true);
      coreContainer
          .getZkController()
          .getZkClient()
          .create(
              ZK_PACKAGESTORE + info.path,
              info.getDetails().getMetaData().sha512.getBytes(UTF_8),
              CreateMode.PERSISTENT,
              true);
    } catch (Exception e) {
      throw new SolrException(SERVER_ERROR, "Unable to create an entry in ZK", e);
    }
    tmpFiles.put(info.path, info);

    List<String> nodes = FileStoreUtils.fetchAndShuffleRemoteLiveNodes(coreContainer);
    int i = 0;
    int FETCHFROM_SRC = 50;
    String myNodeName = coreContainer.getZkController().getNodeName();
    try {
      for (String node : nodes) {
        String baseUrl =
            coreContainer.getZkController().getZkStateReader().getBaseUrlV2ForNodeName(node);
        String getFrom = "";
        if (i < FETCHFROM_SRC) {
          // this is to protect very large clusters from overwhelming a single node
          // the first FETCHFROM_SRC nodes will be asked to fetch from this node.
          // it's there in  the memory now. So , it must be served fast
          getFrom += myNodeName;
        } else {
          if (i == FETCHFROM_SRC) {
            // This is just an optimization
            // at this point a bunch of nodes are already downloading from me
            // I'll wait for them to finish before asking other nodes to download from each other
            try {
              Thread.sleep(2 * 1000);
            } catch (Exception e) {
            }
          }
          // trying to avoid the thundering herd problem when there are a very large no:of nodes
          // others should try to fetch it from any node where it is available. By now,
          // almost FETCHFROM_SRC other nodes may have it
          getFrom += "*";
        }
        try (var solrClient =
            new Http2SolrClient.Builder(baseUrl)
                .withHttpClient(coreContainer.getDefaultHttpSolrClient())
                .build()) {
          var solrParams = new ModifiableSolrParams();
          solrParams.set("getFrom", getFrom);

          var solrRequest = new GenericSolrRequest(GET, "/node/files" + info.path, solrParams);
          // fire and forget
          solrClient.request(solrRequest);
        } catch (Exception e) {
          log.info("Node: {} failed to respond for file fetch notification", node, e);
          // ignore the exception
          // some nodes may be down or not responding
        }
        i++;
      }
    } finally {
      coreContainer
          .getUpdateShardHandler()
          .getUpdateExecutor()
          .submit(
              () -> {
                try {
                  Thread.sleep(10 * 1000);
                } finally {
                  tmpFiles.remove(info.path);
                }
                return null;
              });
    }
  }

  @Override
  public boolean fetch(String path, String from) {
    if (path == null || path.isEmpty()) return false;
    FileInfo f = new FileInfo(path);
    try {
      if (f.exists(true, false)) {
        return true;
      }
    } catch (IOException e) {
      log.error("Error fetching file ", e);
      return false;
    }

    if (from == null || "*".equals(from)) {
      log.info("Missing file in package store: {}", path);
      if (f.fetchFromAnyNode()) {
        log.info("Successfully downloaded : {}", path);
        return true;
      } else {
        log.info("Unable to download file : {}", path);
        return false;
      }

    } else {
      f.fetchFileFromNodeAndPersist(from);
    }

    return false;
  }

  @Override
  public void get(String path, Consumer<FileEntry> consumer, boolean fetchmissing)
      throws IOException {
    File file = getRealpath(path).toFile();
    String simpleName = file.getName();
    if (isMetaDataFile(simpleName)) {
      try (InputStream is = new FileInputStream(file)) {
        consumer.accept(
            new FileEntry(null, null, path) {
              // no metadata for metadata file
              @Override
              public InputStream getInputStream() {
                return is;
              }
            });
      }
      return;
    }

    new FileInfo(path).readData(consumer);
  }

  @Override
  public void syncToAllNodes(String path) throws IOException {
    FileInfo fi = new FileInfo(path);
    if (!fi.exists(true, false)) {
      throw new SolrException(BAD_REQUEST, "No such file : " + path);
    }
    fi.getFileData(true);
    distribute(fi);
  }

  @Override
  public List<FileDetails> list(String path, Predicate<String> predicate) {
    File file = getRealpath(path).toFile();
    List<FileDetails> fileDetails = new ArrayList<>();
    FileType type = getType(path, false);
    if (type == FileType.DIRECTORY) {
      file.list(
          (dir, name) -> {
            if (predicate == null || predicate.test(name)) {
              if (!isMetaDataFile(name)) {
                fileDetails.add(new FileInfo(path + "/" + name).getDetails());
              }
            }
            return false;
          });

    } else if (type == FileType.FILE) {
      fileDetails.add(new FileInfo(path).getDetails());
    }

    return fileDetails;
  }

  @Override
  public void delete(String path) {
    deleteLocal(path);
    List<String> nodes = FileStoreUtils.fetchAndShuffleRemoteLiveNodes(coreContainer);

    final var solrParams = new ModifiableSolrParams();
    solrParams.add("localDelete", "true");
    final var solrRequest = new GenericSolrRequest(DELETE, "/cluster/files" + path, solrParams);

    for (String node : nodes) {
      String baseUrl =
          coreContainer.getZkController().getZkStateReader().getBaseUrlV2ForNodeName(node);
      try (var solrClient =
          new Http2SolrClient.Builder(baseUrl)
              .withHttpClient(coreContainer.getDefaultHttpSolrClient())
              .build()) {
        // invoke delete command on all nodes asynchronously
        solrClient.requestAsync(solrRequest);
      }
    }
  }

  private void checkInZk(String path) {
    try {
      // fail if file exists
      if (coreContainer.getZkController().getZkClient().exists(ZK_PACKAGESTORE + path, true)) {
        throw new SolrException(BAD_REQUEST, "The path exist ZK, delete and retry");
      }

    } catch (SolrException se) {
      throw se;
    } catch (Exception e) {
      log.error("Could not connect to ZK", e);
    }
  }

  @Override
  public void deleteLocal(String path) {
    checkInZk(path);
    FileInfo f = new FileInfo(path);
    f.deleteFile();
  }

  @Override
  public void refresh(String path) {
    try {
      @SuppressWarnings({"rawtypes"})
      List l = null;
      try {
        l =
            coreContainer
                .getZkController()
                .getZkClient()
                .getChildren(ZK_PACKAGESTORE + path, null, true);
      } catch (KeeperException.NoNodeException e) {
        // does not matter
      }
      if (l != null && !l.isEmpty()) {
        @SuppressWarnings({"rawtypes"})
        List myFiles = list(path, s -> true);
        for (Object f : l) {
          // TODO: https://issues.apache.org/jira/browse/SOLR-15426
          // l should be a List<String> and myFiles should be a List<FileDetails>, so contains
          // should always return false!
          if (!myFiles.contains(f)) {
            log.info("{} does not exist locally, downloading.. ", f);
            fetch(path + "/" + f.toString(), "*");
          }
        }
      }
    } catch (Exception e) {
      log.error("Could not refresh files in {}", path, e);
    }
  }

  @Override
  public FileType getType(String path, boolean fetchMissing) {
    File file = getRealpath(path).toFile();
    if (!file.exists() && fetchMissing) {
      if (fetch(path, null)) {
        file = getRealpath(path).toFile();
      }
    }
    return _getFileType(file);
  }

  public static FileType _getFileType(File file) {
    if (!file.exists()) return FileType.NOFILE;
    if (file.isDirectory()) return FileType.DIRECTORY;
    return isMetaDataFile(file.getName()) ? FileType.METADATA : FileType.FILE;
  }

  public static boolean isMetaDataFile(String file) {
    return file.charAt(0) == '.' && file.endsWith(".json");
  }

  public static synchronized Path getFileStoreDirPath(Path solrHome) {
    var path = solrHome.resolve(ClusterFileStore.FILESTORE_DIRECTORY);
    if (!Files.exists(path)) {
      try {
        Files.createDirectories(path);
        log.info("Created filestore folder {}", path);
      } catch (IOException e) {
        throw new SolrException(SERVER_ERROR, "Failed creating 'filestore' folder in SOLR_HOME", e);
      }
    }
    return path;
  }

  private static String _getMetapath(String path) {
    int idx = path.lastIndexOf('/');
    return path.substring(0, idx + 1) + "." + path.substring(idx + 1) + ".json";
  }

  /** Internal API */
  public static void _persistToFile(Path solrHome, String path, ByteBuffer data, ByteBuffer meta)
      throws IOException {
    Path realpath = _getRealPath(path, solrHome);
    Files.createDirectories(realpath.getParent());
    Map<?, ?> m = (Map<?, ?>) Utils.fromJSON(meta.array(), meta.arrayOffset(), meta.limit());
    if (m == null || m.isEmpty()) {
      throw new SolrException(SERVER_ERROR, "invalid metadata , discarding : " + path);
    }

    Path metadataPath = _getRealPath(_getMetapath(path), solrHome);
    try (SeekableByteChannel channel =
        Files.newByteChannel(metadataPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
      channel.write(meta);
    }
    IOUtils.fsync(metadataPath, false);

    try (SeekableByteChannel channel =
        Files.newByteChannel(realpath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
      channel.write(data);
    }
    IOUtils.fsync(realpath, false);
  }

  @Override
  public Map<String, byte[]> getKeys() throws IOException {
    return _getKeys(solrHome);
  }

  // reads local keys file
  private static Map<String, byte[]> _getKeys(Path solrHome) throws IOException {
    Map<String, byte[]> result = new HashMap<>();
    Path keysDir = _getRealPath(ClusterFileStore.KEYS_DIR, solrHome);

    File[] keyFiles = keysDir.toFile().listFiles();
    if (keyFiles == null) return result;
    for (File keyFile : keyFiles) {
      if (keyFile.isFile() && !isMetaDataFile(keyFile.getName())) {
        result.put(keyFile.getName(), Files.readAllBytes(keyFile.toPath()));
      }
    }
    return result;
  }

  public static void deleteZKFileEntry(SolrZkClient client, String path) {
    try {
      client.delete(ZK_PACKAGESTORE + path, -1, true);
    } catch (KeeperException | InterruptedException e) {
      log.error("", e);
    }
  }
}
