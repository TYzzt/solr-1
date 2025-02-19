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
package org.apache.solr.cloud.api.collections;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.cloud.ZkConfigSetService;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.core.backup.repository.LocalFileSystemRepository;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This class implements the tests for local file-system integration for Solr backup/restore
 * capability. Note that the Solr backup/restore still requires a "shared" file-system. Its just
 * that in this case such file-system would be exposed via local file-system API.
 */
// Backups do checksum validation against a footer value not present in 'SimpleText'
@LuceneTestCase.SuppressCodecs({"SimpleText"})
public class TestLocalFSCloudBackupRestore extends AbstractCloudBackupRestoreTestCase {
  private static String backupLocation;

  @BeforeClass
  public static void setupClass() throws Exception {
    String solrXml = MiniSolrCloudCluster.DEFAULT_CLOUD_SOLR_XML;
    String poisoned =
        "    <repository  name=\""
            + TestLocalFSCloudBackupRestore.poisoned
            + "\" default=\"true\" "
            + "class=\"org.apache.solr.cloud.api.collections.TestLocalFSCloudBackupRestore$PoisonedRepository\"> \n"
            + "    </repository>\n";
    String local =
        "    <repository  name=\"local\" "
            + "class=\"org.apache.solr.core.backup.repository.LocalFileSystemRepository\"> \n"
            + "    </repository>\n";
    solrXml =
        solrXml.replace(
            "</solr>",
            "<backup>"
                + (random().nextBoolean() ? poisoned + local : local + poisoned)
                + "</backup>"
                + "</solr>");

    configureCluster(NUM_SHARDS) // nodes
        .addConfig(
            "conf1", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .addConfig(
            "confFaulty",
            TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .withSolrXml(solrXml)
        .configure();
    cluster
        .getZkClient()
        .delete(
            ZkConfigSetService.CONFIGS_ZKNODE + "/" + "confFaulty" + "/" + "solrconfig.xml",
            -1,
            true);

    boolean whitespacesInPath = random().nextBoolean();
    if (whitespacesInPath) {
      backupLocation = createTempDir("my backup").toAbsolutePath().toString();
    } else {
      backupLocation = createTempDir("mybackup").toAbsolutePath().toString();
    }
  }

  @Override
  public String getCollectionNamePrefix() {
    return "backuprestore";
  }

  @Override
  public String getBackupRepoName() {
    return "local";
  }

  @Override
  public String getBackupLocation() {
    return backupLocation;
  }

  @Override
  @Test
  public void test() throws Exception {
    super.test();

    CloudSolrClient solrClient = cluster.getSolrClient();

    errorBackup(solrClient);

    errorRestore(solrClient);
  }

  private void errorRestore(CloudSolrClient solrClient) throws SolrServerException, IOException {
    String backupName = BACKUPNAME_PREFIX + testSuffix;
    CollectionAdminRequest.Restore restore =
        CollectionAdminRequest.restoreCollection(getCollectionName() + "boo", backupName)
            .setLocation(backupLocation);
    if (random().nextBoolean()) {
      restore.setRepositoryName(poisoned);
    }
    try {
      restore.process(solrClient);
      fail("This request should have failed since omitting repo, picks up default poisoned.");
    } catch (SolrException ex) {
      assertEquals(ErrorCode.SERVER_ERROR.code, ex.code());
      assertTrue(ex.getMessage(), ex.getMessage().contains(poisoned));
    }
  }

  private void errorBackup(CloudSolrClient solrClient) throws SolrServerException, IOException {
    CollectionAdminRequest.Backup backup =
        CollectionAdminRequest.backupCollection(getCollectionName(), "poisonedbackup")
            .setLocation(getBackupLocation());
    if (random().nextBoolean()) {
      backup.setRepositoryName(poisoned);
    } // otherwise, we hit default

    try {
      backup.process(solrClient);
      fail("This request should have failed since omitting repo, picks up default poisoned.");
    } catch (SolrException ex) {
      assertEquals(ErrorCode.SERVER_ERROR.code, ex.code());
    }
  }

  private static final String poisoned = "poisoned";
  // let it go through collection handler, and break only when real thing is doing:
  // Restore/BackupCore
  public static class PoisonedRepository extends LocalFileSystemRepository {

    public PoisonedRepository() {
      super();
    }

    @Override
    public void copyFileFrom(Directory sourceDir, String fileName, URI dest) {
      throw new UnsupportedOperationException(poisoned);
    }

    @Override
    public void copyFileTo(URI sourceDir, String fileName, Directory dest) {
      throw new UnsupportedOperationException(poisoned);
    }

    @Override
    public IndexInput openInput(URI dirPath, String fileName, IOContext ctx) throws IOException {
      throw new UnsupportedOperationException(poisoned);
    }

    @Override
    public OutputStream createOutput(URI path) {
      throw new UnsupportedOperationException(poisoned);
    }
  }
}
